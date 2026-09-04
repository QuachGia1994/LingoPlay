package com.lingoplay.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh


data class LocalDubMediaResult(
    val remuxedVideoFile: File,
    val durationMs: Long,
)

enum class MixPhase {
    IDLE,
    RENDERING_AUDIO,
    REMUXING,
    COMPLETED,
    FAILED,
}

internal object AudioQualityPolicy {
    private const val TARGET_RMS = 0.14
    private const val PEAK_CEILING = 0.92
    private const val MAX_GAIN = 2.0
    private const val MIN_NORMALIZE_RMS = 0.008
    private const val MIN_NORMALIZE_PEAK = 0.03
    private const val SOFT_LIMIT_START = 0.90

    fun normalizeSpeech(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples
        var energy = 0.0
        var peak = 0.0
        samples.forEach { sample ->
            val value = sample.toDouble() / Short.MAX_VALUE.toDouble()
            energy += value * value
            peak = max(peak, kotlin.math.abs(value))
        }
        val rms = sqrt(energy / samples.size.toDouble())
        if (rms < MIN_NORMALIZE_RMS && peak < MIN_NORMALIZE_PEAK) return samples
        if (rms <= 1e-6 || peak <= 1e-6) return samples
        val rmsGain = (TARGET_RMS / rms).coerceAtMost(MAX_GAIN)
        val peakGain = PEAK_CEILING / peak
        val gain = minOf(rmsGain, peakGain, MAX_GAIN)
        if (gain in 0.995..1.005) return samples
        return ShortArray(samples.size) { index ->
            softLimitPcm16((samples[index].toDouble() * gain).roundToInt())
        }
    }

    fun softLimitPcm16(value: Int): Short {
        val normalized = value.toDouble() / Short.MAX_VALUE.toDouble()
        val magnitude = kotlin.math.abs(normalized)
        if (magnitude <= SOFT_LIMIT_START) {
            return value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val sign = if (normalized < 0) -1.0 else 1.0
        val excess = (magnitude - SOFT_LIMIT_START) / (1.0 - SOFT_LIMIT_START)
        val limited = SOFT_LIMIT_START + (1.0 - SOFT_LIMIT_START) * tanh(excess)
        return (sign * limited.coerceAtMost(0.999) * Short.MAX_VALUE.toDouble()).roundToInt().toShort()
    }
}

object TimelinePlacementPolicy {
    const val DUCK_FLOOR = 0.16f
    const val DUCK_FADE_MS = 120

    fun frameAt(timeMs: Int, sampleRate: Int): Long {
        require(timeMs >= 0)
        require(sampleRate > 0)
        return (timeMs.toLong() * sampleRate.toLong()) / 1_000L
    }

    fun frameCount(durationMs: Long, sampleRate: Int): Long {
        require(durationMs >= 0)
        require(sampleRate > 0)
        return (durationMs * sampleRate.toLong() + 999L) / 1_000L
    }

    fun clampPcm16(value: Int): Short = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    fun duckGainAt(
        timeMs: Double,
        segments: List<DubSpeechSegment>,
        mode: DubbingModePreset = DubbingModePreset.BALANCED,
    ): Float {
        var gain = 1f
        segments.forEach { segment ->
            val start = segment.startMs.toDouble()
            val end = segment.endMs.toDouble()
            val fade = mode.duckFadeMs.toDouble()
            val local = when {
                timeMs < start - fade || timeMs > end + fade -> 1f
                timeMs < start -> {
                    val progress = ((timeMs - (start - fade)) / fade).coerceIn(0.0, 1.0).toFloat()
                    1f + (mode.duckFloor - 1f) * progress
                }
                timeMs <= end -> mode.duckFloor
                else -> {
                    val progress = ((timeMs - end) / fade).coerceIn(0.0, 1.0).toFloat()
                    mode.duckFloor + (1f - mode.duckFloor) * progress
                }
            }
            gain = minOf(gain, local)
        }
        return gain
    }
}

object TimelineMixService {
    private const val AAC_BIT_RATE = 160_000
    private const val DECODER_TIMEOUT_US = 10_000L
    private const val ENCODER_TIMEOUT_US = 10_000L
    private const val SILENCE_CHUNK_MS = 20
    private const val CACHE_MAX_BYTES = 2L * 1024L * 1024L * 1024L
    private const val CACHE_TARGET_BYTES = 1536L * 1024L * 1024L
    private const val CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L

    suspend fun render(
        context: Context,
        media: LocalMediaItem,
        dub: DubSpeechDocument,
        mode: DubbingModePreset = DubbingModePreset.BALANCED,
        onPhase: suspend (MixPhase) -> Unit = {},
    ): LocalDubMediaResult = withContext(Dispatchers.IO) {
        require(dub.segments.isNotEmpty()) { "No Vietnamese speech clips are available to mix." }
        require(media.durationMs > 0) { "The source video duration is invalid." }

        val parent = File(context.cacheDir, "lingoplay/rendered").apply { mkdirs() }
        purgeRenderCache(parent)
        val root = File(parent, UUID.randomUUID().toString()).apply { mkdirs() }
        val mixedAudio = File(root, "mixed-audio.m4a")
        val remuxedVideo = File(root, "dubbed-video.mp4")
        var success = false

        try {
            onPhase(MixPhase.RENDERING_AUDIO)
            renderMixedAudio(context, media, dub, mode, mixedAudio)

            onPhase(MixPhase.REMUXING)
            remuxVideoWithMixedAudio(context, media.uri, mixedAudio, remuxedVideo)

            check(remuxedVideo.isFile && remuxedVideo.length() > 0L) { "Dubbed video remux produced no output." }
            mixedAudio.delete()
            success = true
            onPhase(MixPhase.COMPLETED)
            purgeRenderCache(parent, exclude = root)
            LocalDubMediaResult(remuxedVideo, media.durationMs)
        } finally {
            if (!success) root.deleteRecursively()
        }
    }

    private fun renderMixedAudio(
        context: Context,
        media: LocalMediaItem,
        dub: DubSpeechDocument,
        mode: DubbingModePreset,
        destination: File,
    ) {
        val extractor = openExtractor(context, media.uri)
        val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("The source video has no readable audio track.")
        extractor.selectTrack(audioTrack)
        val inputFormat = extractor.getTrackFormat(audioTrack)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("The source audio MIME type is missing.")
        inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

        val decoder = MediaCodec.createDecoderByType(mime)
        var encoder: StreamingAacEncoder? = null
        val clipCache = SpeechClipCache(dub.segments)
        var targetRate = 0
        var targetChannels = 0
        var sourceRate = 0
        var sourceChannels = 0
        var sourceEncoding = AudioFormat.ENCODING_PCM_16BIT
        var timelineCursorUs = 0L
        var decoderInputEnded = false
        var decoderOutputEnded = false

        try {
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()

            while (!decoderOutputEnded) {
                if (!decoderInputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(DECODER_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex) ?: error("Audio decoder input buffer is unavailable.")
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, timelineCursorUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decoderInputEnded = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                extractor.sampleTime.coerceAtLeast(0L),
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, DECODER_TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        sourceRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        sourceChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sourceEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        targetChannels = if (sourceChannels == 1) 1 else 2
                        val encoderChoice = chooseAacEncoder(sourceRate, targetChannels)
                        targetRate = encoderChoice.sampleRate
                        encoder = StreamingAacEncoder(
                            codecName = encoderChoice.codecName,
                            destination = destination,
                            sampleRate = targetRate,
                            channels = targetChannels,
                        )
                    }

                    outputIndex >= 0 -> {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (info.size > 0 && outputBuffer != null) {
                            if (encoder == null) {
                                val outputFormat = decoder.outputFormat
                                sourceRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                sourceChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                sourceEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                    outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                } else {
                                    AudioFormat.ENCODING_PCM_16BIT
                                }
                                targetChannels = if (sourceChannels == 1) 1 else 2
                                val encoderChoice = chooseAacEncoder(sourceRate, targetChannels)
                                targetRate = encoderChoice.sampleRate
                                encoder = StreamingAacEncoder(
                                    codecName = encoderChoice.codecName,
                                    destination = destination,
                                    sampleRate = targetRate,
                                    channels = targetChannels,
                                )
                            }

                            val decoded = readDecodedPcm(
                                outputBuffer = outputBuffer,
                                info = info,
                                encoding = sourceEncoding,
                                sourceChannels = sourceChannels,
                                targetChannels = targetChannels,
                            )
                            val normalized = if (sourceRate == targetRate) {
                                decoded
                            } else {
                                resampleInterleaved(decoded, sourceRate, targetRate, targetChannels)
                            }
                            val startUs = info.presentationTimeUs.coerceAtLeast(0L)
                            if (startUs > timelineCursorUs + 2_000L) {
                                queueSilenceRange(
                                    encoder = requireNotNull(encoder),
                                    clipCache = clipCache,
                                    dub = dub,
                                    startUs = timelineCursorUs,
                                    endUs = startUs,
                                    sampleRate = targetRate,
                                    channels = targetChannels,
                                    mode = mode,
                                )
                            }
                            mixOriginalAndSpeech(
                                pcm = normalized,
                                startUs = startUs,
                                sampleRate = targetRate,
                                channels = targetChannels,
                                dub = dub,
                                clipCache = clipCache,
                                mode = mode,
                            )
                            requireNotNull(encoder).queuePcm(normalized, startUs)
                            val frames = normalized.size / targetChannels
                            val endUs = startUs + frames * 1_000_000L / targetRate.toLong()
                            timelineCursorUs = max(timelineCursorUs, endUs)
                        }
                        decoderOutputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            val activeEncoder = encoder ?: error("The source audio decoder produced no PCM output.")
            val mediaEndUs = media.durationMs * 1_000L
            if (timelineCursorUs < mediaEndUs) {
                queueSilenceRange(
                    encoder = activeEncoder,
                    clipCache = clipCache,
                    dub = dub,
                    startUs = timelineCursorUs,
                    endUs = mediaEndUs,
                    sampleRate = targetRate,
                    channels = targetChannels,
                    mode = mode,
                )
                timelineCursorUs = mediaEndUs
            }
            activeEncoder.finish(timelineCursorUs)
        } finally {
            extractor.release()
            runCatching { decoder.stop() }
            decoder.release()
            encoder?.closeQuietly()
            if (!destination.exists() || destination.length() == 0L) destination.delete()
        }
        check(destination.isFile && destination.length() > 0L) { "Mixed AAC audio export produced no output." }
    }

    private fun mixOriginalAndSpeech(
        pcm: ShortArray,
        startUs: Long,
        sampleRate: Int,
        channels: Int,
        dub: DubSpeechDocument,
        clipCache: SpeechClipCache,
        mode: DubbingModePreset,
    ) {
        val frames = pcm.size / channels
        if (frames <= 0) return
        val chunkEndUs = startUs + frames * 1_000_000L / sampleRate.toLong()
        val relevant = dub.segments.filter { segment ->
            val speechEndUs = (segment.startMs.toLong() + segment.speechDurationMs.toLong()) * 1_000L
            val duckEndUs = (segment.endMs.toLong() + mode.duckFadeMs) * 1_000L
            segment.startMs.toLong() * 1_000L <= chunkEndUs && max(speechEndUs, duckEndUs) >= startUs
        }
        val loaded = relevant.associateWith { clipCache.load(it, sampleRate, channels) }

        for (frame in 0 until frames) {
            val timeUs = startUs + frame * 1_000_000L / sampleRate.toLong()
            val timeMs = timeUs / 1_000.0
            val duckGain = TimelinePlacementPolicy.duckGainAt(timeMs, relevant, mode)
            for (channel in 0 until channels) {
                val index = frame * channels + channel
                var mixed = (pcm[index].toInt() * duckGain).roundToInt()
                relevant.forEach { segment ->
                    val clip = loaded.getValue(segment)
                    val offsetUs = timeUs - segment.startMs.toLong() * 1_000L
                    if (offsetUs >= 0L) {
                        val clipFrame = (offsetUs * sampleRate.toLong() / 1_000_000L).toInt()
                        if (clipFrame in 0 until clip.frames) {
                            mixed += (clip.samples[clipFrame * channels + channel].toFloat() * mode.dubGain).roundToInt()
                        }
                    }
                }
                pcm[index] = AudioQualityPolicy.softLimitPcm16(mixed)
            }
        }
        clipCache.evictBefore(startUs - 500_000L)
    }

    private fun queueSilenceRange(
        encoder: StreamingAacEncoder,
        clipCache: SpeechClipCache,
        dub: DubSpeechDocument,
        startUs: Long,
        endUs: Long,
        sampleRate: Int,
        channels: Int,
        mode: DubbingModePreset,
    ) {
        var cursorUs = startUs
        val chunkFrames = max(1, sampleRate * SILENCE_CHUNK_MS / 1_000)
        while (cursorUs < endUs) {
            val remainingFrames = ((endUs - cursorUs) * sampleRate / 1_000_000L).coerceAtLeast(1L)
            val frames = minOf(chunkFrames.toLong(), remainingFrames).toInt()
            val pcm = ShortArray(frames * channels)
            mixOriginalAndSpeech(pcm, cursorUs, sampleRate, channels, dub, clipCache, mode)
            encoder.queuePcm(pcm, cursorUs)
            cursorUs += frames * 1_000_000L / sampleRate.toLong()
        }
    }

    private fun readDecodedPcm(
        outputBuffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        encoding: Int,
        sourceChannels: Int,
        targetChannels: Int,
    ): ShortArray {
        val data = outputBuffer.duplicate().order(ByteOrder.nativeOrder())
        data.position(info.offset)
        data.limit(info.offset + info.size)
        val raw = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = data.slice().order(ByteOrder.nativeOrder()).asFloatBuffer()
                ShortArray(floats.remaining()) { index ->
                    (floats.get(index).coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
                }
            }
            else -> {
                val shorts = data.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                ShortArray(shorts.remaining()).also { shorts.get(it) }
            }
        }
        return convertChannels(raw, sourceChannels, targetChannels)
    }

    private fun convertChannels(samples: ShortArray, sourceChannels: Int, targetChannels: Int): ShortArray {
        require(sourceChannels > 0 && targetChannels in 1..2)
        if (sourceChannels == targetChannels) return samples
        val frames = samples.size / sourceChannels
        val output = ShortArray(frames * targetChannels)
        for (frame in 0 until frames) {
            if (targetChannels == 1) {
                var sum = 0
                for (channel in 0 until sourceChannels) sum += samples[frame * sourceChannels + channel].toInt()
                output[frame] = TimelinePlacementPolicy.clampPcm16(sum / sourceChannels)
            } else if (sourceChannels == 1) {
                val value = samples[frame]
                output[frame * 2] = value
                output[frame * 2 + 1] = value
            } else {
                var left = 0
                var leftCount = 0
                var right = 0
                var rightCount = 0
                for (channel in 0 until sourceChannels) {
                    if (channel % 2 == 0) {
                        left += samples[frame * sourceChannels + channel].toInt()
                        leftCount++
                    } else {
                        right += samples[frame * sourceChannels + channel].toInt()
                        rightCount++
                    }
                }
                output[frame * 2] = TimelinePlacementPolicy.clampPcm16(left / max(1, leftCount))
                output[frame * 2 + 1] = TimelinePlacementPolicy.clampPcm16(right / max(1, rightCount))
            }
        }
        return output
    }

    private fun resampleInterleaved(
        samples: ShortArray,
        sourceRate: Int,
        targetRate: Int,
        channels: Int,
    ): ShortArray {
        if (sourceRate == targetRate || samples.isEmpty()) return samples
        val sourceFrames = samples.size / channels
        val targetFrames = max(1, (sourceFrames.toLong() * targetRate / sourceRate).toInt())
        val output = ShortArray(targetFrames * channels)
        for (frame in 0 until targetFrames) {
            val position = frame.toDouble() * sourceRate.toDouble() / targetRate.toDouble()
            val lower = floor(position).toInt().coerceIn(0, sourceFrames - 1)
            val upper = minOf(lower + 1, sourceFrames - 1)
            val fraction = position - lower.toDouble()
            for (channel in 0 until channels) {
                val a = samples[lower * channels + channel].toDouble()
                val b = samples[upper * channels + channel].toDouble()
                output[frame * channels + channel] = TimelinePlacementPolicy.clampPcm16(
                    (a + (b - a) * fraction).roundToInt(),
                )
            }
        }
        return output
    }

    private data class EncoderChoice(val codecName: String, val sampleRate: Int)

    private fun chooseAacEncoder(sourceRate: Int, channels: Int): EncoderChoice {
        val candidates = linkedSetOf(sourceRate, 48_000, 44_100)
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) }
        }
        candidates.forEach { rate ->
            codecs.forEach { info ->
                val capabilities = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC) }.getOrNull()
                    ?: return@forEach
                val audio = capabilities.audioCapabilities ?: return@forEach
                if (audio.isSampleRateSupported(rate) && channels <= audio.maxInputChannelCount) {
                    return EncoderChoice(info.name, rate)
                }
            }
        }
        error("No AAC encoder on this device supports the source audio or 48/44.1 kHz fallback rates.")
    }

    private class StreamingAacEncoder(
        codecName: String,
        private val destination: File,
        private val sampleRate: Int,
        private val channels: Int,
    ) {
        private val codec = MediaCodec.createByCodecName(codecName)
        private var muxer: MediaMuxer? = null
        private var muxerStarted = false
        private var outputTrack = -1
        private var closed = false

        init {
            destination.parentFile?.mkdirs()
            destination.delete()
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        fun queuePcm(samples: ShortArray, startUs: Long) {
            var sampleOffset = 0
            val totalFrames = samples.size / channels
            while (sampleOffset < samples.size) {
                drain(endOfStream = false)
                val inputIndex = codec.dequeueInputBuffer(ENCODER_TIMEOUT_US)
                if (inputIndex < 0) continue
                val buffer = codec.getInputBuffer(inputIndex) ?: error("AAC encoder input buffer is unavailable.")
                buffer.clear()
                buffer.order(ByteOrder.nativeOrder())
                val maxShorts = (buffer.capacity() / 2 / channels) * channels
                val count = minOf(maxShorts, samples.size - sampleOffset)
                val shortBuffer = buffer.asShortBuffer()
                shortBuffer.put(samples, sampleOffset, count)
                val frameOffset = sampleOffset / channels
                val ptsUs = startUs + frameOffset * 1_000_000L / sampleRate.toLong()
                codec.queueInputBuffer(inputIndex, 0, count * 2, ptsUs, 0)
                sampleOffset += count
            }
            if (totalFrames == 0) drain(endOfStream = false)
        }

        fun finish(endUs: Long) {
            while (true) {
                drain(endOfStream = false)
                val inputIndex = codec.dequeueInputBuffer(ENCODER_TIMEOUT_US)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0, endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
            }
            drain(endOfStream = true)
            closeQuietly()
            check(destination.isFile && destination.length() > 0L) { "AAC encoder produced no output." }
        }

        private fun drain(endOfStream: Boolean) {
            val info = MediaCodec.BufferInfo()
            var eos = false
            var idleCount = 0
            while (!eos) {
                val outputIndex = codec.dequeueOutputBuffer(info, ENCODER_TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        idleCount++
                        if (!endOfStream) break
                        check(idleCount < 200) { "AAC encoder did not reach end of stream after the EOS signal." }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(outputTrack < 0) { "AAC encoder output format changed more than once." }
                        outputTrack = muxer!!.addTrack(codec.outputFormat)
                        muxer!!.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        idleCount = 0
                        val buffer = codec.getOutputBuffer(outputIndex) ?: error("AAC encoder output buffer is unavailable.")
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            check(muxerStarted && outputTrack >= 0) { "AAC muxer was not started before encoded output arrived." }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer!!.writeSampleData(outputTrack, buffer, info)
                        }
                        eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }

        fun closeQuietly() {
            if (closed) return
            closed = true
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            muxer = null
        }
    }

    private data class ResampledClip(
        val samples: ShortArray,
        val channels: Int,
    ) {
        val frames: Int get() = samples.size / channels
    }

    private class SpeechClipCache(private val segments: List<DubSpeechSegment>) {
        private val cache = linkedMapOf<String, ResampledClip>()

        fun load(segment: DubSpeechSegment, targetRate: Int, targetChannels: Int): ResampledClip {
            val key = "${segment.id}:$targetRate:$targetChannels"
            return cache.getOrPut(key) {
                val wav = readWavInfo(segment.audioFile)
                require(wav.audioFormat == 1 && wav.bitsPerSample == 16 && wav.channels in 1..2) {
                    "Vietnamese TTS clip ${segment.id} is not supported 16-bit PCM WAV."
                }
                val input = readWavSamples(wav)
                val convertedChannels = convertClipChannels(input, wav.channels, targetChannels)
                val resampled = resampleClip(convertedChannels, wav.sampleRate, targetRate, targetChannels)
                ResampledClip(AudioQualityPolicy.normalizeSpeech(resampled), targetChannels)
            }
        }

        fun evictBefore(timeUs: Long) {
            val activeIds = segments.filter { segment ->
                (segment.startMs.toLong() + segment.speechDurationMs.toLong()) * 1_000L >= timeUs
            }.mapTo(hashSetOf()) { it.id }
            val iterator = cache.keys.iterator()
            while (iterator.hasNext()) {
                val id = iterator.next().substringBefore(':')
                if (id !in activeIds) iterator.remove()
            }
        }

        private fun convertClipChannels(samples: ShortArray, sourceChannels: Int, targetChannels: Int): ShortArray {
            if (sourceChannels == targetChannels) return samples
            val frames = samples.size / sourceChannels
            val output = ShortArray(frames * targetChannels)
            for (frame in 0 until frames) {
                if (sourceChannels == 1 && targetChannels == 2) {
                    val value = samples[frame]
                    output[frame * 2] = value
                    output[frame * 2 + 1] = value
                } else {
                    val left = samples[frame * sourceChannels].toInt()
                    val right = samples[frame * sourceChannels + minOf(1, sourceChannels - 1)].toInt()
                    output[frame] = TimelinePlacementPolicy.clampPcm16((left + right) / 2)
                }
            }
            return output
        }

        private fun resampleClip(samples: ShortArray, sourceRate: Int, targetRate: Int, channels: Int): ShortArray {
            if (sourceRate == targetRate) return samples
            val sourceFrames = samples.size / channels
            val targetFrames = max(1, (sourceFrames.toLong() * targetRate / sourceRate).toInt())
            val output = ShortArray(targetFrames * channels)
            for (frame in 0 until targetFrames) {
                val position = frame.toDouble() * sourceRate.toDouble() / targetRate.toDouble()
                val lower = floor(position).toInt().coerceIn(0, sourceFrames - 1)
                val upper = minOf(lower + 1, sourceFrames - 1)
                val fraction = position - lower
                for (channel in 0 until channels) {
                    val a = samples[lower * channels + channel].toDouble()
                    val b = samples[upper * channels + channel].toDouble()
                    output[frame * channels + channel] = TimelinePlacementPolicy.clampPcm16(
                        (a + (b - a) * fraction).roundToInt(),
                    )
                }
            }
            return output
        }
    }

    private fun remuxVideoWithMixedAudio(context: Context, sourceUri: Uri, mixedAudio: File, destination: File) {
        val videoExtractor = openExtractor(context, sourceUri)
        val audioExtractor = MediaExtractor().apply { setDataSource(mixedAudio.absolutePath) }
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val videoTrack = (0 until videoExtractor.trackCount).firstOrNull { index ->
                videoExtractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("The source file has no readable video track.")
            val audioTrack = (0 until audioExtractor.trackCount).firstOrNull { index ->
                audioExtractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("The mixed Vietnamese audio has no readable audio track.")

            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)
            destination.parentFile?.mkdirs()
            destination.delete()
            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val outputAudioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            readRotation(context, sourceUri)?.let(muxer::setOrientationHint)
            muxer.start()
            muxerStarted = true

            val videoReader = PendingSampleReader(videoExtractor, videoTrack)
            val audioReader = PendingSampleReader(audioExtractor, audioTrack)
            var videoSample = videoReader.readNext()
            var audioSample = audioReader.readNext()

            while (videoSample != null || audioSample != null) {
                val takeVideo = when {
                    videoSample == null -> false
                    audioSample == null -> true
                    else -> videoSample.info.presentationTimeUs <= audioSample.info.presentationTimeUs
                }
                if (takeVideo) {
                    muxer.writeSampleData(outputVideoTrack, videoSample!!.buffer, videoSample.info)
                    videoSample = videoReader.readNext()
                } else {
                    muxer.writeSampleData(outputAudioTrack, audioSample!!.buffer, audioSample.info)
                    audioSample = audioReader.readNext()
                }
            }
        } finally {
            videoExtractor.release()
            audioExtractor.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!destination.exists() || destination.length() == 0L) destination.delete()
        }
        check(destination.isFile && destination.length() > 0L) { "Video remux produced no output." }
    }

    private data class PendingSample(val buffer: ByteBuffer, val info: MediaCodec.BufferInfo)

    private class PendingSampleReader(
        private val extractor: MediaExtractor,
        trackIndex: Int,
    ) {
        private val buffer: ByteBuffer

        init {
            val format = extractor.getTrackFormat(trackIndex)
            val requested = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                4 * 1024 * 1024
            }
            buffer = ByteBuffer.allocateDirect(max(requested, 4 * 1024 * 1024))
        }

        fun readNext(): PendingSample? {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) return null
            val extractorFlags = extractor.sampleFlags
            if (extractorFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                error("Encrypted media tracks cannot be remuxed locally.")
            }
            var codecFlags = 0
            if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
            val info = MediaCodec.BufferInfo().apply {
                set(0, size, extractor.sampleTime.coerceAtLeast(0L), codecFlags)
            }
            buffer.position(0)
            buffer.limit(size)
            val sample = PendingSample(buffer.duplicate(), info)
            extractor.advance()
            return sample
        }
    }

    private data class WavPcmInfo(
        val file: File,
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long,
    )

    private fun readWavInfo(file: File): WavPcmInfo {
        RandomAccessFile(file, "r").use { input ->
            require(readFourCC(input) == "RIFF") { "Synthesized speech is not a RIFF WAV file." }
            readUInt32LE(input)
            require(readFourCC(input) == "WAVE") { "Synthesized speech is not a WAV file." }
            var audioFormat = -1
            var channels = -1
            var sampleRate = -1
            var bitsPerSample = -1
            var dataOffset = -1L
            var dataSize = -1L
            while (input.filePointer + 8L <= input.length()) {
                val id = readFourCC(input)
                val size = readUInt32LE(input)
                val payloadStart = input.filePointer
                when (id) {
                    "fmt " -> {
                        require(size >= 16L) { "Invalid WAV fmt chunk." }
                        audioFormat = readUInt16LE(input)
                        channels = readUInt16LE(input)
                        sampleRate = readUInt32LE(input).toInt()
                        readUInt32LE(input)
                        readUInt16LE(input)
                        bitsPerSample = readUInt16LE(input)
                    }
                    "data" -> {
                        dataOffset = payloadStart
                        dataSize = minOf(size, input.length() - payloadStart)
                    }
                }
                val padded = size + (size and 1L)
                input.seek((payloadStart + padded).coerceAtMost(input.length()))
                if (audioFormat > 0 && dataOffset >= 0L) break
            }
            require(audioFormat > 0 && channels > 0 && sampleRate > 0 && bitsPerSample > 0 && dataOffset >= 0L && dataSize > 0L) {
                "Synthesized WAV metadata is incomplete."
            }
            return WavPcmInfo(file, audioFormat, channels, sampleRate, bitsPerSample, dataOffset, dataSize)
        }
    }

    private fun readWavSamples(wav: WavPcmInfo): ShortArray {
        require(wav.dataSize <= Int.MAX_VALUE.toLong()) { "A single synthesized speech clip is too large to mix." }
        val bytes = ByteArray(wav.dataSize.toInt())
        RandomAccessFile(wav.file, "r").use { input ->
            input.seek(wav.dataOffset)
            input.readFully(bytes)
        }
        val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(shortBuffer.remaining()).also { shortBuffer.get(it) }
    }

    private fun readRotation(context: Context, uri: Uri): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") retriever.setDataSource(requireNotNull(uri.path)) else retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        } finally {
            retriever.release()
        }
    }

    private fun openExtractor(context: Context, uri: Uri): MediaExtractor {
        val extractor = MediaExtractor()
        if (uri.scheme == "file") {
            try {
                extractor.setDataSource(requireNotNull(uri.path))
                return extractor
            } catch (error: Throwable) {
                extractor.release()
                throw error
            }
        }
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: error("The source video cannot be opened for local media processing.")
        try {
            if (descriptor.declaredLength >= 0) extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
            else extractor.setDataSource(descriptor.fileDescriptor)
        } catch (error: Throwable) {
            extractor.release()
            throw error
        } finally {
            descriptor.close()
        }
        return extractor
    }

    private fun readFourCC(input: RandomAccessFile): String {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun readUInt16LE(input: RandomAccessFile): Int {
        val bytes = ByteArray(2)
        input.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    }

    private fun readUInt32LE(input: RandomAccessFile): Long {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
    }

    private fun purgeRenderCache(parent: File, exclude: File? = null) {
        if (!parent.isDirectory) return
        val now = System.currentTimeMillis()
        parent.listFiles()?.filter { it.isDirectory && it != exclude }?.forEach { directory ->
            if (now - directory.lastModified() > CACHE_MAX_AGE_MS) directory.deleteRecursively()
        }
        val remaining = parent.listFiles()?.filter { it.isDirectory && it != exclude }.orEmpty().sortedBy { it.lastModified() }
        var total = remaining.sumOf(::directorySize) + (exclude?.let(::directorySize) ?: 0L)
        if (total <= CACHE_MAX_BYTES) return
        for (directory in remaining) {
            val size = directorySize(directory)
            if (directory.deleteRecursively()) total -= size
            if (total <= CACHE_TARGET_BYTES) break
        }
    }

    private fun directorySize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::directorySize) ?: 0L
    }
}
