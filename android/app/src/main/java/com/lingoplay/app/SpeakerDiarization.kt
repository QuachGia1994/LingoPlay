package com.lingoplay.app

import android.content.Context
import android.os.StatFs
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.CancellationException
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
import kotlin.math.max
import kotlin.math.roundToInt

enum class SpeakerMode(val label: String) {
    SINGLE("Single voice"),
    MULTI("Multi-speaker"),
    ;

    fun next(): SpeakerMode = entries[(ordinal + 1) % entries.size]
}

internal enum class SpeakerPhase {
    IDLE,
    MODEL_MISSING,
    ANALYZING,
    COMPLETED,
    FAILED,
}

data class SpeakerTurn(
    val startMs: Int,
    val endMs: Int,
    val speakerId: String,
)

data class SpeakerAttribution(
    val speakerId: String?,
    val overlappingSpeakerIds: List<String> = emptyList(),
)

data class SpeakerDiarizationDocument(
    val turns: List<SpeakerTurn>,
    val speakerIds: List<String>,
)

internal object SpeakerDiarizationPolicy {
    private const val MIN_PRIMARY_OVERLAP_MS = 120
    private const val OVERLAP_MIN_MS = 120
    private const val OVERLAP_RATIO = 0.35

    fun normalize(turns: List<Triple<Float, Float, Int>>): SpeakerDiarizationDocument {
        val valid = turns
            .filter { (start, end, _) -> start.isFinite() && end.isFinite() && end > start }
            .sortedWith(compareBy<Triple<Float, Float, Int>> { it.first }.thenBy { it.second })
        val labels = linkedMapOf<Int, String>()
        val normalized = valid.map { (start, end, rawSpeaker) ->
            val speakerId = labels.getOrPut(rawSpeaker) { "speaker_${labels.size + 1}" }
            SpeakerTurn(
                startMs = max(0, (start * 1_000f).roundToInt()),
                endMs = max((start * 1_000f).roundToInt() + 1, (end * 1_000f).roundToInt()),
                speakerId = speakerId,
            )
        }
        return SpeakerDiarizationDocument(normalized, labels.values.toList())
    }

    fun attribution(startMs: Int, endMs: Int, document: SpeakerDiarizationDocument): SpeakerAttribution {
        val rangeStart = startMs.coerceAtLeast(0)
        val rangeEnd = max(rangeStart + 1, endMs)
        val overlapBySpeaker = linkedMapOf<String, Int>()
        document.turns.forEach { turn ->
            val overlap = minOf(rangeEnd, turn.endMs) - maxOf(rangeStart, turn.startMs)
            if (overlap > 0) overlapBySpeaker[turn.speakerId] = overlapBySpeaker.getOrDefault(turn.speakerId, 0) + overlap
        }
        val ranked = overlapBySpeaker.entries.sortedByDescending { it.value }
        val primary = ranked.firstOrNull() ?: return SpeakerAttribution(null)
        if (primary.value < MIN_PRIMARY_OVERLAP_MS) return SpeakerAttribution(null)
        val overlapping = ranked
            .drop(1)
            .filter { it.value >= OVERLAP_MIN_MS && it.value.toDouble() >= primary.value.toDouble() * OVERLAP_RATIO }
            .map { it.key }
        return if (overlapping.isEmpty()) {
            SpeakerAttribution(primary.key)
        } else {
            SpeakerAttribution(null, listOf(primary.key) + overlapping)
        }
    }

    fun annotate(transcript: ASRTranscript, document: SpeakerDiarizationDocument): ASRTranscript = transcript.copy(
        segments = transcript.segments.map { segment ->
            val attribution = attribution(
                startMs = (segment.startSeconds * 1_000f).roundToInt(),
                endMs = (segment.endSeconds * 1_000f).roundToInt(),
                document = document,
            )
            segment.copy(
                speakerId = attribution.speakerId,
                overlappingSpeakerIds = attribution.overlappingSpeakerIds,
            )
        },
    )
}

internal object SpeakerVoicePolicy {
    fun resolve(
        speakerIds: List<String>,
        availableVoices: List<OfflineVoiceOption>,
        targetLanguage: String,
        preferredVoiceId: String?,
        existing: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val language = targetLanguage.substringBefore('-').lowercase()
        val candidates = availableVoices
            .filter { it.languageCode.substringBefore('-').equals(language, ignoreCase = true) }
            .distinctBy(OfflineVoiceOption::id)
        if (candidates.isEmpty()) return emptyMap()
        val validIds = candidates.mapTo(linkedSetOf(), OfflineVoiceOption::id)
        val result = linkedMapOf<String, String>()
        val used = linkedSetOf<String>()
        speakerIds.forEachIndexed { index, speakerId ->
            val preserved = existing[speakerId]?.takeIf { it in validIds && it !in used }
            val preferred = preferredVoiceId?.takeIf { index == 0 && it in validIds && it !in used }
            val next = preserved ?: preferred ?: candidates.firstOrNull { it.id !in used }?.id ?: candidates[index % candidates.size].id
            result[speakerId] = next
            used += next
        }
        return result
    }
}

internal object SpeakerDiarizationManifest {
    const val version = "pyannote3-int8-titanet-small-v1"
    const val archiveRoot = "sherpa-onnx-pyannote-segmentation-3-0"
    const val segmentationArchiveName = "sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
    const val segmentationArchiveBytes = 6_958_444L
    const val segmentationArchiveSha256 = "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488"
    const val segmentationModelName = "model.int8.onnx"
    const val segmentationModelBytes = 1_540_506L
    const val segmentationModelSha256 = "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d"
    const val embeddingModelName = "nemo_en_titanet_small.onnx"
    const val embeddingModelBytes = 40_257_283L
    const val embeddingModelSha256 = "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e"
    const val segmentationArchiveUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/$segmentationArchiveName"
    const val embeddingModelUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/$embeddingModelName"
    val totalDownloadBytes = segmentationArchiveBytes + embeddingModelBytes
}

data class SpeakerDiarizationModel(
    val segmentation: File,
    val embedding: File,
)

object SpeakerDiarizationModelStore {
    fun find(context: Context): SpeakerDiarizationModel? =
        SpeakerDiarizationModelInstaller.activeDirectory(context)?.let(::validatedModel)

    internal fun validatedModel(root: File): SpeakerDiarizationModel? {
        val marker = File(root, "pack.sha256")
        val expectedMarker = "${SpeakerDiarizationManifest.segmentationArchiveSha256}:${SpeakerDiarizationManifest.embeddingModelSha256}"
        if (marker.takeIf(File::isFile)?.readText()?.trim() != expectedMarker) return null
        val segmentation = File(root, SpeakerDiarizationManifest.segmentationModelName)
        val embedding = File(root, SpeakerDiarizationManifest.embeddingModelName)
        if (!segmentation.isFile || segmentation.length() != SpeakerDiarizationManifest.segmentationModelBytes) return null
        if (!embedding.isFile || embedding.length() != SpeakerDiarizationManifest.embeddingModelBytes) return null
        if (!ModelIntegrity.sha256(segmentation).equals(SpeakerDiarizationManifest.segmentationModelSha256, true)) return null
        if (!ModelIntegrity.sha256(embedding).equals(SpeakerDiarizationManifest.embeddingModelSha256, true)) return null
        return SpeakerDiarizationModel(segmentation, embedding)
    }
}

object SpeakerDiarizationModelInstaller {
    private const val rootPath = "lingoplay/models/speaker-diarization"
    private const val activePointerName = "active-model.txt"
    private const val storageSafetyMarginBytes = 64L * 1024L * 1024L

    fun state(context: Context): ModelInstallState {
        val root = activeDirectory(context) ?: return ModelInstallState.NotInstalled
        return ModelInstallState.Installed(root.walkTopDown().filter(File::isFile).sumOf(File::length))
    }

    suspend fun install(
        context: Context,
        wifiOnly: Boolean,
        onProgress: suspend (ModelInstallState.Downloading) -> Unit,
    ): SpeakerDiarizationModel = withContext(Dispatchers.IO) {
        if (wifiOnly && !ASRModelInstaller.isOnWifi(context)) {
            error("Connect to Wi-Fi or disable ‘Download models on Wi-Fi only’ before installing Speaker AI.")
        }
        SpeakerDiarizationModelStore.find(context)?.let {
            onProgress(ModelInstallState.Downloading(SpeakerDiarizationManifest.totalDownloadBytes, SpeakerDiarizationManifest.totalDownloadBytes))
            return@withContext it
        }
        val root = File(context.filesDir, rootPath).apply { mkdirs() }
        ensureStorage(context, SpeakerDiarizationManifest.totalDownloadBytes)
        val archiveSpec = ModelFileSpec(
            SpeakerDiarizationManifest.segmentationArchiveName,
            SpeakerDiarizationManifest.segmentationArchiveUrl,
            SpeakerDiarizationManifest.segmentationArchiveBytes,
            SpeakerDiarizationManifest.segmentationArchiveSha256,
        )
        val embeddingSpec = ModelFileSpec(
            SpeakerDiarizationManifest.embeddingModelName,
            SpeakerDiarizationManifest.embeddingModelUrl,
            SpeakerDiarizationManifest.embeddingModelBytes,
            SpeakerDiarizationManifest.embeddingModelSha256,
        )
        val archive = downloadVerified(root, archiveSpec, 0L, SpeakerDiarizationManifest.totalDownloadBytes, onProgress)
        val embedding = downloadVerified(
            root,
            embeddingSpec,
            SpeakerDiarizationManifest.segmentationArchiveBytes,
            SpeakerDiarizationManifest.totalDownloadBytes,
            onProgress,
        )
        val staging = File(root, "${SpeakerDiarizationManifest.version}.staging")
        val versionDir = File(root, SpeakerDiarizationManifest.version)
        staging.deleteRecursively()
        check(staging.mkdirs() || staging.isDirectory) { "Unable to create Speaker AI staging directory." }
        try {
            extractSegmentation(archive, staging)
            embedding.copyTo(File(staging, SpeakerDiarizationManifest.embeddingModelName), overwrite = true)
            File(staging, "pack.sha256").writeText(
                "${SpeakerDiarizationManifest.segmentationArchiveSha256}:${SpeakerDiarizationManifest.embeddingModelSha256}",
            )
            SpeakerDiarizationModelStore.validatedModel(staging)
                ?: error("The verified Speaker AI model pack is incomplete.")
            versionDir.deleteRecursively()
            check(staging.renameTo(versionDir)) { "Unable to activate Speaker AI model pack." }
            writeActivePointer(root)
            archive.delete()
            embedding.delete()
            SpeakerDiarizationModelStore.find(context) ?: error("Speaker AI model activation failed.")
        } catch (cancelled: CancellationException) {
            staging.deleteRecursively()
            throw cancelled
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun deleteInstalled(context: Context) {
        val root = File(context.filesDir, rootPath)
        if (root.exists() && !root.deleteRecursively()) error("Unable to delete Speaker AI models.")
    }

    internal fun activeDirectory(context: Context): File? {
        val root = File(context.filesDir, rootPath)
        val version = File(root, activePointerName).takeIf(File::isFile)?.readText()?.trim().orEmpty()
        if (version != SpeakerDiarizationManifest.version) return null
        return File(root, version).takeIf(File::isDirectory)
    }

    private suspend fun downloadVerified(
        root: File,
        spec: ModelFileSpec,
        priorBytes: Long,
        totalBytes: Long,
        onProgress: suspend (ModelInstallState.Downloading) -> Unit,
    ): File {
        val final = File(root, spec.name)
        if (ModelIntegrity.matches(final, spec)) {
            onProgress(ModelInstallState.Downloading(priorBytes + spec.bytes, totalBytes))
            return final
        }
        final.delete()
        val part = File(root, "${spec.name}.part")
        if (part.length() > spec.bytes || (part.length() == spec.bytes && !ModelIntegrity.matches(part, spec))) part.delete()
        ASRModelInstaller.downloadResumable(spec, part) { bytes ->
            currentCoroutineContext().ensureActive()
            onProgress(ModelInstallState.Downloading(priorBytes + bytes, totalBytes))
        }
        check(ModelIntegrity.matches(part, spec)) { "Speaker AI download failed integrity verification for ${spec.name}." }
        check(part.renameTo(final)) { "Unable to activate downloaded ${spec.name}." }
        return final
    }

    private suspend fun extractSegmentation(archive: File, staging: File) {
        currentCoroutineContext().ensureActive()
        val expected = "${SpeakerDiarizationManifest.archiveRoot}/${SpeakerDiarizationManifest.segmentationModelName}"
        var found = false
        TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(archive).buffered(), true)).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextEntry ?: break
                if (entry.name != expected) continue
                check(entry.isFile && entry.size == SpeakerDiarizationManifest.segmentationModelBytes) {
                    "Speaker AI segmentation archive entry is invalid."
                }
                val output = File(staging, SpeakerDiarizationManifest.segmentationModelName)
                output.outputStream().buffered().use { sink -> tar.copyTo(sink, 256 * 1024) }
                check(output.length() == SpeakerDiarizationManifest.segmentationModelBytes) { "Speaker AI segmentation model was truncated." }
                check(ModelIntegrity.sha256(output).equals(SpeakerDiarizationManifest.segmentationModelSha256, true)) {
                    "Speaker AI segmentation model failed SHA-256 verification."
                }
                found = true
                break
            }
        }
        check(found) { "Speaker AI segmentation archive did not contain the expected INT8 model." }
    }

    private fun ensureStorage(context: Context, downloadBytes: Long) {
        val required = downloadBytes + SpeakerDiarizationManifest.segmentationModelBytes +
            SpeakerDiarizationManifest.embeddingModelBytes + storageSafetyMarginBytes
        check(StatFs(context.filesDir.absolutePath).availableBytes >= required) {
            "Not enough storage for Speaker AI. Free at least ${MediaFormatting.bytes(required)}."
        }
    }

    private fun writeActivePointer(root: File) {
        val pointer = File(root, activePointerName)
        val temp = File(root, "$activePointerName.tmp")
        temp.writeText(SpeakerDiarizationManifest.version)
        try {
            Files.move(temp.toPath(), pointer.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), pointer.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

object SpeakerDiarizationService {
    private const val MAX_AUDIO_SECONDS = 30 * 60

    suspend fun diarize(context: Context, audioFile: File, model: SpeakerDiarizationModel): SpeakerDiarizationDocument =
        withContext(Dispatchers.Default) {
            val threads = InferenceMemoryPolicy.forDevice(context).numThreads.coerceIn(1, 2)
            val config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                        model = model.segmentation.absolutePath,
                        windowShiftRatio = 0.1f,
                    ),
                    numThreads = threads,
                    provider = "cpu",
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = model.embedding.absolutePath,
                    numThreads = threads,
                    provider = "cpu",
                ),
                clustering = FastClusteringConfig(numClusters = 0, threshold = 0.9f),
                minDurationOn = 0.2f,
                minDurationOff = 0.5f,
            )
            val diarizer = OfflineSpeakerDiarization(assetManager = null, config = config)
            try {
                val samples = AndroidAudioDecoder.decodeResampledMono(
                    file = audioFile,
                    targetSampleRate = diarizer.sampleRate(),
                    maxDurationSeconds = MAX_AUDIO_SECONDS,
                )
                currentCoroutineContext().ensureActive()
                val raw = diarizer.process(samples).map { Triple(it.start, it.end, it.speaker) }
                SpeakerDiarizationPolicy.normalize(raw)
            } finally {
                diarizer.release()
            }
        }
}
