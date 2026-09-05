package com.lingoplay.app

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object SourceSeparationManifest {
    const val version = "spleeter-2stems-fp16-c6c5c430"
    const val archiveRoot = "sherpa-onnx-spleeter-2stems-fp16"
    const val archiveName = "$archiveRoot.tar.bz2"
    const val archiveBytes = 35_271_738L
    const val archiveSha256 = "c6c5c4307673bc6813ddf58d4efdff57c26d2dfc3f25b05c7a32db453d70aca6"
    const val vocalsName = "vocals.fp16.onnx"
    const val accompanimentName = "accompaniment.fp16.onnx"
    const val archiveUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/$archiveName"

    val archiveSpec = ModelFileSpec(archiveName, archiveUrl, archiveBytes, archiveSha256)
}

internal object SourceSeparationArchivePolicy {
    const val maxEntries = 16
    const val maxUncompressedBytes = 96L * 1024L * 1024L
    const val maxEntryBytes = 48L * 1024L * 1024L
    private val allowedFiles = setOf(SourceSeparationManifest.vocalsName, SourceSeparationManifest.accompanimentName)

    fun relativePath(entryName: String): String? {
        if (entryName.isEmpty() || entryName.length > 256 || '\u0000' in entryName || '\\' in entryName) return null
        if (entryName == SourceSeparationManifest.archiveRoot) return ""
        val prefix = "${SourceSeparationManifest.archiveRoot}/"
        if (!entryName.startsWith(prefix)) return null
        val relative = entryName.removePrefix(prefix).trimEnd('/')
        if (relative.isEmpty()) return ""
        if ('/' in relative || relative == "." || relative == "..") return null
        return relative.takeIf { it in allowedFiles }
    }

    fun allowsEntry(entryName: String, isDirectory: Boolean, isRegularFile: Boolean, size: Long): Boolean {
        if (!isDirectory && !isRegularFile) return false
        if (size < 0 || size > maxEntryBytes) return false
        return relativePath(entryName) != null
    }
}

data class SpleeterSourceSeparationModel(
    val vocals: File,
    val accompaniment: File,
)

object SourceSeparationModelStore {
    fun find(context: Context): SpleeterSourceSeparationModel? =
        SourceSeparationModelInstaller.activeDirectory(context)?.let(::validatedModel)

    internal fun validatedModel(root: File): SpleeterSourceSeparationModel? {
        val marker = File(root, "pack.sha256")
        if (marker.takeIf(File::isFile)?.readText()?.trim() != SourceSeparationManifest.archiveSha256) return null
        val vocals = File(root, SourceSeparationManifest.vocalsName)
        val accompaniment = File(root, SourceSeparationManifest.accompanimentName)
        if (!vocals.isFile || vocals.length() <= 0L || !accompaniment.isFile || accompaniment.length() <= 0L) return null
        return SpleeterSourceSeparationModel(vocals, accompaniment)
    }
}

object SourceSeparationModelInstaller {
    private const val rootPath = "lingoplay/models/source-separation"
    private const val activePointerName = "active-model.txt"
    private const val storageSafetyMarginBytes = 64L * 1024L * 1024L

    fun state(context: Context): ModelInstallState {
        val model = SourceSeparationModelStore.find(context) ?: return ModelInstallState.NotInstalled
        val bytes = model.vocals.length() + model.accompaniment.length()
        return ModelInstallState.Installed(bytes)
    }

    suspend fun install(
        context: Context,
        wifiOnly: Boolean,
        onProgress: suspend (ModelInstallState.Downloading) -> Unit,
    ): SpleeterSourceSeparationModel = withContext(Dispatchers.IO) {
        if (wifiOnly && !ASRModelInstaller.isOnWifi(context)) {
            throw IllegalStateException(
                "Connect to Wi-Fi or disable ‘Download models on Wi-Fi only’ before installing Clean Background.",
            )
        }
        SourceSeparationModelStore.find(context)?.let {
            onProgress(ModelInstallState.Downloading(SourceSeparationManifest.archiveBytes, SourceSeparationManifest.archiveBytes))
            return@withContext it
        }

        val root = File(context.filesDir, rootPath).apply { mkdirs() }
        val archive = File(root, SourceSeparationManifest.archiveName)
        val part = File(root, "${SourceSeparationManifest.archiveName}.part")
        if (archive.exists() && !ModelIntegrity.matches(archive, SourceSeparationManifest.archiveSpec)) archive.delete()
        if (part.length() > SourceSeparationManifest.archiveBytes ||
            (part.length() == SourceSeparationManifest.archiveBytes && !ModelIntegrity.matches(part, SourceSeparationManifest.archiveSpec))
        ) part.delete()

        val verified = ModelIntegrity.matches(archive, SourceSeparationManifest.archiveSpec)
        val current = if (verified) SourceSeparationManifest.archiveBytes else part.length().coerceAtLeast(0L)
        ensureStorage(context, SourceSeparationManifest.archiveBytes - current)
        onProgress(ModelInstallState.Downloading(current, SourceSeparationManifest.archiveBytes))

        if (!verified) {
            ASRModelInstaller.downloadResumable(SourceSeparationManifest.archiveSpec, part) { bytes ->
                currentCoroutineContext().ensureActive()
                onProgress(ModelInstallState.Downloading(bytes, SourceSeparationManifest.archiveBytes))
            }
            if (!ModelIntegrity.matches(part, SourceSeparationManifest.archiveSpec)) {
                part.delete()
                error("Clean Background model failed integrity verification.")
            }
            if (archive.exists() && !archive.delete()) error("Unable to replace Clean Background archive.")
            if (!part.renameTo(archive)) error("Unable to prepare Clean Background archive.")
        }

        val staging = File(root, "${SourceSeparationManifest.version}.staging")
        val versionDir = File(root, SourceSeparationManifest.version)
        if (staging.exists() && !staging.deleteRecursively()) error("Unable to clear Clean Background staging directory.")
        if (!staging.mkdirs() && !staging.isDirectory) error("Unable to create Clean Background staging directory.")
        try {
            extractVerifiedArchive(archive, staging)
            File(staging, "pack.sha256").writeText(SourceSeparationManifest.archiveSha256)
            SourceSeparationModelStore.validatedModel(staging)
                ?: error("The verified Clean Background archive is incomplete.")
            if (versionDir.exists() && !versionDir.deleteRecursively()) error("Unable to replace Clean Background model.")
            if (!staging.renameTo(versionDir)) error("Unable to activate Clean Background model.")
            writeActivePointer(root, SourceSeparationManifest.version)
            archive.delete()
            val activated = SourceSeparationModelStore.find(context)
                ?: error("Verified Clean Background model could not be activated.")
            onProgress(ModelInstallState.Downloading(SourceSeparationManifest.archiveBytes, SourceSeparationManifest.archiveBytes))
            activated
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun deleteInstalled(context: Context) {
        val root = File(context.filesDir, rootPath)
        if (root.exists() && !root.deleteRecursively()) error("Unable to delete the installed Clean Background model.")
    }

    internal fun activeDirectory(context: Context): File? {
        val root = File(context.filesDir, rootPath)
        val pointer = File(root, activePointerName)
        val version = pointer.takeIf(File::isFile)?.readText()?.trim().orEmpty()
        if (version != SourceSeparationManifest.version) return null
        return File(root, version).takeIf(File::isDirectory)
    }

    private suspend fun extractVerifiedArchive(archive: File, staging: File) {
        currentCoroutineContext().ensureActive()
        var entries = 0
        var extractedBytes = 0L
        val stagingPrefix = staging.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(archive).buffered(), true)).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextEntry ?: break
                entries++
                if (entries > SourceSeparationArchivePolicy.maxEntries ||
                    !SourceSeparationArchivePolicy.allowsEntry(entry.name, entry.isDirectory, entry.isFile, entry.size)
                ) error("Clean Background archive contains an unsafe entry.")
                val relative = SourceSeparationArchivePolicy.relativePath(entry.name).orEmpty()
                if (relative.isEmpty()) continue
                val output = File(staging, relative)
                if (!output.canonicalPath.startsWith(stagingPrefix)) error("Clean Background archive escapes staging.")
                if (entry.isDirectory) continue
                extractedBytes += entry.size
                if (extractedBytes > SourceSeparationArchivePolicy.maxUncompressedBytes) {
                    error("Clean Background archive exceeds its extracted-size limit.")
                }
                output.outputStream().buffered().use { sink -> tar.copyTo(sink, 256 * 1024) }
                if (output.length() != entry.size) error("Clean Background archive entry was truncated.")
            }
        }
    }

    private fun ensureStorage(context: Context, remainingDownloadBytes: Long) {
        val required = remainingDownloadBytes.coerceAtLeast(0L) +
            SourceSeparationArchivePolicy.maxUncompressedBytes + storageSafetyMarginBytes
        if (StatFs(context.filesDir.absolutePath).availableBytes < required) {
            error("Not enough free storage to install Clean Background. Free at least ${MediaFormatting.bytes(required)}.")
        }
    }

    private fun writeActivePointer(root: File, version: String) {
        val pointer = File(root, activePointerName)
        val temporary = File(root, "$activePointerName.tmp")
        temporary.writeText(version)
        try {
            Files.move(temporary.toPath(), pointer.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), pointer.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
