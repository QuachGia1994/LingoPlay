@preconcurrency import CryptoKit
import Foundation

enum PinnedDownloadError: LocalizedError {
    case invalidURL
    case downloadRejected(Int)
    case integrityFailed
    case activationFailed

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            "The pinned model download URL is invalid."
        case .downloadRejected(let code):
            "Model download failed with HTTP \(code)."
        case .integrityFailed:
            "Model download failed exact size or SHA-256 verification."
        case .activationFailed:
            "The verified model file could not be activated."
        }
    }
}

struct PinnedDownloadSpec: Sendable, Equatable {
    let name: String
    let url: String
    let bytes: Int64
    let sha256: String
}

enum PinnedFileIntegrity {
    static func matches(_ url: URL, spec: PinnedDownloadSpec) -> Bool {
        guard fileSize(url) == spec.bytes,
              let digest = try? sha256(url)
        else { return false }
        return digest.caseInsensitiveCompare(spec.sha256) == .orderedSame
    }

    static func fileSize(_ url: URL) -> Int64? {
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
              values.isRegularFile == true
        else { return nil }
        return Int64(values.fileSize ?? -1)
    }

    static func sha256(_ url: URL) throws -> String {
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
}

final class PinnedRangeFileDownloader: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let destination: URL
    private let existingBytes: Int64
    private let expectedBytes: Int64
    private let progress: @Sendable (Int64) async -> Void
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
        progress: @escaping @Sendable (Int64) async -> Void
    ) {
        self.destination = destination
        self.existingBytes = existingBytes
        self.expectedBytes = expectedBytes
        self.progress = progress
    }

    func download(_ request: URLRequest) async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
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
                throw PinnedDownloadError.downloadRejected(-1)
            }
            guard response.statusCode == 200 || response.statusCode == 206 else {
                throw PinnedDownloadError.downloadRejected(response.statusCode)
            }
            let isPartial = response.statusCode == 206 && existingBytes > 0
            if isPartial {
                let expectedPrefix = "bytes \(existingBytes)-"
                guard response.value(forHTTPHeaderField: "Content-Range")?.hasPrefix(expectedPrefix) == true else {
                    throw PinnedDownloadError.integrityFailed
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
                throw PinnedDownloadError.integrityFailed
            }
            if completedBytes == expectedBytes || completedBytes - lastReportedBytes >= 256 * 1024 {
                lastReportedBytes = completedBytes
                let value = completedBytes
                Task { await progress(value) }
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
            finish(.failure(PinnedDownloadError.integrityFailed))
        }
    }

    private func prepareDestinationForStreaming(append: Bool) throws {
        let fileManager = FileManager.default
        try fileManager.createDirectory(
            at: destination.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        if append {
            guard PinnedFileIntegrity.fileSize(destination) == existingBytes else {
                throw PinnedDownloadError.integrityFailed
            }
        } else {
            if fileManager.fileExists(atPath: destination.path) {
                try fileManager.removeItem(at: destination)
            }
            guard fileManager.createFile(atPath: destination.path, contents: nil) else {
                throw PinnedDownloadError.activationFailed
            }
        }
        let handle = try FileHandle(forWritingTo: destination)
        if append { try handle.seekToEnd() }
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

enum PinnedModelDownload {
    static func downloadVerified(
        spec: PinnedDownloadSpec,
        root: URL,
        progress: @escaping @Sendable (Int64) async -> Void
    ) async throws -> URL {
        let fileManager = FileManager.default
        let final = root.appendingPathComponent(spec.name)
        if PinnedFileIntegrity.matches(final, spec: spec) {
            await progress(spec.bytes)
            return final
        }
        try? fileManager.removeItem(at: final)

        let part = root.appendingPathComponent(spec.name + ".part")
        let partBytes = PinnedFileIntegrity.fileSize(part) ?? 0
        if partBytes > spec.bytes || (partBytes == spec.bytes && !PinnedFileIntegrity.matches(part, spec: spec)) {
            try? fileManager.removeItem(at: part)
        }
        let existing = PinnedFileIntegrity.fileSize(part) ?? 0
        guard let url = URL(string: spec.url), url.scheme?.lowercased() == "https" else {
            throw PinnedDownloadError.invalidURL
        }
        var request = URLRequest(url: url)
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        request.setValue("LingoPlay-ModelInstaller/1", forHTTPHeaderField: "User-Agent")
        if existing > 0 { request.setValue("bytes=\(existing)-", forHTTPHeaderField: "Range") }

        let downloader = PinnedRangeFileDownloader(
            destination: part,
            existingBytes: existing,
            expectedBytes: spec.bytes,
            progress: progress
        )
        try await downloader.download(request)
        try Task.checkCancellation()
        guard PinnedFileIntegrity.matches(part, spec: spec) else {
            try? fileManager.removeItem(at: part)
            throw PinnedDownloadError.integrityFailed
        }
        if fileManager.fileExists(atPath: final.path) {
            try fileManager.removeItem(at: final)
        }
        try fileManager.moveItem(at: part, to: final)
        return final
    }
}
