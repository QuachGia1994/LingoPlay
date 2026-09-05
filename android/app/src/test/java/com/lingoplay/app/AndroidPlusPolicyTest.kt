package com.lingoplay.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPlusPolicyTest {
    @Test
    fun acknowledgementGateSuppressesDuplicateInFlightToken() {
        val gate = PurchaseAcknowledgementGate()

        assertTrue(gate.tryBegin("purchase-token", alreadyAcknowledged = false))
        assertFalse(gate.tryBegin("purchase-token", alreadyAcknowledged = false))

        gate.finish("purchase-token")
        assertTrue(gate.tryBegin("purchase-token", alreadyAcknowledged = false))
    }

    @Test
    fun acknowledgementGateRejectsAlreadyAcknowledgedPurchase() {
        val gate = PurchaseAcknowledgementGate()
        assertFalse(gate.tryBegin("purchase-token", alreadyAcknowledged = true))
    }

    @Test
    fun plusEntitlementRequiresServerAuthorityAndActiveReason() {
        assertTrue(
            AndroidPlusServerEntitlement(
                plan = "plus",
                authority = "server",
                platform = "google",
                productId = AndroidPlusStore.MONTHLY_ID,
                expiresAt = "2026-10-01T00:00:00Z",
                reason = "active",
            ).isPlus,
        )
        assertFalse(
            AndroidPlusServerEntitlement(
                plan = "plus",
                authority = "client",
                platform = "google",
                productId = AndroidPlusStore.MONTHLY_ID,
                expiresAt = "2026-10-01T00:00:00Z",
                reason = "active",
            ).isPlus,
        )
        assertFalse(
            AndroidPlusServerEntitlement(
                plan = "free",
                authority = "server",
                platform = "google",
                productId = AndroidPlusStore.MONTHLY_ID,
                expiresAt = "2026-10-01T00:00:00Z",
                reason = "revoked",
            ).isPlus,
        )
    }
}
