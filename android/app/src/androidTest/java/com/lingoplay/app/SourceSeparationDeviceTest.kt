package com.lingoplay.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class SourceSeparationDeviceTest {
    @Test
    fun pinnedSpleeterSeparatesFixtureWithBoundedDurationDrift() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        ensureVerifiedModel(target)
        assertNotNull("The verified Clean Background model must be active.", SourceSeparationModelStore.find(target))
        assertTrue("The pinned sherpa-onnx source-separation C API must be executable.", SourceSeparationNative.runtimeAvailable)

        val fixture = File(target.cacheDir, "stage20-source.mp4")
        instrumentation.context.assets.open("stage6-source.mp4").use { input ->
            fixture.outputStream().use(input::copyTo)
        }
        var stems: SeparatedAudioStems? = null
        var rendered: LocalDubMediaResult? = null
        val dubAudio = File(target.cacheDir, "stage20-dub.wav")
        try {
            val sourceDurationMs = mediaDurationMs(fixture)
            val peakPssKiB = AtomicLong(0)
            val peakThermalStatus = AtomicInteger(0)
            val powerManager = target.getSystemService(PowerManager::class.java)
            val sampler = launch(Dispatchers.Default) {
                while (isActive) {
                    peakPssKiB.updateAndGet { previous -> maxOf(previous, Debug.getPss()) }
                    peakThermalStatus.updateAndGet { previous ->
                        maxOf(previous, powerManager.currentThermalStatus)
                    }
                    delay(50)
                }
            }
            val separationStartedAt = SystemClock.elapsedRealtime()
            try {
                stems = withTimeout(180_000) {
                    CleanBackgroundCapability.engine(target).separate(fixture)
                }
            } finally {
                sampler.cancelAndJoin()
            }
            println(
                "STAGE20_1_RUNTIME separationMs=${SystemClock.elapsedRealtime() - separationStartedAt} " +
                    "peakPssKiB=${peakPssKiB.get()} thermal=${peakThermalStatus.get()}",
            )
            val vocals = requireNotNull(stems.voice.takeIf(File::isFile))
            val background = requireNotNull(stems.background.takeIf(File::isFile))
            assertTrue("Vocals WAV must contain PCM data.", vocals.length() > 44L)
            assertTrue("Background WAV must contain PCM data.", background.length() > 44L)

            val vocalsDurationMs = pcm16WaveDurationMs(vocals)
            val backgroundDurationMs = pcm16WaveDurationMs(background)
            assertTrue("Vocals duration drifted from source.", abs(vocalsDurationMs - sourceDurationMs) <= 100L)
            assertTrue("Background duration drifted from source.", abs(backgroundDurationMs - sourceDurationMs) <= 100L)
            assertTrue("Background stem must contain audible energy.", pcm16Rms(background) > 0.0005)
            assertTrue("Separated stems must not be byte-identical.", vocals.readBytes().contentEquals(background.readBytes()).not())

            writeToneWave(dubAudio, sampleRate = 16_000, channels = 1, seconds = 1, frequencyHz = 330.0)
            val media = LocalMediaItem(
                uri = Uri.fromFile(fixture),
                name = fixture.name,
                durationMs = sourceDurationMs,
                sizeBytes = fixture.length(),
                hasAudioTrack = true,
            )
            val dub = DubSpeechDocument(
                voiceName = "fixture",
                segments = listOf(DubSpeechSegment("s0", 500, 1_500, dubAudio, 1_000, 0, 1f)),
            )
            rendered = withTimeout(120_000) {
                TimelineMixService.render(
                    context = target,
                    media = media,
                    dub = dub,
                    backgroundAudioFile = background,
                )
            }
            assertTrue("Clean Background direct-WAV mix/remux must produce video.", rendered.remuxedVideoFile.length() > 0L)
        } finally {
            rendered?.remuxedVideoFile?.parentFile?.deleteRecursively()
            stems?.cleanup()
            dubAudio.delete()
            fixture.delete()
        }
    }

    @Test
    fun separatedBackgroundRecoversOriginalAudioPtsOrigin() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val source = File(target.cacheDir, "stage20-pts-source.mp4")
        val shifted = File(target.cacheDir, "stage20-pts-shifted.mp4")
        instrumentation.context.assets.open("stage6-source.mp4").use { input ->
            source.outputStream().use(input::copyTo)
        }
        try {
            remuxWithAudioOffset(source, shifted, 120_000L)
            val startUs = TimelineMixService.originalAudioStartUs(target, Uri.fromFile(shifted))
            assertTrue("Expected ~120 ms audio origin, observed $startUs us", startUs in 100_000L..140_000L)
        } finally {
            source.delete()
            shifted.delete()
        }
    }

    @Test
    fun contextualBoundariesStayTightAndCancelledNativeRunLeavesNoSession() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        ensureVerifiedModel(target)
        val fixture = File(target.cacheDir, "stage20-boundary-tone.wav")
        writeToneWave(fixture, sampleRate = 44_100, channels = 2, seconds = 25, frequencyHz = 440.0)
        var stems: SeparatedAudioStems? = null
        try {
            stems = withTimeout(240_000) { CleanBackgroundCapability.engine(target).separate(fixture) }
            assertTrue(abs(pcm16WaveDurationMs(stems.voice) - 25_000L) <= 30L)
            assertTrue(abs(pcm16WaveDurationMs(stems.background) - 25_000L) <= 30L)
            val vocalSeam = maxOf(seamDelta(stems.voice, 10), seamDelta(stems.voice, 20))
            val backgroundSeam = maxOf(seamDelta(stems.background, 10), seamDelta(stems.background, 20))
            println("STAGE20_1_SEAM vocal=$vocalSeam background=$backgroundSeam")
            assertTrue("Vocals contain a large digital discontinuity at a core boundary.", vocalSeam < 0.35)
            assertTrue("Background contains a large digital discontinuity at a core boundary.", backgroundSeam < 0.35)
        } finally {
            stems?.cleanup()
        }

        val parent = File(target.cacheDir, "lingoplay/separated-audio")
        val before = parent.listFiles()?.map { it.name }?.toSet().orEmpty()
        var completed: SeparatedAudioStems? = null
        val deferred = async { CleanBackgroundCapability.engine(target).separate(fixture) }
        delay(75)
        deferred.cancel()
        try {
            completed = deferred.await()
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected when cancellation lands during a synchronous native chunk.
        } finally {
            completed?.cleanup()
        }
        val leaked = parent.listFiles()?.filter { it.name !in before }.orEmpty()
        assertTrue("Cancelled separation left transient session directories: ${leaked.map { it.name }}", leaked.isEmpty())
        fixture.delete()
        Unit
    }

    private fun remuxWithAudioOffset(source: File, destination: File, audioOffsetUs: Long) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            val trackMap = IntArray(extractor.trackCount)
            val audioTracks = BooleanArray(extractor.trackCount)
            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                trackMap[index] = muxer.addTrack(format)
                audioTracks[index] = format.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                extractor.selectTrack(index)
            }
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val firstPtsUs = LongArray(extractor.trackCount) { Long.MIN_VALUE }
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val inputTrack = extractor.sampleTrackIndex
                val sourcePts = extractor.sampleTime
                if (firstPtsUs[inputTrack] == Long.MIN_VALUE) firstPtsUs[inputTrack] = sourcePts
                val normalizedPts = sourcePts - firstPtsUs[inputTrack]
                val pts = normalizedPts + if (audioTracks[inputTrack]) audioOffsetUs else 0L
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, size, pts.coerceAtLeast(0L), flags)
                buffer.position(0)
                buffer.limit(size)
                muxer.writeSampleData(trackMap[inputTrack], buffer, info)
                if (!extractor.advance()) break
            }
        } finally {
            extractor.release()
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
        assertTrue("Offset media fixture was not created.", destination.isFile && destination.length() > 0L)
    }

    private suspend fun ensureVerifiedModel(target: android.content.Context) {
        if (SourceSeparationModelStore.find(target) != null) return
        withTimeout(240_000) {
            SourceSeparationModelInstaller.install(target, wifiOnly = false) { state ->
                println("STAGE20_1_MODEL_DOWNLOAD ${state.bytesDone}/${state.bytesTotal}")
            }
        }
    }

    private fun mediaDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("Fixture duration unavailable")
        } finally {
            retriever.release()
        }
    }

    private fun pcm16WaveDurationMs(file: File): Long = RandomAccessFile(file, "r").use { input ->
        input.seek(22)
        val channels = readUInt16LE(input)
        val sampleRate = readUInt32LE(input)
        input.seek(34)
        val bits = readUInt16LE(input)
        input.seek(40)
        val dataBytes = readUInt32LE(input)
        require(channels > 0 && sampleRate > 0L && bits == 16)
        val bytesPerFrame = channels * 2L
        (dataBytes * 1_000L) / (sampleRate * bytesPerFrame)
    }

    private fun pcm16Rms(file: File): Double = RandomAccessFile(file, "r").use { input ->
        input.seek(44)
        var sum = 0.0
        var count = 0L
        while (input.filePointer + 1 < input.length()) {
            val sample = readUInt16LE(input).toShort().toDouble() / Short.MAX_VALUE.toDouble()
            sum += sample * sample
            count++
        }
        if (count == 0L) 0.0 else sqrt(sum / count.toDouble())
    }

    private fun seamDelta(file: File, second: Int): Double = RandomAccessFile(file, "r").use { input ->
        input.seek(22)
        val channels = readUInt16LE(input)
        val sampleRate = readUInt32LE(input).toInt()
        val frame = sampleRate.toLong() * second.toLong()
        val bytesPerFrame = channels * 2L
        val beforeOffset = 44L + (frame - 1L) * bytesPerFrame
        val afterOffset = 44L + frame * bytesPerFrame
        var maximum = 0.0
        repeat(channels) { channel ->
            input.seek(beforeOffset + channel * 2L)
            val before = readUInt16LE(input).toShort().toDouble() / Short.MAX_VALUE.toDouble()
            input.seek(afterOffset + channel * 2L)
            val after = readUInt16LE(input).toShort().toDouble() / Short.MAX_VALUE.toDouble()
            maximum = maxOf(maximum, abs(after - before))
        }
        maximum
    }

    private fun writeToneWave(file: File, sampleRate: Int, channels: Int, seconds: Int, frequencyHz: Double) {
        val frames = sampleRate * seconds
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0)
            output.writeBytes("RIFF")
            writeUInt32LE(output, 36L + frames.toLong() * channels * 2L)
            output.writeBytes("WAVEfmt ")
            writeUInt32LE(output, 16)
            writeUInt16LE(output, 1)
            writeUInt16LE(output, channels)
            writeUInt32LE(output, sampleRate.toLong())
            writeUInt32LE(output, sampleRate.toLong() * channels * 2L)
            writeUInt16LE(output, channels * 2)
            writeUInt16LE(output, 16)
            output.writeBytes("data")
            writeUInt32LE(output, frames.toLong() * channels * 2L)
            repeat(frames) { frame ->
                val phase = 2.0 * Math.PI * frequencyHz * frame.toDouble() / sampleRate.toDouble()
                val sample = (kotlin.math.sin(phase) * 0.22 * Short.MAX_VALUE).toInt().toShort()
                repeat(channels) { writeUInt16LE(output, sample.toInt() and 0xffff) }
            }
        }
    }

    private fun writeUInt16LE(output: RandomAccessFile, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeUInt32LE(output: RandomAccessFile, value: Long) {
        repeat(4) { byte -> output.write(((value ushr (byte * 8)) and 0xff).toInt()) }
    }

    private fun readUInt16LE(input: RandomAccessFile): Int {
        val lo = input.readUnsignedByte()
        val hi = input.readUnsignedByte()
        return lo or (hi shl 8)
    }

    private fun readUInt32LE(input: RandomAccessFile): Long {
        var value = 0L
        repeat(4) { byte -> value = value or (input.readUnsignedByte().toLong() shl (byte * 8)) }
        return value
    }
}
