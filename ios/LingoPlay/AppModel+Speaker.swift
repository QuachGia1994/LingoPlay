import Foundation

@MainActor
extension AppModel {
    func resolveSpeakers(
        _ transcript: ASRTranscript,
        sources: ProcessingAudioSources,
        run: ProcessingRun
    ) async {
        guard isActive(run) else { return }
        guard run.config.speakerMode == .multi else {
            speakerState = .idle
            await translateTranscript(transcript, cloneReferences: [:], sources: sources, run: run)
            return
        }
        guard let model = SpeakerDiarizationModelStore().model() else {
            speakerState = .modelMissing
            speakerModelInstallState = .notInstalled
            return
        }

        speakerState = .analyzing
        processingProgress = 0.44
        let diarization: SpeakerDiarizationDocument
        do {
            diarization = try await speakerDiarizationService.diarize(
                audioURL: sources.analysisAudioURL,
                model: model
            )
        } catch {
            guard isActive(run) else { return }
            await diagnostics.record("speaker_diarization_failed")
            speakerState = .failed(error.localizedDescription)
            return
        }
        guard isActive(run) else { return }

        let annotated = SpeakerDiarizationPolicy.annotate(
            transcript: transcript,
            document: diarization
        )
        let mapping = SpeakerVoicePolicy.resolve(
            speakerIDs: diarization.speakerIDs,
            availableVoices: availableOfflineVoices,
            targetLanguage: run.config.targetLanguage.code,
            preferredVoiceIdentifier: run.config.preferredVoiceIdentifier,
            existing: run.config.speakerVoiceMap
        )
        let resolvedConfig = ProcessingConfig(
            sourceLanguage: run.config.sourceLanguage,
            targetLanguage: run.config.targetLanguage,
            preferredVoiceIdentifier: run.config.preferredVoiceIdentifier,
            dubbingMode: run.config.dubbingMode,
            subtitleMode: run.config.subtitleMode,
            translationMode: run.config.translationMode,
            speakerMode: run.config.speakerMode,
            speakerVoiceMap: mapping,
            voiceCloningEnabled: run.config.voiceCloningEnabled,
            cleanBackgroundEnabled: run.config.cleanBackgroundEnabled
        )
        let resolvedRun = run.replacingConfig(resolvedConfig)
        activeProcessingConfig = resolvedConfig
        try? await processingRecoveryStore.save(
            media: run.media,
            preparedAudioURL: sources.preparedAudioURL,
            config: resolvedConfig,
            processingRunID: run.id
        )
        guard isActive(resolvedRun) else { return }
        speakerState = .completed(diarization)
        processingProgress = 0.48

        var cloneReferences: [String: VoiceCloneReference] = [:]
        if resolvedConfig.voiceCloningEnabled,
           VoiceCloningPolicy.supportsPair(source: annotated.language, target: resolvedConfig.targetLanguage.code),
           !VoiceCloningPolicy.eligibleReferenceSegments(annotated).isEmpty {
            guard VoiceCloningModelStore().model() != nil else {
                voiceCloningModelInstallState = .notInstalled
                ttsState = .failed("Voice Cloning model required. Install the optional local model in Settings; cloning never falls back to cloud.")
                return
            }
            do {
                cloneReferences = try await VoiceCloneReferenceBuilder.build(
                    audioURL: sources.analysisAudioURL,
                    transcript: annotated
                )
            } catch {
                guard isActive(resolvedRun) else { return }
                await diagnostics.record("voice_cloning_reference_failed")
                ttsState = .failed(error.localizedDescription)
                return
            }
            // Unknown/mixed speech uses the selected offline voices when no safe reference exists.
        }
        await translateTranscript(annotated, cloneReferences: cloneReferences, sources: sources, run: resolvedRun)
    }

    func cycleSpeakerMode() {
        speakerMode = speakerMode == .single ? .multi : .single
    }

    func installSpeakerModel() {
        guard speakerModelInstallTask == nil else { return }
        speakerModelInstallTask = Task { [weak self] in
            guard let self else { return }
            defer { speakerModelInstallTask = nil }
            do {
                _ = try await speakerModelInstaller.install(wifiOnly: wifiOnly) { [weak self] progress in
                    await MainActor.run {
                        self?.speakerModelInstallState = .downloading(progress: progress)
                    }
                }
                speakerModelInstallState = await speakerModelInstaller.state()
                resumeProcessingAfterSpeakerModelInstall()
            } catch is CancellationError {
                speakerModelInstallState = await speakerModelInstaller.state()
            } catch {
                await diagnostics.record("speaker_model_install_failed")
                speakerModelInstallState = .failed(error.localizedDescription)
            }
        }
    }

    func cancelSpeakerModelInstall() {
        speakerModelInstallTask?.cancel()
    }

    func deleteSpeakerModel() {
        guard canDeleteSpeakerModel else { return }
        speakerModelInstallTask?.cancel()
        speakerModelInstallTask = nil
        Task {
            do {
                try await speakerModelInstaller.deleteInstalledModel()
                speakerModelInstallState = .notInstalled
            } catch {
                speakerModelInstallState = .failed(error.localizedDescription)
            }
        }
    }

    var canDeleteSpeakerModel: Bool {
        !processingLifetimeActive
    }

    func installVoiceCloningModel() {
        guard voiceCloningModelInstallTask == nil else { return }
        voiceCloningModelInstallTask = Task { [weak self] in
            guard let self else { return }
            defer { voiceCloningModelInstallTask = nil }
            do {
                _ = try await voiceCloningModelInstaller.install(wifiOnly: wifiOnly) { [weak self] progress in
                    await MainActor.run {
                        self?.voiceCloningModelInstallState = .downloading(progress: progress)
                    }
                }
                voiceCloningModelInstallState = await voiceCloningModelInstaller.state()
            } catch is CancellationError {
                voiceCloningModelInstallState = await voiceCloningModelInstaller.state()
            } catch {
                await diagnostics.record("voice_cloning_model_install_failed")
                voiceCloningModelInstallState = .failed(error.localizedDescription)
            }
        }
    }

    func cancelVoiceCloningModelInstall() {
        voiceCloningModelInstallTask?.cancel()
    }

    func deleteVoiceCloningModel() {
        guard canDeleteVoiceCloningModel else { return }
        voiceCloningModelInstallTask?.cancel()
        voiceCloningModelInstallTask = nil
        Task {
            do {
                try await voiceCloningModelInstaller.deleteInstalledModel()
                voiceCloningModelInstallState = .notInstalled
            } catch {
                voiceCloningModelInstallState = .failed(error.localizedDescription)
            }
        }
    }

    var canDeleteVoiceCloningModel: Bool {
        !processingLifetimeActive
    }
}
