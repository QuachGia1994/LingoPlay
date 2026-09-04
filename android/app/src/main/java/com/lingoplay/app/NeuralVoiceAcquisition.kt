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

internal object NeuralVoicePackManifest {
    const val voiceId = "neural:vi-vais1000-medium"
    const val voiceLabel = "Vietnamese Neural · VAIS1000"
    const val version = "vi-vais1000-medium-fa136771"
    const val archiveName = "vits-piper-vi_VN-vais1000-medium.tar.bz2"
    const val archiveBytes = 67_154_040L
    const val archiveSha256 = "fa1367710767d36ed5cf13b4a449e20c35ffd12791c2e47c2e64142bfa55551a"
    const val archiveRoot = "vits-piper-vi_VN-vais1000-medium"
    const val modelName = "vi_VN-vais1000-medium.onnx"
    const val modelBytes = 63_149_198L
    const val tokensName = "tokens.txt"
    const val tokensBytes = 921L
    const val sourceRevision = "3d796cc2f2c884b3517c527507e084f7bb245aea"
    const val archiveUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$archiveName"

    val archiveSpec = ModelFileSpec(
        name = archiveName,
        url = archiveUrl,
        bytes = archiveBytes,
        sha256 = archiveSha256,
    )
}

internal object NeuralVoiceArchivePolicy {
    const val maxEntries = 1_024
    const val maxUncompressedBytes = 128L * 1024L * 1024L
    const val maxEntryBytes = 80L * 1024L * 1024L

    fun relativePath(entryName: String): String? {
        if (entryName.isEmpty() || entryName.length > 512 || '\u0000' in entryName || '\\' in entryName) return null
        if (entryName == NeuralVoicePackManifest.archiveRoot) return ""
        val prefix = "${NeuralVoicePackManifest.archiveRoot}/"
        if (!entryName.startsWith(prefix)) return null
        val relative = entryName.removePrefix(prefix).trimEnd('/')
        if (relative.isEmpty()) return ""
        val components = relative.split('/')
        if (components.any { it.isEmpty() || it == "." || it == ".." }) return null
        return relative
    }

    fun allowsEntry(entryName: String, isDirectory: Boolean, isRegularFile: Boolean, size: Long): Boolean {
        if (!isDirectory && !isRegularFile) return false
        if (size < 0L || size > maxEntryBytes) return false
        return relativePath(entryName) != null
    }
}

data class NeuralVoiceModel(
    val model: File,
    val tokens: File,
    val dataDir: File,
)

object NeuralVoiceModelStore {
    fun find(context: Context): NeuralVoiceModel? =
        NeuralVoicePackInstaller.activeDirectory(context)?.let(::validatedModel)

    fun voiceOption(context: Context): OfflineVoiceOption? =
        find(context)?.let {
            OfflineVoiceOption(
                id = NeuralVoicePackManifest.voiceId,
                label = NeuralVoicePackManifest.voiceLabel,
                languageCode = "vi",
            )
        }

    internal fun validatedModel(root: File): NeuralVoiceModel? {
        val marker = File(root, "pack.sha256")
        if (marker.takeIf(File::isFile)?.readText()?.trim() != NeuralVoicePackManifest.archiveSha256) return null
        val model = File(root, NeuralVoicePackManifest.modelName)
        val tokens = File(root, NeuralVoicePackManifest.tokensName)
        val dataDir = File(root, "espeak-ng-data")
        val requiredData = listOf("phondata", "phonindex", "phontab", "intonations", "vi_dict", "lang/aav/vi")
        if (!model.isFile || model.length() != NeuralVoicePackManifest.modelBytes) return null
        if (!tokens.isFile || tokens.length() != NeuralVoicePackManifest.tokensBytes) return null
        if (!dataDir.isDirectory || requiredData.any { !File(dataDir, it).isFile }) return null
        return NeuralVoiceModel(model, tokens, dataDir)
    }
}

object NeuralVoicePackInstaller {
    private const val rootPath = "lingoplay/models/neural-voice"
    private const val activePointerName = "active-model.txt"
    private const val storageSafetyMarginBytes = 64L * 1024L * 1024L

    fun state(context: Context): ModelInstallState {
        val root = activeDirectory(context) ?: return ModelInstallState.NotInstalled
        return ModelInstallState.Installed(directorySize(root))
    }

    suspend fun install(
        context: Context,
        wifiOnly: Boolean,
        onProgress: suspend (ModelInstallState.Downloading) -> Unit,
    ): NeuralVoiceModel = withContext(Dispatchers.IO) {
        if (wifiOnly && !ASRModelInstaller.isOnWifi(context)) {
            throw IllegalStateException(
                "Connect to Wi-Fi or disable ‘Download models on Wi-Fi only’ before installing Neural Voice.",
            )
        }

        val root = File(context.filesDir, rootPath).apply { mkdirs() }
        NeuralVoiceModelStore.find(context)?.let { installed ->
            onProgress(ModelInstallState.Downloading(NeuralVoicePackManifest.archiveBytes, NeuralVoicePackManifest.archiveBytes))
            return@withContext installed
        }

        val archive = File(root, NeuralVoicePackManifest.archiveName)
        val part = File(root, "${NeuralVoicePackManifest.archiveName}.part")
        if (archive.exists() && !ModelIntegrity.matches(archive, NeuralVoicePackManifest.archiveSpec)) archive.delete()
        if (part.length() > NeuralVoicePackManifest.archiveBytes ||
            (part.length() == NeuralVoicePackManifest.archiveBytes &&
                !ModelIntegrity.matches(part, NeuralVoicePackManifest.archiveSpec))
        ) {
            part.delete()
        }

        val verifiedArchiveBytes = if (ModelIntegrity.matches(archive, NeuralVoicePackManifest.archiveSpec)) {
            NeuralVoicePackManifest.archiveBytes
        } else {
            0L
        }
        ensureStorage(
            context,
            NeuralVoicePackManifest.archiveBytes - maxOf(verifiedArchiveBytes, part.length()),
        )
        onProgress(
            ModelInstallState.Downloading(
                maxOf(verifiedArchiveBytes, part.length()),
                NeuralVoicePackManifest.archiveBytes,
            ),
        )

        if (verifiedArchiveBytes == 0L) {
            ASRModelInstaller.downloadResumable(NeuralVoicePackManifest.archiveSpec, part) { bytes ->
                currentCoroutineContext().ensureActive()
                onProgress(ModelInstallState.Downloading(bytes, NeuralVoicePackManifest.archiveBytes))
            }
            if (!ModelIntegrity.matches(part, NeuralVoicePackManifest.archiveSpec)) {
                part.delete()
                throw IllegalStateException("Neural Voice download failed integrity verification.")
            }
            if (archive.exists() && !archive.delete()) error("Unable to replace the Neural Voice archive.")
            if (!part.renameTo(archive)) error("Unable to prepare the Neural Voice archive.")
        }

        val staging = File(root, "${NeuralVoicePackManifest.version}.staging")
        val versionDir = File(root, NeuralVoicePackManifest.version)
        if (staging.exists() && !staging.deleteRecursively()) {
            error("Unable to clear the Neural Voice staging directory.")
        }
        if (!staging.mkdirs() && !staging.isDirectory) {
            error("Unable to create the Neural Voice staging directory.")
        }
        try {
            extractVerifiedArchive(archive, staging)
            File(staging, "pack.sha256").writeText(NeuralVoicePackManifest.archiveSha256)
            NeuralVoiceModelStore.validatedModel(staging)
                ?: throw IllegalStateException("The verified Neural Voice archive is incomplete.")
            if (versionDir.exists() && !versionDir.deleteRecursively()) {
                error("Unable to replace the Neural Voice model.")
            }
            if (!staging.renameTo(versionDir)) error("Unable to activate the Neural Voice model.")
            writeActivePointer(root, NeuralVoicePackManifest.version)
            archive.delete()
            val activated = NeuralVoiceModelStore.find(context)
                ?: error("Verified Neural Voice model could not be activated.")
            onProgress(ModelInstallState.Downloading(NeuralVoicePackManifest.archiveBytes, NeuralVoicePackManifest.archiveBytes))
            activated
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun deleteInstalled(context: Context) {
        val root = File(context.filesDir, rootPath)
        if (root.exists() && !root.deleteRecursively()) {
            error("Unable to delete the installed Neural Voice pack.")
        }
    }

    internal fun activeDirectory(context: Context): File? {
        val root = File(context.filesDir, rootPath)
        val pointer = File(root, activePointerName)
        val version = pointer.takeIf(File::isFile)?.readText()?.trim().orEmpty()
        if (version != NeuralVoicePackManifest.version) return null
        return File(root, version).takeIf(File::isDirectory)
    }

    private suspend fun extractVerifiedArchive(archive: File, staging: File) {
        currentCoroutineContext().ensureActive()
        var entries = 0
        var totalBytes = 0L
        val stagingPrefix = staging.canonicalPath + File.separator
        TarArchiveInputStream(
            BZip2CompressorInputStream(FileInputStream(archive).buffered(), true),
        ).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextEntry ?: break
                entries++
                if (entries > NeuralVoiceArchivePolicy.maxEntries) {
                    throw IllegalStateException("Neural Voice archive contains too many entries.")
                }
                if (!NeuralVoiceArchivePolicy.allowsEntry(
                        entry.name,
                        entry.isDirectory,
                        entry.isFile,
                        entry.size,
                    )
                ) {
                    throw IllegalStateException("Neural Voice archive contains an unsafe entry.")
                }
                val relative = NeuralVoiceArchivePolicy.relativePath(entry.name).orEmpty()
                if (relative.isEmpty()) continue
                val output = File(staging, relative)
                val canonical = output.canonicalPath
                if (!canonical.startsWith(stagingPrefix)) {
                    throw IllegalStateException("Neural Voice archive entry escapes its staging directory.")
                }
                if (entry.isDirectory) {
                    output.mkdirs()
                    continue
                }
                totalBytes += entry.size
                if (totalBytes > NeuralVoiceArchivePolicy.maxUncompressedBytes) {
                    throw IllegalStateException("Neural Voice archive exceeds its extracted-size limit.")
                }
                output.parentFile?.mkdirs()
                output.outputStream().buffered().use { sink -> tar.copyTo(sink, 256 * 1024) }
                if (output.length() != entry.size) {
                    throw IllegalStateException("Neural Voice archive entry was truncated.")
                }
            }
        }
    }

    private fun ensureStorage(context: Context, remainingDownloadBytes: Long) {
        val required = remainingDownloadBytes.coerceAtLeast(0L) +
            NeuralVoiceArchivePolicy.maxUncompressedBytes +
            storageSafetyMarginBytes
        if (StatFs(context.filesDir.absolutePath).availableBytes < required) {
            throw IllegalStateException(
                "Not enough free storage to install Neural Voice. Free at least ${MediaFormatting.bytes(required)}.",
            )
        }
    }

    private fun writeActivePointer(root: File, version: String) {
        val pointer = File(root, activePointerName)
        val temporary = File(root, "$activePointerName.tmp")
        temporary.writeText(version)
        try {
            Files.move(
                temporary.toPath(),
                pointer.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), pointer.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun directorySize(root: File): Long =
        root.walkTopDown().filter(File::isFile).sumOf(File::length)
}
