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
    var wifiOnly = true
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

    private let mediaService = LocalMediaService()
    private let asrModelStore = ASRModelStore()
    private let speechRecognizer: any OnDeviceSpeechRecognizer = WhisperKitSpeechRecognizer()
    private let translationService = TranslationService()
    private let ttsService = SystemVietnameseTTSService()
    private let timelineMixService = TimelineMixService()
    private let libraryStore = LocalLibraryStore()
    private var playbackMixContext: PlaybackMixContext?
    private var playbackTimeObserver: Any?

    func finishSplash() {
        stage = .home
        Task { await refreshLibrary() }
    }

    func beginImport() {
        selectedTab = .home
        importerPresented = true
    }

    func importMedia(from url: URL) async {
        mediaState = .importing
        asrState = .idle
        translationState = .idle
        ttsState = .idle
        mixState = .idle
        processedMedia = nil
        activeLibraryItem = nil
        activeLibraryURL = nil
        liveBlendAvailable = false
        teardownPlayback()
        processingProgress = 0
        do {
            selectedMedia = try await mediaService.importMedia(from: url)
            mediaState = .ready
            stage = .prepare
        } catch {
            mediaState = .failed(error.localizedDescription)
        }
    }

    func beginProcessing() {
        guard selectedMedia != nil else {
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
        Task { await prepareAudio() }
    }

    func prepareAudio() async {
        guard let selectedMedia else { return }
        mediaState = .extractingAudio
        do {
            let audioURL = try await mediaService.extractAudio(from: selectedMedia)
            mediaState = .audioReady(audioURL)
            processingProgress = 0.2
            await recognizeSpeech(from: audioURL)
        } catch {
            mediaState = .failed(error.localizedDescription)
        }
    }

    private func recognizeSpeech(from audioURL: URL) async {
        guard let modelFolder = asrModelStore.whisperModelFolder() else {
            asrState = .modelMissing
            return
        }
        asrState = .loadingModel
        do {
            asrState = .transcribing
            let transcript = try await speechRecognizer.transcribe(audioURL: audioURL, modelFolder: modelFolder)
            asrState = .completed(transcript)
            processingProgress = 0.4
            await translateTranscript(transcript)
        } catch {
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
            ttsState = .voiceMissing
        } catch {
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
            activeLibraryItem = saved
            activeLibraryURL = await libraryStore.videoURL(for: saved)
            await refreshLibrary()
            processedMedia = result
            mixState = .completed(result)
            processingProgress = 1.0
            try await configurePlayback(with: result, dub: dub)
            stage = .player
        } catch {
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
        let player = AVPlayer(url: url)
        videoPlayer = player
        playbackDuration = max(0, duration)
        playbackPosition = 0
        isPlaying = false
        liveBlendAvailable = false
        installPlaybackObserver(on: player)
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
        pausePlayback()
        selectedTab = .home
        stage = .home
    }

    func selectTab(_ tab: Tab) {
        pausePlayback()
        selectedTab = tab
        stage = .home
    }
}
