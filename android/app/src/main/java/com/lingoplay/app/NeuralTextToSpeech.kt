package com.lingoplay.app

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class TTSRoute {
    SYSTEM,
    NEURAL,
}

internal object TTSRoutingPolicy {
    fun route(
        targetLanguage: String,
        preferredVoiceId: String?,
        neuralVoiceInstalled: Boolean,
    ): TTSRoute =
        if (
            targetLanguage.substringBefore('-').equals("vi", ignoreCase = true) &&
            preferredVoiceId == NeuralVoicePackManifest.voiceId &&
            neuralVoiceInstalled
        ) {
            TTSRoute.NEURAL
        } else {
            TTSRoute.SYSTEM
        }
}

object OfflineDubbingTTSService {
    suspend fun availableVoices(context: Context): List<OfflineVoiceOption> {
        val system = runCatching {
            SystemVietnameseTTSService.availableOfflineVoices(context)
        }.getOrDefault(emptyList())
        return (listOfNotNull(NeuralVoiceModelStore.voiceOption(context)) + system)
            .distinctBy(OfflineVoiceOption::id)
    }

    suspend fun synthesize(
        context: Context,
        document: TranslationDocument,
        preferredVoiceId: String?,
        onProgress: suspend (segment: Int, total: Int) -> Unit,
    ): DubSpeechDocument {
        val neuralModel = NeuralVoiceModelStore.find(context)
        return when (
            TTSRoutingPolicy.route(
                targetLanguage = document.targetLanguage,
                preferredVoiceId = preferredVoiceId,
                neuralVoiceInstalled = neuralModel != null,
            )
        ) {
            TTSRoute.NEURAL -> NeuralVietnameseTTSService.synthesize(
                context = context,
                document = document,
                model = checkNotNull(neuralModel),
                onProgress = onProgress,
            )
            TTSRoute.SYSTEM -> SystemVietnameseTTSService.synthesize(
                context = context,
                document = document,
                preferredVoiceName = preferredVoiceId?.takeUnless { it == NeuralVoicePackManifest.voiceId },
                onProgress = onProgress,
            )
        }
    }
}

object NeuralVietnameseTTSService {
    suspend fun synthesize(
        context: Context,
        document: TranslationDocument,
        model: NeuralVoiceModel,
        onProgress: suspend (segment: Int, total: Int) -> Unit,
    ): DubSpeechDocument = withContext(Dispatchers.Default) {
        require(document.targetLanguage.substringBefore('-').equals("vi", ignoreCase = true)) {
            "The installed Neural Voice supports Vietnamese output only."
        }

        val vits = OfflineTtsVitsModelConfig(
            model = model.model.absolutePath,
            tokens = model.tokens.absolutePath,
            dataDir = model.dataDir.absolutePath,
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vits,
            numThreads = NeuralTTSPerformancePolicy.threadCount(Runtime.getRuntime().availableProcessors()),
            provider = "cpu",
        )
        val tts = OfflineTts(
            assetManager = null,
            config = OfflineTtsConfig(model = modelConfig, silenceScale = 0.2f),
        )
        val root = File(context.cacheDir, "lingoplay/neural-tts/${UUID.randomUUID()}").apply { mkdirs() }
        var succeeded = false
        try {
            val output = mutableListOf<DubSpeechSegment>()
            document.segments.forEachIndexed { index, segment ->
                output += synthesizeSegment(tts, segment, root)
                onProgress(index + 1, document.segments.size)
            }
            succeeded = true
            DubSpeechDocument(NeuralVoicePackManifest.voiceId, output)
        } finally {
            tts.release()
            if (!succeeded) root.deleteRecursively()
        }
    }

    private fun synthesizeSegment(
        tts: OfflineTts,
        segment: TranslationSegment,
        root: File,
    ): DubSpeechSegment {
        val targetMs = DurationFitPolicy.targetDurationMs(segment.startMs, segment.endMs)
        var multiplier = 1.0f

        repeat(DurationFitPolicy.MAXIMUM_ATTEMPTS) { attempt ->
            val output = File(root, "${segment.id}-$attempt.wav")
            output.delete()
            val audio = tts.generate(segment.translatedText, sid = 0, speed = multiplier)
            check(audio.samples.isNotEmpty() && audio.sampleRate > 0) {
                "Neural Voice produced no audio for segment ${segment.id}."
            }
            check(audio.save(output.absolutePath) && output.isFile && output.length() > 44L) {
                "Neural Voice could not save segment ${segment.id}."
            }
            val durationMs = max(
                1,
                (audio.samples.size.toDouble() * 1_000.0 / audio.sampleRate.toDouble()).roundToInt(),
            )
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
        error("No Neural Voice synthesis attempt was executed.")
    }
}

internal object NeuralTTSPerformancePolicy {
    fun threadCount(availableProcessors: Int): Int =
        (availableProcessors.coerceAtLeast(1) / 2).coerceIn(1, 2)
}
