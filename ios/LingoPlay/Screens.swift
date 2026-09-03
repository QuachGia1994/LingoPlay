import SwiftUI
import UniformTypeIdentifiers

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

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("LingoPlay")
                            .font(.title2.bold())
                            .foregroundStyle(LPTheme.accent)
                        Text("Understand without borders")
                            .font(.caption)
                            .foregroundStyle(LPTheme.secondaryText)
                    }
                    Spacer()
                    Image(systemName: "crown.fill")
                        .foregroundStyle(LPTheme.accent)
                        .padding(10)
                        .background(LPTheme.surface, in: Circle())
                }

                VStack(alignment: .leading, spacing: 18) {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("AI video translation\n& dubbing")
                                .font(.system(size: 31, weight: .bold, design: .rounded))
                            Text("Import a video you already have. The media stays on this device.")
                                .font(.subheadline)
                                .foregroundStyle(LPTheme.secondaryText)
                        }
                        Spacer(minLength: 12)
                        LPBrandMark()
                    }
                    LPPrimaryButton(title: "Import Video", systemImage: "plus") {
                        model.beginImport()
                    }
                    Label("Video and audio are never uploaded", systemImage: "lock.shield.fill")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(LPTheme.secondaryText)
                }
                .lpCard()

                LPSectionHeader(title: "Recent", trailing: "Local library")

                VStack(spacing: 12) {
                    ForEach(model.recentVideos) { video in
                        RecentVideoRow(video: video) {
                            model.previewResult()
                        }
                    }
                }

                HStack(spacing: 12) {
                    MiniCapability(icon: "waveform", title: "On-device", detail: "Speech AI")
                    MiniCapability(icon: "captions.bubble.fill", title: "Bilingual", detail: "Subtitles")
                    MiniCapability(icon: "arrow.down.circle.fill", title: "Offline", detail: "Playback")
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 18)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .fileImporter(isPresented: $model.importerPresented, allowedContentTypes: [.movie, .mpeg4Movie, .quickTimeMovie]) { result in
            guard case .success(let url) = result else { return }
            Task { await model.importMedia(from: url) }
        }
    }
}

private struct RecentVideoRow: View {
    let video: AppModel.RecentVideo
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                VideoPlaceholder(width: 92, height: 62)
                VStack(alignment: .leading, spacing: 6) {
                    Text(video.title)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Text("\(video.duration)  •  \(video.languagePair)")
                        .font(.caption)
                        .foregroundStyle(LPTheme.secondaryText)
                    if let progress = video.progress {
                        ProgressView(value: progress)
                            .tint(LPTheme.cyan)
                    } else {
                        Text("Completed")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(LPTheme.cyan)
                    }
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption.bold())
                    .foregroundStyle(LPTheme.secondaryText)
            }
            .lpCard()
        }
        .buttonStyle(.plain)
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

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                ScreenHeader(title: "Prepare", backAction: model.returnHome)

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
                        Button("Edit") {
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
                    PrepareSetting(icon: "waveform.badge.mic", title: "From language", value: "Auto Detect", detail: "Detected locally from speech")
                    Divider().overlay(LPTheme.border)
                    PrepareSetting(icon: "character.bubble.fill", title: "To language", value: "Vietnamese", detail: "Tiếng Việt")
                    Divider().overlay(LPTheme.border)
                    PrepareSetting(icon: "person.wave.2.fill", title: "AI Voice", value: "Nam · Natural", detail: "Warm, clear Vietnamese voice")
                    Divider().overlay(LPTheme.border)
                    PrepareSetting(icon: "slider.horizontal.3", title: "Dubbing mode", value: "Balanced", detail: "Quality and processing speed")
                    Divider().overlay(LPTheme.border)
                    PrepareSetting(icon: "captions.bubble.fill", title: "Subtitles", value: "Bilingual", detail: "Original + translated")
                }
                .lpCard()

                LPPrimaryButton(title: "Translate & Dub", systemImage: "sparkles") {
                    model.beginProcessing()
                }

                Text("Estimated time depends on device performance and video duration.")
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                    .multilineTextAlignment(.center)
            }
            .padding(18)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .fileImporter(isPresented: $model.importerPresented, allowedContentTypes: [.movie, .mpeg4Movie, .quickTimeMovie]) { result in
            guard case .success(let url) = result else { return }
            Task { await model.importMedia(from: url) }
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

    var body: some View {
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
            Image(systemName: "chevron.right")
                .font(.caption.bold())
                .foregroundStyle(LPTheme.secondaryText)
        }
        .padding(.vertical, 11)
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
                    ProcessingStageRow(title: "Translating", state: .pending)
                    Divider().overlay(LPTheme.border)
                    ProcessingStageRow(title: "Creating Vietnamese voice", state: .pending)
                    Divider().overlay(LPTheme.border)
                    ProcessingStageRow(title: "Mixing audio", state: .pending)
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

                Label("Processing is designed to continue locally while the app remains active. Background execution is a Plus capability in the product roadmap.", systemImage: "iphone.gen3")
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                    .lpCard()

                if case .modelMissing = model.asrState {
                    Text("Speech model is not installed. LingoPlay will not download a large model without an explicit model-install action.")
                        .font(.caption)
                        .foregroundStyle(LPTheme.secondaryText)
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
            case .completed: return "Speech recognized locally"
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
}

private struct ProcessingStageRow: View {
    enum State {
        case complete
        case active
        case pending
        case blocked
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
                case .blocked:
                    Image(systemName: "lock.circle")
                case .failed:
                    Image(systemName: "exclamationmark.circle.fill")
                }
            }
            .frame(width: 24)
            .foregroundStyle((state == .pending || state == .blocked) ? LPTheme.secondaryText : (state == .failed ? .red : LPTheme.cyan))

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
        case .failed: "Failed"
        }
    }
}

struct PlayerView: View {
    @Bindable var model: AppModel

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                ScreenHeader(title: "The Future of AI", backAction: model.returnHome) {
                    Text("Vietnamese AI")
                        .font(.caption2.weight(.bold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(LPTheme.violet.opacity(0.35), in: Capsule())
                }

                ZStack(alignment: .bottom) {
                    VideoPlaceholder(width: nil, height: 250)
                    HStack(spacing: 34) {
                        Image(systemName: "gobackward.10")
                        Image(systemName: "pause.fill")
                            .font(.title2)
                        Image(systemName: "goforward.10")
                    }
                    .font(.body.bold())
                    .padding(.horizontal, 24)
                    .padding(.vertical, 13)
                    .background(.ultraThinMaterial, in: Capsule())
                    .padding(.bottom, 14)
                }

                VStack(spacing: 8) {
                    Slider(value: .constant(0.19))
                        .tint(LPTheme.cyan)
                    HStack {
                        Text("00:15:42")
                        Spacer()
                        Text("01:24:32")
                    }
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(LPTheme.secondaryText)
                }

                VStack(alignment: .leading, spacing: 12) {
                    SubtitleLine(language: "EN", text: "Artificial intelligence is transforming the way we live and work.")
                    Divider().overlay(LPTheme.border)
                    SubtitleLine(language: "VI", text: "Trí tuệ nhân tạo đang thay đổi cách chúng ta sống và làm việc.")
                }
                .lpCard()

                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("Audio blend")
                            .font(.headline)
                        Spacer()
                        Text("\(Int(model.audioBlend * 100))% Dub")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(LPTheme.cyan)
                    }
                    Slider(value: $model.audioBlend, in: 0...1)
                        .tint(LPTheme.cyan)
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
                    PlayerAction(icon: "captions.bubble.fill", label: "Subtitles")
                    PlayerAction(icon: "waveform", label: "Blend")
                    PlayerAction(icon: "speedometer", label: String(format: "%.1fx", model.playbackSpeed))
                    PlayerAction(icon: "arrow.down.circle.fill", label: "Offline")
                }
            }
            .padding(18)
            .padding(.bottom, 28)
        }
        .scrollIndicators(.hidden)
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
    let offlineOnly: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(offlineOnly ? "Offline" : "Library")
                            .font(.largeTitle.bold())
                        Text(offlineOnly ? "Ready without a connection" : "Your translated videos")
                            .font(.caption)
                            .foregroundStyle(LPTheme.secondaryText)
                    }
                    Spacer()
                    Image(systemName: offlineOnly ? "arrow.down.circle.fill" : "magnifyingglass")
                        .font(.title3)
                        .foregroundStyle(LPTheme.cyan)
                }

                ForEach(model.recentVideos.prefix(offlineOnly ? 2 : model.recentVideos.count)) { video in
                    RecentVideoRow(video: video) {
                        model.previewResult()
                    }
                }

                VStack(alignment: .leading, spacing: 11) {
                    LPSectionHeader(title: "Storage", trailing: "45 GB of 128 GB")
                    ProgressView(value: 0.35)
                        .tint(LPTheme.cyan)
                    Text("AI models and translated media stay on this device.")
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
                        Text("Settings")
                            .font(.largeTitle.bold())
                        Text("Simple playback and privacy preferences")
                            .font(.caption)
                            .foregroundStyle(LPTheme.secondaryText)
                    }
                    Spacer()
                    LPBrandMark(compact: true)
                }

                VStack(spacing: 0) {
                    SettingsValueRow(icon: "person.wave.2.fill", title: "AI Voice", value: "Nam · Natural")
                    Divider().overlay(LPTheme.border)
                    SettingsValueRow(icon: "character.bubble.fill", title: "Playback Language", value: "Vietnamese")
                    Divider().overlay(LPTheme.border)
                    Toggle(isOn: $model.wifiOnly) {
                        Label("Download models on Wi-Fi only", systemImage: "wifi")
                    }
                    .padding(.vertical, 14)
                    Divider().overlay(LPTheme.border)
                    Toggle(isOn: $model.bilingualSubtitles) {
                        Label("Bilingual subtitles", systemImage: "captions.bubble.fill")
                    }
                    .padding(.vertical, 14)
                }
                .lpCard()

                VStack(alignment: .leading, spacing: 12) {
                    Label("Private by architecture", systemImage: "lock.shield.fill")
                        .font(.headline)
                        .foregroundStyle(LPTheme.accent)
                    Text("Video and audio never go to the LingoPlay backend. Only transcript text required for translation is sent as compact JSON when online translation is enabled.")
                        .font(.subheadline)
                        .foregroundStyle(LPTheme.secondaryText)
                }
                .lpCard()

                VStack(spacing: 0) {
                    SettingsValueRow(icon: "shippingbox.fill", title: "Downloaded AI Models", value: "Not installed")
                    Divider().overlay(LPTheme.border)
                    SettingsValueRow(icon: "info.circle.fill", title: "About LingoPlay", value: "Foundation")
                }
                .lpCard()
            }
            .padding(18)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
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
