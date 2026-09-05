package com.lingoplay.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID


data class LocalLibraryItem(
    val id: String,
    val title: String,
    val durationMs: Long,
    val createdAtEpochMs: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    val dubbingMode: DubbingModePreset?,
    val videoFile: File,
    val segments: List<TranslationSegment>,
    val translationMode: TranslationMode = TranslationMode.CLOUD,
    val speakerMode: SpeakerMode = SpeakerMode.SINGLE,
    val speakerVoiceMap: Map<String, String> = emptyMap(),
) {
    val languagePair: String
        get() = "${sourceLanguage.uppercase()} → ${targetLanguage.uppercase()}"

    val durationText: String get() = MediaFormatting.duration(durationMs)
    val sizeBytes: Long get() = videoFile.length().coerceAtLeast(0L)

    fun asProcessedResult(): LocalDubMediaResult = LocalDubMediaResult(videoFile, durationMs)

    fun asTranslationDocument(): TranslationDocument? = segments
        .takeIf { it.isNotEmpty() }
        ?.let { TranslationDocument(sourceLanguage, targetLanguage, it, translationMode, speakerVoiceMap) }
}

object LocalLibraryStore {
    private const val ROOT = "lingoplay/library"
    private const val VIDEO_NAME = "video.mp4"
    private const val METADATA_NAME = "metadata.json"

    suspend fun load(context: Context): List<LocalLibraryItem> = withContext(Dispatchers.IO) {
        val root = root(context)
        root.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory }
            .mapNotNull(::readItem)
            .filter { it.videoFile.isFile && it.videoFile.length() > 0L }
            .sortedByDescending { it.createdAtEpochMs }
            .toList()
    }

    suspend fun save(
        context: Context,
        media: LocalMediaItem,
        result: LocalDubMediaResult,
        translation: TranslationDocument?,
        dubbingMode: DubbingModePreset,
    ): LocalLibraryItem = withContext(Dispatchers.IO) {
        check(result.remuxedVideoFile.isFile && result.remuxedVideoFile.length() > 0L) {
            "The processed video is missing and cannot be saved."
        }
        val id = UUID.randomUUID().toString()
        val directory = File(root(context), id).apply { mkdirs() }
        val destination = File(directory, VIDEO_NAME)
        var success = false
        try {
            result.remuxedVideoFile.copyTo(destination, overwrite = true)
            check(destination.isFile && destination.length() > 0L) { "Saving the processed video produced no output." }
            val item = LocalLibraryItem(
                id = id,
                title = media.name.substringBeforeLast('.', media.name).ifBlank { "Dubbed video" },
                durationMs = result.durationMs,
                createdAtEpochMs = System.currentTimeMillis(),
                sourceLanguage = translation?.sourceLanguage?.ifBlank { "und" } ?: "und",
                targetLanguage = translation?.targetLanguage?.ifBlank { "vi" } ?: "vi",
                dubbingMode = dubbingMode,
                videoFile = destination,
                segments = translation?.segments.orEmpty(),
                translationMode = translation?.mode ?: TranslationMode.CLOUD,
                speakerMode = if (translation?.segments.orEmpty().any { it.speakerId != null || it.overlappingSpeakerIds.isNotEmpty() }) {
                    SpeakerMode.MULTI
                } else {
                    SpeakerMode.SINGLE
                },
                speakerVoiceMap = translation?.speakerVoiceMap.orEmpty(),
            )
            File(directory, METADATA_NAME).writeText(item.toJson().toString(), Charsets.UTF_8)
            success = true
            item
        } finally {
            if (!success) directory.deleteRecursively()
        }
    }

    suspend fun delete(context: Context, item: LocalLibraryItem): Boolean = withContext(Dispatchers.IO) {
        val root = root(context).canonicalFile
        val directory = item.videoFile.parentFile?.canonicalFile ?: return@withContext false
        if (directory.parentFile != root) return@withContext false
        directory.deleteRecursively()
    }

    fun shareIntent(context: Context, item: LocalLibraryItem): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", item.videoFile)
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, item.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun importedMedia(item: LocalLibraryItem): LocalMediaItem = LocalMediaItem(
        uri = Uri.fromFile(item.videoFile),
        name = item.title,
        durationMs = item.durationMs,
        sizeBytes = item.sizeBytes,
        hasAudioTrack = true,
    )

    fun totalBytes(items: List<LocalLibraryItem>): Long = items.sumOf { it.sizeBytes }

    private fun root(context: Context): File {
        val externalMovies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val directory = externalMovies?.let { File(it, "LingoPlay") } ?: File(context.filesDir, ROOT)
        return directory.apply { mkdirs() }
    }

    private fun readItem(directory: File): LocalLibraryItem? = runCatching {
        val metadata = File(directory, METADATA_NAME)
        val video = File(directory, VIDEO_NAME)
        if (!metadata.isFile || !video.isFile) return@runCatching null
        val json = JSONObject(metadata.readText(Charsets.UTF_8))
        val segmentsJson = json.optJSONArray("segments") ?: JSONArray()
        val segments = buildList {
            for (index in 0 until segmentsJson.length()) {
                val segment = segmentsJson.getJSONObject(index)
                add(
                    TranslationSegment(
                        id = segment.getString("id"),
                        startMs = segment.getInt("startMs"),
                        endMs = segment.getInt("endMs"),
                        sourceText = segment.optString("sourceText"),
                        translatedText = segment.optString("translatedText"),
                        speakerId = segment.optString("speakerId").takeIf(String::isNotBlank),
                        overlappingSpeakerIds = segment.optJSONArray("overlappingSpeakerIds")?.let { ids ->
                            buildList { for (i in 0 until ids.length()) ids.optString(i).takeIf(String::isNotBlank)?.let(::add) }
                        }.orEmpty(),
                    ),
                )
            }
        }
        LocalLibraryItem(
            id = json.optString("id", directory.name),
            title = json.optString("title", "Dubbed video"),
            durationMs = json.optLong("durationMs", 0L),
            createdAtEpochMs = json.optLong("createdAtEpochMs", directory.lastModified()),
            sourceLanguage = json.optString("sourceLanguage", "und"),
            targetLanguage = json.optString("targetLanguage", "vi"),
            dubbingMode = json.optString("dubbingMode").takeIf(String::isNotBlank)?.let { value ->
                runCatching { DubbingModePreset.valueOf(value) }.getOrNull()
            },
            videoFile = video,
            segments = segments,
            translationMode = json.optString("translationMode")
                .takeIf(String::isNotBlank)
                ?.let { value -> runCatching { TranslationMode.valueOf(value) }.getOrNull() }
                ?: TranslationMode.CLOUD,
            speakerMode = json.optString("speakerMode")
                .takeIf(String::isNotBlank)
                ?.let { value -> runCatching { SpeakerMode.valueOf(value) }.getOrNull() }
                ?: SpeakerMode.SINGLE,
            speakerVoiceMap = json.optJSONObject("speakerVoiceMap")?.let { mapJson ->
                buildMap { mapJson.keys().forEach { key -> mapJson.optString(key).takeIf(String::isNotBlank)?.let { put(key, it) } } }
            }.orEmpty(),
        )
    }.getOrNull()

    private fun LocalLibraryItem.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("durationMs", durationMs)
        put("createdAtEpochMs", createdAtEpochMs)
        put("sourceLanguage", sourceLanguage)
        put("targetLanguage", targetLanguage)
        dubbingMode?.let { put("dubbingMode", it.name) }
        put("translationMode", translationMode.name)
        put("speakerMode", speakerMode.name)
        put("speakerVoiceMap", JSONObject().apply { speakerVoiceMap.forEach { (speakerId, voiceId) -> put(speakerId, voiceId) } })
        put("segments", JSONArray().apply {
            segments.forEach { segment ->
                put(JSONObject().apply {
                    put("id", segment.id)
                    put("startMs", segment.startMs)
                    put("endMs", segment.endMs)
                    put("sourceText", segment.sourceText)
                    put("translatedText", segment.translatedText)
                    put("speakerId", segment.speakerId ?: "")
                    put("overlappingSpeakerIds", JSONArray(segment.overlappingSpeakerIds))
                })
            }
        })
    }
}
