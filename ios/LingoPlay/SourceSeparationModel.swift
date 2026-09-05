import Foundation
@preconcurrency import SWCompression

enum SourceSeparationManifest {
    static let version = "spleeter-2stems-fp16-c6c5c430"
    static let archiveRoot = "sherpa-onnx-spleeter-2stems-fp16"
    static let archiveName = archiveRoot + ".tar.bz2"
    static let archiveBytes: Int64 = 35_271_738
    static let archiveSHA256 = "c6c5c4307673bc6813ddf58d4efdff57c26d2dfc3f25b05c7a32db453d70aca6"
    static let vocalsName = "vocals.fp16.onnx"
    static let accompanimentName = "accompaniment.fp16.onnx"
    static let archiveURL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/\(archiveName)"
    static let archiveSpec = PinnedDownloadSpec(
        name: archiveName, url: archiveURL, bytes: archiveBytes, sha256: archiveSHA256
    )
}

enum SourceSeparationArchivePolicy {
    static let maximumEntries = 16
    static let maximumUncompressedBytes = 96 * 1024 * 1024
    static let maximumEntryBytes = 48 * 1024 * 1024
    private static let allowed = Set([SourceSeparationManifest.vocalsName, SourceSeparationManifest.accompanimentName])

    static func relativePath(for entryName: String) -> String? {
        guard !entryName.isEmpty, entryName.count <= 256,
              !entryName.contains("\0"), !entryName.contains("\\")
        else { return nil }
        if entryName == SourceSeparationManifest.archiveRoot { return "" }
        let prefix = SourceSeparationManifest.archiveRoot + "/"
        guard entryName.hasPrefix(prefix) else { return nil }
        let relative = String(entryName.dropFirst(prefix.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !relative.isEmpty else { return "" }
        guard !relative.contains("/"), relative != ".", relative != "..", allowed.contains(relative) else { return nil }
        return relative
    }
}

struct InstalledSourceSeparationModel: Sendable, Equatable {
    let vocalsURL: URL
    let accompanimentURL: URL
}

struct SourceSeparationModelStore {
    private static let activePointerName = "active-model.txt"
    private let fileManager = FileManager.default

    func model() -> InstalledSourceSeparationModel? {
        guard let root = try? rootURL(create: false),
              let version = try? String(contentsOf: root.appendingPathComponent(Self.activePointerName), encoding: .utf8)
                .trimmingCharacters(in: .whitespacesAndNewlines),
              version == SourceSeparationManifest.version
        else { return nil }
        return validatedModel(at: root.appendingPathComponent(version, isDirectory: true))
    }

    func validatedModel(at directory: URL) -> InstalledSourceSeparationModel? {
        let marker = directory.appendingPathComponent("pack.sha256")
        guard (try? String(contentsOf: marker, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)) == SourceSeparationManifest.archiveSHA256
        else { return nil }
        let vocals = directory.appendingPathComponent(SourceSeparationManifest.vocalsName)
        let accompaniment = directory.appendingPathComponent(SourceSeparationManifest.accompanimentName)
        guard isNonEmptyFile(vocals), isNonEmptyFile(accompaniment) else { return nil }
        return InstalledSourceSeparationModel(vocalsURL: vocals, accompanimentURL: accompaniment)
    }

    func rootURL(create: Bool) throws -> URL {
        guard let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            throw PinnedDownloadError.activationFailed
        }
        let root = support.appendingPathComponent("LingoPlay/Models/SourceSeparation", isDirectory: true)
        if create { try fileManager.createDirectory(at: root, withIntermediateDirectories: true) }
        return root
    }

    private func isNonEmptyFile(_ url: URL) -> Bool {
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]) else { return false }
        return values.isRegularFile == true && (values.fileSize ?? 0) > 0
    }
}

actor SourceSeparationModelInstaller {
    private static let activePointerName = "active-model.txt"
    private static let storageSafetyMarginBytes: Int64 = 64 * 1024 * 1024
    private let fileManager = FileManager.default
    private let store = SourceSeparationModelStore()

    func state() -> ASRModelInstallState {
        guard let model = store.model() else { return .notInstalled }
        let bytes = (PinnedFileIntegrity.fileSize(model.vocalsURL) ?? 0) +
            (PinnedFileIntegrity.fileSize(model.accompanimentURL) ?? 0)
        return .installed(bytes: bytes)
    }

    func install(
        wifiOnly: Bool,
        progress: @escaping @Sendable (Double) async -> Void
    ) async throws -> InstalledSourceSeparationModel {
        if wifiOnly {
            guard await ModelNetworkPolicy.isUsingWiFi() else {
                throw NSError(
                    domain: "LingoPlay.SourceSeparation", code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "Connect to Wi-Fi or disable ‘Download models on Wi-Fi only’ before installing Clean Background."]
                )
            }
        }
        if let installed = store.model() {
            await progress(1)
            return installed
        }

        let root = try store.rootURL(create: true)
        try ensureStorage(at: root)
        let archive = try await PinnedModelDownload.downloadVerified(
            spec: SourceSeparationManifest.archiveSpec, root: root
        ) { bytes in
            await progress(Double(bytes) / Double(SourceSeparationManifest.archiveBytes))
        }
        try Task.checkCancellation()

        let staging = root.appendingPathComponent(SourceSeparationManifest.version + ".staging", isDirectory: true)
        let versionDirectory = root.appendingPathComponent(SourceSeparationManifest.version, isDirectory: true)
        try? fileManager.removeItem(at: staging)
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: true)
        do {
            try extractVerifiedArchive(archive, to: staging)
            try SourceSeparationManifest.archiveSHA256.write(
                to: staging.appendingPathComponent("pack.sha256"), atomically: true, encoding: .utf8
            )
            guard store.validatedModel(at: staging) != nil else { throw PinnedDownloadError.integrityFailed }
            try? fileManager.removeItem(at: versionDirectory)
            try fileManager.moveItem(at: staging, to: versionDirectory)
            try writeActivePointer(root: root)
            try? fileManager.removeItem(at: archive)
            guard let activated = store.model() else { throw PinnedDownloadError.activationFailed }
            await progress(1)
            return activated
        } catch {
            try? fileManager.removeItem(at: staging)
            throw error
        }
    }

    func deleteInstalledModel() throws {
        let root = try store.rootURL(create: false)
        if fileManager.fileExists(atPath: root.path) { try fileManager.removeItem(at: root) }
    }

    private func extractVerifiedArchive(_ archive: URL, to staging: URL) throws {
        try Task.checkCancellation()
        let compressed = try Data(contentsOf: archive, options: [.mappedIfSafe])
        guard compressed.count == Int(SourceSeparationManifest.archiveBytes) else { throw PinnedDownloadError.integrityFailed }
        let tarData = try BZip2.decompress(data: compressed)
        try Task.checkCancellation()
        guard tarData.count <= SourceSeparationArchivePolicy.maximumUncompressedBytes else { throw PinnedDownloadError.integrityFailed }
        let entries = try TarContainer.open(container: tarData)
        guard entries.count <= SourceSeparationArchivePolicy.maximumEntries else { throw PinnedDownloadError.integrityFailed }
        let stagingPrefix = staging.standardizedFileURL.path + "/"
        var extractedBytes = 0
        for entry in entries {
            try Task.checkCancellation()
            let isDirectory = entry.info.type == .directory
            let isRegular = entry.info.type == .regular || entry.info.type == .contiguous
            guard isDirectory || isRegular,
                  let relative = SourceSeparationArchivePolicy.relativePath(for: entry.info.name)
            else { throw PinnedDownloadError.integrityFailed }
            guard !relative.isEmpty else { continue }
            let size = entry.data?.count ?? 0
            guard size <= SourceSeparationArchivePolicy.maximumEntryBytes else { throw PinnedDownloadError.integrityFailed }
            let output = staging.appendingPathComponent(relative).standardizedFileURL
            guard output.path.hasPrefix(stagingPrefix) else { throw PinnedDownloadError.integrityFailed }
            if isDirectory { continue }
            guard let data = entry.data, entry.info.size == data.count else { throw PinnedDownloadError.integrityFailed }
            extractedBytes += data.count
            guard extractedBytes <= SourceSeparationArchivePolicy.maximumUncompressedBytes else { throw PinnedDownloadError.integrityFailed }
            try data.write(to: output, options: [.atomic])
        }
    }

    private func ensureStorage(at root: URL) throws {
        let values = try root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        let required = SourceSeparationManifest.archiveBytes +
            Int64(SourceSeparationArchivePolicy.maximumUncompressedBytes) + Self.storageSafetyMarginBytes
        if let available = values.volumeAvailableCapacityForImportantUsage, available < required {
            throw NSError(
                domain: "LingoPlay.SourceSeparation", code: 2,
                userInfo: [NSLocalizedDescriptionKey: "Not enough free storage to install Clean Background."]
            )
        }
    }

    private func writeActivePointer(root: URL) throws {
        let pointer = root.appendingPathComponent(Self.activePointerName)
        let temporary = root.appendingPathComponent(Self.activePointerName + ".tmp")
        try SourceSeparationManifest.version.write(to: temporary, atomically: true, encoding: .utf8)
        if fileManager.fileExists(atPath: pointer.path) {
            _ = try fileManager.replaceItemAt(pointer, withItemAt: temporary)
        } else {
            try fileManager.moveItem(at: temporary, to: pointer)
        }
    }
}
