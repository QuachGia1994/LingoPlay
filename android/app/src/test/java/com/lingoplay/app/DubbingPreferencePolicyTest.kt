package com.lingoplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingPreferencePolicyTest {
    @Test
    fun playbackSpeedCyclesThroughSupportedRates() {
        assertEquals(1.25f, DubbingPreferencePolicy.nextPlaybackSpeed(1.0f), 0.001f)
        assertEquals(0.75f, DubbingPreferencePolicy.nextPlaybackSpeed(1.5f), 0.001f)
    }

    @Test
    fun targetLanguageCyclesOnlyThroughInstalledVoiceLanguages() {
        val next = TargetLanguageChoice.VIETNAMESE.next(setOf("vi", "ja"))
        assertEquals(TargetLanguageChoice.JAPANESE, next)
        assertEquals(TargetLanguageChoice.VIETNAMESE, next.next(setOf("vi", "ja")))
    }

    @Test
    fun processingConfigRoundTripPreservesRecoverySnapshot() {
        val expected = ProcessingConfig(
            sourceLanguage = SourceLanguageChoice.JAPANESE,
            targetLanguage = TargetLanguageChoice.JAPANESE,
            preferredVoiceId = "ja-offline-a",
            dubbingMode = DubbingModePreset.SPEECH_FOCUS,
            subtitleMode = SubtitleMode.TRANSLATED,
        )
        assertEquals(expected, expected.toRecord().toConfig())
    }

    @Test
    fun invalidRecoveryConfigDoesNotPartiallyApply() {
        val invalid = ProcessingConfigRecord(
            sourceLanguage = "JAPANESE",
            targetLanguage = "NOT_A_LANGUAGE",
            preferredVoiceId = "voice",
            dubbingMode = "SPEECH_FOCUS",
            subtitleMode = "BILINGUAL",
        )
        assertEquals(null, invalid.toConfig())
    }

    @Test
    fun cleanBackgroundRemainsUnavailableWithoutVerifiedEngine() {
        assertTrue(!CleanBackgroundCapability.isAvailable)
        assertEquals(SourceSeparationAvailability.UNAVAILABLE, CleanBackgroundCapability.engine.availability)
    }
}
