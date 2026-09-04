package com.lingoplay.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max

data class LocalMediaItem(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val hasAudioTrack: Boolean,
) {
    val durationText: String get() = MediaFormatting.duration(durationMs)
    val fileSizeText: String get() = MediaFormatting.bytes(sizeBytes)
}

enum class MediaPreparationState {
    IDLE,
    IMPORTING,
    READY,
    EXTRACTING_AUDIO,
    AUDIO_READY,
    FAILED,
}

sealed class LocalMediaException(message: String) : Exception(message) {
    data object NoAudioTrack : LocalMediaException("This video has no readable audio track.")
    data object CannotOpenSource : LocalMediaException("This local video cannot be opened.")
}

object LocalMediaRepository {
    suspend fun importMedia(context: Context, uri: Uri): LocalMediaItem = withContext(Dispatchers.IO) {
        val metadata = queryMetadata(context, uri)
        val sourceName = metadata.first ?: "Local video.mp4"
        val extension = sourceName.substringAfterLast('.', "mp4").take(8).ifBlank { "mp4" }
        val directory = File(context.cacheDir, "lingoplay/imported-media").apply { mkdirs() }
        val destination = File(directory, "${UUID.randomUUID()}.$extension")
        var success = false
        try {
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                destination.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: throw LocalMediaException.CannotOpenSource
            val ownedUri = Uri.fromFile(destination)
            val durationMs = readDuration(context, ownedUri)
            val hasAudio = hasAudioTrack(context, ownedUri)
            success = true
            LocalMediaItem(
                uri = ownedUri,
                name = sourceName,
                durationMs = durationMs,
                sizeBytes = destination.length(),
                hasAudioTrack = hasAudio,
            )
        } finally {
            if (!success) destination.delete()
        }
    }

    suspend fun inspect(context: Context, uri: Uri): LocalMediaItem = withContext(Dispatchers.IO) {
        val metadata = queryMetadata(context, uri)
        val durationMs = readDuration(context, uri)
        val hasAudio = hasAudioTrack(context, uri)
        LocalMediaItem(uri, metadata.first ?: "Local video", durationMs, metadata.second ?: 0L, hasAudio)
    }

    fun deleteOwnedImport(media: LocalMediaItem?) {
        val uri = media?.uri ?: return
        if (uri.scheme == "file" && uri.path?.contains("lingoplay${File.separator}imported-media") == true) {
            runCatching { File(requireNotNull(uri.path)).delete() }
        }
    }

    suspend fun extractAudio(context: Context, media: LocalMediaItem): File = withContext(Dispatchers.IO) {
        if (!media.hasAudioTrack) throw LocalMediaException.NoAudioTrack
        val directory = File(context.cacheDir, "lingoplay/extracted-audio").apply { mkdirs() }
        val destination = File(directory, "${UUID.randomUUID()}.m4a")
        copyAudioTrack(context, media.uri, destination)
        destination
    }

    private fun queryMetadata(context: Context, uri: Uri): Pair<String?, Long?> {
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File) ?: return null to null
            return file.name to file.length()
        }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null to null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
            return name to size
        }
        return null to null
    }

    private fun readDuration(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") retriever.setDataSource(requireNotNull(uri.path)) else retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    private fun hasAudioTrack(context: Context, uri: Uri): Boolean {
        val extractor = openExtractor(context, uri)
        return try {
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
        } finally {
            extractor.release()
        }
    }

    private fun copyAudioTrack(context: Context, uri: Uri, destination: File) {
        val extractor = openExtractor(context, uri)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw LocalMediaException.NoAudioTrack

            val format = extractor.getTrackFormat(audioTrackIndex)
            extractor.selectTrack(audioTrackIndex)
            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrackIndex = muxer.addTrack(format)
            muxer.start()
            muxerStarted = true

            val requestedSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 256 * 1024
            val buffer = ByteBuffer.allocateDirect(max(requestedSize, 64 * 1024))
            val info = MediaCodec.BufferInfo()

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val extractorFlags = extractor.sampleFlags
                if (extractorFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                    throw IllegalStateException("Encrypted audio tracks are not supported.")
                }
                var codecFlags = 0
                if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                }
                info.set(0, sampleSize, extractor.sampleTime, codecFlags)
                muxer.writeSampleData(outputTrackIndex, buffer, info)
                if (!extractor.advance()) break
            }
        } finally {
            extractor.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!destination.exists() || destination.length() == 0L) destination.delete()
        }

        if (!destination.exists() || destination.length() == 0L) {
            throw IllegalStateException("Audio extraction produced no output.")
        }
    }

    private fun openExtractor(context: Context, uri: Uri): MediaExtractor {
        val extractor = MediaExtractor()
        if (uri.scheme == "file") {
            try {
                extractor.setDataSource(requireNotNull(uri.path))
                return extractor
            } catch (error: Throwable) {
                extractor.release()
                throw error
            }
        }
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: throw LocalMediaException.CannotOpenSource
        try {
            if (descriptor.declaredLength >= 0) extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
            else extractor.setDataSource(descriptor.fileDescriptor)
        } catch (error: Throwable) {
            extractor.release()
            throw error
        } finally {
            descriptor.close()
        }
        return extractor
    }
}

object MediaFormatting {
    fun duration(durationMs: Long): String {
        val totalSeconds = (durationMs.coerceAtLeast(0) + 500) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
    }

    fun bytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0)
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = safe.toDouble()
        var unit = 0
        while (value >= 1000 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
        return if (unit == 0) "${safe} B" else "%.1f %s".format(value, units[unit])
    }
}
