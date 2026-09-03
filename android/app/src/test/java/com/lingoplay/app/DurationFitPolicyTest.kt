package com.lingoplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurationFitPolicyTest {
    @Test
    fun acceptsSpeechWithinTolerance() {
        assertTrue(DurationFitPolicy.fits(actualMs = 1_090, targetMs = 1_000))
        assertEquals(0, DurationFitPolicy.tailSilenceMs(actualMs = 1_090, targetMs = 1_000))
    }

    @Test
    fun reservesTailSilenceWhenSpeechIsShorter() {
        assertTrue(DurationFitPolicy.fits(actualMs = 720, targetMs = 1_000))
        assertEquals(280, DurationFitPolicy.tailSilenceMs(actualMs = 720, targetMs = 1_000))
    }

    @Test
    fun increasesRateForOverlongSpeech() {
        val next = DurationFitPolicy.nextRateMultiplier(actualMs = 1_500, targetMs = 1_000, current = 1.0f)
        assertTrue(next != null && next > 1.0f && next <= DurationFitPolicy.MAX_RATE_MULTIPLIER)
    }

    @Test
    fun stopsRetryingAtMaximumSafeRate() {
        assertNull(
            DurationFitPolicy.nextRateMultiplier(
                actualMs = 2_000,
                targetMs = 1_000,
                current = DurationFitPolicy.MAX_RATE_MULTIPLIER,
            ),
        )
    }
}
