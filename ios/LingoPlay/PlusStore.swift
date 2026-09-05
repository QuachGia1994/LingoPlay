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
    private let entitlementService = PlusEntitlementService()

    func start() {
        guard !started else { return }
        started = true
        updatesTask = Task { [weak self] in
            for await result in Transaction.updates {
                guard let self else { return }
                switch result {
                case .verified(let transaction):
                    if await self.authorize(transaction) {
                        await transaction.finish()
                    }
                    await self.refreshEntitlements()
                case .unverified:
                    self.isPlus = false
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
        var sawRelevantTransaction = false
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result,
                  Self.productIDs.contains(transaction.productID),
                  transaction.revocationDate == nil
            else { continue }
            if let expiration = transaction.expirationDate, expiration <= Date() { continue }
            sawRelevantTransaction = true
            if await authorize(transaction, updateMessage: false) {
                active = true
                break
            }
        }
        isPlus = active
        if active {
            statusMessage = nil
        } else if sawRelevantTransaction && statusMessage == nil {
            statusMessage = "Store purchase found, but server verification did not confirm an active Plus subscription."
        }
    }

    func purchase(_ product: Product) async {
        purchaseState = .purchasing
        statusMessage = nil
        do {
            switch try await product.purchase() {
            case .success(let result):
                switch result {
                case .verified(let transaction):
                    if await authorize(transaction) {
                        purchaseState = .idle
                        await transaction.finish()
                        await refreshEntitlements()
                    } else {
                        purchaseState = .failed("Server verification did not confirm Plus.")
                        statusMessage = statusMessage ?? "Purchase was verified by StoreKit, but Plus stays locked until the backend confirms the subscription."
                    }
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

    private func authorize(_ transaction: Transaction, updateMessage: Bool = true) async -> Bool {
        guard Self.productIDs.contains(transaction.productID),
              transaction.revocationDate == nil
        else { return false }
        if let expiration = transaction.expirationDate, expiration <= Date() { return false }

        #if DEBUG
        if transaction.environment == .xcode {
            let active = PlusEntitlementService.localXcodeEntitlement(transaction)
            if active {
                isPlus = true
                if updateMessage {
                    statusMessage = "Plus active in local Xcode StoreKit Testing. Production builds still require server verification."
                }
            }
            return active
        }
        #endif

        do {
            let entitlement = try await entitlementService.verify(transaction: transaction)
            if entitlement.isPlus {
                isPlus = true
                if updateMessage { statusMessage = nil }
                return true
            }
            if updateMessage {
                statusMessage = "Server verification reports no active Plus subscription (\(entitlement.reason))."
            }
            return false
        } catch {
            if updateMessage {
                statusMessage = "Plus stays locked until server verification succeeds: \(error.localizedDescription)"
            }
            return false
        }
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
