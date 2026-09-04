package com.lingoplay.app

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams


enum class AndroidPlusPhase {
    IDLE,
    CONNECTING,
    LOADING_PRODUCTS,
    PURCHASING,
    PENDING,
    ACTIVE,
    UNAVAILABLE,
    FAILED,
}

data class AndroidPlusProduct(
    val productId: String,
    val title: String,
    val price: String,
    internal val details: ProductDetails,
    internal val offerToken: String,
)

class AndroidPlusStore(context: Context) : PurchasesUpdatedListener {
    companion object {
        const val WEEKLY_ID = "com.lingoplay.plus.weekly"
        const val MONTHLY_ID = "com.lingoplay.plus.monthly"
        private val productIds = listOf(WEEKLY_ID, MONTHLY_ID)
    }

    var phase by mutableStateOf(AndroidPlusPhase.IDLE)
        private set
    var products by mutableStateOf<List<AndroidPlusProduct>>(emptyList())
        private set
    var isPlus by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private val billingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build(),
        )
        .build()

    fun start() {
        if (billingClient.isReady) {
            refresh()
            return
        }
        phase = AndroidPlusPhase.CONNECTING
        message = null
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    refresh()
                } else {
                    fail("Google Play Billing unavailable (${result.responseCode}): ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                phase = AndroidPlusPhase.UNAVAILABLE
                message = "Google Play Billing disconnected. Reopen Plus to retry."
            }
        })
    }

    fun stop() {
        if (billingClient.isReady) billingClient.endConnection()
    }

    fun refresh() {
        if (!billingClient.isReady) {
            start()
            return
        }
        queryPurchases()
        queryProducts()
    }

    fun restore() = refresh()

    fun purchase(activity: Activity, product: AndroidPlusProduct) {
        if (!billingClient.isReady) {
            start()
            return
        }
        phase = AndroidPlusPhase.PURCHASING
        message = null
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product.details)
            .setOfferToken(product.offerToken)
            .build()
        val result = billingClient.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams)).build(),
        )
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            fail("Unable to open Google Play purchase (${result.responseCode}): ${result.debugMessage}")
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> processPurchases(purchases.orEmpty())
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                phase = if (isPlus) AndroidPlusPhase.ACTIVE else AndroidPlusPhase.IDLE
                message = null
            }
            else -> fail("Google Play purchase failed (${result.responseCode}): ${result.debugMessage}")
        }
    }

    private fun queryProducts() {
        phase = if (isPlus) AndroidPlusPhase.ACTIVE else AndroidPlusPhase.LOADING_PRODUCTS
        val query = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map { productId ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                },
            )
            .build()
        billingClient.queryProductDetailsAsync(query) { result, detailsResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                products = emptyList()
                if (!isPlus) {
                    phase = AndroidPlusPhase.UNAVAILABLE
                    message = "Plus products are not available from Google Play yet."
                }
                return@queryProductDetailsAsync
            }
            products = detailsResult.productDetailsList.mapNotNull { details ->
                val offer = details.subscriptionOfferDetails?.firstOrNull() ?: return@mapNotNull null
                val price = offer.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice ?: "—"
                AndroidPlusProduct(
                    productId = details.productId,
                    title = details.name,
                    price = price,
                    details = details,
                    offerToken = offer.offerToken,
                )
            }.sortedBy { productIds.indexOf(it.productId) }
            if (isPlus) {
                phase = AndroidPlusPhase.ACTIVE
                message = null
            } else if (products.isEmpty()) {
                phase = AndroidPlusPhase.UNAVAILABLE
                message = "Plus is pre-wired, but Play Console subscription products are not available for this build/account."
            } else {
                phase = AndroidPlusPhase.IDLE
                message = null
            }
        }
    }

    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchases)
            } else if (!isPlus) {
                message = "Unable to restore Google Play purchases (${result.responseCode})."
            }
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        val relevant = purchases.filter { purchase -> purchase.products.any(productIds::contains) }
        val purchased = relevant.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val pending = relevant.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        isPlus = purchased.isNotEmpty()
        phase = when {
            isPlus -> AndroidPlusPhase.ACTIVE
            pending -> AndroidPlusPhase.PENDING
            products.isEmpty() -> AndroidPlusPhase.UNAVAILABLE
            else -> AndroidPlusPhase.IDLE
        }
        message = if (pending && !isPlus) "Purchase pending in Google Play. Plus unlocks only after payment completes." else null

        purchased.filterNot(Purchase::isAcknowledged).forEach { purchase ->
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(params) { result ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    message = "Plus is active locally, but Google Play acknowledgement will be retried on next refresh."
                }
            }
        }
    }

    private fun fail(reason: String) {
        phase = AndroidPlusPhase.FAILED
        message = reason
    }
}
