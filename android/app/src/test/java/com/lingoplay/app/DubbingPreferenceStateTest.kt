package com.lingoplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DubbingPreferenceStateTest {
    @Test
    fun cyclesPersistAndProcessingConfigUsesCurrentSnapshot() {
        val persistence = FakeDubbingPreferencePersistence()
        val state = DubbingPreferenceState(persistence)
        state.updateOfflineVoices(
            listOf(
                OfflineVoiceOption("vi-a", "Vietnamese A", "vi"),
                OfflineVoiceOption("ja-a", "Japanese A", "ja"),
            ),
        )

        state.cycleTargetLanguage()
        assertEquals(TargetLanguageChoice.JAPANESE, state.targetLanguage)
        assertEquals(TargetLanguageChoice.JAPANESE, persistence.targetLanguage)
        assertNull(state.preferredVoiceId)

        state.cycleVoice()
        assertEquals("ja-a", state.preferredVoiceId)
        assertEquals("ja-a", persistence.preferredVoiceId)

        state.cycleDubbingMode()
        state.cycleSubtitleMode()
        state.cyclePlaybackSpeed()

        assertEquals(DubbingModePreset.SPEECH_FOCUS, state.processingConfig.dubbingMode)
        assertEquals(SubtitleMode.TRANSLATED, state.processingConfig.subtitleMode)
        assertEquals("ja-a", state.processingConfig.preferredVoiceId)
        assertEquals(1.25f, state.playbackSpeed, 0.001f)
    }

    @Test
    fun removingInstalledVoiceClearsPersistedSelection() {
        val persistence = FakeDubbingPreferencePersistence()
        val state = DubbingPreferenceState(persistence)

        state.updateOfflineVoices(listOf(OfflineVoiceOption("vi-a", "Vietnamese A", "vi")))
        assertEquals("vi-a", state.preferredVoiceId)

        state.updateOfflineVoices(emptyList())
        assertNull(state.preferredVoiceId)
        assertNull(persistence.preferredVoiceId)
    }

    private class FakeDubbingPreferencePersistence : DubbingPreferencePersistence {
        override var sourceLanguage = SourceLanguageChoice.AUTO
        override var targetLanguage = TargetLanguageChoice.VIETNAMESE
        override var dubbingMode = DubbingModePreset.BALANCED
        override var subtitleMode = SubtitleMode.BILINGUAL
        override var playbackSpeed = 1.0f
        override var preferredVoiceId: String? = "vi-a"
    }
}
