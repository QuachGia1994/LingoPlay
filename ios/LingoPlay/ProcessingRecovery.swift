import Foundation

struct ProcessingRecoveryCheckpoint: Sendable, Equatable {
    let media: LocalMediaItem
    let preparedAudioURL: URL?
    let config: ProcessingConfig?

    var canResumeFromAudio: Bool {
        guard let preparedAudioURL else { return false }
        return FileManager.default.fileExists(atPath: preparedAudioURL.path)
    }
}

actor ProcessingRecoveryStore {
    private struct Record: Codable {
        let mediaID: UUID
        let mediaPath: String
        let title: String
        let duration: TimeInterval
        let fileSizeBytes: Int64
        let hasAudioTrack: Bool
        let preparedAudioPath: String?
        let sourceLanguage: String?
        let targetLanguage: String?
        let preferredVoiceIdentifier: String?
        let dubbingMode: String?
        let subtitleMode: String?
        let updatedAtEpochMs: Int64
    }

    private let fileManager = FileManager.default

    func load() -> ProcessingRecoveryCheckpoint? {
        guard let file = try? checkpointFile(), fileManager.fileExists(atPath: file.path),
              let data = try? Data(contentsOf: file),
              let record = try? JSONDecoder().decode(Record.self, from: data)
        else { return nil }

        let mediaURL = URL(fileURLWithPath: record.mediaPath)
        guard fileManager.fileExists(atPath: mediaURL.path) else {
            try? fileManager.removeItem(at: file)
            return nil
        }
        let audio = record.preparedAudioPath
            .map { URL(fileURLWithPath: $0) }
            .flatMap { fileManager.fileExists(atPath: $0.path) ? $0 : nil }
        return ProcessingRecoveryCheckpoint(
            media: LocalMediaItem(
                id: record.mediaID,
                localURL: mediaURL,
                title: record.title,
                duration: record.duration,
                fileSizeBytes: record.fileSizeBytes,
                hasAudioTrack: record.hasAudioTrack
            ),
            preparedAudioURL: audio,
            config: config(from: record)
        )
    }

    func save(
        media: LocalMediaItem,
        preparedAudioURL: URL? = nil,
        config: ProcessingConfig? = nil
    ) throws {
        guard fileManager.fileExists(atPath: media.localURL.path) else { return }
        let record = Record(
            mediaID: media.id,
            mediaPath: media.localURL.path,
            title: media.title,
            duration: media.duration,
            fileSizeBytes: media.fileSizeBytes,
            hasAudioTrack: media.hasAudioTrack,
            preparedAudioPath: preparedAudioURL.flatMap { fileManager.fileExists(atPath: $0.path) ? $0.path : nil },
            sourceLanguage: config?.sourceLanguage.rawValue,
            targetLanguage: config?.targetLanguage.rawValue,
            preferredVoiceIdentifier: config?.preferredVoiceIdentifier,
            dubbingMode: config?.dubbingMode.rawValue,
            subtitleMode: config?.subtitleMode.rawValue,
            updatedAtEpochMs: Int64(Date().timeIntervalSince1970 * 1_000)
        )
        let data = try JSONEncoder().encode(record)
        let file = try checkpointFile()
        try fileManager.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: file, options: .atomic)
    }

    func clear(deleteMedia: Bool) {
        let checkpoint = load()
        if let file = try? checkpointFile() { try? fileManager.removeItem(at: file) }
        if let audio = checkpoint?.preparedAudioURL { try? fileManager.removeItem(at: audio) }
        if deleteMedia, let media = checkpoint?.media, isOwnedImportedMedia(media.localURL) {
            try? fileManager.removeItem(at: media.localURL)
        }
    }

    func deleteOwnedImportedMedia(_ media: LocalMediaItem?) {
        guard let media, isOwnedImportedMedia(media.localURL) else { return }
        try? fileManager.removeItem(at: media.localURL)
    }

    private func config(from record: Record) -> ProcessingConfig? {
        guard let sourceRaw = record.sourceLanguage,
              let targetRaw = record.targetLanguage,
              let modeRaw = record.dubbingMode,
              let source = SourceLanguageChoice(rawValue: sourceRaw),
              let target = TargetLanguageChoice(rawValue: targetRaw),
              let mode = DubbingModePreset(rawValue: modeRaw)
        else { return nil }
        return ProcessingConfig(
            sourceLanguage: source,
            targetLanguage: target,
            preferredVoiceIdentifier: record.preferredVoiceIdentifier,
            dubbingMode: mode,
            subtitleMode: record.subtitleMode.flatMap(SubtitleMode.init(rawValue:)) ?? .bilingual
        )
    }

    private func checkpointFile() throws -> URL {
        guard let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            throw CocoaError(.fileNoSuchFile)
        }
        return support
            .appendingPathComponent("LingoPlay/Recovery", isDirectory: true)
            .appendingPathComponent("processing.json")
    }

    private func isOwnedImportedMedia(_ url: URL) -> Bool {
        guard let caches = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first else { return false }
        let root = caches
            .appendingPathComponent("LingoPlay/ImportedMedia", isDirectory: true)
            .standardizedFileURL.path + "/"
        return url.standardizedFileURL.path.hasPrefix(root)
    }
}
