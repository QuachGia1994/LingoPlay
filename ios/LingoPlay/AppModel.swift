import AVFoundation
import Foundation
import Observation

@MainActor
@Observable
final class AppModel {
    enum Stage: Equatable {
        case splash
        case home
        case prepare
        case processing
        case player
    }

    enum Tab: String, CaseIterable, Identifiable {
        case home = "Home"
        case library = "Library"
        case settings = "Settings"

        var id: String { rawValue }

        var systemImage: String {
            switch self {
            case .home: "house.fill"
            case .library: "rectangle.stack.fill"
            case .settings: "gearshape.fill"
            }
        }
    }

    var stage: Stage = .splash
    var selectedTab: Tab = .home
    var processingProgress = 0.0
    var audioBlend = 0.60 {
        didSet { updatePlayerMix() }
    }
    var playbackSpeed = UserDefaults.standard.object(forKey: "lingoplay.playbackSpeed") as? Double ?? 1.0 {
        didSet {
            UserDefaults.standard.set(playbackSpeed, forKey: "lingoplay.playbackSpeed")
            applyPlaybackSpeed()
        }
    }
    var sourceLanguageChoice = SourceLanguageChoice(rawValue: UserDefaults.standard.string(forKey: "lingoplay.sourceLanguage") ?? "auto") ?? .auto {
        didSet { UserDefaults.standard.set(sourceLanguageChoice.rawValue, forKey: "lingoplay.sourceLanguage") }
    }
    var targetLanguageChoice = TargetLanguageChoice(rawValue: UserDefaults.standard.string(forKey: "lingoplay.targetLanguage") ?? "vi") ?? .vi {
        didSet {
            UserDefaults.standard.set(targetLanguageChoice.rawValue, forKey: "lingoplay.targetLanguage")
            if !availableTargetVoices.contains(where: { $0.id == preferredVoiceIdentifier }) {
                preferredVoiceIdentifier = nil
            }
        }
    }
    var dubbingMode = DubbingModePreset(rawValue: UserDefaults.standard.string(forKey: "lingoplay.dubbingMode") ?? "balanced") ?? .balanced {
        didSet { UserDefaults.standard.set(dubbingMode.rawValue, forKey: "lingoplay.dubbingMode") }
    }
    var subtitleMode = SubtitleMode(rawValue: UserDefaults.standard.string(forKey: "lingoplay.subtitleMode") ?? "bilingual") ?? .bilingual {
        didSet { UserDefaults.standard.set(subtitleMode.rawValue, forKey: "lingoplay.subtitleMode") }
    }
    var preferredVoiceIdentifier = UserDefaults.standard.string(forKey: "lingoplay.preferredVoice") {
        didSet { UserDefaults.standard.set(preferredVoiceIdentifier, forKey: "lingoplay.preferredVoice") }
    }
    var wifiOnly = UserDefaults.standard.object(forKey: "lingoplay.wifiOnly") as? Bool ?? true {
        didSet { UserDefaults.standard.set(wifiOnly, forKey: "lingoplay.wifiOnly") }
    }
    var highContrast = UserDefaults.standard.bool(forKey: "lingoplay.highContrast")
    var uiLanguageCode = UserDefaults.standard.string(forKey: "lingoplay.uiLanguage") ?? "en"
    var importerPresented = false
    var selectedMedia: LocalMediaItem?
    var mediaState: MediaPreparationState = .idle
    var asrState: ASRState = .idle
    var translationState: TranslationState = .idle
    var ttsState: TTSState = .idle
    var mixState: MixState = .idle
    var modelInstallState: ASRModelInstallState = .notInstalled
    var plusPresented = false
    var aboutPresented = false
    var pendingRecovery: ProcessingRecoveryCheckpoint?
    var processedMedia: LocalDubMediaResult?
    var libraryItems: [LocalLibraryItem] = []
    var libraryBytes: Int64 = 0
    var libraryURLs: [UUID: URL] = [:]
    var activeLibraryItem: LocalLibraryItem?
    var activeLibraryURL: URL?
    var liveBlendAvailable = false
    var videoPlayer: AVPlayer?
    var playbackPosition = 0.0
    var playbackDuration = 0.0
    var isPlaying = false

    let plusStore = PlusStore()

    private let mediaService = LocalMediaService()
    private let asrModelStore = ASRModelStore()
    private let modelInstaller = WhisperModelInstaller()
    private let speechRecognizer: any OnDeviceSpeechRecognizer = WhisperKitSpeechRecognizer()
    private let translationService = TranslationService()
    private let ttsService = SystemVietnameseTTSService()
    private let timelineMixService = TimelineMixService()
    private let libraryStore = LocalLibraryStore()
    private let processingRecoveryStore = ProcessingRecoveryStore()
    private let diagnostics = LocalDiagnostics()
    private var playbackMixContext: PlaybackMixContext?
    private var playbackTimeObserver: Any?
    private var modelInstallTask: Task<Void, Never>?
    private var activeProcessingConfig: ProcessingConfig?

    func finishSplash() {
        stage = .home
        plusStore.start()
        Task {
            await refreshLibrary()
            await refreshModelState()
            pendingRecovery = await processingRecoveryStore.load()
            if pendingRecovery != nil { await diagnostics.record("recovery_available") }
        }
    }

    func beginImport() {
        selectedTab = .home
        importerPresented = true
    }

    func importMedia(from url: URL) async {
        let previousMedia = selectedMedia
        teardownPlayback()
        await processingRecoveryStore.clear(deleteMedia: true)
        await processingRecoveryStore.deleteOwnedImportedMedia(previousMedia)
        pendingRecovery = nil
        activeProcessingConfig = nil
        mediaState = .importing
        asrState = .idle
        translationState = .idle
        ttsState = .idle
        mixState = .idle
        processedMedia = nil
        activeLibraryItem = nil
        activeLibraryURL = nil
        liveBlendAvailable = false
        processingProgress = 0
        do {
            selectedMedia = try await mediaService.importMedia(from: url)
            await diagnostics.record("import_completed")
            mediaState = .ready
            stage = .prepare
        } catch {
            await diagnostics.record("import_failed")
            mediaState = .failed(error.localizedDescription)
        }
    }

    func cancelPreparation() {
        let media = selectedMedia
        selectedMedia = nil
        mediaState = .idle
        pendingRecovery = nil
        activeProcessingConfig = nil
        stage = .home
        Task {
            await processingRecoveryStore.clear(deleteMedia: true)
            await processingRecoveryStore.deleteOwnedImportedMedia(media)
            await diagnostics.record("preparation_cancelled")
        }
    }

    func beginProcessing() {
        guard let selectedMedia else {
            importerPresented = true
            return
        }
        processingProgress = 0
        asrState = .idle
        translationState = .idle
        ttsState = .idle
        mixState = .idle
        processedMedia = nil
        teardownPlayback()
        let config = currentProcessingConfig()
        activeProcessingConfig = config
        stage = .processing
        pendingRecovery = nil
        Task {
            try? await processingRecoveryStore.save(media: selectedMedia, config: config)
            await prepareAudio(config: config)
        }
    }

    func prepareAudio(config: ProcessingConfig? = nil) async {
        guard let selectedMedia else { return }
        let runConfig = config ?? activeProcessingConfig ?? currentProcessingConfig()
        activeProcessingConfig = runConfig
        mediaState = .extractingAudio
        do {
            let audioURL = try await mediaService.extractAudio(from: selectedMedia)
            mediaState = .audioReady(audioURL)
            try? await processingRecoveryStore.save(media: selectedMedia, preparedAudioURL: audioURL, config: runConfig)
            processingProgress = 0.2
            await recognizeSpeech(from: audioURL, config: runConfig)
        } catch {
            await diagnostics.record("audio_preparation_failed")
            mediaState = .failed(error.localizedDescription)
        }
    }

    private func recognizeSpeech(from audioURL: URL, config: ProcessingConfig) async {
        guard let model = asrModelStore.whisperModel() else {
            asrState = .modelMissing
            modelInstallState = .notInstalled
            return
        }
        asrState = .loadingModel
        do {
            asrState = .transcribing
            let transcript = try await speechRecognizer.transcribe(
                audioURL: audioURL,
                model: model,
                sourceLanguageCode: config.sourceLanguage.code
            )
            asrState = .completed(transcript)
            processingProgress = 0.4
            await translateTranscript(transcript, config: config)
        } catch {
            await diagnostics.record("asr_failed")
            asrState = .failed(error.localizedDescription)
        }
    }

    private func translateTranscript(_ transcript: ASRTranscript, config: ProcessingConfig) async {
        guard let endpoint = translationEndpoint() else {
            translationState = .endpointMissing
            return
        }

        do {
            let document = try await translationService.translate(
                transcript: transcript,
                targetLanguage: config.targetLanguage.code,
                endpoint: endpoint
            ) { [weak self] batch, total in
                self?.translationState = .translating(batch: batch, totalBatches: total)
                let ratio = total > 0 ? Double(batch) / Double(total) : 0
                self?.processingProgress = 0.4 + (0.2 * ratio)
            }
            translationState = .completed(document)
            processingProgress = 0.6
            await synthesizeOfflineSpeech(document, config: config)
        } catch {
            await diagnostics.record("translation_failed")
            translationState = .failed(error.localizedDescription)
        }
    }

    private func synthesizeOfflineSpeech(_ document: TranslationDocument, config: ProcessingConfig) async {
        do {
            let dub = try await ttsService.synthesize(
                document: document,
                preferredVoiceIdentifier: config.preferredVoiceIdentifier
            ) { [weak self] segment, total in
                self?.ttsState = .synthesizing(segment: segment, totalSegments: total)
                let ratio = total > 0 ? Double(segment) / Double(total) : 0
                self?.processingProgress = 0.6 + (0.2 * ratio)
            }
            ttsState = .completed(dub)
            processingProgress = 0.8
            await renderDubbedMedia(dub, config: config)
        } catch TTSError.offlineVoiceMissing(_) {
            await diagnostics.record("tts_voice_missing")
            ttsState = .voiceMissing
        } catch {
            await diagnostics.record("tts_failed")
            ttsState = .failed(error.localizedDescription)
        }
    }

    private func renderDubbedMedia(_ dub: DubSpeechDocument, config: ProcessingConfig) async {
        guard let selectedMedia else { return }
        do {
            let result = try await timelineMixService.render(
                media: selectedMedia,
                dub: dub,
                mode: config.dubbingMode
            ) { [weak self] state in
                self?.mixState = state
                switch state {
                case .renderingAudio:
                    self?.processingProgress = 0.85
                case .remuxing:
                    self?.processingProgress = 0.92
                case .completed:
                    self?.processingProgress = 1.0
                case .idle, .failed:
                    break
                }
            }
            let translation: TranslationDocument?
            if case .completed(let document) = translationState {
                translation = document
            } else {
                translation = nil
            }
            let saved = try await libraryStore.save(
                media: selectedMedia,
                result: result,
                translation: translation,
                dubbingMode: config.dubbingMode
            )
            await processingRecoveryStore.clear(deleteMedia: false)
            pendingRecovery = nil
            await diagnostics.record("processing_completed")
            activeLibraryItem = saved
            activeLibraryURL = await libraryStore.videoURL(for: saved)
            await refreshLibrary()
            processedMedia = result
            mixState = .completed(result)
            processingProgress = 1.0
            try await configurePlayback(with: result, dub: dub, mode: config.dubbingMode)
            activeProcessingConfig = nil
            stage = .player
        } catch {
            await diagnostics.record("mix_failed")
            mixState = .failed(error.localizedDescription)
        }
    }

    private func translationEndpoint() -> URL? {
        let configured = Bundle.main.object(forInfoDictionaryKey: "LingoPlayTranslationAPIBaseURL") as? String
        guard let configured, !configured.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return URL(string: configured)
    }

    func refreshLibrary() async {
        guard let items = try? await libraryStore.load() else { return }
        var urls: [UUID: URL] = [:]
        for item in items {
            urls[item.id] = await libraryStore.videoURL(for: item)
        }
        libraryItems = items
        libraryBytes = await libraryStore.totalBytes(items)
        libraryURLs = urls
    }

    func refreshModelState() async {
        modelInstallState = await modelInstaller.state()
    }

    func installSpeechModel() {
        guard modelInstallTask == nil else { return }
        modelInstallTask = Task { [weak self] in
            guard let self else { return }
            defer { modelInstallTask = nil }
            do {
                _ = try await modelInstaller.install(wifiOnly: wifiOnly) { [weak self] progress in
                    await MainActor.run {
                        self?.modelInstallState = .downloading(progress: progress)
                    }
                }
                await refreshModelState()
                if case .audioReady(let audioURL) = mediaState, asrState == .modelMissing {
                    let config = activeProcessingConfig ?? currentProcessingConfig()
                    activeProcessingConfig = config
                    await recognizeSpeech(from: audioURL, config: config)
                }
            } catch is CancellationError {
                await refreshModelState()
            } catch {
                await diagnostics.record("model_install_failed")
                modelInstallState = .failed(error.localizedDescription)
            }
        }
    }

    func cancelSpeechModelInstall() {
        modelInstallTask?.cancel()
    }

    func resumePendingProcessing() {
        guard let recovery = pendingRecovery else { return }
        Task { await diagnostics.record("recovery_resumed") }
        selectedMedia = recovery.media
        let config = recovery.config ?? currentProcessingConfig()
        activeProcessingConfig = config
        processingProgress = recovery.canResumeFromAudio ? 0.2 : 0
        asrState = .idle
        translationState = .idle
        ttsState = .idle
        mixState = .idle
        processedMedia = nil
        teardownPlayback()
        pendingRecovery = nil
        stage = .processing
        if let audioURL = recovery.preparedAudioURL, recovery.canResumeFromAudio {
            mediaState = .audioReady(audioURL)
            Task { await recognizeSpeech(from: audioURL, config: config) }
        } else {
            mediaState = .ready
            Task { await prepareAudio(config: config) }
        }
    }

    func discardPendingProcessing() {
        pendingRecovery = nil
        activeProcessingConfig = nil
        Task {
            await diagnostics.record("recovery_discarded")
            await processingRecoveryStore.clear(deleteMedia: true)
        }
    }

    func deleteSpeechModel() {
        guard asrState != .loadingModel, asrState != .transcribing else { return }
        modelInstallTask?.cancel()
        modelInstallTask = nil
        Task {
            do {
                try await modelInstaller.deleteInstalledModel()
                modelInstallState = .notInstalled
            } catch {
                modelInstallState = .failed(error.localizedDescription)
            }
        }
    }

    var canDeleteSpeechModel: Bool {
        asrState != .loadingModel && asrState != .transcribing
    }

    func openLibraryItem(_ item: LocalLibraryItem) async {
        guard let url = await libraryStore.videoURL(for: item),
              FileManager.default.fileExists(atPath: url.path)
        else {
            await refreshLibrary()
            return
        }
        let size = await libraryStore.fileSize(for: item)
        activeLibraryItem = item
        activeLibraryURL = url
        selectedMedia = LocalMediaItem(
            id: item.id,
            localURL: url,
            title: item.title,
            duration: item.duration,
            fileSizeBytes: size,
            hasAudioTrack: true
        )
        processedMedia = nil
        if item.segments.isEmpty {
            translationState = .idle
        } else {
            translationState = .completed(
                TranslationDocument(
                    sourceLanguage: item.sourceLanguage,
                    targetLanguage: item.targetLanguage,
                    segments: item.segments
                )
            )
        }
        configureSavedPlayback(url: url, duration: item.duration)
        stage = .player
    }

    func deleteLibraryItem(_ item: LocalLibraryItem) async {
        try? await libraryStore.delete(item)
        if activeLibraryItem?.id == item.id {
            activeLibraryItem = nil
            activeLibraryURL = nil
            teardownPlayback()
            stage = .home
        }
        await refreshLibrary()
    }

    func togglePlayback() {
        guard let videoPlayer else { return }
        if isPlaying {
            pausePlayback()
            return
        }
        videoPlayer.playImmediately(atRate: Float(playbackSpeed))
        isPlaying = true
    }

    func pausePlayback() {
        videoPlayer?.pause()
        isPlaying = false
    }

    func seek(to fraction: Double) {
        guard playbackDuration > 0 else { return }
        let seconds = min(max(fraction, 0), 1) * playbackDuration
        seekPlayback(to: seconds)
    }

    func skipPlayback(seconds: Double) {
        seekPlayback(to: min(max(playbackPosition + seconds, 0), playbackDuration))
    }

    var activeTranslationSegment: TranslationSegment? {
        guard case .completed(let document) = translationState else { return nil }
        let positionMs = Int((playbackPosition * 1_000).rounded())
        return document.segments.last { positionMs >= $0.startMs && positionMs <= $0.endMs }
    }

    private func configurePlayback(with result: LocalDubMediaResult, dub: DubSpeechDocument, mode: DubbingModePreset) async throws {
        guard let selectedMedia else { return }
        teardownPlayback()
        try preparePlaybackAudioSession()

        let session = try await timelineMixService.makePlaybackSession(
            media: selectedMedia,
            dubbedAudioURL: result.dubbedAudioURL,
            speechSegments: dub.segments,
            blend: audioBlend,
            mode: mode
        )
        let player = AVPlayer(playerItem: session.item)
        videoPlayer = player
        playbackMixContext = session.mixContext
        liveBlendAvailable = true
        playbackDuration = max(0, result.duration)
        playbackPosition = 0
        isPlaying = false
        installPlaybackObserver(on: player)
    }

    private func configureSavedPlayback(url: URL, duration: TimeInterval) {
        teardownPlayback()
        try? preparePlaybackAudioSession()
        let player = AVPlayer(url: url)
        videoPlayer = player
        playbackDuration = max(0, duration)
        playbackPosition = 0
        isPlaying = false
        liveBlendAvailable = false
        installPlaybackObserver(on: player)
    }

    private func preparePlaybackAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .moviePlayback)
        try session.setActive(true)
    }

    private func installPlaybackObserver(on player: AVPlayer) {
        let interval = CMTime(seconds: 0.25, preferredTimescale: 600)
        playbackTimeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            Task { @MainActor in
                guard let self else { return }
                let seconds = max(0, time.seconds.isFinite ? time.seconds : 0)
                self.playbackPosition = min(seconds, self.playbackDuration)
            }
        }
    }

    private func seekPlayback(to seconds: Double) {
        guard playbackDuration > 0 else { return }
        let clamped = min(max(seconds, 0), playbackDuration)
        let time = CMTime(seconds: clamped, preferredTimescale: 600)
        videoPlayer?.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero)
        playbackPosition = clamped
    }

    private func updatePlayerMix() {
        guard let context = playbackMixContext, let item = videoPlayer?.currentItem else { return }
        item.audioMix = timelineMixService.makePlaybackAudioMix(context: context, blend: audioBlend)
    }

    private func applyPlaybackSpeed() {
        guard let videoPlayer else { return }
        if isPlaying {
            videoPlayer.playImmediately(atRate: Float(playbackSpeed))
        }
    }

    private func teardownPlayback() {
        pausePlayback()
        if let playbackTimeObserver, let videoPlayer {
            videoPlayer.removeTimeObserver(playbackTimeObserver)
        }
        playbackTimeObserver = nil
        playbackMixContext = nil
        liveBlendAvailable = false
        videoPlayer = nil
        playbackPosition = 0
        playbackDuration = 0
    }

    private func currentProcessingConfig() -> ProcessingConfig {
        ProcessingConfig(
            sourceLanguage: sourceLanguageChoice,
            targetLanguage: targetLanguageChoice,
            preferredVoiceIdentifier: preferredVoiceIdentifier,
            dubbingMode: dubbingMode,
            subtitleMode: subtitleMode
        )
    }

    var availableOfflineVoices: [OfflineVoiceOption] {
        DubbingPreferencePolicy.availableOfflineVoices()
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

    func localDiagnosticsCount() async -> Int {
        await diagnostics.recent().count
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

    func returnHome() {
        let previousStage = stage
        let mediaToRelease = selectedMedia
        if previousStage == .player {
            teardownPlayback()
            if activeLibraryItem != nil {
                selectedMedia = nil
                let activeURL = activeLibraryURL?.standardizedFileURL
                if mediaToRelease?.localURL.standardizedFileURL != activeURL {
                    Task { await processingRecoveryStore.deleteOwnedImportedMedia(mediaToRelease) }
                }
            }
        } else {
            pausePlayback()
        }
        selectedTab = .home
        if previousStage == .processing {
            Task { pendingRecovery = await processingRecoveryStore.load() }
        }
        stage = .home
    }

    func selectTab(_ tab: Tab) {
        pausePlayback()
        selectedTab = tab
        stage = .home
    }
}
