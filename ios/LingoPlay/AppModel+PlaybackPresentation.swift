import Foundation

@MainActor
extension AppModel {
    private var activeTranslationDocument: TranslationDocument? {
        guard case .completed(let document) = translationState else { return nil }
        return document
    }

    var activeTranslationSegment: TranslationSegment? {
        PlaybackPresentationPolicy.activeSegment(
            in: activeTranslationDocument,
            positionSeconds: playbackPosition
        )
    }

    var activeTranslationUsesGoogle: Bool {
        activeTranslationDocument?.mode == .offline
    }

    var activeSubtitleSourceLanguage: String {
        PlaybackPresentationPolicy.sourceLanguageLabel(
            document: activeTranslationDocument,
            fallback: sourceLanguageChoice.code?.uppercased() ?? "SRC"
        )
    }

    var activeSubtitleTargetLanguage: String {
        PlaybackPresentationPolicy.targetLanguageLabel(
            document: activeTranslationDocument,
            fallback: targetLanguageChoice.code.uppercased()
        )
    }
}
