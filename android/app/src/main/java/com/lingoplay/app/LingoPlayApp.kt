package com.lingoplay.app

import android.content.Intent
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.CloudDownload
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Stage { SPLASH, HOME, PREPARE, PROCESSING, PLAYER }
private enum class Tab { HOME, LIBRARY, OFFLINE, SETTINGS }

private data class DemoVideo(
    val title: String,
    val duration: String,
    val languagePair: String,
    val progress: Float? = null,
)

private val demoVideos = listOf(
    DemoVideo("The Future of AI", "01:24:32", "EN → VI"),
    DemoVideo("Space Documentary", "00:48:10", "EN → VI"),
    DemoVideo("Marketing Strategy", "00:32:45", "EN → VI", 0.65f),
)

private val accentBrush = Brush.horizontalGradient(listOf(LpViolet, LpBlue, LpCyan))

@Composable
fun LingoPlayApp() {
    var stageName by rememberSaveable { mutableStateOf(Stage.SPLASH.name) }
    var tabName by rememberSaveable { mutableStateOf(Tab.HOME.name) }
    var audioBlend by rememberSaveable { mutableFloatStateOf(0.60f) }
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var bilingualSubtitles by rememberSaveable { mutableStateOf(true) }
    var selectedMedia by remember { mutableStateOf<LocalMediaItem?>(null) }
    var mediaState by rememberSaveable { mutableStateOf(MediaPreparationState.IDLE.name) }
    var mediaError by rememberSaveable { mutableStateOf<String?>(null) }
    var asrPhaseName by rememberSaveable { mutableStateOf(ASRPhase.IDLE.name) }
    var asrTranscript by remember { mutableStateOf<ASRTranscript?>(null) }
    var asrError by rememberSaveable { mutableStateOf<String?>(null) }
    var translationPhaseName by rememberSaveable { mutableStateOf(TranslationPhase.IDLE.name) }
    var translationDocument by remember { mutableStateOf<TranslationDocument?>(null) }
    var translationError by rememberSaveable { mutableStateOf<String?>(null) }
    var translationBatch by rememberSaveable { mutableStateOf(0) }
    var translationBatchTotal by rememberSaveable { mutableStateOf(0) }
    var ttsPhaseName by rememberSaveable { mutableStateOf(TTSPhase.IDLE.name) }
    var dubSpeechDocument by remember { mutableStateOf<DubSpeechDocument?>(null) }
    var ttsError by rememberSaveable { mutableStateOf<String?>(null) }
    var ttsSegment by rememberSaveable { mutableStateOf(0) }
    var ttsSegmentTotal by rememberSaveable { mutableStateOf(0) }
    var mixPhaseName by rememberSaveable { mutableStateOf(MixPhase.IDLE.name) }
    var mixResult by remember { mutableStateOf<LocalDubMediaResult?>(null) }
    var mixError by rememberSaveable { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stage = Stage.valueOf(stageName)
    val tab = Tab.valueOf(tabName)
    val preparationState = MediaPreparationState.valueOf(mediaState)
    val asrPhase = ASRPhase.valueOf(asrPhaseName)
    val translationPhase = TranslationPhase.valueOf(translationPhaseName)
    val ttsPhase = TTSPhase.valueOf(ttsPhaseName)
    val mixPhase = MixPhase.valueOf(mixPhaseName)

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        mediaState = MediaPreparationState.IMPORTING.name
        mediaError = null
        asrPhaseName = ASRPhase.IDLE.name
        asrTranscript = null
        asrError = null
        translationPhaseName = TranslationPhase.IDLE.name
        translationDocument = null
        translationError = null
        translationBatch = 0
        translationBatchTotal = 0
        ttsPhaseName = TTSPhase.IDLE.name
        dubSpeechDocument = null
        ttsError = null
        ttsSegment = 0
        ttsSegmentTotal = 0
        mixPhaseName = MixPhase.IDLE.name
        mixResult = null
        mixError = null
        scope.launch {
            try {
                selectedMedia = LocalMediaRepository.inspect(context, uri)
                mediaState = MediaPreparationState.READY.name
                stageName = Stage.PREPARE.name
            } catch (error: Throwable) {
                mediaState = MediaPreparationState.FAILED.name
                mediaError = error.message ?: "Unable to inspect this local video."
            }
        }
    }

    LaunchedEffect(Unit) {
        if (stageName == Stage.SPLASH.name) {
            delay(900)
            stageName = Stage.HOME.name
        }
    }

    LaunchedEffect(stageName, selectedMedia?.uri) {
        val media = selectedMedia
        if (stageName == Stage.PROCESSING.name && media != null && mediaState == MediaPreparationState.READY.name) {
            mediaState = MediaPreparationState.EXTRACTING_AUDIO.name
            mediaError = null
            try {
                val audioFile = LocalMediaRepository.extractAudio(context, media)
                mediaState = MediaPreparationState.AUDIO_READY.name
                val model = ASRModelStore.findWhisperModel(context)
                if (model == null) {
                    asrPhaseName = ASRPhase.MODEL_MISSING.name
                } else {
                    asrPhaseName = ASRPhase.LOADING_MODEL.name
                    asrError = null
                    asrPhaseName = ASRPhase.TRANSCRIBING.name
                    asrTranscript = SherpaWhisperSpeechRecognizer.transcribe(context, audioFile, model)
                    asrPhaseName = ASRPhase.COMPLETED.name
                    val transcript = asrTranscript ?: error("Speech transcript was not retained.")
                    if (BuildConfig.TRANSLATION_API_BASE_URL.isBlank()) {
                        translationPhaseName = TranslationPhase.ENDPOINT_MISSING.name
                    } else {
                        translationPhaseName = TranslationPhase.TRANSLATING.name
                        translationError = null
                        translationDocument = TranslationService.translate(transcript) { batch, total ->
                            withContext(Dispatchers.Main.immediate) {
                                translationBatch = batch
                                translationBatchTotal = total
                            }
                        }
                        translationPhaseName = TranslationPhase.COMPLETED.name
                        val translated = translationDocument ?: error("Translation document was not retained.")
                        try {
                            ttsPhaseName = TTSPhase.SYNTHESIZING.name
                            ttsError = null
                            dubSpeechDocument = SystemVietnameseTTSService.synthesize(context, translated) { segment, total ->
                                withContext(Dispatchers.Main.immediate) {
                                    ttsSegment = segment
                                    ttsSegmentTotal = total
                                }
                            }
                            ttsPhaseName = TTSPhase.COMPLETED.name
                            val dub = dubSpeechDocument ?: error("Vietnamese speech document was not retained.")
                            try {
                                mixError = null
                                mixResult = TimelineMixService.render(context, media, dub) { phase ->
                                    withContext(Dispatchers.Main.immediate) {
                                        mixPhaseName = phase.name
                                    }
                                }
                                mixPhaseName = MixPhase.COMPLETED.name
                                delay(300)
                                stageName = Stage.PLAYER.name
                            } catch (error: Throwable) {
                                mixPhaseName = MixPhase.FAILED.name
                                mixError = error.message ?: "Local audio mixing or video remux failed."
                            }
                        } catch (_: OfflineVietnameseVoiceMissingException) {
                            ttsPhaseName = TTSPhase.VOICE_MISSING.name
                        } catch (error: Throwable) {
                            ttsPhaseName = TTSPhase.FAILED.name
                            ttsError = error.message ?: "Vietnamese speech synthesis failed."
                        }
                    }
                }
            } catch (error: Throwable) {
                if (mediaState == MediaPreparationState.EXTRACTING_AUDIO.name) {
                    mediaState = MediaPreparationState.FAILED.name
                    mediaError = error.message ?: "Audio preparation failed."
                } else if (asrPhaseName != ASRPhase.COMPLETED.name) {
                    asrPhaseName = ASRPhase.FAILED.name
                    asrError = error.message ?: "Speech recognition failed."
                } else {
                    translationPhaseName = TranslationPhase.FAILED.name
                    translationError = error.message ?: "Translation failed."
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0B0716),
                        LpBackground,
                        Color(0xFF04131C),
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (stage) {
                    Stage.SPLASH -> SplashScreen()

                    Stage.PREPARE -> PrepareScreen(
                        media = selectedMedia,
                        onBack = { stageName = Stage.HOME.name },
                        onEdit = { mediaPicker.launch(arrayOf("video/*")) },
                        onTranslate = {
                            if (selectedMedia == null) mediaPicker.launch(arrayOf("video/*")) else stageName = Stage.PROCESSING.name
                        },
                    )

                    Stage.PROCESSING -> ProcessingScreen(
                        mediaState = preparationState,
                        mediaError = mediaError,
                        asrPhase = asrPhase,
                        transcript = asrTranscript,
                        asrError = asrError,
                        translationPhase = translationPhase,
                        translation = translationDocument,
                        translationError = translationError,
                        translationBatch = translationBatch,
                        translationBatchTotal = translationBatchTotal,
                        ttsPhase = ttsPhase,
                        dubSpeech = dubSpeechDocument,
                        ttsError = ttsError,
                        ttsSegment = ttsSegment,
                        ttsSegmentTotal = ttsSegmentTotal,
                        mixPhase = mixPhase,
                        mixResult = mixResult,
                        mixError = mixError,
                        onBack = { stageName = Stage.HOME.name },
                    )

                    Stage.PLAYER -> PlayerScreen(
                        media = selectedMedia,
                        processed = mixResult,
                        translation = translationDocument,
                        audioBlend = audioBlend,
                        onAudioBlendChange = { audioBlend = it },
                        onBack = { stageName = Stage.HOME.name },
                    )

                    Stage.HOME -> when (tab) {
                        Tab.HOME -> HomeScreen(
                            onImport = { mediaPicker.launch(arrayOf("video/*")) },
                            onOpenVideo = { stageName = Stage.PLAYER.name },
                        )

                        Tab.LIBRARY -> LibraryScreen(
                            offlineOnly = false,
                            onOpenVideo = { stageName = Stage.PLAYER.name },
                        )

                        Tab.OFFLINE -> LibraryScreen(
                            offlineOnly = true,
                            onOpenVideo = { stageName = Stage.PLAYER.name },
                        )

                        Tab.SETTINGS -> SettingsScreen(
                            wifiOnly = wifiOnly,
                            bilingualSubtitles = bilingualSubtitles,
                            onWifiOnlyChange = { wifiOnly = it },
                            onBilingualSubtitlesChange = { bilingualSubtitles = it },
                        )
                    }
                }
            }

            if (stage == Stage.HOME) {
                BottomNavigation(
                    selected = tab,
                    onSelected = { tabName = it.name },
                    onBrand = {
                        tabName = Tab.HOME.name
                        stageName = Stage.HOME.name
                    },
                )
            }
        }
    }
}

@Composable
private fun SplashScreen() {
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
private fun HomeScreen(onImport: () -> Unit, onOpenVideo: () -> Unit) {
    ScreenScroll {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("LingoPlay", color = LpCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Understand without borders", color = LpSecondaryText, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Surface(color = LpSurface, shape = CircleShape) {
                Text("PLUS", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = LpCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        LpCard {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI video translation\n& dubbing", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                    Text("Import a video you already have. The media stays on this device.", color = LpSecondaryText, fontSize = 14.sp)
                }
                Spacer(Modifier.width(12.dp))
                BrandMark()
            }
            PrimaryAction("Import Video", Icons.Rounded.Add, onImport)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = LpCyan, modifier = Modifier.size(16.dp))
                Text("Video and audio are never uploaded", color = LpSecondaryText, fontSize = 12.sp)
            }
        }

        SectionHeader("Recent", "Local library")
        demoVideos.forEach { video ->
            RecentVideoRow(video, onOpenVideo)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CapabilityCard(Icons.Rounded.Mic, "On-device", "Speech AI", Modifier.weight(1f))
            CapabilityCard(Icons.Rounded.Subtitles, "Bilingual", "Subtitles", Modifier.weight(1f))
            CapabilityCard(Icons.Rounded.Download, "Offline", "Playback", Modifier.weight(1f))
        }
    }
}

@Composable
private fun PrepareScreen(media: LocalMediaItem?, onBack: () -> Unit, onEdit: () -> Unit, onTranslate: () -> Unit) {
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
            PrepareRow(Icons.Rounded.Mic, "From language", "Auto Detect", "Detected locally from speech")
            CardDivider()
            PrepareRow(Icons.Rounded.Language, "To language", "Vietnamese", "Tiếng Việt")
            CardDivider()
            PrepareRow(Icons.Rounded.Person, "AI Voice", "Nam · Natural", "Warm, clear Vietnamese voice")
            CardDivider()
            PrepareRow(Icons.Rounded.Tune, "Dubbing mode", "Balanced", "Quality and processing speed")
            CardDivider()
            PrepareRow(Icons.Rounded.Subtitles, "Subtitles", "Bilingual", "Original + translated")
        }

        PrimaryAction("Translate & Dub", Icons.Rounded.AutoAwesome, onTranslate)
        Text("Estimated time depends on device performance and video duration.", modifier = Modifier.fillMaxWidth(), color = LpSecondaryText, fontSize = 12.sp)
    }
}

@Composable
private fun ProcessingScreen(
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
            ProcessingRow("Creating Vietnamese voice", ttsProcessState(ttsPhase))
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
                    Text("Vietnamese translation", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${translation.segments.size} segments", color = LpCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text(translation.translatedText, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Text("Only transcript JSON was sent · source media stayed on-device", color = LpSecondaryText, fontSize = 10.sp)
            }
        }

        if (asrPhase == ASRPhase.MODEL_MISSING) {
            LpCard {
                Text("Speech model is not installed. LingoPlay will not download a large model without an explicit model-install action.", color = LpSecondaryText, fontSize = 12.sp)
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
                    Text("Vietnamese voice ready", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${dubSpeech.segments.size} clips", color = LpCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text("Offline system voice · ${dubSpeech.voiceName}", fontSize = 12.sp)
                Text("${dubSpeech.totalTailSilenceMs} ms timeline silence reserved · no spoken words truncated", color = LpSecondaryText, fontSize = 10.sp)
            }
        }

        if (ttsPhase == TTSPhase.VOICE_MISSING) {
            LpCard {
                Text("No offline Vietnamese system voice is installed. LingoPlay will not use a network-required TTS voice for this local dubbing path.", color = LpSecondaryText, fontSize = 12.sp)
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
                Text("Original audio and Vietnamese dub remain separate for live blend control.", color = LpSecondaryText, fontSize = 10.sp)
            }
        }

        LpCard {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = LpCyan)
                Text("The production pipeline will keep media processing local. Background execution remains a planned Plus capability.", color = LpSecondaryText, fontSize = 13.sp)
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
                TTSPhase.SYNTHESIZING -> "Creating Vietnamese voice on-device"
                TTSPhase.COMPLETED -> when (mixPhase) {
                    MixPhase.RENDERING_AUDIO -> "Building local dub timeline"
                    MixPhase.REMUXING -> "Remuxing dubbed video"
                    MixPhase.COMPLETED -> "Dubbed video ready"
                    MixPhase.FAILED -> "Local mix or remux stopped"
                    MixPhase.IDLE -> "Vietnamese voice ready"
                }
                TTSPhase.VOICE_MISSING -> "Translation ready · offline voice not installed"
                TTSPhase.FAILED -> "Vietnamese voice synthesis stopped"
                TTSPhase.IDLE -> "Vietnamese translation ready"
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

@Composable
private fun PlayerScreen(
    media: LocalMediaItem?,
    processed: LocalDubMediaResult?,
    translation: TranslationDocument?,
    audioBlend: Float,
    onAudioBlendChange: (Float) -> Unit,
    onBack: () -> Unit,
) {
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
            SingleClockDubPlayer(
                processed = processed,
                translation = translation,
            )
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
                Text("Audio blend", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("Balanced export", color = LpCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = audioBlend,
                onValueChange = onAudioBlendChange,
                valueRange = 0f..1f,
                enabled = false,
            )
            Row {
                Text("Original", color = LpSecondaryText, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("Dub", color = LpSecondaryText, fontSize = 12.sp)
            }
            Text(
                "Android playback uses one mixed audio track to avoid dual-player clock drift. Live blend is disabled until it can run inside one audio graph.",
                color = LpSecondaryText,
                fontSize = 10.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerAction(Icons.Rounded.ClosedCaption, "Subtitles", Modifier.weight(1f))
            PlayerAction(Icons.Rounded.Tune, "Mixed", Modifier.weight(1f))
            PlayerAction(Icons.Rounded.Speed, "1.0x", Modifier.weight(1f))
            PlayerAction(Icons.Rounded.Download, "Offline", Modifier.weight(1f))
        }
    }
}

@Composable
private fun SingleClockDubPlayer(
    processed: LocalDubMediaResult,
    translation: TranslationDocument?,
) {
    var videoView by remember(processed.remuxedVideoFile.absolutePath) { mutableStateOf<VideoView?>(null) }
    var videoReady by remember(processed.remuxedVideoFile.absolutePath) { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentMs by remember { mutableStateOf(0) }

    LaunchedEffect(isPlaying, videoView) {
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
                    setVideoPath(processed.remuxedVideoFile.absolutePath)
                    setOnPreparedListener {
                        videoReady = true
                    }
                    setOnCompletionListener {
                        isPlaying = false
                        currentMs = processed.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    }
                }
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
                modifier = Modifier.clickable {
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
                        } else {
                            view.start()
                            isPlaying = true
                        }
                    },
            )
            Icon(
                Icons.Rounded.Forward10,
                contentDescription = "Forward 10 seconds",
                modifier = Modifier.clickable {
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
        )
        Row {
            Text(MediaFormatting.duration(currentMs.toLong()), color = LpSecondaryText, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(MediaFormatting.duration(durationMs.toLong()), color = LpSecondaryText, fontSize = 10.sp)
        }
    }

    val activeSegment = translation?.segments?.lastOrNull { currentMs >= it.startMs && currentMs < it.endMs }
    LpCard {
        SubtitleRow("SRC", activeSegment?.sourceText ?: "—")
        CardDivider()
        SubtitleRow("VI", activeSegment?.translatedText ?: "—")
    }
}

@Composable
private fun LibraryScreen(offlineOnly: Boolean, onOpenVideo: () -> Unit) {
    ScreenScroll {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(if (offlineOnly) "Offline" else "Library", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(if (offlineOnly) "Ready without a connection" else "Your translated videos", color = LpSecondaryText, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Icon(if (offlineOnly) Icons.Rounded.CloudDownload else Icons.Rounded.Search, contentDescription = null, tint = LpCyan)
        }

        demoVideos.take(if (offlineOnly) 2 else demoVideos.size).forEach { video ->
            RecentVideoRow(video, onOpenVideo)
        }

        LpCard {
            SectionHeader("Storage", "45 GB of 128 GB")
            LinearProgressIndicator(
                progress = { 0.35f },
                modifier = Modifier.fillMaxWidth(),
                color = LpCyan,
                trackColor = LpSurfaceStrong,
            )
            Text("AI models and translated media stay on this device.", color = LpSecondaryText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsScreen(
    wifiOnly: Boolean,
    bilingualSubtitles: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    onBilingualSubtitlesChange: (Boolean) -> Unit,
) {
    ScreenScroll {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Settings", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("Simple playback and privacy preferences", color = LpSecondaryText, fontSize = 12.sp)
            }
            BrandMark(size = 50.dp)
        }

        LpCard {
            SettingsValueRow(Icons.Rounded.Person, "AI Voice", "Nam · Natural")
            CardDivider()
            SettingsValueRow(Icons.Rounded.Language, "Playback Language", "Vietnamese")
            CardDivider()
            ToggleRow(Icons.Rounded.Wifi, "Download models on Wi-Fi only", wifiOnly, onWifiOnlyChange)
            CardDivider()
            ToggleRow(Icons.Rounded.Subtitles, "Bilingual subtitles", bilingualSubtitles, onBilingualSubtitlesChange)
        }

        LpCard {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = LpCyan)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Private by architecture", fontWeight = FontWeight.Bold)
                    Text("Video and audio never go to the LingoPlay backend. Only transcript text required for translation is sent as compact JSON when online translation is enabled.", color = LpSecondaryText, fontSize = 13.sp)
                }
            }
        }

        LpCard {
            SettingsValueRow(Icons.Rounded.Storage, "Downloaded AI Models", "Not installed")
            CardDivider()
            SettingsValueRow(Icons.Rounded.Info, "About LingoPlay", "Foundation")
        }
    }
}

@Composable
private fun BottomNavigation(selected: Tab, onSelected: (Tab) -> Unit, onBrand: () -> Unit) {
    Surface(color = Color(0xF00B0E18), tonalElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomTab(Tab.HOME, selected, Icons.Rounded.Home, "Home", onSelected, Modifier.weight(1f))
            BottomTab(Tab.LIBRARY, selected, Icons.Rounded.VideoLibrary, "Library", onSelected, Modifier.weight(1f))
            Box(modifier = Modifier.weight(0.9f), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.clickable(onClick = onBrand)) {
                    BrandMark(size = 50.dp)
                }
            }
            BottomTab(Tab.OFFLINE, selected, Icons.Rounded.CloudDownload, "Offline", onSelected, Modifier.weight(1f))
            BottomTab(Tab.SETTINGS, selected, Icons.Rounded.Settings, "Settings", onSelected, Modifier.weight(1f))
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
private fun ScreenScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
private fun LpCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, LpBorder, RoundedCornerShape(22.dp)),
        color = LpSurface.copy(alpha = 0.90f),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun BrandMark(size: androidx.compose.ui.unit.Dp = 78.dp) {
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier = Modifier.size(size).clip(shape).background(LpSurfaceStrong).border(1.5.dp, accentBrush, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.ChatBubble, contentDescription = "LingoPlay", tint = LpCyan, modifier = Modifier.size(size * 0.56f))
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.27f))
    }
}

@Composable
private fun PrimaryAction(title: String, icon: ImageVector, action: () -> Unit) {
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
private fun ScreenHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = { Spacer(Modifier.width(38.dp)) }) {
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
private fun VideoPlaceholder(height: androidx.compose.ui.unit.Dp) {
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
private fun RecentVideoRow(video: DemoVideo, action: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = action).border(1.dp, LpBorder, RoundedCornerShape(20.dp)),
        color = LpSurface.copy(alpha = 0.88f),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(width = 92.dp, height = 62.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF12314C), Color(0xFF351B48), Color(0xFF0B3A43)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(video.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${video.duration}  •  ${video.languagePair}", color = LpSecondaryText, fontSize = 11.sp)
                if (video.progress != null) {
                    LinearProgressIndicator(progress = { video.progress }, modifier = Modifier.fillMaxWidth(), color = LpCyan, trackColor = LpSurfaceStrong)
                } else {
                    Text("Completed", color = LpCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CapabilityCard(icon: ImageVector, title: String, detail: String, modifier: Modifier) {
    Surface(modifier = modifier, color = LpSurface, shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = null, tint = LpCyan)
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = LpSecondaryText, fontSize = 9.sp)
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(trailing, color = LpSecondaryText, fontSize = 11.sp)
    }
}

@Composable
private fun PrepareRow(icon: ImageVector, title: String, value: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = LpCyan, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = LpSecondaryText, fontSize = 10.sp)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = LpSecondaryText, fontSize = 10.sp)
        }
        Text("›", color = LpSecondaryText, fontSize = 22.sp)
    }
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

@Composable
private fun SubtitleRow(language: String, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(language, color = LpCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
        Text(text, modifier = Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun PlayerAction(icon: ImageVector, label: String, modifier: Modifier) {
    Surface(modifier = modifier, color = LpSurface, shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = label, tint = LpCyan, modifier = Modifier.size(20.dp))
            Text(label, fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SettingsValueRow(icon: ImageVector, title: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = LpCyan, modifier = Modifier.size(21.dp))
        Text(title, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(value, color = LpSecondaryText, fontSize = 11.sp)
        Text("›", color = LpSecondaryText, fontSize = 20.sp)
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

@Composable
private fun CardDivider() {
    HorizontalDivider(color = LpBorder)
}
