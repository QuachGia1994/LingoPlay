package com.lingoplay.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

data class TranslationSourceSegment(
    val id: String,
    val startMs: Int,
    val endMs: Int,
    val text: String,
    val speakerId: String? = null,
    val overlappingSpeakerIds: List<String> = emptyList(),
)

data class TranslationSegment(
    val id: String,
    val startMs: Int,
    val endMs: Int,
    val sourceText: String,
    val translatedText: String,
    val speakerId: String? = null,
    val overlappingSpeakerIds: List<String> = emptyList(),
)

data class TranslationDocument(
    val sourceLanguage: String,
    val targetLanguage: String,
    val segments: List<TranslationSegment>,
    val mode: TranslationMode = TranslationMode.CLOUD,
    val speakerVoiceMap: Map<String, String> = emptyMap(),
) {
    val translatedText: String get() = segments.joinToString(" ") { it.translatedText }
}

enum class TranslationPhase {
    IDLE,
    ENDPOINT_MISSING,
    TRANSLATING,
    COMPLETED,
    FAILED,
}

object TranslationTextPolicy {
    private val controlTag = Regex("<[^>\\r\\n]{1,96}>")
    private val bracketCue = Regex("\\[[^\\r\\n\\]]{1,96}\\]")
    private val whitespace = Regex("\\s+")
    private val word = Regex("[a-z]+(?:'[a-z]+)?")
    private val commonEnglishWords = setOf(
        "a", "and", "are", "can", "do", "have", "how", "i", "in", "is", "it",
        "me", "of", "please", "that", "the", "this", "to", "we", "what", "you",
    )

    fun speechText(text: String): String = text
        .replace(controlTag, " ")
        .replace(bracketCue, " ")
        .trim()
        .replace(whitespace, " ")

    fun sourceLanguage(reported: String, text: String): String {
        val cleaned = speechText(text)
        val totalLetters = cleaned.count(Char::isLetter)
        val latinLetters = cleaned.count { it in 'a'..'z' || it in 'A'..'Z' }
        val englishHits = word.findAll(cleaned.lowercase()).count { it.value in commonEnglishWords }
        val stronglyEnglish = latinLetters >= 20 &&
            totalLetters > 0 &&
            latinLetters.toDouble() / totalLetters.toDouble() >= 0.75 &&
            englishHits >= 2
        if (stronglyEnglish) return "en"
        return reported.trim().lowercase().substringBefore('-').ifEmpty { "und" }
    }
}

object TranslationBatching {
    fun fromTranscript(transcript: ASRTranscript): List<TranslationSourceSegment> {
        val timed = transcript.segments.mapIndexedNotNull { index, segment ->
            val text = TranslationTextPolicy.speechText(segment.text)
            if (text.isEmpty()) return@mapIndexedNotNull null
            val startMs = max(0, (segment.startSeconds * 1000f).roundToInt())
            val endMs = max(startMs + 1, (segment.endSeconds * 1000f).roundToInt())
            TranslationSourceSegment(
                id = "s$index",
                startMs = startMs,
                endMs = endMs,
                text = text,
                speakerId = segment.speakerId,
                overlappingSpeakerIds = segment.overlappingSpeakerIds,
            )
        }
        if (timed.isNotEmpty()) return timed
        val fallback = TranslationTextPolicy.speechText(transcript.text)
        return if (fallback.isEmpty()) emptyList() else listOf(TranslationSourceSegment("s0", 0, 1, fallback))
    }

    fun batches(
        segments: List<TranslationSourceSegment>,
        maxSegments: Int = 80,
        maxChars: Int = 10_000,
    ): List<List<TranslationSourceSegment>> {
        require(maxSegments > 0 && maxChars > 0)
        val result = mutableListOf<List<TranslationSourceSegment>>()
        var current = mutableListOf<TranslationSourceSegment>()
        var chars = 0

        for (segment in segments) {
            val countOverflow = current.size >= maxSegments
            val charOverflow = chars + segment.text.length > maxChars
            if (current.isNotEmpty() && (countOverflow || charOverflow)) {
                result += current
                current = mutableListOf()
                chars = 0
            }
            current += segment
            chars += segment.text.length
        }
        if (current.isNotEmpty()) result += current
        return result
    }
}

object TranslationService {
    suspend fun translate(
        transcript: ASRTranscript,
        targetLanguage: String = "vi",
        endpointBaseUrl: String = BuildConfig.TRANSLATION_API_BASE_URL,
        mode: TranslationMode = TranslationMode.CLOUD,
        onProgress: suspend (batch: Int, total: Int) -> Unit = { _, _ -> },
    ): TranslationDocument = if (mode == TranslationMode.OFFLINE) {
        OfflineTranslationService.translate(transcript, targetLanguage, onProgress)
    } else withContext(Dispatchers.IO) {
        val endpoint = endpointBaseUrl.trim().trimEnd('/')
        if (endpoint.isEmpty()) throw IllegalStateException("Translation backend is not configured.")

        val sourceSegments = TranslationBatching.fromTranscript(transcript)
        check(sourceSegments.isNotEmpty()) { "No translatable speech remains after removing non-speech markers." }
        val sourceText = sourceSegments.joinToString(" ") { it.text }
        val sourceLanguage = TranslationTextPolicy.sourceLanguage(transcript.language, sourceText)
        val targetBaseLanguage = targetLanguage.trim().lowercase().substringBefore('-')
        if (sourceLanguage == targetBaseLanguage) {
            val copied = sourceSegments.map { source ->
                TranslationSegment(
                    source.id,
                    source.startMs,
                    source.endMs,
                    source.text,
                    source.text,
                    source.speakerId,
                    source.overlappingSpeakerIds,
                )
            }
            onProgress(copied.size, copied.size)
            return@withContext TranslationDocument(
                sourceLanguage,
                targetLanguage,
                copied,
                TranslationMode.CLOUD,
            )
        }
        val batches = TranslationBatching.batches(sourceSegments)
        val translatedById = linkedMapOf<String, String>()

        batches.forEachIndexed { index, batch ->
            translatedById.putAll(postBatch(endpoint, sourceLanguage, targetLanguage, batch))
            onProgress(index + 1, batches.size)
        }

        val translatedSegments = sourceSegments.map { source ->
            val text = TranslationTextPolicy.speechText(translatedById[source.id].orEmpty())
            check(text.isNotEmpty()) { "Translation backend returned an incomplete response." }
            TranslationSegment(
                source.id,
                source.startMs,
                source.endMs,
                source.text,
                text,
                source.speakerId,
                source.overlappingSpeakerIds,
            )
        }
        TranslationDocument(sourceLanguage, targetLanguage, translatedSegments, TranslationMode.CLOUD)
    }

    private fun postBatch(
        endpoint: String,
        sourceLanguage: String,
        targetLanguage: String,
        segments: List<TranslationSourceSegment>,
    ): Map<String, String> {
        val body = JSONObject().apply {
            put("sourceLanguage", sourceLanguage)
            put("targetLanguage", targetLanguage)
            put("segments", JSONArray().apply {
                segments.forEach { segment ->
                    put(JSONObject().apply {
                        put("id", segment.id)
                        put("startMs", segment.startMs)
                        put("endMs", segment.endMs)
                        put("text", segment.text)
                    })
                }
            })
        }

        val connection = (URL("$endpoint/v1/translate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { output -> output.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val responseJson = runCatching { JSONObject(responseText) }.getOrNull()

            if (status !in 200..299) {
                val code = responseJson?.optString("error")?.ifEmpty { null } ?: "server_error"
                throw IllegalStateException("Translation failed ($status): $code")
            }
            if (responseJson == null) throw IllegalStateException("Translation backend returned invalid JSON.")

            val translations = responseJson.optJSONArray("translations")
                ?: throw IllegalStateException("Translation backend returned no translations.")
            check(translations.length() == segments.size) { "Translation backend returned an incomplete response." }

            return buildMap {
                for (index in 0 until translations.length()) {
                    val item = translations.getJSONObject(index)
                    val id = item.getString("id")
                    val text = item.getString("text").trim()
                    check(text.isNotEmpty()) { "Translation backend returned empty text." }
                    put(id, text)
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
