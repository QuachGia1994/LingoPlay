package com.lingoplay.app

import android.app.ActivityManager
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt


data class ASRSegment(
    val id: Int,
    val startSeconds: Float,
    val endSeconds: Float,
    val text: String,
    val speakerId: String? = null,
    val overlappingSpeakerIds: List<String> = emptyList(),
)

data class ASRTranscript(
    val language: String,
    val text: String,
    val segments: List<ASRSegment>,
)

enum class ASRPhase {
    IDLE,
    MODEL_MISSING,
    LOADING_MODEL,
    TRANSCRIBING,
    COMPLETED,
    FAILED,
}

data class SherpaWhisperModel(
    val encoder: File,
    val decoder: File,
    val tokens: File,
)

object ASRModelStore {
    fun findWhisperModel(context: Context): SherpaWhisperModel? {
        val managed = ASRModelInstaller.activeDirectory(context)
        val legacy = File(context.filesDir, "lingoplay/models/sherpa-whisper/current")
        val root = managed ?: legacy.takeIf(File::isDirectory) ?: return null
        val files = root.walkTopDown().filter(File::isFile).toList()
        val encoder = files.firstOrNull { it.extension.equals("onnx", true) && it.name.contains("encoder", true) }
        val decoder = files.firstOrNull { it.extension.equals("onnx", true) && it.name.contains("decoder", true) }
        val tokens = files.firstOrNull { it.extension.equals("txt", true) && it.name.contains("token", true) }
        if (encoder == null || decoder == null || tokens == null) return null
        return SherpaWhisperModel(encoder, decoder, tokens)
    }
}

object InferenceMemoryGate {
    private val trimRequested = AtomicBoolean(false)

    fun reset() {
        trimRequested.set(false)
    }

    fun requestTrim() {
        trimRequested.set(true)
    }

    fun throwIfTrimRequested() {
        if (trimRequested.get()) {
            throw CancellationException("Speech recognition paused to release memory while LingoPlay is not visible.")
        }
    }
}

data class InferenceMemoryBudget(
    val numThreads: Int,
    val chunkSeconds: Int,
)

object InferenceMemoryPolicy {
    fun forDevice(context: Context): InferenceMemoryBudget {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return forCharacteristics(
            lowRamDevice = activityManager.isLowRamDevice,
            memoryClassMb = activityManager.memoryClass,
            availableProcessors = Runtime.getRuntime().availableProcessors(),
        )
    }

    fun forCharacteristics(lowRamDevice: Boolean, memoryClassMb: Int, availableProcessors: Int): InferenceMemoryBudget {
        val cores = availableProcessors.coerceAtLeast(1)
        return when {
            lowRamDevice || memoryClassMb <= 192 -> InferenceMemoryBudget(1, 10)
            memoryClassMb <= 256 -> InferenceMemoryBudget(min(2, cores), 15)
            memoryClassMb <= 384 -> InferenceMemoryBudget(min(3, cores), 20)
            else -> InferenceMemoryBudget(min(4, max(1, cores / 2)), 25)
        }
    }
}

internal object SpeakerAwareASRPolicy {
    private const val MULTI_SPEAKER_CHUNK_SECONDS = 6

    fun chunkSeconds(defaultSeconds: Int, speakerMode: SpeakerMode): Int =
        if (speakerMode == SpeakerMode.MULTI) min(defaultSeconds, MULTI_SPEAKER_CHUNK_SECONDS) else defaultSeconds
}

object SherpaWhisperSpeechRecognizer {
    suspend fun transcribe(
        context: Context,
        audioFile: File,
        model: SherpaWhisperModel,
        sourceLanguageCode: String? = null,
        chunkSecondsOverride: Int? = null,
    ): ASRTranscript = withContext(Dispatchers.Default) {
        val budget = InferenceMemoryPolicy.forDevice(context)
        val chunkSeconds = chunkSecondsOverride?.coerceIn(3, budget.chunkSeconds) ?: budget.chunkSeconds
        InferenceMemoryGate.reset()
        val whisper = OfflineWhisperModelConfig(
            encoder = model.encoder.absolutePath,
            decoder = model.decoder.absolutePath,
            language = sourceLanguageCode.orEmpty(),
            task = "transcribe",
            tailPaddings = 300,
        )
        val modelConfig = OfflineModelConfig(
            whisper = whisper,
            numThreads = budget.numThreads,
            provider = "cpu",
            modelType = "whisper",
            tokens = model.tokens.absolutePath,
        )
        val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = modelConfig))
        val segments = mutableListOf<ASRSegment>()
        var language = "und"
        try {
            AndroidAudioDecoder.forEachChunk(audioFile, chunkSeconds) { chunk ->
                InferenceMemoryGate.throwIfTrimRequested()
                if (!ASRAudioGate.hasLikelySpeech(chunk.samples)) return@forEachChunk
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(chunk.samples, chunk.sampleRate)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    val text = ASRFormatting.normalizedText(result.text)
                    if (text.isNotEmpty()) {
                        if (language == "und" && result.lang.isNotBlank()) language = result.lang
                        segments += ASRSegment(
                            id = segments.size,
                            startSeconds = chunk.startSeconds,
                            endSeconds = chunk.endSeconds,
                            text = text,
                        )
                    }
                } finally {
                    stream.release()
                }
            }
        } finally {
            recognizer.release()
        }

        val normalized = ASRFormatting.normalizedSegments(segments)
        if (normalized.isEmpty()) throw IllegalStateException("No speech could be recognized in this audio.")
        ASRTranscript(
            language = language,
            text = normalized.joinToString(" ") { it.text },
            segments = normalized,
        )
    }
}

internal object ASRAudioGate {
    private const val MIN_RMS = 0.0035f
    private const val ACTIVE_LEVEL = 0.010f
    private const val MIN_ACTIVE_FRACTION = 0.015f

    fun hasLikelySpeech(samples: FloatArray): Boolean {
        if (samples.isEmpty()) return false
        var energy = 0.0
        var active = 0
        samples.forEach { sample ->
            val value = sample.coerceIn(-1f, 1f)
            energy += value.toDouble() * value.toDouble()
            if (kotlin.math.abs(value) >= ACTIVE_LEVEL) active++
        }
        val rms = sqrt(energy / samples.size).toFloat()
        val activeFraction = active.toFloat() / samples.size.toFloat()
        return rms >= MIN_RMS && activeFraction >= MIN_ACTIVE_FRACTION
    }
}

internal object ASRTimelinePolicy {
    fun shifted(transcript: ASRTranscript, offsetMs: Int): ASRTranscript {
        if (offsetMs <= 0) return transcript
        val offsetSeconds = offsetMs.toFloat() / 1_000f
        return transcript.copy(
            segments = transcript.segments.map { segment ->
                segment.copy(
                    startSeconds = segment.startSeconds + offsetSeconds,
                    endSeconds = segment.endSeconds + offsetSeconds,
                )
            },
        )
    }
}

object ASRFormatting {
    fun normalizedText(text: String): String = text.trim().split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")

    fun normalizedSegments(segments: List<ASRSegment>): List<ASRSegment> = segments.mapNotNull { segment ->
        val text = normalizedText(segment.text)
        if (text.isEmpty()) return@mapNotNull null
        val start = segment.startSeconds.coerceAtLeast(0f)
        val end = segment.endSeconds.coerceAtLeast(start)
        segment.copy(startSeconds = start, endSeconds = end, text = text)
    }
}

internal data class DecodedAudioChunk(
    val samples: FloatArray,
    val sampleRate: Int,
    val startSeconds: Float,
    val endSeconds: Float,
)

internal object AndroidAudioDecoder {
    suspend fun decodeResampledMono(
        file: File,
        targetSampleRate: Int,
        maxDurationSeconds: Int,
    ): FloatArray {
        require(targetSampleRate > 0 && maxDurationSeconds > 0)
        val maximumSamples = targetSampleRate.toLong() * maxDurationSeconds.toLong()
        val output = FloatAccumulator(minOf(maximumSamples, targetSampleRate.toLong() * 30L).toInt().coerceAtLeast(1))
        forEachChunk(file, 20) { chunk ->
            val ratio = chunk.sampleRate.toDouble() / targetSampleRate.toDouble()
            val outputCount = max(1, (chunk.samples.size.toDouble() / ratio).toInt())
            check(output.size.toLong() + outputCount.toLong() <= maximumSamples) {
                "Multi-speaker analysis supports up to ${maxDurationSeconds / 60} minutes per video on this device."
            }
            repeat(outputCount) { index ->
                val sourcePosition = index.toDouble() * ratio
                val left = sourcePosition.toInt().coerceIn(0, chunk.samples.lastIndex)
                val right = minOf(left + 1, chunk.samples.lastIndex)
                val fraction = (sourcePosition - left.toDouble()).toFloat()
                output.add(chunk.samples[left] + (chunk.samples[right] - chunk.samples[left]) * fraction)
            }
        }
        return output.toArray()
    }

    suspend fun forEachChunk(file: File, chunkSeconds: Int, consume: (DecodedAudioChunk) -> Unit) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("Prepared audio contains no readable audio track.")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("Audio codec is unknown.")
            extractor.selectTrack(trackIndex)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var chunker = MonoChunker(sampleRate, chunkSeconds, consume)

            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex) ?: throw IllegalStateException("Decoder input buffer unavailable.")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        val newSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (newSampleRate != sampleRate) {
                            chunker.flush()
                            sampleRate = newSampleRate
                            chunker = MonoChunker(sampleRate, chunkSeconds, consume)
                        }
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    }
                    else -> if (outputIndex >= 0) {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            appendMonoSamples(output.slice().order(ByteOrder.nativeOrder()), pcmEncoding, channels, chunker)
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEnded = true
                    }
                }
            }
            chunker.flush()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun appendMonoSamples(buffer: ByteBuffer, encoding: Int, channels: Int, output: MonoChunker) {
        val channelCount = channels.coerceAtLeast(1)
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> throw IllegalStateException("Unsupported decoded PCM encoding: $encoding")
        }
        val frameBytes = bytesPerSample * channelCount
        while (buffer.remaining() >= frameBytes) {
            var sum = 0f
            repeat(channelCount) {
                sum += when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
                    AudioFormat.ENCODING_PCM_16BIT -> (buffer.short / 32768f).coerceIn(-1f, 1f)
                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xFF) - 128) / 128f
                    else -> 0f
                }
            }
            output.add(sum / channelCount)
        }
    }
}

private class FloatAccumulator(initialCapacity: Int) {
    private var values = FloatArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set

    fun add(value: Float) {
        if (size == values.size) values = values.copyOf((values.size * 2).coerceAtLeast(1))
        values[size++] = value
    }

    fun toArray(): FloatArray = values.copyOf(size)
}

internal object ASRChunkBoundaryPolicy {
    private const val SEARCH_WINDOW_MS = 2_000
    private const val ENERGY_WINDOW_MS = 120
    private const val ENERGY_STEP_MS = 40
    private const val MIN_SILENCE_RMS = 0.002f
    private const val MAX_SILENCE_RMS = 0.015f
    private const val RELATIVE_SILENCE_RATIO = 0.35f

    fun chooseSplit(samples: FloatArray, size: Int, sampleRate: Int, targetSize: Int): Int {
        if (size < targetSize || sampleRate <= 0) return size
        val searchFrames = max(1, sampleRate * SEARCH_WINDOW_MS / 1_000)
        val windowFrames = max(1, sampleRate * ENERGY_WINDOW_MS / 1_000)
        val stepFrames = max(1, sampleRate * ENERGY_STEP_MS / 1_000)
        val searchStart = max(windowFrames, size - searchFrames)
        var chunkEnergy = 0.0
        for (index in 0 until size) {
            val sample = samples[index].toDouble()
            chunkEnergy += sample * sample
        }
        val chunkRms = kotlin.math.sqrt(chunkEnergy / max(1, size)).toFloat()
        val silenceThreshold = (chunkRms * RELATIVE_SILENCE_RATIO).coerceIn(MIN_SILENCE_RMS, MAX_SILENCE_RMS)
        var bestEnd = size
        var bestRms = Float.MAX_VALUE
        var end = searchStart
        while (end <= size) {
            val start = max(0, end - windowFrames)
            var energy = 0.0
            for (index in start until end) {
                val sample = samples[index].toDouble()
                energy += sample * sample
            }
            val rms = kotlin.math.sqrt(energy / max(1, end - start)).toFloat()
            if (rms < bestRms) {
                bestRms = rms
                bestEnd = end
            }
            end += stepFrames
        }
        return if (bestRms <= silenceThreshold) bestEnd.coerceIn(1, size) else size
    }
}

private class MonoChunker(
    private val sampleRate: Int,
    secondsPerChunk: Int,
    private val consume: (DecodedAudioChunk) -> Unit,
) {
    private val targetSize = max(1, sampleRate * secondsPerChunk)
    private var values = FloatArray(targetSize)
    private var size = 0
    private var emittedSamples = 0L

    fun add(value: Float) {
        values[size++] = value
        if (size == targetSize) {
            emitPrefix(ASRChunkBoundaryPolicy.chooseSplit(values, size, sampleRate, targetSize))
        }
    }

    fun flush() {
        if (size > 0) emitPrefix(size)
    }

    private fun emitPrefix(count: Int) {
        require(count in 1..size)
        val start = emittedSamples.toFloat() / sampleRate.toFloat()
        emittedSamples += count
        val end = emittedSamples.toFloat() / sampleRate.toFloat()
        consume(DecodedAudioChunk(values.copyOfRange(0, count), sampleRate, start, end))
        val remaining = size - count
        if (remaining > 0) values.copyInto(values, destinationOffset = 0, startIndex = count, endIndex = size)
        size = remaining
    }
}
