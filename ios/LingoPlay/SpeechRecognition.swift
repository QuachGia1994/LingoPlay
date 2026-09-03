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
    private let fileManager = FileManager.default

    func whisperModelFolder() -> URL? {
        guard let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return nil }
        let folder = support.appendingPathComponent("LingoPlay/Models/WhisperKit/current", isDirectory: true)
        guard modelLooksUsable(at: folder) else { return nil }
        return folder
    }

    func modelLooksUsable(at folder: URL) -> Bool {
        guard let enumerator = fileManager.enumerator(at: folder, includingPropertiesForKeys: [.isRegularFileKey]) else { return false }
        let names = enumerator.compactMap { ($0 as? URL)?.lastPathComponent.lowercased() }
        let hasCoreModels = names.contains(where: { $0.contains("melspectrogram") })
            && names.contains(where: { $0.contains("audioencoder") })
            && names.contains(where: { $0.contains("textdecoder") })
        let hasLocalTokenizer = names.contains("tokenizer.json")
            || names.contains("tokenizer_config.json")
            || (names.contains("vocab.json") && names.contains("merges.txt"))
        return hasCoreModels && hasLocalTokenizer
    }
}

protocol OnDeviceSpeechRecognizer: Sendable {
    func transcribe(audioURL: URL, modelFolder: URL) async throws -> ASRTranscript
}

actor WhisperKitSpeechRecognizer: OnDeviceSpeechRecognizer {
    func transcribe(audioURL: URL, modelFolder: URL) async throws -> ASRTranscript {
        let pipe = try await WhisperKit(
            modelFolder: modelFolder.path,
            verbose: false,
            prewarm: false,
            load: true,
            download: false
        )
        let results = try await pipe.transcribe(
            audioPath: audioURL.path,
            audioInputOptions: AudioInputOptions(audioLoadingMode: .incremental)
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
