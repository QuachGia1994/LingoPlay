import XCTest
@testable import LingoPlay

final class PolicyTests: XCTestCase {
    func testHostAppHasResolvedVersionMetadata() throws {
        let version = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)
        let build = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String)
        XCTAssertNotNil(version.range(of: #"^[0-9]+\.[0-9]+\.[0-9]+$"#, options: .regularExpression))
        XCTAssertNotNil(build.range(of: #"^[0-9]+(\.[0-9]+){0,2}$"#, options: .regularExpression))
    }

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

    func testOfflineTranslationRequiresDownloadableModelAndNeverNeedsOneForIdentity() throws {
        XCTAssertEqual(
            try OfflineTranslationLanguagePolicy.requiredModelCodes(
                sourceLanguage: "en-US",
                targetLanguage: "vi"
            ),
            Set(["vi"])
        )
        XCTAssertEqual(
            try OfflineTranslationLanguagePolicy.requiredModelCodes(
                sourceLanguage: "ja",
                targetLanguage: "ja"
            ),
            []
        )
        XCTAssertThrowsError(
            try OfflineTranslationLanguagePolicy.requiredModelCodes(
                sourceLanguage: "und",
                targetLanguage: "vi"
            )
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

    func testNeuralVoiceManifestIsPinnedExactly() {
        XCTAssertEqual(NeuralVoicePackManifest.sourceRevision, "3d796cc2f2c884b3517c527507e084f7bb245aea")
        XCTAssertEqual(NeuralVoicePackManifest.archiveBytes, 67_154_040)
        XCTAssertEqual(NeuralVoicePackManifest.modelBytes, 63_149_198)
        XCTAssertEqual(
            NeuralVoicePackManifest.archiveSHA256,
            "fa1367710767d36ed5cf13b4a449e20c35ffd12791c2e47c2e64142bfa55551a"
        )
    }

    func testNeuralVoiceArchiveRejectsTraversalAndLinks() {
        XCTAssertEqual(
            NeuralVoiceArchivePolicy.relativePath(
                for: "vits-piper-vi_VN-vais1000-medium/vi_VN-vais1000-medium.onnx"
            ),
            "vi_VN-vais1000-medium.onnx"
        )
        XCTAssertNil(
            NeuralVoiceArchivePolicy.relativePath(
                for: "vits-piper-vi_VN-vais1000-medium/../escape"
            )
        )
        XCTAssertFalse(
            NeuralVoiceArchivePolicy.allowsEntry(
                name: "vits-piper-vi_VN-vais1000-medium/link",
                isDirectory: false,
                isRegularFile: false,
                size: 1
            )
        )
        XCTAssertFalse(
            NeuralVoiceArchivePolicy.allowsEntry(
                name: "vits-piper-vi_VN-vais1000-medium/huge",
                isDirectory: false,
                isRegularFile: true,
                size: NeuralVoiceArchivePolicy.maximumEntryBytes + 1
            )
        )
    }

    func testNeuralVoiceRequiresExplicitInstalledSelection() {
        XCTAssertEqual(
            TTSRoutingPolicy.route(
                targetLanguage: "vi-VN",
                preferredVoiceIdentifier: NeuralVoicePackManifest.voiceIdentifier,
                neuralVoiceInstalled: true
            ),
            .neural
        )
        XCTAssertEqual(
            TTSRoutingPolicy.route(
                targetLanguage: "vi",
                preferredVoiceIdentifier: NeuralVoicePackManifest.voiceIdentifier,
                neuralVoiceInstalled: false
            ),
            .system
        )
        XCTAssertEqual(
            TTSRoutingPolicy.route(
                targetLanguage: "vi",
                preferredVoiceIdentifier: nil,
                neuralVoiceInstalled: true
            ),
            .system
        )
        XCTAssertEqual(
            TTSRoutingPolicy.route(
                targetLanguage: "ja",
                preferredVoiceIdentifier: NeuralVoicePackManifest.voiceIdentifier,
                neuralVoiceInstalled: true
            ),
            .system
        )
    }

    func testNeuralVoiceThreadCountRemainsBounded() {
        XCTAssertEqual(NeuralTTSPerformancePolicy.threadCount(availableProcessors: 1), 1)
        XCTAssertEqual(NeuralTTSPerformancePolicy.threadCount(availableProcessors: 2), 1)
        XCTAssertEqual(NeuralTTSPerformancePolicy.threadCount(availableProcessors: 8), 2)
    }

    func testVietnameseSystemVoiceUsesNaturalBoundedRate() {
        XCTAssertEqual(SystemVoiceRatePolicy.baseRateScale(languageCode: "vi-VN"), 0.82, accuracy: 0.001)
        XCTAssertEqual(SystemVoiceRatePolicy.maximumFitMultiplier(languageCode: "vi"), 1.18, accuracy: 0.001)
        XCTAssertEqual(
            SystemVoiceRatePolicy.effectiveRateScale(languageCode: "vi", fitMultiplier: 1.75),
            0.82 * 1.18,
            accuracy: 0.001
        )
        XCTAssertEqual(SystemVoiceRatePolicy.baseRateScale(languageCode: "en-US"), 1.0, accuracy: 0.001)
        XCTAssertEqual(
            SystemVoiceRatePolicy.maximumFitMultiplier(languageCode: "en"),
            DurationFitPolicy.maxRateMultiplier,
            accuracy: 0.001
        )
    }

    func testSpeakerLabelsAreStableAndOverlapRemainsUnknown() {
        let normalized = SpeakerDiarizationPolicy.normalize([
            (start: 0.0, end: 1.0, speaker: 7),
            (start: 1.0, end: 2.0, speaker: 3),
            (start: 2.0, end: 3.0, speaker: 7),
        ])
        XCTAssertEqual(normalized.speakerIDs, ["speaker_1", "speaker_2"])
        XCTAssertEqual(normalized.turns.map(\.speakerID), ["speaker_1", "speaker_2", "speaker_1"])

        let overlapDocument = SpeakerDiarizationDocument(
            turns: [
                SpeakerTurn(startMs: 0, endMs: 1_000, speakerID: "speaker_1"),
                SpeakerTurn(startMs: 400, endMs: 900, speakerID: "speaker_2"),
            ],
            speakerIDs: ["speaker_1", "speaker_2"]
        )
        let overlap = SpeakerDiarizationPolicy.attribution(
            startMs: 450,
            endMs: 800,
            document: overlapDocument
        )
        XCTAssertNil(overlap.speakerID)
        XCTAssertEqual(Set(overlap.overlappingSpeakerIDs), Set(["speaker_1", "speaker_2"]))
    }

    func testSpeakerVoiceMappingUsesDistinctInstalledVoicesWhenAvailable() {
        let mapping = SpeakerVoicePolicy.resolve(
            speakerIDs: ["speaker_1", "speaker_2"],
            availableVoices: [
                OfflineVoiceOption(id: "vi-a", label: "Vietnamese A", languageCode: "vi"),
                OfflineVoiceOption(id: "vi-b", label: "Vietnamese B", languageCode: "vi-VN"),
                OfflineVoiceOption(id: "en-a", label: "English A", languageCode: "en"),
            ],
            targetLanguage: "vi",
            preferredVoiceIdentifier: "vi-b"
        )
        XCTAssertEqual(mapping["speaker_1"], "vi-b")
        XCTAssertEqual(mapping["speaker_2"], "vi-a")
        XCTAssertEqual(Set(mapping.values).count, 2)
    }

    func testVoiceCloningPolicyRequiresSupportedTargetAndClearReference() {
        XCTAssertTrue(VoiceCloningPolicy.supportsTarget("en-US"))
        XCTAssertTrue(VoiceCloningPolicy.supportsTarget("zh-Hans"))
        XCTAssertFalse(VoiceCloningPolicy.supportsTarget("vi"))
        XCTAssertFalse(VoiceCloningPolicy.supportsTarget("ja"))

        let transcript = ASRTranscript(
            language: "en",
            text: "reference candidates",
            segments: [
                ASRSegment(id: 0, start: 0, end: 2, text: "short valid text", speakerID: "speaker_1"),
                ASRSegment(id: 1, start: 2, end: 5, text: "this is the longer valid reference text", speakerID: "speaker_1"),
                ASRSegment(
                    id: 2,
                    start: 5,
                    end: 8,
                    text: "overlap must be rejected",
                    speakerID: nil,
                    overlappingSpeakerIDs: ["speaker_1", "speaker_2"]
                ),
                ASRSegment(id: 3, start: 8, end: 8.8, text: "too short", speakerID: "speaker_2"),
            ]
        )
        let selected = VoiceCloningPolicy.eligibleReferenceSegments(transcript)
        XCTAssertEqual(Set(selected.keys), Set(["speaker_1"]))
        XCTAssertEqual(selected["speaker_1"]?.id, 1)
    }

    func testStage19ProcessingConfigCarriesConsentAndSpeakerMap() {
        let config = ProcessingConfig(
            sourceLanguage: .en,
            targetLanguage: .zh,
            preferredVoiceIdentifier: "zh-a",
            dubbingMode: .balanced,
            subtitleMode: .bilingual,
            translationMode: .offline,
            speakerMode: .multi,
            speakerVoiceMap: ["speaker_1": "zh-a", "speaker_2": "zh-b"],
            voiceCloningEnabled: true
        )
        XCTAssertEqual(config.speakerMode, .multi)
        XCTAssertEqual(config.speakerVoiceMap["speaker_2"], "zh-b")
        XCTAssertTrue(config.voiceCloningEnabled)
    }
}
