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
    var playbackSpeed = DubbingPreferencePolicy.sanitizedPlaybackSpeed(
        UserDefaults.standard.object(forKey: "lingoplay.playbackSpeed") as? Double
    ) {
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
    var translationMode = TranslationMode(rawValue: UserDefaults.standard.string(forKey: "lingoplay.translationMode") ?? "cloud") ?? .cloud {
        didSet { UserDefaults.standard.set(translationMode.rawValue, forKey: "lingoplay.translationMode") }
    }
    var speakerMode = SpeakerMode(rawValue: UserDefaults.standard.string(forKey: "lingoplay.speakerMode") ?? "single") ?? .single {
        didSet { UserDefaults.standard.set(speakerMode.rawValue, forKey: "lingoplay.speakerMode") }
    }
    var voiceCloningEnabled = UserDefaults.standard.bool(forKey: "lingoplay.voiceCloningEnabled") {
        didSet { UserDefaults.standard.set(voiceCloningEnabled, forKey: "lingoplay.voiceCloningEnabled") }
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
    var speakerState: SpeakerState = .idle
    var translationState: TranslationState = .idle
    var ttsState: TTSState = .idle
    var mixState: MixState = .idle
    var modelInstallState: ASRModelInstallState = .notInstalled
    var neuralVoiceInstallState: ASRModelInstallState = .notInstalled
    var speakerModelInstallState: ASRModelInstallState = .notInstalled
    var voiceCloningModelInstallState: ASRModelInstallState = .notInstalled
    var downloadedTranslationModelCodes: Set<String> = []
    var translationModelBusyCode: String?
    var translationModelError: String?
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
    let neuralVoiceInstaller = NeuralVoicePackInstaller()
    let speakerModelInstaller = SpeakerDiarizationModelInstaller()
    let voiceCloningModelInstaller = VoiceCloningModelInstaller()
    let speakerDiarizationService = SpeakerDiarizationService()
    private let speechRecognizer: any OnDeviceSpeechRecognizer = WhisperKitSpeechRecognizer()
    let translationService = TranslationService()
    let offlineTranslationModelManager = OfflineTranslationModelManager()
    let offlineTranslationService = OfflineTranslationService()
    private let ttsService = OfflineDubbingTTSService()
    private let timelineMixService = TimelineMixService()
    private let libraryStore = LocalLibraryStore()
    let processingRecoveryStore = ProcessingRecoveryStore()
    let diagnostics = LocalDiagnostics()
    private var playbackMixContext: PlaybackMixContext?
    private var playbackTimeObserver: Any?
    private var modelInstallTask: Task<Void, Never>?
    var neuralVoiceInstallTask: Task<Void, Never>?
    var speakerModelInstallTask: Task<Void, Never>?
    var voiceCloningModelInstallTask: Task<Void, Never>?
    private var processingTask: Task<Void, Never>?
    private var activeProcessingRunID: UUID?
    var activeProcessingConfig: ProcessingConfig?

    func finishSplash() {
        stage = .home
        plusStore.start()
        TTSCachePolicy.purgeAllSessions()
        Task {
            await refreshLibrary()
            await refreshModelState()
            refreshOfflineTranslationModels()
            pendingRecovery = await processingRecoveryStore.load()
            if pendingRecovery != nil { await diagnostics.record("recovery_available") }
        }
    }

    func beginImport() {
        selectedTab = .home
        importerPresented = true
    }

    func importMedia(from url: URL) async {
        let cancelledProcessingTask = cancelActiveProcessing()
        await cancelledProcessingTask?.value
        let previousMedia = selectedMedia
        teardownPlayback()
        await processingRecoveryStore.clear(deleteMedia: true)
        await processingRecoveryStore.deleteOwnedImportedMedia(previousMedia)
        pendingRecovery = nil
        activeProcessingConfig = nil
        mediaState = .importing
        asrState = .idle
        speakerState = .idle
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
        cancelActiveProcessing()
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
        speakerState = .idle
        translationState = .idle
        ttsState = .idle
        mixState = .idle
        processedMedia = nil
        teardownPlayback()
        let config = currentProcessingConfig()
        activeProcessingConfig = config
        stage = .processing
        pendingRecovery = nil
        launchProcessing(
            media: selectedMedia,
            config: config,
            preparedAudioURL: nil
        )
    }

    struct ProcessingRun {
        let id: UUID
        let media: LocalMediaItem
        let config: ProcessingConfig

        func replacingConfig(_ config: ProcessingConfig) -> ProcessingRun {
            ProcessingRun(id: id, media: media, config: config)
        }
    }

    private func launchProcessing(
        media: LocalMediaItem,
        config: ProcessingConfig,
        preparedAudioURL: URL?
    ) {
        cancelActiveProcessing()
        let run = ProcessingRun(id: UUID(), media: media, config: config)
        activeProcessingRunID = run.id
        processingTask = Task { [weak self] in
            guard let self else { return }
            defer {
                if activeProcessingRunID == run.id {
                    processingTask = nil
                    activeProcessingRunID = nil
                }
            }
            try? await processingRecoveryStore.save(
                media: run.media,
                preparedAudioURL: preparedAudioURL,
                config: run.config,
                processingRunID: run.id
            )
            guard isActive(run) else { return }
            if let preparedAudioURL {
                await recognizeSpeech(from: preparedAudioURL, run: run)
            } else {
                await prepareAudio(run: run)
            }
        }
    }

    @discardableResult
    private func cancelActiveProcessing() -> Task<Void, Never>? {
        let task = processingTask
        task?.cancel()
        processingTask = nil
        activeProcessingRunID = nil
        return task
    }

    func isActive(_ run: ProcessingRun) -> Bool {
        activeProcessingRunID == run.id &&
            !Task.isCancelled &&
            stage == .processing &&
            selectedMedia?.id == run.media.id
    }

    private func prepareAudio(run: ProcessingRun) async {
        guard isActive(run) else { return }
        activeProcessingConfig = run.config
        mediaState = .extractingAudio
        do {
            let audioURL = try await mediaService.extractAudio(from: run.media)
            guard isActive(run) else { return }
            mediaState = .audioReady(audioURL)
            try? await processingRecoveryStore.save(
                media: run.media,
                preparedAudioURL: audioURL,
                config: run.config,
                processingRunID: run.id
            )
            guard isActive(run) else { return }
            processingProgress = 0.2
            await recognizeSpeech(from: audioURL, run: run)
        } catch {
            guard isActive(run) else { return }
            await diagnostics.record("audio_preparation_failed")
            mediaState = .failed(error.localizedDescription)
        }
    }

    private func recognizeSpeech(from audioURL: URL, run: ProcessingRun) async {
        guard isActive(run) else { return }
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
                sourceLanguageCode: run.config.sourceLanguage.code
            )
            guard isActive(run) else { return }
            asrState = .completed(transcript)
            processingProgress = 0.4
            await resolveSpeakers(transcript, audioURL: audioURL, run: run)
        } catch {
            guard isActive(run) else { return }
            await diagnostics.record("asr_failed")
            asrState = .failed(error.localizedDescription)
        }
    }

    func synthesizeOfflineSpeech(
        _ document: TranslationDocument,
        cloneReferences: [String: VoiceCloneReference],
        run: ProcessingRun
    ) async {
        guard isActive(run) else { return }
        do {
            ttsState = .synthesizing(segment: 0, totalSegments: document.segments.count)
            let dub = try await ttsService.synthesize(
                document: document,
                preferredVoiceIdentifier: run.config.preferredVoiceIdentifier,
                speakerVoiceMap: run.config.speakerVoiceMap,
                cloneReferences: cloneReferences
            ) { [weak self] segment, total in
                guard let self, self.isActive(run) else { return }
                ttsState = .synthesizing(segment: segment, totalSegments: total)
                let ratio = total > 0 ? Double(segment) / Double(total) : 0
                processingProgress = 0.6 + (0.2 * ratio)
            }
            guard isActive(run) else {
                TTSCachePolicy.cleanup(document: dub)
                return
            }
            ttsState = .completed(dub)
            processingProgress = 0.8
            await renderDubbedMedia(dub, translation: document, run: run)
        } catch TTSError.offlineVoiceMissing(_) {
            guard isActive(run) else { return }
            await diagnostics.record("tts_voice_missing")
            ttsState = .voiceMissing
        } catch {
            guard isActive(run) else { return }
            await diagnostics.record("tts_failed")
            ttsState = .failed(error.localizedDescription)
        }
    }

    private func renderDubbedMedia(
        _ dub: DubSpeechDocument,
        translation: TranslationDocument,
        run: ProcessingRun
    ) async {
        defer { TTSCachePolicy.cleanup(document: dub) }
        guard isActive(run) else { return }
        do {
            let result = try await timelineMixService.render(
                media: run.media,
                dub: dub,
                mode: run.config.dubbingMode
            ) { [weak self] state in
                guard let self, self.isActive(run) else { return }
                mixState = state
                switch state {
                case .renderingAudio:
                    processingProgress = 0.85
                case .remuxing:
                    processingProgress = 0.92
                case .completed:
                    processingProgress = 1.0
                case .idle, .failed:
                    break
                }
            }
            guard isActive(run) else { return }
            let saved = try await libraryStore.save(
                media: run.media,
                result: result,
                translation: translation,
                dubbingMode: run.config.dubbingMode
            )
            guard isActive(run) else {
                try? await libraryStore.delete(saved)
                return
            }
            await processingRecoveryStore.clear(deleteMedia: false, expectedRunID: run.id)
            pendingRecovery = nil
            await diagnostics.record("processing_completed")
            activeLibraryItem = saved
            activeLibraryURL = await libraryStore.videoURL(for: saved)
            await refreshLibrary()
            guard isActive(run) else { return }
            processedMedia = result
            mixState = .completed(result)
            processingProgress = 1.0
            try await configurePlayback(
                with: result,
                dub: dub,
                media: run.media,
                mode: run.config.dubbingMode
            )
            guard isActive(run) else { return }
            activeProcessingConfig = nil
            stage = .player
        } catch {
            guard isActive(run) else { return }
            await diagnostics.record("mix_failed")
            mixState = .failed(error.localizedDescription)
        }
    }

    func translationEndpoint() -> URL? {
        let configured = Bundle.main.object(
            forInfoDictionaryKey: TranslationEndpointConfiguration.infoDictionaryKey
        ) as? String
        return TranslationEndpointConfiguration.resolve(plistValue: configured)
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
        neuralVoiceInstallState = await neuralVoiceInstaller.state()
        speakerModelInstallState = await speakerModelInstaller.state()
        voiceCloningModelInstallState = await voiceCloningModelInstaller.state()
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
                if case .audioReady(let audioURL) = mediaState,
                   asrState == .modelMissing,
                   stage == .processing,
                   let media = selectedMedia {
                    let config = activeProcessingConfig ?? currentProcessingConfig()
                    activeProcessingConfig = config
                    launchProcessing(
                        media: media,
                        config: config,
                        preparedAudioURL: audioURL
                    )
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

    func resumeProcessingAfterSpeakerModelInstall() {
        guard case .audioReady(let audioURL) = mediaState,
              speakerState == .modelMissing,
              stage == .processing,
              let media = selectedMedia
        else { return }
        let config = activeProcessingConfig ?? currentProcessingConfig()
        activeProcessingConfig = config
        launchProcessing(media: media, config: config, preparedAudioURL: audioURL)
    }

    func resumePendingProcessing() {
        guard let recovery = pendingRecovery else { return }
        Task { await diagnostics.record("recovery_resumed") }
        selectedMedia = recovery.media
        let config = recovery.config ?? currentProcessingConfig()
        activeProcessingConfig = config
        processingProgress = recovery.canResumeFromAudio ? 0.2 : 0
        asrState = .idle
        speakerState = .idle
        translationState = .idle
        ttsState = .idle
        mixState = .idle
        processedMedia = nil
        teardownPlayback()
        pendingRecovery = nil
        stage = .processing
        if let audioURL = recovery.preparedAudioURL, recovery.canResumeFromAudio {
            mediaState = .audioReady(audioURL)
            launchProcessing(
                media: recovery.media,
                config: config,
                preparedAudioURL: audioURL
            )
        } else {
            mediaState = .ready
            launchProcessing(
                media: recovery.media,
                config: config,
                preparedAudioURL: nil
            )
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
                    segments: item.segments,
                    mode: item.translationMode ?? .cloud,
                    speakerVoiceMap: item.speakerVoiceMap
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

    private func configurePlayback(
        with result: LocalDubMediaResult,
        dub: DubSpeechDocument,
        media: LocalMediaItem,
        mode: DubbingModePreset
    ) async throws {
        teardownPlayback()
        try preparePlaybackAudioSession()

        let session = try await timelineMixService.makePlaybackSession(
            media: media,
            dubbedAudioURL: result.dubbedAudioURL,
            speechSegments: dub.segments,
            blend: audioBlend,
            mode: mode
        )
        let player = AVPlayer(playerItem: session.item)
        player.defaultRate = Float(playbackSpeed)
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
        player.defaultRate = Float(playbackSpeed)
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
        let rate = Float(playbackSpeed)
        videoPlayer.defaultRate = rate
        if videoPlayer.timeControlStatus == .playing || videoPlayer.rate != 0 {
            videoPlayer.playImmediately(atRate: rate)
            isPlaying = true
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
            subtitleMode: subtitleMode,
            translationMode: translationMode,
            speakerMode: speakerMode,
            speakerVoiceMap: [:],
            voiceCloningEnabled: voiceCloningEnabled
        )
    }

    func localDiagnosticsCount() async -> Int {
        await diagnostics.recent().count
    }

    func returnHome() {
        let previousStage = stage
        let mediaToRelease = selectedMedia
        let cancelledProcessingTask = previousStage == .processing ? cancelActiveProcessing() : nil
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
            Task { [weak self] in
                await cancelledProcessingTask?.value
                guard let self else { return }
                pendingRecovery = await processingRecoveryStore.load()
            }
        }
        stage = .home
    }

    func selectTab(_ tab: Tab) {
        pausePlayback()
        selectedTab = tab
        stage = .home
    }
}
