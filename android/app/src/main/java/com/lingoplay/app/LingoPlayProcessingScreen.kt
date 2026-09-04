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
import androidx.compose.runtime.DisposableEffect
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
internal fun ProcessingScreen(
    mediaState: MediaPreparationState,
    mediaError: String?,
    asrPhase: ASRPhase,
    transcript: ASRTranscript?,
    asrError: String?,
    translationPhase: TranslationPhase,
    translation: TranslationDocument?,
    translationError: String?,
    translationBatch: Int,
    translationBatchTotal: Int,
    ttsPhase: TTSPhase,
    dubSpeech: DubSpeechDocument?,
    ttsError: String?,
    ttsSegment: Int,
    ttsSegmentTotal: Int,
    mixPhase: MixPhase,
    mixResult: LocalDubMediaResult?,
    mixError: String?,
    modelInstallState: ModelInstallState,
    onInstallModel: () -> Unit,
    onCancelModel: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenScroll {
        ScreenHeader("AI Processing", onBack)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BrandMark(size = 92.dp)
        }
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val progress = when {
                mixPhase == MixPhase.COMPLETED -> 1.00f
                mixPhase == MixPhase.REMUXING -> 0.92f
                mixPhase == MixPhase.RENDERING_AUDIO -> 0.85f
                ttsPhase == TTSPhase.COMPLETED -> 0.80f
                ttsPhase == TTSPhase.SYNTHESIZING && ttsSegmentTotal > 0 -> 0.60f + (0.20f * ttsSegment.toFloat() / ttsSegmentTotal.toFloat())
                translationPhase == TranslationPhase.COMPLETED -> 0.60f
                translationPhase == TranslationPhase.TRANSLATING && translationBatchTotal > 0 -> 0.40f + (0.20f * translationBatch.toFloat() / translationBatchTotal.toFloat())
                asrPhase == ASRPhase.COMPLETED -> 0.40f
                mediaState == MediaPreparationState.AUDIO_READY -> 0.20f
                else -> 0.05f
            }
            Text(processingTitle(mediaState, asrPhase, translationPhase, ttsPhase, mixPhase), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("${(progress * 100).toInt()}%", color = LpCyan, fontSize = 42.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = LpCyan,
                trackColor = LpSurfaceStrong,
            )
        }

        LpCard {
            ProcessingRow("Preparing audio", audioProcessState(mediaState))
            CardDivider()
            ProcessingRow("Understanding speech", speechProcessState(asrPhase))
            CardDivider()
            ProcessingRow("Translating", translationProcessState(translationPhase))
            CardDivider()
            ProcessingRow("Creating offline voice", ttsProcessState(ttsPhase))
            CardDivider()
            ProcessingRow("Mixing audio", mixProcessState(mixPhase))
        }

        if (transcript != null && asrPhase == ASRPhase.COMPLETED) {
            LpCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Speech recognized", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(transcript.language.uppercase(), color = LpCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text(transcript.text, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Text("${transcript.segments.size} timestamped audio-range segment · local only", color = LpSecondaryText, fontSize = 10.sp)
            }
        }

        if (translation != null && translationPhase == TranslationPhase.COMPLETED) {
            LpCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Translation", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${translation.segments.size} segments", color = LpCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text(translation.translatedText, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Text(
                    if (translation.mode == TranslationMode.OFFLINE) {
                        "Powered by Google Translate · transcript stayed on-device"
                    } else {
                        "Only transcript JSON was sent · source media stayed on-device"
                    },
                    color = LpSecondaryText,
                    fontSize = 10.sp,
                )
            }
        }

        if (asrPhase == ASRPhase.MODEL_MISSING) {
            LpCard {
                Text("Speech AI model required", fontWeight = FontWeight.Bold)
                Text(
                    "Install Whisper Tiny once to continue local speech recognition. The model download is separate from your video; video and audio never leave this device.",
                    color = LpSecondaryText,
                    fontSize = 12.sp,
                )
                when (modelInstallState) {
                    is ModelInstallState.Downloading -> {
                        val progress = modelInstallState.progress
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = LpCyan,
                            trackColor = LpSurfaceStrong,
                        )
                        Text(
                            "${(progress * 100).toInt()}% · ${MediaFormatting.bytes(modelInstallState.bytesDone)} / ${MediaFormatting.bytes(modelInstallState.bytesTotal)}",
                            color = LpSecondaryText,
                            fontSize = 11.sp,
                        )
                        TextButton(onClick = onCancelModel) { Text("Cancel download") }
                    }
                    is ModelInstallState.Installed -> Text("Speech AI installed. Processing is resuming…", color = LpCyan, fontSize = 12.sp)
                    is ModelInstallState.Failed -> {
                        Text(modelInstallState.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        PrimaryAction("Retry Speech AI download", Icons.Rounded.Download, onInstallModel)
                    }
                    ModelInstallState.NotInstalled -> PrimaryAction("Install Speech AI · ~104 MB", Icons.Rounded.Download, onInstallModel)
                }
            }
        }

        if (translationPhase == TranslationPhase.ENDPOINT_MISSING) {
            LpCard {
                Text("Translation backend is not configured. The recognized transcript remains local and no network request was made.", color = LpSecondaryText, fontSize = 12.sp)
            }
        }

        if (dubSpeech != null && ttsPhase == TTSPhase.COMPLETED) {
            LpCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Offline voice ready", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${dubSpeech.segments.size} clips", color = LpCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text("Offline voice · ${dubSpeech.voiceName}", fontSize = 12.sp)
                Text("${dubSpeech.totalTailSilenceMs} ms timeline silence reserved · no spoken words truncated", color = LpSecondaryText, fontSize = 10.sp)
            }
        }

        if (ttsPhase == TTSPhase.VOICE_MISSING) {
            LpCard {
                Text("No offline system voice is installed for the selected target language. LingoPlay will not use a network-required TTS voice for this local dubbing path.", color = LpSecondaryText, fontSize = 12.sp)
            }
        }

        if (mixResult != null && mixPhase == MixPhase.COMPLETED) {
            LpCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Dubbed video ready", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("LOCAL", color = LpCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text("Video stream was remuxed without video transcoding.", fontSize = 12.sp)
                Text("Original audio and generated speech were mixed locally using the selected dubbing mode.", color = LpSecondaryText, fontSize = 10.sp)
            }
        }

        LpCard {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = LpCyan)
                Text("If Android suspends or terminates LingoPlay, a local recovery checkpoint can be resumed from Home. PiP is for playback; processing is not falsely promised as unlimited background execution.", color = LpSecondaryText, fontSize = 13.sp)
            }
        }

        mediaError?.let { error ->
            LpCard { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
        asrError?.let { error ->
            LpCard { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
        translationError?.let { error ->
            LpCard { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
        ttsError?.let { error ->
            LpCard { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
        mixError?.let { error ->
            LpCard { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
    }
}

private fun processingTitle(mediaState: MediaPreparationState, asrPhase: ASRPhase, translationPhase: TranslationPhase, ttsPhase: TTSPhase, mixPhase: MixPhase): String = when (mediaState) {
    MediaPreparationState.EXTRACTING_AUDIO -> "Preparing local audio"
    MediaPreparationState.AUDIO_READY -> when (asrPhase) {
        ASRPhase.MODEL_MISSING -> "Audio ready · speech model not installed"
        ASRPhase.LOADING_MODEL -> "Loading local speech model"
        ASRPhase.TRANSCRIBING -> "Understanding speech on-device"
        ASRPhase.COMPLETED -> when (translationPhase) {
            TranslationPhase.TRANSLATING -> "Translating transcript"
            TranslationPhase.COMPLETED -> when (ttsPhase) {
                TTSPhase.SYNTHESIZING -> "Creating offline voice on-device"
                TTSPhase.COMPLETED -> when (mixPhase) {
                    MixPhase.RENDERING_AUDIO -> "Building local dub timeline"
                    MixPhase.REMUXING -> "Remuxing dubbed video"
                    MixPhase.COMPLETED -> "Dubbed video ready"
                    MixPhase.FAILED -> "Local mix or remux stopped"
                    MixPhase.IDLE -> "Offline voice ready"
                }
                TTSPhase.VOICE_MISSING -> "Translation ready · offline voice not installed"
                TTSPhase.FAILED -> "Offline voice synthesis stopped"
                TTSPhase.IDLE -> "Translation ready"
            }
            TranslationPhase.ENDPOINT_MISSING -> "Speech ready · translation not configured"
            TranslationPhase.FAILED -> "Translation stopped"
            else -> "Speech recognized locally"
        }
        ASRPhase.FAILED -> "Speech recognition stopped"
        ASRPhase.IDLE -> "Audio ready"
    }
    MediaPreparationState.FAILED -> "Audio preparation stopped"
    else -> "Preparing local media"
}

private fun audioProcessState(state: MediaPreparationState): ProcessState = when (state) {
    MediaPreparationState.AUDIO_READY -> ProcessState.COMPLETE
    MediaPreparationState.EXTRACTING_AUDIO -> ProcessState.ACTIVE
    MediaPreparationState.FAILED -> ProcessState.FAILED
    else -> ProcessState.PENDING
}

private fun speechProcessState(state: ASRPhase): ProcessState = when (state) {
    ASRPhase.MODEL_MISSING -> ProcessState.BLOCKED
    ASRPhase.LOADING_MODEL, ASRPhase.TRANSCRIBING -> ProcessState.ACTIVE
    ASRPhase.COMPLETED -> ProcessState.COMPLETE
    ASRPhase.FAILED -> ProcessState.FAILED
    ASRPhase.IDLE -> ProcessState.PENDING
}

private fun translationProcessState(state: TranslationPhase): ProcessState = when (state) {
    TranslationPhase.TRANSLATING -> ProcessState.ACTIVE
    TranslationPhase.COMPLETED -> ProcessState.COMPLETE
    TranslationPhase.ENDPOINT_MISSING -> ProcessState.CONFIGURATION_MISSING
    TranslationPhase.FAILED -> ProcessState.FAILED
    TranslationPhase.IDLE -> ProcessState.PENDING
}

private fun ttsProcessState(state: TTSPhase): ProcessState = when (state) {
    TTSPhase.SYNTHESIZING -> ProcessState.ACTIVE
    TTSPhase.COMPLETED -> ProcessState.COMPLETE
    TTSPhase.VOICE_MISSING -> ProcessState.VOICE_MISSING
    TTSPhase.FAILED -> ProcessState.FAILED
    TTSPhase.IDLE -> ProcessState.PENDING
}

private fun mixProcessState(state: MixPhase): ProcessState = when (state) {
    MixPhase.RENDERING_AUDIO, MixPhase.REMUXING -> ProcessState.ACTIVE
    MixPhase.COMPLETED -> ProcessState.COMPLETE
    MixPhase.FAILED -> ProcessState.FAILED
    MixPhase.IDLE -> ProcessState.PENDING
}

private enum class ProcessState { COMPLETE, ACTIVE, PENDING, BLOCKED, CONFIGURATION_MISSING, VOICE_MISSING, FAILED }

@Composable
private fun ProcessingRow(title: String, state: ProcessState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            ProcessState.COMPLETE -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = LpCyan, modifier = Modifier.size(22.dp))
            ProcessState.ACTIVE -> Box(modifier = Modifier.size(22.dp).border(2.dp, LpCyan, CircleShape))
            ProcessState.PENDING -> Icon(Icons.Rounded.RadioButtonUnchecked, contentDescription = null, tint = LpSecondaryText, modifier = Modifier.size(22.dp))
            ProcessState.BLOCKED, ProcessState.CONFIGURATION_MISSING, ProcessState.VOICE_MISSING -> Icon(Icons.Rounded.Lock, contentDescription = null, tint = LpSecondaryText, modifier = Modifier.size(22.dp))
            ProcessState.FAILED -> Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            when (state) {
                ProcessState.COMPLETE -> "Completed"
                ProcessState.ACTIVE -> "In progress"
                ProcessState.PENDING -> "Pending"
                ProcessState.BLOCKED -> "ASR not installed"
                ProcessState.CONFIGURATION_MISSING -> "Not configured"
                ProcessState.VOICE_MISSING -> "Voice not installed"
                ProcessState.FAILED -> "Failed"
            },
            color = LpSecondaryText,
            fontSize = 11.sp,
        )
    }
}
