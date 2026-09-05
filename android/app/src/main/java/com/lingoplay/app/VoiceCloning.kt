package com.lingoplay.app

import android.content.Context
import android.os.StatFs
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
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

data class VoiceCloneReference(
    val samples: FloatArray,
    val sampleRate: Int,
    val referenceText: String,
)

internal object VoiceCloningPolicy {
    private const val MIN_REFERENCE_MS = 1_500
    private const val MAX_REFERENCE_MS = 15_000
    private val supportedLanguages = setOf("en", "zh")

    fun supportsTarget(languageCode: String): Boolean =
        languageCode.trim().lowercase().substringBefore('-') in supportedLanguages

    fun supportsPair(source: String, target: String): Boolean =
        supportsTarget(source) && supportsTarget(target)

    fun eligibleReferenceSegments(transcript: ASRTranscript): Map<String, ASRSegment> {
        if (!supportsTarget(transcript.language)) return emptyMap()
        val result = linkedMapOf<String, ASRSegment>()
        transcript.segments.forEach { segment ->
            val speaker = segment.speakerId ?: return@forEach
            if (segment.overlappingSpeakerIds.isNotEmpty()) return@forEach
            val durationMs = ((segment.endSeconds - segment.startSeconds) * 1_000f).roundToInt()
            if (durationMs !in MIN_REFERENCE_MS..MAX_REFERENCE_MS) return@forEach
            if (segment.text.length < 8) return@forEach
            val existing = result[speaker]
            if (existing == null || segment.text.length > existing.text.length) result[speaker] = segment
        }
        return result
    }
}

internal object VoiceCloningManifest {
    const val version = "zipvoice-distill-int8-zh-en-emilia-v1"
    const val archiveRoot = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia"
    const val archiveName = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2"
    const val archiveBytes = 109_162_785L
    const val archiveSha256 = "77219c8b40f4ee8d73a7f902305ff6c1128ef9b54461c41b4ca6ed890b6c2803"
    const val vocoderName = "vocos_24khz.onnx"
    const val vocoderBytes = 54_157_409L
    const val vocoderSha256 = "bcb3b970e384161c4d634f0bb9e999ff1c471b34c9bc0b1049a5014065ed3cc0"
    const val archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$archiveName"
    const val vocoderUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/$vocoderName"
    val totalDownloadBytes = archiveBytes + vocoderBytes
}

data class VoiceCloningModel(
    val encoder: File,
    val decoder: File,
    val tokens: File,
    val lexicon: File,
    val dataDir: File,
    val vocoder: File,
)

object VoiceCloningModelStore {
    fun find(context: Context): VoiceCloningModel? = VoiceCloningModelInstaller.activeDirectory(context)?.let(::validatedModel)

    internal fun validatedModel(root: File): VoiceCloningModel? {
        val marker = File(root, "pack.sha256")
        val expected = "${VoiceCloningManifest.archiveSha256}:${VoiceCloningManifest.vocoderSha256}"
        if (marker.takeIf(File::isFile)?.readText()?.trim() != expected) return null
        val encoder = File(root, "encoder.int8.onnx")
        val decoder = File(root, "decoder.int8.onnx")
        val tokens = File(root, "tokens.txt")
        val lexicon = File(root, "lexicon.txt")
        val dataDir = File(root, "espeak-ng-data")
        val vocoder = File(root, VoiceCloningManifest.vocoderName)
        if (!encoder.isFile || encoder.length() <= 0L) return null
        if (!decoder.isFile || decoder.length() <= 0L) return null
        if (!tokens.isFile || tokens.length() <= 0L) return null
        if (!lexicon.isFile || lexicon.length() <= 0L) return null
        if (!dataDir.isDirectory) return null
        if (!vocoder.isFile || vocoder.length() != VoiceCloningManifest.vocoderBytes) return null
        if (!ModelIntegrity.sha256(vocoder).equals(VoiceCloningManifest.vocoderSha256, true)) return null
        return VoiceCloningModel(encoder, decoder, tokens, lexicon, dataDir, vocoder)
    }
}

object VoiceCloningModelInstaller {
    private const val rootPath = "lingoplay/models/voice-cloning"
    private const val activePointerName = "active-model.txt"
    private const val storageSafetyMarginBytes = 96L * 1024L * 1024L
    private const val maxExtractedBytes = 384L * 1024L * 1024L
    private const val maxEntries = 4_096

    fun state(context: Context): ModelInstallState {
        val root = activeDirectory(context) ?: return ModelInstallState.NotInstalled
        return ModelInstallState.Installed(root.walkTopDown().filter(File::isFile).sumOf(File::length))
    }

    suspend fun install(
        context: Context,
        wifiOnly: Boolean,
        onProgress: suspend (ModelInstallState.Downloading) -> Unit,
    ): VoiceCloningModel = withContext(Dispatchers.IO) {
        if (wifiOnly && !ASRModelInstaller.isOnWifi(context)) {
            error("Connect to Wi-Fi or disable ‘Download models on Wi-Fi only’ before installing Voice Cloning.")
        }
        VoiceCloningModelStore.find(context)?.let {
            onProgress(ModelInstallState.Downloading(VoiceCloningManifest.totalDownloadBytes, VoiceCloningManifest.totalDownloadBytes))
            return@withContext it
        }
        val root = File(context.filesDir, rootPath).apply { mkdirs() }
        ensureStorage(context)
        val archiveSpec = ModelFileSpec(
            VoiceCloningManifest.archiveName,
            VoiceCloningManifest.archiveUrl,
            VoiceCloningManifest.archiveBytes,
            VoiceCloningManifest.archiveSha256,
        )
        val vocoderSpec = ModelFileSpec(
            VoiceCloningManifest.vocoderName,
            VoiceCloningManifest.vocoderUrl,
            VoiceCloningManifest.vocoderBytes,
            VoiceCloningManifest.vocoderSha256,
        )
        val archive = downloadVerified(root, archiveSpec, 0L, onProgress)
        val vocoder = downloadVerified(root, vocoderSpec, VoiceCloningManifest.archiveBytes, onProgress)
        onProgress(
            ModelInstallState.Downloading(
                VoiceCloningManifest.totalDownloadBytes,
                VoiceCloningManifest.totalDownloadBytes,
                ModelInstallPhase.VERIFYING,
            ),
        )
        val staging = File(root, "${VoiceCloningManifest.version}.staging")
        val versionDir = File(root, VoiceCloningManifest.version)
        staging.deleteRecursively()
        check(staging.mkdirs() || staging.isDirectory) { "Unable to create Voice Cloning staging directory." }
        try {
            onProgress(
                ModelInstallState.Downloading(
                    VoiceCloningManifest.totalDownloadBytes,
                    VoiceCloningManifest.totalDownloadBytes,
                    ModelInstallPhase.EXTRACTING,
                ),
            )
            extractArchive(archive, staging)
            copyFileCancellable(vocoder, File(staging, VoiceCloningManifest.vocoderName))
            onProgress(
                ModelInstallState.Downloading(
                    VoiceCloningManifest.totalDownloadBytes,
                    VoiceCloningManifest.totalDownloadBytes,
                    ModelInstallPhase.VERIFYING,
                ),
            )
            File(staging, "pack.sha256").writeText(
                "${VoiceCloningManifest.archiveSha256}:${VoiceCloningManifest.vocoderSha256}",
            )
            VoiceCloningModelStore.validatedModel(staging)
                ?: error("The verified Voice Cloning model pack is incomplete.")
            onProgress(
                ModelInstallState.Downloading(
                    VoiceCloningManifest.totalDownloadBytes,
                    VoiceCloningManifest.totalDownloadBytes,
                    ModelInstallPhase.ACTIVATING,
                ),
            )
            versionDir.deleteRecursively()
            check(staging.renameTo(versionDir)) { "Unable to activate Voice Cloning model pack." }
            writeActivePointer(root)
            archive.delete()
            vocoder.delete()
            VoiceCloningModelStore.find(context) ?: error("Voice Cloning model activation failed.")
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
        if (root.exists() && !root.deleteRecursively()) error("Unable to delete Voice Cloning models.")
    }

    internal fun activeDirectory(context: Context): File? {
        val root = File(context.filesDir, rootPath)
        val version = File(root, activePointerName).takeIf(File::isFile)?.readText()?.trim().orEmpty()
        if (version != VoiceCloningManifest.version) return null
        return File(root, version).takeIf(File::isDirectory)
    }

    private suspend fun downloadVerified(
        root: File,
        spec: ModelFileSpec,
        priorBytes: Long,
        onProgress: suspend (ModelInstallState.Downloading) -> Unit,
    ): File {
        val final = File(root, spec.name)
        if (ModelIntegrity.matches(final, spec)) {
            onProgress(ModelInstallState.Downloading(priorBytes + spec.bytes, VoiceCloningManifest.totalDownloadBytes))
            return final
        }
        final.delete()
        val part = File(root, "${spec.name}.part")
        if (part.length() > spec.bytes || (part.length() == spec.bytes && !ModelIntegrity.matches(part, spec))) part.delete()
        ASRModelInstaller.downloadResumable(spec, part) { bytes ->
            currentCoroutineContext().ensureActive()
            onProgress(ModelInstallState.Downloading(priorBytes + bytes, VoiceCloningManifest.totalDownloadBytes))
        }
        check(ModelIntegrity.matches(part, spec)) { "Voice Cloning download failed integrity verification for ${spec.name}." }
        check(part.renameTo(final)) { "Unable to activate downloaded ${spec.name}." }
        return final
    }

    private suspend fun extractArchive(archive: File, staging: File) {
        currentCoroutineContext().ensureActive()
        val prefix = "${VoiceCloningManifest.archiveRoot}/"
        val stagingPrefix = staging.canonicalPath + File.separator
        var entries = 0
        var totalBytes = 0L
        TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(archive).buffered(), true)).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextEntry ?: break
                entries++
                check(entries <= maxEntries) { "Voice Cloning archive contains too many entries." }
                if (!entry.name.startsWith(prefix)) continue
                val relative = entry.name.removePrefix(prefix).trimEnd('/')
                if (relative.isEmpty()) continue
                val parts = relative.split('/')
                check(parts.none { it.isEmpty() || it == "." || it == ".." } && '\\' !in relative) {
                    "Voice Cloning archive contains an unsafe path."
                }
                val allowed = relative in setOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt", "lexicon.txt") ||
                    relative.startsWith("espeak-ng-data/")
                if (!allowed) continue
                val output = File(staging, relative)
                check(output.canonicalPath.startsWith(stagingPrefix)) { "Voice Cloning archive entry escapes staging." }
                if (entry.isDirectory) {
                    output.mkdirs()
                    continue
                }
                check(entry.isFile && entry.size >= 0L) { "Voice Cloning archive contains an invalid entry." }
                totalBytes += entry.size
                check(totalBytes <= maxExtractedBytes) { "Voice Cloning archive exceeds extracted-size limit." }
                output.parentFile?.mkdirs()
                output.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(256 * 1024)
                    var remaining = entry.size
                    while (remaining > 0L) {
                        currentCoroutineContext().ensureActive()
                        val requested = minOf(buffer.size.toLong(), remaining).toInt()
                        val count = tar.read(buffer, 0, requested)
                        check(count > 0) { "Voice Cloning archive entry was truncated." }
                        sink.write(buffer, 0, count)
                        remaining -= count.toLong()
                    }
                }
                check(output.length() == entry.size) { "Voice Cloning archive entry was truncated." }
            }
        }
    }

    private suspend fun copyFileCancellable(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        source.inputStream().buffered().use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                }
            }
        }
        check(destination.length() == source.length()) { "Voice Cloning vocoder copy was truncated." }
    }

    private fun ensureStorage(context: Context) {
        val required = VoiceCloningManifest.totalDownloadBytes + maxExtractedBytes + storageSafetyMarginBytes
        check(StatFs(context.filesDir.absolutePath).availableBytes >= required) {
            "Not enough storage for Voice Cloning. Free at least ${MediaFormatting.bytes(required)}."
        }
    }

    private fun writeActivePointer(root: File) {
        val pointer = File(root, activePointerName)
        val temp = File(root, "$activePointerName.tmp")
        temp.writeText(VoiceCloningManifest.version)
        try {
            Files.move(temp.toPath(), pointer.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), pointer.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal object VoiceCloneReferenceBuilder {
    private const val referenceSampleRate = 24_000
    suspend fun build(
        audioFile: File,
        transcript: ASRTranscript,
    ): Map<String, VoiceCloneReference> {
        val selected = VoiceCloningPolicy.eligibleReferenceSegments(transcript)
        if (selected.isEmpty()) return emptyMap()
        // Decode once, retaining only the selected <=15-second windows.
        val samples = selected.mapValues { (_, segment) ->
            FloatArray(((segment.endSeconds - segment.startSeconds) * referenceSampleRate).roundToInt())
        }
        val written = selected.mapValues { 0 }.toMutableMap()
        AndroidAudioDecoder.forEachChunk(audioFile, 5) { chunk ->
            selected.forEach { (speaker, segment) ->
                val output = samples.getValue(speaker)
                val start = max(0, ((chunk.startSeconds - segment.startSeconds) * referenceSampleRate).roundToInt())
                val end = minOf(output.size, ((chunk.endSeconds - segment.startSeconds) * referenceSampleRate).roundToInt())
                for (index in start until end) {
                    val position = (segment.startSeconds.toDouble() + index.toDouble() / referenceSampleRate -
                        chunk.startSeconds.toDouble()) * chunk.sampleRate
                    val left = position.toInt().coerceIn(0, chunk.samples.lastIndex)
                    val right = minOf(left + 1, chunk.samples.lastIndex)
                    val fraction = (position - left).toFloat().coerceIn(0f, 1f)
                    output[index] = chunk.samples[left] + (chunk.samples[right] - chunk.samples[left]) * fraction
                }
                written[speaker] = written.getValue(speaker) + max(0, end - start)
            }
        }
        currentCoroutineContext().ensureActive()
        return selected.mapNotNull { (speaker, segment) ->
            val pcm = samples.getValue(speaker)
            if (written.getValue(speaker) < pcm.size - referenceSampleRate / 20) null
            else speaker to VoiceCloneReference(pcm, referenceSampleRate, segment.text)
        }.toMap()
    }
}

object HybridDubbingTTSService {
    suspend fun synthesize(
        context: Context,
        document: TranslationDocument,
        preferredVoiceId: String?,
        speakerVoiceMap: Map<String, String>,
        cloneReferences: Map<String, VoiceCloneReference>,
        onProgress: suspend (Int, Int) -> Unit,
    ): DubSpeechDocument {
        if (cloneReferences.isEmpty()) {
            return OfflineDubbingTTSService.synthesize(
                context = context,
                document = document,
                preferredVoiceId = preferredVoiceId,
                speakerVoiceMap = speakerVoiceMap,
                onProgress = onProgress,
            )
        }
        val cloneable = document.segments.filter { segment ->
            segment.overlappingSpeakerIds.isEmpty() && segment.speakerId?.let(cloneReferences::containsKey) == true
        }
        val fallback = document.segments.filterNot { it in cloneable }
        val output = mutableListOf<DubSpeechSegment>()
        try {
            var completed = 0
            if (cloneable.isNotEmpty()) {
                val model = VoiceCloningModelStore.find(context)
                    ?: error("Voice Cloning model is not installed.")
                val cloned = VoiceCloningTTSService.synthesize(
                    context = context,
                    document = document.copy(segments = cloneable),
                    model = model,
                    references = cloneReferences,
                ) { local, _ -> onProgress(completed + local, document.segments.size) }
                output += cloned.segments
                completed += cloneable.size
            }
            if (fallback.isNotEmpty()) {
                val normal = OfflineDubbingTTSService.synthesize(
                    context = context,
                    document = document.copy(segments = fallback),
                    preferredVoiceId = preferredVoiceId,
                    speakerVoiceMap = speakerVoiceMap,
                ) { local, _ -> onProgress(completed + local, document.segments.size) }
                output += normal.segments
            }
            val order = document.segments.withIndex().associate { it.value.id to it.index }
            return DubSpeechDocument(
                voiceName = "hybrid:clone-local",
                segments = output.sortedBy { order[it.id] ?: Int.MAX_VALUE },
            )
        } catch (error: Throwable) {
            TTSCachePolicy.cleanup(DubSpeechDocument("partial", output))
            throw error
        }
    }
}

object VoiceCloningTTSService {
    suspend fun synthesize(
        context: Context,
        document: TranslationDocument,
        model: VoiceCloningModel,
        references: Map<String, VoiceCloneReference>,
        onProgress: suspend (Int, Int) -> Unit,
    ): DubSpeechDocument = TTSCachePolicy.synthesizeInSession(context, "clone-tts") { root ->
        require(VoiceCloningPolicy.supportsPair(document.sourceLanguage, document.targetLanguage)) {
            "Voice Cloning requires English or Chinese reference speech and output."
        }
        val zipvoice = OfflineTtsZipVoiceModelConfig(
            tokens = model.tokens.absolutePath,
            encoder = model.encoder.absolutePath,
            decoder = model.decoder.absolutePath,
            vocoder = model.vocoder.absolutePath,
            dataDir = model.dataDir.absolutePath,
            lexicon = model.lexicon.absolutePath,
        )
        val modelConfig = OfflineTtsModelConfig(
            zipvoice = zipvoice,
            numThreads = NeuralTTSPerformancePolicy.threadCount(Runtime.getRuntime().availableProcessors()),
            provider = "cpu",
        )
        val tts = OfflineTts(assetManager = null, config = OfflineTtsConfig(model = modelConfig))
        var succeeded = false
        try {
            val output = mutableListOf<DubSpeechSegment>()
            document.segments.forEachIndexed { index, segment ->
                val speaker = segment.speakerId
                val reference = speaker?.let(references::get)
                check(reference != null && segment.overlappingSpeakerIds.isEmpty()) {
                    "No consented single-speaker reference is available for ${speaker ?: "unknown speech"}."
                }
                output += synthesizeSegment(tts, segment, reference, root)
                onProgress(index + 1, document.segments.size)
            }
            currentCoroutineContext().ensureActive()
            succeeded = true
            DubSpeechDocument("clone:zipvoice", output)
        } finally {
            tts.release()
            if (!succeeded) root.deleteRecursively()
        }
    }

    private suspend fun synthesizeSegment(
        tts: OfflineTts,
        segment: TranslationSegment,
        reference: VoiceCloneReference,
        root: File,
    ): DubSpeechSegment {
        val targetMs = DurationFitPolicy.targetDurationMs(segment.startMs, segment.endMs)
        var multiplier = 1.0f
        repeat(DurationFitPolicy.MAXIMUM_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            val output = File(root, "${segment.id}-$attempt.wav")
            output.delete()
            val config = GenerationConfig(
                speed = multiplier,
                referenceAudio = reference.samples,
                referenceSampleRate = reference.sampleRate,
                referenceText = reference.referenceText,
                numSteps = 4,
                extra = mapOf("min_char_in_sentence" to "10"),
            )
            val audio = tts.generateWithConfig(segment.spokenText, config)
            currentCoroutineContext().ensureActive()
            check(audio.samples.isNotEmpty() && audio.sampleRate > 0) { "Voice Cloning produced no audio for ${segment.id}." }
            check(audio.save(output.absolutePath) && output.length() > 44L) { "Voice Cloning could not save ${segment.id}." }
            val durationMs = PcmWaveFile.durationMs(output)
            val next = DurationFitPolicy.nextRateMultiplier(durationMs, targetMs, multiplier)
            val fits = DurationFitPolicy.fits(durationMs, targetMs)
            if (fits || next == null || attempt == DurationFitPolicy.MAXIMUM_ATTEMPTS - 1) {
                return DubSpeechSegment(
                    id = segment.id,
                    startMs = segment.startMs,
                    endMs = DurationFitPolicy.effectiveEndMs(segment.startMs, segment.endMs, durationMs),
                    audioFile = output,
                    speechDurationMs = durationMs,
                    tailSilenceMs = DurationFitPolicy.tailSilenceMs(durationMs, targetMs),
                    rateMultiplier = multiplier,
                )
            }
            output.delete()
            multiplier = next
        }
        error("No Voice Cloning synthesis attempt was executed.")
    }
}
