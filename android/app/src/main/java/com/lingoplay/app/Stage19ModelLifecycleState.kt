package com.lingoplay.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
internal class Stage19ModelLifecycleState(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    var speechState by mutableStateOf<ModelInstallState>(ASRModelInstaller.state(appContext))
        private set
    var neuralState by mutableStateOf<ModelInstallState>(NeuralVoicePackInstaller.state(appContext))
        private set
    var speakerState by mutableStateOf<ModelInstallState>(SpeakerDiarizationModelInstaller.state(appContext))
        private set
    var cloningState by mutableStateOf<ModelInstallState>(VoiceCloningModelInstaller.state(appContext))
        private set
    var sourceSeparationState by mutableStateOf<ModelInstallState>(SourceSeparationModelInstaller.state(appContext))
        private set
    var downloadedTranslationModelCodes by mutableStateOf<Set<String>>(emptySet())
        private set
    var translationModelBusyCode by mutableStateOf<String?>(null)
        private set
    var translationModelError by mutableStateOf<String?>(null)
        private set

    private var speechJob: Job? = null
    private var neuralJob: Job? = null
    private var speakerJob: Job? = null
    private var cloningJob: Job? = null
    private var sourceSeparationJob: Job? = null
    private var translationModelJob: Job? = null

    val speechInstalled: Boolean
        get() = speechState is ModelInstallState.Installed
    val speakerInstalled: Boolean
        get() = speakerState is ModelInstallState.Installed

    fun installSpeech(wifiOnly: Boolean, onInstalled: () -> Unit) {
        if (speechJob != null) return
        speechJob = scope.launch {
            try {
                val installed = ASRModelInstaller.install(appContext, wifiOnly) { progress ->
                    withContext(Dispatchers.Main.immediate) { speechState = progress }
                }
                speechState = ModelInstallState.Installed(
                    installed.encoder.length() + installed.decoder.length() + installed.tokens.length(),
                )
                onInstalled()
            } catch (cancelled: CancellationException) {
                speechState = ASRModelInstaller.state(appContext)
                throw cancelled
            } catch (error: Throwable) {
                speechState = ModelInstallState.Failed(error.message ?: "Speech AI installation failed.")
            } finally {
                speechJob = null
            }
        }
    }

    fun cancelSpeech() {
        speechJob?.cancel()
    }

    fun deleteSpeech(canDelete: Boolean) {
        if (!canDelete) return
        speechJob?.cancel()
        speechJob = null
        ASRModelInstaller.deleteInstalled(appContext)
        speechState = ModelInstallState.NotInstalled
    }

    fun installNeural(wifiOnly: Boolean, onChanged: suspend () -> Unit) {
        if (neuralJob != null) return
        neuralJob = scope.launch {
            try {
                NeuralVoicePackInstaller.install(appContext, wifiOnly) { progress ->
                    withContext(Dispatchers.Main.immediate) { neuralState = progress }
                }
                neuralState = NeuralVoicePackInstaller.state(appContext)
                onChanged()
            } catch (cancelled: CancellationException) {
                neuralState = NeuralVoicePackInstaller.state(appContext)
                throw cancelled
            } catch (error: Throwable) {
                neuralState = ModelInstallState.Failed(error.message ?: "Neural Voice installation failed.")
            } finally {
                neuralJob = null
            }
        }
    }

    fun cancelNeural() {
        neuralJob?.cancel()
    }

    fun deleteNeural(canDelete: Boolean, onChanged: suspend () -> Unit) {
        if (!canDelete) return
        neuralJob?.cancel()
        neuralJob = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NeuralVoicePackInstaller.deleteInstalled(appContext) }
                neuralState = ModelInstallState.NotInstalled
                onChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                neuralState = ModelInstallState.Failed(
                    error.message ?: "Unable to delete the installed Neural Voice pack.",
                )
            }
        }
    }

    suspend fun refreshTranslationModels() {
        try {
            downloadedTranslationModelCodes = OfflineTranslationModelManager.downloadedCodes()
            translationModelError = null
        } catch (error: Throwable) {
            translationModelError = error.message ?: "Unable to inspect offline translation models."
        }
    }

    fun toggleTranslationModel(code: String, wifiOnly: Boolean, canManage: Boolean) {
        if (!canManage || translationModelJob != null) return
        translationModelBusyCode = code
        translationModelError = null
        translationModelJob = scope.launch {
            try {
                if (code in downloadedTranslationModelCodes) {
                    OfflineTranslationModelManager.delete(code)
                } else {
                    OfflineTranslationModelManager.download(code, wifiOnly)
                }
                downloadedTranslationModelCodes = OfflineTranslationModelManager.downloadedCodes()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                translationModelError = error.message ?: "Unable to update the offline translation model."
                downloadedTranslationModelCodes = runCatching {
                    OfflineTranslationModelManager.downloadedCodes()
                }.getOrDefault(downloadedTranslationModelCodes)
            } finally {
                translationModelBusyCode = null
                translationModelJob = null
            }
        }
    }

    fun installSpeaker(wifiOnly: Boolean, onInstalled: () -> Unit) {
        if (speakerJob != null) return
        speakerJob = scope.launch {
            try {
                SpeakerDiarizationModelInstaller.install(appContext, wifiOnly) { progress ->
                    withContext(Dispatchers.Main.immediate) { speakerState = progress }
                }
                speakerState = SpeakerDiarizationModelInstaller.state(appContext)
                onInstalled()
            } catch (cancelled: CancellationException) {
                speakerState = SpeakerDiarizationModelInstaller.state(appContext)
                throw cancelled
            } catch (error: Throwable) {
                speakerState = ModelInstallState.Failed(error.message ?: "Speaker AI installation failed.")
            } finally {
                speakerJob = null
            }
        }
    }

    fun cancelSpeaker() {
        speakerJob?.cancel()
    }

    fun deleteSpeaker(canDelete: Boolean) {
        if (!canDelete) return
        speakerJob?.cancel()
        speakerJob = null
        runCatching { SpeakerDiarizationModelInstaller.deleteInstalled(appContext) }
            .onSuccess { speakerState = ModelInstallState.NotInstalled }
            .onFailure { speakerState = ModelInstallState.Failed(it.message ?: "Unable to delete Speaker AI.") }
    }

    fun installCloning(wifiOnly: Boolean) {
        if (cloningJob != null) return
        cloningJob = scope.launch {
            try {
                VoiceCloningModelInstaller.install(appContext, wifiOnly) { progress ->
                    withContext(Dispatchers.Main.immediate) { cloningState = progress }
                }
                cloningState = VoiceCloningModelInstaller.state(appContext)
            } catch (cancelled: CancellationException) {
                cloningState = VoiceCloningModelInstaller.state(appContext)
                throw cancelled
            } catch (error: Throwable) {
                cloningState = ModelInstallState.Failed(error.message ?: "Voice Cloning installation failed.")
            } finally {
                cloningJob = null
            }
        }
    }

    fun cancelCloning() {
        cloningJob?.cancel()
    }

    fun deleteCloning(canDelete: Boolean) {
        if (!canDelete) return
        cloningJob?.cancel()
        cloningJob = null
        runCatching { VoiceCloningModelInstaller.deleteInstalled(appContext) }
            .onSuccess { cloningState = ModelInstallState.NotInstalled }
            .onFailure { cloningState = ModelInstallState.Failed(it.message ?: "Unable to delete Voice Cloning models.") }
    }

    fun installSourceSeparation(wifiOnly: Boolean) {
        if (sourceSeparationJob != null) return
        sourceSeparationJob = scope.launch {
            try {
                SourceSeparationModelInstaller.install(appContext, wifiOnly) { progress ->
                    withContext(Dispatchers.Main.immediate) { sourceSeparationState = progress }
                }
                sourceSeparationState = SourceSeparationModelInstaller.state(appContext)
            } catch (cancelled: CancellationException) {
                sourceSeparationState = SourceSeparationModelInstaller.state(appContext)
                throw cancelled
            } catch (error: Throwable) {
                sourceSeparationState = ModelInstallState.Failed(error.message ?: "Clean Background installation failed.")
            } finally {
                sourceSeparationJob = null
            }
        }
    }

    fun cancelSourceSeparation() {
        sourceSeparationJob?.cancel()
    }

    fun deleteSourceSeparation(canDelete: Boolean) {
        if (!canDelete) return
        sourceSeparationJob?.cancel()
        sourceSeparationJob = null
        runCatching { SourceSeparationModelInstaller.deleteInstalled(appContext) }
            .onSuccess { sourceSeparationState = ModelInstallState.NotInstalled }
            .onFailure { sourceSeparationState = ModelInstallState.Failed(it.message ?: "Unable to delete Clean Background model.") }
    }
}

@Composable
internal fun rememberStage19ModelLifecycleState(context: Context): Stage19ModelLifecycleState {
    val scope = rememberCoroutineScope()
    return remember(context.applicationContext, scope) {
        Stage19ModelLifecycleState(context.applicationContext, scope)
    }
}
