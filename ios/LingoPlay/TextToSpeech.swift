import AVFoundation
import Foundation

struct DubSpeechSegment: Identifiable, Sendable, Equatable {
    let id: String
    let startMs: Int
    let endMs: Int
    let audioURL: URL
    let speechDurationMs: Int
    let tailSilenceMs: Int
    let rateMultiplier: Float
}

struct DubSpeechDocument: Sendable, Equatable {
    let voiceIdentifier: String
    let segments: [DubSpeechSegment]

    var totalTailSilenceMs: Int {
        segments.reduce(0) { $0 + $1.tailSilenceMs }
    }
}

enum TTSState: Equatable {
    case idle
    case voiceMissing
    case synthesizing(segment: Int, totalSegments: Int)
    case completed(DubSpeechDocument)
    case failed(String)
}

enum TTSError: LocalizedError {
    case offlineVoiceMissing(String)
    case synthesisFailed(String)
    case invalidAudio

    var errorDescription: String? {
        switch self {
        case let .offlineVoiceMissing(languageCode):
            "No offline system voice is available for language '\(languageCode)' on this device."
        case let .synthesisFailed(message):
            "Speech synthesis failed: \(message)"
        case .invalidAudio:
            "The synthesized speech file is invalid."
        }
    }
}

enum DurationFitPolicy {
    static let maxRateMultiplier: Float = 1.75
    static let maximumAttempts = 4

    static func targetDurationMs(startMs: Int, endMs: Int) -> Int {
        max(1, endMs - startMs)
    }

    static func toleranceMs(for targetMs: Int) -> Int {
        max(120, Int((Double(targetMs) * 0.06).rounded()))
    }

    static func fits(actualMs: Int, targetMs: Int) -> Bool {
        actualMs <= targetMs + toleranceMs(for: targetMs)
    }

    static func tailSilenceMs(actualMs: Int, targetMs: Int) -> Int {
        max(0, targetMs - actualMs)
    }

    static func effectiveEndMs(startMs: Int, sourceEndMs: Int, speechDurationMs: Int) -> Int {
        let speechEnd = Int64(startMs) + Int64(max(1, speechDurationMs))
        return Int(min(Int64(Int.max), max(Int64(sourceEndMs), speechEnd)))
    }

    static func nextRateMultiplier(
        actualMs: Int,
        targetMs: Int,
        current: Float,
        maximum: Float = maxRateMultiplier
    ) -> Float? {
        guard !fits(actualMs: actualMs, targetMs: targetMs) else { return nil }
        let boundedMaximum = max(1, maximum)
        guard current < boundedMaximum - 0.01 else { return nil }
        let ratio = Float(actualMs) / Float(max(1, targetMs))
        let proposed = max(current * 1.08, current * ratio * 1.02)
        let next = min(boundedMaximum, proposed)
        return next > current + 0.01 ? next : nil
    }
}

enum SynthesizedAudioPolicy {
    static func durationMs(of url: URL) throws -> Int {
        let file = try AVAudioFile(forReading: url)
        let sampleRate = file.processingFormat.sampleRate
        guard sampleRate > 0, file.length > 0 else { throw TTSError.invalidAudio }
        return max(1, Int((Double(file.length) / sampleRate * 1_000).rounded()))
    }
}

enum SystemVoiceRatePolicy {
    static func baseRateScale(languageCode: String) -> Float {
        normalized(languageCode) == "vi" ? 0.82 : 1.0
    }

    static func maximumFitMultiplier(languageCode: String) -> Float {
        normalized(languageCode) == "vi" ? 1.18 : DurationFitPolicy.maxRateMultiplier
    }

    static func effectiveRateScale(languageCode: String, fitMultiplier: Float) -> Float {
        baseRateScale(languageCode: languageCode) * min(
            max(1, fitMultiplier),
            maximumFitMultiplier(languageCode: languageCode)
        )
    }

    private static func normalized(_ code: String) -> String {
        code.lowercased().split(separator: "-").first.map(String.init) ?? code.lowercased()
    }
}

enum TTSSynthesisLivenessPolicy {
    static let minimumTimeoutSeconds = 20.0
    static let maximumTimeoutSeconds = 60.0

    static func timeoutSeconds(textLength: Int, targetDurationMs: Int) -> Double {
        let timelineBudget = (Double(max(1, targetDurationMs)) / 1_000 * 2.0) + 10.0
        let textBudget = (Double(max(1, textLength)) / 5.0) + 10.0
        return min(maximumTimeoutSeconds, max(minimumTimeoutSeconds, timelineBudget, textBudget))
    }

    static func timeoutNanoseconds(textLength: Int, targetDurationMs: Int) -> UInt64 {
        UInt64((timeoutSeconds(textLength: textLength, targetDurationMs: targetDurationMs) * 1_000_000_000).rounded())
    }
}

@MainActor
final class SystemVietnameseTTSService {
    func synthesize(
        document: TranslationDocument,
        preferredVoiceIdentifier: String? = nil,
        progress: @MainActor @Sendable (Int, Int) -> Void
    ) async throws -> DubSpeechDocument {
        guard let voice = offlineVoice(languageCode: document.targetLanguage, preferredIdentifier: preferredVoiceIdentifier) else {
            throw TTSError.offlineVoiceMissing(document.targetLanguage)
        }
        let root = try makeSessionDirectory()
        let synthesizer = AVSpeechSynthesizer()
        var succeeded = false
        defer {
            _ = synthesizer.stopSpeaking(at: .immediate)
            if !succeeded { try? FileManager.default.removeItem(at: root) }
        }
        var output: [DubSpeechSegment] = []

        for (index, segment) in document.segments.enumerated() {
            let synthesized = try await synthesizeSegment(
                segment,
                voice: voice,
                root: root,
                synthesizer: synthesizer
            )
            output.append(synthesized)
            progress(index + 1, document.segments.count)
        }

        succeeded = true
        return DubSpeechDocument(voiceIdentifier: voice.identifier, segments: output)
    }

    private func offlineVoice(languageCode: String, preferredIdentifier: String?) -> AVSpeechSynthesisVoice? {
        let normalized = languageCode.lowercased().split(separator: "-").first.map(String.init) ?? languageCode.lowercased()
        let eligible = AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language.lowercased().hasPrefix(normalized) }
        if let preferredIdentifier,
           let preferred = eligible.first(where: { $0.identifier == preferredIdentifier }) {
            return preferred
        }
        return eligible.max { lhs, rhs in lhs.quality.rawValue < rhs.quality.rawValue }
    }

    private func makeSessionDirectory() throws -> URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let directory = caches
            .appendingPathComponent("LingoPlay/TTS", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private func synthesizeSegment(
        _ segment: TranslationSegment,
        voice: AVSpeechSynthesisVoice,
        root: URL,
        synthesizer: AVSpeechSynthesizer
    ) async throws -> DubSpeechSegment {
        let targetMs = DurationFitPolicy.targetDurationMs(startMs: segment.startMs, endMs: segment.endMs)
        var multiplier: Float = 1.0

        for attempt in 0..<DurationFitPolicy.maximumAttempts {
            let fileURL = root.appendingPathComponent("\(segment.id)-\(attempt).caf")
            try? FileManager.default.removeItem(at: fileURL)
            let spokenText = segment.spokenText
            try await synthesizeOnce(
                text: spokenText,
                voice: voice,
                multiplier: multiplier,
                to: fileURL,
                synthesizer: synthesizer,
                timeoutNanoseconds: TTSSynthesisLivenessPolicy.timeoutNanoseconds(
                    textLength: spokenText.count,
                    targetDurationMs: targetMs
                )
            )
            let durationMs = try SynthesizedAudioPolicy.durationMs(of: fileURL)

            let fits = DurationFitPolicy.fits(actualMs: durationMs, targetMs: targetMs)
            let next = DurationFitPolicy.nextRateMultiplier(
                actualMs: durationMs,
                targetMs: targetMs,
                current: multiplier,
                maximum: SystemVoiceRatePolicy.maximumFitMultiplier(languageCode: voice.language)
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

        throw TTSError.synthesisFailed("No speech synthesis attempt was executed.")
    }

    private func synthesizeOnce(
        text: String,
        voice: AVSpeechSynthesisVoice,
        multiplier: Float,
        to url: URL,
        synthesizer: AVSpeechSynthesizer,
        timeoutNanoseconds: UInt64
    ) async throws {
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voice
        let effectiveScale = SystemVoiceRatePolicy.effectiveRateScale(
            languageCode: voice.language,
            fitMultiplier: multiplier
        )
        utterance.rate = min(
            AVSpeechUtteranceMaximumSpeechRate,
            max(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceDefaultSpeechRate * effectiveScale)
        )
        utterance.preUtteranceDelay = 0
        utterance.postUtteranceDelay = 0

        let writer = SpeechBufferFileWriter(url: url)
        try await withTaskCancellationHandler(
            operation: {
                try await withCheckedThrowingContinuation { continuation in
                    guard writer.begin(continuation: continuation) else {
                        continuation.resume(throwing: CancellationError())
                        return
                    }
                    let watchdog = Task { @MainActor [writer] in
                        do {
                            try await Task.sleep(nanoseconds: timeoutNanoseconds)
                        } catch {
                            return
                        }
                        writer.timeout(
                            TTSError.synthesisFailed("Offline voice callback timed out; retry this job.")
                        )
                    }
                    guard writer.retain(synthesizer, watchdog: watchdog) else {
                        watchdog.cancel()
                        return
                    }
                    guard !Task.isCancelled else {
                        writer.cancel()
                        return
                    }
                    synthesizer.write(utterance) { buffer in
                        writer.consume(buffer)
                    }
                }
            },
            onCancel: {
                writer.cancel()
            }
        )
    }

}

private final class SpeechBufferFileWriter: @unchecked Sendable {
    private let url: URL
    private let lock = NSLock()
    private var file: AVAudioFile?
    private var continuation: CheckedContinuation<Void, Error>?
    private var synthesizer: AVSpeechSynthesizer?
    private var watchdog: Task<Void, Never>?
    private var frameCount: AVAudioFramePosition = 0
    private var finished = false

    init(url: URL) {
        self.url = url
    }

    func begin(continuation: CheckedContinuation<Void, Error>) -> Bool {
        lock.withLock {
            guard !finished else { return false }
            self.continuation = continuation
            return true
        }
    }

    func retain(_ synthesizer: AVSpeechSynthesizer, watchdog: Task<Void, Never>) -> Bool {
        lock.withLock {
            guard !finished else { return false }
            self.synthesizer = synthesizer
            self.watchdog = watchdog
            return true
        }
    }

    @MainActor
    func timeout(_ error: Error) {
        let activeSynthesizer = lock.withLock { synthesizer }
        if finish(.failure(error)) {
            _ = activeSynthesizer?.stopSpeaking(at: .immediate)
        }
    }

    func cancel() {
        finish(.failure(CancellationError()))
    }

    func consume(_ buffer: AVAudioBuffer) {
        guard let pcm = buffer as? AVAudioPCMBuffer else {
            finish(.failure(TTSError.invalidAudio))
            return
        }
        if pcm.frameLength == 0 {
            let frames = lock.withLock { frameCount }
            finish(frames > 0 ? .success(()) : .failure(TTSError.invalidAudio))
            return
        }

        do {
            try lock.withLock {
                if file == nil {
                    try FileManager.default.createDirectory(
                        at: url.deletingLastPathComponent(),
                        withIntermediateDirectories: true
                    )
                    file = try AVAudioFile(forWriting: url, settings: pcm.format.settings)
                }
                try file?.write(from: pcm)
                frameCount += AVAudioFramePosition(pcm.frameLength)
            }
        } catch {
            finish(.failure(error))
        }
    }

    @discardableResult
    private func finish(_ result: Result<Void, Error>) -> Bool {
        let completion: (CheckedContinuation<Void, Error>?, Task<Void, Never>?) = lock.withLock {
            guard !finished else { return (nil, nil) }
            finished = true
            file = nil
            synthesizer = nil
            let savedContinuation = continuation
            let savedWatchdog = watchdog
            continuation = nil
            watchdog = nil
            return (savedContinuation, savedWatchdog)
        }
        completion.1?.cancel()
        guard let continuation = completion.0 else { return false }
        switch result {
        case .success:
            continuation.resume()
        case let .failure(error):
            continuation.resume(throwing: error)
        }
        return true
    }
}
