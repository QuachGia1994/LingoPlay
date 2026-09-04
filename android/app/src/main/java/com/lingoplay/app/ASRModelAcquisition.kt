package com.lingoplay.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface ModelInstallState {
    data object NotInstalled : ModelInstallState
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : ModelInstallState {
        val progress: Float get() = if (bytesTotal <= 0L) 0f else (bytesDone.toDouble() / bytesTotal.toDouble()).toFloat().coerceIn(0f, 1f)
    }
    data class Installed(val bytes: Long) : ModelInstallState
    data class Failed(val message: String) : ModelInstallState
}

internal data class ModelFileSpec(
    val name: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
)

internal object SherpaWhisperTinyManifest {
    const val version = "tiny-int8-65176e2"
    private const val base = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/65176e2deb88badc814a94058666cadccc29b61c"

    val files = listOf(
        ModelFileSpec(
            name = "tiny-encoder.int8.onnx",
            url = "$base/tiny-encoder.int8.onnx",
            bytes = 12_937_772L,
            sha256 = "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
        ),
        ModelFileSpec(
            name = "tiny-decoder.int8.onnx",
            url = "$base/tiny-decoder.int8.onnx",
            bytes = 89_855_401L,
            sha256 = "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
        ),
        ModelFileSpec(
            name = "tiny-tokens.txt",
            url = "$base/tiny-tokens.txt",
            bytes = 816_730L,
            sha256 = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
        ),
    )

    val totalBytes: Long = files.sumOf(ModelFileSpec::bytes)
}

internal object ModelIntegrity {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(file: File, spec: ModelFileSpec): Boolean =
        file.isFile && file.length() == spec.bytes && sha256(file).equals(spec.sha256, ignoreCase = true)
}

object ASRModelInstaller {
    private const val modelRootPath = "lingoplay/models/sherpa-whisper"
    private const val activePointerName = "active-model.txt"
    private const val safetyMarginBytes = 64L * 1024L * 1024L
    private const val maxRedirects = 8

    fun state(context: Context): ModelInstallState {
        val model = ASRModelStore.findWhisperModel(context) ?: return ModelInstallState.NotInstalled
        val bytes = model.encoder.length() + model.decoder.length() + model.tokens.length()
        return ModelInstallState.Installed(bytes)
    }

    suspend fun install(
        context: Context,
        wifiOnly: Boolean,
        onProgress: suspend (ModelInstallState.Downloading) -> Unit,
    ): SherpaWhisperModel = withContext(Dispatchers.IO) {
        if (wifiOnly && !isOnWifi(context)) {
            throw IllegalStateException("Connect to Wi-Fi or disable ‘Download models on Wi-Fi only’ before installing Speech AI.")
        }

        val root = File(context.filesDir, modelRootPath).apply { mkdirs() }
        val versionDir = File(root, SherpaWhisperTinyManifest.version).apply { mkdirs() }
        val completedBytes = SherpaWhisperTinyManifest.files.sumOf { spec ->
            val finalFile = File(versionDir, spec.name)
            if (ModelIntegrity.matches(finalFile, spec)) spec.bytes else 0L
        }
        ensureStorage(context, SherpaWhisperTinyManifest.totalBytes - completedBytes)

        var aggregateDone = completedBytes
        onProgress(ModelInstallState.Downloading(aggregateDone, SherpaWhisperTinyManifest.totalBytes))

        for (spec in SherpaWhisperTinyManifest.files) {
            currentCoroutineContext().ensureActive()
            val finalFile = File(versionDir, spec.name)
            if (ModelIntegrity.matches(finalFile, spec)) continue
            if (finalFile.exists()) finalFile.delete()

            val partFile = File(versionDir, "${spec.name}.part")
            if (partFile.length() > spec.bytes || (partFile.length() == spec.bytes && !ModelIntegrity.matches(partFile, spec))) {
                partFile.delete()
            }
            val verifiedOtherBytes = SherpaWhisperTinyManifest.files
                .filter { it.name != spec.name }
                .sumOf { other -> if (ModelIntegrity.matches(File(versionDir, other.name), other)) other.bytes else 0L }

            downloadResumable(spec, partFile) { currentFileBytes ->
                currentCoroutineContext().ensureActive()
                aggregateDone = verifiedOtherBytes + currentFileBytes
                onProgress(ModelInstallState.Downloading(aggregateDone, SherpaWhisperTinyManifest.totalBytes))
            }

            if (!ModelIntegrity.matches(partFile, spec)) {
                partFile.delete()
                throw IllegalStateException("Speech AI download failed integrity verification for ${spec.name}.")
            }
            if (finalFile.exists() && !finalFile.delete()) error("Unable to replace ${spec.name}.")
            if (!partFile.renameTo(finalFile)) error("Unable to activate ${spec.name}.")
        }

        val verified = SherpaWhisperTinyManifest.files.all { spec -> ModelIntegrity.matches(File(versionDir, spec.name), spec) }
        if (!verified) throw IllegalStateException("Speech AI model verification did not complete.")
        writeActivePointer(root, SherpaWhisperTinyManifest.version)
        ASRModelStore.findWhisperModel(context) ?: error("Verified Speech AI model could not be activated.")
    }

    fun deleteInstalled(context: Context) {
        val root = File(context.filesDir, modelRootPath)
        File(root, activePointerName).delete()
        root.listFiles()?.forEach { child ->
            if (child.isDirectory) child.deleteRecursively()
        }
    }

    internal fun activeDirectory(context: Context): File? {
        val root = File(context.filesDir, modelRootPath)
        val pointer = File(root, activePointerName)
        val version = pointer.takeIf(File::isFile)?.readText()?.trim().orEmpty()
        if (version.isBlank() || version.contains('/') || version.contains('\\')) return null
        return File(root, version).takeIf(File::isDirectory)
    }

    private suspend fun downloadResumable(
        spec: ModelFileSpec,
        partFile: File,
        onProgress: suspend (Long) -> Unit,
    ) {
        var existing = partFile.takeIf(File::isFile)?.length() ?: 0L
        if (existing == spec.bytes) {
            onProgress(existing)
            return
        }

        val connection = openDownloadConnection(spec.url, existing)

        try {
            val code = connection.responseCode
            val append = existing > 0L && code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("Speech AI download failed with HTTP $code.")
            }
            if (!append) {
                existing = 0L
                if (partFile.exists()) partFile.delete()
            }

            partFile.parentFile?.mkdirs()
            connection.inputStream.buffered().use { input ->
                FileOutputStream(partFile, append).buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = existing
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        onProgress(done.coerceAtMost(spec.bytes))
                    }
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun openDownloadConnection(initialUrl: String, existingBytes: Long): HttpURLConnection {
        var current = URL(initialUrl)
        repeat(maxRedirects + 1) { hop ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "LingoPlay-ModelInstaller/1")
                if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
            }
            val code = connection.responseCode
            if (code !in setOf(301, 302, 303, 307, 308)) return connection

            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) {
                throw IllegalStateException("Speech AI download redirect did not include a Location header.")
            }
            if (hop >= maxRedirects) {
                throw IllegalStateException("Speech AI download exceeded the redirect limit.")
            }
            current = URL(current, location)
        }
        error("Speech AI download redirect resolution failed.")
    }

    private fun ensureStorage(context: Context, remainingBytes: Long) {
        val available = StatFs(context.filesDir.absolutePath).availableBytes
        if (available < remainingBytes.coerceAtLeast(0L) + safetyMarginBytes) {
            throw IllegalStateException("Not enough free storage to install Speech AI. Free at least ${MediaFormatting.bytes(remainingBytes + safetyMarginBytes)}.")
        }
    }

    private fun isOnWifi(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun writeActivePointer(root: File, version: String) {
        val pointer = File(root, activePointerName)
        val temporary = File(root, "$activePointerName.tmp")
        temporary.writeText(version)
        if (pointer.exists() && !pointer.delete()) error("Unable to replace active model pointer.")
        if (!temporary.renameTo(pointer)) error("Unable to activate Speech AI model pointer.")
    }
}
