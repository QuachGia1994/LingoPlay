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

enum SourceSeparationCachePolicy {
    static func purgeStaleSessions() {
        guard let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return }
        try? FileManager.default.removeItem(
            at: caches.appendingPathComponent("LingoPlay/SeparatedAudio", isDirectory: true)
        )
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
    private let coreSeconds: Int = 10
    private let contextMilliseconds: Int = 500

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
        let decoder = try StereoFloatChunkReader(
            url: sourceAudioURL,
            coreSeconds: coreSeconds,
            contextMilliseconds: contextMilliseconds
        )
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
            guard let stems = separator.process(buffer: input), stems.count == 2 else {
                throw SourceSeparationError.inferenceFailed
            }
            try Task.checkCancellation()
            let voice = stems[0]
            let background = stems[1]
            if voiceWriter == nil {
                voiceWriter = try Pcm16WaveWriter(url: vocalsURL, sampleRate: voice.sampleRate, channels: voice.channelCount)
            }
            if backgroundWriter == nil {
                backgroundWriter = try Pcm16WaveWriter(
                    url: backgroundURL, sampleRate: background.sampleRate, channels: background.channelCount
                )
            }
            let voiceCrop = cropRange(for: chunk, outputSampleRate: voice.sampleRate)
            let backgroundCrop = cropRange(for: chunk, outputSampleRate: background.sampleRate)
            try voiceWriter?.append(voice, startFrame: voiceCrop.start, frameCount: voiceCrop.count)
            try backgroundWriter?.append(background, startFrame: backgroundCrop.start, frameCount: backgroundCrop.count)
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

    private func cropRange(for chunk: StereoFloatChunk, outputSampleRate: Int) -> (start: Int, count: Int) {
        let processStart = mapFrame(chunk.processStartFrame, from: chunk.sampleRate, to: outputSampleRate)
        let coreStart = mapFrame(chunk.coreStartFrame, from: chunk.sampleRate, to: outputSampleRate)
        let coreEnd = mapFrame(
            chunk.coreStartFrame + Int64(chunk.coreFrames),
            from: chunk.sampleRate,
            to: outputSampleRate
        )
        return (max(0, Int(coreStart - processStart)), max(1, Int(coreEnd - coreStart)))
    }

    private func mapFrame(_ frame: Int64, from sourceRate: Int, to targetRate: Int) -> Int64 {
        let numerator = frame * Int64(targetRate)
        return (numerator + Int64(sourceRate) / 2) / Int64(sourceRate)
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

struct StereoFloatChunk: Sendable {
    let planarStereo: [Float]
    let frames: Int
    let sampleRate: Int
    let processStartFrame: Int64
    let coreStartFrame: Int64
    let coreFrames: Int
}

final class StereoFloatChunkReader {
    private let file: AVAudioFile
    private let format: AVAudioFormat
    private let coreFrames: Int64
    private let contextFrames: Int64
    private var nextCoreStart: Int64 = 0

    init(url: URL, coreSeconds: Int, contextMilliseconds: Int) throws {
        file = try AVAudioFile(forReading: url, commonFormat: .pcmFormatFloat32, interleaved: false)
        format = file.processingFormat
        guard format.sampleRate > 0, format.channelCount > 0,
              format.commonFormat == .pcmFormatFloat32, !format.isInterleaved
        else { throw SourceSeparationError.invalidAudio }
        coreFrames = Int64(max(1, Int(format.sampleRate.rounded()) * coreSeconds))
        contextFrames = max(1, Int64((format.sampleRate * Double(contextMilliseconds) / 1_000).rounded()))
    }

    func nextChunk() throws -> StereoFloatChunk? {
        guard nextCoreStart < file.length else { return nil }
        let coreEnd = min(file.length, nextCoreStart + coreFrames)
        let processStart = max(0, nextCoreStart - contextFrames)
        let processEnd = min(file.length, coreEnd + contextFrames)
        let requested64 = processEnd - processStart
        guard requested64 > 0, requested64 <= Int64(UInt32.max) else {
            throw SourceSeparationError.invalidAudio
        }
        let requested = AVAudioFrameCount(requested64)
        file.framePosition = processStart
        guard let input = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: requested) else {
            throw SourceSeparationError.invalidAudio
        }
        try file.read(into: input, frameCount: requested)
        guard input.frameLength > 0, let channels = input.floatChannelData else {
            throw SourceSeparationError.invalidAudio
        }
        let count = Int(input.frameLength)
        let channelCount = Int(format.channelCount)
        var planar = [Float](repeating: 0, count: count * 2)
        if channelCount == 1 {
            planar.withUnsafeMutableBufferPointer { buffer in
                buffer.baseAddress!.initialize(from: channels[0], count: count)
                buffer.baseAddress!.advanced(by: count).initialize(from: channels[0], count: count)
            }
        } else if channelCount == 2 {
            planar.withUnsafeMutableBufferPointer { buffer in
                buffer.baseAddress!.initialize(from: channels[0], count: count)
                buffer.baseAddress!.advanced(by: count).initialize(from: channels[1], count: count)
            }
        } else {
            for frame in 0..<count {
                var left: Float = 0
                var right: Float = 0
                var leftCount: Float = 0
                var rightCount: Float = 0
                for channel in 0..<channelCount {
                    if channel.isMultiple(of: 2) {
                        left += channels[channel][frame]
                        leftCount += 1
                    } else {
                        right += channels[channel][frame]
                        rightCount += 1
                    }
                }
                planar[frame] = left / max(1, leftCount)
                planar[count + frame] = right / max(1, rightCount)
            }
        }
        let actualProcessEnd = processStart + Int64(count)
        let actualCoreEnd = min(coreEnd, actualProcessEnd)
        let actualCoreFrames = Int(max(0, actualCoreEnd - nextCoreStart))
        guard actualCoreFrames > 0 else { throw SourceSeparationError.invalidAudio }
        let chunk = StereoFloatChunk(
            planarStereo: planar,
            frames: count,
            sampleRate: Int(format.sampleRate.rounded()),
            processStartFrame: processStart,
            coreStartFrame: nextCoreStart,
            coreFrames: actualCoreFrames
        )
        nextCoreStart += Int64(actualCoreFrames)
        return chunk
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

    func append(_ audio: LingoAudioData, startFrame: Int, frameCount: Int) throws {
        guard audio.sampleRate == sampleRate, audio.channelCount == channels,
              startFrame >= 0, frameCount > 0, startFrame < audio.samplesPerChannel
        else { throw SourceSeparationError.inferenceFailed }
        let availableFrames = audio.samplesPerChannel - startFrame
        let frames = min(availableFrames, frameCount)
        let missingFrames = frameCount - frames
        let maximumTailPadding = max(4_096, sampleRate / 10)
        guard missingFrames <= maximumTailPadding else { throw SourceSeparationError.inferenceFailed }
        let totalByteCount = frameCount * channels * MemoryLayout<Int16>.size
        guard UInt64(dataBytes) + UInt64(totalByteCount) <= UInt64(UInt32.max - 36) else {
            throw SourceSeparationError.inferenceFailed
        }
        var interleaved = [Int16](repeating: 0, count: frameCount * channels)
        if frames > 0 {
            for frame in 0..<frames {
                for channel in 0..<channels {
                    let sourceIndex = channel * audio.samplesPerChannel + startFrame + frame
                    let value = audio.samples[sourceIndex].clamped(to: -1...1)
                    interleaved[frame * channels + channel] = Int16((value * Float(Int16.max)).rounded())
                }
            }
        }
        let pcmData = interleaved.withUnsafeBytes { Data($0) }
        try handle.write(contentsOf: pcmData)
        dataBytes += UInt32(totalByteCount)
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
