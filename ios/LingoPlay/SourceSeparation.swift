import Foundation


enum SourceSeparationError: Error {
    case unavailable
}

enum SourceSeparationAvailability: Sendable, Equatable {
    case unavailable
    case engineReady
}

struct SeparatedAudioStems: Sendable, Equatable {
    let voiceURL: URL
    let backgroundURL: URL
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

enum CleanBackgroundCapability {
    static let engine: any SourceSeparationEngine = UnavailableSourceSeparationEngine()
    static var isAvailable: Bool { engine.availability == .engineReady }
}
