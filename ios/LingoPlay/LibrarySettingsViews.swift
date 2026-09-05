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
                    Button(action: model.cycleTranslationMode) {
                        SettingsValueRow(icon: "translate", title: model.uiText("Translation mode", "Chế độ dịch"), value: model.translationMode.label)
                    }
                    .buttonStyle(.plain)
                    Divider().overlay(LPTheme.border)
                    Button(action: model.cycleSpeakerMode) {
                        SettingsValueRow(icon: "person.2.wave.2.fill", title: model.uiText("Speaker mode", "Chế độ người nói"), value: model.speakerMode.label)
                    }
                    .buttonStyle(.plain)
                    Divider().overlay(LPTheme.border)
                    Toggle(isOn: $model.voiceCloningEnabled) {
                        Label(model.uiText("Allow local voice cloning", "Cho phép clone giọng cục bộ"), systemImage: "lock.shield.fill")
                    }
                    .padding(.vertical, 14)
                    Text(model.uiText(
                        "Use only voices you own or have permission to reproduce. Cloning stays local, is off by default, and never assigns a cloned identity to overlapping/unknown speech.",
                        "Chỉ dùng giọng bạn sở hữu hoặc được phép tái tạo. Clone chạy cục bộ, mặc định tắt và không gán danh tính clone cho đoạn chồng giọng/không xác định."
                    ))
                        .font(.caption2)
                        .foregroundStyle(LPTheme.secondaryText)
                        .padding(.bottom, 10)
                    Divider().overlay(LPTheme.border)
                    Toggle(isOn: $model.cleanBackgroundEnabled) {
                        Label(model.uiText("Clean Background", "Tách nền sạch"), systemImage: "waveform.path.ecg")
                    }
                    .padding(.vertical, 14)
                    Text(model.uiText(
                        "When enabled, local source separation removes dialogue from the background stem before translated speech is mixed. A verified model must be installed.",
                        "Khi bật, tách nguồn cục bộ loại lời thoại khỏi stem nền trước khi trộn giọng dịch. Cần cài model đã xác minh."
                    ))
                        .font(.caption2)
                        .foregroundStyle(LPTheme.secondaryText)
                        .padding(.bottom, 10)
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
                    Text(model.uiText("Cloud mode sends transcript JSON only to LingoPlay. Offline mode keeps transcript text on-device; ML Kit may contact Google for model downloads, updates, and performance/utilization metrics.", "Chế độ Cloud chỉ gửi transcript JSON tới LingoPlay. Chế độ Offline giữ transcript trên thiết bị; ML Kit có thể kết nối Google để tải/cập nhật model và gửi chỉ số hiệu năng/mức sử dụng."))
                        .font(.subheadline)
                        .foregroundStyle(LPTheme.secondaryText)
                }
                .lpCard()

                OfflineTranslationModelManagementCard(model: model)

                SpeechModelManagementCard(model: model)

                SourceSeparationModelManagementCard(model: model)

                SpeakerModelManagementCard(model: model)

                VoiceCloningModelManagementCard(model: model)

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
                        Text(model.uiText("Clean Background requires an explicit verified-model install; cross-device quality certification remains pending.", "Tách nền sạch cần chủ động cài model đã xác minh; kiểm định chất lượng đa thiết bị vẫn đang chờ."))
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
                        Text(model.uiText("Video/audio stay on-device. Only transcript JSON is eligible for Cloud translation requests.", "Video/audio luôn ở trên thiết bị. Chỉ transcript JSON có thể được gửi khi dịch Cloud."))
                            .font(.subheadline)
                            .foregroundStyle(LPTheme.secondaryText)
                        Text(model.uiText("Offline translation input/output stays on-device. ML Kit may contact Google for models, updates, and performance/utilization metrics.", "Nội dung vào/ra của dịch Offline ở lại trên thiết bị. ML Kit có thể kết nối Google để tải model, cập nhật và gửi chỉ số hiệu năng/mức sử dụng."))
                            .font(.caption)
                            .foregroundStyle(LPTheme.secondaryText)
                        Text("Powered by Google Translate · ML Kit")
                            .font(.caption2)
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

struct OfflineTranslationModelManagementCard: View {
    @Bindable var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(model.uiText("Offline Translation Models", "Model dịch Offline"), systemImage: "translate")
                .font(.headline)
                .foregroundStyle(LPTheme.accent)
            Text(model.uiText("English support is built in. Install or delete Vietnamese, Japanese, and Chinese explicitly; each downloadable model is about 30 MB.", "Tiếng Anh được tích hợp sẵn. Cài hoặc xóa thủ công model tiếng Việt, Nhật và Trung; mỗi model tải về khoảng 30 MB."))
                .font(.caption)
                .foregroundStyle(LPTheme.secondaryText)

            ForEach(OfflineTranslationLanguagePolicy.supportedCodes, id: \.self) { code in
                Divider().overlay(LPTheme.border)
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(OfflineTranslationLanguagePolicy.displayName(code))
                            .font(.subheadline.weight(.semibold))
                        Text(status(for: code))
                            .font(.caption2)
                            .foregroundStyle(LPTheme.secondaryText)
                    }
                    Spacer()
                    if code == "en" {
                        Text(model.uiText("Built in", "Tích hợp sẵn"))
                            .font(.caption)
                            .foregroundStyle(LPTheme.secondaryText)
                    } else if model.translationModelBusyCode == code {
                        ProgressView()
                            .tint(LPTheme.cyan)
                    } else {
                        Button(model.downloadedTranslationModelCodes.contains(code) ? model.uiText("Delete", "Xóa") : model.uiText("Install", "Cài")) {
                            model.toggleOfflineTranslationModel(code)
                        }
                        .buttonStyle(.bordered)
                        .disabled(!model.canManageOfflineTranslationModels)
                    }
                }
            }

            if let error = model.translationModelError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
            Text("Powered by Google Translate · ML Kit")
                .font(.caption2)
                .foregroundStyle(LPTheme.secondaryText)
            Text(model.uiText("Missing models stop the job; LingoPlay never switches to cloud automatically.", "Thiếu model sẽ dừng tác vụ; LingoPlay không tự chuyển sang cloud."))
                .font(.caption2)
                .foregroundStyle(LPTheme.secondaryText)
        }
        .lpCard()
    }

    private func status(for code: String) -> String {
        if code == "en" {
            return model.uiText("Built-in pivot language", "Ngôn ngữ trung gian tích hợp sẵn")
        }
        if model.translationModelBusyCode == code {
            return model.uiText("Working…", "Đang xử lý…")
        }
        return model.downloadedTranslationModelCodes.contains(code)
            ? model.uiText("Installed", "Đã cài")
            : model.uiText("Not installed", "Chưa cài")
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
                    "Optional 64 MiB download (~78 MiB installed). It runs fully on-device after an explicit install. One Vietnamese 22.05 kHz preset is included; voice cloning, when available, uses a separate optional model and is not this preset.",
                    "Gói tải tùy chọn 64 MiB (khoảng 78 MiB sau khi cài). Sau khi bạn chủ động cài, giọng chạy hoàn toàn trên thiết bị. Đây là một preset tiếng Việt 22,05 kHz; clone giọng, khi khả dụng, dùng model tùy chọn riêng chứ không phải preset này."
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
