import Foundation
import WhisperKit

struct ASRSegment: Identifiable, Sendable, Equatable {
    let id: Int
    let start: TimeInterval
    let end: TimeInterval
    let text: String
}

struct ASRTranscript: Sendable, Equatable {
    let language: String
    let text: String
    let segments: [ASRSegment]

    init(language: String, text: String, segments: [ASRSegment]) {
        self.language = language
        self.text = ASRFormatting.normalizedText(text)
        self.segments = ASRFormatting.normalizedSegments(segments)
    }
}

enum ASRState: Equatable {
    case idle
    case modelMissing
    case loadingModel
    case transcribing
    case completed(ASRTranscript)
    case failed(String)
}

enum ASRError: LocalizedError {
    case modelMissing
    case emptyTranscript

    var errorDescription: String? {
        switch self {
        case .modelMissing: "Speech model is not installed on this device."
        case .emptyTranscript: "No speech could be recognized in this audio."
        }
    }
}

struct ASRModelStore {
    private static let activePointerName = "active-model.txt"
    private let fileManager = FileManager.default

    func whisperModel() -> InstalledWhisperModel? {
        guard let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return nil }
        let root = support.appendingPathComponent("LingoPlay/Models/WhisperKit", isDirectory: true)
        let pointer = root.appendingPathComponent(Self.activePointerName)
        guard let relative = try? String(contentsOf: pointer, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines),
              !relative.isEmpty,
              !relative.hasPrefix("/"),
              !relative.contains("..")
        else { return nil }
        let folder = root.appendingPathComponent(relative, isDirectory: true).standardizedFileURL
        guard folder.path.hasPrefix(root.standardizedFileURL.path + "/"), coreModelLooksUsable(at: folder) else { return nil }
        return InstalledWhisperModel(modelFolder: folder, tokenizerFolder: root)
    }

    func coreModelLooksUsable(at folder: URL) -> Bool {
        guard let enumerator = fileManager.enumerator(at: folder, includingPropertiesForKeys: [.isRegularFileKey]) else { return false }
        let names = enumerator.compactMap { ($0 as? URL)?.lastPathComponent.lowercased() }
        return names.contains(where: { $0.contains("melspectrogram") })
            && names.contains(where: { $0.contains("audioencoder") })
            && names.contains(where: { $0.contains("textdecoder") })
    }
}

protocol OnDeviceSpeechRecognizer: Sendable {
    func transcribe(audioURL: URL, model: InstalledWhisperModel, sourceLanguageCode: String?) async throws -> ASRTranscript
}

actor WhisperKitSpeechRecognizer: OnDeviceSpeechRecognizer {
    func transcribe(audioURL: URL, model: InstalledWhisperModel, sourceLanguageCode: String? = nil) async throws -> ASRTranscript {
        let pipe = try await WhisperKit(
            modelFolder: model.modelFolder.path,
            tokenizerFolder: model.tokenizerFolder,
            verbose: false,
            prewarm: false,
            load: true,
            download: false
        )
        var decodeOptions = DecodingOptions(verbose: false)
        decodeOptions.language = sourceLanguageCode
        decodeOptions.detectLanguage = sourceLanguageCode == nil
        decodeOptions.usePrefillPrompt = true
        let results = try await pipe.transcribe(
            audioPath: audioURL.path,
            audioInputOptions: AudioInputOptions(audioLoadingMode: .incremental),
            decodeOptions: decodeOptions
        )
        let segments = results.flatMap(\.segments).enumerated().map { index, segment in
            ASRSegment(
                id: index,
                start: TimeInterval(segment.start),
                end: TimeInterval(segment.end),
                text: segment.text
            )
        }
        let text = results.map(\.text).joined(separator: " ")
        let language = results.first?.language ?? "und"
        let transcript = ASRTranscript(language: language, text: text, segments: segments)
        guard !transcript.text.isEmpty else { throw ASRError.emptyTranscript }
        return transcript
    }
}

enum ASRFormatting {
    static func normalizedText(_ text: String) -> String {
        text.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func normalizedSegments(_ segments: [ASRSegment]) -> [ASRSegment] {
        segments.compactMap { segment in
            let text = normalizedText(segment.text)
            guard !text.isEmpty else { return nil }
            let start = max(0, segment.start)
            let end = max(start, segment.end)
            return ASRSegment(id: segment.id, start: start, end: end, text: text)
        }
    }
}
