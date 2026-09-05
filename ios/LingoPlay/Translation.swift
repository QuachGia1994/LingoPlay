import Foundation

enum TranslationEndpointConfiguration {
    static let infoDictionaryKey = "LingoPlayTranslationAPIBaseURL"
    static let productionBaseURLString = "https://lingoplay-api.kim-phong619.workers.dev"

    static func resolve(plistValue: String?) -> URL? {
        if let override = validHTTPSBaseURL(plistValue) {
            return override
        }
        return validHTTPSBaseURL(productionBaseURLString)
    }

    private static func validHTTPSBaseURL(_ value: String?) -> URL? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            !trimmed.isEmpty,
            let url = URL(string: trimmed),
            url.scheme?.lowercased() == "https",
            url.host != nil,
            url.query == nil,
            url.fragment == nil
        else {
            return nil
        }
        return url
    }
}

enum TranslationTextPolicy {
    private static let commonEnglishWords: Set<String> = [
        "a", "and", "are", "can", "do", "have", "how", "i", "in", "is", "it",
        "me", "of", "please", "that", "the", "this", "to", "we", "what", "you",
    ]

    static func speechText(_ text: String) -> String {
        let withoutAngles = text.replacingOccurrences(
            of: "<[^>\\r\\n]{1,96}>",
            with: " ",
            options: .regularExpression
        )
        let withoutCues = withoutAngles.replacingOccurrences(
            of: "\\[[^\\r\\n\\]]{1,96}\\]",
            with: " ",
            options: .regularExpression
        )
        return withoutCues
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func sourceLanguage(reported: String, text: String) -> String {
        let cleaned = speechText(text)
        let totalLetters = cleaned.filter { $0.isLetter }.count
        let latinLetters = cleaned.filter { character in
            character.unicodeScalars.allSatisfy { scalar in
                (65...90).contains(Int(scalar.value)) || (97...122).contains(Int(scalar.value))
            }
        }.count
        let englishHits = cleaned
            .lowercased()
            .split(whereSeparator: { !$0.isLetter && $0 != "'" })
            .count { commonEnglishWords.contains(String($0)) }
        let stronglyEnglish = latinLetters >= 20 &&
            totalLetters > 0 &&
            Double(latinLetters) / Double(totalLetters) >= 0.75 &&
            englishHits >= 2
        if stronglyEnglish { return "en" }
        let normalized = reported
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .split(separator: "-")
            .first
            .map(String.init) ?? ""
        return normalized.isEmpty ? "und" : normalized
    }
}

struct TranslationSourceSegment: Identifiable, Sendable, Equatable, Codable {
    let id: String
    let startMs: Int
    let endMs: Int
    let text: String
}

struct TranslationSegment: Identifiable, Sendable, Equatable, Codable {
    let id: String
    let startMs: Int
    let endMs: Int
    let sourceText: String
    let translatedText: String
}

struct TranslationDocument: Sendable, Equatable {
    let sourceLanguage: String
    let targetLanguage: String
    let segments: [TranslationSegment]
    let mode: TranslationMode

    init(
        sourceLanguage: String,
        targetLanguage: String,
        segments: [TranslationSegment],
        mode: TranslationMode = .cloud
    ) {
        self.sourceLanguage = sourceLanguage
        self.targetLanguage = targetLanguage
        self.segments = segments
        self.mode = mode
    }

    var translatedText: String {
        segments.map(\.translatedText).joined(separator: " ")
    }
}

enum TranslationState: Equatable {
    case idle
    case endpointMissing
    case translating(batch: Int, totalBatches: Int)
    case completed(TranslationDocument)
    case failed(String)
}

enum TranslationError: LocalizedError {
    case endpointMissing
    case noSpeechSegments
    case invalidResponse
    case server(Int, String)

    var errorDescription: String? {
        switch self {
        case .endpointMissing:
            "Translation backend is not configured."
        case .noSpeechSegments:
            "No translatable speech remains after removing non-speech markers."
        case .invalidResponse:
            "Translation backend returned an invalid response."
        case let .server(status, message):
            "Translation failed (\(status)): \(message)"
        }
    }
}

struct TranslationService: Sendable {
    static let requestTimeoutSeconds: TimeInterval = 60

    private struct RequestBody: Codable {
        let sourceLanguage: String
        let targetLanguage: String
        let segments: [TranslationSourceSegment]
    }

    private struct ResponseTranslation: Codable {
        let id: String
        let text: String
    }

    private struct ResponseBody: Codable {
        let sourceLanguage: String
        let targetLanguage: String
        let translations: [ResponseTranslation]
    }

    private struct ErrorBody: Codable {
        let error: String
    }

    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func translate(
        transcript: ASRTranscript,
        targetLanguage: String,
        endpoint: URL,
        progress: @MainActor @Sendable (Int, Int) -> Void
    ) async throws -> TranslationDocument {
        let sourceSegments = Self.makeSourceSegments(transcript)
        guard !sourceSegments.isEmpty else { throw TranslationError.noSpeechSegments }
        let sourceText = sourceSegments.map(\.text).joined(separator: " ")
        let sourceLanguage = TranslationTextPolicy.sourceLanguage(
            reported: transcript.language,
            text: sourceText
        )
        let targetBaseLanguage = targetLanguage
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .split(separator: "-")
            .first
            .map(String.init) ?? ""
        if sourceLanguage == targetBaseLanguage {
            let copied = sourceSegments.map { source in
                TranslationSegment(
                    id: source.id,
                    startMs: source.startMs,
                    endMs: source.endMs,
                    sourceText: source.text,
                    translatedText: source.text
                )
            }
            await progress(copied.count, copied.count)
            return TranslationDocument(
                sourceLanguage: sourceLanguage,
                targetLanguage: targetLanguage,
                segments: copied,
                mode: .cloud
            )
        }
        let batches = Self.makeBatches(sourceSegments)
        var translatedByID: [String: String] = [:]

        for (index, batch) in batches.enumerated() {
            let response = try await translateBatch(
                batch,
                sourceLanguage: sourceLanguage,
                targetLanguage: targetLanguage,
                endpoint: endpoint
            )
            for item in response.translations {
                translatedByID[item.id] = item.text
            }
            await progress(index + 1, batches.count)
        }

        let translated = try sourceSegments.map { source -> TranslationSegment in
            let translatedText = TranslationTextPolicy.speechText(translatedByID[source.id] ?? "")
            guard !translatedText.isEmpty else {
                throw TranslationError.invalidResponse
            }
            return TranslationSegment(
                id: source.id,
                startMs: source.startMs,
                endMs: source.endMs,
                sourceText: source.text,
                translatedText: translatedText
            )
        }

        return TranslationDocument(
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            segments: translated,
            mode: .cloud
        )
    }

    private func translateBatch(
        _ segments: [TranslationSourceSegment],
        sourceLanguage: String,
        targetLanguage: String,
        endpoint: URL
    ) async throws -> ResponseBody {
        let body = RequestBody(
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            segments: segments
        )
        var request = URLRequest(url: endpoint.appendingPathComponent("v1/translate"))
        request.httpMethod = "POST"
        request.timeoutInterval = Self.requestTimeoutSeconds
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw TranslationError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            let message = (try? JSONDecoder().decode(ErrorBody.self, from: data).error) ?? "server_error"
            throw TranslationError.server(http.statusCode, message)
        }

        let decoded = try JSONDecoder().decode(ResponseBody.self, from: data)
        guard decoded.translations.count == segments.count else { throw TranslationError.invalidResponse }
        return decoded
    }

    static func makeSourceSegments(_ transcript: ASRTranscript) -> [TranslationSourceSegment] {
        let timed = transcript.segments.enumerated().compactMap { index, segment -> TranslationSourceSegment? in
            let text = TranslationTextPolicy.speechText(segment.text)
            guard !text.isEmpty else { return nil }
            let startMs = max(0, Int((segment.start * 1_000).rounded()))
            let endMs = max(startMs + 1, Int((segment.end * 1_000).rounded()))
            return TranslationSourceSegment(
                id: "s\(index)",
                startMs: startMs,
                endMs: endMs,
                text: text
            )
        }
        if !timed.isEmpty { return timed }

        let fallback = TranslationTextPolicy.speechText(transcript.text)
        return fallback.isEmpty ? [] : [TranslationSourceSegment(id: "s0", startMs: 0, endMs: 1, text: fallback)]
    }

    static func makeBatches(_ segments: [TranslationSourceSegment]) -> [[TranslationSourceSegment]] {
        var result: [[TranslationSourceSegment]] = []
        var current: [TranslationSourceSegment] = []
        var currentChars = 0

        for segment in segments {
            let wouldOverflowCount = current.count >= 80
            let wouldOverflowChars = currentChars + segment.text.count > 10_000
            if !current.isEmpty && (wouldOverflowCount || wouldOverflowChars) {
                result.append(current)
                current = []
                currentChars = 0
            }
            current.append(segment)
            currentChars += segment.text.count
        }

        if !current.isEmpty { result.append(current) }
        return result
    }
}
