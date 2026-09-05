import SwiftUI

struct SpeakerModelManagementCard: View {
    @Bindable var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "person.2.wave.2.fill")
                    .foregroundStyle(LPTheme.cyan)
                VStack(alignment: .leading, spacing: 3) {
                    Text(model.uiText("Speaker AI", "AI nhận diện người nói"))
                        .font(.subheadline.weight(.semibold))
                    Text(statusText)
                        .font(.caption)
                        .foregroundStyle(LPTheme.secondaryText)
                }
                Spacer()
            }

            switch model.speakerModelInstallState {
            case .notInstalled:
                Text(model.uiText(
                    "Optional local diarization pack (~45 MiB download). Multi-speaker mode labels speakers by first appearance; overlap stays unknown instead of being guessed.",
                    "Gói diarization cục bộ tùy chọn (tải khoảng 45 MiB). Multi-speaker gán nhãn theo lần xuất hiện đầu; đoạn chồng giọng giữ unknown thay vì đoán."
                ))
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                LPPrimaryButton(title: model.uiText("Install Speaker AI", "Cài Speaker AI"), systemImage: "arrow.down.circle.fill") {
                    model.installSpeakerModel()
                }
            case .downloading(let progress):
                ProgressView(value: progress)
                    .tint(LPTheme.cyan)
                Text("\(Int(progress * 100))%")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(LPTheme.secondaryText)
                Button(model.uiText("Cancel download", "Hủy tải")) { model.cancelSpeakerModelInstall() }
                    .buttonStyle(.bordered)
            case .installed(let bytes):
                Text(model.uiText(
                    "Installed locally · \(MediaFormatting.bytes(bytes)). Audio analysis never leaves this device.",
                    "Đã cài cục bộ · \(MediaFormatting.bytes(bytes)). Phân tích âm thanh không rời thiết bị."
                ))
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                Button(role: .destructive) {
                    model.deleteSpeakerModel()
                } label: {
                    Label(model.uiText("Delete Speaker AI", "Xóa Speaker AI"), systemImage: "trash")
                }
                .buttonStyle(.bordered)
                .disabled(!model.canDeleteSpeakerModel)
            case .failed(let message):
                Text(message).font(.caption).foregroundStyle(.red)
                LPPrimaryButton(title: model.uiText("Retry install", "Thử cài lại"), systemImage: "arrow.clockwise") {
                    model.installSpeakerModel()
                }
            }

            Text("Pyannote Segmentation INT8 + NeMo Titanet · sherpa-onnx 1.13.7")
                .font(.caption2.monospaced())
                .foregroundStyle(LPTheme.secondaryText)
        }
        .lpCard()
    }

    private var statusText: String {
        switch model.speakerModelInstallState {
        case .notInstalled: model.uiText("Optional · required for Multi-speaker", "Tùy chọn · cần cho Multi-speaker")
        case .downloading: model.uiText("Downloading verified diarization pack…", "Đang tải gói diarization đã xác minh…")
        case .installed: model.uiText("Installed · offline", "Đã cài · offline")
        case .failed: model.uiText("Install failed", "Cài đặt thất bại")
        }
    }
}

struct VoiceCloningModelManagementCard: View {
    @Bindable var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "lock.shield.fill")
                    .foregroundStyle(LPTheme.accent)
                VStack(alignment: .leading, spacing: 3) {
                    Text(model.uiText("Local Voice Cloning", "Clone giọng cục bộ"))
                        .font(.subheadline.weight(.semibold))
                    Text(statusText)
                        .font(.caption)
                        .foregroundStyle(LPTheme.secondaryText)
                }
                Spacer()
            }

            switch model.voiceCloningModelInstallState {
            case .notInstalled:
                Text(model.uiText(
                    "Optional ZipVoice INT8 pack (~156 MiB download), available only for English and Chinese output. Cloning is off by default and requires the explicit consent toggle above.",
                    "Gói ZipVoice INT8 tùy chọn (tải khoảng 156 MiB), chỉ dùng cho đầu ra tiếng Anh và Trung. Clone mặc định tắt và cần bật rõ công tắc đồng ý ở trên."
                ))
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                LPPrimaryButton(
                    title: model.uiText("Install Voice Cloning", "Cài Voice Cloning"),
                    systemImage: "arrow.down.circle.fill"
                ) {
                    model.installVoiceCloningModel()
                }
            case .downloading(let progress):
                ProgressView(value: progress)
                    .tint(LPTheme.cyan)
                Text("\(Int(progress * 100))%")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(LPTheme.secondaryText)
                Button(model.uiText("Cancel download", "Hủy tải")) {
                    model.cancelVoiceCloningModelInstall()
                }
                .buttonStyle(.bordered)
            case .installed(let bytes):
                Text(model.uiText(
                    "Installed locally · \(MediaFormatting.bytes(bytes)). A clear single-speaker segment from the current video is used only as an ephemeral reference; overlapping/unknown speech falls back to installed voices.",
                    "Đã cài cục bộ · \(MediaFormatting.bytes(bytes)). Chỉ một đoạn đơn-speaker rõ trong video hiện tại được dùng làm mẫu tạm thời; đoạn chồng giọng/không xác định dùng giọng đã cài làm fallback."
                ))
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                Button(role: .destructive) {
                    model.deleteVoiceCloningModel()
                } label: {
                    Label(model.uiText("Delete Voice Cloning", "Xóa Voice Cloning"), systemImage: "trash")
                }
                .buttonStyle(.bordered)
                .disabled(!model.canDeleteVoiceCloningModel)
            case .failed(let message):
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.red)
                LPPrimaryButton(
                    title: model.uiText("Retry install", "Thử cài lại"),
                    systemImage: "arrow.clockwise"
                ) {
                    model.installVoiceCloningModel()
                }
            }

            Text(model.uiText(
                "Use only voices you own or have explicit permission to reproduce. Reference audio/profile is not saved as a reusable voicebank after processing.",
                "Chỉ dùng giọng bạn sở hữu hoặc được cho phép rõ ràng để tái tạo. Mẫu/profile giọng không được lưu thành voicebank dùng lại sau xử lý."
            ))
                .font(.caption2)
                .foregroundStyle(LPTheme.secondaryText)
        }
        .lpCard()
    }

    private var statusText: String {
        switch model.voiceCloningModelInstallState {
        case .notInstalled:
            model.uiText("Optional · consent required · EN/ZH only", "Tùy chọn · cần đồng ý · chỉ EN/ZH")
        case .downloading:
            model.uiText("Downloading verified cloning pack…", "Đang tải gói clone đã xác minh…")
        case .installed:
            model.uiText("Installed · offline", "Đã cài · offline")
        case .failed:
            model.uiText("Install failed", "Cài đặt thất bại")
        }
    }
}
