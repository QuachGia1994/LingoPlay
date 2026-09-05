package com.lingoplay.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID


enum class SourceSeparationAvailability {
    UNAVAILABLE,
    ENGINE_READY,
}

data class SeparatedAudioStems(
    val voice: File,
    val background: File,
    val rootDirectory: File,
) {
    fun cleanup() {
        rootDirectory.deleteRecursively()
    }
}

internal object SourceSeparationCachePolicy {
    fun purgeStaleSessions(context: android.content.Context) {
        File(context.cacheDir, "lingoplay/separated-audio").deleteRecursively()
    }
}

interface SourceSeparationEngine {
    val availability: SourceSeparationAvailability
    suspend fun separate(sourceAudio: File): SeparatedAudioStems
}

object UnavailableSourceSeparationEngine : SourceSeparationEngine {
    override val availability = SourceSeparationAvailability.UNAVAILABLE
    override suspend fun separate(sourceAudio: File): SeparatedAudioStems {
        throw IllegalStateException("Clean Background requires the verified local source-separation model.")
    }
}

object CleanBackgroundCapability {
    fun isAvailable(context: android.content.Context): Boolean =
        SourceSeparationNative.runtimeAvailable && SourceSeparationModelStore.find(context) != null

    fun engine(context: android.content.Context): SourceSeparationEngine {
        val model = SourceSeparationModelStore.find(context) ?: return UnavailableSourceSeparationEngine
        return if (SourceSeparationNative.runtimeAvailable) {
            SpleeterSourceSeparationEngine(context.applicationContext, model)
        } else {
            UnavailableSourceSeparationEngine
        }
    }
}

internal object SourceSeparationNative {
    private val bridgeLoaded = runCatching { System.loadLibrary("lingoplay-separation") }.isSuccess

    val runtimeAvailable: Boolean by lazy {
        bridgeLoaded && runCatching { nativeRuntimeAvailable() }.getOrDefault(false)
    }

    fun separateChunk(
        model: SpleeterSourceSeparationModel,
        chunk: StereoAudioChunk,
        vocalsOutput: File,
        accompanimentOutput: File,
    ) {
        check(runtimeAvailable) { "The local Clean Background runtime is unavailable." }
        check(
            nativeSeparateChunk(
                model.vocals.absolutePath,
                model.accompaniment.absolutePath,
                chunk.planarStereo,
                chunk.frames,
                chunk.sampleRate,
                chunk.processStartFrame,
                chunk.coreStartFrame,
                chunk.coreFrames,
                vocalsOutput.absolutePath,
                accompanimentOutput.absolutePath,
            ),
        ) { "Local source separation failed for an audio chunk." }
    }

    @JvmStatic private external fun nativeRuntimeAvailable(): Boolean
    @JvmStatic private external fun nativeSeparateChunk(
        vocalsModel: String,
        accompanimentModel: String,
        planarStereo: FloatArray,
        frames: Int,
        sampleRate: Int,
        processStartFrame: Long,
        coreStartFrame: Long,
        coreFrames: Int,
        vocalsOutput: String,
        accompanimentOutput: String,
    ): Boolean
}

internal class SpleeterSourceSeparationEngine(
    private val context: android.content.Context,
    private val model: SpleeterSourceSeparationModel,
) : SourceSeparationEngine {
    override val availability = SourceSeparationAvailability.ENGINE_READY

    override suspend fun separate(sourceAudio: File): SeparatedAudioStems = withContext(Dispatchers.IO) {
        require(sourceAudio.isFile && sourceAudio.length() > 0L) { "Prepared audio is unavailable for Clean Background." }
        val parent = File(context.cacheDir, "lingoplay/separated-audio").apply { mkdirs() }
        val root = File(parent, UUID.randomUUID().toString()).apply { mkdirs() }
        val vocals = File(root, "vocals.wav")
        val background = File(root, "accompaniment.wav")
        var success = false
        try {
            Pcm16WaveAppender(vocals).use { vocalsWriter ->
                Pcm16WaveAppender(background).use { backgroundWriter ->
                    var chunkIndex = 0
                    StereoAudioChunkDecoder.forEachChunk(
                        file = sourceAudio,
                        coreSeconds = 10,
                        contextMilliseconds = 500,
                    ) { chunk ->
                        currentCoroutineContext().ensureActive()
                        val voiceChunk = File(root, "voice-$chunkIndex.wav")
                        val backgroundChunk = File(root, "background-$chunkIndex.wav")
                        try {
                            SourceSeparationNative.separateChunk(model, chunk, voiceChunk, backgroundChunk)
                            currentCoroutineContext().ensureActive()
                            vocalsWriter.append(voiceChunk, chunk)
                            backgroundWriter.append(backgroundChunk, chunk)
                        } finally {
                            voiceChunk.delete()
                            backgroundChunk.delete()
                        }
                        chunkIndex++
                    }
                    check(chunkIndex > 0) { "Prepared audio contained no decodable PCM for Clean Background." }
                }
            }
            check(vocals.isFile && vocals.length() > 44L && background.isFile && background.length() > 44L) {
                "Clean Background produced empty stems."
            }
            success = true
            SeparatedAudioStems(vocals, background, root)
        } finally {
            if (!success) root.deleteRecursively()
        }
    }
}

internal data class StereoAudioChunk(
    val planarStereo: FloatArray,
    val frames: Int,
    val sampleRate: Int,
    val processStartFrame: Long,
    val coreStartFrame: Long,
    val coreFrames: Int,
)

private object StereoAudioChunkDecoder {
    suspend fun forEachChunk(
        file: File,
        coreSeconds: Int,
        contextMilliseconds: Int,
        consume: suspend (StereoAudioChunk) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(coreSeconds in 4..30)
        require(contextMilliseconds in 100..2_000)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Prepared audio contains no readable audio track.")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Prepared audio codec is unknown.")
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
            var chunker = StereoContextChunkAccumulator(sampleRate, coreSeconds, contextMilliseconds)

            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex) ?: error("Audio decoder input buffer unavailable.")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        val newRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (newRate != sampleRate) {
                            chunker.flush().forEach { consume(it) }
                            sampleRate = newRate
                            chunker = StereoContextChunkAccumulator(sampleRate, coreSeconds, contextMilliseconds)
                        }
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                    }
                    else -> if (outputIndex >= 0) {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            appendDecoded(
                                output.slice().order(ByteOrder.nativeOrder()), pcmEncoding, channels, chunker, consume,
                            )
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEnded = true
                    }
                }
            }
            chunker.flush().forEach { consume(it) }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private suspend fun appendDecoded(
        buffer: ByteBuffer,
        encoding: Int,
        channels: Int,
        chunker: StereoContextChunkAccumulator,
        consume: suspend (StereoAudioChunk) -> Unit,
    ) {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> error("Unsupported decoded PCM encoding for Clean Background: $encoding")
        }
        val frameBytes = bytesPerSample * channels
        while (buffer.remaining() >= frameBytes) {
            val first = readSample(buffer, encoding)
            val second = if (channels > 1) readSample(buffer, encoding) else first
            repeat((channels - 2).coerceAtLeast(0)) { readSample(buffer, encoding) }
            chunker.add(first, second)?.let { consume(it) }
        }
    }

    private fun readSample(buffer: ByteBuffer, encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
        AudioFormat.ENCODING_PCM_16BIT -> (buffer.short / 32768f).coerceIn(-1f, 1f)
        AudioFormat.ENCODING_PCM_8BIT -> (((buffer.get().toInt() and 0xff) - 128) / 128f).coerceIn(-1f, 1f)
        else -> 0f
    }
}

internal class StereoContextChunkAccumulator(
    private val sampleRate: Int,
    coreSeconds: Int,
    contextMilliseconds: Int,
) {
    private val coreCapacity = sampleRate * coreSeconds
    private val contextCapacity = ((sampleRate.toLong() * contextMilliseconds) / 1_000L).toInt().coerceAtLeast(1)
    private val currentCapacity = coreCapacity + contextCapacity
    private val currentLeft = FloatArray(currentCapacity)
    private val currentRight = FloatArray(currentCapacity)
    private var currentSize = 0
    private var leftContextLeft = FloatArray(contextCapacity)
    private var leftContextRight = FloatArray(contextCapacity)
    private var leftContextSize = 0
    private var coreStartFrame = 0L

    fun add(leftSample: Float, rightSample: Float): StereoAudioChunk? {
        currentLeft[currentSize] = leftSample
        currentRight[currentSize] = rightSample
        currentSize++
        return if (currentSize == currentCapacity) emitCurrent() else null
    }

    fun flush(): List<StereoAudioChunk> {
        if (currentSize == 0) return emptyList()
        val chunks = mutableListOf<StereoAudioChunk>()
        while (currentSize > 0) {
            chunks += emitCurrent()
        }
        return chunks
    }

    private fun emitCurrent(): StereoAudioChunk {
        val coreFrames = minOf(coreCapacity, currentSize)
        val processFrames = leftContextSize + currentSize
        val planar = FloatArray(processFrames * 2)
        leftContextLeft.copyInto(planar, 0, 0, leftContextSize)
        currentLeft.copyInto(planar, leftContextSize, 0, currentSize)
        leftContextRight.copyInto(planar, processFrames, 0, leftContextSize)
        currentRight.copyInto(planar, processFrames + leftContextSize, 0, currentSize)
        val processStartFrame = coreStartFrame - leftContextSize.toLong()
        val emitted = StereoAudioChunk(
            planarStereo = planar,
            frames = processFrames,
            sampleRate = sampleRate,
            processStartFrame = processStartFrame,
            coreStartFrame = coreStartFrame,
            coreFrames = coreFrames,
        )

        val nextLeftSize = minOf(contextCapacity, coreFrames)
        if (nextLeftSize > 0) {
            val leftStart = coreFrames - nextLeftSize
            currentLeft.copyInto(leftContextLeft, 0, leftStart, coreFrames)
            currentRight.copyInto(leftContextRight, 0, leftStart, coreFrames)
        }
        leftContextSize = nextLeftSize

        val carry = (currentSize - coreFrames).coerceAtLeast(0)
        if (carry > 0) {
            currentLeft.copyInto(currentLeft, 0, coreFrames, currentSize)
            currentRight.copyInto(currentRight, 0, coreFrames, currentSize)
        }
        currentSize = carry
        coreStartFrame += coreFrames.toLong()
        return emitted
    }
}

private class Pcm16WaveAppender(private val destination: File) : AutoCloseable {
    private val output = RandomAccessFile(destination, "rw")
    private var sampleRate = 0
    private var channels = 0
    private var dataBytes = 0L
    private var closed = false

    init {
        output.setLength(0)
        output.write(ByteArray(44))
    }

    fun append(chunk: File, sourceChunk: StereoAudioChunk) {
        val info = readWaveInfo(chunk)
        if (sampleRate == 0) {
            sampleRate = info.sampleRate
            channels = info.channels
        } else {
            check(sampleRate == info.sampleRate && channels == info.channels) { "Separated chunk format changed unexpectedly." }
        }
        check(info.bitsPerSample == 16 && info.audioFormat == 1) { "Separated stem must be PCM16 WAV." }
        val bytesPerFrame = info.channels * 2L
        check(info.dataBytes % bytesPerFrame == 0L) { "Separated WAV has a partial PCM frame." }
        val actualFrames = info.dataBytes / bytesPerFrame
        val coreEndFrame = sourceChunk.coreStartFrame + sourceChunk.coreFrames.toLong()
        val expectedStart = mapFrame(sourceChunk.coreStartFrame, sourceChunk.sampleRate, info.sampleRate)
        val expectedEnd = mapFrame(coreEndFrame, sourceChunk.sampleRate, info.sampleRate)
        val expectedFrames = (expectedEnd - expectedStart).coerceAtLeast(1L)
        check(actualFrames <= expectedFrames) { "Separated core exceeds its expected timeline span." }
        val missingFrames = expectedFrames - actualFrames
        val maximumTailPadding = maxOf(4_096L, info.sampleRate.toLong() / 10L)
        check(missingFrames <= maximumTailPadding) { "Separated core lost too many output frames." }

        RandomAccessFile(chunk, "r").use { input ->
            input.seek(info.dataOffset)
            var remaining = info.dataBytes
            val buffer = ByteArray(256 * 1024)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count <= 0) error("Separated WAV chunk was truncated.")
                output.write(buffer, 0, count)
                dataBytes += count
                remaining -= count
            }
        }
        if (missingFrames > 0L) {
            var remainingBytes = missingFrames * bytesPerFrame
            val zeros = ByteArray(64 * 1024)
            while (remainingBytes > 0L) {
                val count = minOf(zeros.size.toLong(), remainingBytes).toInt()
                output.write(zeros, 0, count)
                dataBytes += count
                remainingBytes -= count
            }
        }
    }

    private fun mapFrame(frame: Long, sourceRate: Int, targetRate: Int): Long {
        val numerator = frame * targetRate.toLong()
        return (numerator + sourceRate.toLong() / 2L) / sourceRate.toLong()
    }

    override fun close() {
        if (closed) return
        closed = true
        if (sampleRate > 0 && channels > 0) writeHeader()
        output.close()
    }

    private fun writeHeader() {
        check(dataBytes <= 0xffff_ffffL - 36L) { "Separated WAV exceeds RIFF size limit." }
        output.seek(0)
        output.writeBytes("RIFF")
        output.writeIntLE((36L + dataBytes).toInt())
        output.writeBytes("WAVEfmt ")
        output.writeIntLE(16)
        output.writeShortLE(1)
        output.writeShortLE(channels)
        output.writeIntLE(sampleRate)
        val byteRate = sampleRate * channels * 2
        output.writeIntLE(byteRate)
        output.writeShortLE(channels * 2)
        output.writeShortLE(16)
        output.writeBytes("data")
        output.writeIntLE(dataBytes.toInt())
    }

    private data class WaveInfo(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataBytes: Long,
    )

    private fun readWaveInfo(file: File): WaveInfo = RandomAccessFile(file, "r").use { input ->
        check(input.readFourCC() == "RIFF") { "Invalid separated WAV header." }
        input.skipBytes(4)
        check(input.readFourCC() == "WAVE") { "Invalid separated WAV format." }
        var audioFormat = 0
        var channelCount = 0
        var rate = 0
        var bits = 0
        var dataOffset = -1L
        var size = -1L
        while (input.filePointer + 8 <= input.length()) {
            val id = input.readFourCC()
            val chunkSize = input.readUnsignedIntLE()
            val chunkStart = input.filePointer
            when (id) {
                "fmt " -> {
                    check(chunkSize >= 16) { "Invalid WAV fmt chunk." }
                    audioFormat = input.readUnsignedShortLE()
                    channelCount = input.readUnsignedShortLE()
                    rate = input.readIntLE()
                    input.skipBytes(6)
                    bits = input.readUnsignedShortLE()
                }
                "data" -> {
                    dataOffset = chunkStart
                    size = minOf(chunkSize, input.length() - chunkStart)
                }
            }
            input.seek(chunkStart + chunkSize + (chunkSize and 1L))
            if (dataOffset >= 0 && audioFormat != 0) break
        }
        check(dataOffset >= 0 && size >= 0 && channelCount > 0 && rate > 0) { "Separated WAV is incomplete." }
        WaveInfo(audioFormat, channelCount, rate, bits, dataOffset, size)
    }
}

private fun RandomAccessFile.readFourCC(): String {
    val bytes = ByteArray(4)
    readFully(bytes)
    return bytes.toString(Charsets.US_ASCII)
}
private fun RandomAccessFile.readUnsignedShortLE(): Int {
    val b0 = readUnsignedByte()
    val b1 = readUnsignedByte()
    return b0 or (b1 shl 8)
}
private fun RandomAccessFile.readIntLE(): Int =
    readUnsignedByte() or (readUnsignedByte() shl 8) or (readUnsignedByte() shl 16) or (readUnsignedByte() shl 24)
private fun RandomAccessFile.readUnsignedIntLE(): Long = readIntLE().toLong() and 0xffff_ffffL
private fun RandomAccessFile.writeShortLE(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
}
private fun RandomAccessFile.writeIntLE(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
    write((value ushr 16) and 0xff)
    write((value ushr 24) and 0xff)
}
