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
    case durationFitFailed(String)
    case invalidAudio

    var errorDescription: String? {
        switch self {
        case let .offlineVoiceMissing(languageCode):
            "No offline system voice is available for language '\(languageCode)' on this device."
        case let .synthesisFailed(message):
            "Speech synthesis failed: \(message)"
        case let .durationFitFailed(segmentID):
            "Speech for \(segmentID) is still longer than its source time window after safe speed fitting."
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

    static func nextRateMultiplier(actualMs: Int, targetMs: Int, current: Float) -> Float? {
        guard !fits(actualMs: actualMs, targetMs: targetMs) else { return nil }
        guard current < maxRateMultiplier - 0.01 else { return nil }
        let ratio = Float(actualMs) / Float(max(1, targetMs))
        let proposed = max(current * 1.08, current * ratio * 1.02)
        let next = min(maxRateMultiplier, proposed)
        return next > current + 0.01 ? next : nil
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
        var output: [DubSpeechSegment] = []

        for (index, segment) in document.segments.enumerated() {
            let synthesized = try await synthesizeSegment(segment, voice: voice, root: root)
            output.append(synthesized)
            progress(index + 1, document.segments.count)
        }

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
        root: URL
    ) async throws -> DubSpeechSegment {
        let targetMs = DurationFitPolicy.targetDurationMs(startMs: segment.startMs, endMs: segment.endMs)
        var multiplier: Float = 1.0

        for attempt in 0..<DurationFitPolicy.maximumAttempts {
            let fileURL = root.appendingPathComponent("\(segment.id)-\(attempt).caf")
            try? FileManager.default.removeItem(at: fileURL)
            try await synthesizeOnce(text: segment.translatedText, voice: voice, multiplier: multiplier, to: fileURL)
            let durationMs = try measuredDurationMs(of: fileURL)

            if DurationFitPolicy.fits(actualMs: durationMs, targetMs: targetMs) {
                return DubSpeechSegment(
                    id: segment.id,
                    startMs: segment.startMs,
                    endMs: segment.endMs,
                    audioURL: fileURL,
                    speechDurationMs: durationMs,
                    tailSilenceMs: DurationFitPolicy.tailSilenceMs(actualMs: durationMs, targetMs: targetMs),
                    rateMultiplier: multiplier
                )
            }

            guard let next = DurationFitPolicy.nextRateMultiplier(
                actualMs: durationMs,
                targetMs: targetMs,
                current: multiplier
            ) else {
                throw TTSError.durationFitFailed(segment.id)
            }
            try? FileManager.default.removeItem(at: fileURL)
            multiplier = next
        }

        throw TTSError.durationFitFailed(segment.id)
    }

    private func synthesizeOnce(
        text: String,
        voice: AVSpeechSynthesisVoice,
        multiplier: Float,
        to url: URL
    ) async throws {
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voice
        utterance.rate = min(
            AVSpeechUtteranceMaximumSpeechRate,
            max(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceDefaultSpeechRate * multiplier)
        )
        utterance.preUtteranceDelay = 0
        utterance.postUtteranceDelay = 0

        let writer = SpeechBufferFileWriter(url: url)
        try await withCheckedThrowingContinuation { continuation in
            writer.begin(continuation: continuation)
            let synthesizer = AVSpeechSynthesizer()
            writer.retain(synthesizer)
            synthesizer.write(utterance) { buffer in
                writer.consume(buffer)
            }
        }
    }

    private func measuredDurationMs(of url: URL) throws -> Int {
        let file = try AVAudioFile(forReading: url)
        let sampleRate = file.processingFormat.sampleRate
        guard sampleRate > 0, file.length > 0 else { throw TTSError.invalidAudio }
        return max(1, Int((Double(file.length) / sampleRate * 1_000).rounded()))
    }
}

private final class SpeechBufferFileWriter: @unchecked Sendable {
    private let url: URL
    private let lock = NSLock()
    private var file: AVAudioFile?
    private var continuation: CheckedContinuation<Void, Error>?
    private var synthesizer: AVSpeechSynthesizer?
    private var frameCount: AVAudioFramePosition = 0
    private var finished = false

    init(url: URL) {
        self.url = url
    }

    func begin(continuation: CheckedContinuation<Void, Error>) {
        lock.withLock {
            self.continuation = continuation
        }
    }

    func retain(_ synthesizer: AVSpeechSynthesizer) {
        lock.withLock {
            self.synthesizer = synthesizer
        }
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

    private func finish(_ result: Result<Void, Error>) {
        let continuation: CheckedContinuation<Void, Error>? = lock.withLock {
            guard !finished else { return nil }
            finished = true
            file = nil
            synthesizer = nil
            let saved = self.continuation
            self.continuation = nil
            return saved
        }
        guard let continuation else { return }
        switch result {
        case .success:
            continuation.resume()
        case let .failure(error):
            continuation.resume(throwing: error)
        }
    }
}
