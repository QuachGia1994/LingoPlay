package com.lingoplay.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFormattingTest {
    @Test
    fun durationFormatsShortAndLongMedia() {
        assertEquals("00:00", MediaFormatting.duration(0))
        assertEquals("01:05", MediaFormatting.duration(65_000))
        assertEquals("01:01:01", MediaFormatting.duration(3_661_000))
    }

    @Test
    fun bytesFormatsConsumerFriendlyUnits() {
        assertEquals("0 B", MediaFormatting.bytes(0))
        assertEquals("1.5 KB", MediaFormatting.bytes(1_500))
        assertEquals("2.5 MB", MediaFormatting.bytes(2_500_000))
        assertEquals("1.2 GB", MediaFormatting.bytes(1_200_000_000))
    }
}
