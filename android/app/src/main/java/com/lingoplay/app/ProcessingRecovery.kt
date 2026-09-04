package com.lingoplay.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File


data class ProcessingCheckpoint(
    val media: LocalMediaItem,
    val preparedAudioFile: File?,
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
        )
    }.getOrNull()

    fun save(context: Context, media: LocalMediaItem, preparedAudioFile: File? = null) {
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
            put("updatedAtEpochMs", System.currentTimeMillis())
        }
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        if (file.exists()) file.delete()
        check(temp.renameTo(file)) { "Unable to persist processing recovery checkpoint." }
    }

    fun clear(context: Context, deleteMedia: Boolean) {
        val checkpoint = load(context)
        checkpointFile(context).delete()
        checkpoint?.preparedAudioFile?.delete()
        if (deleteMedia) LocalMediaRepository.deleteOwnedImport(checkpoint?.media)
    }

    private fun checkpointFile(context: Context): File = File(File(context.filesDir, ROOT), FILE_NAME)
}
