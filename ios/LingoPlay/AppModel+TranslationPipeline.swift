import Foundation

@MainActor
extension AppModel {
    func translateTranscript(
        _ transcript: ASRTranscript,
        cloneReferences: [String: VoiceCloneReference],
        run: ProcessingRun
    ) async {
        let updateProgress: @MainActor @Sendable (Int, Int) -> Void = { [weak self] item, total in
            guard let self, self.isActive(run) else { return }
            translationState = .translating(batch: item, totalBatches: total)
            let ratio = total > 0 ? Double(item) / Double(total) : 0
            processingProgress = 0.48 + (0.12 * ratio)
        }

        do {
            let document: TranslationDocument
            switch run.config.translationMode {
            case .cloud:
                guard let endpoint = translationEndpoint() else {
                    guard isActive(run) else { return }
                    translationState = .endpointMissing
                    return
                }
                document = try await translationService.translate(
                    transcript: transcript,
                    targetLanguage: run.config.targetLanguage.code,
                    endpoint: endpoint,
                    progress: updateProgress
                )
            case .offline:
                document = try await offlineTranslationService.translate(
                    transcript: transcript,
                    targetLanguage: run.config.targetLanguage.code,
                    progress: updateProgress
                )
            }
            guard isActive(run) else { return }
            let resolvedDocument = TranslationDocument(
                sourceLanguage: document.sourceLanguage,
                targetLanguage: document.targetLanguage,
                segments: document.segments,
                mode: document.mode,
                speakerVoiceMap: run.config.speakerVoiceMap
            )
            translationState = .completed(resolvedDocument)
            processingProgress = 0.6
            await synthesizeOfflineSpeech(
                resolvedDocument,
                cloneReferences: cloneReferences,
                run: run
            )
        } catch {
            guard isActive(run) else { return }
            await diagnostics.record("translation_failed")
            translationState = .failed(error.localizedDescription)
        }
    }
}
