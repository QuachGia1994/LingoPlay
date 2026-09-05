import AVFoundation
import Foundation
@preconcurrency import SherpaOnnx

enum TTSRoute: Equatable {
    case system
    case neural
}

enum TTSRoutingPolicy {
    static func route(
        targetLanguage: String,
        preferredVoiceIdentifier: String?,
        neuralVoiceInstalled: Bool
    ) -> TTSRoute {
        let baseLanguage = targetLanguage.lowercased().split(separator: "-").first.map(String.init)
        return baseLanguage == "vi" &&
            preferredVoiceIdentifier == NeuralVoicePackManifest.voiceIdentifier &&
            neuralVoiceInstalled
            ? .neural
            : .system
    }
}

enum NeuralTTSPerformancePolicy {
    static func threadCount(availableProcessors: Int) -> Int {
        min(2, max(1, max(1, availableProcessors) / 2))
    }
}

actor NeuralVietnameseTTSService {
    func synthesize(
        document: TranslationDocument,
        model: InstalledNeuralVoice,
        progress: @MainActor @Sendable (Int, Int) -> Void
    ) async throws -> DubSpeechDocument {
        let baseLanguage = document.targetLanguage.lowercased().split(separator: "-").first.map(String.init)
        guard baseLanguage == "vi" else {
            throw TTSError.synthesisFailed("The installed Neural Voice supports Vietnamese output only.")
        }

        let vits = sherpaOnnxOfflineTtsVitsModelConfig(
            model: model.modelURL.path,
            lexicon: "",
            tokens: model.tokensURL.path,
            dataDir: model.dataDirectoryURL.path
        )
        let modelConfig = sherpaOnnxOfflineTtsModelConfig(
            vits: vits,
            numThreads: NeuralTTSPerformancePolicy.threadCount(
                availableProcessors: ProcessInfo.processInfo.activeProcessorCount
            ),
            provider: "cpu"
        )
        var config = sherpaOnnxOfflineTtsConfig(model: modelConfig, silenceScale: 0.2)
        let tts = SherpaOnnxOfflineTtsWrapper(config: &config)
        guard tts.tts != nil else {
            throw TTSError.synthesisFailed("Unable to initialize the installed Neural Voice model.")
        }

        let root = try makeSessionDirectory()
        var succeeded = false
        defer {
            if !succeeded { try? FileManager.default.removeItem(at: root) }
        }
        var output: [DubSpeechSegment] = []
        for (index, segment) in document.segments.enumerated() {
            output.append(try synthesizeSegment(segment, tts: tts, root: root))
            await progress(index + 1, document.segments.count)
        }
        succeeded = true
        return DubSpeechDocument(
            voiceIdentifier: NeuralVoicePackManifest.voiceIdentifier,
            segments: output
        )
    }

    private func synthesizeSegment(
        _ segment: TranslationSegment,
        tts: SherpaOnnxOfflineTtsWrapper,
        root: URL
    ) throws -> DubSpeechSegment {
        let targetMs = DurationFitPolicy.targetDurationMs(startMs: segment.startMs, endMs: segment.endMs)
        var multiplier: Float = 1

        for attempt in 0..<DurationFitPolicy.maximumAttempts {
            let fileURL = root.appendingPathComponent("\(segment.id)-\(attempt).wav")
            try? FileManager.default.removeItem(at: fileURL)
            let audio = tts.generate(text: segment.translatedText, sid: 0, speed: multiplier)
            guard audio.audio != nil, audio.n > 0, audio.sampleRate > 0 else {
                throw TTSError.synthesisFailed("Neural Voice produced no audio for segment \(segment.id).")
            }
            guard audio.save(filename: fileURL.path) == 1,
                  FileManager.default.fileExists(atPath: fileURL.path)
            else {
                throw TTSError.synthesisFailed("Neural Voice could not save segment \(segment.id).")
            }

            let durationMs = max(
                1,
                Int((Double(audio.n) * 1_000 / Double(audio.sampleRate)).rounded())
            )
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
                    tailSilenceMs: DurationFitPolicy.tailSilenceMs(
                        actualMs: durationMs,
                        targetMs: targetMs
                    ),
                    rateMultiplier: multiplier
                )
            }
            try? FileManager.default.removeItem(at: fileURL)
            multiplier = next ?? multiplier
        }
        throw TTSError.synthesisFailed("No Neural Voice synthesis attempt was executed.")
    }

    private func makeSessionDirectory() throws -> URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let directory = caches
            .appendingPathComponent("LingoPlay/NeuralTTS", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}

@MainActor
final class OfflineDubbingTTSService {
    private let system = SystemVietnameseTTSService()
    private let neural = NeuralVietnameseTTSService()
    private let neuralStore = NeuralVoiceModelStore()
    private let cloning = VoiceCloningTTSService()
    private let cloningStore = VoiceCloningModelStore()

    func synthesize(
        document: TranslationDocument,
        preferredVoiceIdentifier: String?,
        speakerVoiceMap: [String: String] = [:],
        cloneReferences: [String: VoiceCloneReference] = [:],
        progress: @MainActor @Sendable (Int, Int) -> Void
    ) async throws -> DubSpeechDocument {
        guard !cloneReferences.isEmpty else {
            return try await synthesizeRegular(
                document: document,
                preferredVoiceIdentifier: preferredVoiceIdentifier,
                speakerVoiceMap: speakerVoiceMap,
                progress: progress
            )
        }

        let cloneable = document.segments.filter { segment in
            segment.overlappingSpeakerIDs.isEmpty &&
                segment.speakerID.flatMap { cloneReferences[$0] } != nil
        }
        let cloneableIDs = Set(cloneable.map(\.id))
        let fallback = document.segments.filter { !cloneableIDs.contains($0.id) }
        var output: [DubSpeechSegment] = []
        var completed = 0

        if !cloneable.isEmpty {
            guard let model = cloningStore.model() else {
                throw TTSError.synthesisFailed("Voice Cloning model is not installed.")
            }
            let cloned = try await cloning.synthesize(
                document: TranslationDocument(
                    sourceLanguage: document.sourceLanguage,
                    targetLanguage: document.targetLanguage,
                    segments: cloneable,
                    mode: document.mode,
                    speakerVoiceMap: document.speakerVoiceMap
                ),
                model: model,
                references: cloneReferences
            ) { local, _ in
                progress(completed + local, document.segments.count)
            }
            output.append(contentsOf: cloned.segments)
            completed += cloneable.count
        }

        if !fallback.isEmpty {
            let normal = try await synthesizeRegular(
                document: TranslationDocument(
                    sourceLanguage: document.sourceLanguage,
                    targetLanguage: document.targetLanguage,
                    segments: fallback,
                    mode: document.mode,
                    speakerVoiceMap: document.speakerVoiceMap
                ),
                preferredVoiceIdentifier: preferredVoiceIdentifier,
                speakerVoiceMap: speakerVoiceMap
            ) { local, _ in
                progress(completed + local, document.segments.count)
            }
            output.append(contentsOf: normal.segments)
        }

        let order = Dictionary(uniqueKeysWithValues: document.segments.enumerated().map { ($1.id, $0) })
        output.sort { (order[$0.id] ?? .max) < (order[$1.id] ?? .max) }
        return DubSpeechDocument(voiceIdentifier: "hybrid:clone-local", segments: output)
    }

    private func synthesizeRegular(
        document: TranslationDocument,
        preferredVoiceIdentifier: String?,
        speakerVoiceMap: [String: String],
        progress: @MainActor @Sendable (Int, Int) -> Void
    ) async throws -> DubSpeechDocument {
        if speakerVoiceMap.isEmpty {
            return try await synthesizeWithVoice(
                document: document,
                preferredVoiceIdentifier: preferredVoiceIdentifier,
                progress: progress
            )
        }

        var grouped: [(voiceID: String?, segments: [TranslationSegment])] = []
        for segment in document.segments {
            let voiceID = segment.speakerID.flatMap { speakerVoiceMap[$0] } ?? preferredVoiceIdentifier
            if let index = grouped.firstIndex(where: { $0.voiceID == voiceID }) {
                grouped[index].segments.append(segment)
            } else {
                grouped.append((voiceID, [segment]))
            }
        }

        var output: [DubSpeechSegment] = []
        var completed = 0
        for group in grouped {
            let partial = try await synthesizeWithVoice(
                document: TranslationDocument(
                    sourceLanguage: document.sourceLanguage,
                    targetLanguage: document.targetLanguage,
                    segments: group.segments,
                    mode: document.mode,
                    speakerVoiceMap: document.speakerVoiceMap
                ),
                preferredVoiceIdentifier: group.voiceID
            ) { local, _ in
                progress(completed + local, document.segments.count)
            }
            output.append(contentsOf: partial.segments)
            completed += group.segments.count
        }
        let order = Dictionary(uniqueKeysWithValues: document.segments.enumerated().map { ($1.id, $0) })
        output.sort { (order[$0.id] ?? .max) < (order[$1.id] ?? .max) }
        return DubSpeechDocument(
            voiceIdentifier: "multi-speaker:\(grouped.count)",
            segments: output
        )
    }

    private func synthesizeWithVoice(
        document: TranslationDocument,
        preferredVoiceIdentifier: String?,
        progress: @MainActor @Sendable (Int, Int) -> Void
    ) async throws -> DubSpeechDocument {
        let neuralModel = neuralStore.model()
        switch TTSRoutingPolicy.route(
            targetLanguage: document.targetLanguage,
            preferredVoiceIdentifier: preferredVoiceIdentifier,
            neuralVoiceInstalled: neuralModel != nil
        ) {
        case .neural:
            return try await neural.synthesize(
                document: document,
                model: neuralModel!,
                progress: progress
            )
        case .system:
            let systemIdentifier = preferredVoiceIdentifier == NeuralVoicePackManifest.voiceIdentifier
                ? nil
                : preferredVoiceIdentifier
            return try await system.synthesize(
                document: document,
                preferredVoiceIdentifier: systemIdentifier,
                progress: progress
            )
        }
    }
}
