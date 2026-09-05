import AVFoundation
import XCTest
@testable import LingoPlay

final class Stage19RuntimeTests: XCTestCase {
    func testReferenceDecoderReadsOnlySelectedWindow() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".wav")
        defer { try? FileManager.default.removeItem(at: url) }
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 24_000, channels: 1))
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 24_000 * 20))
        buffer.frameLength = buffer.frameCapacity
        let channel = try XCTUnwrap(buffer.floatChannelData?[0])
        for index in 0..<Int(buffer.frameLength) {
            channel[index] = index < 24_000 * 17 ? 0.8 : 0.25
        }
        do {
            let file = try AVAudioFile(forWriting: url, settings: format.settings)
            try file.write(from: buffer)
        }
        let transcript = ASRTranscript(language: "en", text: "window", segments: [
            ASRSegment(id: 0, start: 17, end: 19, text: "This is the reference window.", speakerID: "speaker_1"),
        ])
        let references = try await VoiceCloneReferenceBuilder.build(audioURL: url, transcript: transcript)
        let reference = try XCTUnwrap(references["speaker_1"])
        XCTAssertLessThanOrEqual(abs(reference.samples.count - 48_000), 64)
        XCTAssertEqual(reference.samples[1_000], 0.25, accuracy: 0.001)
    }

    func testCancelledCheckpointSavePreservesPreviousRecord() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mp4")
        try Data([1]).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let store = ProcessingRecoveryStore()
        let before = await store.load()
        let media = LocalMediaItem(id: UUID(), localURL: url, title: "cancelled", duration: 1, fileSizeBytes: 1, hasAudioTrack: true)
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            try await store.save(media: media, processingRunID: UUID())
        }
        do {
            try await task.value
            XCTFail("Cancelled work must not write a checkpoint")
        } catch is CancellationError {
            // Expected at the actor storage boundary.
        }
        let after = await store.load()
        XCTAssertEqual(after, before)
    }

    @MainActor
    func testSpeechTailSurvivesMixRemuxAndPlaybackComposition() async throws {
        let bundle = Bundle(for: Self.self)
        let source = try XCTUnwrap(bundle.url(forResource: "stage6-source", withExtension: "mp4"))
        let clip = try XCTUnwrap(bundle.url(forResource: "stage6-dub-1", withExtension: "wav"))
        let media = LocalMediaItem(id: UUID(), localURL: source, title: "tail", duration: 3, fileSizeBytes: 1, hasAudioTrack: true)
        let dub = DubSpeechDocument(voiceIdentifier: "fixture", segments: [
            DubSpeechSegment(id: "tail", startMs: 2_900, endMs: 3_450, audioURL: clip,
                             speechDurationMs: 550, tailSilenceMs: 0, rateMultiplier: 1),
        ])
        let service = TimelineMixService()
        let result = try await service.render(media: media, dub: dub) { _ in }
        defer { try? FileManager.default.removeItem(at: result.remuxedVideoURL.deletingLastPathComponent()) }
        XCTAssertGreaterThan(result.duration, 3.3)
        let asset = AVURLAsset(url: result.remuxedVideoURL)
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        let audio = try XCTUnwrap(tracks.first)
        let range = try await audio.load(.timeRange)
        XCTAssertGreaterThan(range.end.seconds, 3.3)
        let playback = try await service.makePlaybackSession(
            media: media, dubbedAudioURL: result.dubbedAudioURL, speechSegments: dub.segments, blend: 0.6
        )
        let duration = try await playback.item.asset.load(.duration)
        XCTAssertGreaterThan(duration.seconds, 3.3)
    }
}
