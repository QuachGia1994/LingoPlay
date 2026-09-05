package com.lingoplay.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Stage19ModelSettingsCards(
    language: UiLanguage,
    speakerModelInstallState: ModelInstallState,
    canDeleteSpeakerModel: Boolean,
    cloningModelInstallState: ModelInstallState,
    canDeleteCloningModel: Boolean,
    onInstallSpeakerModel: () -> Unit,
    onCancelSpeakerModel: () -> Unit,
    onDeleteSpeakerModel: () -> Unit,
    onInstallCloningModel: () -> Unit,
    onCancelCloningModel: () -> Unit,
    onDeleteCloningModel: () -> Unit,
) {
    LpCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Person, contentDescription = null, tint = LpCyan, modifier = Modifier.size(21.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(language.text("Speaker AI", "AI nhận diện người nói"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when (speakerModelInstallState) {
                        is ModelInstallState.Installed -> "Pyannote INT8 + Titanet · ${MediaFormatting.bytes(speakerModelInstallState.bytes)} · offline"
                        is ModelInstallState.Downloading -> language.text("Downloading verified diarization pack…", "Đang tải gói diarization đã xác minh…")
                        is ModelInstallState.Failed -> language.text("Install failed", "Cài đặt thất bại")
                        ModelInstallState.NotInstalled -> language.text("Optional · required for Multi-speaker", "Tùy chọn · cần cho Multi-speaker")
                    },
                    color = LpSecondaryText,
                    fontSize = 11.sp,
                )
            }
        }
        when (speakerModelInstallState) {
            is ModelInstallState.Downloading -> {
                LinearProgressIndicator(
                    progress = { speakerModelInstallState.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = LpCyan,
                    trackColor = LpSurfaceStrong,
                )
                Text(
                    "${(speakerModelInstallState.progress * 100).toInt()}% · ${MediaFormatting.bytes(speakerModelInstallState.bytesDone)} / ${MediaFormatting.bytes(speakerModelInstallState.bytesTotal)}",
                    color = LpSecondaryText,
                    fontSize = 11.sp,
                )
                TextButton(onClick = onCancelSpeakerModel) { Text(language.text("Cancel download", "Hủy tải")) }
            }
            is ModelInstallState.Installed -> {
                Text(
                    language.text(
                        "Detects speakers locally and assigns stable speaker_1… labels by first appearance. Overlap stays unknown instead of guessing a speaker.",
                        "Nhận diện người nói cục bộ và gán nhãn speaker_1… ổn định theo lần xuất hiện đầu. Đoạn chồng giọng giữ unknown thay vì đoán.",
                    ),
                    color = LpSecondaryText,
                    fontSize = 11.sp,
                )
                TextButton(onClick = onDeleteSpeakerModel, enabled = canDeleteSpeakerModel) {
                    Text(language.text("Delete Speaker AI", "Xóa Speaker AI"))
                }
            }
            is ModelInstallState.Failed -> {
                Text(speakerModelInstallState.message, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                PrimaryAction(language.text("Retry install", "Thử cài lại"), Icons.Rounded.Download, onInstallSpeakerModel)
            }
            ModelInstallState.NotInstalled -> PrimaryAction(
                language.text("Install Speaker AI · ~45 MiB", "Cài Speaker AI · ~45 MiB"),
                Icons.Rounded.Download,
                onInstallSpeakerModel,
            )
        }
    }

    LpCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Shield, contentDescription = null, tint = LpViolet, modifier = Modifier.size(21.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(language.text("Local Voice Cloning", "Clone giọng cục bộ"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when (cloningModelInstallState) {
                        is ModelInstallState.Installed -> "ZipVoice INT8 · ${MediaFormatting.bytes(cloningModelInstallState.bytes)} · EN/ZH only"
                        is ModelInstallState.Downloading -> when (cloningModelInstallState.phase) {
                            ModelInstallPhase.DOWNLOADING -> language.text("Downloading verified cloning pack…", "Đang tải gói clone đã xác minh…")
                            ModelInstallPhase.VERIFYING -> language.text("Verifying downloaded model…", "Đang xác minh model đã tải…")
                            ModelInstallPhase.EXTRACTING -> language.text("Extracting Voice Cloning model…", "Đang giải nén model Voice Cloning…")
                            ModelInstallPhase.ACTIVATING -> language.text("Activating Voice Cloning…", "Đang kích hoạt Voice Cloning…")
                        }
                        is ModelInstallState.Failed -> language.text("Install failed", "Cài đặt thất bại")
                        ModelInstallState.NotInstalled -> language.text("Optional · consent required · EN/ZH only", "Tùy chọn · cần đồng ý · chỉ EN/ZH")
                    },
                    color = LpSecondaryText,
                    fontSize = 11.sp,
                )
            }
        }
        when (cloningModelInstallState) {
            is ModelInstallState.Downloading -> {
                if (cloningModelInstallState.phase == ModelInstallPhase.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { cloningModelInstallState.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = LpViolet,
                        trackColor = LpSurfaceStrong,
                    )
                    Text("${(cloningModelInstallState.progress * 100).toInt()}%", color = LpSecondaryText, fontSize = 11.sp)
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = LpViolet,
                        trackColor = LpSurfaceStrong,
                    )
                    Text(
                        language.text("Working locally · do not close the app", "Đang xử lý cục bộ · không đóng ứng dụng"),
                        color = LpSecondaryText,
                        fontSize = 11.sp,
                    )
                }
                TextButton(onClick = onCancelCloningModel) {
                    Text(
                        if (cloningModelInstallState.phase == ModelInstallPhase.DOWNLOADING) {
                            language.text("Cancel download", "Hủy tải")
                        } else {
                            language.text("Cancel install", "Hủy cài đặt")
                        },
                    )
                }
            }
            is ModelInstallState.Installed -> {
                Text(
                    language.text(
                        "Uses only a clear single-speaker segment from the current video as an ephemeral reference. Reference audio/profile is not stored after processing. Overlap and unknown speakers fall back to installed voices.",
                        "Chỉ dùng một đoạn đơn-speaker rõ trong video hiện tại làm mẫu tạm thời. Mẫu/profile giọng không được lưu sau xử lý. Đoạn chồng giọng và không xác định dùng giọng đã cài làm fallback.",
                    ),
                    color = LpSecondaryText,
                    fontSize = 11.sp,
                )
                TextButton(onClick = onDeleteCloningModel, enabled = canDeleteCloningModel) {
                    Text(language.text("Delete cloning model", "Xóa model clone"))
                }
            }
            is ModelInstallState.Failed -> {
                Text(cloningModelInstallState.message, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                PrimaryAction(language.text("Retry install", "Thử cài lại"), Icons.Rounded.Download, onInstallCloningModel)
            }
            ModelInstallState.NotInstalled -> PrimaryAction(
                language.text("Install Voice Cloning · ~156 MiB", "Cài Voice Cloning · ~156 MiB"),
                Icons.Rounded.Download,
                onInstallCloningModel,
            )
        }
        Text(
            language.text(
                "Use only voices you own or have explicit permission to reproduce. ZipVoice supports English and Chinese; Vietnamese/Japanese are not presented as clone-capable.",
                "Chỉ dùng giọng bạn sở hữu hoặc được cho phép rõ ràng để tái tạo. ZipVoice hỗ trợ tiếng Anh và Trung; tiếng Việt/Nhật không được hiển thị là có khả năng clone.",
            ),
            color = LpSecondaryText,
            fontSize = 10.sp,
        )
    }
}
