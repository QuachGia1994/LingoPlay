import XCTest
@testable import LingoPlay

@MainActor
final class Stage18RuntimeIntegrityTests: XCTestCase {
    func testCloudTranslationRequestTimeoutIsExplicitAndFinite() {
        XCTAssertEqual(TranslationService.requestTimeoutSeconds, 60)
    }

    func testCloudIdentityTranslationDoesNotNeedNetwork() async throws {
        let transcript = ASRTranscript(
            language: "vi-VN",
            text: "Xin chào thế giới",
            segments: [ASRSegment(id: 0, start: 0, end: 1.5, text: "Xin chào thế giới")]
        )
        let endpoint = try XCTUnwrap(URL(string: "https://127.0.0.1:1"))

        let document = try await TranslationService().translate(
            transcript: transcript,
            targetLanguage: "vi",
            endpoint: endpoint,
            progress: { _, _ in }
        )

        XCTAssertEqual(document.sourceLanguage, "vi")
        XCTAssertEqual(document.targetLanguage, "vi")
        XCTAssertEqual(document.segments.map(\.translatedText), ["Xin chào thế giới"])
    }

    func testNonSpeechTranscriptFailsLocallyWithSpecificError() async throws {
        let transcript = ASRTranscript(
            language: "en",
            text: "[Music]",
            segments: [ASRSegment(id: 0, start: 0, end: 1, text: "[Music]")]
        )
        let endpoint = try XCTUnwrap(URL(string: "https://127.0.0.1:1"))

        do {
            _ = try await TranslationService().translate(
                transcript: transcript,
                targetLanguage: "vi",
                endpoint: endpoint,
                progress: { _, _ in }
            )
            XCTFail("Expected a local no-speech error")
        } catch TranslationError.noSpeechSegments {
            // Expected: no backend request is needed to classify this transcript.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testTTSCacheCleanupDeletesOnlyGeneratedSession() throws {
        let caches = try XCTUnwrap(FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first)
        let session = caches
            .appendingPathComponent("LingoPlay/TTS", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: session, withIntermediateDirectories: true)
        let audio = session.appendingPathComponent("s0-0.caf")
        try Data([0, 1, 2, 3]).write(to: audio)

        let document = DubSpeechDocument(
            voiceIdentifier: "test",
            segments: [
                DubSpeechSegment(
                    id: "s0",
                    startMs: 0,
                    endMs: 1_000,
                    audioURL: audio,
                    speechDurationMs: 800,
                    tailSilenceMs: 200,
                    rateMultiplier: 1
                )
            ]
        )

        TTSCachePolicy.cleanup(document: document)

        XCTAssertFalse(FileManager.default.fileExists(atPath: session.path))
    }
}
