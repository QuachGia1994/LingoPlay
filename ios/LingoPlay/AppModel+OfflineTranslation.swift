import Foundation

@MainActor
extension AppModel {
    var canManageOfflineTranslationModels: Bool {
        if case .translating = translationState { return false }
        return translationModelBusyCode == nil
    }

    func refreshOfflineTranslationModels() {
        downloadedTranslationModelCodes = offlineTranslationModelManager.downloadedCodes()
    }

    func toggleOfflineTranslationModel(_ code: String) {
        guard canManageOfflineTranslationModels else { return }
        translationModelBusyCode = code
        translationModelError = nil
        Task { @MainActor in
            defer {
                refreshOfflineTranslationModels()
                translationModelBusyCode = nil
            }
            do {
                if downloadedTranslationModelCodes.contains(code) {
                    try await offlineTranslationModelManager.delete(code)
                } else {
                    try await offlineTranslationModelManager.download(code, wifiOnly: wifiOnly)
                }
            } catch {
                translationModelError = error.localizedDescription
            }
        }
    }
}
