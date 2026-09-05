package com.lingoplay.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL


data class AndroidPlusServerEntitlement(
    val plan: String,
    val authority: String,
    val platform: String,
    val productId: String?,
    val expiresAt: String?,
    val reason: String,
) {
    val isPlus: Boolean
        get() = plan == "plus" && authority == "server" && platform == "google" && reason == "active"
}

internal object PlusEntitlementResponsePolicy {
    fun parse(jsonText: String): AndroidPlusServerEntitlement {
        val json = runCatching { JSONObject(jsonText) }
            .getOrElse { throw IllegalStateException("Billing verification backend returned invalid JSON.") }
        val plan = json.optString("plan")
        val authority = json.optString("authority")
        val platform = json.optString("platform")
        val reason = json.optString("reason")
        require(plan == "free" || plan == "plus") { "Billing verification backend returned an invalid plan." }
        require(authority == "server") { "Billing verification backend authority is invalid." }
        require(platform == "google") { "Billing verification backend platform is invalid." }
        require(reason in setOf("active", "expired", "revoked", "not_found", "mismatch", "not_active")) {
            "Billing verification backend reason is invalid."
        }
        return AndroidPlusServerEntitlement(
            plan = plan,
            authority = authority,
            platform = platform,
            productId = json.optString("productId").ifBlank { null },
            expiresAt = json.optString("expiresAt").ifBlank { null },
            reason = reason,
        )
    }
}

object PlusEntitlementService {
    suspend fun verifyGoogle(
        purchaseToken: String,
        endpointBaseUrl: String = BuildConfig.TRANSLATION_API_BASE_URL,
    ): AndroidPlusServerEntitlement = withContext(Dispatchers.IO) {
        require(purchaseToken.isNotBlank() && !purchaseToken.any(Char::isWhitespace)) {
            "Google Play purchase token is invalid."
        }
        val endpoint = endpointBaseUrl.trim().trimEnd('/')
        check(endpoint.startsWith("https://")) { "Billing verification backend is not configured." }
        val body = JSONObject().apply {
            put("platform", "google")
            put("purchaseToken", purchaseToken)
        }
        val connection = (URL("$endpoint/v1/entitlements/verify").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { output -> output.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val code = runCatching { JSONObject(responseText).optString("error") }
                    .getOrNull()
                    .orEmpty()
                    .ifBlank { "server_error" }
                throw IllegalStateException("Billing verification failed ($status): $code")
            }
            PlusEntitlementResponsePolicy.parse(responseText)
        } finally {
            connection.disconnect()
        }
    }
}
