package com.lingoplay.app

import android.net.Uri
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
                listOf("extract", "checkpoint", "model", "asr:en", "translate:ja", "tts:ja-voice", "mix:SPEECH_FOCUS", "save:SPEECH_FOCUS", "clear", "record:processing_completed"),
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

    private class FakeRuntime(
        private val root: File,
        private val modelFailure: Throwable? = null,
    ) : AndroidProcessingRuntime {
        val calls = mutableListOf<String>()
        private val audio = File(root, "audio.wav").apply { writeBytes(byteArrayOf(1)) }
        private val encoder = File(root, "encoder.onnx").apply { writeBytes(byteArrayOf(1)) }
        private val decoder = File(root, "decoder.onnx").apply { writeBytes(byteArrayOf(1)) }
        private val tokens = File(root, "tokens.txt").apply { writeText("x") }
        private val dubAudio = File(root, "dub.wav").apply { writeBytes(byteArrayOf(1)) }
        private val video = File(root, "video.mp4").apply { writeBytes(byteArrayOf(1)) }

        override suspend fun extractAudio(media: LocalMediaItem): File {
            calls += "extract"
            return audio
        }

        override fun findWhisperModel(): SherpaWhisperModel? {
            calls += "model"
            modelFailure?.let { throw it }
            return SherpaWhisperModel(encoder, decoder, tokens)
        }

        override suspend fun transcribe(audioFile: File, model: SherpaWhisperModel, sourceLanguageCode: String?): ASRTranscript {
            calls += "asr:${sourceLanguageCode ?: "auto"}"
            return ASRTranscript("en", "hello", listOf(ASRSegment(0, 0f, 1f, "hello")))
        }

        override suspend fun translate(
            transcript: ASRTranscript,
            targetLanguage: String,
            onProgress: suspend (Int, Int) -> Unit,
        ): TranslationDocument {
            calls += "translate:$targetLanguage"
            onProgress(1, 1)
            return TranslationDocument("en", targetLanguage, listOf(TranslationSegment("s0", 0, 1_000, "hello", "こんにちは")))
        }

        override suspend fun synthesize(
            document: TranslationDocument,
            preferredVoiceId: String?,
            onProgress: suspend (Int, Int) -> Unit,
        ): DubSpeechDocument {
            calls += "tts:${preferredVoiceId ?: "auto"}"
            onProgress(1, 1)
            return DubSpeechDocument("voice", listOf(DubSpeechSegment("s0", 0, 1_000, dubAudio, 900, 100, 1f)))
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
