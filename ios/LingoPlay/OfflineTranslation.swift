@preconcurrency import Foundation
@preconcurrency import MLKitTranslate

enum OfflineTranslationError: LocalizedError {
    case unsupportedLanguage(String)
    case builtInLanguage(String)
    case missingModels([String])
    case emptyResponse
    case timeout

    var errorDescription: String? {
        switch self {
        case .unsupportedLanguage(let code):
            "Offline translation does not support \(code.uppercased())."
        case .builtInLanguage(let code):
            "\(OfflineTranslationLanguagePolicy.displayName(code)) translation support is built in and has no remote model."
        case .missingModels(let codes):
            "Offline translation model missing: \(codes.map(OfflineTranslationLanguagePolicy.displayName).joined(separator: ", ")). Install it in Settings; LingoPlay will not switch to cloud automatically."
        case .emptyResponse:
            "Offline translation returned empty text."
        case .timeout:
            "Offline translation timed out."
        }
    }
}

enum OfflineTranslationLanguagePolicy {
    static let supportedCodes = ["en", "vi", "ja", "zh"]

    static func displayName(_ code: String) -> String {
        switch normalized(code) {
        case "en": "English"
        case "vi": "Vietnamese"
        case "ja": "Japanese"
        case "zh": "Chinese"
        default: code.uppercased()
        }
    }

    static func language(_ code: String) throws -> TranslateLanguage {
        switch normalized(code) {
        case "en": .english
        case "vi": .vietnamese
        case "ja": .japanese
        case "zh": .chinese
        default: throw OfflineTranslationError.unsupportedLanguage(code)
        }
    }

    static func requiredModelCodes(sourceLanguage: String, targetLanguage: String) throws -> Set<String> {
        let source = normalized(sourceLanguage)
        let target = normalized(targetLanguage)
        if source == target { return [] }
        _ = try language(source)
        _ = try language(target)
        return Set([source, target].filter { $0 != "en" })
    }

    static func remoteModel(_ code: String) throws -> TranslateRemoteModel {
        let normalizedCode = normalized(code)
        guard normalizedCode != "en" else { throw OfflineTranslationError.builtInLanguage(code) }
        return TranslateRemoteModel.translateRemoteModel(language: try language(normalizedCode))
    }

    private static func normalized(_ code: String) -> String {
        code
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .split(separator: "-")
            .first
            .map(String.init) ?? ""
    }
}

private final class TranslationCallbackGate<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Value, Error>?
    private var pendingResult: Result<Value, Error>?
    private var completed = false

    func install(_ continuation: CheckedContinuation<Value, Error>) {
        lock.lock()
        if let pendingResult {
            self.pendingResult = nil
            completed = true
            lock.unlock()
            continuation.resume(with: pendingResult)
        } else if completed {
            lock.unlock()
            continuation.resume(throwing: CancellationError())
        } else {
            self.continuation = continuation
            lock.unlock()
        }
    }

    func finish(_ result: Result<Value, Error>) {
        lock.lock()
        if completed {
            lock.unlock()
        } else if let continuation {
            self.continuation = nil
            completed = true
            lock.unlock()
            continuation.resume(with: result)
        } else if pendingResult == nil {
            pendingResult = result
            lock.unlock()
        } else {
            lock.unlock()
        }
    }
}

@MainActor
final class OfflineTranslationModelManager {
    func downloadedCodes() -> Set<String> {
        let downloaded = ModelManager.modelManager().downloadedTranslateModels
        var result: Set<String> = ["en"]
        result.formUnion(OfflineTranslationLanguagePolicy.supportedCodes.filter { code in
            guard code != "en", let model = try? OfflineTranslationLanguagePolicy.remoteModel(code) else { return false }
            return downloaded.contains(model)
        })
        return result
    }

    func download(_ code: String, wifiOnly: Bool) async throws {
        guard code != "en" else { return }
        let language = try OfflineTranslationLanguagePolicy.language(code)
        let companion: TranslateLanguage = .english
        let translator = Translator.translator(
            options: TranslatorOptions(sourceLanguage: language, targetLanguage: companion)
        )
        let conditions = ModelDownloadConditions(
            allowsCellularAccess: !wifiOnly,
            allowsBackgroundDownloading: true
        )
        let gate = TranslationCallbackGate<Void>()

        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                gate.install(continuation)
                translator.downloadModelIfNeeded(with: conditions) { error in
                    _ = translator
                    if let error {
                        gate.finish(.failure(error))
                    } else {
                        gate.finish(.success(()))
                    }
                }
                Task {
                    try? await Task.sleep(nanoseconds: 300_000_000_000)
                    gate.finish(.failure(OfflineTranslationError.timeout))
                }
            }
        } onCancel: {
            gate.finish(.failure(CancellationError()))
        }
    }

    func delete(_ code: String) async throws {
        guard code != "en" else { return }
        let model = try OfflineTranslationLanguagePolicy.remoteModel(code)
        let gate = TranslationCallbackGate<Void>()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                gate.install(continuation)
                ModelManager.modelManager().deleteDownloadedModel(model) { error in
                    if let error {
                        gate.finish(.failure(error))
                    } else {
                        gate.finish(.success(()))
                    }
                }
                Task {
                    try? await Task.sleep(nanoseconds: 60_000_000_000)
                    gate.finish(.failure(OfflineTranslationError.timeout))
                }
            }
        } onCancel: {
            gate.finish(.failure(CancellationError()))
        }
    }
}

@MainActor
final class OfflineTranslationService {
    private let modelManager: OfflineTranslationModelManager

    init(modelManager: OfflineTranslationModelManager = OfflineTranslationModelManager()) {
        self.modelManager = modelManager
    }

    func translate(
        transcript: ASRTranscript,
        targetLanguage: String,
        progress: @MainActor @Sendable (Int, Int) -> Void
    ) async throws -> TranslationDocument {
        let sourceSegments = TranslationService.makeSourceSegments(transcript)
        guard !sourceSegments.isEmpty else { throw OfflineTranslationError.emptyResponse }

        let sourceText = sourceSegments.map(\.text).joined(separator: " ")
        let sourceLanguage = TranslationTextPolicy.sourceLanguage(
            reported: transcript.language,
            text: sourceText
        )
        let required = try OfflineTranslationLanguagePolicy.requiredModelCodes(
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage
        )

        if required.isEmpty {
            let copied = sourceSegments.map { source in
                TranslationSegment(
                    id: source.id,
                    startMs: source.startMs,
                    endMs: source.endMs,
                    sourceText: source.text,
                    translatedText: source.text
                )
            }
            progress(copied.count, copied.count)
            return TranslationDocument(
                sourceLanguage: sourceLanguage,
                targetLanguage: targetLanguage,
                segments: copied,
                mode: .offline
            )
        }

        let missing = required.subtracting(modelManager.downloadedCodes()).sorted()
        guard missing.isEmpty else { throw OfflineTranslationError.missingModels(missing) }

        let translator = Translator.translator(
            options: TranslatorOptions(
                sourceLanguage: try OfflineTranslationLanguagePolicy.language(sourceLanguage),
                targetLanguage: try OfflineTranslationLanguagePolicy.language(targetLanguage)
            )
        )
        var translated: [TranslationSegment] = []
        translated.reserveCapacity(sourceSegments.count)

        for (index, source) in sourceSegments.enumerated() {
            try Task.checkCancellation()
            let text = try await translate(source.text, using: translator)
            let cleaned = TranslationTextPolicy.speechText(text)
            guard !cleaned.isEmpty else { throw OfflineTranslationError.emptyResponse }
            translated.append(
                TranslationSegment(
                    id: source.id,
                    startMs: source.startMs,
                    endMs: source.endMs,
                    sourceText: source.text,
                    translatedText: cleaned
                )
            )
            progress(index + 1, sourceSegments.count)
        }

        return TranslationDocument(
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            segments: translated,
            mode: .offline
        )
    }

    private func translate(_ text: String, using translator: Translator) async throws -> String {
        let gate = TranslationCallbackGate<String>()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                gate.install(continuation)
                translator.translate(text) { translatedText, error in
                    if let error {
                        gate.finish(.failure(error))
                    } else if let translatedText {
                        gate.finish(.success(translatedText))
                    } else {
                        gate.finish(.failure(OfflineTranslationError.emptyResponse))
                    }
                }
                Task {
                    try? await Task.sleep(nanoseconds: 60_000_000_000)
                    gate.finish(.failure(OfflineTranslationError.timeout))
                }
            }
        } onCancel: {
            gate.finish(.failure(CancellationError()))
        }
    }
}
