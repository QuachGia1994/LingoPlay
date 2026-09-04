package com.lingoplay.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


internal class ProcessingRunGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

data class ProcessingCheckpoint(
    val media: LocalMediaItem,
    val preparedAudioFile: File?,
    val config: ProcessingConfig?,
) {
    val canResumeFromAudio: Boolean get() = preparedAudioFile?.isFile == true
}

object ProcessingCheckpointStore {
    private const val ROOT = "lingoplay/recovery"
    private const val FILE_NAME = "processing.json"

    fun load(context: Context): ProcessingCheckpoint? = runCatching {
        val file = checkpointFile(context)
        if (!file.isFile) return@runCatching null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val mediaFile = File(json.getString("mediaPath"))
        if (!mediaFile.isFile || mediaFile.length() <= 0L) {
            file.delete()
            return@runCatching null
        }
        val audioPath = json.optString("preparedAudioPath").takeIf(String::isNotBlank)
        val audio = audioPath?.let(::File)?.takeIf(File::isFile)
        ProcessingCheckpoint(
            media = LocalMediaItem(
                uri = Uri.fromFile(mediaFile),
                name = json.optString("name", mediaFile.name),
                durationMs = json.optLong("durationMs", 0L),
                sizeBytes = json.optLong("sizeBytes", mediaFile.length()),
                hasAudioTrack = json.optBoolean("hasAudioTrack", true),
            ),
            preparedAudioFile = audio,
            config = json.optJSONObject("config")?.let(::configFromJson),
        )
    }.getOrNull()

    fun save(
        context: Context,
        media: LocalMediaItem,
        preparedAudioFile: File? = null,
        config: ProcessingConfig? = null,
    ) {
        val mediaPath = media.uri.takeIf { it.scheme == "file" }?.path ?: return
        val mediaFile = File(mediaPath)
        if (!mediaFile.isFile) return
        val file = checkpointFile(context)
        file.parentFile?.mkdirs()
        val json = JSONObject().apply {
            put("mediaPath", mediaFile.absolutePath)
            put("name", media.name)
            put("durationMs", media.durationMs)
            put("sizeBytes", media.sizeBytes)
            put("hasAudioTrack", media.hasAudioTrack)
            put("preparedAudioPath", preparedAudioFile?.takeIf(File::isFile)?.absolutePath ?: "")
            if (config != null) put("config", configToJson(config))
            put("updatedAtEpochMs", System.currentTimeMillis())
        }
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Throwable) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun clear(context: Context, deleteMedia: Boolean) {
        val checkpoint = load(context)
        checkpointFile(context).delete()
        checkpoint?.preparedAudioFile?.delete()
        if (deleteMedia) LocalMediaRepository.deleteOwnedImport(context, checkpoint?.media)
    }

    private fun configToJson(config: ProcessingConfig): JSONObject {
        val record = config.toRecord()
        return JSONObject().apply {
            put("sourceLanguage", record.sourceLanguage)
            put("targetLanguage", record.targetLanguage)
            put("preferredVoiceId", record.preferredVoiceId ?: "")
            put("dubbingMode", record.dubbingMode)
            put("subtitleMode", record.subtitleMode)
            put("translationMode", record.translationMode)
        }
    }

    private fun configFromJson(json: JSONObject): ProcessingConfig? = ProcessingConfigRecord(
        sourceLanguage = json.getString("sourceLanguage"),
        targetLanguage = json.getString("targetLanguage"),
        preferredVoiceId = json.optString("preferredVoiceId").takeIf(String::isNotBlank),
        dubbingMode = json.getString("dubbingMode"),
        subtitleMode = json.optString("subtitleMode", SubtitleMode.BILINGUAL.name),
        translationMode = json.optString("translationMode", TranslationMode.CLOUD.name),
    ).toConfig()

    private fun checkpointFile(context: Context): File = File(File(context.filesDir, ROOT), FILE_NAME)
}
