# Product

> updated 2026-09-05 · pre-release

## Positioning
LingoPlay is for consumers who want to understand foreign-language videos without learning editing software. The primary job is: import a local video, receive a Vietnamese dubbed version, and watch it immediately.

## Falsifiable USP
For supported devices, LingoPlay processes the video/audio media path locally and does not upload the user's video or audio to the backend; only compact translation and entitlement JSON crosses the network.

## Audience
Primary: mobile-first viewers consuming foreign-language entertainment, learning material, news, podcasts, and short-form video.

Secondary: language learners who benefit from bilingual subtitles and controllable original/dub audio blend.

## Revenue path
Free provides local import, explicit installable on-device Speech AI, Vietnamese offline system voices, quality-normalized balanced dub, standard playback/PiP, subtitles, recovery after interruption, and single-speaker fast dub. Stage 17 adds one optional Vietnamese neural voice pack. Stage 20 adds an optional Clean Background path backed by the pinned local sherpa-onnx Spleeter runtime/model; both remain explicit installs and are not bundled into the base app. Plus remains planned for validated natural Vietnamese voice choices, richer multi-speaker and dual-audio controls, smart speed, dictionary, summary, and advanced offline model/cache management.

Adaptive ducking alone must not be called source separation. `Clean Background` is off by default and is executable only when the local runtime plus checksum-verified Spleeter model are present. It routes the separated vocals stem to speech analysis and the accompaniment stem to mixing, then deletes both temporary stems. Cross-device quality/performance certification remains a physical-device gate.

Stage 9 pre-wires iOS StoreKit 2 only. Weekly/monthly local subscription products use reserved IDs `com.lingoplay.plus.weekly` and `com.lingoplay.plus.monthly`. Until an Apple Developer account and App Store Connect products exist, `Products.storekit` is the local test catalog; current pre-release capabilities remain usable when products are unavailable.

## Stage 19 pre-release behavior
Multi-speaker processing and local EN/ZH Voice Cloning are optional and off by default. Cloning requires ownership consent plus an eligible current-video reference in a supported source language; Resume rechecks current consent. Mixed or unsupported reference speech uses ordinary installed offline voices. Speaker labels identify clusters within the current run, not a verified person across videos. Longer dubbed speech may extend output duration beyond the original video track so the last words remain audible.

## Stage 20 pre-release behavior
Clean Background is a local-only, explicit opt-in. The model archive is pinned by exact size/SHA-256 and installed outside the base app. Resume preserves the immutable preference but recomputes stems from durable prepared audio instead of persisting a reusable stem library. Missing runtime/model fails closed; normal processing remains on the existing original-audio path when Clean Background is off.

## Product boundaries
- No third-party media scraping/downloading in store builds.
- No video/audio storage or processing on the backend.
- No client-embedded provider API secrets.
- Consumer controls only; model/runtime tuning remains automatic.
- No third-party analytics/crash SDK in the current release foundation; bounded diagnostics stay local and contain event codes only.
- Production network endpoints must use HTTPS; Android rejects cleartext traffic.

## Validation before paid implementation
The consumer flow should be testable end-to-end as: pick video → explicit Speech AI install if needed → local ASR → transcript-only translation → local Vietnamese TTS → mix/remux → Library/playback/export. The optional neural path adds a separate explicit 64 MiB download (~78 MiB installed), checksum validation, user selection, and system-voice fallback. Paid capability gating should remain narrow until physical-device neural-voice quality/performance and clean dub/background playback demonstrate user value. StoreKit 2 wiring may be tested locally before App Store Connect exists, but production entitlement authority requires later account identity and server-side App Store transaction verification.
