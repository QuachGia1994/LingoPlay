import XCTest
@testable import LingoPlay

final class PolicyTests: XCTestCase {
    func testTranslationEndpointUsesValidOverride() {
        let override = "https://translation.example.test"
        XCTAssertEqual(
            TranslationEndpointConfiguration.resolve(plistValue: override)?.absoluteString,
            override
        )
    }

    func testTranslationEndpointFallsBackToProduction() {
        let expected = TranslationEndpointConfiguration.productionBaseURLString
        XCTAssertEqual(TranslationEndpointConfiguration.resolve(plistValue: nil)?.absoluteString, expected)
        XCTAssertEqual(TranslationEndpointConfiguration.resolve(plistValue: "   ")?.absoluteString, expected)
        XCTAssertEqual(TranslationEndpointConfiguration.resolve(plistValue: "not-a-url")?.absoluteString, expected)
        XCTAssertEqual(TranslationEndpointConfiguration.resolve(plistValue: "http://unsafe.test")?.absoluteString, expected)
        XCTAssertEqual(TranslationEndpointConfiguration.resolve(plistValue: "https://safe.test?q=1")?.absoluteString, expected)
    }

    func testPlaybackSpeedSanitization() {
        XCTAssertEqual(DubbingPreferencePolicy.sanitizedPlaybackSpeed(1.25), 1.25)
        XCTAssertEqual(DubbingPreferencePolicy.sanitizedPlaybackSpeed(0), 1.0)
        XCTAssertEqual(DubbingPreferencePolicy.sanitizedPlaybackSpeed(.nan), 1.0)
        XCTAssertEqual(DubbingPreferencePolicy.sanitizedPlaybackSpeed(nil), 1.0)
    }

    func testSavedSubtitleDocumentControlsLabelsAndEndBoundary() {
        let segment = TranslationSegment(
            id: "s1",
            startMs: 1_000,
            endMs: 2_000,
            sourceText: "hello",
            translatedText: "xin chào"
        )
        let document = TranslationDocument(
            sourceLanguage: "en",
            targetLanguage: "vi",
            segments: [segment]
        )

        XCTAssertEqual(
            PlaybackPresentationPolicy.activeSegment(in: document, positionSeconds: 2.0),
            segment
        )
        XCTAssertEqual(
            PlaybackPresentationPolicy.sourceLanguageLabel(document: document, fallback: "SRC"),
            "EN"
        )
        XCTAssertEqual(
            PlaybackPresentationPolicy.targetLanguageLabel(document: document, fallback: "TR"),
            "VI"
        )
    }

    func testPlaybackPresentationFallsBackWithoutDocument() {
        XCTAssertNil(PlaybackPresentationPolicy.activeSegment(in: nil, positionSeconds: 1.0))
        XCTAssertEqual(PlaybackPresentationPolicy.sourceLanguageLabel(document: nil, fallback: "SRC"), "SRC")
        XCTAssertEqual(PlaybackPresentationPolicy.targetLanguageLabel(document: nil, fallback: "JA"), "JA")
    }
}
