package com.lingoplay.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerInteractionPolicyTest {
    @Test
    fun speedAppliesOnlyWhenPreparedAndPlaying() {
        assertFalse(PlayerInteractionPolicy.shouldApplyPlaybackSpeed(videoReady = false, isPlaying = false))
        assertFalse(PlayerInteractionPolicy.shouldApplyPlaybackSpeed(videoReady = true, isPlaying = false))
        assertFalse(PlayerInteractionPolicy.shouldApplyPlaybackSpeed(videoReady = false, isPlaying = true))
        assertTrue(PlayerInteractionPolicy.shouldApplyPlaybackSpeed(videoReady = true, isPlaying = true))
    }

    @Test
    fun seekIsBlockedUntilPrepared() {
        assertFalse(PlayerInteractionPolicy.canSeek(videoReady = false))
        assertTrue(PlayerInteractionPolicy.canSeek(videoReady = true))
    }
}
