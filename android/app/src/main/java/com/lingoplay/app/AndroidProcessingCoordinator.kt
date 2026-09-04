package com.lingoplay.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import java.io.File

internal enum class ProcessingFailureStep {
    AUDIO,
    ASR,
    TRANSLATION,
    TTS,
    MIX,
}

internal sealed interface ProcessingEvent {
    data object ExtractingAudio : ProcessingEvent
    data class AudioReady(val file: File) : ProcessingEvent
    data object AsrLoadingModel : ProcessingEvent
    data object AsrTranscribing : ProcessingEvent
    data class TranscriptReady(val transcript: ASRTranscript) : ProcessingEvent
    data object TranslationStarted : ProcessingEvent
    data class TranslationProgress(val batch: Int, val total: Int) : ProcessingEvent
    data class TranslationReady(val document: TranslationDocument) : ProcessingEvent
    data object TtsStarted : ProcessingEvent
    data class TtsProgress(val segment: Int, val total: Int) : ProcessingEvent
    data class TtsReady(val document: DubSpeechDocument) : ProcessingEvent
    data class MixChanged(val phase: MixPhase) : ProcessingEvent
}

internal sealed interface ProcessingOutcome {
    data class Completed(
        val saved: LocalLibraryItem,
        val rendered: LocalDubMediaResult,
        val translation: TranslationDocument,
        val dub: DubSpeechDocument,
    ) : ProcessingOutcome

    data object ModelMissing : ProcessingOutcome
    data object TranslationEndpointMissing : ProcessingOutcome
    data object VoiceMissing : ProcessingOutcome
    data class Failed(val step: ProcessingFailureStep, val message: String) : ProcessingOutcome
}

internal interface AndroidProcessingRuntime {
    suspend fun extractAudio(media: LocalMediaItem): File
    fun findWhisperModel(): SherpaWhisperModel?
    suspend fun transcribe(audioFile: File, model: SherpaWhisperModel, sourceLanguageCode: String?): ASRTranscript
    suspend fun translate(
        transcript: ASRTranscript,
        targetLanguage: String,
        onProgress: suspend (Int, Int) -> Unit,
    ): TranslationDocument

    suspend fun synthesize(
        document: TranslationDocument,
        preferredVoiceId: String?,
        onProgress: suspend (Int, Int) -> Unit,
    ): DubSpeechDocument

    suspend fun render(
        media: LocalMediaItem,
        dub: DubSpeechDocument,
        mode: DubbingModePreset,
        onPhase: suspend (MixPhase) -> Unit,
    ): LocalDubMediaResult

    suspend fun save(
        media: LocalMediaItem,
        result: LocalDubMediaResult,
        translation: TranslationDocument,
        mode: DubbingModePreset,
    ): LocalLibraryItem

    fun saveCheckpoint(media: LocalMediaItem, preparedAudioFile: File, config: ProcessingConfig)
    fun clearCheckpoint()
    fun record(event: String)
}

internal class DefaultAndroidProcessingRuntime(private val context: Context) : AndroidProcessingRuntime {
    override suspend fun extractAudio(media: LocalMediaItem): File =
        LocalMediaRepository.extractAudio(context, media)

    override fun findWhisperModel(): SherpaWhisperModel? = ASRModelStore.findWhisperModel(context)

    override suspend fun transcribe(
        audioFile: File,
        model: SherpaWhisperModel,
        sourceLanguageCode: String?,
    ): ASRTranscript = SherpaWhisperSpeechRecognizer.transcribe(
        context = context,
        audioFile = audioFile,
        model = model,
        sourceLanguageCode = sourceLanguageCode,
    )

    override suspend fun translate(
        transcript: ASRTranscript,
        targetLanguage: String,
        onProgress: suspend (Int, Int) -> Unit,
    ): TranslationDocument = TranslationService.translate(
        transcript = transcript,
        targetLanguage = targetLanguage,
        onProgress = onProgress,
    )

    override suspend fun synthesize(
        document: TranslationDocument,
        preferredVoiceId: String?,
        onProgress: suspend (Int, Int) -> Unit,
    ): DubSpeechDocument = SystemVietnameseTTSService.synthesize(
        context = context,
        document = document,
        preferredVoiceName = preferredVoiceId,
        onProgress = onProgress,
    )

    override suspend fun render(
        media: LocalMediaItem,
        dub: DubSpeechDocument,
        mode: DubbingModePreset,
        onPhase: suspend (MixPhase) -> Unit,
    ): LocalDubMediaResult = TimelineMixService.render(
        context = context,
        media = media,
        dub = dub,
        mode = mode,
        onPhase = onPhase,
    )

    override suspend fun save(
        media: LocalMediaItem,
        result: LocalDubMediaResult,
        translation: TranslationDocument,
        mode: DubbingModePreset,
    ): LocalLibraryItem = LocalLibraryStore.save(
        context = context,
        media = media,
        result = result,
        translation = translation,
        dubbingMode = mode,
    )

    override fun saveCheckpoint(media: LocalMediaItem, preparedAudioFile: File, config: ProcessingConfig) {
        ProcessingCheckpointStore.save(context, media, preparedAudioFile, config)
    }

    override fun clearCheckpoint() {
        ProcessingCheckpointStore.clear(context, deleteMedia = true)
    }

    override fun record(event: String) {
        LocalDiagnostics.record(context, event)
    }
}

internal class AndroidProcessingCoordinator(
    private val runtime: AndroidProcessingRuntime,
    private val translationConfigured: Boolean,
) {
    suspend fun run(
        media: LocalMediaItem,
        reusableAudio: File?,
        config: ProcessingConfig,
        onEvent: suspend (ProcessingEvent) -> Unit,
    ): ProcessingOutcome = try {
        val audioFile = prepareAudio(media, reusableAudio, config, onEvent)
            ?: return ProcessingOutcome.Failed(ProcessingFailureStep.AUDIO, "Audio preparation failed.")

        val model = findWhisperModel() ?: return ProcessingOutcome.ModelMissing
        val transcript = transcribe(audioFile, model, config, onEvent)
            ?: return ProcessingOutcome.Failed(ProcessingFailureStep.ASR, "Speech recognition failed.")

        if (!translationConfigured) return ProcessingOutcome.TranslationEndpointMissing
        val translation = translate(transcript, config, onEvent)
            ?: return ProcessingOutcome.Failed(ProcessingFailureStep.TRANSLATION, "Translation failed.")

        val dubOutcome = synthesize(translation, config, onEvent)
        if (dubOutcome.voiceMissing) return ProcessingOutcome.VoiceMissing
        val dub = dubOutcome.document
            ?: return ProcessingOutcome.Failed(
                ProcessingFailureStep.TTS,
                dubOutcome.errorMessage ?: "Offline speech synthesis failed.",
            )

        renderAndSave(media, translation, dub, config, onEvent)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ProcessingStepException) {
        ProcessingOutcome.Failed(failure.step, failure.message)
    }

    private suspend fun prepareAudio(
        media: LocalMediaItem,
        reusableAudio: File?,
        config: ProcessingConfig,
        onEvent: suspend (ProcessingEvent) -> Unit,
    ): File? {
        if (reusableAudio?.isFile == true) {
            onEvent(ProcessingEvent.AudioReady(reusableAudio))
            return reusableAudio
        }
        return try {
            onEvent(ProcessingEvent.ExtractingAudio)
            runtime.extractAudio(media).also { file ->
                runtime.saveCheckpoint(media, file, config)
                onEvent(ProcessingEvent.AudioReady(file))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            runtime.record("audio_preparation_failed")
            throw ProcessingStepException(ProcessingFailureStep.AUDIO, error.message ?: "Audio preparation failed.", error)
        }
    }

    private fun findWhisperModel(): SherpaWhisperModel? = try {
        runtime.findWhisperModel()
    } catch (error: Throwable) {
        runtime.record("asr_model_lookup_failed")
        throw ProcessingStepException(
            ProcessingFailureStep.ASR,
            error.message ?: "Speech recognition model lookup failed.",
            error,
        )
    }

    private suspend fun transcribe(
        audioFile: File,
        model: SherpaWhisperModel,
        config: ProcessingConfig,
        onEvent: suspend (ProcessingEvent) -> Unit,
    ): ASRTranscript? = try {
        onEvent(ProcessingEvent.AsrLoadingModel)
        onEvent(ProcessingEvent.AsrTranscribing)
        runtime.transcribe(audioFile, model, config.sourceLanguage.code).also { transcript ->
            onEvent(ProcessingEvent.TranscriptReady(transcript))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        runtime.record("asr_failed")
        throw ProcessingStepException(ProcessingFailureStep.ASR, error.message ?: "Speech recognition failed.", error)
    }

    private suspend fun translate(
        transcript: ASRTranscript,
        config: ProcessingConfig,
        onEvent: suspend (ProcessingEvent) -> Unit,
    ): TranslationDocument? = try {
        onEvent(ProcessingEvent.TranslationStarted)
        runtime.translate(transcript, config.targetLanguage.code) { batch, total ->
            onEvent(ProcessingEvent.TranslationProgress(batch, total))
        }.also { document ->
            onEvent(ProcessingEvent.TranslationReady(document))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        runtime.record("translation_failed")
        throw ProcessingStepException(ProcessingFailureStep.TRANSLATION, error.message ?: "Translation failed.", error)
    }

    private class ProcessingStepException(
        val step: ProcessingFailureStep,
        override val message: String,
        cause: Throwable,
    ) : RuntimeException(message, cause)

    private data class DubOutcome(
        val document: DubSpeechDocument?,
        val voiceMissing: Boolean,
        val errorMessage: String? = null,
    )

    private suspend fun synthesize(
        translation: TranslationDocument,
        config: ProcessingConfig,
        onEvent: suspend (ProcessingEvent) -> Unit,
    ): DubOutcome = try {
        onEvent(ProcessingEvent.TtsStarted)
        val document = runtime.synthesize(translation, config.preferredVoiceId) { segment, total ->
            onEvent(ProcessingEvent.TtsProgress(segment, total))
        }
        onEvent(ProcessingEvent.TtsReady(document))
        DubOutcome(document, voiceMissing = false)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: OfflineTargetVoiceMissingException) {
        runtime.record("tts_voice_missing")
        DubOutcome(null, voiceMissing = true)
    } catch (error: Throwable) {
        runtime.record("tts_failed")
        DubOutcome(null, voiceMissing = false, errorMessage = error.message)
    }

    private suspend fun renderAndSave(
        media: LocalMediaItem,
        translation: TranslationDocument,
        dub: DubSpeechDocument,
        config: ProcessingConfig,
        onEvent: suspend (ProcessingEvent) -> Unit,
    ): ProcessingOutcome = try {
        val rendered = runtime.render(media, dub, config.dubbingMode) { phase ->
            onEvent(ProcessingEvent.MixChanged(phase))
        }
        val saved = runtime.save(media, rendered, translation, config.dubbingMode)
        runtime.clearCheckpoint()
        runtime.record("processing_completed")
        ProcessingOutcome.Completed(saved, rendered, translation, dub)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        runtime.record("mix_failed")
        ProcessingOutcome.Failed(
            ProcessingFailureStep.MIX,
            error.message ?: "Local audio mixing or video remux failed.",
        )
    }
}
