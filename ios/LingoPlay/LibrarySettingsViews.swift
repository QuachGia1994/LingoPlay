import AVFoundation
import AVKit
import CoreTransferable
import PhotosUI
import StoreKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

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

                NeuralVoiceManagementCard(model: model)

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
                        Text(model.uiText("Installed offline-voice selection and PiP are live. Clean Background/source separation remains disabled until a verified native engine is integrated.", "Chọn giọng offline đã cài và PiP đã hoạt động. Clean Background/tách nguồn vẫn tắt cho đến khi có engine native được xác minh."))
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

struct SpeechModelManagementCard: View {
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

struct NeuralVoiceManagementCard: View {
    @Bindable var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "waveform.badge.plus")
                    .foregroundStyle(LPTheme.cyan)
                VStack(alignment: .leading, spacing: 3) {
                    Text(model.uiText("Vietnamese Neural Voice", "Giọng Neural tiếng Việt"))
                        .font(.subheadline.weight(.semibold))
                    Text(statusText)
                        .font(.caption)
                        .foregroundStyle(LPTheme.secondaryText)
                }
                Spacer()
            }

            switch model.neuralVoiceInstallState {
            case .notInstalled:
                Text(model.uiText(
                    "Optional 64 MiB download (~78 MiB installed). It runs fully on-device after an explicit install. One Vietnamese 22.05 kHz preset is included; emotion and voice cloning are not enabled.",
                    "Gói tải tùy chọn 64 MiB (khoảng 78 MiB sau khi cài). Sau khi bạn chủ động cài, giọng chạy hoàn toàn trên thiết bị. Hiện có một giọng tiếng Việt 22,05 kHz; chưa bật cảm xúc và nhân bản giọng."
                ))
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                LPPrimaryButton(
                    title: model.uiText("Install Neural Voice", "Cài giọng Neural"),
                    systemImage: "arrow.down.circle.fill"
                ) {
                    model.installNeuralVoice()
                }
            case .downloading(let progress):
                ProgressView(value: progress)
                    .tint(LPTheme.cyan)
                Text("\(Int(progress * 100))%")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(LPTheme.secondaryText)
                Button(model.uiText("Cancel download", "Hủy tải")) {
                    model.cancelNeuralVoiceInstall()
                }
                .buttonStyle(.bordered)
            case .installed(let bytes):
                Text(model.uiText(
                    "Installed locally · \(MediaFormatting.bytes(bytes)). Select “Vietnamese Neural · VAIS1000” under AI Voice to use it. System offline voice remains the safe fallback.",
                    "Đã cài cục bộ · \(MediaFormatting.bytes(bytes)). Chọn “Vietnamese Neural · VAIS1000” trong Giọng AI để dùng. Giọng hệ thống offline vẫn là phương án dự phòng an toàn."
                ))
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                Button(role: .destructive) {
                    model.deleteNeuralVoice()
                } label: {
                    Label(model.uiText("Delete Neural Voice", "Xóa giọng Neural"), systemImage: "trash")
                }
                .buttonStyle(.bordered)
                .disabled(!model.canDeleteNeuralVoice)
            case .failed(let message):
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.red)
                LPPrimaryButton(
                    title: model.uiText("Retry install", "Thử cài lại"),
                    systemImage: "arrow.clockwise"
                ) {
                    model.installNeuralVoice()
                }
            }

            Text("VAIS-1000 · CC BY 4.0 · sherpa-onnx 1.13.7")
                .font(.caption2.monospaced())
                .foregroundStyle(LPTheme.secondaryText)
        }
        .lpCard()
    }

    private var statusText: String {
        switch model.neuralVoiceInstallState {
        case .notInstalled:
            model.uiText("Not installed", "Chưa cài")
        case .downloading:
            model.uiText("Downloading verified voice pack…", "Đang tải gói giọng đã xác minh…")
        case .installed:
            model.uiText("Installed · offline", "Đã cài · offline")
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
