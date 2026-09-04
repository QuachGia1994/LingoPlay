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
        let line = "\(ISO8601DateFormatter().string(from: Date())) \(eventName)"
        var lines = (try? String(contentsOf: file, encoding: .utf8))
            .map { $0.split(separator: "\n").map(String.init) } ?? []
        lines.append(line)
        lines = Array(lines.suffix(maxLines))
        var output = lines.joined(separator: "\n") + "\n"
        while output.utf8.count > maxBytes, lines.count > 1 {
            lines.removeFirst()
            output = lines.joined(separator: "\n") + "\n"
        }
        try? output.write(to: file, atomically: true, encoding: .utf8)
    }

    func recent() -> [String] {
        guard let file = try? logFile(), let text = try? String(contentsOf: file, encoding: .utf8) else { return [] }
        return text.split(separator: "\n").suffix(maxLines).map(String.init)
    }

    func clear() {
        if let file = try? logFile() { try? fileManager.removeItem(at: file) }
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
