package com.lingoplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRecognitionTest {
    @Test
    fun normalizesWhitespace() {
        assertEquals("Hello world from LingoPlay", ASRFormatting.normalizedText("  Hello\n world   from\tLingoPlay "))
    }

    @Test
    fun removesEmptySegmentsAndClampsTimes() {
        val segments = ASRFormatting.normalizedSegments(
            listOf(
                ASRSegment(0, -2f, 1f, "  hello  "),
                ASRSegment(1, 2f, 1f, "world"),
                ASRSegment(2, 3f, 4f, "   "),
            ),
        )
        assertEquals(2, segments.size)
        assertEquals(0f, segments[0].startSeconds)
        assertEquals(1f, segments[0].endSeconds)
        assertEquals(2f, segments[1].startSeconds)
        assertEquals(2f, segments[1].endSeconds)
        assertEquals("world", segments[1].text)
    }

    @Test
    fun restoresOriginalAudioTimelineAfterZeroBasedAnalysis() {
        val transcript = ASRTranscript(
            language = "en",
            text = "hello world",
            segments = listOf(
                ASRSegment(0, 0.1f, 0.6f, "hello", speakerId = "speaker_1"),
                ASRSegment(1, 0.7f, 1.2f, "world", overlappingSpeakerIds = listOf("speaker_1", "speaker_2")),
            ),
        )

        val shifted = ASRTimelinePolicy.shifted(transcript, offsetMs = 500)

        assertEquals(0.6f, shifted.segments[0].startSeconds, 0.0001f)
        assertEquals(1.1f, shifted.segments[0].endSeconds, 0.0001f)
        assertEquals("speaker_1", shifted.segments[0].speakerId)
        assertEquals(1.2f, shifted.segments[1].startSeconds, 0.0001f)
        assertEquals(listOf("speaker_1", "speaker_2"), shifted.segments[1].overlappingSpeakerIds)
        assertTrue(ASRTimelinePolicy.shifted(transcript, offsetMs = 0) === transcript)
    }

    @Test
    fun skipsNearSilentChunksBeforeWhisper() {
        assertTrue(!ASRAudioGate.hasLikelySpeech(FloatArray(16_000) { 0.0005f }))
        val speechLike = FloatArray(16_000) { index -> if (index % 20 < 10) 0.04f else -0.04f }
        assertTrue(ASRAudioGate.hasLikelySpeech(speechLike))
    }

    @Test
    fun prefersAQuietBoundaryNearTheEndOfAChunk() {
        val sampleRate = 1_000
        val targetSize = 10_000
        val samples = FloatArray(targetSize) { 0.20f }
        for (index in 8_400 until 8_800) samples[index] = 0.001f

        val split = ASRChunkBoundaryPolicy.chooseSplit(samples, samples.size, sampleRate, targetSize)

        assertTrue(split in 8_400..8_920)
        assertTrue(split < targetSize)
    }

    @Test
    fun quietSpeechDoesNotBecomeFakeSilenceWithoutARealDip() {
        val sampleRate = 1_000
        val targetSize = 10_000
        val samples = FloatArray(targetSize) { index -> if (index % 20 < 10) 0.008f else -0.008f }

        assertEquals(targetSize, ASRChunkBoundaryPolicy.chooseSplit(samples, samples.size, sampleRate, targetSize))
    }

    @Test
    fun quietSpeechStillFindsARelativeQuietBoundary() {
        val sampleRate = 1_000
        val targetSize = 10_000
        val samples = FloatArray(targetSize) { index -> if (index % 20 < 10) 0.008f else -0.008f }
        for (index in 8_400 until 8_800) samples[index] = 0.0008f

        val split = ASRChunkBoundaryPolicy.chooseSplit(samples, samples.size, sampleRate, targetSize)

        assertTrue(split in 8_400..8_920)
    }

    @Test
    fun fallsBackToHardBoundaryWhenNoQuietWindowExists() {
        val sampleRate = 1_000
        val targetSize = 10_000
        val samples = FloatArray(targetSize) { 0.20f }

        assertEquals(targetSize, ASRChunkBoundaryPolicy.chooseSplit(samples, samples.size, sampleRate, targetSize))
    }

    @Test
    fun scalesInferenceBudgetDownForLowMemoryDevices() {
        assertEquals(
            InferenceMemoryBudget(numThreads = 1, chunkSeconds = 10),
            InferenceMemoryPolicy.forCharacteristics(lowRamDevice = true, memoryClassMb = 512, availableProcessors = 8),
        )
        assertEquals(
            InferenceMemoryBudget(numThreads = 2, chunkSeconds = 15),
            InferenceMemoryPolicy.forCharacteristics(lowRamDevice = false, memoryClassMb = 256, availableProcessors = 8),
        )
        assertEquals(
            InferenceMemoryBudget(numThreads = 4, chunkSeconds = 25),
            InferenceMemoryPolicy.forCharacteristics(lowRamDevice = false, memoryClassMb = 512, availableProcessors = 8),
        )
    }
}
