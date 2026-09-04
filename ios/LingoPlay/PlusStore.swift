import Foundation
import Observation
import StoreKit

@MainActor
@Observable
final class PlusStore {
    static let weeklyID = "com.lingoplay.plus.weekly"
    static let monthlyID = "com.lingoplay.plus.monthly"
    static let productIDs = [weeklyID, monthlyID]

    enum PurchaseState: Equatable {
        case idle
        case loadingProducts
        case purchasing
        case pending
        case restoring
        case failed(String)
    }

    var products: [Product] = []
    var isPlus = false
    var purchaseState: PurchaseState = .idle
    var statusMessage: String?

    private var updatesTask: Task<Void, Never>?
    private var started = false

    func start() {
        guard !started else { return }
        started = true
        updatesTask = Task { [weak self] in
            for await result in Transaction.updates {
                guard let self else { return }
                switch result {
                case .verified(let transaction):
                    self.applyVerifiedEntitlement(transaction)
                    await transaction.finish()
                    await self.refreshEntitlements()
                case .unverified:
                    self.statusMessage = "StoreKit returned an unverified transaction. Plus access was not granted."
                }
            }
        }
        Task { await refresh() }
    }

    func refresh() async {
        await loadProducts()
        await refreshEntitlements()
    }

    func loadProducts() async {
        purchaseState = .loadingProducts
        do {
            let loaded = try await Product.products(for: Self.productIDs)
            let order = Dictionary(uniqueKeysWithValues: Self.productIDs.enumerated().map { ($1, $0) })
            products = loaded.sorted { (order[$0.id] ?? .max) < (order[$1.id] ?? .max) }
            purchaseState = .idle
            statusMessage = products.isEmpty
                ? "Plus products are unavailable. In development, run with the local Products.storekit configuration enabled."
                : nil
        } catch {
            products = []
            purchaseState = .failed(error.localizedDescription)
            statusMessage = error.localizedDescription
        }
    }

    func refreshEntitlements() async {
        var active = false
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result,
                  Self.productIDs.contains(transaction.productID),
                  transaction.revocationDate == nil
            else { continue }
            if let expiration = transaction.expirationDate, expiration <= Date() { continue }
            active = true
        }
        isPlus = active
    }

    func purchase(_ product: Product) async {
        purchaseState = .purchasing
        statusMessage = nil
        do {
            switch try await product.purchase() {
            case .success(let result):
                switch result {
                case .verified(let transaction):
                    applyVerifiedEntitlement(transaction)
                    purchaseState = .idle
                    await transaction.finish()
                    await refreshEntitlements()
                case .unverified:
                    purchaseState = .failed("Purchase could not be verified.")
                    statusMessage = "Purchase could not be verified, so Plus access was not granted."
                }
            case .pending:
                purchaseState = .pending
                statusMessage = "Purchase is pending approval. Plus will unlock only after StoreKit reports a verified transaction."
            case .userCancelled:
                purchaseState = .idle
            @unknown default:
                purchaseState = .failed("Unknown StoreKit purchase result.")
                statusMessage = "StoreKit returned an unknown purchase result."
            }
        } catch {
            purchaseState = .failed(error.localizedDescription)
            statusMessage = error.localizedDescription
        }
    }

    private func applyVerifiedEntitlement(_ transaction: Transaction) {
        guard Self.productIDs.contains(transaction.productID),
              transaction.revocationDate == nil
        else { return }
        if let expiration = transaction.expirationDate, expiration <= Date() { return }
        isPlus = true
    }

    func restore() async {
        purchaseState = .restoring
        statusMessage = nil
        do {
            try await AppStore.sync()
            await refreshEntitlements()
            purchaseState = .idle
            statusMessage = isPlus ? "Plus restored." : "No active Plus subscription was found."
        } catch {
            purchaseState = .failed(error.localizedDescription)
            statusMessage = error.localizedDescription
        }
    }
}
