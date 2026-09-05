import StoreKit
import SwiftUI

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
                        Text(model.uiText(
                            "Production Plus requires LingoPlay server verification after StoreKit verifies the transaction. Xcode StoreKit Testing remains available only in Debug builds until App Store Connect is configured.",
                            "Plus production yêu cầu server LingoPlay xác minh sau khi StoreKit xác minh giao dịch. StoreKit Testing của Xcode chỉ còn dùng trong build Debug cho tới khi App Store Connect được cấu hình."
                        ))
                            .font(.subheadline)
                            .foregroundStyle(LPTheme.secondaryText)
                            .multilineTextAlignment(.center)
                    }

                    if model.plusStore.isPlus {
                        Label(model.uiText("Plus active", "Plus đang hoạt động"), systemImage: "checkmark.seal.fill")
                            .font(.headline)
                            .foregroundStyle(LPTheme.cyan)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .lpCard()
                    }

                    VStack(alignment: .leading, spacing: 10) {
                        Label(model.uiText("Planned Plus capabilities", "Tính năng Plus dự kiến"), systemImage: "sparkles")
                            .font(.headline)
                            .foregroundStyle(LPTheme.accent)
                        Text(model.uiText(
                            "Clean Background requires an explicit verified-model install; cross-device quality certification remains pending.",
                            "Tách nền sạch cần chủ động cài model đã xác minh; kiểm định chất lượng đa thiết bị vẫn đang chờ."
                        ))
                            .font(.subheadline)
                            .foregroundStyle(LPTheme.secondaryText)
                    }
                    .lpCard()

                    if model.plusStore.products.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(model.uiText("Products unavailable", "Chưa có sản phẩm"))
                                .font(.headline)
                            Text(model.plusStore.statusMessage ?? model.uiText(
                                "Run from Xcode with Products.storekit for local Debug purchase tests. Production products require App Store Connect.",
                                "Chạy từ Xcode với Products.storekit để test mua trong Debug. Sản phẩm production cần App Store Connect."
                            ))
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

                    Text(model.uiText(
                        "Development note: Products.storekit is test-only. Release builds never treat local StoreKit state as production authority; App Store Connect products and server API credentials are still required.",
                        "Ghi chú phát triển: Products.storekit chỉ dùng để test. Build Release không xem trạng thái StoreKit cục bộ là quyền production; vẫn cần sản phẩm App Store Connect và thông tin xác minh server."
                    ))
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
