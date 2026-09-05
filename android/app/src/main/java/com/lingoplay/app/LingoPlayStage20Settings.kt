package com.lingoplay.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
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
internal fun SourceSeparationModelManagementCard(
    language: UiLanguage,
    state: ModelInstallState,
    canDelete: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    LpCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = LpCyan, modifier = Modifier.size(21.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(language.text("Clean Background Model", "Model Tách nền sạch"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when (state) {
                        is ModelInstallState.Installed -> "Spleeter 2-stem FP16 · ${MediaFormatting.bytes(state.bytes)} · offline"
                        is ModelInstallState.Downloading -> language.text("Downloading verified separator pack…", "Đang tải gói tách nguồn đã xác minh…")
                        is ModelInstallState.Failed -> language.text("Install failed", "Cài đặt thất bại")
                        ModelInstallState.NotInstalled -> language.text("Optional · required for Clean Background", "Tùy chọn · cần cho Tách nền sạch")
                    },
                    color = LpSecondaryText,
                    fontSize = 11.sp,
                )
            }
        }
        when (state) {
            is ModelInstallState.Downloading -> {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = LpCyan,
                    trackColor = LpSurfaceStrong,
                )
                Text(
                    "${(state.progress * 100).toInt()}% · ${MediaFormatting.bytes(state.bytesDone)} / ${MediaFormatting.bytes(state.bytesTotal)}",
                    color = LpSecondaryText,
                    fontSize = 11.sp,
                )
                TextButton(onClick = onCancel) { Text(language.text("Cancel download", "Hủy tải")) }
            }
            is ModelInstallState.Installed -> {
                Text(
                    language.text(
                        "Separates vocals and accompaniment locally. Stems are temporary and deleted after each processing run.",
                        "Tách giọng và nhạc nền cục bộ. Các stem chỉ tồn tại tạm thời và được xóa sau mỗi lần xử lý.",
                    ),
                    color = LpSecondaryText,
                    fontSize = 11.sp,
                )
                TextButton(onClick = onDelete, enabled = canDelete) {
                    Text(language.text("Delete Clean Background model", "Xóa model Tách nền sạch"))
                }
            }
            is ModelInstallState.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                PrimaryAction(language.text("Retry install", "Thử cài lại"), Icons.Rounded.Download, onInstall)
            }
            ModelInstallState.NotInstalled -> {
                Text(
                    language.text(
                        "Downloads the pinned 33.6 MiB sherpa-onnx Spleeter archive only after you choose Install.",
                        "Chỉ tải archive Spleeter sherpa-onnx 33,6 MiB đã pin sau khi bạn chọn Cài đặt.",
                    ),
                    color = LpSecondaryText,
                    fontSize = 11.sp,
                )
                PrimaryAction(language.text("Install Clean Background · 33.6 MiB", "Cài Tách nền sạch · 33,6 MiB"), Icons.Rounded.Download, onInstall)
            }
        }
    }
}
