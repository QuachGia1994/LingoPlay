package com.lingoplay.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TimelineMixDeviceSmokeTest {
    @Test
    fun stage6MixerRunsOnPhysicalCodec() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testAssets = instrumentation.context.assets
        val root = File(targetContext.cacheDir, "lingoplay/device-smoke").apply {
            deleteRecursively()
            mkdirs()
        }

        val source = copyAsset("stage6-source.mp4", File(root, "source.mp4"), testAssets)
        val dub1 = copyAsset("stage6-dub-1.wav", File(root, "dub-1.wav"), testAssets)
        val dub2 = copyAsset("stage6-dub-2.wav", File(root, "dub-2.wav"), testAssets)

        val media = LocalMediaItem(
            uri = Uri.fromFile(source),
            name = source.name,
            durationMs = 3_000L,
            sizeBytes = source.length(),
            hasAudioTrack = true,
        )
        val dub = DubSpeechDocument(
            voiceName = "device-fixture",
            segments = listOf(
                DubSpeechSegment(
                    id = "device-1",
                    startMs = 650,
                    endMs = 1_300,
                    audioFile = dub1,
                    speechDurationMs = 550,
                    tailSilenceMs = 100,
                    rateMultiplier = 1f,
                ),
                DubSpeechSegment(
                    id = "device-2",
                    startMs = 1_750,
                    endMs = 2_350,
                    audioFile = dub2,
                    speechDurationMs = 500,
                    tailSilenceMs = 100,
                    rateMultiplier = 1f,
                ),
            ),
        )

        val phases = mutableListOf<MixPhase>()
        val result = runBlocking {
            TimelineMixService.render(targetContext, media, dub) { phase -> phases += phase }
        }

        assertTrue("Stage 6 did not produce an MP4 file", result.remuxedVideoFile.isFile)
        assertTrue("Stage 6 output is empty", result.remuxedVideoFile.length() > 0L)
        assertTrue("Expected audio rendering phase", phases.contains(MixPhase.RENDERING_AUDIO))
        assertTrue("Expected remux phase", phases.contains(MixPhase.REMUXING))
        assertTrue("Expected completion phase", phases.contains(MixPhase.COMPLETED))

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(result.remuxedVideoFile.absolutePath)
            var videoTracks = 0
            var audioTracks = 0
            repeat(extractor.trackCount) { index ->
                when {
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true -> videoTracks++
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true -> audioTracks++
                }
            }
            assertTrue("Final MP4 lost the video track", videoTracks >= 1)
            assertTrue("Final MP4 lost the mixed audio track", audioTracks >= 1)
        } finally {
            extractor.release()
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(result.remuxedVideoFile.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            assertTrue("Final MP4 duration is unexpectedly short: $duration ms", duration >= 2_700L)
            assertTrue("Final MP4 duration is unexpectedly long: $duration ms", duration <= 3_300L)
        } finally {
            retriever.release()
        }

        Log.i(
            "LingoPlayDeviceSmoke",
            "PASS output=${result.remuxedVideoFile.absolutePath} bytes=${result.remuxedVideoFile.length()} phases=$phases",
        )
    }

    @Test
    fun stage6MixerSustainsTwoMinuteMediaOnPhysicalCodec() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testAssets = instrumentation.context.assets
        val root = File(targetContext.cacheDir, "lingoplay/device-smoke-long").apply {
            deleteRecursively()
            mkdirs()
        }

        val source = copyAsset("stage6-source-120s.mp4", File(root, "source-120s.mp4"), testAssets)
        val dub1 = copyAsset("stage6-dub-1.wav", File(root, "dub-1.wav"), testAssets)
        val dub2 = copyAsset("stage6-dub-2.wav", File(root, "dub-2.wav"), testAssets)
        val media = LocalMediaItem(
            uri = Uri.fromFile(source),
            name = source.name,
            durationMs = 120_000L,
            sizeBytes = source.length(),
            hasAudioTrack = true,
        )
        val dub = DubSpeechDocument(
            voiceName = "device-fixture-long",
            segments = listOf(
                DubSpeechSegment("long-1", 5_000, 5_700, dub1, 550, 150, 1f),
                DubSpeechSegment("long-2", 115_000, 115_650, dub2, 500, 150, 1f),
            ),
        )

        val result = runBlocking { TimelineMixService.render(targetContext, media, dub) }
        assertTrue("Two-minute Stage 6 output is missing", result.remuxedVideoFile.isFile)
        assertTrue("Two-minute Stage 6 output is empty", result.remuxedVideoFile.length() > 0L)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(result.remuxedVideoFile.absolutePath)
            var hasVideo = false
            var hasAudio = false
            repeat(extractor.trackCount) { index ->
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/")) hasAudio = true
            }
            assertTrue("Two-minute output lost video", hasVideo)
            assertTrue("Two-minute output lost mixed audio", hasAudio)
        } finally {
            extractor.release()
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(result.remuxedVideoFile.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            assertTrue("Two-minute output is too short: $duration ms", duration >= 119_000L)
            assertTrue("Two-minute output is too long: $duration ms", duration <= 121_000L)
        } finally {
            retriever.release()
        }

        Log.i(
            "LingoPlayDeviceSmoke",
            "PASS_LONG output=${result.remuxedVideoFile.absolutePath} bytes=${result.remuxedVideoFile.length()}",
        )
    }

    @Test
    fun finalSpeechTailSurvivesPhysicalMixAndRemux() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val root = File(target.cacheDir, "stage19-tail-test").apply { mkdirs() }
        var rendered: File? = null
        try {
            val source = copyAsset("stage6-source.mp4", File(root, "source.mp4"), instrumentation.context.assets)
            val clip = copyAsset("stage6-dub-1.wav", File(root, "tail.wav"), instrumentation.context.assets)
            val media = LocalMediaItem(Uri.fromFile(source), source.name, 3_000, source.length(), true)
            val dub = DubSpeechDocument("fixture", listOf(
                DubSpeechSegment("tail", 2_900, 3_450, clip, 550, 0, 1f),
            ))
            val result = TimelineMixService.render(target, media, dub)
            rendered = result.remuxedVideoFile
            assertTrue("Result metadata must retain the speech tail", result.durationMs >= 3_450)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(result.remuxedVideoFile.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("Remux truncated the speech tail: $duration", duration >= 3_400)
            } finally { retriever.release() }
            var tailPeak = 0f
            AndroidAudioDecoder.forEachChunk(result.remuxedVideoFile, 1) { chunk ->
                chunk.samples.forEachIndexed { index, value ->
                    val time = chunk.startSeconds + index.toFloat() / chunk.sampleRate
                    if (time > 3.1f) tailPeak = maxOf(tailPeak, kotlin.math.abs(value))
                }
            }
            assertTrue("The audio beyond the video must contain speech, not just silence", tailPeak > 0.01f)
        } finally {
            rendered?.parentFile?.deleteRecursively()
            root.deleteRecursively()
        }
    }

    private fun copyAsset(name: String, destination: File, assets: android.content.res.AssetManager): File {
        destination.parentFile?.mkdirs()
        assets.open(name).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        return destination
    }
}
