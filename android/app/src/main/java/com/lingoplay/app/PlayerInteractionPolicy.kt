package com.lingoplay.app

/** Pure playback interaction rules kept independent from VideoView/MediaPlayer lifecycle. */
object PlayerInteractionPolicy {
    fun shouldApplyPlaybackSpeed(videoReady: Boolean, isPlaying: Boolean): Boolean =
        videoReady && isPlaying

    fun canSeek(videoReady: Boolean): Boolean = videoReady
}
