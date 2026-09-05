import Foundation

struct ProcessingAudioSources: Sendable {
    let preparedAudioURL: URL
    let analysisAudioURL: URL
    let backgroundAudioURL: URL?
}

@MainActor
extension AppModel {
    func processPreparedAudio(_ preparedAudioURL: URL, run: ProcessingRun) async {
        guard isActive(run) else { return }
        var separated: SeparatedAudioStems?
        defer { separated?.cleanup() }
        do {
            let sources: ProcessingAudioSources
            if run.config.cleanBackgroundEnabled {
                guard CleanBackgroundCapability.isAvailable else {
                    sourceSeparationModelInstallState = .notInstalled
                    mediaState = .failed("Clean Background model required. Install the verified local model in Settings, then retry.")
                    return
                }
                await diagnostics.record("source_separation_started")
                let stems = try await CleanBackgroundCapability.engine.separate(sourceAudioURL: preparedAudioURL)
                separated = stems
                guard isActive(run) else { return }
                await diagnostics.record("source_separation_completed")
                sources = ProcessingAudioSources(
                    preparedAudioURL: preparedAudioURL,
                    analysisAudioURL: stems.voiceURL,
                    backgroundAudioURL: stems.backgroundURL
                )
            } else {
                sources = ProcessingAudioSources(
                    preparedAudioURL: preparedAudioURL,
                    analysisAudioURL: preparedAudioURL,
                    backgroundAudioURL: nil
                )
            }
            await recognizeSpeech(from: sources, run: run)
        } catch is CancellationError {
            return
        } catch {
            guard isActive(run) else { return }
            await diagnostics.record("source_separation_failed")
            mediaState = .failed(error.localizedDescription)
        }
    }

    func installSourceSeparationModel() {
        guard sourceSeparationModelInstallTask == nil else { return }
        sourceSeparationModelInstallTask = Task { [weak self] in
            guard let self else { return }
            defer { sourceSeparationModelInstallTask = nil }
            do {
                _ = try await sourceSeparationModelInstaller.install(wifiOnly: wifiOnly) { [weak self] progress in
                    await MainActor.run {
                        self?.sourceSeparationModelInstallState = .downloading(progress: progress)
                    }
                }
                sourceSeparationModelInstallState = await sourceSeparationModelInstaller.state()
            } catch is CancellationError {
                sourceSeparationModelInstallState = await sourceSeparationModelInstaller.state()
            } catch {
                await diagnostics.record("source_separation_model_install_failed")
                sourceSeparationModelInstallState = .failed(error.localizedDescription)
            }
        }
    }

    func cancelSourceSeparationModelInstall() {
        sourceSeparationModelInstallTask?.cancel()
    }

    func deleteSourceSeparationModel() {
        guard canDeleteSourceSeparationModel else { return }
        sourceSeparationModelInstallTask?.cancel()
        sourceSeparationModelInstallTask = nil
        Task {
            do {
                try await sourceSeparationModelInstaller.deleteInstalledModel()
                sourceSeparationModelInstallState = .notInstalled
            } catch {
                sourceSeparationModelInstallState = .failed(error.localizedDescription)
            }
        }
    }

    var canDeleteSourceSeparationModel: Bool {
        !processingLifetimeActive
    }
}
