import AVFoundation
import Foundation


enum SourceLanguageChoice: String, CaseIterable, Identifiable, Sendable {
    case auto
    case en
    case vi
    case ja
    case zh

    var id: String { rawValue }
    var code: String? { self == .auto ? nil : rawValue }
    var label: String {
        switch self {
        case .auto: "Auto Detect"
        case .en: "English"
        case .vi: "Vietnamese"
        case .ja: "Japanese"
        case .zh: "Chinese"
        }
    }
}

enum TargetLanguageChoice: String, CaseIterable, Identifiable, Sendable {
    case vi
    case en
    case ja
    case zh

    var id: String { rawValue }
    var code: String { rawValue }
    var label: String {
        switch self {
        case .vi: "Vietnamese"
        case .en: "English"
        case .ja: "Japanese"
        case .zh: "Chinese"
        }
    }
}

enum DubbingModePreset: String, CaseIterable, Identifiable, Sendable, Codable {
    case balanced
    case speechFocus
    case originalFocus

    var id: String { rawValue }
    var label: String {
        switch self {
        case .balanced: "Balanced"
        case .speechFocus: "Speech Focus"
        case .originalFocus: "Original Focus"
        }
    }
    var detail: String {
        switch self {
        case .balanced: "Natural voice + soundtrack balance"
        case .speechFocus: "Stronger ducking for clearer dialogue"
        case .originalFocus: "Keeps more of the original soundtrack"
        }
    }
    var duckFloor: Float {
        switch self {
        case .balanced: 0.16
        case .speechFocus: 0.08
        case .originalFocus: 0.34
        }
    }
    var dubVolume: Float {
        switch self {
        case .balanced: 0.92
        case .speechFocus: 1.0
        case .originalFocus: 0.82
        }
    }
    var duckFadeSeconds: TimeInterval {
        switch self {
        case .balanced, .speechFocus: 0.12
        case .originalFocus: 0.10
        }
    }
}

enum SubtitleMode: String, CaseIterable, Identifiable, Sendable {
    case bilingual
    case translated
    case off

    var id: String { rawValue }
    var label: String {
        switch self {
        case .bilingual: "Bilingual"
        case .translated: "Translated"
        case .off: "Off"
        }
    }
}

enum TranslationMode: String, CaseIterable, Identifiable, Sendable, Codable {
    case cloud
    case offline

    var id: String { rawValue }
    var label: String { self == .cloud ? "Cloud" : "Offline" }
    var detail: String {
        self == .cloud
            ? "Transcript JSON only · Cloudflare Workers AI"
            : "Google ML Kit models installed on this device"
    }
}

struct OfflineVoiceOption: Identifiable, Hashable, Sendable {
    let id: String
    let label: String
    let languageCode: String
}

struct ProcessingConfig: Sendable, Equatable {
    let sourceLanguage: SourceLanguageChoice
    let targetLanguage: TargetLanguageChoice
    let preferredVoiceIdentifier: String?
    let dubbingMode: DubbingModePreset
    let subtitleMode: SubtitleMode
    let translationMode: TranslationMode

    init(
        sourceLanguage: SourceLanguageChoice,
        targetLanguage: TargetLanguageChoice,
        preferredVoiceIdentifier: String?,
        dubbingMode: DubbingModePreset,
        subtitleMode: SubtitleMode,
        translationMode: TranslationMode = .cloud
    ) {
        self.sourceLanguage = sourceLanguage
        self.targetLanguage = targetLanguage
        self.preferredVoiceIdentifier = preferredVoiceIdentifier
        self.dubbingMode = dubbingMode
        self.subtitleMode = subtitleMode
        self.translationMode = translationMode
    }
}

enum DubbingPreferencePolicy {
    static let playbackSpeeds: [Double] = [0.75, 1.0, 1.25, 1.5]

    static func sanitizedPlaybackSpeed(_ value: Double?) -> Double {
        guard let value, value.isFinite, playbackSpeeds.contains(value) else { return 1.0 }
        return value
    }

    static func nextPlaybackSpeed(after current: Double) -> Double {
        let nearest = playbackSpeeds.enumerated().min { abs($0.element - current) < abs($1.element - current) }?.offset ?? 1
        return playbackSpeeds[(nearest + 1) % playbackSpeeds.count]
    }

    @MainActor
    static func availableOfflineVoices() -> [OfflineVoiceOption] {
        AVSpeechSynthesisVoice.speechVoices()
            .map { voice in
                let languageCode = voice.language.split(separator: "-").first.map(String.init)?.lowercased() ?? "und"
                return OfflineVoiceOption(
                    id: voice.identifier,
                    label: "\(Locale.current.localizedString(forLanguageCode: languageCode) ?? languageCode) · \(voice.name)",
                    languageCode: languageCode.lowercased()
                )
            }
            .sorted { lhs, rhs in lhs.languageCode == rhs.languageCode ? lhs.label < rhs.label : lhs.languageCode < rhs.languageCode }
    }
}
