import assert from "node:assert/strict";
import test from "node:test";
import { handleRequest, validateTranslationPayload } from "../src/index.ts";

test("health declares the zero-media backend boundary", async () => {
  const response = await handleRequest(new Request("https://lingoplay.test/health"));
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true, service: "lingoplay-api", mediaUpload: false });
});

test("media payloads are rejected", async () => {
  const response = await handleRequest(new Request("https://lingoplay.test/v1/translate", {
    method: "POST",
    headers: { "content-type": "video/mp4" },
    body: "not-media-for-test",
  }));
  assert.equal(response.status, 415);
  assert.deepEqual(await response.json(), { error: "media_payloads_not_allowed" });
});

test("translation payload validates timestamps and text", () => {
  const result = validateTranslationPayload({
    sourceLanguage: "en",
    targetLanguage: "vi",
    segments: [{ id: "s1", startMs: 0, endMs: 2200, text: "Hello world" }],
  });
  assert.equal(result.ok, true);
});

test("duplicate segment ids are rejected", () => {
  const result = validateTranslationPayload({
    sourceLanguage: "en",
    targetLanguage: "vi",
    segments: [
      { id: "same", startMs: 0, endMs: 1000, text: "One" },
      { id: "same", startMs: 1000, endMs: 2000, text: "Two" },
    ],
  });
  assert.deepEqual(result, { ok: false, error: "invalid_segment_id" });
});

test("translation fails explicitly when provider secrets are absent", async () => {
  const response = await handleRequest(new Request("https://lingoplay.test/v1/translate", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      sourceLanguage: "en",
      targetLanguage: "vi",
      segments: [{ id: "s1", startMs: 0, endMs: 2200, text: "Hello world" }],
    }),
  }));
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "provider_not_configured" });
});

test("Workers AI translation preserves ids and sends text only", async () => {
  const calls: Array<{ model: string; text: string; source_lang: string; target_lang: string }> = [];
  const response = await handleRequest(new Request("https://lingoplay.test/v1/translate", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      sourceLanguage: "en",
      targetLanguage: "vi",
      segments: [
        { id: "s1", startMs: 0, endMs: 1400, text: "Hello world" },
        { id: "s2", startMs: 1400, endMs: 3100, text: "This is LingoPlay" },
      ],
    }),
  }), {
    AI: {
      run: async (model, input) => {
        calls.push({ model, ...input });
        return { translated_text: input.text === "Hello world" ? "Xin chào thế giới" : "Đây là LingoPlay" };
      },
    },
  });

  assert.equal(response.status, 200);
  const body = await response.json() as { translations: Array<{ id: string; text: string }> };
  assert.deepEqual(body.translations, [
    { id: "s1", text: "Xin chào thế giới" },
    { id: "s2", text: "Đây là LingoPlay" },
  ]);
  assert.deepEqual(calls, [
    { model: "@cf/meta/m2m100-1.2b", text: "Hello world", source_lang: "en", target_lang: "vi" },
    { model: "@cf/meta/m2m100-1.2b", text: "This is LingoPlay", source_lang: "en", target_lang: "vi" },
  ]);
});

test("Workers AI rejects unknown source language instead of assuming English", async () => {
  let called = false;
  const response = await handleRequest(new Request("https://lingoplay.test/v1/translate", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      sourceLanguage: "und",
      targetLanguage: "vi",
      segments: [{ id: "s1", startMs: 0, endMs: 2200, text: "Hello world" }],
    }),
  }), {
    AI: {
      run: async () => {
        called = true;
        return { translated_text: "should not run" };
      },
    },
  });

  assert.equal(response.status, 422);
  assert.deepEqual(await response.json(), { error: "source_language_unknown" });
  assert.equal(called, false);
});

test("translation proxy forwards transcript JSON only and preserves ids", async () => {
  const originalFetch = globalThis.fetch;
  let providerBody: unknown;
  globalThis.fetch = async (_input, init) => {
    providerBody = JSON.parse(String(init?.body));
    return new Response(JSON.stringify({
      translations: [
        { id: "s1", text: "Xin chào thế giới" },
        { id: "s2", text: "Đây là LingoPlay" },
      ],
    }), { status: 200, headers: { "content-type": "application/json" } });
  };

  try {
    const response = await handleRequest(new Request("https://lingoplay.test/v1/translate", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        sourceLanguage: "und",
        targetLanguage: "vi",
        segments: [
          { id: "s1", startMs: 0, endMs: 1400, text: "Hello world" },
          { id: "s2", startMs: 1400, endMs: 3100, text: "This is LingoPlay" },
        ],
      }),
    }), {
      TRANSLATION_PROVIDER_URL: "https://provider.test/translate",
      TRANSLATION_PROVIDER_KEY: "test-only",
    });

    assert.equal(response.status, 200);
    const body = await response.json() as { sourceLanguage: string; targetLanguage: string; translations: Array<{ id: string; text: string }> };
    assert.equal(body.sourceLanguage, "und");
    assert.equal(body.targetLanguage, "vi");
    assert.deepEqual(body.translations.map((item) => item.id), ["s1", "s2"]);
    assert.deepEqual(providerBody, {
      sourceLanguage: "und",
      targetLanguage: "vi",
      segments: [
        { id: "s1", startMs: 0, endMs: 1400, text: "Hello world" },
        { id: "s2", startMs: 1400, endMs: 3100, text: "This is LingoPlay" },
      ],
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("invalid provider response is rejected instead of fabricating translation", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({ translations: [] }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });

  try {
    const response = await handleRequest(new Request("https://lingoplay.test/v1/translate", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        sourceLanguage: "en",
        targetLanguage: "vi",
        segments: [{ id: "s1", startMs: 0, endMs: 2200, text: "Hello world" }],
      }),
    }), {
      TRANSLATION_PROVIDER_URL: "https://provider.test/translate",
      TRANSLATION_PROVIDER_KEY: "test-only",
    });
    assert.equal(response.status, 502);
    assert.deepEqual(await response.json(), { error: "provider_invalid_shape" });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("entitlements expose a minimal free capability set", async () => {
  const response = await handleRequest(new Request("https://lingoplay.test/v1/entitlements"));
  assert.equal(response.status, 200);
  const body = await response.json() as { plan: string; capabilities: Record<string, boolean> };
  assert.equal(body.plan, "free");
  assert.equal(body.capabilities.localImport, true);
  assert.equal(body.capabilities.cleanDub, false);
});
