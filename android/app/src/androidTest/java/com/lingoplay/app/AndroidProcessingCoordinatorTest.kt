package com.lingoplay.app

import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AndroidProcessingCoordinatorTest {
    @Test
    fun successUsesOneConfigAndOrderedRuntimeBoundary() = runBlocking {
        val root = createTempDirectory("lingoplay-coordinator-").toFile()
        try {
            val runtime = FakeRuntime(root)
            val coordinator = AndroidProcessingCoordinator(runtime, translationConfigured = true)
            val media = LocalMediaItem(Uri.EMPTY, "sample.mp4", 2_000, 10, true)
            val config = ProcessingConfig(
                SourceLanguageChoice.ENGLISH,
                TargetLanguageChoice.JAPANESE,
                "ja-voice",
                DubbingModePreset.SPEECH_FOCUS,
                SubtitleMode.TRANSLATED,
            )
            val events = mutableListOf<ProcessingEvent>()

            val outcome = coordinator.run(media, reusableAudio = null, config = config) { events += it }

            assertTrue(outcome is ProcessingOutcome.Completed)
            assertEquals(
                listOf("extract", "checkpoint", "model", "asr:en", "translate:ja:CLOUD", "tts:ja-voice", "mix:SPEECH_FOCUS", "save:SPEECH_FOCUS", "clear", "record:processing_completed"),
                runtime.calls,
            )
            assertTrue(events.first() is ProcessingEvent.ExtractingAudio)
            assertTrue(events.last() is ProcessingEvent.MixChanged)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun modelLookupFailureIsAttributedToAsrInsteadOfEscapingCoordinator() = runBlocking {
        val root = createTempDirectory("lingoplay-coordinator-").toFile()
        try {
            val runtime = FakeRuntime(root, modelFailure = IllegalStateException("model registry unavailable"))
            val coordinator = AndroidProcessingCoordinator(runtime, translationConfigured = true)
            val media = LocalMediaItem(Uri.EMPTY, "sample.mp4", 2_000, 10, true)
            val config = ProcessingConfig(
                SourceLanguageChoice.AUTO,
                TargetLanguageChoice.VIETNAMESE,
                null,
                DubbingModePreset.BALANCED,
                SubtitleMode.BILINGUAL,
            )

            val outcome = coordinator.run(media, reusableAudio = null, config = config) { }

            assertTrue(outcome is ProcessingOutcome.Failed)
            val failed = outcome as ProcessingOutcome.Failed
            assertEquals(ProcessingFailureStep.ASR, failed.step)
            assertEquals("model registry unavailable", failed.message)
            assertEquals(
                listOf("extract", "checkpoint", "model", "record:asr_model_lookup_failed"),
                runtime.calls,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingTranslationEndpointStopsBeforeNetworkAndTts() = runBlocking {
        val root = createTempDirectory("lingoplay-coordinator-").toFile()
        try {
            val runtime = FakeRuntime(root)
            val coordinator = AndroidProcessingCoordinator(runtime, translationConfigured = false)
            val media = LocalMediaItem(Uri.EMPTY, "sample.mp4", 2_000, 10, true)
            val config = ProcessingConfig(
                SourceLanguageChoice.AUTO,
                TargetLanguageChoice.VIETNAMESE,
                null,
                DubbingModePreset.BALANCED,
                SubtitleMode.BILINGUAL,
            )

            val outcome = coordinator.run(media, reusableAudio = null, config = config) { }

            assertEquals(ProcessingOutcome.TranslationEndpointMissing, outcome)
            assertEquals(listOf("extract", "checkpoint", "model", "asr:auto"), runtime.calls)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun offlineModeDoesNotRequireCloudEndpointAndPreservesRoute() = runBlocking {
        val root = createTempDirectory("lingoplay-coordinator-").toFile()
        try {
            val runtime = FakeRuntime(root)
            val coordinator = AndroidProcessingCoordinator(runtime, translationConfigured = false)
            val media = LocalMediaItem(Uri.EMPTY, "sample.mp4", 2_000, 10, true)
            val config = ProcessingConfig(
                sourceLanguage = SourceLanguageChoice.ENGLISH,
                targetLanguage = TargetLanguageChoice.VIETNAMESE,
                preferredVoiceId = null,
                dubbingMode = DubbingModePreset.BALANCED,
                subtitleMode = SubtitleMode.BILINGUAL,
                translationMode = TranslationMode.OFFLINE,
            )

            val outcome = coordinator.run(media, reusableAudio = null, config = config) { }

            assertTrue(outcome is ProcessingOutcome.Completed)
            assertTrue("translate:vi:OFFLINE" in runtime.calls)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stage22CleanBackgroundMultiSpeakerCloningRestoresTimelineOnlyAfterReferenceAnalysis() = runBlocking {
        val root = createTempDirectory("lingoplay-stage22-composition-").toFile()
        try {
            val runtime = FakeRuntime(root, stage22Composition = true, timelineOffsetMs = 500)
            val coordinator = AndroidProcessingCoordinator(runtime, translationConfigured = true)
            val media = LocalMediaItem(Uri.EMPTY, "shifted.mp4", 3_000, 10, true)
            val config = ProcessingConfig(
                sourceLanguage = SourceLanguageChoice.ENGLISH,
                targetLanguage = TargetLanguageChoice.ENGLISH,
                preferredVoiceId = "en-a",
                dubbingMode = DubbingModePreset.BALANCED,
                subtitleMode = SubtitleMode.BILINGUAL,
                speakerMode = SpeakerMode.MULTI,
                voiceCloningEnabled = true,
                cleanBackgroundEnabled = true,
            )

            val outcome = coordinator.run(media, reusableAudio = null, config = config) { }

            assertTrue(outcome is ProcessingOutcome.Completed)
            assertEquals("vocals.wav", runtime.diarizationAudioName)
            assertEquals("vocals.wav", runtime.cloneReferenceAudioName)
            assertEquals(0f, requireNotNull(runtime.cloneReferenceTranscriptStartSeconds), 0.0001f)
            assertEquals(0.5f, requireNotNull(runtime.translatedTranscriptStartSeconds), 0.0001f)
            assertEquals(1, runtime.synthesisCloneReferenceCount)
            assertEquals("accompaniment.wav", runtime.renderBackgroundName)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancelledNativeReturnDeletesSessionBeforeCallerReceivesOutput() = runBlocking {
        val target = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        var session: File? = null
        val job = async {
            TTSCachePolicy.synthesizeInSession(target, "clone-tts") { root ->
                session = root
                File(root, "partial.wav").writeBytes(byteArrayOf(1, 2, 3))
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.cancel()
                "native result returned after cancellation"
            }
        }
        try { job.await() } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue("Native return must have allocated its session", session != null)
        assertTrue("Dispatcher cancellation discarded output without cleanup", session?.exists() == false)
    }

    @Test
    fun cancelledAudioResultCannotWriteCheckpoint() = runBlocking {
        val root = createTempDirectory("lingoplay-cancelled-").toFile()
        try {
            val runtime = FakeRuntime(root, cancelAfterExtract = true)
            val job = async {
                AndroidProcessingCoordinator(runtime, true).run(
                    LocalMediaItem(Uri.EMPTY, "sample.mp4", 2_000, 10, true), null,
                    ProcessingConfig(SourceLanguageChoice.AUTO, TargetLanguageChoice.ENGLISH, null,
                        DubbingModePreset.BALANCED, SubtitleMode.BILINGUAL),
                ) { }
            }
            try { job.await() } catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(listOf("extract"), runtime.calls)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun cancelledSeparatedResultIsCleanedBeforeCoordinatorAcceptsOwnership() = runBlocking {
        val root = createTempDirectory("lingoplay-separation-cancelled-").toFile()
        try {
            val runtime = FakeRuntime(root, cancelAfterSeparation = true)
            val job = async {
                AndroidProcessingCoordinator(runtime, true).run(
                    LocalMediaItem(Uri.EMPTY, "sample.mp4", 2_000, 10, true), null,
                    ProcessingConfig(
                        sourceLanguage = SourceLanguageChoice.AUTO,
                        targetLanguage = TargetLanguageChoice.ENGLISH,
                        preferredVoiceId = null,
                        dubbingMode = DubbingModePreset.BALANCED,
                        subtitleMode = SubtitleMode.BILINGUAL,
                        cleanBackgroundEnabled = true,
                    ),
                ) { }
            }
            try { job.await() } catch (_: kotlinx.coroutines.CancellationException) { }
            assertTrue("Cancelled separated stems must be deleted before ownership is lost.", runtime.separationRoot?.exists() == false)
            assertTrue("The separation runtime must have returned a concrete session.", "separate" in runtime.calls)
        } finally { root.deleteRecursively() }
    }

    private class FakeRuntime(
        private val root: File,
        private val modelFailure: Throwable? = null,
        private val cancelAfterExtract: Boolean = false,
        private val cancelAfterSeparation: Boolean = false,
        private val stage22Composition: Boolean = false,
        private val timelineOffsetMs: Int = 0,
    ) : AndroidProcessingRuntime {
        val calls = mutableListOf<String>()
        var separationRoot: File? = null
        var diarizationAudioName: String? = null
        var cloneReferenceAudioName: String? = null
        var cloneReferenceTranscriptStartSeconds: Float? = null
        var translatedTranscriptStartSeconds: Float? = null
        var synthesisCloneReferenceCount: Int = 0
        var renderBackgroundName: String? = null
        private val audio = File(root, "audio.wav").apply { writeBytes(byteArrayOf(1)) }
        private val encoder = File(root, "encoder.onnx").apply { writeBytes(byteArrayOf(1)) }
        private val decoder = File(root, "decoder.onnx").apply { writeBytes(byteArrayOf(1)) }
        private val tokens = File(root, "tokens.txt").apply { writeText("x") }
        private val dubAudio = File(root, "dub.wav").apply { writeBytes(byteArrayOf(1)) }
        private val video = File(root, "video.mp4").apply { writeBytes(byteArrayOf(1)) }

        override suspend fun extractAudio(media: LocalMediaItem): File {
            calls += "extract"
            if (cancelAfterExtract) kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.cancel()
            return audio
        }

        override suspend fun audioTimelineOffsetMs(media: LocalMediaItem): Int = timelineOffsetMs

        override fun findWhisperModel(): SherpaWhisperModel? {
            calls += "model"
            modelFailure?.let { throw it }
            return SherpaWhisperModel(encoder, decoder, tokens)
        }

        override suspend fun transcribe(
            audioFile: File,
            model: SherpaWhisperModel,
            sourceLanguageCode: String?,
            speakerMode: SpeakerMode,
        ): ASRTranscript {
            calls += "asr:${sourceLanguageCode ?: "auto"}"
            return if (stage22Composition) {
                ASRTranscript("en", "reference speech", listOf(ASRSegment(0, 0f, 2f, "reference speech")))
            } else {
                ASRTranscript("en", "hello", listOf(ASRSegment(0, 0f, 1f, "hello")))
            }
        }

        override fun findSpeakerModel(): SpeakerDiarizationModel? =
            if (stage22Composition) SpeakerDiarizationModel(encoder, decoder) else null

        override suspend fun diarize(
            audioFile: File,
            model: SpeakerDiarizationModel,
        ): SpeakerDiarizationDocument {
            diarizationAudioName = audioFile.name
            return if (stage22Composition) {
                SpeakerDiarizationDocument(listOf(SpeakerTurn(0, 2_000, "speaker_1")), listOf("speaker_1"))
            } else {
                SpeakerDiarizationDocument(emptyList(), emptyList())
            }
        }

        override suspend fun availableVoices(): List<OfflineVoiceOption> =
            if (stage22Composition) listOf(OfflineVoiceOption("en-a", "English A", "en")) else emptyList()

        override fun voiceCloningModelInstalled(): Boolean = stage22Composition

        override fun sourceSeparationAvailable(): Boolean = cancelAfterSeparation || stage22Composition

        override suspend fun separateAudio(audioFile: File): SeparatedAudioStems {
            calls += "separate"
            val session = File(root, "separated-session").apply { mkdirs() }
            separationRoot = session
            val voice = File(session, "vocals.wav").apply { writeBytes(byteArrayOf(1)) }
            val background = File(session, "accompaniment.wav").apply { writeBytes(byteArrayOf(2)) }
            if (cancelAfterSeparation) kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.cancel()
            return SeparatedAudioStems(voice, background, session)
        }

        override suspend fun buildVoiceCloneReferences(
            audioFile: File,
            transcript: ASRTranscript,
        ): Map<String, VoiceCloneReference> {
            cloneReferenceAudioName = audioFile.name
            cloneReferenceTranscriptStartSeconds = transcript.segments.firstOrNull()?.startSeconds
            return if (stage22Composition) {
                mapOf("speaker_1" to VoiceCloneReference(FloatArray(24_000), 24_000, "reference speech"))
            } else {
                emptyMap()
            }
        }

        override suspend fun translate(
            transcript: ASRTranscript,
            targetLanguage: String,
            mode: TranslationMode,
            onProgress: suspend (Int, Int) -> Unit,
        ): TranslationDocument {
            calls += "translate:$targetLanguage:${mode.name}"
            translatedTranscriptStartSeconds = transcript.segments.firstOrNull()?.startSeconds
            onProgress(1, 1)
            val startMs = ((transcript.segments.firstOrNull()?.startSeconds ?: 0f) * 1_000f).toInt()
            return TranslationDocument("en", targetLanguage, listOf(TranslationSegment("s0", startMs, startMs + 1_000, "hello", "hello")), mode)
        }

        override suspend fun synthesize(
            document: TranslationDocument,
            preferredVoiceId: String?,
            speakerVoiceMap: Map<String, String>,
            cloneReferences: Map<String, VoiceCloneReference>,
            onProgress: suspend (Int, Int) -> Unit,
        ): DubSpeechDocument {
            calls += "tts:${preferredVoiceId ?: "auto"}"
            synthesisCloneReferenceCount = cloneReferences.size
            onProgress(1, 1)
            val startMs = document.segments.firstOrNull()?.startMs ?: 0
            return DubSpeechDocument("voice", listOf(DubSpeechSegment("s0", startMs, startMs + 1_000, dubAudio, 900, 100, 1f)))
        }

        override suspend fun render(
            media: LocalMediaItem,
            dub: DubSpeechDocument,
            mode: DubbingModePreset,
            onPhase: suspend (MixPhase) -> Unit,
        ): LocalDubMediaResult {
            calls += "mix:${mode.name}"
            onPhase(MixPhase.RENDERING_AUDIO)
            onPhase(MixPhase.REMUXING)
            onPhase(MixPhase.COMPLETED)
            return LocalDubMediaResult(video, media.durationMs)
        }

        override suspend fun render(
            media: LocalMediaItem,
            dub: DubSpeechDocument,
            mode: DubbingModePreset,
            backgroundAudioFile: File?,
            onPhase: suspend (MixPhase) -> Unit,
        ): LocalDubMediaResult {
            renderBackgroundName = backgroundAudioFile?.name
            return render(media, dub, mode, onPhase)
        }

        override suspend fun save(
            media: LocalMediaItem,
            result: LocalDubMediaResult,
            translation: TranslationDocument,
            mode: DubbingModePreset,
        ): LocalLibraryItem {
            calls += "save:${mode.name}"
            return LocalLibraryItem("id", "sample", media.durationMs, 1, translation.sourceLanguage, translation.targetLanguage, mode, video, translation.segments)
        }

        override fun saveCheckpoint(media: LocalMediaItem, preparedAudioFile: File, config: ProcessingConfig) {
            calls += "checkpoint"
        }

        override fun clearCheckpoint() {
            calls += "clear"
        }

        override fun record(event: String) {
            calls += "record:$event"
        }
    }
}
