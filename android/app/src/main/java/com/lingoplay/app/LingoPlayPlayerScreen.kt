package com.lingoplay.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.util.Rational
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun PlayerScreen(
    media: LocalMediaItem?,
    processed: LocalDubMediaResult?,
    translation: TranslationDocument?,
    subtitleMode: SubtitleMode,
    dubbingMode: DubbingModePreset?,
    playbackSpeed: Float,
    onSubtitleMode: () -> Unit,
    onPlaybackSpeed: () -> Unit,
    onEnterPip: () -> Unit,
    onShare: (() -> Unit)?,
    onBack: () -> Unit,
) {
    var pipAvailable by remember(processed?.remuxedVideoFile?.absolutePath) { mutableStateOf(false) }
    ScreenScroll {
        ScreenHeader(media?.name ?: "Preview", onBack) {
            Surface(color = LpViolet.copy(alpha = 0.30f), shape = RoundedCornerShape(50)) {
                Text(
                    if (processed != null) "Vietnamese AI" else "Demo UI",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (processed != null) {
            key(processed.remuxedVideoFile.absolutePath) {
                SingleClockDubPlayer(
                    processed = processed,
                    translation = translation,
                    subtitleMode = subtitleMode,
                    playbackSpeed = playbackSpeed,
                    onPipAvailabilityChange = { pipAvailable = it },
                )
            }
        } else {
            VideoPlaceholder(250.dp)
            LpCard {
                Text("Demo navigation only", fontWeight = FontWeight.Bold)
                Text(
                    "Import and process a local video to use real single-clock dubbed playback.",
                    color = LpSecondaryText,
                    fontSize = 12.sp,
                )
            }
        }

        LpCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Final audio mix", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(dubbingMode?.label ?: "Saved final mix", color = LpCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                dubbingMode?.detail ?: "Generation mode was not recorded by this older Library item.",
                color = LpSecondaryText,
                fontSize = 12.sp,
            )
            Text(
                "Android export contains one final mixed audio track. A fake live blend control is intentionally not shown.",
                color = LpSecondaryText,
                fontSize = 10.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerAction(Icons.Rounded.ClosedCaption, subtitleMode.label, Modifier.weight(1f), onSubtitleMode)
            PlayerAction(Icons.Rounded.Speed, String.format("%.2gx", playbackSpeed), Modifier.weight(1f), onPlaybackSpeed)
            if (processed != null && pipAvailable) {
                PlayerAction(Icons.Rounded.PictureInPictureAlt, "PiP", Modifier.weight(1f), onEnterPip)
            }
            if (onShare != null) {
                PlayerAction(Icons.Rounded.Share, "Share", Modifier.weight(1f), onShare)
            }
        }
    }
}

internal fun processedSupportsPip(processed: LocalDubMediaResult?): Boolean =
    processed?.remuxedVideoFile?.isFile == true && processed.remuxedVideoFile.length() > 0L

@Composable
private fun SingleClockDubPlayer(
    processed: LocalDubMediaResult,
    translation: TranslationDocument?,
    subtitleMode: SubtitleMode,
    playbackSpeed: Float,
    onPipAvailabilityChange: (Boolean) -> Unit,
) {
    var videoView by remember(processed.remuxedVideoFile.absolutePath) { mutableStateOf<VideoView?>(null) }
    var mediaPlayer by remember(processed.remuxedVideoFile.absolutePath) { mutableStateOf<android.media.MediaPlayer?>(null) }
    var videoReady by remember(processed.remuxedVideoFile.absolutePath) { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentMs by remember { mutableIntStateOf(0) }

    LaunchedEffect(playbackSpeed, mediaPlayer, videoReady, isPlaying) {
        val player = mediaPlayer
        if (player != null && PlayerInteractionPolicy.shouldApplyPlaybackSpeed(videoReady, isPlaying)) {
            runCatching { player.playbackParams = player.playbackParams.setSpeed(playbackSpeed) }
        }
    }

    LaunchedEffect(isPlaying, videoView) {
        onPipAvailabilityChange(videoReady && isPlaying)
        while (isPlaying) {
            currentMs = videoView?.currentPosition?.coerceAtLeast(0) ?: currentMs
            delay(250)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    setOnPreparedListener { player ->
                        mediaPlayer = player
                        videoReady = true
                        onPipAvailabilityChange(false)
                    }
                    setOnCompletionListener {
                        isPlaying = false
                        onPipAvailabilityChange(false)
                        currentMs = processed.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    }
                    setVideoPath(processed.remuxedVideoFile.absolutePath)
                }
            },
            onReset = null,
            onRelease = { releasedView ->
                releasedView.setOnPreparedListener(null)
                releasedView.setOnCompletionListener(null)
                releasedView.stopPlayback()
                videoView = null
                mediaPlayer = null
                videoReady = false
                isPlaying = false
                onPipAvailabilityChange(false)
            },
            update = { view -> videoView = view },
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.58f))
                .padding(horizontal = 18.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Replay10,
                contentDescription = "Back 10 seconds",
                modifier = Modifier.clickable(enabled = PlayerInteractionPolicy.canSeek(videoReady)) {
                    val target = (currentMs - 10_000).coerceAtLeast(0)
                    videoView?.seekTo(target)
                    currentMs = target
                },
            )
            Icon(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier
                    .size(30.dp)
                    .clickable(enabled = videoReady) {
                        val view = videoView ?: return@clickable
                        if (isPlaying) {
                            view.pause()
                            isPlaying = false
                            onPipAvailabilityChange(false)
                        } else {
                            view.start()
                            isPlaying = true
                            mediaPlayer?.let { player ->
                                runCatching { player.playbackParams = player.playbackParams.setSpeed(playbackSpeed) }
                            }
                            onPipAvailabilityChange(true)
                        }
                    },
            )
            Icon(
                Icons.Rounded.Forward10,
                contentDescription = "Forward 10 seconds",
                modifier = Modifier.clickable(enabled = PlayerInteractionPolicy.canSeek(videoReady)) {
                    val duration = processed.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val target = (currentMs + 10_000).coerceAtMost(duration)
                    videoView?.seekTo(target)
                    currentMs = target
                },
            )
        }
    }

    val durationMs = processed.durationMs.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Slider(
            value = currentMs.coerceIn(0, durationMs).toFloat(),
            onValueChange = { value ->
                val target = value.toInt().coerceIn(0, durationMs)
                currentMs = target
                videoView?.seekTo(target)
            },
            valueRange = 0f..durationMs.toFloat(),
            enabled = PlayerInteractionPolicy.canSeek(videoReady),
        )
        Row {
            Text(MediaFormatting.duration(currentMs.toLong()), color = LpSecondaryText, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(MediaFormatting.duration(durationMs.toLong()), color = LpSecondaryText, fontSize = 10.sp)
        }
    }

    val activeSegment = translation?.segments?.lastOrNull { currentMs >= it.startMs && currentMs <= it.endMs }
    when (subtitleMode) {
        SubtitleMode.OFF -> Unit
        SubtitleMode.TRANSLATED -> LpCard {
            SubtitleRow(translation?.targetLanguage?.uppercase() ?: "TR", activeSegment?.displayText ?: "—")
            if (translation?.mode == TranslationMode.OFFLINE) {
                Text("Powered by Google Translate", color = LpSecondaryText, fontSize = 9.sp)
            }
        }
        SubtitleMode.BILINGUAL -> LpCard {
            SubtitleRow(translation?.sourceLanguage?.uppercase() ?: "SRC", activeSegment?.sourceText ?: "—")
            CardDivider()
            SubtitleRow(translation?.targetLanguage?.uppercase() ?: "TR", activeSegment?.displayText ?: "—")
            if (translation?.mode == TranslationMode.OFFLINE) {
                Text("Powered by Google Translate", color = LpSecondaryText, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun SubtitleRow(language: String, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(language, color = LpCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
        Text(text, modifier = Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun PlayerAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    action: (() -> Unit)? = null,
) {
    val interactive = if (action != null) modifier.clickable(onClick = action) else modifier
    Surface(modifier = interactive, color = LpSurface, shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = label, tint = LpCyan, modifier = Modifier.size(20.dp))
            Text(label, fontSize = 9.sp, maxLines = 1)
        }
    }
}
