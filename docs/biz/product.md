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
Free provides local import, explicit installable on-device Speech AI, basic Vietnamese system voice, standard playback, subtitles, and single-speaker fast dub. Plus is planned for natural Vietnamese voices, multi-speaker mapping, clean-dub/source separation, background/PiP playback for local media, dual-audio controls, smart speed, dictionary, summary, and advanced offline model/cache management.

Stage 9 pre-wires iOS StoreKit 2 only. Weekly/monthly local subscription products use reserved IDs `com.lingoplay.plus.weekly` and `com.lingoplay.plus.monthly`. Until an Apple Developer account and App Store Connect products exist, `Products.storekit` is the local test catalog; current pre-release capabilities remain usable when products are unavailable.

## Product boundaries
- No third-party media scraping/downloading in store builds.
- No video/audio storage or processing on the backend.
- No client-embedded provider API secrets.
- Consumer controls only; model/runtime tuning remains automatic.

## Validation before paid implementation
The consumer flow should be testable end-to-end as: pick video → explicit Speech AI install if needed → local ASR → transcript-only translation → local Vietnamese TTS → mix/remux → Library/playback/export. Paid capability gating should remain narrow until natural voice/clean dub/background playback demonstrate user value. StoreKit 2 wiring may be tested locally before App Store Connect exists, but production entitlement authority requires later account identity and server-side App Store transaction verification.
