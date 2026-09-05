import assert from "node:assert/strict";
import test from "node:test";
import {
  BillingConfigurationError,
  validateEntitlementVerificationPayload,
  verifyAppleEntitlement,
  verifyGoogleEntitlement,
} from "../src/entitlements.ts";
import { handleRequest } from "../src/index.ts";

const NOW = Date.parse("2026-09-05T10:00:00.000Z");

function base64Url(value: string): string {
  return Buffer.from(value, "utf8").toString("base64url");
}

function fakeJws(payload: unknown): string {
  return `${base64Url(JSON.stringify({ alg: "ES256", kid: "test" }))}.${base64Url(JSON.stringify(payload))}.AA`;
}

function pem(label: string, der: ArrayBuffer): string {
  const base64 = Buffer.from(der).toString("base64").match(/.{1,64}/g)?.join("\n") ?? "";
  return `-----BEGIN ${label}-----\n${base64}\n-----END ${label}-----`;
}

async function appleEnv() {
  const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const privateKey = await crypto.subtle.exportKey("pkcs8", pair.privateKey);
  return {
    APPLE_APP_STORE_ISSUER_ID: "issuer-test",
    APPLE_APP_STORE_KEY_ID: "KEY123",
    APPLE_APP_STORE_PRIVATE_KEY_P8: pem("PRIVATE KEY", privateKey),
    APPLE_BUNDLE_ID: "com.lingoplay.app",
  };
}

async function googleEnv() {
  const pair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const privateKey = await crypto.subtle.exportKey("pkcs8", pair.privateKey);
  return {
    GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL: "billing-test@project.iam.gserviceaccount.com",
    GOOGLE_PLAY_PRIVATE_KEY_PEM: pem("PRIVATE KEY", privateKey),
    GOOGLE_PLAY_PACKAGE_NAME: "com.lingoplay.app",
  };
}

test("verification payload validation fails closed", () => {
  assert.deepEqual(validateEntitlementVerificationPayload({ platform: "apple", transactionId: "" }), {
    ok: false,
    error: "invalid_transaction_id",
  });
  assert.deepEqual(validateEntitlementVerificationPayload({ platform: "apple", transactionId: "123", environment: "xcode" }), {
    ok: false,
    error: "invalid_store_environment",
  });
  assert.deepEqual(validateEntitlementVerificationPayload({ platform: "google", purchaseToken: "bad token" }), {
    ok: false,
    error: "invalid_purchase_token",
  });
});

test("verification endpoint returns 503 when store credentials are absent", async () => {
  const response = await handleRequest(new Request("https://lingoplay.test/v1/entitlements/verify", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ platform: "apple", transactionId: "123456" }),
  }));
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "apple_billing_not_configured" });
});

test("Apple server verification grants only matching non-revoked unexpired Plus", async () => {
  const env = await appleEnv();
  const payload = {
    transactionId: "10000001",
    bundleId: "com.lingoplay.app",
    productId: "com.lingoplay.plus.monthly",
    expiresDate: NOW + 86_400_000,
    environment: "Production",
  };
  const result = await verifyAppleEntitlement(
    { platform: "apple", transactionId: "10000001", environment: "production" },
    env,
    NOW,
    async () => new Response(JSON.stringify({ signedTransactionInfo: fakeJws(payload) }), {
      status: 200,
      headers: { "content-type": "application/json" },
    }),
  );
  assert.equal(result.plan, "plus");
  assert.equal(result.authority, "server");
  assert.equal(result.productId, "com.lingoplay.plus.monthly");
  assert.equal(result.reason, "active");
});

test("Apple verification rejects expired, revoked, and mismatched transactions", async () => {
  const env = await appleEnv();
  const cases = [
    [{ bundleId: "com.lingoplay.app", productId: "com.lingoplay.plus.weekly", expiresDate: NOW - 1 }, "expired"],
    [{ bundleId: "com.lingoplay.app", productId: "com.lingoplay.plus.weekly", expiresDate: NOW + 1000, revocationDate: NOW - 50 }, "revoked"],
    [{ bundleId: "other.bundle", productId: "com.lingoplay.plus.weekly", expiresDate: NOW + 1000 }, "mismatch"],
    [{ bundleId: "com.lingoplay.app", productId: "other.product", expiresDate: NOW + 1000 }, "mismatch"],
  ] as const;

  for (const [payload, reason] of cases) {
    const result = await verifyAppleEntitlement(
      { platform: "apple", transactionId: "10000002", environment: "production" },
      env,
      NOW,
      async () => new Response(JSON.stringify({ signedTransactionInfo: fakeJws(payload) }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    assert.equal(result.plan, "free");
    assert.equal(result.reason, reason);
  }
});

test("Apple verification falls back from production to sandbox only when environment is unknown", async () => {
  const env = await appleEnv();
  const urls: string[] = [];
  const result = await verifyAppleEntitlement(
    { platform: "apple", transactionId: "sandbox-1" },
    env,
    NOW,
    async (input) => {
      const url = String(input);
      urls.push(url);
      if (url.includes("api.storekit.apple.com")) return new Response("{}", { status: 404 });
      return new Response(JSON.stringify({ signedTransactionInfo: fakeJws({
        bundleId: "com.lingoplay.app",
        productId: "com.lingoplay.plus.weekly",
        expiresDate: NOW + 100_000,
      }) }), { status: 200, headers: { "content-type": "application/json" } });
    },
  );
  assert.equal(urls.length, 2);
  assert.equal(result.plan, "plus");
});

test("Google server verification grants active and canceled-until-expiry subscriptions", async () => {
  const env = await googleEnv();
  for (const state of ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_CANCELED", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"]) {
    const result = await verifyGoogleEntitlement(
      { platform: "google", purchaseToken: `token-${state}` },
      env,
      NOW,
      async (input) => {
        const url = String(input);
        if (url === "https://oauth2.googleapis.com/token") {
          return new Response(JSON.stringify({ access_token: "oauth-test", token_type: "Bearer", expires_in: 3600 }), {
            status: 200,
            headers: { "content-type": "application/json" },
          });
        }
        assert.match(url, /applications\/com\.lingoplay\.app\/purchases\/subscriptionsv2\/tokens\//);
        return new Response(JSON.stringify({
          subscriptionState: state,
          lineItems: [{ productId: "com.lingoplay.plus.monthly", expiryTime: "2026-09-06T10:00:00Z" }],
        }), { status: 200, headers: { "content-type": "application/json" } });
      },
    );
    assert.equal(result.plan, "plus");
    assert.equal(result.reason, "active");
  }
});

test("Google verification rejects pending/on-hold, expired, and wrong products", async () => {
  const env = await googleEnv();
  const cases = [
    [{ subscriptionState: "SUBSCRIPTION_STATE_PENDING", lineItems: [{ productId: "com.lingoplay.plus.weekly", expiryTime: "2026-09-06T10:00:00Z" }] }, "not_active"],
    [{ subscriptionState: "SUBSCRIPTION_STATE_ON_HOLD", lineItems: [{ productId: "com.lingoplay.plus.weekly", expiryTime: "2026-09-06T10:00:00Z" }] }, "not_active"],
    [{ subscriptionState: "SUBSCRIPTION_STATE_ACTIVE", lineItems: [{ productId: "com.lingoplay.plus.weekly", expiryTime: "2026-09-05T09:59:59Z" }] }, "expired"],
    [{ subscriptionState: "SUBSCRIPTION_STATE_ACTIVE", lineItems: [{ productId: "other.product", expiryTime: "2026-09-06T10:00:00Z" }] }, "mismatch"],
  ] as const;

  for (const [purchase, reason] of cases) {
    const result = await verifyGoogleEntitlement(
      { platform: "google", purchaseToken: "token-test" },
      env,
      NOW,
      async (input) => String(input) === "https://oauth2.googleapis.com/token"
        ? new Response(JSON.stringify({ access_token: "oauth-test" }), { status: 200, headers: { "content-type": "application/json" } })
        : new Response(JSON.stringify(purchase), { status: 200, headers: { "content-type": "application/json" } }),
    );
    assert.equal(result.plan, "free");
    assert.equal(result.reason, reason);
  }
});

test("provider configuration errors remain explicit and fail closed", async () => {
  await assert.rejects(
    () => verifyGoogleEntitlement({ platform: "google", purchaseToken: "token" }, {}, NOW),
    BillingConfigurationError,
  );
});
