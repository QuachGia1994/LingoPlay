package com.lingoplay.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.util.Rational
import android.widget.VideoView
import androidx.activity.compose.BackHandler
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

internal enum class Stage { SPLASH, HOME, PREPARE, PROCESSING, PLAYER, PLUS, ABOUT }
internal enum class Tab { HOME, LIBRARY, SETTINGS }
internal enum class UiLanguage { ENGLISH, VIETNAMESE }

internal fun UiLanguage.text(english: String, vietnamese: String): String =
    if (this == UiLanguage.VIETNAMESE) vietnamese else english

internal val accentBrush = Brush.horizontalGradient(listOf(LpViolet, LpBlue, LpCyan))

@Composable
fun LingoPlayApp() {
    var stageName by rememberSaveable { mutableStateOf(Stage.SPLASH.name) }
    var tabName by rememberSaveable { mutableStateOf(Tab.HOME.name) }
    var selectedMedia by remember { mutableStateOf<LocalMediaItem?>(null) }
    var preparedAudioFile by remember { mutableStateOf<File?>(null) }
    var mediaState by rememberSaveable { mutableStateOf(MediaPreparationState.IDLE.name) }
    var mediaError by rememberSaveable { mutableStateOf<String?>(null) }
    var asrPhaseName by rememberSaveable { mutableStateOf(ASRPhase.IDLE.name) }
    var asrTranscript by remember { mutableStateOf<ASRTranscript?>(null) }
    var asrError by rememberSaveable { mutableStateOf<String?>(null) }
    var speakerPhaseName by rememberSaveable { mutableStateOf(SpeakerPhase.IDLE.name) }
    var speakerDocument by remember { mutableStateOf<SpeakerDiarizationDocument?>(null) }
    var speakerError by rememberSaveable { mutableStateOf<String?>(null) }
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
    var libraryItems by remember { mutableStateOf<List<LocalLibraryItem>>(emptyList()) }
    var activeLibraryItem by remember { mutableStateOf<LocalLibraryItem?>(null) }
    var pendingRecovery by remember { mutableStateOf<ProcessingCheckpoint?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val processingGate = remember { ProcessingRunGate() }
    val plusStore = remember(context) { AndroidPlusStore(context.applicationContext) }
    val dubbingState = remember(context) { DubbingPreferenceState(DubbingPreferencesStore(context)) }
    var activeProcessingConfig by remember { mutableStateOf<ProcessingConfig?>(null) }
    val sourceLanguage = dubbingState.sourceLanguage
    val targetLanguage = dubbingState.targetLanguage
    val translationMode = dubbingState.translationMode
    val speakerMode = dubbingState.speakerMode
    val voiceCloningEnabled = dubbingState.voiceCloningEnabled
    val dubbingMode = dubbingState.dubbingMode
    val subtitleMode = dubbingState.subtitleMode
    val playbackSpeed = dubbingState.playbackSpeed
    val preferredVoiceLabel = dubbingState.preferredVoiceLabel
    val currentProcessingConfig = dubbingState.processingConfig
    val cycleSourceLanguage: () -> Unit = dubbingState::cycleSourceLanguage
    val cycleTargetLanguage: () -> Unit = dubbingState::cycleTargetLanguage
    val cycleTranslationMode: () -> Unit = dubbingState::cycleTranslationMode
    val cycleSpeakerMode: () -> Unit = dubbingState::cycleSpeakerMode
    val setVoiceCloningEnabled: (Boolean) -> Unit = dubbingState::updateVoiceCloningConsent
    val cycleDubbingMode: () -> Unit = dubbingState::cycleDubbingMode
    val cycleSubtitleMode: () -> Unit = dubbingState::cycleSubtitleMode
    val cycleVoice: () -> Unit = dubbingState::cycleVoice
    val cyclePlaybackSpeed: () -> Unit = dubbingState::cyclePlaybackSpeed
    val uiPreferences = remember(context) { context.getSharedPreferences("lingoplay_ui", Context.MODE_PRIVATE) }
    var wifiOnly by rememberSaveable { mutableStateOf(uiPreferences.getBoolean("wifi_only", true)) }
    var highContrast by rememberSaveable { mutableStateOf(uiPreferences.getBoolean("high_contrast", false)) }
    val stage19Models = rememberStage19ModelLifecycleState(context)
    val modelInstallState = stage19Models.speechState
    val neuralVoiceInstallState = stage19Models.neuralState
    val speakerModelInstallState = stage19Models.speakerState
    val cloningModelInstallState = stage19Models.cloningState
    val downloadedTranslationModelCodes = stage19Models.downloadedTranslationModelCodes
    val translationModelBusyCode = stage19Models.translationModelBusyCode
    val translationModelError = stage19Models.translationModelError
    var uiLanguageName by rememberSaveable {
        mutableStateOf(uiPreferences.getString("ui_language", UiLanguage.ENGLISH.name) ?: UiLanguage.ENGLISH.name)
    }
    val uiLanguage = runCatching { UiLanguage.valueOf(uiLanguageName) }.getOrDefault(UiLanguage.ENGLISH)
    val stage = Stage.valueOf(stageName)
    val tab = Tab.valueOf(tabName)
    val preparationState = MediaPreparationState.valueOf(mediaState)
    val asrPhase = ASRPhase.valueOf(asrPhaseName)
    val speakerPhase = SpeakerPhase.valueOf(speakerPhaseName)
    val translationPhase = TranslationPhase.valueOf(translationPhaseName)
    val ttsPhase = TTSPhase.valueOf(ttsPhaseName)
    val mixPhase = MixPhase.valueOf(mixPhaseName)
    val modelInstalled = stage19Models.speechInstalled
    val speakerModelInstalled = stage19Models.speakerInstalled
    val startModelInstall: () -> Unit = {
        stage19Models.installSpeech(wifiOnly) {
            if (stageName == Stage.PROCESSING.name && asrPhaseName == ASRPhase.MODEL_MISSING.name) {
                asrPhaseName = ASRPhase.IDLE.name
            }
        }
    }
    val cancelModelInstall: () -> Unit = stage19Models::cancelSpeech
    val deleteModel: () -> Unit = {
        stage19Models.deleteSpeech(
            canDelete = asrPhase != ASRPhase.LOADING_MODEL && asrPhase != ASRPhase.TRANSCRIBING,
        )
    }
    val refreshVoiceOptions: suspend () -> Unit = {
        dubbingState.updateOfflineVoices(OfflineDubbingTTSService.availableVoices(context))
    }
    val startNeuralVoiceInstall: () -> Unit = {
        stage19Models.installNeural(wifiOnly, refreshVoiceOptions)
    }
    val cancelNeuralVoiceInstall: () -> Unit = stage19Models::cancelNeural
    val deleteNeuralVoice: () -> Unit = {
        stage19Models.deleteNeural(
            canDelete = ttsPhase != TTSPhase.SYNTHESIZING,
            onChanged = refreshVoiceOptions,
        )
    }

    val startSpeakerModelInstall: () -> Unit = {
        stage19Models.installSpeaker(wifiOnly) {
            if (stageName == Stage.PROCESSING.name && speakerPhaseName == SpeakerPhase.MODEL_MISSING.name) {
                speakerPhaseName = SpeakerPhase.IDLE.name
            }
        }
    }
    val cancelSpeakerModelInstall: () -> Unit = stage19Models::cancelSpeaker
    val deleteSpeakerModel: () -> Unit = {
        stage19Models.deleteSpeaker(canDelete = speakerPhase != SpeakerPhase.ANALYZING)
    }

    val startCloningModelInstall: () -> Unit = { stage19Models.installCloning(wifiOnly) }
    val cancelCloningModelInstall: () -> Unit = stage19Models::cancelCloning
    val deleteCloningModel: () -> Unit = {
        stage19Models.deleteCloning(canDelete = ttsPhase != TTSPhase.SYNTHESIZING)
    }

    val toggleTranslationModel: (String) -> Unit = { code ->
        stage19Models.toggleTranslationModel(
            code = code,
            wifiOnly = wifiOnly,
            canManage = translationPhaseName != TranslationPhase.TRANSLATING.name,
        )
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scope.launch {
            processingGate.run {
                preparedAudioFile?.delete()
                preparedAudioFile = null
                ProcessingCheckpointStore.clear(context, deleteMedia = true)
                pendingRecovery = null
                activeProcessingConfig = null
                LocalMediaRepository.deleteOwnedImport(context, selectedMedia)
                mediaState = MediaPreparationState.IMPORTING.name
                mediaError = null
                asrPhaseName = ASRPhase.IDLE.name
                asrTranscript = null
                asrError = null
                speakerPhaseName = SpeakerPhase.IDLE.name
                speakerDocument = null
                speakerError = null
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
                activeLibraryItem = null
                try {
                    selectedMedia = LocalMediaRepository.importMedia(context, uri)
                    LocalDiagnostics.record(context, "import_completed")
                    mediaState = MediaPreparationState.READY.name
                    stageName = Stage.PREPARE.name
                } catch (error: Throwable) {
                    LocalDiagnostics.record(context, "import_failed")
                    mediaState = MediaPreparationState.FAILED.name
                    mediaError = error.message ?: "Unable to inspect this local video."
                }
            }
        }
    }

    DisposableEffect(plusStore) {
        onDispose { plusStore.stop() }
    }

    LaunchedEffect(Unit) {
        TTSCachePolicy.purgeAllSessions(context)
        plusStore.start()
        libraryItems = LocalLibraryStore.load(context)
        pendingRecovery = ProcessingCheckpointStore.load(context)
        refreshVoiceOptions()
        stage19Models.refreshTranslationModels()
        if (pendingRecovery != null) LocalDiagnostics.record(context, "recovery_available")
        if (stageName == Stage.SPLASH.name) {
            delay(900)
            stageName = Stage.HOME.name
        }
    }

    val processingCoordinator = remember(context) {
        AndroidProcessingCoordinator(
            runtime = DefaultAndroidProcessingRuntime(context.applicationContext),
            translationConfigured = BuildConfig.TRANSLATION_API_BASE_URL.isNotBlank(),
        )
    }

    LaunchedEffect(stageName, modelInstalled, speakerModelInstalled) {
        processingGate.run {
            val media = selectedMedia
            val reusableAudio = preparedAudioFile?.takeIf(File::isFile)
                ?.takeIf { mediaState == MediaPreparationState.AUDIO_READY.name }
            val runConfig = activeProcessingConfig ?: currentProcessingConfig.also { activeProcessingConfig = it }
            val canProcess = mediaState == MediaPreparationState.READY.name || reusableAudio != null
            if (stageName != Stage.PROCESSING.name || media == null || !canProcess) return@run

            mediaError = null
            asrError = null
            speakerError = null
            translationError = null
            ttsError = null
            mixError = null
            val outcome = processingCoordinator.run(
                media = media,
                reusableAudio = reusableAudio,
                config = runConfig,
            ) { event ->
                withContext(Dispatchers.Main.immediate) {
                    when (event) {
                        ProcessingEvent.ExtractingAudio -> mediaState = MediaPreparationState.EXTRACTING_AUDIO.name
                        is ProcessingEvent.AudioReady -> {
                            preparedAudioFile = event.file
                            mediaState = MediaPreparationState.AUDIO_READY.name
                        }
                        ProcessingEvent.AsrLoadingModel -> {
                            asrError = null
                            asrPhaseName = ASRPhase.LOADING_MODEL.name
                        }
                        ProcessingEvent.AsrTranscribing -> asrPhaseName = ASRPhase.TRANSCRIBING.name
                        is ProcessingEvent.TranscriptReady -> {
                            asrTranscript = event.transcript
                            asrPhaseName = ASRPhase.COMPLETED.name
                        }
                        ProcessingEvent.DiarizationStarted -> {
                            speakerError = null
                            speakerPhaseName = SpeakerPhase.ANALYZING.name
                        }
                        is ProcessingEvent.DiarizationReady -> {
                            speakerDocument = event.document
                            speakerPhaseName = SpeakerPhase.COMPLETED.name
                        }
                        ProcessingEvent.TranslationStarted -> {
                            translationError = null
                            translationPhaseName = TranslationPhase.TRANSLATING.name
                        }
                        is ProcessingEvent.TranslationProgress -> {
                            translationBatch = event.batch
                            translationBatchTotal = event.total
                        }
                        is ProcessingEvent.TranslationReady -> {
                            translationDocument = event.document
                            translationPhaseName = TranslationPhase.COMPLETED.name
                        }
                        ProcessingEvent.TtsStarted -> {
                            ttsError = null
                            ttsPhaseName = TTSPhase.SYNTHESIZING.name
                        }
                        is ProcessingEvent.TtsProgress -> {
                            ttsSegment = event.segment
                            ttsSegmentTotal = event.total
                        }
                        is ProcessingEvent.TtsReady -> {
                            dubSpeechDocument = event.document
                            ttsPhaseName = TTSPhase.COMPLETED.name
                        }
                        is ProcessingEvent.MixChanged -> {
                            mixError = null
                            mixPhaseName = event.phase.name
                        }
                    }
                }
            }

            when (outcome) {
                ProcessingOutcome.ModelMissing -> asrPhaseName = ASRPhase.MODEL_MISSING.name
                ProcessingOutcome.SpeakerModelMissing -> speakerPhaseName = SpeakerPhase.MODEL_MISSING.name
                ProcessingOutcome.CloningModelMissing -> {
                    ttsPhaseName = TTSPhase.FAILED.name
                    ttsError = "Voice Cloning model required. Install the optional local model in Settings; cloning never falls back to cloud."
                }
                ProcessingOutcome.TranslationEndpointMissing -> translationPhaseName = TranslationPhase.ENDPOINT_MISSING.name
                ProcessingOutcome.VoiceMissing -> ttsPhaseName = TTSPhase.VOICE_MISSING.name
                is ProcessingOutcome.Failed -> when (outcome.step) {
                    ProcessingFailureStep.AUDIO -> {
                        mediaState = MediaPreparationState.FAILED.name
                        mediaError = outcome.message
                    }
                    ProcessingFailureStep.ASR -> {
                        asrPhaseName = ASRPhase.FAILED.name
                        asrError = outcome.message
                    }
                    ProcessingFailureStep.DIARIZATION -> {
                        speakerPhaseName = SpeakerPhase.FAILED.name
                        speakerError = outcome.message
                    }
                    ProcessingFailureStep.TRANSLATION -> {
                        translationPhaseName = TranslationPhase.FAILED.name
                        translationError = outcome.message
                    }
                    ProcessingFailureStep.TTS -> {
                        ttsPhaseName = TTSPhase.FAILED.name
                        ttsError = outcome.message
                    }
                    ProcessingFailureStep.MIX -> {
                        mixPhaseName = MixPhase.FAILED.name
                        mixError = outcome.message
                    }
                }
                is ProcessingOutcome.Completed -> {
                    pendingRecovery = null
                    libraryItems = LocalLibraryStore.load(context)
                    activeLibraryItem = outcome.saved
                    activeProcessingConfig = null
                    selectedMedia = LocalLibraryStore.importedMedia(outcome.saved)
                    translationDocument = outcome.saved.asTranslationDocument()
                    dubSpeechDocument = outcome.dub
                    mixResult = outcome.saved.asProcessedResult()
                    mixPhaseName = MixPhase.COMPLETED.name
                    stageName = Stage.PLAYER.name
                }
            }
        }
    }

    val backFromPrepare: () -> Unit = {
        preparedAudioFile?.delete()
        preparedAudioFile = null
        ProcessingCheckpointStore.clear(context, deleteMedia = true)
        LocalMediaRepository.deleteOwnedImport(context, selectedMedia)
        selectedMedia = null
        activeProcessingConfig = null
        mediaState = MediaPreparationState.IDLE.name
        stageName = Stage.HOME.name
    }
    val backFromProcessing: () -> Unit = {
        pendingRecovery = ProcessingCheckpointStore.load(context)
        stageName = Stage.HOME.name
    }
    val backToHome: () -> Unit = { stageName = Stage.HOME.name }

    BackHandler(enabled = stage != Stage.HOME && stage != Stage.SPLASH) {
        when (stage) {
            Stage.PREPARE -> backFromPrepare()
            Stage.PROCESSING -> backFromProcessing()
            Stage.PLAYER, Stage.PLUS, Stage.ABOUT -> backToHome()
            Stage.SPLASH, Stage.HOME -> Unit
        }
    }

    LingoPlayTheme(highContrast = highContrast) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            if (highContrast) {
                                listOf(Color.Black, LpBackground, Color(0xFF061018))
                            } else {
                                listOf(Color(0xFF0B0716), LpBackground, Color(0xFF04131C))
                            },
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
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        translationMode = translationMode,
                        voiceLabel = preferredVoiceLabel,
                        dubbingMode = dubbingMode,
                        subtitleMode = subtitleMode,
                        cleanBackgroundAvailable = CleanBackgroundCapability.isAvailable,
                        onSourceLanguage = cycleSourceLanguage,
                        onTargetLanguage = cycleTargetLanguage,
                        onTranslationMode = cycleTranslationMode,
                        onVoice = cycleVoice,
                        onDubbingMode = cycleDubbingMode,
                        onSubtitleMode = cycleSubtitleMode,
                        onBack = backFromPrepare,
                        onEdit = { mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                        onTranslate = {
                            val media = selectedMedia
                            if (media == null) {
                                mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                            } else {
                                val runConfig = currentProcessingConfig
                                activeProcessingConfig = runConfig
                                ProcessingCheckpointStore.save(context, media, preparedAudioFile, runConfig)
                                pendingRecovery = null
                                stageName = Stage.PROCESSING.name
                            }
                        },
                    )

                    Stage.PROCESSING -> ProcessingScreen(
                        mediaState = preparationState,
                        mediaError = mediaError,
                        asrPhase = asrPhase,
                        transcript = asrTranscript,
                        asrError = asrError,
                        speakerPhase = speakerPhase,
                        speakerDocument = speakerDocument,
                        speakerError = speakerError,
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
                        modelInstallState = modelInstallState,
                        speakerModelInstallState = speakerModelInstallState,
                        onInstallModel = startModelInstall,
                        onCancelModel = cancelModelInstall,
                        onInstallSpeakerModel = startSpeakerModelInstall,
                        onCancelSpeakerModel = cancelSpeakerModelInstall,
                        onBack = backFromProcessing,
                    )

                    Stage.PLAYER -> PlayerScreen(
                        media = selectedMedia,
                        processed = mixResult,
                        translation = translationDocument,
                        subtitleMode = subtitleMode,
                        dubbingMode = activeLibraryItem?.dubbingMode,
                        playbackSpeed = playbackSpeed,
                        onSubtitleMode = cycleSubtitleMode,
                        onPlaybackSpeed = cyclePlaybackSpeed,
                        onEnterPip = {
                            val activity = context as? Activity
                            if (activity != null && processedSupportsPip(mixResult)) {
                                activity.enterPictureInPictureMode(
                                    PictureInPictureParams.Builder()
                                        .setAspectRatio(Rational(16, 9))
                                        .build(),
                                )
                            }
                        },
                        onShare = activeLibraryItem?.let { item ->
                            {
                                context.startActivity(Intent.createChooser(LocalLibraryStore.shareIntent(context, item), "Share dubbed video"))
                            }
                        },
                        onBack = backToHome,
                    )

                    Stage.HOME -> when (tab) {
                        Tab.HOME -> HomeScreen(
                            language = uiLanguage,
                            items = libraryItems,
                            recovery = pendingRecovery,
                            onPlus = { stageName = Stage.PLUS.name },
                            onImport = { mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                            onResumeRecovery = { checkpoint ->
                                LocalDiagnostics.record(context, "recovery_resumed")
                                selectedMedia = checkpoint.media
                                preparedAudioFile = checkpoint.preparedAudioFile
                                activeProcessingConfig = checkpoint.config ?: currentProcessingConfig
                                mediaState = if (checkpoint.canResumeFromAudio) MediaPreparationState.AUDIO_READY.name else MediaPreparationState.READY.name
                                mediaError = null
                                asrPhaseName = ASRPhase.IDLE.name
                                asrTranscript = null
                                asrError = null
                                speakerPhaseName = SpeakerPhase.IDLE.name
                                speakerDocument = null
                                speakerError = null
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
                                pendingRecovery = null
                                stageName = Stage.PROCESSING.name
                            },
                            onDiscardRecovery = {
                                scope.launch {
                                    processingGate.run {
                                        LocalDiagnostics.record(context, "recovery_discarded")
                                        ProcessingCheckpointStore.clear(context, deleteMedia = true)
                                        pendingRecovery = null
                                        activeProcessingConfig = null
                                    }
                                }
                            },
                            onOpenItem = { item ->
                                activeLibraryItem = item
                                selectedMedia = LocalLibraryStore.importedMedia(item)
                                mixResult = item.asProcessedResult()
                                translationDocument = item.asTranslationDocument()
                                stageName = Stage.PLAYER.name
                            },
                        )

                        Tab.LIBRARY -> LibraryScreen(
                            language = uiLanguage,
                            items = libraryItems,
                            onOpenItem = { item ->
                                activeLibraryItem = item
                                selectedMedia = LocalLibraryStore.importedMedia(item)
                                mixResult = item.asProcessedResult()
                                translationDocument = item.asTranslationDocument()
                                stageName = Stage.PLAYER.name
                            },
                            onShare = { item ->
                                context.startActivity(Intent.createChooser(LocalLibraryStore.shareIntent(context, item), "Share dubbed video"))
                            },
                            onDelete = { item ->
                                scope.launch {
                                    LocalLibraryStore.delete(context, item)
                                    libraryItems = LocalLibraryStore.load(context)
                                    if (activeLibraryItem?.id == item.id) activeLibraryItem = null
                                }
                            },
                        )


                        Tab.SETTINGS -> SettingsScreen(
                            language = uiLanguage,
                            highContrast = highContrast,
                            wifiOnly = wifiOnly,
                            subtitleMode = subtitleMode,
                            targetLanguage = targetLanguage,
                            translationMode = translationMode,
                            speakerMode = speakerMode,
                            voiceCloningEnabled = voiceCloningEnabled,
                            voiceLabel = preferredVoiceLabel,
                            isPlus = plusStore.isPlus,
                            modelInstallState = modelInstallState,
                            canDeleteModel = asrPhase != ASRPhase.LOADING_MODEL && asrPhase != ASRPhase.TRANSCRIBING,
                            neuralVoiceInstallState = neuralVoiceInstallState,
                            canDeleteNeuralVoice = ttsPhase != TTSPhase.SYNTHESIZING,
                            speakerModelInstallState = speakerModelInstallState,
                            canDeleteSpeakerModel = speakerPhase != SpeakerPhase.ANALYZING,
                            cloningModelInstallState = cloningModelInstallState,
                            canDeleteCloningModel = ttsPhase != TTSPhase.SYNTHESIZING,
                            downloadedTranslationModelCodes = downloadedTranslationModelCodes,
                            translationModelBusyCode = translationModelBusyCode,
                            translationModelError = translationModelError,
                            canManageTranslationModels = translationPhase != TranslationPhase.TRANSLATING,
                            onInstallModel = startModelInstall,
                            onCancelModel = cancelModelInstall,
                            onDeleteModel = deleteModel,
                            onInstallNeuralVoice = startNeuralVoiceInstall,
                            onCancelNeuralVoice = cancelNeuralVoiceInstall,
                            onDeleteNeuralVoice = deleteNeuralVoice,
                            onInstallSpeakerModel = startSpeakerModelInstall,
                            onCancelSpeakerModel = cancelSpeakerModelInstall,
                            onDeleteSpeakerModel = deleteSpeakerModel,
                            onInstallCloningModel = startCloningModelInstall,
                            onCancelCloningModel = cancelCloningModelInstall,
                            onDeleteCloningModel = deleteCloningModel,
                            onToggleTranslationModel = toggleTranslationModel,
                            onToggleAppearance = {
                                highContrast = !highContrast
                                uiPreferences.edit { putBoolean("high_contrast", highContrast) }
                            },
                            onToggleLanguage = {
                                val next = if (uiLanguage == UiLanguage.ENGLISH) UiLanguage.VIETNAMESE else UiLanguage.ENGLISH
                                uiLanguageName = next.name
                                uiPreferences.edit { putString("ui_language", next.name) }
                            },
                            onWifiOnlyChange = {
                                wifiOnly = it
                                uiPreferences.edit { putBoolean("wifi_only", it) }
                            },
                            onSubtitleMode = cycleSubtitleMode,
                            onTargetLanguage = cycleTargetLanguage,
                            onTranslationMode = cycleTranslationMode,
                            onSpeakerMode = cycleSpeakerMode,
                            onVoiceCloningEnabled = setVoiceCloningEnabled,
                            onVoice = cycleVoice,
                            onPlus = { stageName = Stage.PLUS.name },
                            onAbout = { stageName = Stage.ABOUT.name },
                        )
                    }

                    Stage.PLUS -> PlusScreen(
                        language = uiLanguage,
                        store = plusStore,
                        onBack = backToHome,
                    )

                    Stage.ABOUT -> AboutScreen(
                        language = uiLanguage,
                        diagnosticsCount = LocalDiagnostics.recent(context).size,
                        cleanBackgroundAvailable = CleanBackgroundCapability.isAvailable,
                        onBack = backToHome,
                    )
                }
            }

            if (stage == Stage.HOME) {
                BottomNavigation(
                    language = uiLanguage,
                    selected = tab,
                    onSelected = { tabName = it.name },
                    onImport = { mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                )
            }
                }
            }
        }
    }
}
