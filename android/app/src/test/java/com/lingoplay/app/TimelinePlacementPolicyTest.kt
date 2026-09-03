package com.lingoplay.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelinePlacementPolicyTest {
    @Test
    fun convertsMillisecondsToFramesWithoutFloatingPointDrift() {
        assertEquals(24_000L, TimelinePlacementPolicy.frameAt(1_000, 24_000))
        assertEquals(12_000L, TimelinePlacementPolicy.frameAt(500, 24_000))
        assertEquals(86_400_000L, TimelinePlacementPolicy.frameCount(3_600_000, 24_000))
    }

    @Test
    fun clampsMixedPcmToSigned16BitRange() {
        assertEquals(Short.MAX_VALUE, TimelinePlacementPolicy.clampPcm16(50_000))
        assertEquals(Short.MIN_VALUE, TimelinePlacementPolicy.clampPcm16(-50_000))
        assertEquals(1_234.toShort(), TimelinePlacementPolicy.clampPcm16(1_234))
    }

    @Test
    fun duckEnvelopeFadesAroundSpeechWithoutMutingBackground() {
        val segment = DubSpeechSegment(
            id = "s1",
            startMs = 1_000,
            endMs = 2_000,
            audioFile = File("unused.wav"),
            speechDurationMs = 900,
            tailSilenceMs = 100,
            rateMultiplier = 1f,
        )
        assertEquals(1f, TimelinePlacementPolicy.duckGainAt(800.0, listOf(segment)), 0.001f)
        assertEquals(0.56f, TimelinePlacementPolicy.duckGainAt(960.0, listOf(segment)), 0.02f)
        assertEquals(TimelinePlacementPolicy.DUCK_FLOOR, TimelinePlacementPolicy.duckGainAt(1_500.0, listOf(segment)), 0.001f)
        assertEquals(1f, TimelinePlacementPolicy.duckGainAt(2_100.0, listOf(segment)), 0.001f)
    }
}
