import Foundation

actor LocalDiagnostics {
    private let fileManager = FileManager.default
    private let maxLines = 120
    private let maxBytes: Int64 = 96 * 1024

    func record(_ event: String) {
        let safe = event
            .lowercased()
            .map { character -> Character in
                if character.isLetter || character.isNumber || "_.-".contains(character) { return character }
                return "_"
            }
        let eventName = String(safe.prefix(64))
        guard !eventName.isEmpty, let file = try? logFile() else { return }
        try? fileManager.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let line = "\(ISO8601DateFormatter().string(from: Date())) \(eventName)\n"
        if let data = line.data(using: .utf8) {
            if !fileManager.fileExists(atPath: file.path) { fileManager.createFile(atPath: file.path, contents: nil) }
            if let handle = try? FileHandle(forWritingTo: file) {
                defer { try? handle.close() }
                try? handle.seekToEnd()
                try? handle.write(contentsOf: data)
            }
        }
        if fileSize(file) > maxBytes { trim(file) }
    }

    func recent() -> [String] {
        guard let file = try? logFile(), let text = try? String(contentsOf: file, encoding: .utf8) else { return [] }
        return text.split(separator: "\n").suffix(maxLines).map(String.init)
    }

    func clear() {
        if let file = try? logFile() { try? fileManager.removeItem(at: file) }
    }

    private func trim(_ file: URL) {
        guard let text = try? String(contentsOf: file, encoding: .utf8) else { return }
        let lines = text.split(separator: "\n").suffix(maxLines)
        let trimmed = lines.joined(separator: "\n") + (lines.isEmpty ? "" : "\n")
        try? trimmed.write(to: file, atomically: true, encoding: .utf8)
    }

    private func fileSize(_ file: URL) -> Int64 {
        let values = try? file.resourceValues(forKeys: [.fileSizeKey])
        return Int64(values?.fileSize ?? 0)
    }

    private func logFile() throws -> URL {
        guard let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            throw CocoaError(.fileNoSuchFile)
        }
        return support
            .appendingPathComponent("LingoPlay/Diagnostics", isDirectory: true)
            .appendingPathComponent("events.log")
    }
}
