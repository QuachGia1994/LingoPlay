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
internal fun BottomNavigation(
    language: UiLanguage,
    selected: Tab,
    onSelected: (Tab) -> Unit,
    onImport: () -> Unit,
) {
    Surface(color = Color(0xF50B0E18), tonalElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomTab(Tab.HOME, selected, Icons.Rounded.Home, language.text("Home", "Trang chủ"), onSelected, Modifier.weight(1f))
            BottomTab(Tab.LIBRARY, selected, Icons.Rounded.VideoLibrary, language.text("Library", "Thư viện"), onSelected, Modifier.weight(1f))
            Column(
                modifier = Modifier.weight(1f).clickable(onClick = onImport).padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    modifier = Modifier.size(38.dp).clip(CircleShape).background(accentBrush),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(21.dp))
                }
                Text(language.text("Import", "Chọn video"), color = LpCyan, fontSize = 9.sp, maxLines = 1)
            }
            BottomTab(Tab.SETTINGS, selected, Icons.Rounded.Settings, language.text("Settings", "Cài đặt"), onSelected, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BottomTab(tab: Tab, selected: Tab, icon: ImageVector, label: String, onSelected: (Tab) -> Unit, modifier: Modifier) {
    val active = selected == tab
    Column(
        modifier = modifier.clickable { onSelected(tab) }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = label, tint = if (active) LpCyan else LpSecondaryText, modifier = Modifier.size(20.dp))
        Text(label, color = if (active) LpCyan else LpSecondaryText, fontSize = 9.sp)
    }
}

@Composable
internal fun ScreenScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
internal fun LpCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, LpBorder, RoundedCornerShape(22.dp)),
        color = LpSurface.copy(alpha = 0.90f),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
internal fun BrandMark(size: androidx.compose.ui.unit.Dp = 78.dp) {
    Image(
        painter = painterResource(R.drawable.lingoplay_mark),
        contentDescription = "LingoPlay",
        modifier = Modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}

@Composable
internal fun PrimaryAction(title: String, icon: ImageVector, action: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(accentBrush).clickable(onClick = action).padding(vertical = 16.dp, horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun ScreenHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = { Spacer(Modifier.width(38.dp)) }) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.size(38.dp).clickable(onClick = onBack), color = LpSurface, shape = CircleShape) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Text(title, modifier = Modifier.widthIn(max = 220.dp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Box(modifier = Modifier.widthIn(min = 38.dp), contentAlignment = Alignment.CenterEnd) { trailing() }
    }
}

@Composable
internal fun VideoPlaceholder(height: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(18.dp)).background(
            Brush.linearGradient(listOf(Color(0xFF0B2238), Color(0xFF28133E), Color(0xFF063846))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = Color.Black.copy(alpha = 0.28f), shape = CircleShape) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", modifier = Modifier.padding(14.dp).size(28.dp), tint = Color.White)
        }
        Row(modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.32f)).padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(11.dp), tint = LpCyan)
            Spacer(Modifier.width(4.dp))
            Text("LOCAL", fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun LibraryItemRow(
    item: LocalLibraryItem,
    onOpen: () -> Unit,
    onShare: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, LpBorder, RoundedCornerShape(20.dp)),
        color = LpSurface.copy(alpha = 0.88f),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(width = 92.dp, height = 62.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF12314C), Color(0xFF351B48), Color(0xFF0B3A43)))).clickable(onClick = onOpen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${item.title}", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f).clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${item.durationText}  •  ${item.languagePair}", color = LpSecondaryText, fontSize = 11.sp)
                Text(MediaFormatting.bytes(item.sizeBytes), color = LpCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (onShare != null) {
                Icon(Icons.Rounded.Share, contentDescription = "Share ${item.title}", tint = LpCyan, modifier = Modifier.size(20.dp).clickable(onClick = onShare))
            }
            if (onDelete != null) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete ${item.title}", tint = LpSecondaryText, modifier = Modifier.size(20.dp).clickable(onClick = onDelete))
            }
        }
    }
}

@Composable
internal fun CapabilityCard(icon: ImageVector, title: String, detail: String, modifier: Modifier) {
    Surface(modifier = modifier, color = LpSurface, shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = null, tint = LpCyan)
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = LpSecondaryText, fontSize = 9.sp)
        }
    }
}

@Composable
internal fun SectionHeader(title: String, trailing: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(trailing, color = LpSecondaryText, fontSize = 11.sp)
    }
}

@Composable
internal fun PrepareRow(
    icon: ImageVector,
    title: String,
    value: String,
    detail: String,
    action: (() -> Unit)? = null,
) {
    val modifier = if (action != null) Modifier.fillMaxWidth().clickable(onClick = action) else Modifier.fillMaxWidth()
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = LpCyan, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = LpSecondaryText, fontSize = 10.sp)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = LpSecondaryText, fontSize = 10.sp)
        }
        if (action != null) Text("›", color = LpSecondaryText, fontSize = 22.sp)
    }
}

@Composable
internal fun CardDivider() {
    HorizontalDivider(color = LpBorder)
}
