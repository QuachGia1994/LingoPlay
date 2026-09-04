import Foundation

@MainActor
extension AppModel {
    var availableOfflineVoices: [OfflineVoiceOption] {
        let system = DubbingPreferencePolicy.availableOfflineVoices()
        let neural = NeuralVoiceModelStore().voiceOption().map { [$0] } ?? []
        return (neural + system).reduce(into: [OfflineVoiceOption]()) { result, voice in
            if !result.contains(where: { $0.id == voice.id }) { result.append(voice) }
        }
    }

    var availableTargetVoices: [OfflineVoiceOption] {
        availableOfflineVoices.filter { $0.languageCode == targetLanguageChoice.code }
    }

    var preferredVoiceLabel: String {
        availableTargetVoices.first(where: { $0.id == preferredVoiceIdentifier })?.label ?? "Automatic"
    }

    func cycleSourceLanguage() {
        let values = SourceLanguageChoice.allCases
        let index = values.firstIndex(of: sourceLanguageChoice) ?? 0
        sourceLanguageChoice = values[(index + 1) % values.count]
    }

    func cycleTargetLanguage() {
        let availableCodes = Set(availableOfflineVoices.map(\.languageCode))
        let values = TargetLanguageChoice.allCases.filter { availableCodes.contains($0.code) }
        let candidates = values.isEmpty ? [.vi] : values
        let index = candidates.firstIndex(of: targetLanguageChoice) ?? -1
        targetLanguageChoice = candidates[(index + 1) % candidates.count]
    }

    func cycleVoice() {
        let candidates: [String?] = [nil] + availableTargetVoices.map(\.id)
        let index = candidates.firstIndex(where: { $0 == preferredVoiceIdentifier }) ?? 0
        preferredVoiceIdentifier = candidates[(index + 1) % candidates.count]
    }

    func cycleTranslationMode() {
        let values = TranslationMode.allCases
        let index = values.firstIndex(of: translationMode) ?? 0
        translationMode = values[(index + 1) % values.count]
    }

    func cycleDubbingMode() {
        let values = DubbingModePreset.allCases
        let index = values.firstIndex(of: dubbingMode) ?? 0
        dubbingMode = values[(index + 1) % values.count]
    }

    func cycleSubtitleMode() {
        let values = SubtitleMode.allCases
        let index = values.firstIndex(of: subtitleMode) ?? 0
        subtitleMode = values[(index + 1) % values.count]
    }

    func cyclePlaybackSpeed() {
        playbackSpeed = DubbingPreferencePolicy.nextPlaybackSpeed(after: playbackSpeed)
    }

    var uiLanguageLabel: String {
        uiLanguageCode == "vi" ? "Tiếng Việt" : "English"
    }

    func uiText(_ english: String, _ vietnamese: String) -> String {
        uiLanguageCode == "vi" ? vietnamese : english
    }

    func toggleAppearance() {
        highContrast.toggle()
        UserDefaults.standard.set(highContrast, forKey: "lingoplay.highContrast")
    }

    func toggleLanguage() {
        uiLanguageCode = uiLanguageCode == "vi" ? "en" : "vi"
        UserDefaults.standard.set(uiLanguageCode, forKey: "lingoplay.uiLanguage")
    }

}
