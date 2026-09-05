export const PLUS_PRODUCT_IDS = [
  "com.lingoplay.plus.weekly",
  "com.lingoplay.plus.monthly",
] as const;

export type PlusProductId = (typeof PLUS_PRODUCT_IDS)[number];
export type BillingPlatform = "apple" | "google";
export type StoreEnvironment = "production" | "sandbox";

export interface BillingEnv {
  APPLE_APP_STORE_ISSUER_ID?: string;
  APPLE_APP_STORE_KEY_ID?: string;
  APPLE_APP_STORE_PRIVATE_KEY_P8?: string;
  APPLE_BUNDLE_ID?: string;
  GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL?: string;
  GOOGLE_PLAY_PRIVATE_KEY_PEM?: string;
  GOOGLE_PLAY_PACKAGE_NAME?: string;
}

export type EntitlementVerificationRequest =
  | {
      platform: "apple";
      transactionId: string;
      environment?: StoreEnvironment;
    }
  | {
      platform: "google";
      purchaseToken: string;
    };

export interface EntitlementVerificationResponse {
  plan: "free" | "plus";
  authority: "server";
  platform: BillingPlatform;
  productId?: PlusProductId;
  expiresAt?: string;
  reason: "active" | "expired" | "revoked" | "not_found" | "mismatch" | "not_active";
  verifiedAt: string;
}

export type EntitlementValidation =
  | { ok: true; data: EntitlementVerificationRequest }
  | { ok: false; error: string };

export class BillingConfigurationError extends Error {}
export class BillingProviderError extends Error {
  readonly status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.status = status;
  }
}

interface AppleTransactionPayload {
  bundleId?: string;
  environment?: string;
  expiresDate?: number;
  productId?: string;
  revocationDate?: number;
  transactionId?: string;
}

interface GoogleSubscriptionLineItem {
  productId?: string;
  expiryTime?: string;
}

interface GoogleSubscriptionPurchaseV2 {
  subscriptionState?: string;
  lineItems?: GoogleSubscriptionLineItem[];
}

interface GoogleOAuthTokenResponse {
  access_token?: string;
  expires_in?: number;
  token_type?: string;
}

const APP_STORE_AUDIENCE = "appstoreconnect-v1";
const GOOGLE_OAUTH_AUDIENCE = "https://oauth2.googleapis.com/token";
const GOOGLE_ANDROID_PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const APPLE_PRODUCTION_BASE = "https://api.storekit.apple.com";
const APPLE_SANDBOX_BASE = "https://api.storekit-sandbox.apple.com";
const GOOGLE_PUBLISHER_BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3";
const MAX_TOKEN_LENGTH = 4096;
const MAX_TRANSACTION_ID_LENGTH = 128;

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isPlusProductId(value: unknown): value is PlusProductId {
  return typeof value === "string" && (PLUS_PRODUCT_IDS as readonly string[]).includes(value);
}

export function validateEntitlementVerificationPayload(value: unknown): EntitlementValidation {
  if (!isObject(value)) return { ok: false, error: "body_must_be_object" };
  if (value.platform === "apple") {
    if (
      typeof value.transactionId !== "string" ||
      value.transactionId.length === 0 ||
      value.transactionId.length > MAX_TRANSACTION_ID_LENGTH ||
      !/^[A-Za-z0-9._-]+$/.test(value.transactionId)
    ) {
      return { ok: false, error: "invalid_transaction_id" };
    }
    if (
      value.environment !== undefined &&
      value.environment !== "production" &&
      value.environment !== "sandbox"
    ) {
      return { ok: false, error: "invalid_store_environment" };
    }
    return {
      ok: true,
      data: {
        platform: "apple",
        transactionId: value.transactionId,
        environment: value.environment,
      },
    };
  }

  if (value.platform === "google") {
    if (
      typeof value.purchaseToken !== "string" ||
      value.purchaseToken.length === 0 ||
      value.purchaseToken.length > MAX_TOKEN_LENGTH ||
      /\s/.test(value.purchaseToken)
    ) {
      return { ok: false, error: "invalid_purchase_token" };
    }
    return { ok: true, data: { platform: "google", purchaseToken: value.purchaseToken } };
  }

  return { ok: false, error: "invalid_billing_platform" };
}

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, Math.min(bytes.length, index + 0x8000)));
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function utf8Base64Url(value: string): string {
  return bytesToBase64Url(new TextEncoder().encode(value));
}

function base64UrlToUtf8(value: string): string {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

function pemToPkcs8(pem: string): ArrayBuffer {
  const normalized = pem
    .replace(/-----BEGIN [^-]+-----/g, "")
    .replace(/-----END [^-]+-----/g, "")
    .replace(/\\n/g, "\n")
    .replace(/\s+/g, "");
  if (!normalized) throw new BillingConfigurationError("billing_private_key_missing");
  let binary: string;
  try {
    binary = atob(normalized);
  } catch {
    throw new BillingConfigurationError("billing_private_key_invalid");
  }
  return Uint8Array.from(binary, (character) => character.charCodeAt(0)).buffer;
}

async function signJwt(
  algorithm: "ES256" | "RS256",
  keyId: string | undefined,
  payload: Record<string, unknown>,
  privateKeyPem: string,
): Promise<string> {
  const header: Record<string, string> = { alg: algorithm, typ: "JWT" };
  if (keyId) header.kid = keyId;
  const encodedHeader = utf8Base64Url(JSON.stringify(header));
  const encodedPayload = utf8Base64Url(JSON.stringify(payload));
  const signingInput = `${encodedHeader}.${encodedPayload}`;
  const keyData = pemToPkcs8(privateKeyPem);

  const key = algorithm === "ES256"
    ? await crypto.subtle.importKey(
        "pkcs8",
        keyData,
        { name: "ECDSA", namedCurve: "P-256" },
        false,
        ["sign"],
      )
    : await crypto.subtle.importKey(
        "pkcs8",
        keyData,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["sign"],
      );

  const signature = algorithm === "ES256"
    ? await crypto.subtle.sign(
        { name: "ECDSA", hash: "SHA-256" },
        key,
        new TextEncoder().encode(signingInput),
      )
    : await crypto.subtle.sign(
        "RSASSA-PKCS1-v1_5",
        key,
        new TextEncoder().encode(signingInput),
      );

  return `${signingInput}.${bytesToBase64Url(new Uint8Array(signature))}`;
}

function decodeJwsPayload<T>(signedJws: string): T {
  const parts = signedJws.split(".");
  if (parts.length !== 3 || parts[1].length === 0) throw new BillingProviderError("provider_invalid_jws");
  try {
    return JSON.parse(base64UrlToUtf8(parts[1])) as T;
  } catch {
    throw new BillingProviderError("provider_invalid_jws_payload");
  }
}

function requiredAppleConfig(env: BillingEnv): {
  issuerId: string;
  keyId: string;
  privateKey: string;
  bundleId: string;
} {
  const issuerId = env.APPLE_APP_STORE_ISSUER_ID?.trim();
  const keyId = env.APPLE_APP_STORE_KEY_ID?.trim();
  const privateKey = env.APPLE_APP_STORE_PRIVATE_KEY_P8?.trim();
  const bundleId = env.APPLE_BUNDLE_ID?.trim();
  if (!issuerId || !keyId || !privateKey || !bundleId) {
    throw new BillingConfigurationError("apple_billing_not_configured");
  }
  return { issuerId, keyId, privateKey, bundleId };
}

async function appleAuthorizationToken(env: BillingEnv, nowMs: number): Promise<string> {
  const config = requiredAppleConfig(env);
  const issuedAt = Math.floor(nowMs / 1000);
  return signJwt(
    "ES256",
    config.keyId,
    {
      iss: config.issuerId,
      iat: issuedAt,
      exp: issuedAt + 300,
      aud: APP_STORE_AUDIENCE,
      bid: config.bundleId,
    },
    config.privateKey,
  );
}

async function fetchAppleTransaction(
  transactionId: string,
  environment: StoreEnvironment,
  env: BillingEnv,
  nowMs: number,
  fetchImpl: typeof fetch,
): Promise<{ status: "found"; payload: AppleTransactionPayload } | { status: "not_found" }> {
  const token = await appleAuthorizationToken(env, nowMs);
  const base = environment === "sandbox" ? APPLE_SANDBOX_BASE : APPLE_PRODUCTION_BASE;
  let response: Response;
  try {
    response = await fetchImpl(`${base}/inApps/v1/transactions/${encodeURIComponent(transactionId)}`, {
      headers: { authorization: `Bearer ${token}` },
    });
  } catch {
    throw new BillingProviderError("apple_provider_unreachable");
  }

  if (response.status === 404) return { status: "not_found" };
  if (!response.ok) throw new BillingProviderError("apple_provider_failed", response.status);

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new BillingProviderError("apple_provider_invalid_json", response.status);
  }
  if (!isObject(body) || typeof body.signedTransactionInfo !== "string") {
    throw new BillingProviderError("apple_provider_invalid_shape", response.status);
  }
  return { status: "found", payload: decodeJwsPayload<AppleTransactionPayload>(body.signedTransactionInfo) };
}

export async function verifyAppleEntitlement(
  request: Extract<EntitlementVerificationRequest, { platform: "apple" }>,
  env: BillingEnv,
  nowMs = Date.now(),
  fetchImpl: typeof fetch = fetch,
): Promise<EntitlementVerificationResponse> {
  const config = requiredAppleConfig(env);
  const environments: StoreEnvironment[] = request.environment
    ? [request.environment]
    : ["production", "sandbox"];
  let payload: AppleTransactionPayload | undefined;

  for (const environment of environments) {
    const result = await fetchAppleTransaction(request.transactionId, environment, env, nowMs, fetchImpl);
    if (result.status === "found") {
      payload = result.payload;
      break;
    }
  }

  const verifiedAt = new Date(nowMs).toISOString();
  if (!payload) {
    return { plan: "free", authority: "server", platform: "apple", reason: "not_found", verifiedAt };
  }
  if (payload.bundleId !== config.bundleId || !isPlusProductId(payload.productId)) {
    return { plan: "free", authority: "server", platform: "apple", reason: "mismatch", verifiedAt };
  }
  if (payload.revocationDate !== undefined && payload.revocationDate !== null) {
    return {
      plan: "free",
      authority: "server",
      platform: "apple",
      productId: payload.productId,
      reason: "revoked",
      verifiedAt,
    };
  }
  if (typeof payload.expiresDate !== "number" || !Number.isFinite(payload.expiresDate) || payload.expiresDate <= nowMs) {
    return {
      plan: "free",
      authority: "server",
      platform: "apple",
      productId: payload.productId,
      reason: "expired",
      verifiedAt,
    };
  }

  return {
    plan: "plus",
    authority: "server",
    platform: "apple",
    productId: payload.productId,
    expiresAt: new Date(payload.expiresDate).toISOString(),
    reason: "active",
    verifiedAt,
  };
}

function requiredGoogleConfig(env: BillingEnv): {
  email: string;
  privateKey: string;
  packageName: string;
} {
  const email = env.GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL?.trim();
  const privateKey = env.GOOGLE_PLAY_PRIVATE_KEY_PEM?.trim();
  const packageName = env.GOOGLE_PLAY_PACKAGE_NAME?.trim();
  if (!email || !privateKey || !packageName) {
    throw new BillingConfigurationError("google_billing_not_configured");
  }
  return { email, privateKey, packageName };
}

async function googleAccessToken(
  env: BillingEnv,
  nowMs: number,
  fetchImpl: typeof fetch,
): Promise<string> {
  const config = requiredGoogleConfig(env);
  const issuedAt = Math.floor(nowMs / 1000);
  const assertion = await signJwt(
    "RS256",
    undefined,
    {
      iss: config.email,
      scope: GOOGLE_ANDROID_PUBLISHER_SCOPE,
      aud: GOOGLE_OAUTH_AUDIENCE,
      iat: issuedAt,
      exp: issuedAt + 3600,
    },
    config.privateKey,
  );

  let response: Response;
  try {
    response = await fetchImpl(GOOGLE_OAUTH_AUDIENCE, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }).toString(),
    });
  } catch {
    throw new BillingProviderError("google_oauth_unreachable");
  }
  if (!response.ok) throw new BillingProviderError("google_oauth_failed", response.status);

  let body: GoogleOAuthTokenResponse;
  try {
    body = await response.json() as GoogleOAuthTokenResponse;
  } catch {
    throw new BillingProviderError("google_oauth_invalid_json", response.status);
  }
  if (typeof body.access_token !== "string" || body.access_token.length === 0) {
    throw new BillingProviderError("google_oauth_invalid_shape", response.status);
  }
  return body.access_token;
}

export async function verifyGoogleEntitlement(
  request: Extract<EntitlementVerificationRequest, { platform: "google" }>,
  env: BillingEnv,
  nowMs = Date.now(),
  fetchImpl: typeof fetch = fetch,
): Promise<EntitlementVerificationResponse> {
  const config = requiredGoogleConfig(env);
  const accessToken = await googleAccessToken(env, nowMs, fetchImpl);
  let response: Response;
  try {
    response = await fetchImpl(
      `${GOOGLE_PUBLISHER_BASE}/applications/${encodeURIComponent(config.packageName)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(request.purchaseToken)}`,
      { headers: { authorization: `Bearer ${accessToken}` } },
    );
  } catch {
    throw new BillingProviderError("google_provider_unreachable");
  }

  const verifiedAt = new Date(nowMs).toISOString();
  if (response.status === 404) {
    return { plan: "free", authority: "server", platform: "google", reason: "not_found", verifiedAt };
  }
  if (!response.ok) throw new BillingProviderError("google_provider_failed", response.status);

  let purchase: GoogleSubscriptionPurchaseV2;
  try {
    purchase = await response.json() as GoogleSubscriptionPurchaseV2;
  } catch {
    throw new BillingProviderError("google_provider_invalid_json", response.status);
  }

  const activeStates = new Set([
    "SUBSCRIPTION_STATE_ACTIVE",
    "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
    "SUBSCRIPTION_STATE_CANCELED",
  ]);
  const eligibleItems = (purchase.lineItems ?? [])
    .filter((item): item is GoogleSubscriptionLineItem & { productId: PlusProductId; expiryTime: string } =>
      isPlusProductId(item.productId) && typeof item.expiryTime === "string",
    )
    .map((item) => ({ ...item, expiryMs: Date.parse(item.expiryTime) }))
    .filter((item) => Number.isFinite(item.expiryMs));

  if (eligibleItems.length === 0) {
    return { plan: "free", authority: "server", platform: "google", reason: "mismatch", verifiedAt };
  }
  const latest = eligibleItems.reduce((best, item) => item.expiryMs > best.expiryMs ? item : best);
  if (!activeStates.has(purchase.subscriptionState ?? "")) {
    return {
      plan: "free",
      authority: "server",
      platform: "google",
      productId: latest.productId,
      expiresAt: new Date(latest.expiryMs).toISOString(),
      reason: "not_active",
      verifiedAt,
    };
  }
  if (latest.expiryMs <= nowMs) {
    return {
      plan: "free",
      authority: "server",
      platform: "google",
      productId: latest.productId,
      expiresAt: new Date(latest.expiryMs).toISOString(),
      reason: "expired",
      verifiedAt,
    };
  }

  return {
    plan: "plus",
    authority: "server",
    platform: "google",
    productId: latest.productId,
    expiresAt: new Date(latest.expiryMs).toISOString(),
    reason: "active",
    verifiedAt,
  };
}

export async function verifyEntitlement(
  request: EntitlementVerificationRequest,
  env: BillingEnv,
  nowMs = Date.now(),
  fetchImpl: typeof fetch = fetch,
): Promise<EntitlementVerificationResponse> {
  return request.platform === "apple"
    ? verifyAppleEntitlement(request, env, nowMs, fetchImpl)
    : verifyGoogleEntitlement(request, env, nowMs, fetchImpl);
}
