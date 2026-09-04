import Foundation

@MainActor
extension AppModel {
    func installNeuralVoice() {
        guard neuralVoiceInstallTask == nil else { return }
        neuralVoiceInstallTask = Task { [weak self] in
            guard let self else { return }
            defer { neuralVoiceInstallTask = nil }
            do {
                _ = try await neuralVoiceInstaller.install(wifiOnly: wifiOnly) { [weak self] progress in
                    await MainActor.run {
                        self?.neuralVoiceInstallState = .downloading(progress: progress)
                    }
                }
                neuralVoiceInstallState = await neuralVoiceInstaller.state()
            } catch is CancellationError {
                neuralVoiceInstallState = await neuralVoiceInstaller.state()
            } catch {
                await diagnostics.record("neural_voice_install_failed")
                neuralVoiceInstallState = .failed(error.localizedDescription)
            }
        }
    }

    func cancelNeuralVoiceInstall() {
        neuralVoiceInstallTask?.cancel()
    }

    func deleteNeuralVoice() {
        guard canDeleteNeuralVoice else { return }
        neuralVoiceInstallTask?.cancel()
        neuralVoiceInstallTask = nil
        Task {
            do {
                try await neuralVoiceInstaller.deleteInstalledVoice()
                neuralVoiceInstallState = .notInstalled
                if preferredVoiceIdentifier == NeuralVoicePackManifest.voiceIdentifier {
                    preferredVoiceIdentifier = nil
                }
            } catch {
                neuralVoiceInstallState = .failed(error.localizedDescription)
            }
        }
    }

    var canDeleteNeuralVoice: Bool {
        if case .synthesizing = ttsState { return false }
        return true
    }
}
