import AVFoundation
import Foundation

struct LocalMediaItem: Identifiable, Sendable, Equatable {
    let id: UUID
    let localURL: URL
    let title: String
    let duration: TimeInterval
    let fileSizeBytes: Int64
    let hasAudioTrack: Bool

    init(id: UUID = UUID(), localURL: URL, title: String, duration: TimeInterval, fileSizeBytes: Int64, hasAudioTrack: Bool) {
        self.id = id
        self.localURL = localURL
        self.title = title
        self.duration = duration
        self.fileSizeBytes = fileSizeBytes
        self.hasAudioTrack = hasAudioTrack
    }

    var durationText: String { MediaFormatting.duration(seconds: duration) }
    var fileSizeText: String { MediaFormatting.bytes(fileSizeBytes) }
}

enum MediaPreparationState: Equatable {
    case idle
    case importing
    case ready
    case extractingAudio
    case audioReady(URL)
    case failed(String)
}

enum LocalMediaError: LocalizedError {
    case noAudioTrack
    case exportSessionUnavailable
    case exportFailed(String)

    var errorDescription: String? {
        switch self {
        case .noAudioTrack: "This video has no readable audio track."
        case .exportSessionUnavailable: "This audio track cannot be prepared on this device."
        case .exportFailed(let detail): "Audio preparation failed: \(detail)"
        }
    }
}

actor LocalMediaService {
    private let fileManager = FileManager.default

    func importMedia(from sourceURL: URL) async throws -> LocalMediaItem {
        let accessed = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if accessed { sourceURL.stopAccessingSecurityScopedResource() }
        }

        let directory = try cacheDirectory(named: "ImportedMedia")
        let fileName = "\(UUID().uuidString).\(sourceURL.pathExtension.isEmpty ? "mp4" : sourceURL.pathExtension)"
        let localURL = directory.appendingPathComponent(fileName)
        try fileManager.copyItem(at: sourceURL, to: localURL)

        let asset = AVURLAsset(url: localURL)
        let duration = try await asset.load(.duration)
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        let values = try localURL.resourceValues(forKeys: [.fileSizeKey])

        return LocalMediaItem(
            localURL: localURL,
            title: sourceURL.lastPathComponent,
            duration: max(0, CMTimeGetSeconds(duration)),
            fileSizeBytes: Int64(values.fileSize ?? 0),
            hasAudioTrack: !audioTracks.isEmpty
        )
    }

    func extractAudio(from media: LocalMediaItem) async throws -> URL {
        guard media.hasAudioTrack else { throw LocalMediaError.noAudioTrack }

        let directory = try cacheDirectory(named: "ExtractedAudio")
        let destination = directory.appendingPathComponent("\(media.id.uuidString).m4a")
        if fileManager.fileExists(atPath: destination.path) {
            try fileManager.removeItem(at: destination)
        }

        let asset = AVURLAsset(url: media.localURL)
        guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            throw LocalMediaError.exportSessionUnavailable
        }

        session.outputURL = destination
        session.outputFileType = .m4a
        await session.export()

        guard session.status == .completed else {
            throw LocalMediaError.exportFailed(session.error?.localizedDescription ?? "unknown error")
        }
        return destination
    }

    private func cacheDirectory(named name: String) throws -> URL {
        let root = fileManager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let directory = root.appendingPathComponent("LingoPlay", isDirectory: true).appendingPathComponent(name, isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}

enum MediaTimelinePolicy {
    static func audioStartOffsetSeconds(for sourceURL: URL) async throws -> TimeInterval {
        let asset = AVURLAsset(url: sourceURL)
        guard let audioTrack = try await asset.loadTracks(withMediaType: .audio).first else {
            throw LocalMediaError.noAudioTrack
        }
        let range = try await audioTrack.load(.timeRange)
        let seconds = range.start.seconds
        guard seconds.isFinite else { return 0 }
        return max(0, seconds)
    }
}

enum MediaFormatting {
    static func duration(seconds: TimeInterval) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "00:00" }
        let total = Int(seconds.rounded())
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let secs = total % 60
        if hours > 0 {
            return String(format: "%02d:%02d:%02d", hours, minutes, secs)
        }
        return String(format: "%02d:%02d", minutes, secs)
    }

    static func bytes(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}
