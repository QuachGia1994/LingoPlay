package com.lingoplay.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlayerLifecycleDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var firstVideo: File
    private lateinit var secondVideo: File
    private val visible = mutableStateOf(true)
    private val selected = mutableStateOf<LocalDubMediaResult?>(null)
    private val speed = mutableStateOf(1f)

    @Before
    fun showSavedPlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        firstVideo = File.createTempFile("player-first-", ".mp4", instrumentation.targetContext.cacheDir)
        secondVideo = File.createTempFile("player-next-", ".mp4", instrumentation.targetContext.cacheDir)
        for (destination in listOf(firstVideo, secondVideo)) {
            instrumentation.context.assets.open("stage6-source.mp4").use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
        selected.value = LocalDubMediaResult(firstVideo, 3_000L)
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    PlayerScreen(
                        media = null,
                        processed = selected.value,
                        translation = null,
                        subtitleMode = SubtitleMode.OFF,
                        dubbingMode = null,
                        playbackSpeed = speed.value,
                        onSubtitleMode = {},
                        onPlaybackSpeed = {},
                        onEnterPip = {},
                        onShare = null,
                        onBack = { visible.value = false },
                    )
                }
            }
        }
    }

    @After
    fun disposePlayer() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        firstVideo.delete()
        secondVideo.delete()
    }

    @Test
    fun firstOpenAndReentryPlayWithoutRetry() {
        repeat(3) {
            playOnceAndAwaitPosition()
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { visible.value = true }
        }
    }

    @Test
    fun speedRecompositionAndMediaReplacementKeepPlayerUsable() {
        playOnceAndAwaitPosition()
        compose.runOnIdle { speed.value = 1.25f }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("00:02").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { selected.value = LocalDubMediaResult(secondVideo, 3_000L) }
        playOnceAndAwaitPosition()
    }

    private fun playOnceAndAwaitPosition() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().any {
                !it.config.contains(SemanticsProperties.Disabled)
            }
        }
        compose.onNodeWithContentDescription("Play").assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("00:01").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
