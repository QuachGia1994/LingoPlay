import AVFoundation
import AVKit
import CoreTransferable
import PhotosUI
import StoreKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

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
                            SubtitleLine(language: model.activeSubtitleTargetLanguage, text: model.activeTranslationSegment?.translatedText ?? "—")
                        }
                        .lpCard()
                    case .bilingual:
                        VStack(alignment: .leading, spacing: 12) {
                            SubtitleLine(language: model.activeSubtitleSourceLanguage, text: model.activeTranslationSegment?.sourceText ?? "—")
                            Divider().overlay(LPTheme.border)
                            SubtitleLine(language: model.activeSubtitleTargetLanguage, text: model.activeTranslationSegment?.translatedText ?? "—")
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
