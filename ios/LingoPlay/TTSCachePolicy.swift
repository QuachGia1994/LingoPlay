import Foundation

enum TTSCachePolicy {
    private static let cacheFamilies = ["TTS", "NeuralTTS", "CloneTTS"]

    static func cleanup(document: DubSpeechDocument) {
        sessionDirectories(document: document).forEach { directory in
            try? FileManager.default.removeItem(at: directory)
        }
    }

    static func purgeAllSessions() {
        guard let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return }
        let root = caches.appendingPathComponent("LingoPlay", isDirectory: true)
        for family in cacheFamilies {
            let familyDirectory = root.appendingPathComponent(family, isDirectory: true)
            guard let children = try? FileManager.default.contentsOfDirectory(
                at: familyDirectory,
                includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]
            ) else { continue }
            for child in children {
                try? FileManager.default.removeItem(at: child)
            }
        }
    }

    private static func sessionDirectories(document: DubSpeechDocument) -> Set<URL> {
        Set(document.segments.compactMap { segment in
            validatedSessionDirectory(for: segment.audioURL)
        })
    }

    private static func validatedSessionDirectory(for audioURL: URL) -> URL? {
        guard let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
        let standardized = audioURL.standardizedFileURL
        let session = standardized.deletingLastPathComponent()
        let family = session.deletingLastPathComponent()
        let appRoot = family.deletingLastPathComponent()
        guard cacheFamilies.contains(family.lastPathComponent) else { return nil }
        guard appRoot.standardizedFileURL == caches.appendingPathComponent("LingoPlay", isDirectory: true).standardizedFileURL else {
            return nil
        }
        return session
    }
}
