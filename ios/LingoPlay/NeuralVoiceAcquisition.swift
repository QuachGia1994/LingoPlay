@preconcurrency import CryptoKit
import Foundation
@preconcurrency import SWCompression

enum NeuralVoicePackManifest {
    static let voiceIdentifier = "neural:vi-vais1000-medium"
    static let voiceLabel = "Vietnamese Neural · VAIS1000"
    static let version = "vi-vais1000-medium-fa136771"
    static let sourceRevision = "3d796cc2f2c884b3517c527507e084f7bb245aea"
    static let archiveRoot = "vits-piper-vi_VN-vais1000-medium"
    static let archiveName = "vits-piper-vi_VN-vais1000-medium.tar.bz2"
    static let archiveBytes: Int64 = 67_154_040
    static let archiveSHA256 = "fa1367710767d36ed5cf13b4a449e20c35ffd12791c2e47c2e64142bfa55551a"
    static let modelName = "vi_VN-vais1000-medium.onnx"
    static let modelBytes: Int64 = 63_149_198
    static let tokensName = "tokens.txt"
    static let tokensBytes: Int64 = 921
    static let archiveURL = URL(
        string: "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/\(archiveName)"
    )!
}

enum NeuralVoiceArchivePolicy {
    static let maximumEntries = 1_024
    static let maximumUncompressedBytes = 128 * 1024 * 1024
    static let maximumEntryBytes = 80 * 1024 * 1024

    static func relativePath(for entryName: String) -> String? {
        guard !entryName.isEmpty,
              entryName.count <= 512,
              !entryName.contains("\0"),
              !entryName.contains("\\")
        else { return nil }
        if entryName == NeuralVoicePackManifest.archiveRoot { return "" }
        let prefix = NeuralVoicePackManifest.archiveRoot + "/"
        guard entryName.hasPrefix(prefix) else { return nil }
        let relative = String(entryName.dropFirst(prefix.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !relative.isEmpty else { return "" }
        let components = relative.split(separator: "/", omittingEmptySubsequences: false)
        guard components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }) else { return nil }
        return relative
    }

    static func allowsEntry(name: String, isDirectory: Bool, isRegularFile: Bool, size: Int) -> Bool {
        guard isDirectory || isRegularFile else { return false }
        guard size >= 0, size <= maximumEntryBytes else { return false }
        return relativePath(for: name) != nil
    }
}

struct InstalledNeuralVoice: Sendable, Equatable {
    let modelURL: URL
    let tokensURL: URL
    let dataDirectoryURL: URL
}

enum NeuralVoiceAcquisitionError: LocalizedError {
    case wifiRequired
    case insufficientStorage
    case downloadRejected(Int)
    case integrityFailed
    case unsafeArchive
    case incompleteArchive
    case activationFailed

    var errorDescription: String? {
        switch self {
        case .wifiRequired:
            "Connect to Wi-Fi or disable ‘Download models on Wi-Fi only’ before installing Neural Voice."
        case .insufficientStorage:
            "Not enough free storage to download and prepare Neural Voice."
        case .downloadRejected(let code):
            "Neural Voice download failed with HTTP \(code)."
        case .integrityFailed:
            "Neural Voice download failed exact size or SHA-256 verification."
        case .unsafeArchive:
            "Neural Voice archive contains an unsafe or oversized entry."
        case .incompleteArchive:
            "The verified Neural Voice archive is incomplete."
        case .activationFailed:
            "Neural Voice was verified but could not be activated."
        }
    }
}

struct NeuralVoiceModelStore {
    private static let activePointerName = "active-model.txt"
    private let fileManager = FileManager.default

    func voiceOption() -> OfflineVoiceOption? {
        model().map { _ in
            OfflineVoiceOption(
                id: NeuralVoicePackManifest.voiceIdentifier,
                label: NeuralVoicePackManifest.voiceLabel,
                languageCode: "vi"
            )
        }
    }

    func model() -> InstalledNeuralVoice? {
        guard let root = try? rootURL(create: false),
              let version = try? String(
                contentsOf: root.appendingPathComponent(Self.activePointerName),
                encoding: .utf8
              ).trimmingCharacters(in: .whitespacesAndNewlines),
              version == NeuralVoicePackManifest.version
        else { return nil }
        return validatedModel(at: root.appendingPathComponent(version, isDirectory: true))
    }

    func validatedModel(at directory: URL) -> InstalledNeuralVoice? {
        let marker = directory.appendingPathComponent("pack.sha256")
        guard (try? String(contentsOf: marker, encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines)) == NeuralVoicePackManifest.archiveSHA256
        else { return nil }

        let model = directory.appendingPathComponent(NeuralVoicePackManifest.modelName)
        let tokens = directory.appendingPathComponent(NeuralVoicePackManifest.tokensName)
        let dataDirectory = directory.appendingPathComponent("espeak-ng-data", isDirectory: true)
        guard fileSize(model) == NeuralVoicePackManifest.modelBytes,
              fileSize(tokens) == NeuralVoicePackManifest.tokensBytes
        else { return nil }
        let requiredData = [
            "phondata", "phonindex", "phontab", "intonations", "vi_dict", "lang/aav/vi",
        ]
        guard requiredData.allSatisfy({
            fileManager.fileExists(atPath: dataDirectory.appendingPathComponent($0).path)
        }) else { return nil }
        return InstalledNeuralVoice(modelURL: model, tokensURL: tokens, dataDirectoryURL: dataDirectory)
    }

    func rootURL(create: Bool) throws -> URL {
        guard let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            throw NeuralVoiceAcquisitionError.activationFailed
        }
        let root = support.appendingPathComponent("LingoPlay/Models/NeuralVoice", isDirectory: true)
        if create {
            try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
        }
        return root
    }

    private func fileSize(_ url: URL) -> Int64? {
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
              values.isRegularFile == true
        else { return nil }
        return Int64(values.fileSize ?? -1)
    }
}

actor NeuralVoicePackInstaller {
    private static let activePointerName = "active-model.txt"
    private static let storageSafetyMarginBytes: Int64 = 64 * 1024 * 1024
    private let fileManager = FileManager.default
    private let store = NeuralVoiceModelStore()

    func state() -> ASRModelInstallState {
        guard let model = store.model() else { return .notInstalled }
        let root = model.modelURL.deletingLastPathComponent()
        return .installed(bytes: directorySize(root))
    }

    func install(
        wifiOnly: Bool,
        progress: @escaping @Sendable (Double) async -> Void
    ) async throws -> InstalledNeuralVoice {
        if wifiOnly {
            let usingWiFi = await ModelNetworkPolicy.isUsingWiFi()
            guard usingWiFi else { throw NeuralVoiceAcquisitionError.wifiRequired }
        }
        if let installed = store.model() {
            await progress(1)
            return installed
        }

        let root = try store.rootURL(create: true)
        let archive = root.appendingPathComponent(NeuralVoicePackManifest.archiveName)
        let part = root.appendingPathComponent(NeuralVoicePackManifest.archiveName + ".part")
        if fileManager.fileExists(atPath: archive.path), !matchesArchive(archive) {
            try fileManager.removeItem(at: archive)
        }
        if let partBytes = fileSize(part),
           partBytes > NeuralVoicePackManifest.archiveBytes ||
            (partBytes == NeuralVoicePackManifest.archiveBytes && !matchesArchive(part)) {
            try fileManager.removeItem(at: part)
        }

        let verifiedArchive = matchesArchive(archive)
        let currentBytes = verifiedArchive ? NeuralVoicePackManifest.archiveBytes : max(0, fileSize(part) ?? 0)
        try ensureStorage(at: root, remainingDownloadBytes: NeuralVoicePackManifest.archiveBytes - currentBytes)
        await progress(Double(currentBytes) / Double(NeuralVoicePackManifest.archiveBytes))

        if !verifiedArchive {
            var request = URLRequest(url: NeuralVoicePackManifest.archiveURL)
            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            request.setValue("LingoPlay-ModelInstaller/1", forHTTPHeaderField: "User-Agent")
            if currentBytes > 0 {
                request.setValue("bytes=\(currentBytes)-", forHTTPHeaderField: "Range")
            }
            let downloader = RangeFileDownloader(
                destination: part,
                existingBytes: currentBytes,
                expectedBytes: NeuralVoicePackManifest.archiveBytes,
                progress: progress
            )
            try await downloader.download(request)
            try Task.checkCancellation()
            guard matchesArchive(part) else {
                try? fileManager.removeItem(at: part)
                throw NeuralVoiceAcquisitionError.integrityFailed
            }
            if fileManager.fileExists(atPath: archive.path) {
                try fileManager.removeItem(at: archive)
            }
            try fileManager.moveItem(at: part, to: archive)
        }

        let staging = root.appendingPathComponent(NeuralVoicePackManifest.version + ".staging", isDirectory: true)
        let versionDirectory = root.appendingPathComponent(NeuralVoicePackManifest.version, isDirectory: true)
        if fileManager.fileExists(atPath: staging.path) {
            try fileManager.removeItem(at: staging)
        }
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: true)
        do {
            try extractVerifiedArchive(archive, to: staging)
            try NeuralVoicePackManifest.archiveSHA256.write(
                to: staging.appendingPathComponent("pack.sha256"),
                atomically: true,
                encoding: .utf8
            )
            guard store.validatedModel(at: staging) != nil else {
                throw NeuralVoiceAcquisitionError.incompleteArchive
            }
            if fileManager.fileExists(atPath: versionDirectory.path) {
                try fileManager.removeItem(at: versionDirectory)
            }
            try fileManager.moveItem(at: staging, to: versionDirectory)
            try writeActivePointer(root: root)
            try? fileManager.removeItem(at: archive)
            guard let activated = store.model() else {
                throw NeuralVoiceAcquisitionError.activationFailed
            }
            await progress(1)
            return activated
        } catch {
            try? fileManager.removeItem(at: staging)
            throw error
        }
    }

    func deleteInstalledVoice() throws {
        let root = try store.rootURL(create: false)
        if fileManager.fileExists(atPath: root.path) {
            try fileManager.removeItem(at: root)
        }
    }

    private func extractVerifiedArchive(_ archive: URL, to staging: URL) throws {
        try Task.checkCancellation()
        let compressed = try Data(contentsOf: archive, options: [.mappedIfSafe])
        guard compressed.count == Int(NeuralVoicePackManifest.archiveBytes) else {
            throw NeuralVoiceAcquisitionError.integrityFailed
        }
        let tarData = try BZip2.decompress(data: compressed)
        try Task.checkCancellation()
        guard tarData.count <= NeuralVoiceArchivePolicy.maximumUncompressedBytes else {
            throw NeuralVoiceAcquisitionError.unsafeArchive
        }
        let entries = try TarContainer.open(container: tarData)
        guard entries.count <= NeuralVoiceArchivePolicy.maximumEntries else {
            throw NeuralVoiceAcquisitionError.unsafeArchive
        }

        let stagingPrefix = staging.standardizedFileURL.path + "/"
        var extractedBytes = 0
        for entry in entries {
            try Task.checkCancellation()
            let isDirectory: Bool
            let isRegular: Bool
            switch entry.info.type {
            case .directory:
                isDirectory = true
                isRegular = false
            case .regular, .contiguous:
                isDirectory = false
                isRegular = true
            default:
                isDirectory = false
                isRegular = false
            }
            let size = entry.data?.count ?? 0
            guard NeuralVoiceArchivePolicy.allowsEntry(
                name: entry.info.name,
                isDirectory: isDirectory,
                isRegularFile: isRegular,
                size: size
            ), let relative = NeuralVoiceArchivePolicy.relativePath(for: entry.info.name)
            else { throw NeuralVoiceAcquisitionError.unsafeArchive }
            guard !relative.isEmpty else { continue }

            let output = staging.appendingPathComponent(relative, isDirectory: isDirectory).standardizedFileURL
            guard output.path.hasPrefix(stagingPrefix) else {
                throw NeuralVoiceAcquisitionError.unsafeArchive
            }
            if isDirectory {
                try fileManager.createDirectory(at: output, withIntermediateDirectories: true)
                continue
            }
            guard let data = entry.data, entry.info.size == data.count else {
                throw NeuralVoiceAcquisitionError.incompleteArchive
            }
            extractedBytes += data.count
            guard extractedBytes <= NeuralVoiceArchivePolicy.maximumUncompressedBytes else {
                throw NeuralVoiceAcquisitionError.unsafeArchive
            }
            try fileManager.createDirectory(
                at: output.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try data.write(to: output, options: [.atomic])
        }
    }

    private func matchesArchive(_ url: URL) -> Bool {
        guard fileSize(url) == NeuralVoicePackManifest.archiveBytes,
              let digest = try? sha256(url)
        else { return false }
        return digest == NeuralVoicePackManifest.archiveSHA256
    }

    private func sha256(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            let chunk = try handle.read(upToCount: 1024 * 1024) ?? Data()
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private func fileSize(_ url: URL) -> Int64? {
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
              values.isRegularFile == true
        else { return nil }
        return Int64(values.fileSize ?? -1)
    }

    private func ensureStorage(at root: URL, remainingDownloadBytes: Int64) throws {
        let values = try root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        let required = max(0, remainingDownloadBytes) +
            Int64(NeuralVoiceArchivePolicy.maximumUncompressedBytes) +
            Self.storageSafetyMarginBytes
        if let available = values.volumeAvailableCapacityForImportantUsage, available < required {
            throw NeuralVoiceAcquisitionError.insufficientStorage
        }
    }

    private func writeActivePointer(root: URL) throws {
        let pointer = root.appendingPathComponent(Self.activePointerName)
        let temporary = root.appendingPathComponent(Self.activePointerName + ".tmp")
        try NeuralVoicePackManifest.version.write(to: temporary, atomically: true, encoding: .utf8)
        if fileManager.fileExists(atPath: pointer.path) {
            _ = try fileManager.replaceItemAt(pointer, withItemAt: temporary)
        } else {
            try fileManager.moveItem(at: temporary, to: pointer)
        }
    }

    private func directorySize(_ root: URL) -> Int64 {
        guard let enumerator = fileManager.enumerator(
            at: root,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        ) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in enumerator {
            guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
                  values.isRegularFile == true
            else { continue }
            total += Int64(values.fileSize ?? 0)
        }
        return total
    }
}

private final class RangeFileDownloader: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let destination: URL
    private let existingBytes: Int64
    private let expectedBytes: Int64
    private let progress: @Sendable (Double) async -> Void
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?
    private var task: URLSessionDataTask?
    private var session: URLSession?
    private var output: FileHandle?
    private var completedBytes: Int64 = 0
    private var lastReportedBytes: Int64 = 0
    private var finished = false

    init(
        destination: URL,
        existingBytes: Int64,
        expectedBytes: Int64,
        progress: @escaping @Sendable (Double) async -> Void
    ) {
        self.destination = destination
        self.existingBytes = existingBytes
        self.expectedBytes = expectedBytes
        self.progress = progress
    }

    func download(_ request: URLRequest) async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                if Task.isCancelled {
                    lock.unlock()
                    continuation.resume(throwing: CancellationError())
                    return
                }
                self.continuation = continuation
                let session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)
                let task = session.dataTask(with: request)
                self.session = session
                self.task = task
                lock.unlock()
                task.resume()
            }
        } onCancel: {
            self.cancel()
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard request.url?.scheme?.lowercased() == "https" else {
            completionHandler(nil)
            return
        }
        var redirected = request
        redirected.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        redirected.setValue("LingoPlay-ModelInstaller/1", forHTTPHeaderField: "User-Agent")
        if existingBytes > 0 {
            redirected.setValue("bytes=\(existingBytes)-", forHTTPHeaderField: "Range")
        }
        completionHandler(redirected)
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        do {
            guard let response = response as? HTTPURLResponse else {
                throw NeuralVoiceAcquisitionError.downloadRejected(-1)
            }
            guard response.statusCode == 200 || response.statusCode == 206 else {
                throw NeuralVoiceAcquisitionError.downloadRejected(response.statusCode)
            }
            let isPartial = response.statusCode == 206 && existingBytes > 0
            if isPartial {
                let expectedPrefix = "bytes \(existingBytes)-"
                guard response.value(forHTTPHeaderField: "Content-Range")?.hasPrefix(expectedPrefix) == true else {
                    throw NeuralVoiceAcquisitionError.integrityFailed
                }
            }
            try prepareDestinationForStreaming(append: isPartial)
            completedBytes = isPartial ? existingBytes : 0
            lastReportedBytes = completedBytes
            completionHandler(.allow)
        } catch {
            completionHandler(.cancel)
            finish(.failure(error))
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        do {
            guard !finished, let output else { return }
            try output.write(contentsOf: data)
            completedBytes += Int64(data.count)
            guard completedBytes <= expectedBytes else {
                throw NeuralVoiceAcquisitionError.integrityFailed
            }
            if completedBytes == expectedBytes || completedBytes - lastReportedBytes >= 256 * 1024 {
                lastReportedBytes = completedBytes
                let fraction = min(1, max(0, Double(completedBytes) / Double(expectedBytes)))
                Task { await progress(fraction) }
            }
        } catch {
            dataTask.cancel()
            finish(.failure(error))
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        if let error {
            finish(.failure(error))
        } else if completedBytes == expectedBytes {
            finish(.success(()))
        } else {
            finish(.failure(NeuralVoiceAcquisitionError.integrityFailed))
        }
    }

    private func prepareDestinationForStreaming(append: Bool) throws {
        let fileManager = FileManager.default
        try fileManager.createDirectory(
            at: destination.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        if append {
            guard let current = try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                  Int64(current) == existingBytes
            else { throw NeuralVoiceAcquisitionError.integrityFailed }
        } else {
            if fileManager.fileExists(atPath: destination.path) {
                try fileManager.removeItem(at: destination)
            }
            guard fileManager.createFile(atPath: destination.path, contents: nil) else {
                throw NeuralVoiceAcquisitionError.activationFailed
            }
        }
        let handle = try FileHandle(forWritingTo: destination)
        if append {
            try handle.seekToEnd()
        }
        output = handle
    }

    private func cancel() {
        lock.lock()
        let task = self.task
        lock.unlock()
        task?.cancel()
    }

    private func finish(_ result: Result<Void, Error>) {
        lock.lock()
        guard !finished else {
            lock.unlock()
            return
        }
        finished = true
        let continuation = self.continuation
        let session = self.session
        let output = self.output
        self.continuation = nil
        self.task = nil
        self.session = nil
        self.output = nil
        lock.unlock()

        try? output?.close()
        session?.invalidateAndCancel()
        switch result {
        case .success:
            continuation?.resume()
        case .failure(let error):
            continuation?.resume(throwing: error)
        }
    }
}
