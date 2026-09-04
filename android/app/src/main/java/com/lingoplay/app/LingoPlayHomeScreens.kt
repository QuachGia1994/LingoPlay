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
internal fun SplashScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        BrandMark(size = 96.dp)
        Spacer(Modifier.height(22.dp))
        Text("LingoPlay", color = LpCyan, fontSize = 38.sp, fontWeight = FontWeight.Bold)
        Text("AI video translation & dubbing", color = LpSecondaryText, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        LinearProgressIndicator(modifier = Modifier.width(180.dp), color = LpCyan, trackColor = LpSurfaceStrong)
        Spacer(Modifier.height(12.dp))
        Text("Preparing LingoPlay…", color = LpSecondaryText, fontSize = 12.sp)
        Spacer(Modifier.height(56.dp))
    }
}

@Composable
internal fun HomeScreen(
    language: UiLanguage,
    items: List<LocalLibraryItem>,
    recovery: ProcessingCheckpoint?,
    onPlus: () -> Unit,
    onImport: () -> Unit,
    onResumeRecovery: (ProcessingCheckpoint) -> Unit,
    onDiscardRecovery: () -> Unit,
    onOpenItem: (LocalLibraryItem) -> Unit,
) {
    ScreenScroll {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("LingoPlay", color = LpCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(language.text("Understand without borders", "Hiểu mọi nội dung, không rào cản"), color = LpSecondaryText, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Surface(modifier = Modifier.clickable(onClick = onPlus), color = LpSurface, shape = CircleShape) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.WorkspacePremium, contentDescription = "LingoPlay Plus", tint = LpViolet, modifier = Modifier.size(21.dp))
                }
            }
        }

        LpCard {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(language.text("AI video translation\n& dubbing", "Dịch video AI\n& lồng tiếng"), fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                    Text(language.text("Import a video you already have. The media stays on this device.", "Chọn video có sẵn trong thư viện. Media luôn nằm trên thiết bị này."), color = LpSecondaryText, fontSize = 14.sp)
                }
                Spacer(Modifier.width(12.dp))
                BrandMark()
            }
            PrimaryAction(language.text("Import Video", "Chọn video"), Icons.Rounded.Add, onImport)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = LpCyan, modifier = Modifier.size(16.dp))
                Text(language.text("Video and audio are never uploaded", "Video và audio không bao giờ được tải lên server"), color = LpSecondaryText, fontSize = 12.sp)
            }
        }

        if (recovery != null) {
            LpCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = LpCyan)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(language.text("Interrupted dub", "Bản lồng tiếng bị gián đoạn"), fontWeight = FontWeight.Bold)
                        Text(recovery.media.name, color = LpSecondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(language.text("Resume from the last durable local boundary. Media stays in LingoPlay storage.", "Tiếp tục từ mốc cục bộ an toàn gần nhất. Media vẫn nằm trong bộ nhớ LingoPlay."), color = LpSecondaryText, fontSize = 12.sp)
                PrimaryAction(language.text("Resume processing", "Tiếp tục xử lý"), Icons.Rounded.PlayArrow) { onResumeRecovery(recovery) }
                TextButton(onClick = onDiscardRecovery) { Text(language.text("Discard", "Bỏ phiên")) }
            }
        }

        SectionHeader(language.text("Recent", "Gần đây"), language.text("Local library", "Thư viện cục bộ"))
        if (items.isEmpty()) {
            LpCard {
                Text(language.text("No dubbed videos yet", "Chưa có video lồng tiếng"), fontWeight = FontWeight.Bold)
                Text(language.text("Import and process a local video. Completed results are saved privately on this device.", "Chọn và xử lý một video. Kết quả hoàn tất sẽ được lưu riêng trên thiết bị."), color = LpSecondaryText, fontSize = 12.sp)
            }
        } else {
            items.take(3).forEach { item ->
                LibraryItemRow(item = item, onOpen = { onOpenItem(item) })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CapabilityCard(Icons.Rounded.Mic, language.text("On-device", "Trên máy"), language.text("Speech AI", "AI giọng nói"), Modifier.weight(1f))
            CapabilityCard(Icons.Rounded.Subtitles, language.text("Bilingual", "Song ngữ"), language.text("Subtitles", "Phụ đề"), Modifier.weight(1f))
            CapabilityCard(Icons.Rounded.Download, "Offline", language.text("Playback", "Phát lại"), Modifier.weight(1f))
        }
    }
}

@Composable
internal fun PrepareScreen(
    media: LocalMediaItem?,
    sourceLanguage: SourceLanguageChoice,
    targetLanguage: TargetLanguageChoice,
    translationMode: TranslationMode,
    voiceLabel: String,
    dubbingMode: DubbingModePreset,
    subtitleMode: SubtitleMode,
    cleanBackgroundAvailable: Boolean,
    onSourceLanguage: () -> Unit,
    onTargetLanguage: () -> Unit,
    onTranslationMode: () -> Unit,
    onVoice: () -> Unit,
    onDubbingMode: () -> Unit,
    onSubtitleMode: () -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onTranslate: () -> Unit,
) {
    ScreenScroll {
        ScreenHeader("Prepare", onBack)
        LpCard {
            VideoPlaceholder(190.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(media?.name ?: "Choose a local video", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val audio = if (media?.hasAudioTrack == true) "Audio detected" else "No audio track"
                    Text(media?.let { "${it.durationText}  •  ${it.fileSizeText}  •  $audio" } ?: "Local file required", color = LpSecondaryText, fontSize = 12.sp)
                }
                Surface(modifier = Modifier.clickable(onClick = onEdit), color = LpSurfaceStrong, shape = RoundedCornerShape(50)) {
                    Text("Edit", modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        LpCard {
            PrepareRow(Icons.Rounded.Mic, "From language", sourceLanguage.label, "Whisper language override", onSourceLanguage)
            CardDivider()
            PrepareRow(Icons.Rounded.Language, "To language", targetLanguage.label, "Translation + offline TTS target", onTargetLanguage)
            CardDivider()
            PrepareRow(Icons.Rounded.Translate, "Translation mode", translationMode.label, translationMode.detail, onTranslationMode)
            CardDivider()
            PrepareRow(Icons.Rounded.Person, "AI Voice", voiceLabel, "Installed offline voice", onVoice)
            CardDivider()
            PrepareRow(Icons.Rounded.Tune, "Dubbing mode", dubbingMode.label, dubbingMode.detail, onDubbingMode)
            CardDivider()
            PrepareRow(
                Icons.Rounded.AutoAwesome,
                "Clean Background",
                if (cleanBackgroundAvailable) "Ready" else "Unavailable",
                if (cleanBackgroundAvailable) "Verified source-separation engine installed" else "No verified source-separation engine is bundled yet",
            )
            CardDivider()
            PrepareRow(Icons.Rounded.Subtitles, "Subtitles", subtitleMode.label, "Player subtitle display mode", onSubtitleMode)
        }

        PrimaryAction("Translate & Dub", Icons.Rounded.AutoAwesome, onTranslate)
        Text("Estimated time depends on device performance and video duration.", modifier = Modifier.fillMaxWidth(), color = LpSecondaryText, fontSize = 12.sp)
    }
}

@Composable
internal fun LibraryScreen(
    language: UiLanguage,
    items: List<LocalLibraryItem>,
    onOpenItem: (LocalLibraryItem) -> Unit,
    onShare: (LocalLibraryItem) -> Unit,
    onDelete: (LocalLibraryItem) -> Unit,
) {
    ScreenScroll {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(language.text("Library", "Thư viện"), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(language.text("Saved dubs · always available offline", "Video đã lưu · luôn xem được khi offline"), color = LpSecondaryText, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.VideoLibrary, contentDescription = null, tint = LpCyan)
        }

        if (items.isEmpty()) {
            LpCard {
                Text(language.text("Nothing saved yet", "Chưa có video đã lưu"), fontWeight = FontWeight.Bold)
                Text(language.text("Completed dubs appear here automatically after local processing finishes.", "Video lồng tiếng hoàn tất sẽ tự động xuất hiện ở đây sau khi xử lý cục bộ."), color = LpSecondaryText, fontSize = 12.sp)
            }
        } else {
            items.forEach { item ->
                LibraryItemRow(
                    item = item,
                    onOpen = { onOpenItem(item) },
                    onShare = { onShare(item) },
                    onDelete = { onDelete(item) },
                )
            }
        }

        val totalBytes = LocalLibraryStore.totalBytes(items)
        LpCard {
            SectionHeader(language.text("Saved media", "Media đã lưu"), "${MediaFormatting.bytes(totalBytes)}")
            Text(language.text("${items.size} local dubbed video${if (items.size == 1) "" else "s"} · stored only in LingoPlay app storage", "${items.size} video lồng tiếng cục bộ · chỉ lưu trong bộ nhớ ứng dụng LingoPlay"), color = LpSecondaryText, fontSize = 12.sp)
        }
    }
}
