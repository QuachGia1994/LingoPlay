package com.lingoplay.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt


data class DubSpeechSegment(
    val id: String,
    val startMs: Int,
    val endMs: Int,
    val audioFile: File,
    val speechDurationMs: Int,
    val tailSilenceMs: Int,
    val rateMultiplier: Float,
)

data class DubSpeechDocument(
    val voiceName: String,
    val segments: List<DubSpeechSegment>,
) {
    val totalTailSilenceMs: Int get() = segments.sumOf { it.tailSilenceMs }
}

enum class TTSPhase {
    IDLE,
    VOICE_MISSING,
    SYNTHESIZING,
    COMPLETED,
    FAILED,
}

class OfflineTargetVoiceMissingException(val languageCode: String) : IllegalStateException(
    "No offline system voice is installed for language '$languageCode' on this device.",
)

object DurationFitPolicy {
    const val MAX_RATE_MULTIPLIER = 1.75f
    const val MAXIMUM_ATTEMPTS = 4

    fun targetDurationMs(startMs: Int, endMs: Int): Int = max(1, endMs - startMs)

    fun toleranceMs(targetMs: Int): Int = max(120, (targetMs * 0.06).roundToInt())

    fun fits(actualMs: Int, targetMs: Int): Boolean = actualMs <= targetMs + toleranceMs(targetMs)

    fun tailSilenceMs(actualMs: Int, targetMs: Int): Int = max(0, targetMs - actualMs)

    fun effectiveEndMs(startMs: Int, sourceEndMs: Int, speechDurationMs: Int): Int {
        val speechEnd = startMs.toLong() + max(1, speechDurationMs).toLong()
        return max(sourceEndMs.toLong(), speechEnd).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun nextRateMultiplier(actualMs: Int, targetMs: Int, current: Float): Float? {
        if (fits(actualMs, targetMs) || current >= MAX_RATE_MULTIPLIER - 0.01f) return null
        val ratio = actualMs.toFloat() / max(1, targetMs).toFloat()
        val proposed = max(current * 1.08f, current * ratio * 1.02f)
        val next = minOf(MAX_RATE_MULTIPLIER, proposed)
        return next.takeIf { it > current + 0.01f }
    }
}

object SystemVietnameseTTSService {
    suspend fun synthesize(
        context: Context,
        document: TranslationDocument,
        preferredVoiceName: String? = null,
        onProgress: suspend (segment: Int, total: Int) -> Unit = { _, _ -> },
    ): DubSpeechDocument {
        val tts = createTts(context)
        try {
            val voice = selectOfflineVoice(tts, document.targetLanguage, preferredVoiceName)
                ?: throw OfflineTargetVoiceMissingException(document.targetLanguage)
            check(tts.setVoice(voice) == TextToSpeech.SUCCESS) { "Unable to select the offline system voice." }

            val root = File(context.cacheDir, "lingoplay/tts/${UUID.randomUUID()}").apply { mkdirs() }
            val output = mutableListOf<DubSpeechSegment>()
            document.segments.forEachIndexed { index, segment ->
                output += synthesizeSegment(tts, segment, root)
                onProgress(index + 1, document.segments.size)
            }
            return DubSpeechDocument(voice.name, output)
        } finally {
            withContext(Dispatchers.Main.immediate) { tts.shutdown() }
        }
    }

    suspend fun availableOfflineVoices(context: Context): List<OfflineVoiceOption> {
        val tts = createTts(context)
        return try {
            tts.voices.orEmpty()
                .asSequence()
                .filterNot { it.isNetworkConnectionRequired }
                .map { voice ->
                    OfflineVoiceOption(
                        id = voice.name,
                        label = "${voice.locale.displayLanguage} · ${voice.name}",
                        languageCode = voice.locale.language.lowercase(),
                    )
                }
                .distinctBy(OfflineVoiceOption::id)
                .sortedWith(compareBy<OfflineVoiceOption> { it.languageCode }.thenBy { it.label })
                .toList()
        } finally {
            withContext(Dispatchers.Main.immediate) { tts.shutdown() }
        }
    }

    fun hasOfflineVietnameseVoice(tts: TextToSpeech): Boolean = selectOfflineVoice(tts, "vi", null) != null

    private suspend fun createTts(context: Context): TextToSpeech = withContext(Dispatchers.Main.immediate) {
        val initialized = CompletableDeferred<Int>()
        val tts = TextToSpeech(context.applicationContext) { status -> initialized.complete(status) }
        val status = initialized.await()
        if (status != TextToSpeech.SUCCESS) {
            tts.shutdown()
            throw IllegalStateException("Android text-to-speech engine initialization failed.")
        }
        tts
    }

    private fun selectOfflineVoice(tts: TextToSpeech, languageCode: String, preferredVoiceName: String?): Voice? {
        val eligible = tts.voices
            .orEmpty()
            .asSequence()
            .filter { it.locale.language.equals(languageCode.substringBefore('-'), ignoreCase = true) }
            .filterNot { it.isNetworkConnectionRequired }
            .toList()
        preferredVoiceName?.let { preferred ->
            eligible.firstOrNull { it.name == preferred }?.let { return it }
        }
        return eligible.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.latency }).firstOrNull()
    }

    private suspend fun synthesizeSegment(
        tts: TextToSpeech,
        segment: TranslationSegment,
        root: File,
    ): DubSpeechSegment {
        val targetMs = DurationFitPolicy.targetDurationMs(segment.startMs, segment.endMs)
        var multiplier = 1.0f

        repeat(DurationFitPolicy.MAXIMUM_ATTEMPTS) { attempt ->
            val output = File(root, "${segment.id}-$attempt.wav")
            output.delete()
            synthesizeOnce(tts, segment.translatedText, multiplier, output)
            val durationMs = measuredDurationMs(output)

            val fits = DurationFitPolicy.fits(durationMs, targetMs)
            val next = DurationFitPolicy.nextRateMultiplier(durationMs, targetMs, multiplier)
            val finalAttempt = attempt == DurationFitPolicy.MAXIMUM_ATTEMPTS - 1
            if (fits || next == null || finalAttempt) {
                return DubSpeechSegment(
                    id = segment.id,
                    startMs = segment.startMs,
                    endMs = DurationFitPolicy.effectiveEndMs(segment.startMs, segment.endMs, durationMs),
                    audioFile = output,
                    speechDurationMs = durationMs,
                    tailSilenceMs = DurationFitPolicy.tailSilenceMs(durationMs, targetMs),
                    rateMultiplier = multiplier,
                )
            }

            output.delete()
            multiplier = next
        }

        error("No speech synthesis attempt was executed.")
    }

    private suspend fun synthesizeOnce(
        tts: TextToSpeech,
        text: String,
        rateMultiplier: Float,
        output: File,
    ) {
        check(text.isNotBlank()) { "Cannot synthesize an empty translated segment." }
        check(tts.setSpeechRate(rateMultiplier) == TextToSpeech.SUCCESS) { "Unable to set Android speech rate." }

        val utteranceId = UUID.randomUUID().toString()
        val finished = CompletableDeferred<Int>()
        val listener = object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit

            override fun onDone(id: String?) {
                if (id == utteranceId && !finished.isCompleted) finished.complete(TextToSpeech.SUCCESS)
            }

            override fun onError(id: String?) {
                if (id == utteranceId && !finished.isCompleted) finished.complete(TextToSpeech.ERROR)
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId && !finished.isCompleted) finished.complete(errorCode)
            }
        }
        check(tts.setOnUtteranceProgressListener(listener) == TextToSpeech.SUCCESS) {
            "Unable to attach Android TTS progress listener."
        }
        val queued = tts.synthesizeToFile(text, Bundle(), output, utteranceId)
        check(queued == TextToSpeech.SUCCESS) { "Android TTS rejected the synthesis request." }
        val result = finished.await()
        check(result == TextToSpeech.SUCCESS && output.isFile && output.length() > 0L) {
            "Android TTS failed while writing synthesized audio (code=$result)."
        }
    }

    private fun measuredDurationMs(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IllegalStateException("Unable to measure synthesized speech duration.")
            max(1L, duration).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } finally {
            retriever.release()
        }
    }
}
