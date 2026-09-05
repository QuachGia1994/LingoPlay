import Foundation
import StoreKit

struct PlusServerEntitlement: Codable, Sendable, Equatable {
    let plan: String
    let authority: String
    let platform: String
    let productId: String?
    let expiresAt: String?
    let reason: String

    var isPlus: Bool { plan == "plus" && authority == "server" && reason == "active" }
}

enum PlusEntitlementServiceError: LocalizedError, Equatable {
    case endpointMissing
    case invalidResponse
    case server(status: Int, code: String)

    var errorDescription: String? {
        switch self {
        case .endpointMissing:
            "Billing verification backend is not configured."
        case .invalidResponse:
            "Billing verification backend returned an invalid response."
        case let .server(status, code):
            "Billing verification failed (\(status)): \(code)"
        }
    }
}

struct PlusEntitlementService: Sendable {
    private struct AppleRequest: Codable {
        let platform = "apple"
        let transactionId: String
        let environment: String
    }

    private struct ErrorResponse: Codable {
        let error: String
    }

    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func verify(transaction: Transaction) async throws -> PlusServerEntitlement {
        guard let baseURL = TranslationEndpointConfiguration.resolve(
            plistValue: Bundle.main.object(forInfoDictionaryKey: TranslationEndpointConfiguration.infoDictionaryKey) as? String
        ) else {
            throw PlusEntitlementServiceError.endpointMissing
        }
        let environment: String
        switch transaction.environment {
        case .production:
            environment = "production"
        case .sandbox:
            environment = "sandbox"
        default:
            throw PlusEntitlementServiceError.invalidResponse
        }

        let url = baseURL.appending(path: "v1/entitlements/verify")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        request.httpBody = try JSONEncoder().encode(
            AppleRequest(transactionId: String(transaction.id), environment: environment)
        )

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw PlusEntitlementServiceError.invalidResponse
        }
        guard (200..<300).contains(http.statusCode) else {
            let code = (try? JSONDecoder().decode(ErrorResponse.self, from: data).error) ?? "server_error"
            throw PlusEntitlementServiceError.server(status: http.statusCode, code: code)
        }
        guard let entitlement = try? JSONDecoder().decode(PlusServerEntitlement.self, from: data),
              entitlement.platform == "apple",
              entitlement.authority == "server"
        else {
            throw PlusEntitlementServiceError.invalidResponse
        }
        return entitlement
    }

    #if DEBUG
    static func localXcodeEntitlement(_ transaction: Transaction, now: Date = Date()) -> Bool {
        guard transaction.environment == .xcode,
              PlusStore.productIDs.contains(transaction.productID),
              transaction.revocationDate == nil
        else { return false }
        if let expiration = transaction.expirationDate, expiration <= now { return false }
        return true
    }
    #endif
}
