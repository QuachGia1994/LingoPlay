package com.lingoplay.app

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class SourceSeparationDeviceTest {
    @Test
    fun pinnedSpleeterSeparatesFixtureWithBoundedDurationDrift() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertNotNull(
            "Install the verified Clean Background model on the target device before this physical gate.",
            SourceSeparationModelStore.find(target),
        )
        assertTrue("The pinned sherpa-onnx source-separation C API must be executable.", SourceSeparationNative.runtimeAvailable)

        val fixture = File(target.cacheDir, "stage20-source.mp4")
        instrumentation.context.assets.open("stage6-source.mp4").use { input ->
            fixture.outputStream().use(input::copyTo)
        }
        var stems: SeparatedAudioStems? = null
        try {
            val sourceDurationMs = mediaDurationMs(fixture)
            stems = withTimeout(180_000) {
                CleanBackgroundCapability.engine(target).separate(fixture)
            }
            val vocals = requireNotNull(stems.voice.takeIf(File::isFile))
            val background = requireNotNull(stems.background.takeIf(File::isFile))
            assertTrue("Vocals WAV must contain PCM data.", vocals.length() > 44L)
            assertTrue("Background WAV must contain PCM data.", background.length() > 44L)

            val vocalsDurationMs = pcm16WaveDurationMs(vocals)
            val backgroundDurationMs = pcm16WaveDurationMs(background)
            assertTrue("Vocals duration drifted from source.", abs(vocalsDurationMs - sourceDurationMs) <= 300L)
            assertTrue("Background duration drifted from source.", abs(backgroundDurationMs - sourceDurationMs) <= 300L)
            assertTrue("Background stem must contain audible energy.", pcm16Rms(background) > 0.0005)
            assertTrue("Separated stems must not be byte-identical.", vocals.readBytes().contentEquals(background.readBytes()).not())
        } finally {
            stems?.cleanup()
            fixture.delete()
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
