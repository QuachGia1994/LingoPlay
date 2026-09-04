import Foundation

enum PlaybackPresentationPolicy {
    static func activeSegment(in document: TranslationDocument?, positionSeconds: Double) -> TranslationSegment? {
        guard let document else { return nil }
        let positionMs = Int((max(0, positionSeconds) * 1_000).rounded())
        return document.segments.last { positionMs >= $0.startMs && positionMs <= $0.endMs }
    }

    static func sourceLanguageLabel(document: TranslationDocument?, fallback: String) -> String {
        document?.sourceLanguage.uppercased() ?? fallback
    }

    static func targetLanguageLabel(document: TranslationDocument?, fallback: String) -> String {
        document?.targetLanguage.uppercased() ?? fallback
    }
}
