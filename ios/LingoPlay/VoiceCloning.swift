import Foundation
@preconcurrency import SherpaOnnx
@preconcurrency import SWCompression

struct VoiceCloneReference: Sendable, Equatable {
    let samples: [Float]
    let sampleRate: Int
    let referenceText: String
}

enum VoiceCloningPolicy {
    private static let minimumReferenceMs = 1_500
    private static let maximumReferenceMs = 15_000
    private static let supportedLanguages = Set(["en", "zh"])

    static func supportsTarget(_ languageCode: String) -> Bool {
        let base = languageCode
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .split(separator: "-")
            .first
            .map(String.init) ?? ""
        return supportedLanguages.contains(base)
    }

    static func supportsPair(source: String, target: String) -> Bool {
        supportsTarget(source) && supportsTarget(target)
    }

    static func eligibleReferenceSegments(_ transcript: ASRTranscript) -> [String: ASRSegment] {
        guard supportsTarget(transcript.language) else { return [:] }
        var result: [String: ASRSegment] = [:]
        for segment in transcript.segments {
            guard let speakerID = segment.speakerID,
                  segment.overlappingSpeakerIDs.isEmpty
            else { continue }
            let durationMs = Int(((segment.end - segment.start) * 1_000).rounded())
            guard (minimumReferenceMs...maximumReferenceMs).contains(durationMs),
                  segment.text.count >= 8
            else { continue }
            if let existing = result[speakerID], existing.text.count >= segment.text.count {
                continue
            }
            result[speakerID] = segment
        }
        return result
    }
}

enum VoiceCloningManifest {
    static let version = "zipvoice-distill-int8-zh-en-emilia-v1"
    static let archiveRoot = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia"
    static let archiveName = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2"
    static let archiveBytes: Int64 = 109_162_785
    static let archiveSHA256 = "77219c8b40f4ee8d73a7f902305ff6c1128ef9b54461c41b4ca6ed890b6c2803"
    static let vocoderName = "vocos_24khz.onnx"
    static let vocoderBytes: Int64 = 54_157_409
    static let vocoderSHA256 = "bcb3b970e384161c4d634f0bb9e999ff1c471b34c9bc0b1049a5014065ed3cc0"
    static let archiveURL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/\(archiveName)"
    static let vocoderURL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/\(vocoderName)"
    static let totalDownloadBytes = archiveBytes + vocoderBytes
}

struct InstalledVoiceCloningModel: Sendable, Equatable {
    let encoderURL: URL
    let decoderURL: URL
    let tokensURL: URL
    let lexiconURL: URL
    let dataDirectoryURL: URL
    let vocoderURL: URL
}

struct VoiceCloningModelStore {
    private static let activePointerName = "active-model.txt"
    private let fileManager = FileManager.default

    func model() -> InstalledVoiceCloningModel? {
        guard let root = try? rootURL(create: false),
              let version = try? String(
                contentsOf: root.appendingPathComponent(Self.activePointerName),
                encoding: .utf8
              ).trimmingCharacters(in: .whitespacesAndNewlines),
              version == VoiceCloningManifest.version
        else { return nil }
        return validatedModel(at: root.appendingPathComponent(version, isDirectory: true))
    }

    func validatedModel(at root: URL) -> InstalledVoiceCloningModel? {
        let expectedMarker = VoiceCloningManifest.archiveSHA256 + ":" + VoiceCloningManifest.vocoderSHA256
        let marker = root.appendingPathComponent("pack.sha256")
        guard (try? String(contentsOf: marker, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)) == expectedMarker
        else { return nil }

        let encoder = root.appendingPathComponent("encoder.int8.onnx")
        let decoder = root.appendingPathComponent("decoder.int8.onnx")
        let tokens = root.appendingPathComponent("tokens.txt")
        let lexicon = root.appendingPathComponent("lexicon.txt")
        let dataDirectory = root.appendingPathComponent("espeak-ng-data", isDirectory: true)
        let vocoder = root.appendingPathComponent(VoiceCloningManifest.vocoderName)
        guard isNonEmptyFile(encoder),
              isNonEmptyFile(decoder),
              isNonEmptyFile(tokens),
              isNonEmptyFile(lexicon),
              (try? dataDirectory.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true
        else { return nil }
        let vocoderSpec = PinnedDownloadSpec(
            name: VoiceCloningManifest.vocoderName,
            url: "",
            bytes: VoiceCloningManifest.vocoderBytes,
            sha256: VoiceCloningManifest.vocoderSHA256
        )
        guard PinnedFileIntegrity.matches(vocoder, spec: vocoderSpec) else { return nil }
        return InstalledVoiceCloningModel(
            encoderURL: encoder,
            decoderURL: decoder,
            tokensURL: tokens,
            lexiconURL: lexicon,
            dataDirectoryURL: dataDirectory,
            vocoderURL: vocoder
        )
    }

    func rootURL(create: Bool) throws -> URL {
        guard let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            throw PinnedDownloadError.activationFailed
        }
        let root = support.appendingPathComponent("LingoPlay/Models/VoiceCloning", isDirectory: true)
        if create { try fileManager.createDirectory(at: root, withIntermediateDirectories: true) }
        return root
    }

    private func isNonEmptyFile(_ url: URL) -> Bool {
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]) else { return false }
        return values.isRegularFile == true && (values.fileSize ?? 0) > 0
    }
}

actor VoiceCloningModelInstaller {
    private static let activePointerName = "active-model.txt"
    private static let storageSafetyMarginBytes: Int64 = 96 * 1024 * 1024
    private static let maximumExtractedBytes: Int64 = 384 * 1024 * 1024
    private static let maximumEntries = 4_096
    private let fileManager = FileManager.default
    private let store = VoiceCloningModelStore()

    func state() -> ASRModelInstallState {
        guard let model = store.model() else { return .notInstalled }
        let urls = [
            model.encoderURL,
            model.decoderURL,
            model.tokensURL,
            model.lexiconURL,
            model.vocoderURL,
        ]
        var bytes = urls.reduce(Int64(0)) { $0 + (PinnedFileIntegrity.fileSize($1) ?? 0) }
        if let enumerator = fileManager.enumerator(
            at: model.dataDirectoryURL,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        ) {
            for case let url as URL in enumerator {
                guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
                      values.isRegularFile == true
                else { continue }
                bytes += Int64(values.fileSize ?? 0)
            }
        }
        return .installed(bytes: bytes)
    }

    func install(
        wifiOnly: Bool,
        progress: @escaping @Sendable (Double) async -> Void
    ) async throws -> InstalledVoiceCloningModel {
        if wifiOnly {
            let usingWiFi = await ModelNetworkPolicy.isUsingWiFi()
            guard usingWiFi else {
                throw NSError(
                    domain: "LingoPlay.VoiceCloning",
                    code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "Connect to Wi-Fi or disable ‘Download models on Wi-Fi only’ before installing Voice Cloning."]
                )
            }
        }
        if let installed = store.model() {
            await progress(1)
            return installed
        }

        let root = try store.rootURL(create: true)
        try ensureStorage(at: root)
        let archiveSpec = PinnedDownloadSpec(
            name: VoiceCloningManifest.archiveName,
            url: VoiceCloningManifest.archiveURL,
            bytes: VoiceCloningManifest.archiveBytes,
            sha256: VoiceCloningManifest.archiveSHA256
        )
        let vocoderSpec = PinnedDownloadSpec(
            name: VoiceCloningManifest.vocoderName,
            url: VoiceCloningManifest.vocoderURL,
            bytes: VoiceCloningManifest.vocoderBytes,
            sha256: VoiceCloningManifest.vocoderSHA256
        )
        let archive = try await PinnedModelDownload.downloadVerified(spec: archiveSpec, root: root) { done in
            await progress(Double(done) / Double(VoiceCloningManifest.totalDownloadBytes))
        }
        let vocoder = try await PinnedModelDownload.downloadVerified(spec: vocoderSpec, root: root) { done in
            let aggregate = VoiceCloningManifest.archiveBytes + done
            await progress(min(1, Double(aggregate) / Double(VoiceCloningManifest.totalDownloadBytes)))
        }
        try Task.checkCancellation()

        let staging = root.appendingPathComponent(VoiceCloningManifest.version + ".staging", isDirectory: true)
        let versionDirectory = root.appendingPathComponent(VoiceCloningManifest.version, isDirectory: true)
        try? fileManager.removeItem(at: staging)
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: true)
        do {
            try extractArchive(archive: archive, to: staging)
            try fileManager.copyItem(at: vocoder, to: staging.appendingPathComponent(VoiceCloningManifest.vocoderName))
            let marker = VoiceCloningManifest.archiveSHA256 + ":" + VoiceCloningManifest.vocoderSHA256
            try marker.write(to: staging.appendingPathComponent("pack.sha256"), atomically: true, encoding: .utf8)
            guard store.validatedModel(at: staging) != nil else { throw PinnedDownloadError.integrityFailed }
            try? fileManager.removeItem(at: versionDirectory)
            try fileManager.moveItem(at: staging, to: versionDirectory)
            try writeActivePointer(root: root)
            try? fileManager.removeItem(at: archive)
            try? fileManager.removeItem(at: vocoder)
            guard let activated = store.model() else { throw PinnedDownloadError.activationFailed }
            await progress(1)
            return activated
        } catch {
            try? fileManager.removeItem(at: staging)
            throw error
        }
    }

    func deleteInstalledModel() throws {
        let root = try store.rootURL(create: false)
        if fileManager.fileExists(atPath: root.path) {
            try fileManager.removeItem(at: root)
        }
    }

    private func extractArchive(archive: URL, to staging: URL) throws {
        let compressed = try Data(contentsOf: archive, options: [.mappedIfSafe])
        guard Int64(compressed.count) == VoiceCloningManifest.archiveBytes else {
            throw PinnedDownloadError.integrityFailed
        }
        let tarData = try BZip2.decompress(data: compressed)
        let entries = try TarContainer.open(container: tarData)
        let prefix = VoiceCloningManifest.archiveRoot + "/"
        let stagingPrefix = staging.standardizedFileURL.path + "/"
        var entryCount = 0
        var extractedBytes: Int64 = 0

        for entry in entries {
            try Task.checkCancellation()
            entryCount += 1
            guard entryCount <= Self.maximumEntries else { throw PinnedDownloadError.integrityFailed }
            let name = entry.info.name
            guard name.hasPrefix(prefix) else { continue }
            let relative = String(name.dropFirst(prefix.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard !relative.isEmpty else { continue }
            let components = relative.split(separator: "/", omittingEmptySubsequences: false)
            guard !relative.contains("\\"),
                  components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." })
            else { throw PinnedDownloadError.integrityFailed }
            let allowed = ["encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt", "lexicon.txt"].contains(relative)
                || relative.hasPrefix("espeak-ng-data/")
            guard allowed else { continue }
            guard let data = entry.data else { continue }
            extractedBytes += Int64(data.count)
            guard extractedBytes <= Self.maximumExtractedBytes else { throw PinnedDownloadError.integrityFailed }
            let output = staging.appendingPathComponent(relative).standardizedFileURL
            guard output.path.hasPrefix(stagingPrefix) else { throw PinnedDownloadError.integrityFailed }
            try fileManager.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: output, options: [.atomic])
        }
    }

    private func ensureStorage(at root: URL) throws {
        let values = try root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        let required = VoiceCloningManifest.totalDownloadBytes + Self.maximumExtractedBytes + Self.storageSafetyMarginBytes
        if let available = values.volumeAvailableCapacityForImportantUsage,
           available < required {
            throw NSError(
                domain: "LingoPlay.VoiceCloning",
                code: 2,
                userInfo: [NSLocalizedDescriptionKey: "Not enough storage for Voice Cloning. Free at least \(MediaFormatting.bytes(required))."]
            )
        }
    }

    private func writeActivePointer(root: URL) throws {
        let pointer = root.appendingPathComponent(Self.activePointerName)
        let temporary = root.appendingPathComponent(Self.activePointerName + ".tmp")
        try VoiceCloningManifest.version.write(to: temporary, atomically: true, encoding: .utf8)
        if fileManager.fileExists(atPath: pointer.path) {
            _ = try fileManager.replaceItemAt(pointer, withItemAt: temporary)
        } else {
            try fileManager.moveItem(at: temporary, to: pointer)
        }
    }
}

enum VoiceCloneReferenceBuilder {
    private static let referenceSampleRate = 24_000.0

    nonisolated static func build(audioURL: URL, transcript: ASRTranscript) async throws -> [String: VoiceCloneReference] {
        var result: [String: VoiceCloneReference] = [:]
        for (speaker, segment) in VoiceCloningPolicy.eligibleReferenceSegments(transcript) {
            try Task.checkCancellation()
            let samples = try SpeakerDiarizationService.decodeMono(
                audioURL: audioURL, sampleRate: referenceSampleRate, maximumSeconds: 16,
                startSeconds: segment.start, durationSeconds: segment.end - segment.start
            )
            guard Double(samples.count) / referenceSampleRate >= segment.end - segment.start - 0.05 else { continue }
            result[speaker] = VoiceCloneReference(
                samples: samples, sampleRate: Int(referenceSampleRate), referenceText: segment.text
            )
        }
        return result
    }
}

actor VoiceCloningTTSService {
    func synthesize(
        document: TranslationDocument,
        model: InstalledVoiceCloningModel,
        references: [String: VoiceCloneReference],
        progress: @MainActor @Sendable (Int, Int) -> Void
    ) async throws -> DubSpeechDocument {
        guard VoiceCloningPolicy.supportsPair(source: document.sourceLanguage, target: document.targetLanguage) else {
            throw TTSError.synthesisFailed("Voice Cloning requires English or Chinese reference speech and output.")
        }
        try Task.checkCancellation()
        let zipvoice = sherpaOnnxOfflineTtsZipvoiceModelConfig(
            tokens: model.tokensURL.path,
            encoder: model.encoderURL.path,
            decoder: model.decoderURL.path,
            vocoder: model.vocoderURL.path,
            dataDir: model.dataDirectoryURL.path,
            lexicon: model.lexiconURL.path
        )
        let modelConfig = sherpaOnnxOfflineTtsModelConfig(
            numThreads: NeuralTTSPerformancePolicy.threadCount(
                availableProcessors: ProcessInfo.processInfo.activeProcessorCount
            ),
            provider: "cpu",
            zipvoice: zipvoice
        )
        var config = sherpaOnnxOfflineTtsConfig(model: modelConfig, silenceScale: 0.2)
        let tts = SherpaOnnxOfflineTtsWrapper(config: &config)
        guard tts.tts != nil else {
            throw TTSError.synthesisFailed("Unable to initialize the installed Voice Cloning model.")
        }

        let root = try makeSessionDirectory()
        var succeeded = false
        defer {
            if !succeeded { try? FileManager.default.removeItem(at: root) }
        }
        var output: [DubSpeechSegment] = []
        for (index, segment) in document.segments.enumerated() {
            guard let speakerID = segment.speakerID,
                  segment.overlappingSpeakerIDs.isEmpty,
                  let reference = references[speakerID]
            else {
                throw TTSError.synthesisFailed("No consented single-speaker reference is available for this segment.")
            }
            output.append(try synthesizeSegment(segment, reference: reference, tts: tts, root: root))
            await progress(index + 1, document.segments.count)
        }
        try Task.checkCancellation()
        succeeded = true
        return DubSpeechDocument(voiceIdentifier: "clone:zipvoice", segments: output)
    }

    private func synthesizeSegment(
        _ segment: TranslationSegment,
        reference: VoiceCloneReference,
        tts: SherpaOnnxOfflineTtsWrapper,
        root: URL
    ) throws -> DubSpeechSegment {
        let targetMs = DurationFitPolicy.targetDurationMs(startMs: segment.startMs, endMs: segment.endMs)
        var multiplier: Float = 1
        for attempt in 0..<DurationFitPolicy.maximumAttempts {
            try Task.checkCancellation()
            let fileURL = root.appendingPathComponent("\(segment.id)-\(attempt).wav")
            try? FileManager.default.removeItem(at: fileURL)
            let generation = SherpaOnnxGenerationConfigSwift(
                speed: multiplier,
                referenceAudio: reference.samples,
                referenceSampleRate: reference.sampleRate,
                referenceText: reference.referenceText,
                numSteps: 4,
                extra: ["min_char_in_sentence": "10"]
            )
            let audio = tts.generateWithConfig(
                text: segment.spokenText,
                config: generation,
                callback: nil,
                arg: nil
            )
            try Task.checkCancellation()
            guard audio.audio != nil, audio.n > 0, audio.sampleRate > 0 else {
                throw TTSError.synthesisFailed("Voice Cloning produced no audio for segment \(segment.id).")
            }
            guard audio.save(filename: fileURL.path) == 1,
                  FileManager.default.fileExists(atPath: fileURL.path)
            else {
                throw TTSError.synthesisFailed("Voice Cloning could not save segment \(segment.id).")
            }
            let durationMs = try SynthesizedAudioPolicy.durationMs(of: fileURL)
            let fits = DurationFitPolicy.fits(actualMs: durationMs, targetMs: targetMs)
            let next = DurationFitPolicy.nextRateMultiplier(
                actualMs: durationMs,
                targetMs: targetMs,
                current: multiplier
            )
            let finalAttempt = attempt == DurationFitPolicy.maximumAttempts - 1
            if fits || next == nil || finalAttempt {
                return DubSpeechSegment(
                    id: segment.id,
                    startMs: segment.startMs,
                    endMs: DurationFitPolicy.effectiveEndMs(
                        startMs: segment.startMs,
                        sourceEndMs: segment.endMs,
                        speechDurationMs: durationMs
                    ),
                    audioURL: fileURL,
                    speechDurationMs: durationMs,
                    tailSilenceMs: DurationFitPolicy.tailSilenceMs(actualMs: durationMs, targetMs: targetMs),
                    rateMultiplier: multiplier
                )
            }
            try? FileManager.default.removeItem(at: fileURL)
            multiplier = next ?? multiplier
        }
        throw TTSError.synthesisFailed("No Voice Cloning synthesis attempt was executed.")
    }

    private func makeSessionDirectory() throws -> URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let directory = caches
            .appendingPathComponent("LingoPlay/CloneTTS", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}
