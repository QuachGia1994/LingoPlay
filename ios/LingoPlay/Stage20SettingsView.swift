import SwiftUI

struct SourceSeparationModelManagementCard: View {
    @Bindable var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "waveform.path.ecg")
                    .foregroundStyle(LPTheme.cyan)
                VStack(alignment: .leading, spacing: 3) {
                    Text(model.uiText("Clean Background Model", "Model Tách nền sạch"))
                        .font(.subheadline.weight(.semibold))
                    Text(statusText)
                        .font(.caption)
                        .foregroundStyle(LPTheme.secondaryText)
                }
                Spacer()
            }

            switch model.sourceSeparationModelInstallState {
            case .notInstalled:
                Text(model.uiText(
                    "Optional 33.6 MiB verified download. The model runs locally and is required only when Clean Background is enabled.",
                    "Gói tải tùy chọn 33,6 MiB đã xác minh. Model chạy cục bộ và chỉ cần khi bật Tách nền sạch."
                ))
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                LPPrimaryButton(
                    title: model.uiText("Install Clean Background", "Cài Tách nền sạch"),
                    systemImage: "arrow.down.circle.fill"
                ) {
                    model.installSourceSeparationModel()
                }
            case .downloading(let progress):
                ProgressView(value: progress)
                    .tint(LPTheme.cyan)
                Text("\(Int(progress * 100))%")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(LPTheme.secondaryText)
                Button(model.uiText("Cancel download", "Hủy tải")) {
                    model.cancelSourceSeparationModelInstall()
                }
                .buttonStyle(.bordered)
            case .installed(let bytes):
                Text(model.uiText(
                    "Spleeter 2-stem FP16 · \(MediaFormatting.bytes(bytes)). Vocals and accompaniment stems are temporary and deleted after each processing run.",
                    "Spleeter 2-stem FP16 · \(MediaFormatting.bytes(bytes)). Stem giọng và nhạc nền chỉ tồn tại tạm thời và được xóa sau mỗi lần xử lý."
                ))
                    .font(.caption)
                    .foregroundStyle(LPTheme.secondaryText)
                Button(role: .destructive) {
                    model.deleteSourceSeparationModel()
                } label: {
                    Label(model.uiText("Delete Clean Background model", "Xóa model Tách nền sạch"), systemImage: "trash")
                }
                .buttonStyle(.bordered)
                .disabled(!model.canDeleteSourceSeparationModel)
            case .failed(let message):
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.red)
                LPPrimaryButton(
                    title: model.uiText("Retry install", "Thử cài lại"),
                    systemImage: "arrow.clockwise"
                ) {
                    model.installSourceSeparationModel()
                }
            }

            Text("sherpa-onnx 1.13.7 · Spleeter 2-stem FP16")
                .font(.caption2.monospaced())
                .foregroundStyle(LPTheme.secondaryText)
        }
        .lpCard()
    }

    private var statusText: String {
        switch model.sourceSeparationModelInstallState {
        case .notInstalled:
            model.uiText("Not installed", "Chưa cài")
        case .downloading:
            model.uiText("Downloading verified separator pack…", "Đang tải gói tách nguồn đã xác minh…")
        case .installed:
            model.uiText("Installed · offline", "Đã cài · offline")
        case .failed:
            model.uiText("Install failed", "Cài đặt thất bại")
        }
    }
}
