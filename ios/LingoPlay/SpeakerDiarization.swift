import AVFoundation
import Foundation
@preconcurrency import SherpaOnnx
@preconcurrency import SWCompression

enum SpeakerMode: String, CaseIterable, Identifiable, Codable, Sendable {
    case single
    case multi

    var id: String { rawValue }
    var label: String { self == .single ? "Single voice" : "Multi-speaker" }
}

enum SpeakerState: Equatable {
    case idle
    case modelMissing
    case analyzing
    case completed(SpeakerDiarizationDocument)
    case failed(String)
}

struct SpeakerTurn: Sendable, Equatable, Codable {
    let startMs: Int
    let endMs: Int
    let speakerID: String
}

struct SpeakerAttribution: Sendable, Equatable {
    let speakerID: String?
    let overlappingSpeakerIDs: [String]

    init(speakerID: String?, overlappingSpeakerIDs: [String] = []) {
        self.speakerID = speakerID
        self.overlappingSpeakerIDs = overlappingSpeakerIDs
    }
}

struct SpeakerDiarizationDocument: Sendable, Equatable {
    let turns: [SpeakerTurn]
    let speakerIDs: [String]
}

enum SpeakerDiarizationPolicy {
    private static let minimumPrimaryOverlapMs = 120
    private static let overlapMinimumMs = 120
    private static let overlapRatio = 0.35

    static func normalize(_ turns: [(start: Float, end: Float, speaker: Int)]) -> SpeakerDiarizationDocument {
        let valid = turns
            .filter { $0.start.isFinite && $0.end.isFinite && $0.end > $0.start }
            .sorted {
                $0.start == $1.start ? $0.end < $1.end : $0.start < $1.start
            }
        var labels: [Int: String] = [:]
        var orderedLabels: [String] = []
        let normalized = valid.map { turn -> SpeakerTurn in
            let speakerID: String
            if let existing = labels[turn.speaker] {
                speakerID = existing
            } else {
                speakerID = "speaker_\(orderedLabels.count + 1)"
                labels[turn.speaker] = speakerID
                orderedLabels.append(speakerID)
            }
            let startMs = max(0, Int((Double(turn.start) * 1_000).rounded()))
            let endMs = max(startMs + 1, Int((Double(turn.end) * 1_000).rounded()))
            return SpeakerTurn(startMs: startMs, endMs: endMs, speakerID: speakerID)
        }
        return SpeakerDiarizationDocument(turns: normalized, speakerIDs: orderedLabels)
    }

    static func attribution(
        startMs: Int,
        endMs: Int,
        document: SpeakerDiarizationDocument
    ) -> SpeakerAttribution {
        let rangeStart = max(0, startMs)
        let rangeEnd = max(rangeStart + 1, endMs)
        var overlapBySpeaker: [String: Int] = [:]
        for turn in document.turns {
            let overlap = min(rangeEnd, turn.endMs) - max(rangeStart, turn.startMs)
            if overlap > 0 {
                overlapBySpeaker[turn.speakerID, default: 0] += overlap
            }
        }
        let ranked = overlapBySpeaker.sorted { lhs, rhs in
            lhs.value == rhs.value ? lhs.key < rhs.key : lhs.value > rhs.value
        }
        guard let primary = ranked.first, primary.value >= minimumPrimaryOverlapMs else {
            return SpeakerAttribution(speakerID: nil)
        }
        let overlapping = ranked.dropFirst().compactMap { item -> String? in
            guard item.value >= overlapMinimumMs,
                  Double(item.value) >= Double(primary.value) * overlapRatio
            else { return nil }
            return item.key
        }
        if overlapping.isEmpty {
            return SpeakerAttribution(speakerID: primary.key)
        }
        return SpeakerAttribution(
            speakerID: nil,
            overlappingSpeakerIDs: [primary.key] + overlapping
        )
    }

    static func annotate(
        transcript: ASRTranscript,
        document: SpeakerDiarizationDocument
    ) -> ASRTranscript {
        let segments = transcript.segments.map { segment in
            let attribution = attribution(
                startMs: Int((segment.start * 1_000).rounded()),
                endMs: Int((segment.end * 1_000).rounded()),
                document: document
            )
            return ASRSegment(
                id: segment.id,
                start: segment.start,
                end: segment.end,
                text: segment.text,
                speakerID: attribution.speakerID,
                overlappingSpeakerIDs: attribution.overlappingSpeakerIDs
            )
        }
        return ASRTranscript(language: transcript.language, text: transcript.text, segments: segments)
    }
}

enum SpeakerVoicePolicy {
    static func resolve(
        speakerIDs: [String],
        availableVoices: [OfflineVoiceOption],
        targetLanguage: String,
        preferredVoiceIdentifier: String?,
        existing: [String: String] = [:]
    ) -> [String: String] {
        let language = targetLanguage.lowercased().split(separator: "-").first.map(String.init) ?? targetLanguage.lowercased()
        var seen = Set<String>()
        let candidates = availableVoices.filter { voice in
            let base = voice.languageCode.lowercased().split(separator: "-").first.map(String.init) ?? voice.languageCode.lowercased()
            return base == language && seen.insert(voice.id).inserted
        }
        guard !candidates.isEmpty else { return [:] }
        let validIDs = Set(candidates.map(\.id))
        var used = Set<String>()
        var result: [String: String] = [:]

        for (index, speakerID) in speakerIDs.enumerated() {
            let preserved = existing[speakerID].flatMap { validIDs.contains($0) && !used.contains($0) ? $0 : nil }
            let preferred = index == 0
                ? preferredVoiceIdentifier.flatMap { validIDs.contains($0) && !used.contains($0) ? $0 : nil }
                : nil
            let next = preserved
                ?? preferred
                ?? candidates.first(where: { !used.contains($0.id) })?.id
                ?? candidates[index % candidates.count].id
            result[speakerID] = next
            used.insert(next)
        }
        return result
    }
}

enum SpeakerDiarizationManifest {
    static let version = "pyannote3-int8-titanet-small-v1"
    static let archiveRoot = "sherpa-onnx-pyannote-segmentation-3-0"
    static let segmentationArchiveName = "sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
    static let segmentationArchiveBytes: Int64 = 6_958_444
    static let segmentationArchiveSHA256 = "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488"
    static let segmentationModelName = "model.int8.onnx"
    static let segmentationModelBytes: Int64 = 1_540_506
    static let segmentationModelSHA256 = "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d"
    static let embeddingModelName = "nemo_en_titanet_small.onnx"
    static let embeddingModelBytes: Int64 = 40_257_283
    static let embeddingModelSHA256 = "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e"
    static let segmentationArchiveURL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/\(segmentationArchiveName)"
    static let embeddingModelURL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/\(embeddingModelName)"
    static let totalDownloadBytes = segmentationArchiveBytes + embeddingModelBytes
}

struct InstalledSpeakerDiarizationModel: Sendable, Equatable {
    let segmentationURL: URL
    let embeddingURL: URL
}

struct SpeakerDiarizationModelStore {
    private static let activePointerName = "active-model.txt"
    private let fileManager = FileManager.default

    func model() -> InstalledSpeakerDiarizationModel? {
        guard let root = try? rootURL(create: false),
              let version = try? String(
                contentsOf: root.appendingPathComponent(Self.activePointerName),
                encoding: .utf8
              ).trimmingCharacters(in: .whitespacesAndNewlines),
              version == SpeakerDiarizationManifest.version
        else { return nil }
        return validatedModel(at: root.appendingPathComponent(version, isDirectory: true))
    }

    func validatedModel(at root: URL) -> InstalledSpeakerDiarizationModel? {
        let marker = root.appendingPathComponent("pack.sha256")
        let expectedMarker = SpeakerDiarizationManifest.segmentationArchiveSHA256 + ":" + SpeakerDiarizationManifest.embeddingModelSHA256
        guard (try? String(contentsOf: marker, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)) == expectedMarker
        else { return nil }

        let segmentation = root.appendingPathComponent(SpeakerDiarizationManifest.segmentationModelName)
        let embedding = root.appendingPathComponent(SpeakerDiarizationManifest.embeddingModelName)
        let segmentationSpec = PinnedDownloadSpec(
            name: SpeakerDiarizationManifest.segmentationModelName,
            url: "",
            bytes: SpeakerDiarizationManifest.segmentationModelBytes,
            sha256: SpeakerDiarizationManifest.segmentationModelSHA256
        )
        let embeddingSpec = PinnedDownloadSpec(
            name: SpeakerDiarizationManifest.embeddingModelName,
            url: "",
            bytes: SpeakerDiarizationManifest.embeddingModelBytes,
            sha256: SpeakerDiarizationManifest.embeddingModelSHA256
        )
        guard PinnedFileIntegrity.matches(segmentation, spec: segmentationSpec),
              PinnedFileIntegrity.matches(embedding, spec: embeddingSpec)
        else { return nil }
        return InstalledSpeakerDiarizationModel(segmentationURL: segmentation, embeddingURL: embedding)
    }

    func rootURL(create: Bool) throws -> URL {
        guard let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            throw PinnedDownloadError.activationFailed
        }
        let root = support.appendingPathComponent("LingoPlay/Models/SpeakerDiarization", isDirectory: true)
        if create { try fileManager.createDirectory(at: root, withIntermediateDirectories: true) }
        return root
    }
}

actor SpeakerDiarizationModelInstaller {
    private static let activePointerName = "active-model.txt"
    private static let storageSafetyMarginBytes: Int64 = 64 * 1024 * 1024
    private let fileManager = FileManager.default
    private let store = SpeakerDiarizationModelStore()

    func state() -> ASRModelInstallState {
        guard let model = store.model() else { return .notInstalled }
        let bytes = (PinnedFileIntegrity.fileSize(model.segmentationURL) ?? 0) +
            (PinnedFileIntegrity.fileSize(model.embeddingURL) ?? 0)
        return .installed(bytes: bytes)
    }

    func install(
        wifiOnly: Bool,
        progress: @escaping @Sendable (Double) async -> Void
    ) async throws -> InstalledSpeakerDiarizationModel {
        if wifiOnly {
            let usingWiFi = await ModelNetworkPolicy.isUsingWiFi()
            guard usingWiFi else {
                throw NSError(
                    domain: "LingoPlay.SpeakerAI",
                    code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "Connect to Wi-Fi or disable ‘Download models on Wi-Fi only’ before installing Speaker AI."]
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
            name: SpeakerDiarizationManifest.segmentationArchiveName,
            url: SpeakerDiarizationManifest.segmentationArchiveURL,
            bytes: SpeakerDiarizationManifest.segmentationArchiveBytes,
            sha256: SpeakerDiarizationManifest.segmentationArchiveSHA256
        )
        let embeddingSpec = PinnedDownloadSpec(
            name: SpeakerDiarizationManifest.embeddingModelName,
            url: SpeakerDiarizationManifest.embeddingModelURL,
            bytes: SpeakerDiarizationManifest.embeddingModelBytes,
            sha256: SpeakerDiarizationManifest.embeddingModelSHA256
        )

        let archive = try await PinnedModelDownload.downloadVerified(spec: archiveSpec, root: root) { done in
            await progress(Double(done) / Double(SpeakerDiarizationManifest.totalDownloadBytes))
        }
        let embedding = try await PinnedModelDownload.downloadVerified(spec: embeddingSpec, root: root) { done in
            let aggregate = SpeakerDiarizationManifest.segmentationArchiveBytes + done
            await progress(min(1, Double(aggregate) / Double(SpeakerDiarizationManifest.totalDownloadBytes)))
        }
        try Task.checkCancellation()

        let staging = root.appendingPathComponent(SpeakerDiarizationManifest.version + ".staging", isDirectory: true)
        let versionDirectory = root.appendingPathComponent(SpeakerDiarizationManifest.version, isDirectory: true)
        try? fileManager.removeItem(at: staging)
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: true)
        do {
            try extractSegmentation(archive: archive, to: staging)
            try fileManager.copyItem(
                at: embedding,
                to: staging.appendingPathComponent(SpeakerDiarizationManifest.embeddingModelName)
            )
            let marker = SpeakerDiarizationManifest.segmentationArchiveSHA256 + ":" + SpeakerDiarizationManifest.embeddingModelSHA256
            try marker.write(to: staging.appendingPathComponent("pack.sha256"), atomically: true, encoding: .utf8)
            guard store.validatedModel(at: staging) != nil else {
                throw PinnedDownloadError.integrityFailed
            }
            try? fileManager.removeItem(at: versionDirectory)
            try fileManager.moveItem(at: staging, to: versionDirectory)
            try writeActivePointer(root: root)
            try? fileManager.removeItem(at: archive)
            try? fileManager.removeItem(at: embedding)
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

    private func extractSegmentation(archive: URL, to staging: URL) throws {
        let compressed = try Data(contentsOf: archive, options: [.mappedIfSafe])
        guard Int64(compressed.count) == SpeakerDiarizationManifest.segmentationArchiveBytes else {
            throw PinnedDownloadError.integrityFailed
        }
        let tarData = try BZip2.decompress(data: compressed)
        let entries = try TarContainer.open(container: tarData)
        let expected = SpeakerDiarizationManifest.archiveRoot + "/" + SpeakerDiarizationManifest.segmentationModelName
        guard let entry = entries.first(where: { $0.info.name == expected }),
              let data = entry.data,
              Int64(data.count) == SpeakerDiarizationManifest.segmentationModelBytes
        else { throw PinnedDownloadError.integrityFailed }
        let output = staging.appendingPathComponent(SpeakerDiarizationManifest.segmentationModelName)
        try data.write(to: output, options: [.atomic])
        let spec = PinnedDownloadSpec(
            name: SpeakerDiarizationManifest.segmentationModelName,
            url: "",
            bytes: SpeakerDiarizationManifest.segmentationModelBytes,
            sha256: SpeakerDiarizationManifest.segmentationModelSHA256
        )
        guard PinnedFileIntegrity.matches(output, spec: spec) else {
            throw PinnedDownloadError.integrityFailed
        }
    }

    private func ensureStorage(at root: URL) throws {
        let values = try root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        let required = SpeakerDiarizationManifest.totalDownloadBytes +
            SpeakerDiarizationManifest.segmentationModelBytes +
            SpeakerDiarizationManifest.embeddingModelBytes +
            Self.storageSafetyMarginBytes
        if let available = values.volumeAvailableCapacityForImportantUsage,
           available < required {
            throw NSError(
                domain: "LingoPlay.SpeakerAI",
                code: 2,
                userInfo: [NSLocalizedDescriptionKey: "Not enough storage for Speaker AI. Free at least \(MediaFormatting.bytes(required))."]
            )
        }
    }

    private func writeActivePointer(root: URL) throws {
        let pointer = root.appendingPathComponent(Self.activePointerName)
        let temporary = root.appendingPathComponent(Self.activePointerName + ".tmp")
        try SpeakerDiarizationManifest.version.write(to: temporary, atomically: true, encoding: .utf8)
        if fileManager.fileExists(atPath: pointer.path) {
            _ = try fileManager.replaceItemAt(pointer, withItemAt: temporary)
        } else {
            try fileManager.moveItem(at: temporary, to: pointer)
        }
    }
}

actor SpeakerDiarizationService {
    static let maximumAudioSeconds = 15 * 60

    func diarize(
        audioURL: URL,
        model: InstalledSpeakerDiarizationModel
    ) async throws -> SpeakerDiarizationDocument {
        var config = sherpaOnnxOfflineSpeakerDiarizationConfig(
            segmentation: sherpaOnnxOfflineSpeakerSegmentationModelConfig(
                pyannote: sherpaOnnxOfflineSpeakerSegmentationPyannoteModelConfig(
                    model: model.segmentationURL.path,
                    windowShiftRatio: 0.1
                ),
                numThreads: Self.threadCount,
                provider: "cpu"
            ),
            embedding: sherpaOnnxSpeakerEmbeddingExtractorConfig(
                model: model.embeddingURL.path,
                numThreads: Self.threadCount,
                provider: "cpu"
            ),
            clustering: sherpaOnnxFastClusteringConfig(numClusters: 0, threshold: 0.9),
            minDurationOn: 0.2,
            minDurationOff: 0.5
        )
        let diarizer = SherpaOnnxOfflineSpeakerDiarizationWrapper(config: &config)
        guard diarizer.impl != nil, diarizer.sampleRate > 0 else {
            throw NSError(
                domain: "LingoPlay.SpeakerAI",
                code: 3,
                userInfo: [NSLocalizedDescriptionKey: "Unable to initialize the installed Speaker AI models."]
            )
        }
        let samples = try Self.decodeMono(
            audioURL: audioURL,
            sampleRate: Double(diarizer.sampleRate),
            maximumSeconds: Self.maximumAudioSeconds
        )
        try Task.checkCancellation()
        let raw = diarizer.process(samples: samples).map { segment in
            (start: segment.start, end: segment.end, speaker: Int(segment.speaker))
        }
        return SpeakerDiarizationPolicy.normalize(raw)
    }

    static func referenceSamples(
        audioURL: URL,
        startMs: Int,
        endMs: Int,
        sampleRate: Double = 16_000
    ) throws -> (samples: [Float], sampleRate: Int32) {
        let all = try decodeMono(
            audioURL: audioURL,
            sampleRate: sampleRate,
            maximumSeconds: maximumAudioSeconds
        )
        let start = max(0, Int((Double(startMs) / 1_000 * sampleRate).rounded()))
        let end = min(all.count, max(start + 1, Int((Double(endMs) / 1_000 * sampleRate).rounded())))
        guard start < end else { return ([], Int32(sampleRate)) }
        return (Array(all[start..<end]), Int32(sampleRate))
    }

    private static var threadCount: Int {
        min(2, max(1, ProcessInfo.processInfo.activeProcessorCount / 2))
    }

    static func decodeMono(
        audioURL: URL,
        sampleRate: Double,
        maximumSeconds: Int
    ) throws -> [Float] {
        let file = try AVAudioFile(forReading: audioURL)
        let inputFormat = file.processingFormat
        guard let outputFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: sampleRate,
            channels: 1,
            interleaved: false
        ), let converter = AVAudioConverter(from: inputFormat, to: outputFormat)
        else {
            throw NSError(
                domain: "LingoPlay.SpeakerAI",
                code: 4,
                userInfo: [NSLocalizedDescriptionKey: "Unable to decode audio for local speaker analysis."]
            )
        }

        let maximumSamples = Int64(sampleRate * Double(maximumSeconds))
        var result: [Float] = []
        result.reserveCapacity(Int(min(maximumSamples, Int64(sampleRate * 30))))
        let inputCapacity: AVAudioFrameCount = 8_192
        guard let inputBuffer = AVAudioPCMBuffer(pcmFormat: inputFormat, frameCapacity: inputCapacity) else {
            throw TTSError.invalidAudio
        }

        while file.framePosition < file.length {
            try Task.checkCancellation()
            inputBuffer.frameLength = 0
            try file.read(into: inputBuffer, frameCount: inputCapacity)
            if inputBuffer.frameLength == 0 { break }
            let ratio = sampleRate / inputFormat.sampleRate
            let outputCapacity = AVAudioFrameCount(max(1, Int(ceil(Double(inputBuffer.frameLength) * ratio)) + 32))
            guard let outputBuffer = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: outputCapacity) else {
                throw TTSError.invalidAudio
            }
            var supplied = false
            var conversionError: NSError?
            let status = converter.convert(to: outputBuffer, error: &conversionError) { _, inputStatus in
                if supplied {
                    inputStatus.pointee = .noDataNow
                    return nil
                }
                supplied = true
                inputStatus.pointee = .haveData
                return inputBuffer
            }
            if status == .error {
                throw conversionError ?? TTSError.invalidAudio
            }
            if let channel = outputBuffer.floatChannelData?[0], outputBuffer.frameLength > 0 {
                let count = Int(outputBuffer.frameLength)
                guard Int64(result.count + count) <= maximumSamples else {
                    throw NSError(
                        domain: "LingoPlay.SpeakerAI",
                        code: 5,
                        userInfo: [NSLocalizedDescriptionKey: "Multi-speaker analysis supports up to \(maximumSeconds / 60) minutes per video on this device."]
                    )
                }
                result.append(contentsOf: UnsafeBufferPointer(start: channel, count: count))
            }
        }
        return result
    }
}
