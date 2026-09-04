# LingoPlay

LingoPlay is a consumer mobile app for translating and dubbing local video on-device. The video/audio media path stays on the phone; the backend receives only small JSON translation requests and entitlement metadata.

## Current implementation
- `ios/` — SwiftUI product flow with Photos Picker video import, WhisperKit on-device ASR, explicit Whisper Tiny acquisition, local Vietnamese TTS/mix/remux/library, and StoreKit 2 Plus pre-wiring with a local `Products.storekit` test catalog.
- `android/` — Jetpack Compose mirror with Photo Picker import, sherpa-onnx Whisper ASR, resumable/checksummed Whisper Tiny runtime-model acquisition, local Vietnamese TTS/mix/remux/library, and no Play Billing yet.
- `backend/` — Cloudflare Worker TypeScript boundary for health, transcript-only translation, and informational entitlement JSON. Media payloads are rejected by design.
- `docs/` — current product, architecture, and feature source of truth.

## Product boundary
This repository does not implement TikTok/YouTube/Douyin scraping, downloading, CAPTCHA bypass, or third-party media extraction. Import is from media the user already has as a local file.

## Run status
- Backend focused tests pass locally and keep the zero-media server boundary enforced.
- Android SDK/toolchain lives under `D:\LacViet\Android`; unit/lint/debug/release/AAB and 16 KB release gates are part of the local quality loop.
- iOS source is authored for SwiftUI/Swift 6. Stage 9 StoreKit Testing is configured through the generated Xcode Run scheme and does not require App Store Connect products; unsigned device-target builds remain verified by the macOS GitHub workflow because this host is Windows.
- Physical Android Stage 8 model-download smoke remains device-dependent; the MEIZU Lucky 08 can be reused when reconnected.

See `docs/index.md` for the canonical docs map.
