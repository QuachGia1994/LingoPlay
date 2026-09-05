import AVFoundation
import Foundation
@preconcurrency import SherpaOnnxC


enum SourceSeparationError: LocalizedError {
    case unavailable
    case invalidAudio
    case inferenceFailed

    var errorDescription: String? {
        switch self {
        case .unavailable:
            "Clean Background requires the verified local source-separation model."
        case .invalidAudio:
            "The prepared audio could not be decoded for Clean Background."
        case .inferenceFailed:
            "Local source separation failed."
        }
    }
}

enum SourceSeparationAvailability: Sendable, Equatable {
    case unavailable
    case engineReady
}

struct SeparatedAudioStems: Sendable, Equatable {
    let voiceURL: URL
    let backgroundURL: URL
    let rootURL: URL

    func cleanup() {
        try? FileManager.default.removeItem(at: rootURL)
    }
}

protocol SourceSeparationEngine: Sendable {
    var availability: SourceSeparationAvailability { get }
    func separate(sourceAudioURL: URL) async throws -> SeparatedAudioStems
}

struct UnavailableSourceSeparationEngine: SourceSeparationEngine {
    let availability: SourceSeparationAvailability = .unavailable

    func separate(sourceAudioURL: URL) async throws -> SeparatedAudioStems {
        throw SourceSeparationError.unavailable
    }
}

private enum SourceSeparationRuntime {
    static var isAvailable: Bool {
        SherpaOnnxGetVersionStr() != nil
    }
}

enum CleanBackgroundCapability {
    static var engine: any SourceSeparationEngine {
        guard SourceSeparationRuntime.isAvailable,
              let model = SourceSeparationModelStore().model()
        else {
            return UnavailableSourceSeparationEngine()
        }
        return SpleeterSourceSeparationEngine(model: model)
    }

    static var isAvailable: Bool {
        SourceSeparationRuntime.isAvailable && SourceSeparationModelStore().model() != nil
    }
}

actor SpleeterSourceSeparationEngine: SourceSeparationEngine {
    nonisolated let availability: SourceSeparationAvailability = .engineReady
    private let model: InstalledSourceSeparationModel
    private let secondsPerChunk: Int = 12

    init(model: InstalledSourceSeparationModel) {
        self.model = model
    }

    func separate(sourceAudioURL: URL) async throws -> SeparatedAudioStems {
        try Task.checkCancellation()
        let root = try makeSessionDirectory()
        let vocalsURL = root.appendingPathComponent("vocals.wav")
        let backgroundURL = root.appendingPathComponent("accompaniment.wav")
        var success = false
        defer {
            if !success { try? FileManager.default.removeItem(at: root) }
        }

        guard let separator = LingoSourceSeparator(model: model) else {
            throw SourceSeparationError.inferenceFailed
        }
        let decoder = try StereoFloatChunkReader(url: sourceAudioURL, secondsPerChunk: secondsPerChunk)
        var voiceWriter: Pcm16WaveWriter?
        var backgroundWriter: Pcm16WaveWriter?
        var chunkCount = 0
        defer {
            try? voiceWriter?.close()
            try? backgroundWriter?.close()
        }

        while let chunk = try decoder.nextChunk() {
            try Task.checkCancellation()
            let input = LingoAudioData(samples: chunk.planarStereo, channelCount: 2, sampleRate: chunk.sampleRate)
            guard let stems = separator.process(buffer: input), stems.count >= 2 else {
                throw SourceSeparationError.inferenceFailed
            }
            try Task.checkCancellation()
            let voice = stems[0]
            let background = stems[1]
            let expectedVoiceFrames = max(1, Int((
                Double(chunk.frames) * Double(voice.sampleRate) / Double(chunk.sampleRate)
            ).rounded()))
            let expectedBackgroundFrames = max(1, Int((
                Double(chunk.frames) * Double(background.sampleRate) / Double(chunk.sampleRate)
            ).rounded()))
            if voiceWriter == nil {
                voiceWriter = try Pcm16WaveWriter(url: vocalsURL, sampleRate: voice.sampleRate, channels: voice.channelCount)
            }
            if backgroundWriter == nil {
                backgroundWriter = try Pcm16WaveWriter(
                    url: backgroundURL, sampleRate: background.sampleRate, channels: background.channelCount
                )
            }
            try voiceWriter?.append(voice, maximumFrames: expectedVoiceFrames)
            try backgroundWriter?.append(background, maximumFrames: expectedBackgroundFrames)
            chunkCount += 1
        }
        guard chunkCount > 0 else { throw SourceSeparationError.invalidAudio }
        try voiceWriter?.close()
        voiceWriter = nil
        try backgroundWriter?.close()
        backgroundWriter = nil
        try validateNonEmpty(vocalsURL)
        try validateNonEmpty(backgroundURL)
        success = true
        return SeparatedAudioStems(voiceURL: vocalsURL, backgroundURL: backgroundURL, rootURL: root)
    }

    private func makeSessionDirectory() throws -> URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let root = caches
            .appendingPathComponent("LingoPlay/SeparatedAudio", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    private func validateNonEmpty(_ url: URL) throws {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true, (values.fileSize ?? 0) > 44 else {
            throw SourceSeparationError.inferenceFailed
        }
    }
}

private struct LingoAudioData {
    let samples: [Float]
    let channelCount: Int
    let sampleRate: Int
    var samplesPerChannel: Int { channelCount > 0 ? samples.count / channelCount : 0 }
}

private final class LingoSourceSeparator {
    private var engine: OpaquePointer?

    init?(model: InstalledSourceSeparationModel) {
        var created: OpaquePointer?
        model.vocalsURL.path.withCString { vocals in
            model.accompanimentURL.path.withCString { accompaniment in
                "cpu".withCString { provider in
                    var config = SherpaOnnxOfflineSourceSeparationConfig()
                    config.model.spleeter.vocals = vocals
                    config.model.spleeter.accompaniment = accompaniment
                    config.model.num_threads = 1
                    config.model.debug = 0
                    config.model.provider = provider
                    created = SherpaOnnxCreateOfflineSourceSeparation(&config)
                }
            }
        }
        engine = created
        if engine == nil { return nil }
    }

    deinit {
        if let engine { SherpaOnnxDestroyOfflineSourceSeparation(engine) }
    }

    func process(buffer: LingoAudioData) -> [LingoAudioData]? {
        guard let engine else { return nil }
        return buffer.samples.withUnsafeBufferPointer { flat in
            guard let base = flat.baseAddress else { return nil }
            var channels: [UnsafePointer<Float>?] = (0..<buffer.channelCount).map {
                base + ($0 * buffer.samplesPerChannel)
            }
            guard let raw = SherpaOnnxOfflineSourceSeparationProcess(
                engine,
                &channels,
                Int32(buffer.channelCount),
                Int32(buffer.samplesPerChannel),
                Int32(buffer.sampleRate)
            ) else { return nil }
            defer { SherpaOnnxDestroySourceSeparationOutput(raw) }
            return (0..<Int(raw.pointee.num_stems)).map { stemIndex in
                let stem = raw.pointee.stems[stemIndex]
                let channelCount = Int(stem.num_channels)
                let frames = Int(stem.n)
                var samples = [Float](repeating: 0, count: channelCount * frames)
                for channel in 0..<channelCount {
                    guard let source = stem.samples[channel] else { continue }
                    samples.withUnsafeMutableBufferPointer { destination in
                        destination.baseAddress!.advanced(by: channel * frames)
                            .initialize(from: source, count: frames)
                    }
                }
                return LingoAudioData(
                    samples: samples,
                    channelCount: channelCount,
                    sampleRate: Int(raw.pointee.sample_rate)
                )
            }
        }
    }
}

private struct StereoFloatChunk: Sendable {
    let planarStereo: [Float]
    let frames: Int
    let sampleRate: Int
}

private final class StereoFloatChunkReader {
    private let file: AVAudioFile
    private let inputFormat: AVAudioFormat
    private let outputFormat: AVAudioFormat
    private let converter: AVAudioConverter?
    private let framesPerChunk: AVAudioFrameCount

    init(url: URL, secondsPerChunk: Int) throws {
        file = try AVAudioFile(forReading: url)
        inputFormat = file.processingFormat
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0,
              let stereo = AVAudioFormat(
                commonFormat: .pcmFormatFloat32,
                sampleRate: inputFormat.sampleRate,
                channels: 2,
                interleaved: false
              )
        else { throw SourceSeparationError.invalidAudio }
        outputFormat = stereo
        let alreadyStereoFloat = inputFormat.commonFormat == .pcmFormatFloat32 &&
            inputFormat.channelCount == 2 && !inputFormat.isInterleaved
        converter = alreadyStereoFloat ? nil : AVAudioConverter(from: inputFormat, to: outputFormat)
        if !alreadyStereoFloat && converter == nil { throw SourceSeparationError.invalidAudio }
        framesPerChunk = AVAudioFrameCount(max(1, Int(inputFormat.sampleRate) * secondsPerChunk))
    }

    func nextChunk() throws -> StereoFloatChunk? {
        guard file.framePosition < file.length else { return nil }
        let remaining = file.length - file.framePosition
        let requested = AVAudioFrameCount(min(Int64(framesPerChunk), remaining))
        guard let input = AVAudioPCMBuffer(pcmFormat: inputFormat, frameCapacity: requested) else {
            throw SourceSeparationError.invalidAudio
        }
        try file.read(into: input, frameCount: requested)
        guard input.frameLength > 0 else { return nil }

        let stereo: AVAudioPCMBuffer
        if converter == nil {
            stereo = input
        } else {
            guard let converter,
                  let output = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: input.frameLength + 32)
            else { throw SourceSeparationError.invalidAudio }
            var supplied = false
            var conversionError: NSError?
            let status = converter.convert(to: output, error: &conversionError) { _, status in
                if supplied {
                    status.pointee = .noDataNow
                    return nil
                }
                supplied = true
                status.pointee = .haveData
                return input
            }
            guard status != .error else { throw conversionError ?? SourceSeparationError.invalidAudio }
            stereo = output
        }
        guard let channels = stereo.floatChannelData, stereo.format.channelCount >= 2 else {
            throw SourceSeparationError.invalidAudio
        }
        let count = Int(stereo.frameLength)
        guard count > 0 else { return nil }
        var planar = [Float](repeating: 0, count: count * 2)
        planar.withUnsafeMutableBufferPointer { buffer in
            buffer.baseAddress!.initialize(from: channels[0], count: count)
            buffer.baseAddress!.advanced(by: count).initialize(from: channels[1], count: count)
        }
        return StereoFloatChunk(planarStereo: planar, frames: count, sampleRate: Int(stereo.format.sampleRate.rounded()))
    }
}

private final class Pcm16WaveWriter {
    private let handle: FileHandle
    private let sampleRate: Int
    private let channels: Int
    private var dataBytes: UInt32 = 0
    private var closed = false

    init(url: URL, sampleRate: Int, channels: Int) throws {
        guard sampleRate > 0, channels > 0 else { throw SourceSeparationError.invalidAudio }
        self.sampleRate = sampleRate
        self.channels = channels
        FileManager.default.createFile(atPath: url.path, contents: nil)
        handle = try FileHandle(forWritingTo: url)
        try handle.write(contentsOf: Data(repeating: 0, count: 44))
    }

    func append(_ audio: LingoAudioData, maximumFrames: Int) throws {
        guard audio.sampleRate == sampleRate, audio.channelCount == channels else {
            throw SourceSeparationError.inferenceFailed
        }
        let frames = min(audio.samplesPerChannel, maximumFrames)
        guard frames > 0 else { return }
        let byteCount = frames * channels * MemoryLayout<Int16>.size
        guard UInt64(dataBytes) + UInt64(byteCount) <= UInt64(UInt32.max - 36) else {
            throw SourceSeparationError.inferenceFailed
        }
        var interleaved = [Int16](repeating: 0, count: frames * channels)
        for frame in 0..<frames {
            for channel in 0..<channels {
                let value = audio.samples[channel * audio.samplesPerChannel + frame].clamped(to: -1...1)
                interleaved[frame * channels + channel] = Int16((value * Float(Int16.max)).rounded())
            }
        }
        let pcmData = interleaved.withUnsafeBytes { Data($0) }
        try handle.write(contentsOf: pcmData)
        dataBytes += UInt32(byteCount)
    }

    func close() throws {
        guard !closed else { return }
        closed = true
        try handle.seek(toOffset: 0)
        try handle.write(contentsOf: waveHeader())
        try handle.close()
    }

    private func waveHeader() -> Data {
        var data = Data()
        data.append(contentsOf: "RIFF".utf8)
        data.appendLittleEndian(UInt32(36) + dataBytes)
        data.append(contentsOf: "WAVEfmt ".utf8)
        data.appendLittleEndian(UInt32(16))
        data.appendLittleEndian(UInt16(1))
        data.appendLittleEndian(UInt16(channels))
        data.appendLittleEndian(UInt32(sampleRate))
        data.appendLittleEndian(UInt32(sampleRate * channels * 2))
        data.appendLittleEndian(UInt16(channels * 2))
        data.appendLittleEndian(UInt16(16))
        data.append(contentsOf: "data".utf8)
        data.appendLittleEndian(dataBytes)
        return data
    }
}

private extension Float {
    func clamped(to range: ClosedRange<Float>) -> Float { min(max(self, range.lowerBound), range.upperBound) }
}

private extension Data {
    mutating func appendLittleEndian<T: FixedWidthInteger>(_ value: T) {
        var little = value.littleEndian
        Swift.withUnsafeBytes(of: &little) { append(contentsOf: $0) }
    }
}
