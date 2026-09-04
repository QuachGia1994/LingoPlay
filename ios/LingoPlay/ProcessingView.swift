import AVFoundation
import AVKit
import CoreTransferable
import PhotosUI
import StoreKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

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
                    ProcessingStageRow(
                        title: "Creating offline voice",
                        state: ttsStageState,
                        statusOverride: ttsStageStatus
                    )
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
                        Text(
                            translation.mode == .offline
                                ? "Powered by Google Translate · transcript stayed on-device"
                                : "Only transcript JSON was sent · source media stayed on-device"
                        )
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
                        Text("Offline voice · \(dub.voiceIdentifier)")
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

    private var ttsStageStatus: String? {
        guard case let .synthesizing(completedSegments, totalSegments) = model.ttsState,
              totalSegments > 0 else { return nil }
        let currentSegment = min(totalSegments, max(1, completedSegments + 1))
        return "\(currentSegment)/\(totalSegments)"
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
    let statusOverride: String?

    init(title: String, state: State, statusOverride: String? = nil) {
        self.title = title
        self.state = state
        self.statusOverride = statusOverride
    }

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
            Text(statusOverride ?? status)
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
