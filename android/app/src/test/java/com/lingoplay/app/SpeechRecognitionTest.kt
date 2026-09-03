package com.lingoplay.app

import org.junit.Assert.assertEquals
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
