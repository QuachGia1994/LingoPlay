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

    func testTranslationTextPolicyRemovesControlsAndCorrectsLanguage() {
        XCTAssertEqual(
            TranslationTextPolicy.speechText(
                "<|startoftranscript|><transcribe><0.00>[Music] We have to shut it down. <12.00>"
            ),
            "We have to shut it down."
        )
        XCTAssertEqual(
            TranslationTextPolicy.sourceLanguage(
                reported: "th",
                text: "We have to shut it down. Please tell me how you can do this."
            ),
            "en"
        )
        XCTAssertEqual(
            TranslationTextPolicy.sourceLanguage(reported: "th", text: "สวัสดีครับ วันนี้อากาศดีมาก"),
            "th"
        )
    }

    func testDurationOverflowExtendsLocalSpeechWindowWithoutFatalFit() {
        XCTAssertEqual(
            DurationFitPolicy.effectiveEndMs(startMs: 1_000, sourceEndMs: 2_000, speechDurationMs: 1_600),
            2_600
        )
        XCTAssertEqual(
            DurationFitPolicy.effectiveEndMs(startMs: 1_000, sourceEndMs: 2_000, speechDurationMs: 800),
            2_000
        )
        XCTAssertNil(
            DurationFitPolicy.nextRateMultiplier(
                actualMs: 2_600,
                targetMs: 1_000,
                current: DurationFitPolicy.maxRateMultiplier
            )
        )
    }

    func testTTSSynthesisTimeoutIsBoundedAndScalesWithWork() {
        XCTAssertEqual(
            TTSSynthesisLivenessPolicy.timeoutSeconds(textLength: 1, targetDurationMs: 1),
            TTSSynthesisLivenessPolicy.minimumTimeoutSeconds
        )
        XCTAssertGreaterThan(
            TTSSynthesisLivenessPolicy.timeoutSeconds(textLength: 120, targetDurationMs: 8_000),
            TTSSynthesisLivenessPolicy.minimumTimeoutSeconds
        )
        XCTAssertEqual(
            TTSSynthesisLivenessPolicy.timeoutSeconds(textLength: 10_000, targetDurationMs: 600_000),
            TTSSynthesisLivenessPolicy.maximumTimeoutSeconds
        )
        XCTAssertEqual(
            TTSSynthesisLivenessPolicy.timeoutNanoseconds(textLength: 1, targetDurationMs: 1),
            20_000_000_000
        )
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
