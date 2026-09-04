# Product

> updated 2026-09-04 · pre-release

## Positioning
LingoPlay is for consumers who want to understand foreign-language videos without learning editing software. The primary job is: import a local video, receive a Vietnamese dubbed version, and watch it immediately.

## Falsifiable USP
For supported devices, LingoPlay processes the video/audio media path locally and does not upload the user's video or audio to the backend; only compact translation and entitlement JSON crosses the network.

## Audience
Primary: mobile-first viewers consuming foreign-language entertainment, learning material, news, podcasts, and short-form video.

Secondary: language learners who benefit from bilingual subtitles and controllable original/dub audio blend.

## Revenue path
Free provides local import, explicit installable on-device Speech AI, Vietnamese offline system voices, quality-normalized balanced dub, standard playback/PiP, subtitles, recovery after interruption, and single-speaker fast dub. Stage 17 adds one optional Vietnamese neural voice pack to the pre-release build for explicit install and physical validation; it is not yet marketed as premium or natural. Plus remains planned for validated natural Vietnamese voice choices, multi-speaker mapping, a real verified clean-background/source-separation engine, richer dual-audio controls, smart speed, dictionary, summary, and advanced offline model/cache management.

The current product must not call adaptive ducking “clean dub” or “source separation”. `Clean Background` stays visibly unavailable until a real separator runtime is integrated and verified on both native clients.

Stage 9 pre-wires iOS StoreKit 2 only. Weekly/monthly local subscription products use reserved IDs `com.lingoplay.plus.weekly` and `com.lingoplay.plus.monthly`. Until an Apple Developer account and App Store Connect products exist, `Products.storekit` is the local test catalog; current pre-release capabilities remain usable when products are unavailable.

## Product boundaries
- No third-party media scraping/downloading in store builds.
- No video/audio storage or processing on the backend.
- No client-embedded provider API secrets.
- Consumer controls only; model/runtime tuning remains automatic.
- No third-party analytics/crash SDK in the current release foundation; bounded diagnostics stay local and contain event codes only.
- Production network endpoints must use HTTPS; Android rejects cleartext traffic.

## Validation before paid implementation
The consumer flow should be testable end-to-end as: pick video → explicit Speech AI install if needed → local ASR → transcript-only translation → local Vietnamese TTS → mix/remux → Library/playback/export. The optional neural path adds a separate explicit 64 MiB download (~78 MiB installed), checksum validation, user selection, and system-voice fallback. Paid capability gating should remain narrow until physical-device neural-voice quality/performance and clean dub/background playback demonstrate user value. StoreKit 2 wiring may be tested locally before App Store Connect exists, but production entitlement authority requires later account identity and server-side App Store transaction verification.
