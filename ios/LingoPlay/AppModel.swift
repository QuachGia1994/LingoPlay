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
        case offline = "Offline"
        case settings = "Settings"

        var id: String { rawValue }

        var systemImage: String {
            switch self {
            case .home: "house.fill"
            case .library: "rectangle.stack.fill"
            case .offline: "arrow.down.circle.fill"
            case .settings: "gearshape.fill"
            }
        }
    }

    struct RecentVideo: Identifiable {
        let id: UUID
        let title: String
        let duration: String
        let languagePair: String
        let progress: Double?

        init(title: String, duration: String, languagePair: String, progress: Double? = nil) {
            id = UUID()
            self.title = title
            self.duration = duration
            self.languagePair = languagePair
            self.progress = progress
        }
    }

    var stage: Stage = .splash
    var selectedTab: Tab = .home
    var processingProgress = 0.0
    var audioBlend = 0.60
    var playbackSpeed = 1.0
    var wifiOnly = true
    var bilingualSubtitles = true
    var importerPresented = false
    var selectedMedia: LocalMediaItem?
    var mediaState: MediaPreparationState = .idle
    var asrState: ASRState = .idle
    var translationState: TranslationState = .idle

    private let mediaService = LocalMediaService()
    private let asrModelStore = ASRModelStore()
    private let speechRecognizer: any OnDeviceSpeechRecognizer = WhisperKitSpeechRecognizer()
    private let translationService = TranslationService()

    let recentVideos = [
        RecentVideo(title: "The Future of AI", duration: "01:24:32", languagePair: "EN → VI"),
        RecentVideo(title: "Space Documentary", duration: "00:48:10", languagePair: "EN → VI"),
        RecentVideo(title: "Marketing Strategy", duration: "00:32:45", languagePair: "EN → VI", progress: 0.65),
    ]

    func finishSplash() {
        stage = .home
    }

    func beginImport() {
        selectedTab = .home
        importerPresented = true
    }

    func importMedia(from url: URL) async {
        mediaState = .importing
        asrState = .idle
        translationState = .idle
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
        } catch {
            translationState = .failed(error.localizedDescription)
        }
    }

    private func translationEndpoint() -> URL? {
        let configured = Bundle.main.object(forInfoDictionaryKey: "LingoPlayTranslationAPIBaseURL") as? String
        guard let configured, !configured.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return URL(string: configured)
    }

    func previewResult() {
        processingProgress = 1
        stage = .player
    }

    func returnHome() {
        selectedTab = .home
        stage = .home
    }

    func selectTab(_ tab: Tab) {
        selectedTab = tab
        stage = .home
    }
}
