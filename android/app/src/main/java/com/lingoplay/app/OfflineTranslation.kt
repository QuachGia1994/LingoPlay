package com.lingoplay.app

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

object OfflineTranslationLanguagePolicy {
    val supportedCodes = listOf("en", "vi", "ja", "zh")

    fun displayName(code: String): String = when (normalized(code)) {
        "en" -> "English"
        "vi" -> "Vietnamese"
        "ja" -> "Japanese"
        "zh" -> "Chinese"
        else -> code.uppercase()
    }

    fun mlKitLanguage(code: String): String = TranslateLanguage.fromLanguageTag(normalized(code))
        ?.takeIf { it in TranslateLanguage.getAllLanguages() }
        ?: throw IllegalArgumentException("Offline translation does not support ${displayName(code)}.")

    fun requiredModelCodes(sourceLanguage: String, targetLanguage: String): Set<String> {
        val source = normalized(sourceLanguage)
        val target = normalized(targetLanguage)
        if (source == target) return emptySet()
        mlKitLanguage(source)
        mlKitLanguage(target)
        return setOf(source, target).filterTo(linkedSetOf()) { it != "en" }
    }

    private fun normalized(code: String): String =
        code.trim().lowercase().substringBefore('-')
}

object OfflineTranslationModelManager {
    private val manager: RemoteModelManager
        get() = RemoteModelManager.getInstance()

    suspend fun downloadedCodes(): Set<String> = withContext(Dispatchers.IO) {
        val downloaded = manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
        OfflineTranslationLanguagePolicy.supportedCodes.filterTo(linkedSetOf()) { code ->
            code == "en" || downloaded.contains(model(code))
        }
    }

    suspend fun download(code: String, wifiOnly: Boolean) = withContext(Dispatchers.IO) {
        require(code != "en") { "English translation support is built in." }
        val conditions = DownloadConditions.Builder()
            .apply { if (wifiOnly) requireWifi() }
            .build()
        manager.download(model(code), conditions).await()
    }

    suspend fun delete(code: String) = withContext(Dispatchers.IO) {
        require(code != "en") { "English translation support is built in." }
        manager.deleteDownloadedModel(model(code)).await()
    }

    private fun model(code: String): TranslateRemoteModel =
        TranslateRemoteModel.Builder(OfflineTranslationLanguagePolicy.mlKitLanguage(code)).build()
}

object OfflineTranslationService {
    suspend fun translate(
        transcript: ASRTranscript,
        targetLanguage: String,
        onProgress: suspend (segment: Int, total: Int) -> Unit,
    ): TranslationDocument = withContext(Dispatchers.Default) {
        val sourceSegments = TranslationBatching.fromTranscript(transcript)
        check(sourceSegments.isNotEmpty()) {
            "No translatable speech remains after removing non-speech markers."
        }
        val sourceText = sourceSegments.joinToString(" ") { it.text }
        val sourceLanguage = TranslationTextPolicy.sourceLanguage(transcript.language, sourceText)
        val requiredModels = OfflineTranslationLanguagePolicy.requiredModelCodes(sourceLanguage, targetLanguage)

        if (requiredModels.isEmpty()) {
            val copied = sourceSegments.map {
                TranslationSegment(it.id, it.startMs, it.endMs, it.text, it.text)
            }
            onProgress(copied.size, copied.size)
            return@withContext TranslationDocument(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                segments = copied,
                mode = TranslationMode.OFFLINE,
            )
        }

        val missing = requiredModels - OfflineTranslationModelManager.downloadedCodes()
        check(missing.isEmpty()) {
            val names = missing.joinToString { OfflineTranslationLanguagePolicy.displayName(it) }
            "Offline translation model missing: $names. Install it in Settings; LingoPlay will not switch to cloud automatically."
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(OfflineTranslationLanguagePolicy.mlKitLanguage(sourceLanguage))
            .setTargetLanguage(OfflineTranslationLanguagePolicy.mlKitLanguage(targetLanguage))
            .build()
        val translator = Translation.getClient(options)
        try {
            val translated = sourceSegments.mapIndexed { index, source ->
                coroutineContext.ensureActive()
                val result = withTimeout(60_000) { translator.translate(source.text).await() }
                val cleaned = TranslationTextPolicy.speechText(result)
                check(cleaned.isNotEmpty()) { "Offline translation returned empty text." }
                onProgress(index + 1, sourceSegments.size)
                TranslationSegment(
                    id = source.id,
                    startMs = source.startMs,
                    endMs = source.endMs,
                    sourceText = source.text,
                    translatedText = cleaned,
                )
            }
            TranslationDocument(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                segments = translated,
                mode = TranslationMode.OFFLINE,
            )
        } finally {
            translator.close()
        }
    }
}
