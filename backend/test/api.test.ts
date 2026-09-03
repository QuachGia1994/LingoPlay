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

test("entitlements expose a minimal free capability set", async () => {
  const response = await handleRequest(new Request("https://lingoplay.test/v1/entitlements"));
  assert.equal(response.status, 200);
  const body = await response.json() as { plan: string; capabilities: Record<string, boolean> };
  assert.equal(body.plan, "free");
  assert.equal(body.capabilities.localImport, true);
  assert.equal(body.capabilities.cleanDub, false);
});
