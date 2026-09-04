package com.lingoplay.app

import android.content.Context


enum class SourceLanguageChoice(val code: String?, val label: String) {
    AUTO(null, "Auto Detect"),
    ENGLISH("en", "English"),
    VIETNAMESE("vi", "Vietnamese"),
    JAPANESE("ja", "Japanese"),
    CHINESE("zh", "Chinese"),
    ;

    fun next(): SourceLanguageChoice = entries[(ordinal + 1) % entries.size]
}

enum class TargetLanguageChoice(val code: String, val label: String) {
    VIETNAMESE("vi", "Vietnamese"),
    ENGLISH("en", "English"),
    JAPANESE("ja", "Japanese"),
    CHINESE("zh", "Chinese"),
    ;

    fun next(availableLanguageCodes: Set<String>): TargetLanguageChoice {
        val eligible = entries.filter { it.code in availableLanguageCodes }.ifEmpty { listOf(VIETNAMESE) }
        val index = eligible.indexOf(this).takeIf { it >= 0 } ?: -1
        return eligible[(index + 1).mod(eligible.size)]
    }
}

enum class DubbingModePreset(
    val label: String,
    val detail: String,
    val duckFloor: Float,
    val dubGain: Float,
    val duckFadeMs: Int,
) {
    BALANCED("Balanced", "Natural voice + soundtrack balance", 0.16f, 0.92f, 120),
    SPEECH_FOCUS("Speech Focus", "Stronger ducking for clearer dialogue", 0.08f, 1.00f, 120),
    ORIGINAL_FOCUS("Original Focus", "Keeps more of the original soundtrack", 0.34f, 0.82f, 100),
    ;

    fun next(): DubbingModePreset = entries[(ordinal + 1) % entries.size]
}

enum class SubtitleMode(val label: String) {
    BILINGUAL("Bilingual"),
    TRANSLATED("Translated"),
    OFF("Off"),
    ;

    fun next(): SubtitleMode = entries[(ordinal + 1) % entries.size]
}

data class OfflineVoiceOption(
    val id: String,
    val label: String,
    val languageCode: String,
)

object DubbingPreferencePolicy {
    val playbackSpeeds = listOf(0.75f, 1.0f, 1.25f, 1.5f)

    fun nextPlaybackSpeed(current: Float): Float {
        val nearest = playbackSpeeds.indices.minByOrNull { kotlin.math.abs(playbackSpeeds[it] - current) } ?: 1
        return playbackSpeeds[(nearest + 1) % playbackSpeeds.size]
    }
}

class DubbingPreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("lingoplay_dubbing", Context.MODE_PRIVATE)

    var sourceLanguage: SourceLanguageChoice
        get() = enumValue("source_language", SourceLanguageChoice.AUTO)
        set(value) = prefs.edit().putString("source_language", value.name).apply()

    var targetLanguage: TargetLanguageChoice
        get() = enumValue("target_language", TargetLanguageChoice.VIETNAMESE)
        set(value) = prefs.edit().putString("target_language", value.name).apply()

    var dubbingMode: DubbingModePreset
        get() = enumValue("dubbing_mode", DubbingModePreset.BALANCED)
        set(value) = prefs.edit().putString("dubbing_mode", value.name).apply()

    var subtitleMode: SubtitleMode
        get() = enumValue("subtitle_mode", SubtitleMode.BILINGUAL)
        set(value) = prefs.edit().putString("subtitle_mode", value.name).apply()

    var playbackSpeed: Float
        get() = prefs.getFloat("playback_speed", 1.0f).takeIf { it in 0.5f..2.0f } ?: 1.0f
        set(value) = prefs.edit().putFloat("playback_speed", value.coerceIn(0.5f, 2.0f)).apply()

    var preferredVoiceId: String?
        get() = prefs.getString("preferred_voice_id", null)?.takeIf(String::isNotBlank)
        set(value) = prefs.edit().putString("preferred_voice_id", value.orEmpty()).apply()

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(prefs.getString(key, fallback.name) ?: fallback.name) }.getOrDefault(fallback)
}
