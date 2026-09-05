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

struct SavedVideoRow: View {
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
                    PrepareSetting(icon: "character.bubble.fill", title: "To language", value: model.targetLanguageChoice.label, detail: "Translation + offline voice", action: model.cycleTargetLanguage)
                    Divider().overlay(LPTheme.border)
                    PrepareSetting(icon: "translate", title: "Translation mode", value: model.translationMode.label, detail: model.translationMode.detail, action: model.cycleTranslationMode)
                    Divider().overlay(LPTheme.border)
                    PrepareSetting(icon: "person.wave.2.fill", title: "AI Voice", value: model.preferredVoiceLabel, detail: "Installed offline voice", action: model.cycleVoice)
                    Divider().overlay(LPTheme.border)
                    PrepareSetting(icon: "slider.horizontal.3", title: "Dubbing mode", value: model.dubbingMode.label, detail: model.dubbingMode.detail, action: model.cycleDubbingMode)
                    Divider().overlay(LPTheme.border)
                    PrepareSetting(
                        icon: "waveform.path.ecg",
                        title: "Clean Background",
                        value: model.cleanBackgroundEnabled ? "On" : "Off",
                        detail: CleanBackgroundCapability.isAvailable
                            ? "Uses local vocals/accompaniment separation"
                            : "Install the verified local model in Settings before processing",
                        action: { model.cleanBackgroundEnabled.toggle() }
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
