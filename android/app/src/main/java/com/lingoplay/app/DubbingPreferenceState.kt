package com.lingoplay.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Screen-level plain state holder for persisted dubbing preferences.
 * Product policy stays in DubbingPreferencePolicy; this type only owns UI state and persistence wiring.
 */
@Stable
class DubbingPreferenceState(private val store: DubbingPreferencePersistence) {
    var sourceLanguage by mutableStateOf(store.sourceLanguage)
        private set
    var targetLanguage by mutableStateOf(store.targetLanguage)
        private set
    var translationMode by mutableStateOf(store.translationMode)
        private set
    var dubbingMode by mutableStateOf(store.dubbingMode)
        private set
    var subtitleMode by mutableStateOf(store.subtitleMode)
        private set
    var playbackSpeed by mutableFloatStateOf(store.playbackSpeed)
        private set
    var preferredVoiceId by mutableStateOf(store.preferredVoiceId)
        private set
    var speakerMode by mutableStateOf(store.speakerMode)
        private set
    var voiceCloningEnabled by mutableStateOf(store.voiceCloningEnabled)
        private set
    var offlineVoices by mutableStateOf<List<OfflineVoiceOption>>(emptyList())
        private set

    val targetVoices: List<OfflineVoiceOption>
        get() = offlineVoices.filter {
            it.languageCode.substringBefore('-') == targetLanguage.code.substringBefore('-')
        }

    val preferredVoiceLabel: String
        get() = targetVoices.firstOrNull { it.id == preferredVoiceId }?.label ?: "Automatic"

    val processingConfig: ProcessingConfig
        get() = ProcessingConfig(
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            preferredVoiceId = preferredVoiceId,
            dubbingMode = dubbingMode,
            subtitleMode = subtitleMode,
            translationMode = translationMode,
            speakerMode = speakerMode,
            voiceCloningEnabled = voiceCloningEnabled,
        )

    fun updateOfflineVoices(voices: List<OfflineVoiceOption>) {
        offlineVoices = voices
        if (preferredVoiceId != null && voices.none { it.id == preferredVoiceId }) {
            preferredVoiceId = null
            store.preferredVoiceId = null
        }
    }

    fun cycleSourceLanguage() {
        sourceLanguage = sourceLanguage.next().also { store.sourceLanguage = it }
    }

    fun cycleTargetLanguage() {
        val availableCodes = offlineVoices.map { it.languageCode.substringBefore('-') }.toSet()
        targetLanguage = targetLanguage.next(availableCodes).also { store.targetLanguage = it }
        preferredVoiceId = null
        store.preferredVoiceId = null
    }

    fun cycleTranslationMode() {
        translationMode = translationMode.next().also { store.translationMode = it }
    }

    fun cycleDubbingMode() {
        dubbingMode = dubbingMode.next().also { store.dubbingMode = it }
    }

    fun cycleSpeakerMode() {
        speakerMode = speakerMode.next().also { store.speakerMode = it }
    }

    fun updateVoiceCloningConsent(enabled: Boolean) {
        voiceCloningEnabled = enabled
        store.voiceCloningEnabled = enabled
    }

    fun cycleSubtitleMode() {
        subtitleMode = subtitleMode.next().also { store.subtitleMode = it }
    }

    fun cycleVoice() {
        val ids = listOf<String?>(null) + targetVoices.map(OfflineVoiceOption::id)
        val currentIndex = ids.indexOf(preferredVoiceId).takeIf { it >= 0 } ?: 0
        preferredVoiceId = ids[(currentIndex + 1) % ids.size]
        store.preferredVoiceId = preferredVoiceId
    }

    fun cyclePlaybackSpeed() {
        playbackSpeed = DubbingPreferencePolicy.nextPlaybackSpeed(playbackSpeed)
        store.playbackSpeed = playbackSpeed
    }
}
