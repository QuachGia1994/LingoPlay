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
    case invalidResponse
    case server(Int, String)

    var errorDescription: String? {
        switch self {
        case .endpointMissing:
            "Translation backend is not configured."
        case .invalidResponse:
            "Translation backend returned an invalid response."
        case let .server(status, message):
            "Translation failed (\(status)): \(message)"
        }
    }
}

struct TranslationService: Sendable {
    private struct RequestSegment: Codable {
        let id: String
        let startMs: Int
        let endMs: Int
        let text: String
    }

    private struct RequestBody: Codable {
        let sourceLanguage: String
        let targetLanguage: String
        let segments: [RequestSegment]
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
        let sourceLanguage = normalizeLanguage(transcript.language)
        let sourceSegments = Self.makeSourceSegments(transcript)
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
            guard let translatedText = translatedByID[source.id], !translatedText.isEmpty else {
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
            segments: translated
        )
    }

    private func translateBatch(
        _ segments: [RequestSegment],
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

    private func normalizeLanguage(_ language: String) -> String {
        let trimmed = language.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "und" : trimmed
    }

    private static func makeSourceSegments(_ transcript: ASRTranscript) -> [RequestSegment] {
        if !transcript.segments.isEmpty {
            return transcript.segments.enumerated().map { index, segment in
                let startMs = max(0, Int((segment.start * 1_000).rounded()))
                let endMs = max(startMs + 1, Int((segment.end * 1_000).rounded()))
                return RequestSegment(
                    id: "s\(index)",
                    startMs: startMs,
                    endMs: endMs,
                    text: segment.text
                )
            }
        }

        return [RequestSegment(id: "s0", startMs: 0, endMs: 1, text: transcript.text)]
    }

    private static func makeBatches(_ segments: [RequestSegment]) -> [[RequestSegment]] {
        var result: [[RequestSegment]] = []
        var current: [RequestSegment] = []
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
