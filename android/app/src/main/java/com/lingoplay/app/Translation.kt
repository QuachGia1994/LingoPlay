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
)

data class TranslationSegment(
    val id: String,
    val startMs: Int,
    val endMs: Int,
    val sourceText: String,
    val translatedText: String,
)

data class TranslationDocument(
    val sourceLanguage: String,
    val targetLanguage: String,
    val segments: List<TranslationSegment>,
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

object TranslationBatching {
    fun fromTranscript(transcript: ASRTranscript): List<TranslationSourceSegment> {
        if (transcript.segments.isNotEmpty()) {
            return transcript.segments.mapIndexed { index, segment ->
                val startMs = max(0, (segment.startSeconds * 1000f).roundToInt())
                val endMs = max(startMs + 1, (segment.endSeconds * 1000f).roundToInt())
                TranslationSourceSegment(
                    id = "s$index",
                    startMs = startMs,
                    endMs = endMs,
                    text = segment.text,
                )
            }
        }
        return listOf(TranslationSourceSegment("s0", 0, 1, transcript.text))
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
        onProgress: suspend (batch: Int, total: Int) -> Unit = { _, _ -> },
    ): TranslationDocument = withContext(Dispatchers.IO) {
        val endpoint = endpointBaseUrl.trim().trimEnd('/')
        if (endpoint.isEmpty()) throw IllegalStateException("Translation backend is not configured.")

        val sourceLanguage = transcript.language.trim().ifEmpty { "und" }
        val sourceSegments = TranslationBatching.fromTranscript(transcript)
        val batches = TranslationBatching.batches(sourceSegments)
        val translatedById = linkedMapOf<String, String>()

        batches.forEachIndexed { index, batch ->
            translatedById.putAll(postBatch(endpoint, sourceLanguage, targetLanguage, batch))
            onProgress(index + 1, batches.size)
        }

        val translatedSegments = sourceSegments.map { source ->
            val text = translatedById[source.id]?.trim().orEmpty()
            check(text.isNotEmpty()) { "Translation backend returned an incomplete response." }
            TranslationSegment(source.id, source.startMs, source.endMs, source.text, text)
        }
        TranslationDocument(sourceLanguage, targetLanguage, translatedSegments)
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
