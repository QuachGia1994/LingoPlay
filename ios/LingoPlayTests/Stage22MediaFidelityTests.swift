import AVFoundation
import XCTest
@testable import LingoPlay

final class Stage22MediaFidelityTests: XCTestCase {
    func testDisplayTextPreservesCuesWhileSpokenTextRemovesThem() {
        let segment = TranslationSegment(
            id: "s0",
            startMs: 0,
            endMs: 1_000,
            sourceText: "hello",
            translatedText: "  Xin chào   [cười]   thế giới  "
        )
        XCTAssertEqual(segment.displayText, "Xin chào [cười] thế giới")
        XCTAssertEqual(segment.spokenText, "Xin chào thế giới")
    }

    func testPersistedAudioFramesAreTheDurationAuthority() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".wav")
        defer { try? FileManager.default.removeItem(at: url) }
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1))
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 48_000))
        buffer.frameLength = 48_000
        let file = try AVAudioFile(forWriting: url, settings: format.settings)
        try file.write(from: buffer)
        XCTAssertEqual(try SynthesizedAudioPolicy.durationMs(of: url), 1_000)
    }

    func testSpeechSeamFadeIsEightMillisecondsAndClampsToHalfShortClip() {
        XCTAssertEqual(SpeechSeamPolicy.edgeFadeSeconds(clipDuration: 1), 0.008, accuracy: 0.000_001)
        XCTAssertEqual(SpeechSeamPolicy.edgeFadeSeconds(clipDuration: 0.004), 0.002, accuracy: 0.000_001)
    }

    func testDelayedAudioStartIsReadFromOriginalAssetTimeline() async throws {
        let source = try fixture("stage22-shifted-audio", "mp4")
        let offset = try await MediaTimelinePolicy.audioStartOffsetSeconds(for: source)
        XCTAssertGreaterThan(offset, 0.45)
        XCTAssertLessThan(offset, 0.55)
    }

    func testNoAudioFixtureFailsClosed() async throws {
        let source = try fixture("stage22-no-audio", "mp4")
        do {
            _ = try await MediaTimelinePolicy.audioStartOffsetSeconds(for: source)
            XCTFail("No-audio video must fail closed instead of fabricating an audio timeline.")
        } catch LocalMediaError.noAudioTrack {
            // Expected.
        }
    }

    @MainActor
    func testFractionalAndVariableFrameRatePresentationTimestampsSurvivePassthroughRemux() async throws {
        let dubClip = try fixture("stage6-dub-1", "wav")
        for name in ["stage22-23976", "stage22-2997", "stage22-vfr", "stage22-subsecond"] {
            let source = try fixture(name, "mp4")
            let asset = AVURLAsset(url: source)
            let duration = try await asset.load(.duration).seconds
            let audioTrack = try XCTUnwrap(try await asset.loadTracks(withMediaType: .audio).first)
            let descriptions = try await audioTrack.load(.formatDescriptions)
            let description = try XCTUnwrap(descriptions.first)
            let basic = try XCTUnwrap(CMAudioFormatDescriptionGetStreamBasicDescription(description)?.pointee)
            XCTAssertEqual(basic.mSampleRate, 48_000, accuracy: 0.5, "\(name) must stay 48 kHz")
            XCTAssertEqual(basic.mChannelsPerFrame, 1, "\(name) must stay mono")

            let media = LocalMediaItem(
                localURL: source,
                title: name,
                duration: duration,
                fileSizeBytes: 1,
                hasAudioTrack: true
            )
            let dub = DubSpeechDocument(
                voiceIdentifier: "stage22-fixture",
                segments: [
                    DubSpeechSegment(
                        id: "d",
                        startMs: 100,
                        endMs: 650,
                        audioURL: dubClip,
                        speechDurationMs: 550,
                        tailSilenceMs: 0,
                        rateMultiplier: 1
                    ),
                ]
            )
            let result = try await TimelineMixService().render(media: media, dub: dub) { _ in }
            defer { try? FileManager.default.removeItem(at: result.remuxedVideoURL.deletingLastPathComponent()) }

            let sourcePTS = try await videoPresentationTimes(source)
            let outputPTS = try await videoPresentationTimes(result.remuxedVideoURL)
            XCTAssertEqual(sourcePTS.count, outputPTS.count, "\(name) frame count changed")
            for (index, pair) in zip(sourcePTS, outputPTS).enumerated() {
                XCTAssertEqual(CMTimeCompare(pair.0, pair.1), 0, "\(name) PTS changed at sample \(index)")
            }
        }
    }

    func testExactTenTwentySecondAndSubSecondSpleeterChunkWindowsCoverEveryCoreFrameOnce() throws {
        try assertChunkCoverage(seconds: 10.0, expectedCoreStarts: [0], expectedCoreFrames: [480_000])
        try assertChunkCoverage(seconds: 20.0, expectedCoreStarts: [0, 480_000], expectedCoreFrames: [480_000, 480_000])
        try assertChunkCoverage(seconds: 0.75, expectedCoreStarts: [0], expectedCoreFrames: [36_000])
    }

    private func assertChunkCoverage(
        seconds: Double,
        expectedCoreStarts: [Int64],
        expectedCoreFrames: [Int]
    ) throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".wav")
        defer { try? FileManager.default.removeItem(at: url) }
        let sampleRate = 48_000.0
        let frames = AVAudioFrameCount((sampleRate * seconds).rounded())
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1))
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames))
        buffer.frameLength = frames
        if let channel = buffer.floatChannelData?[0] {
            for index in 0..<Int(frames) { channel[index] = 0 }
        }
        let file = try AVAudioFile(forWriting: url, settings: format.settings)
        try file.write(from: buffer)

        let reader = try StereoFloatChunkReader(url: url, coreSeconds: 10, contextMilliseconds: 500)
        var starts: [Int64] = []
        var counts: [Int] = []
        while let chunk = try reader.nextChunk() {
            starts.append(chunk.coreStartFrame)
            counts.append(chunk.coreFrames)
        }
        XCTAssertEqual(starts, expectedCoreStarts)
        XCTAssertEqual(counts, expectedCoreFrames)
        XCTAssertEqual(counts.reduce(0, +), Int(frames))
    }

    private func fixture(_ name: String, _ ext: String) throws -> URL {
        try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: ext))
    }

    private func videoPresentationTimes(_ url: URL) async throws -> [CMTime] {
        let asset = AVURLAsset(url: url)
        let track = try XCTUnwrap(try await asset.loadTracks(withMediaType: .video).first)
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
        XCTAssertTrue(reader.canAdd(output))
        reader.add(output)
        XCTAssertTrue(reader.startReading())
        var result: [CMTime] = []
        while let sample = output.copyNextSampleBuffer() {
            result.append(CMSampleBufferGetPresentationTimeStamp(sample))
        }
        XCTAssertEqual(reader.status, .completed, reader.error?.localizedDescription ?? "AVAssetReader failed")
        return result
    }
}
