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
}
