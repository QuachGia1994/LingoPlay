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
    var playbackSpeed = 1.0
    var wifiOnly = UserDefaults.standard.object(forKey: "lingoplay.wifiOnly") as? Bool ?? true {
        didSet { UserDefaults.standard.set(wifiOnly, forKey: "lingoplay.wifiOnly") }
    }
    var bilingualSubtitles = true
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
        stage = .processing
        pendingRecovery = nil
        Task {
            try? await processingRecoveryStore.save(media: selectedMedia)
            await prepareAudio()
        }
    }

    func prepareAudio() async {
        guard let selectedMedia else { return }
        mediaState = .extractingAudio
        do {
            let audioURL = try await mediaService.extractAudio(from: selectedMedia)
            mediaState = .audioReady(audioURL)
            try? await processingRecoveryStore.save(media: selectedMedia, preparedAudioURL: audioURL)
            processingProgress = 0.2
            await recognizeSpeech(from: audioURL)
        } catch {
            await diagnostics.record("audio_preparation_failed")
            mediaState = .failed(error.localizedDescription)
        }
    }

    private func recognizeSpeech(from audioURL: URL) async {
        guard let model = asrModelStore.whisperModel() else {
            asrState = .modelMissing
            modelInstallState = .notInstalled
            return
        }
        asrState = .loadingModel
        do {
            asrState = .transcribing
            let transcript = try await speechRecognizer.transcribe(audioURL: audioURL, model: model)
            asrState = .completed(transcript)
            processingProgress = 0.4
            await translateTranscript(transcript)
        } catch {
            await diagnostics.record("asr_failed")
            asrState = .failed(error.localizedDescription)
        }
    }

    private func translateTranscript(_ transcript: ASRTranscript) async {
        guard let endpoint = translationEndpoint() else {
            translationState = .endpointMissing
            return
        }

        do {
            let document = try await translationService.translate(
                transcript: transcript,
                targetLanguage: "vi",
                endpoint: endpoint
            ) { [weak self] batch, total in
                self?.translationState = .translating(batch: batch, totalBatches: total)
                let ratio = total > 0 ? Double(batch) / Double(total) : 0
                self?.processingProgress = 0.4 + (0.2 * ratio)
            }
            translationState = .completed(document)
            processingProgress = 0.6
            await synthesizeVietnameseSpeech(document)
        } catch {
            await diagnostics.record("translation_failed")
            translationState = .failed(error.localizedDescription)
        }
    }

    private func synthesizeVietnameseSpeech(_ document: TranslationDocument) async {
        do {
            let dub = try await ttsService.synthesize(document: document) { [weak self] segment, total in
                self?.ttsState = .synthesizing(segment: segment, totalSegments: total)
                let ratio = total > 0 ? Double(segment) / Double(total) : 0
                self?.processingProgress = 0.6 + (0.2 * ratio)
            }
            ttsState = .completed(dub)
            processingProgress = 0.8
            await renderDubbedMedia(dub)
        } catch TTSError.offlineVietnameseVoiceMissing {
            await diagnostics.record("tts_voice_missing")
            ttsState = .voiceMissing
        } catch {
            await diagnostics.record("tts_failed")
            ttsState = .failed(error.localizedDescription)
        }
    }

    private func renderDubbedMedia(_ dub: DubSpeechDocument) async {
        guard let selectedMedia else { return }
        do {
            let result = try await timelineMixService.render(media: selectedMedia, dub: dub) { [weak self] state in
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
                translation: translation
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
            try await configurePlayback(with: result, dub: dub)
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
                    await recognizeSpeech(from: audioURL)
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
            Task { await recognizeSpeech(from: audioURL) }
        } else {
            mediaState = .ready
            Task { await prepareAudio() }
        }
    }

    func discardPendingProcessing() {
        pendingRecovery = nil
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
        videoPlayer.play()
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
        return document.segments.last { positionMs >= $0.startMs && positionMs < $0.endMs }
    }

    private func configurePlayback(with result: LocalDubMediaResult, dub: DubSpeechDocument) async throws {
        guard let selectedMedia else { return }
        teardownPlayback()
        try preparePlaybackAudioSession()

        let session = try await timelineMixService.makePlaybackSession(
            media: selectedMedia,
            dubbedAudioURL: result.dubbedAudioURL,
            speechSegments: dub.segments,
            blend: audioBlend
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
                Task { await processingRecoveryStore.deleteOwnedImportedMedia(mediaToRelease) }
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
