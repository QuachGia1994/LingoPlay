import AVFoundation
import Foundation

struct LocalDubMediaResult: Sendable, Equatable {
    let dubbedAudioURL: URL
    let remuxedVideoURL: URL
    let duration: TimeInterval
}

@MainActor
final class PlaybackMixContext {
    let originalTrack: AVCompositionTrack
    let dubTrack: AVCompositionTrack
    let speechSegments: [DubSpeechSegment]
    let mode: DubbingModePreset

    init(originalTrack: AVCompositionTrack, dubTrack: AVCompositionTrack, speechSegments: [DubSpeechSegment], mode: DubbingModePreset) {
        self.originalTrack = originalTrack
        self.dubTrack = dubTrack
        self.speechSegments = speechSegments
        self.mode = mode
    }
}

@MainActor
struct PlaybackSession {
    let item: AVPlayerItem
    let mixContext: PlaybackMixContext
}

private final class LegacyExportSessionBox: @unchecked Sendable {
    let session: AVAssetExportSession

    init(_ session: AVAssetExportSession) {
        self.session = session
    }
}

enum MixState: Equatable {
    case idle
    case renderingAudio
    case remuxing
    case completed(LocalDubMediaResult)
    case failed(String)
}

enum TimelineMixError: LocalizedError {
    case noSpeechClips
    case noVideoTrack
    case noOriginalAudioTrack
    case noAudioTrack(String)
    case exportUnavailable
    case exportFailed(String)
    case emptyOutput(String)

    var errorDescription: String? {
        switch self {
        case .noSpeechClips:
            "No Vietnamese speech clips are available to mix."
        case .noVideoTrack:
            "The source file has no readable video track."
        case .noOriginalAudioTrack:
            "The source video has no readable original audio track."
        case let .noAudioTrack(name):
            "The local speech file has no readable audio track: \(name)."
        case .exportUnavailable:
            "The local media exporter is unavailable for this source format."
        case let .exportFailed(message):
            "Local media export failed: \(message)"
        case let .emptyOutput(name):
            "Local media export produced no output: \(name)."
        }
    }
}

enum SpeechSeamPolicy {
    static let edgeFadeSecondsMaximum: TimeInterval = 0.008

    static func edgeFadeSeconds(clipDuration: TimeInterval) -> TimeInterval {
        guard clipDuration.isFinite, clipDuration > 0 else { return 0 }
        return min(edgeFadeSecondsMaximum, clipDuration / 2)
    }

    static func apply(to parameters: AVMutableAudioMixInputParameters, interval: CMTimeRange) {
        let fadeSeconds = edgeFadeSeconds(clipDuration: interval.duration.seconds)
        guard fadeSeconds > 0 else { return }
        let fade = CMTime(seconds: fadeSeconds, preferredTimescale: 60_000)
        let end = CMTimeRangeGetEnd(interval)
        let fadeOutStart = CMTimeSubtract(end, fade)
        parameters.setVolume(0, at: interval.start)
        parameters.setVolumeRamp(
            fromStartVolume: 0,
            toEndVolume: 1,
            timeRange: CMTimeRange(start: interval.start, duration: fade)
        )
        parameters.setVolumeRamp(
            fromStartVolume: 1,
            toEndVolume: 0,
            timeRange: CMTimeRange(start: fadeOutStart, duration: fade)
        )
    }
}

@MainActor
final class TimelineMixService {
    private let cacheMaxAge: TimeInterval = 24 * 60 * 60
    private let cacheMaxBytes: Int64 = 2 * 1024 * 1024 * 1024
    private let cacheTargetBytes: Int64 = 1536 * 1024 * 1024

    func render(
        media: LocalMediaItem,
        dub: DubSpeechDocument,
        mode: DubbingModePreset = .balanced,
        backgroundAudioURL: URL? = nil,
        progress: @MainActor @Sendable (MixState) -> Void
    ) async throws -> LocalDubMediaResult {
        guard !dub.segments.isEmpty else { throw TimelineMixError.noSpeechClips }
        let parent = try renderedRootDirectory()
        purgeRenderCache(parent: parent)
        let root = parent.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)

        let dubbedAudioURL = root.appendingPathComponent("dub-audio.m4a")
        let mixedAudioURL = root.appendingPathComponent("mixed-audio.m4a")
        let remuxedVideoURL = root.appendingPathComponent("dubbed-video.mp4")
        var success = false

        defer {
            if !success {
                try? FileManager.default.removeItem(at: root)
            }
        }

        progress(.renderingAudio)
        try await renderDubTimeline(
            segments: dub.segments,
            sourceDuration: media.duration,
            destination: dubbedAudioURL
        )
        try await renderMixedAudio(
            sourceVideoURL: media.localURL,
            backgroundAudioURL: backgroundAudioURL,
            dubbedAudioURL: dubbedAudioURL,
            speechSegments: dub.segments,
            mode: mode,
            destination: mixedAudioURL
        )

        progress(.remuxing)
        try await remuxVideo(
            sourceVideoURL: media.localURL,
            mixedAudioURL: mixedAudioURL,
            destination: remuxedVideoURL
        )

        try validateNonEmpty(dubbedAudioURL)
        try validateNonEmpty(remuxedVideoURL)
        try? FileManager.default.removeItem(at: mixedAudioURL)
        let result = LocalDubMediaResult(
            dubbedAudioURL: dubbedAudioURL,
            remuxedVideoURL: remuxedVideoURL,
            duration: try await AVURLAsset(url: remuxedVideoURL).load(.duration).seconds
        )
        success = true
        progress(.completed(result))
        purgeRenderCache(parent: parent, excluding: root)
        return result
    }

    func makePlaybackSession(
        media: LocalMediaItem,
        dubbedAudioURL: URL,
        speechSegments: [DubSpeechSegment],
        blend: Double,
        mode: DubbingModePreset = .balanced
    ) async throws -> PlaybackSession {
        let source = AVURLAsset(url: media.localURL)
        guard let sourceVideoTrack = try await source.loadTracks(withMediaType: .video).first else {
            throw TimelineMixError.noVideoTrack
        }
        guard let sourceAudioTrack = try await source.loadTracks(withMediaType: .audio).first else {
            throw TimelineMixError.noOriginalAudioTrack
        }
        let sourceDuration = try await source.load(.duration)
        let sourceAudioRange = try await sourceAudioTrack.load(.timeRange)
        let sourceAudioDuration = CMTimeMinimum(
            sourceAudioRange.duration,
            CMTimeMaximum(.zero, CMTimeSubtract(sourceDuration, sourceAudioRange.start))
        )
        let dubAsset = AVURLAsset(url: dubbedAudioURL)
        guard let dubTrack = try await dubAsset.loadTracks(withMediaType: .audio).first else {
            throw TimelineMixError.noAudioTrack(dubbedAudioURL.lastPathComponent)
        }
        let dubDuration = try await dubAsset.load(.duration)

        let composition = AVMutableComposition()
        guard let outputVideoTrack = composition.addMutableTrack(
            withMediaType: .video,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ), let outputOriginalTrack = composition.addMutableTrack(
            withMediaType: .audio,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ), let outputDubTrack = composition.addMutableTrack(
            withMediaType: .audio,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ) else {
            throw TimelineMixError.exportUnavailable
        }

        try outputVideoTrack.insertTimeRange(
            CMTimeRange(start: .zero, duration: sourceDuration),
            of: sourceVideoTrack,
            at: .zero
        )
        outputVideoTrack.preferredTransform = try await sourceVideoTrack.load(.preferredTransform)
        if CMTimeCompare(sourceAudioDuration, .zero) > 0 {
            try outputOriginalTrack.insertTimeRange(
                CMTimeRange(start: sourceAudioRange.start, duration: sourceAudioDuration),
                of: sourceAudioTrack,
                at: sourceAudioRange.start
            )
        }
        try outputDubTrack.insertTimeRange(
            CMTimeRange(start: .zero, duration: dubDuration),
            of: dubTrack,
            at: .zero
        )
        let playbackDuration = CMTimeMaximum(sourceDuration, dubDuration)
        let sourceAudioEnd = CMTimeAdd(sourceAudioRange.start, sourceAudioDuration)
        if CMTimeCompare(playbackDuration, sourceAudioEnd) > 0 {
            outputOriginalTrack.insertEmptyTimeRange(CMTimeRange(
                start: sourceAudioEnd,
                duration: CMTimeSubtract(playbackDuration, sourceAudioEnd)
            ))
        }
        if CMTimeCompare(playbackDuration, sourceDuration) > 0 {
            outputVideoTrack.insertEmptyTimeRange(CMTimeRange(
                start: sourceDuration,
                duration: CMTimeSubtract(playbackDuration, sourceDuration)
            ))
        }

        let context = PlaybackMixContext(
            originalTrack: outputOriginalTrack,
            dubTrack: outputDubTrack,
            speechSegments: speechSegments,
            mode: mode
        )
        let item = AVPlayerItem(asset: composition)
        item.audioMix = makePlaybackAudioMix(context: context, blend: blend)
        return PlaybackSession(item: item, mixContext: context)
    }

    func makePlaybackAudioMix(context: PlaybackMixContext, blend: Double) -> AVAudioMix {
        let safeBlend = Float(min(max(blend, 0), 1))
        return makeAudioMix(
            originalTrack: context.originalTrack,
            dubTrack: context.dubTrack,
            speechSegments: context.speechSegments,
            originalBaseVolume: 1,
            dubVolume: safeBlend,
            duckStrength: safeBlend,
            duckFloor: context.mode.duckFloor,
            duckFadeSeconds: context.mode.duckFadeSeconds
        )
    }

    private func renderDubTimeline(
        segments: [DubSpeechSegment],
        sourceDuration: TimeInterval,
        destination: URL
    ) async throws {
        let composition = AVMutableComposition()
        var lanes: [(track: AVMutableCompositionTrack, end: CMTime)] = []
        var intervalsByLane: [ObjectIdentifier: [CMTimeRange]] = [:]

        for segment in segments.sorted(by: { lhs, rhs in
            lhs.startMs == rhs.startMs ? lhs.endMs < rhs.endMs : lhs.startMs < rhs.startMs
        }) {
            let asset = AVURLAsset(url: segment.audioURL)
            guard let sourceTrack = try await asset.loadTracks(withMediaType: .audio).first else {
                throw TimelineMixError.noAudioTrack(segment.audioURL.lastPathComponent)
            }
            let assetDuration = try await asset.load(.duration)
            let start = CMTime(value: CMTimeValue(segment.startMs), timescale: 1_000)
            let requestedDuration = CMTime(value: CMTimeValue(max(1, segment.speechDurationMs)), timescale: 1_000)
            let clipDuration = CMTimeMinimum(assetDuration, requestedDuration)
            let range = CMTimeRange(start: .zero, duration: clipDuration)

            let laneIndex = lanes.firstIndex { CMTimeCompare($0.end, start) <= 0 }
            let lane: AVMutableCompositionTrack
            if let laneIndex {
                lane = lanes[laneIndex].track
            } else {
                guard let created = composition.addMutableTrack(
                    withMediaType: .audio,
                    preferredTrackID: kCMPersistentTrackID_Invalid
                ) else {
                    throw TimelineMixError.exportUnavailable
                }
                lane = created
                lanes.append((created, .zero))
            }

            try lane.insertTimeRange(range, of: sourceTrack, at: start)
            let insertedRange = CMTimeRange(start: start, duration: clipDuration)
            intervalsByLane[ObjectIdentifier(lane), default: []].append(insertedRange)
            let end = CMTimeAdd(start, clipDuration)
            if let index = lanes.firstIndex(where: { $0.track === lane }) {
                lanes[index].end = CMTimeMaximum(lanes[index].end, end)
            }
        }

        let targetDuration = CMTime(seconds: max(0, sourceDuration), preferredTimescale: 600)
        if CMTimeCompare(targetDuration, composition.duration) > 0 {
            composition.insertEmptyTimeRange(
                CMTimeRange(start: composition.duration, duration: CMTimeSubtract(targetDuration, composition.duration))
            )
        }

        guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A) else {
            throw TimelineMixError.exportUnavailable
        }
        let laneMix = AVMutableAudioMix()
        laneMix.inputParameters = lanes.map { lane in
            let parameters = AVMutableAudioMixInputParameters(track: lane.track)
            parameters.setVolume(1, at: .zero)
            intervalsByLane[ObjectIdentifier(lane.track), default: []].forEach { interval in
                SpeechSeamPolicy.apply(to: parameters, interval: interval)
            }
            return parameters
        }
        exporter.audioMix = laneMix
        try await export(exporter, to: destination, as: .m4a)
    }

    private func renderMixedAudio(
        sourceVideoURL: URL,
        backgroundAudioURL: URL?,
        dubbedAudioURL: URL,
        speechSegments: [DubSpeechSegment],
        mode: DubbingModePreset,
        destination: URL
    ) async throws {
        let source = AVURLAsset(url: sourceVideoURL)
        let background = AVURLAsset(url: backgroundAudioURL ?? sourceVideoURL)
        guard let sourceAudioTrack = try await background.loadTracks(withMediaType: .audio).first else {
            throw TimelineMixError.noOriginalAudioTrack
        }
        let sourceDuration = try await source.load(.duration)
        let sourceAudioRange = try await sourceAudioTrack.load(.timeRange)
        let backgroundInsertStart: CMTime
        if backgroundAudioURL != nil {
            guard let originalAudioTrack = try await source.loadTracks(withMediaType: .audio).first else {
                throw TimelineMixError.noOriginalAudioTrack
            }
            let originalRange = try await originalAudioTrack.load(.timeRange)
            backgroundInsertStart = CMTimeMaximum(.zero, originalRange.start)
        } else {
            backgroundInsertStart = sourceAudioRange.start
        }
        let sourceAudioDuration = CMTimeMinimum(
            sourceAudioRange.duration,
            CMTimeMaximum(.zero, CMTimeSubtract(sourceDuration, backgroundInsertStart))
        )
        let dubAsset = AVURLAsset(url: dubbedAudioURL)
        guard let dubTrack = try await dubAsset.loadTracks(withMediaType: .audio).first else {
            throw TimelineMixError.noAudioTrack(dubbedAudioURL.lastPathComponent)
        }
        let dubDuration = try await dubAsset.load(.duration)

        let composition = AVMutableComposition()
        guard let outputOriginalTrack = composition.addMutableTrack(
            withMediaType: .audio,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ), let outputDubTrack = composition.addMutableTrack(
            withMediaType: .audio,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ) else {
            throw TimelineMixError.exportUnavailable
        }
        if CMTimeCompare(sourceAudioDuration, .zero) > 0 {
            try outputOriginalTrack.insertTimeRange(
                CMTimeRange(start: sourceAudioRange.start, duration: sourceAudioDuration),
                of: sourceAudioTrack,
                at: backgroundInsertStart
            )
        }
        try outputDubTrack.insertTimeRange(
            CMTimeRange(start: .zero, duration: dubDuration),
            of: dubTrack,
            at: .zero
        )
        let mixedDuration = CMTimeMaximum(sourceDuration, dubDuration)
        let sourceAudioEnd = CMTimeAdd(backgroundInsertStart, sourceAudioDuration)
        if CMTimeCompare(mixedDuration, sourceAudioEnd) > 0 {
            outputOriginalTrack.insertEmptyTimeRange(CMTimeRange(
                start: sourceAudioEnd,
                duration: CMTimeSubtract(mixedDuration, sourceAudioEnd)
            ))
        }

        guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A) else {
            throw TimelineMixError.exportUnavailable
        }
        exporter.audioMix = makeAudioMix(
            originalTrack: outputOriginalTrack,
            dubTrack: outputDubTrack,
            speechSegments: speechSegments,
            originalBaseVolume: 1,
            dubVolume: mode.dubVolume,
            duckStrength: 1,
            duckFloor: mode.duckFloor,
            duckFadeSeconds: mode.duckFadeSeconds
        )
        try await export(exporter, to: destination, as: .m4a)
    }

    private func makeAudioMix(
        originalTrack: AVCompositionTrack,
        dubTrack: AVCompositionTrack,
        speechSegments: [DubSpeechSegment],
        originalBaseVolume: Float,
        dubVolume: Float,
        duckStrength: Float,
        duckFloor: Float,
        duckFadeSeconds: TimeInterval
    ) -> AVAudioMix {
        let mix = AVMutableAudioMix()
        let original = AVMutableAudioMixInputParameters(track: originalTrack)
        let dub = AVMutableAudioMixInputParameters(track: dubTrack)
        original.setVolume(originalBaseVolume, at: .zero)
        applyDuckingEnvelope(
            to: original,
            speechSegments: speechSegments,
            baseVolume: originalBaseVolume,
            strength: duckStrength,
            duckFloor: duckFloor,
            duckFadeSeconds: duckFadeSeconds
        )
        dub.setVolume(dubVolume, at: .zero)
        mix.inputParameters = [original, dub]
        return mix
    }

    private func applyDuckingEnvelope(
        to parameters: AVMutableAudioMixInputParameters,
        speechSegments: [DubSpeechSegment],
        baseVolume: Float,
        strength: Float,
        duckFloor: Float,
        duckFadeSeconds: TimeInterval
    ) {
        let safeStrength = min(max(strength, 0), 1)
        let duckMultiplier = 1 - safeStrength * (1 - duckFloor)
        let duckVolume = baseVolume * duckMultiplier
        for interval in mergedSpeechIntervals(speechSegments, duckFadeSeconds: duckFadeSeconds) {
            let fadeOutStart = max(0, interval.start - duckFadeSeconds)
            if interval.start > fadeOutStart {
                parameters.setVolumeRamp(
                    fromStartVolume: baseVolume,
                    toEndVolume: duckVolume,
                    timeRange: CMTimeRange(
                        start: CMTime(seconds: fadeOutStart, preferredTimescale: 600),
                        duration: CMTime(seconds: interval.start - fadeOutStart, preferredTimescale: 600)
                    )
                )
            } else {
                parameters.setVolume(duckVolume, at: .zero)
            }
            parameters.setVolume(duckVolume, at: CMTime(seconds: interval.start, preferredTimescale: 600))
            parameters.setVolumeRamp(
                fromStartVolume: duckVolume,
                toEndVolume: baseVolume,
                timeRange: CMTimeRange(
                    start: CMTime(seconds: interval.end, preferredTimescale: 600),
                    duration: CMTime(seconds: duckFadeSeconds, preferredTimescale: 600)
                )
            )
        }
    }

    private struct SpeechInterval {
        var start: TimeInterval
        var end: TimeInterval
    }

    private func mergedSpeechIntervals(_ segments: [DubSpeechSegment], duckFadeSeconds: TimeInterval) -> [SpeechInterval] {
        let mergeGap = duckFadeSeconds * 2
        let ordered = segments
            .map { SpeechInterval(start: Double($0.startMs) / 1_000, end: Double($0.endMs) / 1_000) }
            .sorted { $0.start < $1.start }
        var merged: [SpeechInterval] = []
        for interval in ordered {
            guard var last = merged.last else {
                merged.append(interval)
                continue
            }
            if interval.start <= last.end + mergeGap {
                last.end = max(last.end, interval.end)
                merged[merged.count - 1] = last
            } else {
                merged.append(interval)
            }
        }
        return merged
    }

    private func remuxVideo(
        sourceVideoURL: URL,
        mixedAudioURL: URL,
        destination: URL
    ) async throws {
        let source = AVURLAsset(url: sourceVideoURL)
        guard let sourceVideoTrack = try await source.loadTracks(withMediaType: .video).first else {
            throw TimelineMixError.noVideoTrack
        }
        let sourceDuration = try await source.load(.duration)
        let mixedAsset = AVURLAsset(url: mixedAudioURL)
        guard let mixedTrack = try await mixedAsset.loadTracks(withMediaType: .audio).first else {
            throw TimelineMixError.noAudioTrack(mixedAudioURL.lastPathComponent)
        }
        let mixedDuration = try await mixedAsset.load(.duration)

        let composition = AVMutableComposition()
        guard let outputVideoTrack = composition.addMutableTrack(
            withMediaType: .video,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ), let outputAudioTrack = composition.addMutableTrack(
            withMediaType: .audio,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ) else {
            throw TimelineMixError.exportUnavailable
        }

        try outputVideoTrack.insertTimeRange(
            CMTimeRange(start: .zero, duration: sourceDuration),
            of: sourceVideoTrack,
            at: .zero
        )
        outputVideoTrack.preferredTransform = try await sourceVideoTrack.load(.preferredTransform)
        try outputAudioTrack.insertTimeRange(
            CMTimeRange(start: .zero, duration: mixedDuration),
            of: mixedTrack,
            at: .zero
        )
        if CMTimeCompare(mixedDuration, sourceDuration) > 0 {
            outputVideoTrack.insertEmptyTimeRange(CMTimeRange(
                start: sourceDuration,
                duration: CMTimeSubtract(mixedDuration, sourceDuration)
            ))
        }

        guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough) else {
            throw TimelineMixError.exportUnavailable
        }
        try await export(exporter, to: destination, as: .mp4)
    }

    private func export(_ exporter: AVAssetExportSession, to destination: URL, as fileType: AVFileType) async throws {
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.createDirectory(
            at: destination.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )

        if #available(iOS 18.0, *) {
            do {
                try await exporter.export(to: destination, as: fileType)
            } catch {
                throw TimelineMixError.exportFailed(error.localizedDescription)
            }
            return
        }

        exporter.outputURL = destination
        exporter.outputFileType = fileType
        let box = LegacyExportSessionBox(exporter)
        try await withCheckedThrowingContinuation { continuation in
            box.session.exportAsynchronously {
                if box.session.status == .completed {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: TimelineMixError.exportFailed(
                        box.session.error?.localizedDescription ?? "Unknown export error"
                    ))
                }
            }
        }
    }

    private func renderedRootDirectory() throws -> URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let directory = caches.appendingPathComponent("LingoPlay/Rendered", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private func validateNonEmpty(_ url: URL) throws {
        let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
        guard values.isRegularFile == true, (values.fileSize ?? 0) > 0 else {
            throw TimelineMixError.emptyOutput(url.lastPathComponent)
        }
    }

    private func purgeRenderCache(parent: URL, excluding: URL? = nil) {
        let manager = FileManager.default
        let keys: Set<URLResourceKey> = [.contentModificationDateKey, .isDirectoryKey]
        guard var directories = try? manager.contentsOfDirectory(
            at: parent,
            includingPropertiesForKeys: Array(keys),
            options: [.skipsHiddenFiles]
        ) else { return }

        let cutoff = Date().addingTimeInterval(-cacheMaxAge)
        for directory in directories where directory != excluding {
            let values = try? directory.resourceValues(forKeys: keys)
            if values?.isDirectory == true, let modified = values?.contentModificationDate, modified < cutoff {
                try? manager.removeItem(at: directory)
            }
        }

        directories = (try? manager.contentsOfDirectory(
            at: parent,
            includingPropertiesForKeys: Array(keys),
            options: [.skipsHiddenFiles]
        )) ?? []
        let candidates = directories
            .filter { $0 != excluding }
            .sorted {
                let lhs = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let rhs = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                return lhs < rhs
            }
        var total = directories.reduce(Int64(0)) { $0 + directorySize($1) }
        guard total > cacheMaxBytes else { return }
        for directory in candidates {
            let size = directorySize(directory)
            if (try? manager.removeItem(at: directory)) != nil {
                total -= size
            }
            if total <= cacheTargetBytes { break }
        }
    }

    private func directorySize(_ url: URL) -> Int64 {
        let manager = FileManager.default
        guard let enumerator = manager.enumerator(
            at: url,
            includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else { return 0 }
        var total: Int64 = 0
        for case let fileURL as URL in enumerator {
            let values = try? fileURL.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            if values?.isRegularFile == true {
                total += Int64(values?.fileSize ?? 0)
            }
        }
        return total
    }
}
