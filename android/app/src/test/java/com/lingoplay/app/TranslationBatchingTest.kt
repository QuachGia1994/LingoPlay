package com.lingoplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchingTest {
    @Test
    fun `transcript segments preserve timing and stable ids`() {
        val transcript = ASRTranscript(
            language = "en",
            text = "Hello world",
            segments = listOf(
                ASRSegment(3, 0.25f, 1.5f, "Hello"),
                ASRSegment(4, 1.5f, 2.25f, "world"),
            ),
        )

        val segments = TranslationBatching.fromTranscript(transcript)

        assertEquals(listOf("s0", "s1"), segments.map { it.id })
        assertEquals(250, segments[0].startMs)
        assertEquals(1500, segments[0].endMs)
        assertEquals("world", segments[1].text)
    }

    @Test
    fun `batching stays below segment and character ceilings`() {
        val segments = (0 until 205).map { index ->
            TranslationSourceSegment("s$index", index * 1000, (index + 1) * 1000, "x".repeat(120))
        }

        val batches = TranslationBatching.batches(segments, maxSegments = 80, maxChars = 10_000)

        assertTrue(batches.size >= 3)
        assertTrue(batches.all { it.size <= 80 })
        assertTrue(batches.all { batch -> batch.sumOf { it.text.length } <= 10_000 })
        assertEquals(segments.map { it.id }, batches.flatten().map { it.id })
    }
}
