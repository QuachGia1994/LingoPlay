import Foundation

struct LocalLibraryItem: Identifiable, Codable, Sendable, Equatable {
    let id: UUID
    let title: String
    let duration: TimeInterval
    let createdAt: Date
    let sourceLanguage: String
    let targetLanguage: String
    let videoFileName: String
    let segments: [TranslationSegment]

    var languagePair: String {
        "\(sourceLanguage.uppercased()) → \(targetLanguage.uppercased())"
    }

    var durationText: String {
        MediaFormatting.duration(seconds: duration)
    }
}

actor LocalLibraryStore {
    private let fileManager = FileManager.default
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        self.encoder = encoder

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        self.decoder = decoder
    }

    func load() throws -> [LocalLibraryItem] {
        let root = try rootDirectory()
        let directories = try fileManager.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )
        return directories.compactMap { directory in
            let metadataURL = directory.appendingPathComponent("metadata.json")
            guard let data = try? Data(contentsOf: metadataURL),
                  let item = try? decoder.decode(LocalLibraryItem.self, from: data),
                  let videoURL = videoURL(for: item),
                  fileManager.fileExists(atPath: videoURL.path)
            else { return nil }
            return item
        }
        .sorted { $0.createdAt > $1.createdAt }
    }

    func save(
        media: LocalMediaItem,
        result: LocalDubMediaResult,
        translation: TranslationDocument?
    ) throws -> LocalLibraryItem {
        guard fileManager.fileExists(atPath: result.remuxedVideoURL.path) else {
            throw CocoaError(.fileNoSuchFile)
        }

        let id = UUID()
        let directory = try rootDirectory().appendingPathComponent(id.uuidString, isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = directory.appendingPathComponent("video.mp4")
        var success = false
        defer {
            if !success { try? fileManager.removeItem(at: directory) }
        }

        try fileManager.copyItem(at: result.remuxedVideoURL, to: destination)
        let values = try destination.resourceValues(forKeys: [.fileSizeKey])
        guard (values.fileSize ?? 0) > 0 else { throw CocoaError(.fileWriteUnknown) }

        let cleanTitle = (media.title as NSString)
            .deletingPathExtension
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let item = LocalLibraryItem(
            id: id,
            title: cleanTitle.isEmpty ? "Dubbed video" : cleanTitle,
            duration: result.duration,
            createdAt: Date(),
            sourceLanguage: translation?.sourceLanguage.isEmpty == false ? translation!.sourceLanguage : "und",
            targetLanguage: translation?.targetLanguage.isEmpty == false ? translation!.targetLanguage : "vi",
            videoFileName: destination.lastPathComponent,
            segments: translation?.segments ?? []
        )
        try encoder.encode(item).write(to: directory.appendingPathComponent("metadata.json"), options: [.atomic])
        success = true
        return item
    }

    func delete(_ item: LocalLibraryItem) throws {
        let directory = try itemDirectory(for: item)
        guard directory.deletingLastPathComponent().standardizedFileURL == (try rootDirectory()).standardizedFileURL else {
            throw CocoaError(.fileWriteNoPermission)
        }
        if fileManager.fileExists(atPath: directory.path) {
            try fileManager.removeItem(at: directory)
        }
    }

    func videoURL(for item: LocalLibraryItem) -> URL? {
        try? itemDirectory(for: item).appendingPathComponent(item.videoFileName)
    }

    func fileSize(for item: LocalLibraryItem) -> Int64 {
        guard let url = videoURL(for: item) else { return 0 }
        let values = try? url.resourceValues(forKeys: [.fileSizeKey])
        return Int64(values?.fileSize ?? 0)
    }

    func totalBytes(_ items: [LocalLibraryItem]) -> Int64 {
        items.reduce(into: 0) { partial, item in
            partial += fileSize(for: item)
        }
    }

    private func rootDirectory() throws -> URL {
        let base = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let root = base.appendingPathComponent("LingoPlay/Library", isDirectory: true)
        try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    private func itemDirectory(for item: LocalLibraryItem) throws -> URL {
        try rootDirectory().appendingPathComponent(item.id.uuidString, isDirectory: true)
    }
}
