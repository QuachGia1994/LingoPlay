export interface WorkersAITranslationInput {
  text: string;
  source_lang: string;
  target_lang: string;
}

export interface WorkersAI {
  run(model: string, input: WorkersAITranslationInput): Promise<unknown>;
}

export interface Env {
  AI?: WorkersAI;
  // Legacy provider proxy remains supported for self-hosted deployments.
  TRANSLATION_PROVIDER_URL?: string;
  TRANSLATION_PROVIDER_KEY?: string;
}

export interface TranslationSegment {
  id: string;
  startMs: number;
  endMs: number;
  text: string;
}

export interface TranslationRequest {
  sourceLanguage: string;
  targetLanguage: string;
  segments: TranslationSegment[];
}

interface ProviderTranslation {
  id: string;
  text: string;
}

interface ProviderResponse {
  translations: ProviderTranslation[];
}

const MAX_BODY_BYTES = 64 * 1024;
const MAX_SEGMENTS = 240;
const MAX_TOTAL_TEXT_CHARS = 24_000;
const MAX_SEGMENT_TEXT_CHARS = 2_000;

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isLanguageCode(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})?$/.test(value);
}

export function validateTranslationPayload(value: unknown): { ok: true; data: TranslationRequest } | { ok: false; error: string } {
  if (!isObject(value)) return { ok: false, error: "body_must_be_object" };
  if (!isLanguageCode(value.sourceLanguage)) return { ok: false, error: "invalid_source_language" };
  if (!isLanguageCode(value.targetLanguage)) return { ok: false, error: "invalid_target_language" };
  if (!Array.isArray(value.segments) || value.segments.length === 0) return { ok: false, error: "segments_required" };
  if (value.segments.length > MAX_SEGMENTS) return { ok: false, error: "too_many_segments" };

  const ids = new Set<string>();
  const segments: TranslationSegment[] = [];
  let totalTextChars = 0;

  for (const raw of value.segments) {
    if (!isObject(raw)) return { ok: false, error: "invalid_segment" };
    if (typeof raw.id !== "string" || raw.id.length === 0 || raw.id.length > 64 || ids.has(raw.id)) return { ok: false, error: "invalid_segment_id" };
    if (!Number.isFinite(raw.startMs) || !Number.isFinite(raw.endMs)) return { ok: false, error: "invalid_segment_timing" };

    const startMs = Number(raw.startMs);
    const endMs = Number(raw.endMs);
    if (startMs < 0 || endMs <= startMs) return { ok: false, error: "invalid_segment_timing" };
    if (typeof raw.text !== "string") return { ok: false, error: "invalid_segment_text" };

    const text = raw.text.trim();
    if (text.length === 0 || text.length > MAX_SEGMENT_TEXT_CHARS) return { ok: false, error: "invalid_segment_text" };

    totalTextChars += text.length;
    if (totalTextChars > MAX_TOTAL_TEXT_CHARS) return { ok: false, error: "text_limit_exceeded" };

    ids.add(raw.id);
    segments.push({ id: raw.id, startMs, endMs, text });
  }

  return {
    ok: true,
    data: {
      sourceLanguage: value.sourceLanguage,
      targetLanguage: value.targetLanguage,
      segments,
    },
  };
}

function isForbiddenMediaContentType(contentType: string): boolean {
  const normalized = contentType.toLowerCase();
  return normalized.startsWith("video/") || normalized.startsWith("audio/") || normalized.startsWith("multipart/") || normalized === "application/octet-stream";
}

async function parseJsonBody(request: Request): Promise<{ ok: true; value: unknown } | { ok: false; response: Response }> {
  const contentType = request.headers.get("content-type") ?? "";
  if (isForbiddenMediaContentType(contentType)) return { ok: false, response: json({ error: "media_payloads_not_allowed" }, 415) };
  if (!contentType.toLowerCase().startsWith("application/json")) return { ok: false, response: json({ error: "application_json_required" }, 415) };

  const body = await request.text();
  if (new TextEncoder().encode(body).byteLength > MAX_BODY_BYTES) return { ok: false, response: json({ error: "payload_too_large" }, 413) };

  try {
    return { ok: true, value: JSON.parse(body) };
  } catch {
    return { ok: false, response: json({ error: "invalid_json" }, 400) };
  }
}

function sanitizeSpeechText(value: string): string {
  return value
    .replace(/<[^>\r\n]{1,96}>/g, " ")
    .replace(/\[[^\r\n\]]{1,96}\]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function normalizedBaseLanguage(value: string): string {
  return value.trim().toLowerCase().split("-")[0] || "und";
}

function resolvedSourceLanguage(request: TranslationRequest): string {
  const reported = normalizedBaseLanguage(request.sourceLanguage);
  const text = sanitizeSpeechText(request.segments.map((segment) => segment.text).join(" "));
  const letters = Array.from(text).filter((character) => /\p{L}/u.test(character));
  const latinLetters = letters.filter((character) => /[A-Za-z]/.test(character)).length;
  const commonEnglish = new Set([
    "a", "and", "are", "can", "do", "have", "how", "i", "in", "is", "it",
    "me", "of", "please", "that", "the", "this", "to", "we", "what", "you",
  ]);
  const englishHits = (text.toLowerCase().match(/[a-z]+(?:'[a-z]+)?/g) ?? [])
    .filter((word) => commonEnglish.has(word)).length;
  const stronglyEnglish = latinLetters >= 20 &&
    letters.length > 0 &&
    latinLetters / letters.length >= 0.75 &&
    englishHits >= 2;
  return stronglyEnglish ? "en" : reported;
}

function translatedTextFromWorkersAI(value: unknown): string | null {
  if (!isObject(value) || typeof value.translated_text !== "string") return null;
  const text = sanitizeSpeechText(value.translated_text);
  return text.length > 0 ? text : null;
}

async function translateWithWorkersAI(request: TranslationRequest, ai: WorkersAI): Promise<ProviderResponse> {
  const sourceLanguage = resolvedSourceLanguage(request);
  const targetLanguage = normalizedBaseLanguage(request.targetLanguage);
  if (sourceLanguage === "und") throw new Error("source_language_unknown");
  if (targetLanguage === "und") throw new Error("target_language_unknown");

  const translations: ProviderTranslation[] = [];
  const concurrency = 8;
  for (let offset = 0; offset < request.segments.length; offset += concurrency) {
    const page = request.segments.slice(offset, offset + concurrency);
    const pageTranslations = await Promise.all(page.map(async (segment) => {
      const sourceText = sanitizeSpeechText(segment.text);
      if (!sourceText) throw new Error("source_text_empty");
      const response = await ai.run("@cf/meta/m2m100-1.2b", {
        text: sourceText,
        source_lang: sourceLanguage,
        target_lang: targetLanguage,
      });
      const text = translatedTextFromWorkersAI(response);
      if (!text) throw new Error("provider_invalid_shape");
      return { id: segment.id, text };
    }));
    translations.push(...pageTranslations);
  }

  return { translations };
}

function validateProviderResponse(value: unknown, request: TranslationRequest): ProviderResponse | null {
  if (!isObject(value) || !Array.isArray(value.translations) || value.translations.length !== request.segments.length) return null;
  const expectedIds = new Set(request.segments.map((segment) => segment.id));
  const translations: ProviderTranslation[] = [];

  for (const raw of value.translations) {
    if (!isObject(raw) || typeof raw.id !== "string" || !expectedIds.has(raw.id) || typeof raw.text !== "string") return null;
    const text = raw.text.trim();
    if (text.length === 0 || text.length > MAX_SEGMENT_TEXT_CHARS * 2) return null;
    expectedIds.delete(raw.id);
    translations.push({ id: raw.id, text });
  }

  if (expectedIds.size !== 0) return null;
  return { translations };
}

async function translate(request: Request, env: Env): Promise<Response> {
  const parsed = await parseJsonBody(request);
  if (!parsed.ok) return parsed.response;

  const validated = validateTranslationPayload(parsed.value);
  if (!validated.ok) return json({ error: validated.error }, 400);

  let normalized: ProviderResponse | null = null;
  const resolvedSource = resolvedSourceLanguage(validated.data);
  const normalizedTarget = normalizedBaseLanguage(validated.data.targetLanguage);

  if (resolvedSource !== "und" && resolvedSource === normalizedTarget) {
    const translations: ProviderTranslation[] = [];
    for (const segment of validated.data.segments) {
      const text = sanitizeSpeechText(segment.text);
      if (!text) return json({ error: "source_text_empty" }, 422);
      translations.push({ id: segment.id, text });
    }
    normalized = { translations };
  } else if (env.AI) {
    try {
      normalized = await translateWithWorkersAI(validated.data, env.AI);
    } catch (error) {
      const code = error instanceof Error ? error.message : "provider_failed";
      if (code === "source_language_unknown" || code === "target_language_unknown" || code === "source_text_empty") return json({ error: code }, 422);
      if (code === "provider_invalid_shape") return json({ error: code }, 502);
      return json({ error: "provider_unreachable" }, 502);
    }
  } else if (env.TRANSLATION_PROVIDER_URL && env.TRANSLATION_PROVIDER_KEY) {
    let providerResponse: Response;
    try {
      providerResponse = await fetch(env.TRANSLATION_PROVIDER_URL, {
        method: "POST",
        headers: {
          authorization: `Bearer ${env.TRANSLATION_PROVIDER_KEY}`,
          "content-type": "application/json",
        },
        body: JSON.stringify(validated.data),
      });
    } catch {
      return json({ error: "provider_unreachable" }, 502);
    }

    if (!providerResponse.ok) return json({ error: "provider_failed", status: providerResponse.status }, 502);

    let providerJson: unknown;
    try {
      providerJson = await providerResponse.json();
    } catch {
      return json({ error: "provider_invalid_json" }, 502);
    }

    normalized = validateProviderResponse(providerJson, validated.data);
  } else {
    return json({ error: "provider_not_configured" }, 503);
  }

  if (!normalized) return json({ error: "provider_invalid_shape" }, 502);

  return json({
    sourceLanguage: validated.data.sourceLanguage,
    targetLanguage: validated.data.targetLanguage,
    translations: normalized.translations,
  });
}

export async function handleRequest(request: Request, env: Env = {}): Promise<Response> {
  const url = new URL(request.url);

  if (request.method === "GET" && url.pathname === "/health") {
    return json({ ok: true, service: "lingoplay-api", mediaUpload: false });
  }

  if (request.method === "GET" && url.pathname === "/v1/entitlements") {
    return json({
      plan: "free",
      capabilities: {
        localImport: true,
        basicVietnameseVoice: true,
        bilingualSubtitles: true,
        naturalVoice: false,
        cleanDub: false,
        backgroundPlayback: false,
        smartSpeed: false,
      },
    });
  }

  if (request.method === "POST" && url.pathname === "/v1/translate") return translate(request, env);

  if (request.method === "POST" || request.method === "PUT" || request.method === "PATCH") {
    const contentType = request.headers.get("content-type") ?? "";
    if (isForbiddenMediaContentType(contentType)) return json({ error: "media_payloads_not_allowed" }, 415);
  }

  return json({ error: "not_found" }, 404);
}

export default {
  fetch(request: Request, env: Env): Promise<Response> {
    return handleRequest(request, env);
  },
};
