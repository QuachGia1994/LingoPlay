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
internal fun SettingsScreen(
    language: UiLanguage,
    highContrast: Boolean,
    wifiOnly: Boolean,
    subtitleMode: SubtitleMode,
    targetLanguage: TargetLanguageChoice,
    translationMode: TranslationMode,
    speakerMode: SpeakerMode,
    voiceCloningEnabled: Boolean,
    cleanBackgroundEnabled: Boolean,
    voiceLabel: String,
    isPlus: Boolean,
    modelInstallState: ModelInstallState,
    canDeleteModel: Boolean,
    neuralVoiceInstallState: ModelInstallState,
    canDeleteNeuralVoice: Boolean,
    speakerModelInstallState: ModelInstallState,
    canDeleteSpeakerModel: Boolean,
    cloningModelInstallState: ModelInstallState,
    canDeleteCloningModel: Boolean,
    sourceSeparationModelInstallState: ModelInstallState,
    canDeleteSourceSeparationModel: Boolean,
    downloadedTranslationModelCodes: Set<String>,
    translationModelBusyCode: String?,
    translationModelError: String?,
    canManageTranslationModels: Boolean,
    onInstallModel: () -> Unit,
    onCancelModel: () -> Unit,
    onDeleteModel: () -> Unit,
    onInstallNeuralVoice: () -> Unit,
    onCancelNeuralVoice: () -> Unit,
    onDeleteNeuralVoice: () -> Unit,
    onInstallSpeakerModel: () -> Unit,
    onCancelSpeakerModel: () -> Unit,
    onDeleteSpeakerModel: () -> Unit,
    onInstallCloningModel: () -> Unit,
    onCancelCloningModel: () -> Unit,
    onDeleteCloningModel: () -> Unit,
    onInstallSourceSeparationModel: () -> Unit,
    onCancelSourceSeparationModel: () -> Unit,
    onDeleteSourceSeparationModel: () -> Unit,
    onToggleTranslationModel: (String) -> Unit,
    onToggleAppearance: () -> Unit,
    onToggleLanguage: () -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onSubtitleMode: () -> Unit,
    onTargetLanguage: () -> Unit,
    onTranslationMode: () -> Unit,
    onSpeakerMode: () -> Unit,
    onVoiceCloningEnabled: (Boolean) -> Unit,
    onCleanBackgroundEnabled: (Boolean) -> Unit,
    onVoice: () -> Unit,
    onPlus: () -> Unit,
    onAbout: () -> Unit,
) {
    ScreenScroll {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(language.text("Settings", "Cài đặt"), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(language.text("Playback, appearance and privacy preferences", "Tùy chọn phát lại, giao diện và riêng tư"), color = LpSecondaryText, fontSize = 12.sp)
            }
            BrandMark(size = 50.dp)
        }

        LpCard {
            SettingsValueRow(
                Icons.Rounded.Tune,
                language.text("Appearance", "Giao diện"),
                if (highContrast) language.text("High Contrast", "Tương phản cao") else "Midnight",
                onToggleAppearance,
            )
            CardDivider()
            SettingsValueRow(
                Icons.Rounded.Language,
                language.text("App Language", "Ngôn ngữ ứng dụng"),
                if (language == UiLanguage.VIETNAMESE) "Tiếng Việt" else "English",
                onToggleLanguage,
            )
            CardDivider()
            SettingsValueRow(Icons.Rounded.Person, language.text("AI Voice", "Giọng AI"), voiceLabel, onVoice)
            CardDivider()
            SettingsValueRow(Icons.Rounded.Language, language.text("Dubbing Language", "Ngôn ngữ lồng tiếng"), targetLanguage.label, onTargetLanguage)
            CardDivider()
            SettingsValueRow(Icons.Rounded.Translate, language.text("Translation mode", "Chế độ dịch"), translationMode.label, onTranslationMode)
            CardDivider()
            SettingsValueRow(Icons.Rounded.Person, language.text("Speaker mode", "Chế độ người nói"), speakerMode.label, onSpeakerMode)
            CardDivider()
            ToggleRow(
                Icons.Rounded.Shield,
                language.text("Allow local voice cloning", "Cho phép clone giọng cục bộ"),
                voiceCloningEnabled,
                onVoiceCloningEnabled,
            )
            Text(
                language.text(
                    "Consent gate only. Cloning is local, requires a verified model and matching reference speech; never use it to impersonate someone without permission.",
                    "Chỉ là cổng đồng ý. Clone chạy cục bộ, cần model đã xác minh và mẫu giọng khớp; không dùng để giả mạo người khác khi chưa được phép.",
                ),
                color = LpSecondaryText,
                fontSize = 10.sp,
            )
            CardDivider()
            ToggleRow(
                Icons.Rounded.AutoAwesome,
                language.text("Clean Background", "Tách nền sạch"),
                cleanBackgroundEnabled,
                onCleanBackgroundEnabled,
            )
            Text(
                language.text(
                    "When enabled, local source separation removes dialogue from the background stem before mixing the translated voice. A verified model must be installed.",
                    "Khi bật, tách nguồn cục bộ loại lời thoại khỏi stem nền trước khi trộn giọng dịch. Cần cài model đã xác minh.",
                ),
                color = LpSecondaryText,
                fontSize = 10.sp,
            )
            CardDivider()
            ToggleRow(Icons.Rounded.Wifi, language.text("Download models on Wi-Fi only", "Chỉ tải model bằng Wi-Fi"), wifiOnly, onWifiOnlyChange)
            CardDivider()
            SettingsValueRow(Icons.Rounded.Subtitles, language.text("Subtitles", "Phụ đề"), subtitleMode.label, onSubtitleMode)
        }

        LpCard {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = LpCyan)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(language.text("Private by architecture", "Riêng tư ngay từ kiến trúc"), fontWeight = FontWeight.Bold)
                    Text(language.text("Cloud mode sends transcript JSON only to LingoPlay. Offline mode keeps transcript text on-device; ML Kit may contact Google for model downloads, updates, and performance/utilization metrics.", "Chế độ Cloud chỉ gửi transcript JSON tới LingoPlay. Chế độ Offline giữ transcript trên thiết bị; ML Kit có thể kết nối Google để tải/cập nhật model và gửi chỉ số hiệu năng/mức sử dụng."), color = LpSecondaryText, fontSize = 13.sp)
                }
            }
        }

        LpCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Translate, contentDescription = null, tint = LpCyan, modifier = Modifier.size(21.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(language.text("Offline Translation Models", "Model dịch Offline"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(language.text("Explicit install/delete · about 30 MB per language", "Cài/xóa thủ công · khoảng 30 MB mỗi ngôn ngữ"), color = LpSecondaryText, fontSize = 11.sp)
                }
            }
            OfflineTranslationLanguagePolicy.supportedCodes.forEach { code ->
                CardDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(OfflineTranslationLanguagePolicy.displayName(code), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                code == "en" -> language.text("Built in", "Tích hợp sẵn")
                                translationModelBusyCode == code -> language.text("Working…", "Đang xử lý…")
                                code in downloadedTranslationModelCodes -> language.text("Installed", "Đã cài")
                                else -> language.text("Not installed", "Chưa cài")
                            },
                            color = LpSecondaryText,
                            fontSize = 10.sp,
                        )
                    }
                    if (code != "en") {
                        TextButton(
                            enabled = canManageTranslationModels && translationModelBusyCode == null,
                            onClick = { onToggleTranslationModel(code) },
                        ) {
                            Text(if (code in downloadedTranslationModelCodes) language.text("Delete", "Xóa") else language.text("Install", "Cài"))
                        }
                    }
                }
            }
            translationModelError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
            Text("Powered by Google Translate · ML Kit", color = LpSecondaryText, fontSize = 10.sp)
            Text(language.text("Missing models stop the job; LingoPlay never switches to cloud automatically.", "Thiếu model sẽ dừng tác vụ; LingoPlay không tự chuyển sang cloud."), color = LpSecondaryText, fontSize = 10.sp)
        }

        LpCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Storage, contentDescription = null, tint = LpCyan, modifier = Modifier.size(21.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(language.text("Speech AI Model", "Model AI nhận dạng giọng nói"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (modelInstallState) {
                            is ModelInstallState.Installed -> language.text("Whisper Tiny · ${MediaFormatting.bytes(modelInstallState.bytes)} · offline", "Whisper Tiny · ${MediaFormatting.bytes(modelInstallState.bytes)} · offline")
                            is ModelInstallState.Downloading -> language.text("Downloading…", "Đang tải…")
                            is ModelInstallState.Failed -> language.text("Install failed", "Cài đặt thất bại")
                            ModelInstallState.NotInstalled -> language.text("Not installed", "Chưa cài")
                        },
                        color = LpSecondaryText,
                        fontSize = 11.sp,
                    )
                }
            }
            when (modelInstallState) {
                is ModelInstallState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { modelInstallState.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = LpCyan,
                        trackColor = LpSurfaceStrong,
                    )
                    Text(
                        "${(modelInstallState.progress * 100).toInt()}% · ${MediaFormatting.bytes(modelInstallState.bytesDone)} / ${MediaFormatting.bytes(modelInstallState.bytesTotal)}",
                        color = LpSecondaryText,
                        fontSize = 11.sp,
                    )
                    TextButton(onClick = onCancelModel) { Text(language.text("Cancel download", "Hủy tải")) }
                }
                is ModelInstallState.Installed -> {
                    Text(
                        language.text("Used only for on-device speech recognition. Inference does not download anything after activation.", "Chỉ dùng để nhận dạng giọng nói trên thiết bị. Sau khi kích hoạt, inference không tải thêm dữ liệu."),
                        color = LpSecondaryText,
                        fontSize = 11.sp,
                    )
                    TextButton(onClick = onDeleteModel, enabled = canDeleteModel) {
                        Text(language.text("Delete model", "Xóa model"))
                    }
                }
                is ModelInstallState.Failed -> {
                    Text(modelInstallState.message, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    PrimaryAction(language.text("Retry install", "Thử cài lại"), Icons.Rounded.Download, onInstallModel)
                }
                ModelInstallState.NotInstalled -> PrimaryAction(language.text("Install Speech AI · ~104 MB", "Cài Speech AI · ~104 MB"), Icons.Rounded.Download, onInstallModel)
            }
        }

        SourceSeparationModelManagementCard(
            language = language,
            state = sourceSeparationModelInstallState,
            canDelete = canDeleteSourceSeparationModel,
            onInstall = onInstallSourceSeparationModel,
            onCancel = onCancelSourceSeparationModel,
            onDelete = onDeleteSourceSeparationModel,
        )

        Stage19ModelSettingsCards(
            language = language,
            speakerModelInstallState = speakerModelInstallState,
            canDeleteSpeakerModel = canDeleteSpeakerModel,
            cloningModelInstallState = cloningModelInstallState,
            canDeleteCloningModel = canDeleteCloningModel,
            onInstallSpeakerModel = onInstallSpeakerModel,
            onCancelSpeakerModel = onCancelSpeakerModel,
            onDeleteSpeakerModel = onDeleteSpeakerModel,
            onInstallCloningModel = onInstallCloningModel,
            onCancelCloningModel = onCancelCloningModel,
            onDeleteCloningModel = onDeleteCloningModel,
        )

        LpCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = LpViolet, modifier = Modifier.size(21.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(language.text("Vietnamese Neural Voice", "Giọng Neural tiếng Việt"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (neuralVoiceInstallState) {
                            is ModelInstallState.Installed -> "VAIS1000 Medium · ${MediaFormatting.bytes(neuralVoiceInstallState.bytes)} · offline"
                            is ModelInstallState.Downloading -> language.text("Downloading verified voice pack…", "Đang tải gói giọng đã xác minh…")
                            is ModelInstallState.Failed -> language.text("Install failed", "Cài đặt thất bại")
                            ModelInstallState.NotInstalled -> language.text("Optional · not installed", "Tùy chọn · chưa cài")
                        },
                        color = LpSecondaryText,
                        fontSize = 11.sp,
                    )
                }
            }
            when (neuralVoiceInstallState) {
                is ModelInstallState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { neuralVoiceInstallState.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = LpViolet,
                        trackColor = LpSurfaceStrong,
                    )
                    Text(
                        "${(neuralVoiceInstallState.progress * 100).toInt()}% · ${MediaFormatting.bytes(neuralVoiceInstallState.bytesDone)} / ${MediaFormatting.bytes(neuralVoiceInstallState.bytesTotal)}",
                        color = LpSecondaryText,
                        fontSize = 11.sp,
                    )
                    TextButton(onClick = onCancelNeuralVoice) { Text(language.text("Cancel download", "Hủy tải")) }
                }
                is ModelInstallState.Installed -> {
                    Text(
                        language.text(
                            "One local 22.05 kHz neural preset. Select it in AI Voice; system voices remain the fallback. Emotion is unavailable; Voice Cloning uses the separate optional EN/ZH model above.",
                            "Một preset neural 22,05 kHz chạy cục bộ. Chọn trong Giọng AI; giọng hệ thống vẫn là fallback. Chưa hỗ trợ cảm xúc; Voice Cloning dùng model EN/ZH tùy chọn riêng ở trên.",
                        ),
                        color = LpSecondaryText,
                        fontSize = 11.sp,
                    )
                    TextButton(onClick = onDeleteNeuralVoice, enabled = canDeleteNeuralVoice) {
                        Text(language.text("Delete voice pack", "Xóa gói giọng"))
                    }
                }
                is ModelInstallState.Failed -> {
                    Text(neuralVoiceInstallState.message, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    PrimaryAction(language.text("Retry install", "Thử cài lại"), Icons.Rounded.Download, onInstallNeuralVoice)
                }
                ModelInstallState.NotInstalled -> {
                    Text(
                        language.text(
                            "Downloads only the pinned model archive (64 MiB; ~78 MiB installed). Audio synthesis stays on-device. VAIS-1000 dataset: CC BY 4.0.",
                            "Chỉ tải archive model đã pin (64 MiB; khoảng 78 MiB sau khi cài). Tổng hợp âm thanh luôn ở trên thiết bị. Dataset VAIS-1000: CC BY 4.0.",
                        ),
                        color = LpSecondaryText,
                        fontSize = 11.sp,
                    )
                    PrimaryAction(language.text("Install Neural Voice · 64 MiB", "Cài Giọng Neural · 64 MiB"), Icons.Rounded.Download, onInstallNeuralVoice)
                }
            }
        }

        LpCard {
            SettingsValueRow(
                Icons.Rounded.WorkspacePremium,
                "LingoPlay Plus",
                if (isPlus) language.text("Active", "Đang hoạt động") else language.text("Explore", "Xem gói"),
                onPlus,
            )
            CardDivider()
            SettingsValueRow(Icons.Rounded.Info, language.text("About LingoPlay", "Giới thiệu LingoPlay"), BuildConfig.VERSION_NAME, onAbout)
        }
    }
}

@Composable
internal fun PlusScreen(language: UiLanguage, store: AndroidPlusStore, onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { store.start() }
    ScreenScroll {
        ScreenHeader("LingoPlay Plus", onBack)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.WorkspacePremium, contentDescription = null, tint = LpViolet, modifier = Modifier.size(52.dp))
        }
        LpCard {
            Text(
                if (store.isPlus) language.text("Plus is active", "Plus đang hoạt động") else language.text("Plus subscriptions", "Gói đăng ký Plus"),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                language.text(
                    "Google Play Billing 9.1 is wired for weekly/monthly subscriptions. Pending purchases never unlock Plus.",
                    "Google Play Billing 9.1 đã được nối cho gói tuần/tháng. Giao dịch đang chờ không mở khóa Plus.",
                ),
                color = LpSecondaryText,
                fontSize = 12.sp,
            )
            store.message?.let { Text(it, color = LpSecondaryText, fontSize = 11.sp) }
        }
        store.products.forEach { product ->
            LpCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(product.title, fontWeight = FontWeight.Bold)
                        Text(product.productId, color = LpSecondaryText, fontSize = 10.sp)
                    }
                    Text(product.price, color = LpCyan, fontWeight = FontWeight.Bold)
                }
                PrimaryAction(language.text("Subscribe", "Đăng ký"), Icons.Rounded.WorkspacePremium) {
                    (context as? Activity)?.let { store.purchase(it, product) }
                }
            }
        }
        if (
            store.products.isEmpty() &&
            !store.isPlus &&
            store.phase != AndroidPlusPhase.CONNECTING &&
            store.phase != AndroidPlusPhase.LOADING_PRODUCTS
        ) {
            LpCard {
                Text(language.text("Products unavailable", "Chưa có sản phẩm"), fontWeight = FontWeight.Bold)
                Text(
                    store.message ?: language.text(
                        "This build/account has no matching Play Console subscription products yet.",
                        "Build/tài khoản này chưa có subscription tương ứng trên Play Console.",
                    ),
                    color = LpSecondaryText,
                    fontSize = 12.sp,
                )
                PrimaryAction(language.text("Retry", "Thử lại"), Icons.Rounded.Download) { store.refresh() }
            }
        }
        TextButton(onClick = { store.restore() }) {
            Text(language.text("Restore purchases", "Khôi phục giao dịch"))
        }
    }
}

@Composable
internal fun AboutScreen(
    language: UiLanguage,
    diagnosticsCount: Int,
    cleanBackgroundAvailable: Boolean,
    onBack: () -> Unit,
) {
    ScreenScroll {
        ScreenHeader(language.text("About LingoPlay", "Giới thiệu LingoPlay"), onBack)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BrandMark(size = 86.dp) }
        LpCard {
            Text("LingoPlay ${BuildConfig.VERSION_NAME}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(language.text("Private local-media dubbing", "Lồng tiếng media cục bộ riêng tư"), color = LpSecondaryText)
            CardDivider()
            Text(language.text("Media boundary", "Biên media"), fontWeight = FontWeight.Bold)
            Text(language.text("Video/audio stay on-device; only transcript JSON is eligible for translation requests.", "Video/audio luôn trên thiết bị; chỉ transcript JSON có thể được gửi để dịch."), color = LpSecondaryText, fontSize = 12.sp)
            CardDivider()
            Text("Clean Background: ${if (cleanBackgroundAvailable) "Ready" else "Unavailable"}", fontSize = 12.sp)
            Text(language.text("Local diagnostic events: $diagnosticsCount", "Sự kiện chẩn đoán cục bộ: $diagnosticsCount"), fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsValueRow(icon: ImageVector, title: String, value: String, action: (() -> Unit)? = null) {
    val modifier = if (action != null) Modifier.fillMaxWidth().clickable(onClick = action) else Modifier.fillMaxWidth()
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = LpCyan, modifier = Modifier.size(21.dp))
        Text(title, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(value, color = LpSecondaryText, fontSize = 11.sp)
        if (action != null) Text("›", color = LpSecondaryText, fontSize = 20.sp)
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = LpCyan, modifier = Modifier.size(21.dp))
        Text(title, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
