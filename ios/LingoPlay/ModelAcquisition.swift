import Foundation
import Network
import WhisperKit

enum ASRModelInstallState: Equatable, Sendable {
    case notInstalled
    case downloading(progress: Double)
    case installed(bytes: Int64)
    case failed(String)
}

struct InstalledWhisperModel: Sendable, Equatable {
    let modelFolder: URL
    let tokenizerFolder: URL
}

enum ModelAcquisitionError: LocalizedError {
    case wifiRequired
    case insufficientStorage
    case invalidDownloadedModel
    case activationFailed

    var errorDescription: String? {
        switch self {
        case .wifiRequired:
            "Connect to Wi-Fi or disable ‘Download models on Wi-Fi only’ before installing Speech AI."
        case .insufficientStorage:
            "Not enough free storage to install and prepare Speech AI."
        case .invalidDownloadedModel:
            "The downloaded Speech AI model could not be validated."
        case .activationFailed:
            "Speech AI was downloaded but could not be activated."
        }
    }
}

actor WhisperModelInstaller {
    static let variant = "tiny"
    private static let minimumFreeBytes: Int64 = 256 * 1024 * 1024
    private static let activePointerName = "active-model.txt"
    private let fileManager = FileManager.default

    func state() -> ASRModelInstallState {
        guard let model = ASRModelStore().whisperModel() else { return .notInstalled }
        return .installed(bytes: directorySize(model.tokenizerFolder))
    }

    func install(
        wifiOnly: Bool,
        progress: @escaping @Sendable (Double) async -> Void
    ) async throws -> InstalledWhisperModel {
        if wifiOnly && !(await ModelNetworkPolicy.isUsingWiFi()) {
            throw ModelAcquisitionError.wifiRequired
        }

        var root = try modelRoot()
        if ASRModelStore().whisperModel() == nil {
            try resetUnactivatedCache(at: root)
            root = try modelRoot()
        }
        try ensureStorage(at: root)
        try Task.checkCancellation()

        let modelFolder = try await WhisperKit.download(
            variant: Self.variant,
            downloadBase: root,
            useBackgroundSession: false,
            progressCallback: { value in
                Task { await progress(value.fractionCompleted.coerce01) }
            }
        )
        try Task.checkCancellation()

        let config = WhisperKitConfig(
            model: Self.variant,
            downloadBase: root,
            modelFolder: modelFolder.path,
            tokenizerFolder: root,
            verbose: false,
            prewarm: true,
            load: true,
            download: false,
            useBackgroundDownloadSession: false
        )
        let pipe = try await WhisperKit(config)
        guard pipe.modelFolder != nil, pipe.tokenizer != nil else {
            throw ModelAcquisitionError.invalidDownloadedModel
        }
        try Task.checkCancellation()

        try writeActivePointer(root: root, modelFolder: modelFolder)
        guard let activated = ASRModelStore().whisperModel() else {
            throw ModelAcquisitionError.activationFailed
        }
        await progress(1.0)
        return activated
    }

    func deleteInstalledModel() throws {
        let root = try modelRoot()
        if fileManager.fileExists(atPath: root.path) {
            try fileManager.removeItem(at: root)
        }
    }

    private func modelRoot() throws -> URL {
        guard let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            throw ModelAcquisitionError.activationFailed
        }
        let root = support.appendingPathComponent("LingoPlay/Models/WhisperKit", isDirectory: true)
        try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    private func ensureStorage(at root: URL) throws {
        let values = try root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        if let available = values.volumeAvailableCapacityForImportantUsage,
           available < Self.minimumFreeBytes {
            throw ModelAcquisitionError.insufficientStorage
        }
    }

    private func resetUnactivatedCache(at root: URL) throws {
        guard !fileManager.fileExists(atPath: root.appendingPathComponent(Self.activePointerName).path) else { return }
        if fileManager.fileExists(atPath: root.path) {
            try fileManager.removeItem(at: root)
        }
    }

    private func writeActivePointer(root: URL, modelFolder: URL) throws {
        let rootPath = root.standardizedFileURL.path
        let modelPath = modelFolder.standardizedFileURL.path
        let prefix = rootPath + "/"
        guard modelPath.hasPrefix(prefix) else { throw ModelAcquisitionError.activationFailed }
        let relative = String(modelPath.dropFirst(prefix.count))
        guard !relative.isEmpty, !relative.contains("../") else { throw ModelAcquisitionError.activationFailed }

        let pointer = root.appendingPathComponent(Self.activePointerName)
        let temporary = root.appendingPathComponent("\(Self.activePointerName).tmp")
        try relative.write(to: temporary, atomically: true, encoding: .utf8)
        if fileManager.fileExists(atPath: pointer.path) {
            try fileManager.removeItem(at: pointer)
        }
        try fileManager.moveItem(at: temporary, to: pointer)
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

private final class NetworkPathOneShot: @unchecked Sendable {
    private let lock = NSLock()
    private var completed = false

    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !completed else { return false }
        completed = true
        return true
    }
}

private enum ModelNetworkPolicy {
    static func isUsingWiFi() async -> Bool {
        let monitor = NWPathMonitor(requiredInterfaceType: .wifi)
        let queue = DispatchQueue(label: "com.lingoplay.model-network-gate")
        let oneShot = NetworkPathOneShot()
        return await withCheckedContinuation { continuation in
            monitor.pathUpdateHandler = { path in
                guard oneShot.claim() else { return }
                monitor.cancel()
                continuation.resume(returning: path.status == .satisfied)
            }
            monitor.start(queue: queue)
        }
    }
}

private extension Double {
    var coerce01: Double { min(max(self, 0), 1) }
}
