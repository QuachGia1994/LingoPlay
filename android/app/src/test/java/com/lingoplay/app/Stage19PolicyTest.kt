package com.lingoplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage19PolicyTest {
    @Test
    fun multiSpeakerAsrUsesShorterBoundedChunks() {
        assertEquals(6, SpeakerAwareASRPolicy.chunkSeconds(25, SpeakerMode.MULTI))
        assertEquals(5, SpeakerAwareASRPolicy.chunkSeconds(5, SpeakerMode.MULTI))
        assertEquals(25, SpeakerAwareASRPolicy.chunkSeconds(25, SpeakerMode.SINGLE))
    }

    @Test
    fun speakerLabelsAreStableByFirstAppearance() {
        val document = SpeakerDiarizationPolicy.normalize(
            listOf(
                Triple(0.0f, 1.0f, 7),
                Triple(1.0f, 2.0f, 3),
                Triple(2.0f, 3.0f, 7),
            ),
        )

        assertEquals(listOf("speaker_1", "speaker_2"), document.speakerIds)
        assertEquals(
            listOf("speaker_1", "speaker_2", "speaker_1"),
            document.turns.map(SpeakerTurn::speakerId),
        )
    }

    @Test
    fun overlapIsUnknownInsteadOfFabricatedIdentity() {
        val document = SpeakerDiarizationDocument(
            turns = listOf(
                SpeakerTurn(0, 1_000, "speaker_1"),
                SpeakerTurn(400, 900, "speaker_2"),
            ),
            speakerIds = listOf("speaker_1", "speaker_2"),
        )

        val overlap = SpeakerDiarizationPolicy.attribution(450, 800, document)
        assertNull(overlap.speakerId)
        assertEquals(setOf("speaker_1", "speaker_2"), overlap.overlappingSpeakerIds.toSet())

        val clean = SpeakerDiarizationPolicy.attribution(50, 300, document)
        assertEquals("speaker_1", clean.speakerId)
        assertTrue(clean.overlappingSpeakerIds.isEmpty())
    }

    @Test
    fun speakerVoiceMappingUsesDistinctInstalledVoicesWhenAvailable() {
        val mapping = SpeakerVoicePolicy.resolve(
            speakerIds = listOf("speaker_1", "speaker_2"),
            availableVoices = listOf(
                OfflineVoiceOption("vi-a", "Vietnamese A", "vi"),
                OfflineVoiceOption("vi-b", "Vietnamese B", "vi-VN"),
                OfflineVoiceOption("en-a", "English A", "en"),
            ),
            targetLanguage = "vi",
            preferredVoiceId = "vi-b",
        )

        assertEquals("vi-b", mapping["speaker_1"])
        assertEquals("vi-a", mapping["speaker_2"])
        assertEquals(2, mapping.values.toSet().size)
    }

    @Test
    fun cloningRequiresSupportedTargetAndClearSingleSpeakerReference() {
        assertTrue(VoiceCloningPolicy.supportsTarget("en-US"))
        assertTrue(VoiceCloningPolicy.supportsTarget("zh-Hans"))
        assertFalse(VoiceCloningPolicy.supportsTarget("vi"))
        assertFalse(VoiceCloningPolicy.supportsTarget("ja"))

        val transcript = ASRTranscript(
            language = "en",
            text = "reference candidates",
            segments = listOf(
                ASRSegment(0, 0f, 2f, "short valid text", "speaker_1"),
                ASRSegment(1, 2f, 5f, "this is the longer valid reference text", "speaker_1"),
                ASRSegment(2, 5f, 8f, "overlapping speech must be rejected", null, listOf("speaker_1", "speaker_2")),
                ASRSegment(3, 8f, 8.8f, "too short", "speaker_2"),
            ),
        )

        val selected = VoiceCloningPolicy.eligibleReferenceSegments(transcript)
        assertEquals(setOf("speaker_1"), selected.keys)
        assertEquals(1, selected["speaker_1"]?.id)
    }

    @Test
    fun processingConfigRoundTripPreservesStage19Snapshot() {
        val original = ProcessingConfig(
            sourceLanguage = SourceLanguageChoice.ENGLISH,
            targetLanguage = TargetLanguageChoice.CHINESE,
            preferredVoiceId = "zh-a",
            dubbingMode = DubbingModePreset.BALANCED,
            subtitleMode = SubtitleMode.BILINGUAL,
            translationMode = TranslationMode.OFFLINE,
            speakerMode = SpeakerMode.MULTI,
            speakerVoiceMap = mapOf("speaker_1" to "zh-a", "speaker_2" to "zh-b"),
            voiceCloningEnabled = true,
        )

        assertEquals(original, original.toRecord().toConfig())
        val sanitized = original.toRecord().copy(
            speakerVoiceMap = original.speakerVoiceMap + ("not-a-speaker" to "bad"),
        ).toConfig()
        assertEquals(original.speakerVoiceMap, sanitized?.speakerVoiceMap)
    }
}
