import AVFoundation
import AVKit
import CoreTransferable
import PhotosUI
import StoreKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

private struct PickedVideoFile: Transferable, Sendable {
    let url: URL

    nonisolated static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { received in
            let source = received.file
            let fileExtension = source.pathExtension.isEmpty ? "mov" : source.pathExtension
            let copy = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension(fileExtension)
            try FileManager.default.copyItem(at: source, to: copy)
            return PickedVideoFile(url: copy)
        }
    }
}

struct SplashView: View {
    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            LPBrandMark()
                .scaleEffect(1.18)
            Text("LingoPlay")
                .font(.system(size: 38, weight: .bold, design: .rounded))
                .foregroundStyle(LPTheme.accent)
            Text("AI video translation & dubbing")
                .font(.subheadline)
                .foregroundStyle(LPTheme.secondaryText)
            Spacer()
            VStack(spacing: 12) {
                ProgressView()
                    .tint(LPTheme.cyan)
                Text("Preparing LingoPlay…")
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
            }
            .padding(.bottom, 56)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }
}

struct HomeView: View {
    @Bindable var model: AppModel
    @State private var pickedVideo: PhotosPickerItem?

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("LingoPlay")
                            .font(.title2.bold())
                            .foregroundStyle(LPTheme.accent)
                        Text(model.uiText("Understand without borders", "Hiểu mọi nội dung, không rào cản"))
                            .font(.caption)
                            .foregroundStyle(LPTheme.secondaryText)
                    }
                    Spacer()
                    Button {
                        model.plusPresented = true
                    } label: {
                        Image(systemName: "crown.fill")
                            .foregroundStyle(LPTheme.accent)
                            .padding(10)
                            .background(LPTheme.surface, in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("LingoPlay Plus")
                }

                VStack(alignment: .leading, spacing: 18) {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(model.uiText("AI video translation\n& dubbing", "Dịch video AI\n& lồng tiếng"))
                                .font(.system(size: 31, weight: .bold, design: .rounded))
                            Text(model.uiText("Import a video you already have. The media stays on this device.", "Chọn video có sẵn trong thư viện. Media luôn nằm trên thiết bị này."))
                                .font(.subheadline)
                                .foregroundStyle(LPTheme.secondaryText)
                        }
                        Spacer(minLength: 12)
                        LPBrandMark()
                    }
                    LPPrimaryButton(title: model.uiText("Import Video", "Chọn video"), systemImage: "plus") {
                        model.beginImport()
                    }
                    Label(model.uiText("Video and audio are never uploaded", "Video và audio không bao giờ được tải lên server"), systemImage: "lock.shield.fill")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(LPTheme.secondaryText)
                }
                .lpCard()

                if let recovery = model.pendingRecovery {
                    VStack(alignment: .leading, spacing: 12) {
                        Label(model.uiText("Interrupted dub", "Bản lồng tiếng bị gián đoạn"), systemImage: "arrow.clockwise.circle.fill")
                            .font(.headline)
                            .foregroundStyle(LPTheme.accent)
                        Text(recovery.media.title)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                        Text(model.uiText("Resume from the last durable local boundary. Media remains in LingoPlay storage.", "Tiếp tục từ mốc cục bộ an toàn gần nhất. Media vẫn nằm trong bộ nhớ LingoPlay."))
                            .font(.caption)
                            .foregroundStyle(LPTheme.secondaryText)
                        LPPrimaryButton(title: model.uiText("Resume Processing", "Tiếp tục xử lý"), systemImage: "play.fill") {
                            model.resumePendingProcessing()
                        }
                        Button(role: .destructive) {
                            model.discardPendingProcessing()
                        } label: {
                            Text(model.uiText("Discard interrupted session", "Bỏ phiên bị gián đoạn"))
                        }
                        .buttonStyle(.bordered)
                    }
                    .lpCard()
                }

                LPSectionHeader(title: model.uiText("Recent", "Gần đây"), trailing: model.uiText("Local library", "Thư viện cục bộ"))

                VStack(spacing: 12) {
                    if model.libraryItems.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(model.uiText("No dubbed videos yet", "Chưa có video lồng tiếng"))
                                .font(.headline)
                            Text(model.uiText("Import and process a local video. Completed results are saved privately on this device.", "Chọn và xử lý một video. Kết quả hoàn tất sẽ được lưu riêng trên thiết bị."))
                                .font(.caption)
                                .foregroundStyle(LPTheme.secondaryText)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .lpCard()
                    } else {
                        ForEach(model.libraryItems.prefix(3)) { item in
                            SavedVideoRow(item: item) {
                                Task { await model.openLibraryItem(item) }
                            }
                        }
                    }
                }

                HStack(spacing: 12) {
                    MiniCapability(icon: "waveform", title: model.uiText("On-device", "Trên máy"), detail: model.uiText("Speech AI", "AI giọng nói"))
                    MiniCapability(icon: "captions.bubble.fill", title: model.uiText("Bilingual", "Song ngữ"), detail: model.uiText("Subtitles", "Phụ đề"))
                    MiniCapability(icon: "arrow.down.circle.fill", title: "Offline", detail: model.uiText("Playback", "Phát lại"))
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 18)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .photosPicker(
            isPresented: $model.importerPresented,
            selection: $pickedVideo,
            matching: .videos,
            preferredItemEncoding: .automatic
        )
        .onChange(of: pickedVideo) { _, item in
            guard let item else { return }
            Task {
                guard let picked = try? await item.loadTransferable(type: PickedVideoFile.self) else { return }
                defer { try? FileManager.default.removeItem(at: picked.url) }
                await model.importMedia(from: picked.url)
                pickedVideo = nil
            }
        }
    }
}

private struct SavedVideoRow: View {
    let item: LocalLibraryItem
    let shareURL: URL?
    let onDelete: (() -> Void)?
    let action: () -> Void

    init(
        item: LocalLibraryItem,
        shareURL: URL? = nil,
        onDelete: (() -> Void)? = nil,
        action: @escaping () -> Void
    ) {
        self.item = item
        self.shareURL = shareURL
        self.onDelete = onDelete
        self.action = action
    }

    var body: some View {
        HStack(spacing: 12) {
            Button(action: action) {
                HStack(spacing: 14) {
                    VideoPlaceholder(width: 92, height: 62)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(item.title)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                        Text("\(item.durationText)  •  \(item.languagePair)")
                            .font(.caption)
                            .foregroundStyle(LPTheme.secondaryText)
                        Text("Saved locally")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(LPTheme.cyan)
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption.bold())
                        .foregroundStyle(LPTheme.secondaryText)
                }
            }
            .buttonStyle(.plain)

            if let shareURL {
                ShareLink(item: shareURL) {
                    Image(systemName: "square.and.arrow.up")
                        .foregroundStyle(LPTheme.cyan)
                }
                .buttonStyle(.plain)
            }

            if let onDelete {
                Button(role: .destructive, action: onDelete) {
                    Image(systemName: "trash")
                }
                .buttonStyle(.plain)
            }
        }
        .lpCard()
    }
}

private struct MiniCapability: View {
    let icon: String
    let title: String
    let detail: String

    var body: some View {
        VStack(spacing: 7) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(LPTheme.accent)
            Text(title)
                .font(.caption.weight(.semibold))
            Text(detail)
                .font(.caption2)
                .foregroundStyle(LPTheme.secondaryText)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
        .background(LPTheme.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

struct PrepareView: View {
    @Bindable var model: AppModel
    @State private var pickedVideo: PhotosPickerItem?

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                ScreenHeader(title: model.uiText("Prepare", "Chuẩn bị"), backAction: model.cancelPreparation)

                VStack(spacing: 12) {
                    VideoPlaceholder(width: nil, height: 190)
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(model.selectedMedia?.title ?? "Choose a local video")
                                .font(.subheadline.weight(.semibold))
                                .lineLimit(1)
                            Text(mediaSummary)
                                .font(.caption)
                                .foregroundStyle(LPTheme.secondaryText)
                        }
                        Spacer()
                        Button(model.uiText("Edit", "Đổi video")) {
                            model.importerPresented = true
                        }
                        .font(.caption.weight(.semibold))
                        .buttonStyle(.plain)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(LPTheme.surfaceStrong, in: Capsule())
                    }
                }
                .lpCard()

                VStack(spacing: 0) {
                    PrepareSetting(icon: "waveform.badge.mic", title: "From language", value: model.sourceLanguageChoice.label, detail: "Whisper language override", action: model.cycleSourceLanguage)
                    Divider().overlay(LPTheme.border)
                    PrepareSetting(icon: "character.bubble.fill", title: "To language", value: model.targetLanguageChoice.label, detail: "Translation + offline system voice", action: model.cycleTargetLanguage)
                    Divider().overlay(LPTheme.border)
                    PrepareSetting(icon: "person.wave.2.fill", title: "AI Voice", value: model.preferredVoiceLabel, detail: "Installed system voice", action: model.cycleVoice)
                    Divider().overlay(LPTheme.border)
                    PrepareSetting(icon: "slider.horizontal.3", title: "Dubbing mode", value: model.dubbingMode.label, detail: model.dubbingMode.detail, action: model.cycleDubbingMode)
                    Divider().overlay(LPTheme.border)
                    PrepareSetting(
                        icon: "waveform.path.ecg",
                        title: "Clean Background",
                        value: CleanBackgroundCapability.isAvailable ? "Ready" : "Unavailable",
                        detail: CleanBackgroundCapability.isAvailable ? "Verified source-separation engine ready" : "No verified source-separation engine is bundled yet"
                    )
                    Divider().overlay(LPTheme.border)
                    PrepareSetting(icon: "captions.bubble.fill", title: "Subtitles", value: model.subtitleMode.label, detail: "Player subtitle display mode", action: model.cycleSubtitleMode)
                }
                .lpCard()

                LPPrimaryButton(title: model.uiText("Translate & Dub", "Dịch & Lồng tiếng"), systemImage: "sparkles") {
                    model.beginProcessing()
                }

                Text(model.uiText("Estimated time depends on device performance and video duration.", "Thời gian xử lý phụ thuộc hiệu năng thiết bị và độ dài video."))
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                    .multilineTextAlignment(.center)
            }
            .padding(18)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .photosPicker(
            isPresented: $model.importerPresented,
            selection: $pickedVideo,
            matching: .videos,
            preferredItemEncoding: .automatic
        )
        .onChange(of: pickedVideo) { _, item in
            guard let item else { return }
            Task {
                guard let picked = try? await item.loadTransferable(type: PickedVideoFile.self) else { return }
                defer { try? FileManager.default.removeItem(at: picked.url) }
                await model.importMedia(from: picked.url)
                pickedVideo = nil
            }
        }
    }

    private var mediaSummary: String {
        guard let media = model.selectedMedia else { return "Local file required" }
        let audio = media.hasAudioTrack ? "Audio detected" : "No audio track"
        return "\(media.durationText)  •  \(media.fileSizeText)  •  \(audio)"
    }
}

private struct PrepareSetting: View {
    let icon: String
    let title: String
    let value: String
    let detail: String
    var action: (() -> Void)? = nil

    var body: some View {
        Button {
            action?()
        } label: {
        HStack(spacing: 13) {
            Image(systemName: icon)
                .frame(width: 28)
                .foregroundStyle(LPTheme.accent)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                Text(value)
                    .font(.subheadline.weight(.semibold))
                Text(detail)
                    .font(.caption2)
                    .foregroundStyle(LPTheme.secondaryText)
            }
            Spacer()
            if action != nil {
                Image(systemName: "chevron.right")
                    .font(.caption.bold())
                    .foregroundStyle(LPTheme.secondaryText)
            }
        }
        .padding(.vertical, 11)
        }
        .buttonStyle(.plain)
        .disabled(action == nil)
    }
}

struct ProcessingView: View {
    @Bindable var model: AppModel

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                ScreenHeader(title: "AI Processing", backAction: model.returnHome)

                LPBrandMark()
                    .scaleEffect(1.12)
                    .padding(.top, 10)

                VStack(spacing: 8) {
                    Text(processingTitle)
                        .font(.title3.bold())
                    Text("\(Int(model.processingProgress * 100))%")
                        .font(.system(size: 42, weight: .bold, design: .rounded))
                        .foregroundStyle(LPTheme.accent)
                        .monospacedDigit()
                    ProgressView(value: model.processingProgress)
                        .tint(LPTheme.cyan)
                        .scaleEffect(x: 1, y: 1.6)
                }

                VStack(spacing: 0) {
                    ProcessingStageRow(title: "Preparing audio", state: audioStageState)
                    Divider().overlay(LPTheme.border)
                    ProcessingStageRow(title: "Understanding speech", state: speechStageState)
                    Divider().overlay(LPTheme.border)
                    ProcessingStageRow(title: "Translating", state: translationStageState)
                    Divider().overlay(LPTheme.border)
                    ProcessingStageRow(title: "Creating offline voice", state: ttsStageState)
                    Divider().overlay(LPTheme.border)
                    ProcessingStageRow(title: "Mixing audio", state: mixingStageState)
                }
                .lpCard()

                if case .completed(let transcript) = model.asrState {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Label("Speech recognized", systemImage: "waveform.badge.mic")
                                .font(.headline)
                                .foregroundStyle(LPTheme.accent)
                            Spacer()
                            Text(transcript.language.uppercased())
                                .font(.caption2.bold())
                                .foregroundStyle(LPTheme.cyan)
                        }
                        Text(transcript.text)
                            .font(.subheadline)
                            .lineLimit(5)
                        Text("\(transcript.segments.count) timestamped segments · local only")
                            .font(.caption2)
                            .foregroundStyle(LPTheme.secondaryText)
                    }
                    .lpCard()
                }

                if case .completed(let translation) = model.translationState {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Label("Translation", systemImage: "character.bubble.fill")
                                .font(.headline)
                                .foregroundStyle(LPTheme.accent)
                            Spacer()
                            Text("\(translation.segments.count) segments")
                                .font(.caption2.bold())
                                .foregroundStyle(LPTheme.cyan)
                        }
                        Text(translation.translatedText)
                            .font(.subheadline)
                            .lineLimit(5)
                        Text("Only transcript JSON was sent · source media stayed on-device")
                            .font(.caption2)
                            .foregroundStyle(LPTheme.secondaryText)
                    }
                    .lpCard()
                }

                Label("If iOS suspends or terminates LingoPlay, the app keeps a local recovery checkpoint and offers Resume on Home. PiP is for playback; processing is not falsely promised as unlimited background execution.", systemImage: "arrow.clockwise.icloud.fill")
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                    .lpCard()

                if case .modelMissing = model.asrState {
                    SpeechModelManagementCard(model: model)
                }

                if case .endpointMissing = model.translationState {
                    Text("Translation backend is not configured. The recognized transcript remains local and no network request was made.")
                        .font(.caption)
                        .foregroundStyle(LPTheme.secondaryText)
                        .lpCard()
                }

                if case .completed(let dub) = model.ttsState {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Label("Offline voice ready", systemImage: "waveform.circle.fill")
                                .font(.headline)
                                .foregroundStyle(LPTheme.accent)
                            Spacer()
                            Text("\(dub.segments.count) clips")
                                .font(.caption2.bold())
                                .foregroundStyle(LPTheme.cyan)
                        }
                        Text("System voice · \(dub.voiceIdentifier)")
                            .font(.caption)
                        Text("\(dub.totalTailSilenceMs) ms timeline silence reserved · no spoken words truncated")
                            .font(.caption2)
                            .foregroundStyle(LPTheme.secondaryText)
                    }
                    .lpCard()
                }

                if case .voiceMissing = model.ttsState {
                    Text("No system voice is installed for the selected target language. Install a matching voice in iOS speech settings before local dubbing.")
                        .font(.caption)
                        .foregroundStyle(LPTheme.secondaryText)
                        .lpCard()
                }

                if case .completed = model.mixState {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Label("Dubbed video ready", systemImage: "checkmark.circle.fill")
                                .font(.headline)
                                .foregroundStyle(LPTheme.accent)
                            Spacer()
                            Text("LOCAL")
                                .font(.caption2.bold())
                                .foregroundStyle(LPTheme.cyan)
                        }
                        Text("Video was remuxed without video transcoding.")
                            .font(.caption)
                        Text("Original audio and generated speech remain in one local playback graph for live blend control.")
                            .font(.caption2)
                            .foregroundStyle(LPTheme.secondaryText)
                    }
                    .lpCard()
                }

                if case .failed(let message) = model.mediaState {
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .lpCard()
                }

                if case .failed(let message) = model.asrState {
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .lpCard()
                }

                if case .failed(let message) = model.translationState {
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .lpCard()
                }

                if case .failed(let message) = model.ttsState {
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .lpCard()
                }

                if case .failed(let message) = model.mixState {
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .lpCard()
                }
            }
            .padding(18)
            .padding(.bottom, 28)
        }
        .scrollIndicators(.hidden)
    }

    private var processingTitle: String {
        switch model.mediaState {
        case .extractingAudio:
            return "Preparing local audio"
        case .audioReady:
            switch model.asrState {
            case .modelMissing: return "Audio ready · speech model not installed"
            case .loadingModel: return "Loading local speech model"
            case .transcribing: return "Understanding speech on-device"
            case .completed:
                switch model.translationState {
                case .translating: return "Translating transcript"
                case .completed:
                    switch model.ttsState {
                    case .synthesizing: return "Creating offline voice on-device"
                    case .completed:
                        switch model.mixState {
                        case .renderingAudio: return "Building local dub timeline"
                        case .remuxing: return "Remuxing dubbed video"
                        case .completed: return "Dubbed video ready"
                        case .failed: return "Local mix or remux stopped"
                        case .idle: return "Offline voice ready"
                        }
                    case .voiceMissing: return "Translation ready · offline voice not installed"
                    case .failed: return "Offline voice synthesis stopped"
                    case .idle: return "Translation ready"
                    }
                case .endpointMissing: return "Speech ready · translation not configured"
                case .failed: return "Translation stopped"
                case .idle: return "Speech recognized locally"
                }
            case .failed: return "Speech recognition stopped"
            case .idle: return "Audio ready"
            }
        case .failed:
            return "Audio preparation stopped"
        default:
            return "Preparing local media"
        }
    }

    private var audioStageState: ProcessingStageRow.State {
        switch model.mediaState {
        case .audioReady: .complete
        case .extractingAudio: .active
        case .failed: .failed
        default: .pending
        }
    }

    private var speechStageState: ProcessingStageRow.State {
        switch model.asrState {
        case .modelMissing: .blocked
        case .loadingModel, .transcribing: .active
        case .completed: .complete
        case .failed: .failed
        case .idle: .pending
        }
    }

    private var translationStageState: ProcessingStageRow.State {
        switch model.translationState {
        case .endpointMissing: .configurationMissing
        case .translating: .active
        case .completed: .complete
        case .failed: .failed
        case .idle: .pending
        }
    }

    private var ttsStageState: ProcessingStageRow.State {
        switch model.ttsState {
        case .voiceMissing: .voiceMissing
        case .synthesizing: .active
        case .completed: .complete
        case .failed: .failed
        case .idle: .pending
        }
    }

    private var mixingStageState: ProcessingStageRow.State {
        switch model.mixState {
        case .renderingAudio, .remuxing: .active
        case .completed: .complete
        case .failed: .failed
        case .idle: .pending
        }
    }
}

private struct ProcessingStageRow: View {
    enum State {
        case complete
        case active
        case pending
        case blocked
        case configurationMissing
        case voiceMissing
        case failed
    }

    let title: String
    let state: State

    var body: some View {
        HStack(spacing: 13) {
            Group {
                switch state {
                case .complete:
                    Image(systemName: "checkmark.circle.fill")
                case .active:
                    ProgressView()
                        .tint(LPTheme.cyan)
                case .pending:
                    Image(systemName: "circle")
                case .blocked, .configurationMissing, .voiceMissing:
                    Image(systemName: "lock.circle")
                case .failed:
                    Image(systemName: "exclamationmark.circle.fill")
                }
            }
            .frame(width: 24)
            .foregroundStyle((state == .pending || state == .blocked || state == .configurationMissing || state == .voiceMissing) ? LPTheme.secondaryText : (state == .failed ? .red : LPTheme.cyan))

            Text(title)
                .font(.subheadline.weight(.medium))
            Spacer()
            Text(status)
                .font(.caption)
                .foregroundStyle(LPTheme.secondaryText)
        }
        .padding(.vertical, 13)
    }

    private var status: String {
        switch state {
        case .complete: "Completed"
        case .active: "In progress"
        case .pending: "Pending"
        case .blocked: "ASR not installed"
        case .configurationMissing: "Not configured"
        case .voiceMissing: "Voice not installed"
        case .failed: "Failed"
        }
    }
}

struct PlayerView: View {
    @Bindable var model: AppModel

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                ScreenHeader(title: model.selectedMedia?.title ?? "Preview", backAction: model.returnHome) {
                    Text(model.videoPlayer == nil ? "Preview" : (model.liveBlendAvailable ? "Vietnamese AI" : "Saved Dub"))
                        .font(.caption2.weight(.bold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(LPTheme.violet.opacity(0.35), in: Capsule())
                }

                if let player = model.videoPlayer {
                    LocalVideoSurface(player: player)
                        .frame(height: 250)
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .background(.black, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                } else {
                    VideoPlaceholder(width: nil, height: 250)
                    VStack(alignment: .leading, spacing: 8) {
                        Text("No dubbed video selected")
                            .font(.headline)
                        Text("Import a local video or choose a saved result from Library.")
                            .font(.caption)
                            .foregroundStyle(LPTheme.secondaryText)
                    }
                    .lpCard()
                }

                if model.videoPlayer != nil {
                    VStack(spacing: 8) {
                        Slider(value: playbackFraction)
                            .tint(LPTheme.cyan)
                        HStack {
                            Text(formatPlaybackTime(model.playbackPosition))
                            Spacer()
                            Text(formatPlaybackTime(model.playbackDuration))
                        }
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(LPTheme.secondaryText)
                    }

                    switch model.subtitleMode {
                    case .off:
                        EmptyView()
                    case .translated:
                        VStack(alignment: .leading, spacing: 12) {
                            SubtitleLine(language: model.targetLanguageChoice.code.uppercased(), text: model.activeTranslationSegment?.translatedText ?? "—")
                        }
                        .lpCard()
                    case .bilingual:
                        VStack(alignment: .leading, spacing: 12) {
                            SubtitleLine(language: model.sourceLanguageChoice.code?.uppercased() ?? "SRC", text: model.activeTranslationSegment?.sourceText ?? "—")
                            Divider().overlay(LPTheme.border)
                            SubtitleLine(language: model.targetLanguageChoice.code.uppercased(), text: model.activeTranslationSegment?.translatedText ?? "—")
                        }
                        .lpCard()
                    }
                }

                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("Audio blend")
                            .font(.headline)
                        Spacer()
                        Text(model.liveBlendAvailable ? "\(Int(model.audioBlend * 100))% Dub" : "Final mix")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(LPTheme.cyan)
                    }
                    Slider(value: $model.audioBlend, in: 0...1)
                        .tint(LPTheme.cyan)
                        .disabled(!model.liveBlendAvailable)
                    HStack {
                        Text("Original")
                        Spacer()
                        Text("Dub")
                    }
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                }
                .lpCard()

                HStack(spacing: 8) {
                    Button(action: model.cycleSubtitleMode) {
                        PlayerAction(icon: "captions.bubble.fill", label: model.subtitleMode.label)
                    }
                    .buttonStyle(.plain)
                    Button(action: model.cyclePlaybackSpeed) {
                        PlayerAction(icon: "speedometer", label: String(format: "%.2gx", model.playbackSpeed))
                    }
                    .buttonStyle(.plain)
                    if let url = model.activeLibraryURL {
                        ShareLink(item: url) {
                            PlayerAction(icon: "square.and.arrow.up", label: "Share")
                        }
                        .buttonStyle(.plain)
                    }
                }

                if let item = model.activeLibraryItem {
                    Button(role: .destructive) {
                        Task { await model.deleteLibraryItem(item) }
                    } label: {
                        Label("Delete from LingoPlay", systemImage: "trash")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
            }
            .padding(18)
            .padding(.bottom, 28)
        }
        .scrollIndicators(.hidden)
        .onDisappear { model.pausePlayback() }
    }

    private var playbackFraction: Binding<Double> {
        Binding(
            get: {
                guard model.playbackDuration > 0 else { return 0 }
                return min(max(model.playbackPosition / model.playbackDuration, 0), 1)
            },
            set: { model.seek(to: $0) }
        )
    }

    private func formatPlaybackTime(_ seconds: Double) -> String {
        let total = Int(max(0, seconds).rounded())
        let hours = total / 3_600
        let minutes = (total % 3_600) / 60
        let remainder = total % 60
        return hours > 0
            ? String(format: "%02d:%02d:%02d", hours, minutes, remainder)
            : String(format: "%02d:%02d", minutes, remainder)
    }
}

private struct LocalVideoSurface: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.videoGravity = .resizeAspect
        controller.showsPlaybackControls = true
        controller.allowsPictureInPicturePlayback = true
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        controller.player = player
    }
}

private struct SubtitleLine: View {
    let language: String
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(language)
                .font(.caption2.bold())
                .foregroundStyle(LPTheme.cyan)
                .frame(width: 24)
            Text(text)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
    }
}

private struct PlayerAction: View {
    let icon: String
    let label: String

    var body: some View {
        VStack(spacing: 7) {
            Image(systemName: icon)
                .font(.body.weight(.semibold))
                .foregroundStyle(LPTheme.accent)
            Text(label)
                .font(.caption2.weight(.medium))
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(LPTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

struct LibraryView: View {
    @Bindable var model: AppModel

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(model.uiText("Library", "Thư viện"))
                            .font(.largeTitle.bold())
                        Text(model.uiText("Saved dubs · always available offline", "Video đã lưu · luôn xem được khi offline"))
                            .font(.caption)
                            .foregroundStyle(LPTheme.secondaryText)
                    }
                    Spacer()
                    Image(systemName: "rectangle.stack.fill")
                        .font(.title3)
                        .foregroundStyle(LPTheme.cyan)
                }

                if model.libraryItems.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(model.uiText("Nothing saved yet", "Chưa có video đã lưu"))
                            .font(.headline)
                        Text(model.uiText("Completed dubs appear here automatically after local processing finishes.", "Video lồng tiếng hoàn tất sẽ tự động xuất hiện ở đây sau khi xử lý cục bộ."))
                            .font(.caption)
                            .foregroundStyle(LPTheme.secondaryText)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .lpCard()
                } else {
                    ForEach(model.libraryItems) { item in
                        SavedVideoRow(
                            item: item,
                            shareURL: model.libraryURLs[item.id],
                            onDelete: { Task { await model.deleteLibraryItem(item) } }
                        ) {
                            Task { await model.openLibraryItem(item) }
                        }
                    }
                }

                VStack(alignment: .leading, spacing: 11) {
                    LPSectionHeader(title: model.uiText("Saved media", "Media đã lưu"), trailing: MediaFormatting.bytes(model.libraryBytes))
                    Text(model.uiText("\(model.libraryItems.count) local dubbed video\(model.libraryItems.count == 1 ? "" : "s") · stored only in LingoPlay app storage", "\(model.libraryItems.count) video lồng tiếng cục bộ · chỉ lưu trong bộ nhớ ứng dụng LingoPlay"))
                        .font(.caption)
                        .foregroundStyle(LPTheme.secondaryText)
                }
                .lpCard()
            }
            .padding(18)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
    }
}

struct SettingsView: View {
    @Bindable var model: AppModel

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(model.uiText("Settings", "Cài đặt"))
                            .font(.largeTitle.bold())
                        Text(model.uiText("Playback, appearance and privacy preferences", "Tùy chọn phát lại, giao diện và riêng tư"))
                            .font(.caption)
                            .foregroundStyle(LPTheme.secondaryText)
                    }
                    Spacer()
                    LPBrandMark(compact: true)
                }

                VStack(spacing: 0) {
                    Button(action: model.toggleAppearance) {
                        SettingsValueRow(
                            icon: "circle.lefthalf.filled",
                            title: model.uiText("Appearance", "Giao diện"),
                            value: model.highContrast ? model.uiText("High Contrast", "Tương phản cao") : "Midnight"
                        )
                    }
                    .buttonStyle(.plain)
                    Divider().overlay(LPTheme.border)
                    Button(action: model.toggleLanguage) {
                        SettingsValueRow(
                            icon: "globe",
                            title: model.uiText("App Language", "Ngôn ngữ ứng dụng"),
                            value: model.uiLanguageLabel
                        )
                    }
                    .buttonStyle(.plain)
                    Divider().overlay(LPTheme.border)
                    Button(action: model.cycleVoice) {
                        SettingsValueRow(icon: "person.wave.2.fill", title: model.uiText("AI Voice", "Giọng AI"), value: model.preferredVoiceLabel)
                    }
                    .buttonStyle(.plain)
                    Divider().overlay(LPTheme.border)
                    Button(action: model.cycleTargetLanguage) {
                        SettingsValueRow(icon: "character.bubble.fill", title: model.uiText("Dubbing Language", "Ngôn ngữ lồng tiếng"), value: model.targetLanguageChoice.label)
                    }
                    .buttonStyle(.plain)
                    Divider().overlay(LPTheme.border)
                    Toggle(isOn: $model.wifiOnly) {
                        Label(model.uiText("Download models on Wi-Fi only", "Chỉ tải model bằng Wi-Fi"), systemImage: "wifi")
                    }
                    .padding(.vertical, 14)
                    Divider().overlay(LPTheme.border)
                    Button(action: model.cycleSubtitleMode) {
                        SettingsValueRow(icon: "captions.bubble.fill", title: model.uiText("Subtitles", "Phụ đề"), value: model.subtitleMode.label)
                    }
                    .buttonStyle(.plain)
                }
                .lpCard()

                VStack(alignment: .leading, spacing: 12) {
                    Label(model.uiText("Private by architecture", "Riêng tư ngay từ kiến trúc"), systemImage: "lock.shield.fill")
                        .font(.headline)
                        .foregroundStyle(LPTheme.accent)
                    Text(model.uiText("Video and audio never go to the LingoPlay backend. Only transcript text required for translation is sent as compact JSON when online translation is enabled.", "Video và audio không bao giờ đi tới backend LingoPlay. Chỉ văn bản transcript cần cho dịch thuật được gửi dưới dạng JSON gọn khi bật dịch online."))
                        .font(.subheadline)
                        .foregroundStyle(LPTheme.secondaryText)
                }
                .lpCard()

                SpeechModelManagementCard(model: model)

                VStack(spacing: 0) {
                    Button {
                        model.plusPresented = true
                    } label: {
                        SettingsValueRow(
                            icon: "crown.fill",
                            title: "LingoPlay Plus",
                            value: model.plusStore.isPlus ? model.uiText("Active", "Đang hoạt động") : model.uiText("Explore", "Xem gói")
                        )
                    }
                    .buttonStyle(.plain)
                    Divider().overlay(LPTheme.border)
                    Button {
                        model.aboutPresented = true
                    } label: {
                        SettingsValueRow(
                            icon: "info.circle.fill",
                            title: model.uiText("About LingoPlay", "Giới thiệu LingoPlay"),
                            value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
                        )
                    }
                    .buttonStyle(.plain)
                }
                .lpCard()
            }
            .padding(18)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
    }
}

struct PlusView: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    VStack(spacing: 10) {
                        Image(systemName: "crown.fill")
                            .font(.system(size: 38, weight: .bold))
                            .foregroundStyle(LPTheme.accent)
                        Text("LingoPlay Plus")
                            .font(.largeTitle.bold())
                        Text(model.uiText("StoreKit 2 is pre-wired for local testing. Current pre-release capabilities remain available while billing is being validated.", "StoreKit 2 đã được đấu sẵn để test cục bộ. Các tính năng pre-release hiện tại vẫn dùng được trong lúc kiểm thử thanh toán."))
                            .font(.subheadline)
                            .foregroundStyle(LPTheme.secondaryText)
                            .multilineTextAlignment(.center)
                    }

                    if model.plusStore.isPlus {
                        Label(model.uiText("Plus active on this StoreKit account", "Plus đang hoạt động trên tài khoản StoreKit này"), systemImage: "checkmark.seal.fill")
                            .font(.headline)
                            .foregroundStyle(LPTheme.cyan)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .lpCard()
                    }

                    VStack(alignment: .leading, spacing: 10) {
                        Label(model.uiText("Planned Plus capabilities", "Tính năng Plus dự kiến"), systemImage: "sparkles")
                            .font(.headline)
                            .foregroundStyle(LPTheme.accent)
                        Text(model.uiText("Installed system-voice selection and PiP are live. Clean Background/source separation remains disabled until a verified native engine is integrated.", "Chọn giọng hệ thống đã cài và PiP đã hoạt động. Clean Background/tách nguồn vẫn tắt cho đến khi có engine native được xác minh."))
                            .font(.subheadline)
                            .foregroundStyle(LPTheme.secondaryText)
                    }
                    .lpCard()

                    if model.plusStore.products.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(model.uiText("Products unavailable", "Chưa có sản phẩm"))
                                .font(.headline)
                            Text(model.plusStore.statusMessage ?? model.uiText("Run the app from Xcode with Products.storekit selected in the Run scheme to test purchases locally without App Store Connect.", "Chạy app từ Xcode với Products.storekit được chọn trong Run scheme để test mua hàng cục bộ mà không cần App Store Connect."))
                                .font(.caption)
                                .foregroundStyle(LPTheme.secondaryText)
                            Button(model.uiText("Reload products", "Tải lại sản phẩm")) {
                                Task { await model.plusStore.refresh() }
                            }
                            .buttonStyle(.bordered)
                        }
                        .lpCard()
                    } else {
                        ForEach(model.plusStore.products, id: \.id) { product in
                            Button {
                                Task { await model.plusStore.purchase(product) }
                            } label: {
                                HStack(spacing: 14) {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(product.displayName)
                                            .font(.headline)
                                        Text(product.description)
                                            .font(.caption)
                                            .foregroundStyle(LPTheme.secondaryText)
                                    }
                                    Spacer()
                                    Text(product.displayPrice)
                                        .font(.headline)
                                        .foregroundStyle(LPTheme.cyan)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(model.plusStore.purchaseState == .purchasing || model.plusStore.purchaseState == .restoring)
                            .lpCard()
                        }
                    }

                    if let message = model.plusStore.statusMessage {
                        Text(message)
                            .font(.caption)
                            .foregroundStyle(LPTheme.secondaryText)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    Button(model.uiText("Restore Purchases", "Khôi phục giao dịch")) {
                        Task { await model.plusStore.restore() }
                    }
                    .buttonStyle(.bordered)
                    .disabled(model.plusStore.purchaseState == .purchasing || model.plusStore.purchaseState == .restoring)

                    Text(model.uiText("Development note: these local StoreKit products are not synced to App Store Connect. Use the same product IDs later when an Apple Developer account is available.", "Ghi chú phát triển: các sản phẩm StoreKit cục bộ này chưa đồng bộ App Store Connect. Sau này khi có Apple Developer account, tạo sản phẩm với đúng các Product ID này."))
                        .font(.caption2)
                        .foregroundStyle(LPTheme.secondaryText)
                        .multilineTextAlignment(.center)
                }
                .padding(18)
            }
            .navigationTitle("Plus")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(model.uiText("Done", "Xong")) { dismiss() }
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}

struct AboutView: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var diagnosticsCount = 0

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    LPBrandMark()
                    Text("LingoPlay")
                        .font(.largeTitle.bold())
                    Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—")
                        .font(.caption.monospaced())
                        .foregroundStyle(LPTheme.secondaryText)

                    VStack(alignment: .leading, spacing: 12) {
                        Label(model.uiText("Private by architecture", "Riêng tư ngay từ kiến trúc"), systemImage: "lock.shield.fill")
                            .font(.headline)
                            .foregroundStyle(LPTheme.accent)
                        Text(model.uiText("Video/audio stay on-device. Only transcript JSON is eligible for translation requests.", "Video/audio luôn ở trên thiết bị. Chỉ transcript JSON có thể được gửi để dịch."))
                            .font(.subheadline)
                            .foregroundStyle(LPTheme.secondaryText)
                        Divider().overlay(LPTheme.border)
                        Text("Clean Background: \(CleanBackgroundCapability.isAvailable ? "Ready" : "Unavailable")")
                            .font(.caption)
                        Text(model.uiText("Local diagnostic events: \(diagnosticsCount)", "Sự kiện chẩn đoán cục bộ: \(diagnosticsCount)"))
                            .font(.caption)
                    }
                    .lpCard()
                }
                .padding(18)
            }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(model.uiText("Done", "Xong")) { dismiss() }
                }
            }
        }
        .task { diagnosticsCount = await model.localDiagnosticsCount() }
    }
}

private struct SpeechModelManagementCard: View {
    @Bindable var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "shippingbox.fill")
                    .foregroundStyle(LPTheme.accent)
                VStack(alignment: .leading, spacing: 3) {
                    Text(model.uiText("Speech AI Model", "Model AI nhận dạng giọng nói"))
                        .font(.subheadline.weight(.semibold))
                    Text(statusText)
                        .font(.caption)
                        .foregroundStyle(LPTheme.secondaryText)
                }
                Spacer()
            }

            switch model.modelInstallState {
            case .notInstalled:
                Text(model.uiText("Install Whisper Tiny once for fully local speech recognition. Your video is never part of this download.", "Cài Whisper Tiny một lần để nhận dạng giọng nói hoàn toàn cục bộ. Video của bạn không liên quan tới lượt tải này."))
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                LPPrimaryButton(title: model.uiText("Install Speech AI", "Cài Speech AI"), systemImage: "arrow.down.circle.fill") {
                    model.installSpeechModel()
                }
            case .downloading(let progress):
                ProgressView(value: progress)
                    .tint(LPTheme.cyan)
                Text("\(Int(progress * 100))%")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(LPTheme.secondaryText)
                Button(model.uiText("Cancel download", "Hủy tải")) {
                    model.cancelSpeechModelInstall()
                }
                .buttonStyle(.bordered)
            case .installed:
                Text(model.uiText("Activated for offline inference. Future transcription loads only the installed model and tokenizer cache.", "Đã kích hoạt cho inference offline. Các lần nhận dạng sau chỉ nạp model và tokenizer đã cài."))
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                Button(role: .destructive) {
                    model.deleteSpeechModel()
                } label: {
                    Label(model.uiText("Delete model", "Xóa model"), systemImage: "trash")
                }
                .buttonStyle(.bordered)
                .disabled(!model.canDeleteSpeechModel)
            case .failed(let message):
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.red)
                LPPrimaryButton(title: model.uiText("Retry install", "Thử cài lại"), systemImage: "arrow.clockwise") {
                    model.installSpeechModel()
                }
            }
        }
        .lpCard()
    }

    private var statusText: String {
        switch model.modelInstallState {
        case .notInstalled:
            model.uiText("Not installed", "Chưa cài")
        case .downloading:
            model.uiText("Downloading Whisper Tiny…", "Đang tải Whisper Tiny…")
        case .installed(let bytes):
            "Whisper Tiny · \(MediaFormatting.bytes(bytes)) · offline"
        case .failed:
            model.uiText("Install failed", "Cài đặt thất bại")
        }
    }
}

private struct SettingsValueRow: View {
    let icon: String
    let title: String
    let value: String

    var body: some View {
        HStack(spacing: 13) {
            Image(systemName: icon)
                .frame(width: 24)
                .foregroundStyle(LPTheme.accent)
            Text(title)
                .font(.subheadline)
            Spacer()
            Text(value)
                .font(.caption)
                .foregroundStyle(LPTheme.secondaryText)
            Image(systemName: "chevron.right")
                .font(.caption2.bold())
                .foregroundStyle(LPTheme.secondaryText)
        }
        .padding(.vertical, 14)
    }
}

private struct ScreenHeader<Trailing: View>: View {
    let title: String
    let backAction: () -> Void
    @ViewBuilder let trailing: () -> Trailing

    init(title: String, backAction: @escaping () -> Void, @ViewBuilder trailing: @escaping () -> Trailing) {
        self.title = title
        self.backAction = backAction
        self.trailing = trailing
    }

    var body: some View {
        HStack {
            Button(action: backAction) {
                Image(systemName: "chevron.left")
                    .font(.headline.bold())
                    .frame(width: 38, height: 38)
                    .background(LPTheme.surface, in: Circle())
            }
            .buttonStyle(.plain)
            Spacer()
            Text(title)
                .font(.headline)
                .lineLimit(1)
            Spacer()
            trailing()
                .frame(minWidth: 38, alignment: .trailing)
        }
    }
}

private extension ScreenHeader where Trailing == EmptyView {
    init(title: String, backAction: @escaping () -> Void) {
        self.init(title: title, backAction: backAction) {
            EmptyView()
        }
    }
}

private struct VideoPlaceholder: View {
    let width: CGFloat?
    let height: CGFloat

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [Color(red: 0.05, green: 0.12, blue: 0.22), Color(red: 0.18, green: 0.08, blue: 0.28), Color(red: 0.04, green: 0.24, blue: 0.30)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            Circle()
                .fill(.ultraThinMaterial)
                .frame(width: min(height * 0.34, 62), height: min(height * 0.34, 62))
            Image(systemName: "play.fill")
                .font(.system(size: min(height * 0.13, 24), weight: .bold))
                .foregroundStyle(.white)
                .offset(x: 2)
            VStack {
                Spacer()
                HStack {
                    Label("LOCAL", systemImage: "lock.fill")
                        .font(.system(size: 8, weight: .bold))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 5)
                        .background(.black.opacity(0.35), in: Capsule())
                    Spacer()
                }
                .padding(10)
            }
        }
        .frame(maxWidth: width == nil ? .infinity : nil)
        .frame(width: width, height: height)
        .clipped()
    }
}
