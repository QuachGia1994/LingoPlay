# LingoPlay

LingoPlay is a consumer mobile app for translating and dubbing local video on-device. The video/audio media path stays on the phone; the backend receives only small JSON translation requests and entitlement metadata.

## Current implementation
- `ios/` — SwiftUI product flow with Photos Picker import, WhisperKit ASR/model acquisition, local Vietnamese TTS/quality mix/remux/library, durable processing recovery, native AVPlayer PiP, local-only diagnostics, PrivacyInfo.xcprivacy, and StoreKit 2 Plus pre-wiring.
- `android/` — Jetpack Compose mirror with app-owned Photo Picker import, sherpa-onnx Whisper ASR, resumable/checksummed Whisper Tiny acquisition, silence-aware bounded ASR, normalized/soft-limited local mix, durable recovery, PiP, local-only diagnostics, and no Play Billing yet.
- `backend/` — Cloudflare Worker TypeScript boundary for health, transcript-only translation, and informational entitlement JSON. Media payloads are rejected by design.
- `docs/` — current product, architecture, and feature source of truth.

## Product boundary
This repository does not implement TikTok/YouTube/Douyin scraping, downloading, CAPTCHA bypass, or third-party media extraction. Import is from media the user already has as a local file.

## Run status
- Backend focused tests pass locally and keep the zero-media server boundary enforced.
- Android SDK/toolchain lives under `D:\LacViet\Android`; unit/lint/debug/release/AAB and 16 KB release gates are part of the local quality loop. GitHub Actions publishes `LingoPlay-Android-debug-apk` separately; release-test APK, AAB, and reports are separate artifacts so device testing does not require downloading one oversized bundle.
- iOS source is authored for SwiftUI/Swift 6. StoreKit Testing is configured locally; PrivacyInfo.xcprivacy and PiP/background-audio project wiring are release-verified in CI. Unsigned device-target builds remain verified by the macOS GitHub workflow because this host is Windows.
- `scripts/verify_release_privacy.py` fails CI if the iOS required-reason privacy declarations, Android cleartext policy, or iOS privacy/PiP project wiring regress.
- Clean Background/source separation is intentionally unavailable in the current build; adaptive soundtrack ducking is not mislabeled as stem separation.
- Physical Android Stage 8 model-download smoke remains device-dependent; the MEIZU Lucky 08 can be reused when reconnected.

See `docs/index.md` for the canonical docs map.
