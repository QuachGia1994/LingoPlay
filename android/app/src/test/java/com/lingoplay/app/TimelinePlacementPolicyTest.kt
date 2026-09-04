package com.lingoplay.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun normalizesQuietSpeechWithoutCrossingPeakCeiling() {
        val quiet = ShortArray(4_800) { index -> if (index % 2 == 0) 1_200 else -1_200 }
        val normalized = AudioQualityPolicy.normalizeSpeech(quiet)
        val peak = normalized.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(peak > 1_200)
        assertTrue(peak <= (Short.MAX_VALUE * 0.93).toInt())
    }

    @Test
    fun lowNoiseFloorIsNotAmplifiedBySpeechNormalizer() {
        val noise = ShortArray(4_800) { index -> if (index % 2 == 0) 80 else -80 }
        val normalized = AudioQualityPolicy.normalizeSpeech(noise)
        assertEquals(80, normalized.maxOf { kotlin.math.abs(it.toInt()) })
    }

    @Test
    fun softLimiterPreservesNormalSamplesAndBoundsOverload() {
        assertEquals(12_000.toShort(), AudioQualityPolicy.softLimitPcm16(12_000))
        val overloaded = AudioQualityPolicy.softLimitPcm16(65_000).toInt()
        assertTrue(overloaded in 29_000..Short.MAX_VALUE.toInt())
        assertTrue(AudioQualityPolicy.softLimitPcm16(-65_000).toInt() < 0)
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
        assertEquals(0.44f, TimelinePlacementPolicy.duckGainAt(960.0, listOf(segment)), 0.02f)
        assertEquals(DubbingModePreset.BALANCED.duckFloor, TimelinePlacementPolicy.duckGainAt(1_500.0, listOf(segment)), 0.001f)
        assertEquals(1f, TimelinePlacementPolicy.duckGainAt(2_200.0, listOf(segment)), 0.001f)
    }

    @Test
    fun dubbingModesProduceDistinctRealDuckPolicies() {
        val segment = DubSpeechSegment(
            id = "s1",
            startMs = 1_000,
            endMs = 2_000,
            audioFile = File("unused.wav"),
            speechDurationMs = 900,
            tailSilenceMs = 100,
            rateMultiplier = 1f,
        )
        val speechFocus = TimelinePlacementPolicy.duckGainAt(1_500.0, listOf(segment), DubbingModePreset.SPEECH_FOCUS)
        val balanced = TimelinePlacementPolicy.duckGainAt(1_500.0, listOf(segment), DubbingModePreset.BALANCED)
        val originalFocus = TimelinePlacementPolicy.duckGainAt(1_500.0, listOf(segment), DubbingModePreset.ORIGINAL_FOCUS)
        assertTrue(speechFocus < balanced)
        assertTrue(balanced < originalFocus)
    }
}
