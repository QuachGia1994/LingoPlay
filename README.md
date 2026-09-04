# LingoPlay

LingoPlay is a consumer mobile app for translating and dubbing local video. Video/audio always stays on the phone. Translation is an explicit choice between transcript-only Cloud requests and optional Google ML Kit on-device models.

## Current implementation
- `ios/` — SwiftUI product flow with Photos Picker import, WhisperKit ASR/model acquisition, optional ML Kit offline translation through the official pinned CocoaPod, target-aware offline system TTS plus an optional verified Vietnamese neural voice pack, quality mix/remux/library, durable processing recovery, native AVPlayer PiP, local-only diagnostics, PrivacyInfo.xcprivacy, StoreKit 2 Plus pre-wiring, and release binary/settings audits in macOS CI.
- `android/` — Jetpack Compose mirror with app-owned Photo Picker import, sherpa-onnx Whisper ASR, resumable/checksummed Whisper Tiny acquisition, silence-aware bounded ASR, optional pinned ML Kit offline translation, target-aware offline system TTS plus an optional verified Vietnamese neural voice pack, normalized/soft-limited local mix, durable recovery, PiP, local-only diagnostics, and Google Play Billing 9.1.0 pre-wiring.
- `backend/` — Cloudflare Worker TypeScript boundary for health, transcript-only translation, and informational entitlement JSON. Media payloads are rejected by design.
- `docs/` — current product, architecture, and feature source of truth.

## Product boundary
This repository does not implement TikTok/YouTube/Douyin scraping, downloading, CAPTCHA bypass, or third-party media extraction. Import is from media the user already has as a local file.

## Run status
- Backend focused tests pass locally and keep the zero-media server boundary enforced.
- Android SDK/toolchain lives under `D:\LacViet\Android`; unit/lint/debug/release/AAB and 16 KB release gates are part of the local quality loop. GitHub Actions publishes `LingoPlay-Android-debug-apk` separately; release-test APK, AAB, and reports are separate artifacts so device testing does not require downloading one oversized bundle. Debug installs as `com.lingoplay.app.debug` and all development/device-test APKs use the committed public test identity for repeatable updates; that key must never sign a Play production artifact.
- iOS source is authored for SwiftUI/Swift 6. StoreKit Testing is configured locally; PrivacyInfo.xcprivacy, source-to-required-reason coverage, Release build settings, Mach-O size budgets, arm64, dSYM UUIDs, and PiP/background-audio project wiring are audited in macOS CI. Unsigned device-target builds remain verified there because this host is Windows.
- `scripts/verify_release_privacy.py` fails CI if the iOS required-reason privacy declarations, Android cleartext policy, or iOS privacy/PiP project wiring regress. `verify_ios_source_hardening.py`, `verify_ios_build_settings.py`, and `verify_ios_binary.py` add source, effective-Xcode-settings, and built-artifact release gates without unsafe linker/order-file guesses.
- Clean Background/source separation is intentionally unavailable in the current build; adaptive soundtrack ducking is not mislabeled as stem separation.
- Physical Android Stage 8 model-download smoke remains device-dependent; the MEIZU Lucky 08 can be reused when reconnected.

Optional neural-voice and offline-translation dependency/attribution details are recorded in `THIRD_PARTY_NOTICES.md`.

See `docs/index.md` for the canonical docs map.
