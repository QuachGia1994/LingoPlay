# LingoPlay

LingoPlay is a consumer mobile app for translating and dubbing local video. Video/audio always stays on the phone. Translation is an explicit choice between transcript-only Cloud requests and optional Google ML Kit on-device models.

## Current implementation
- `ios/` — SwiftUI product flow with Photos Picker import, WhisperKit ASR/model acquisition, optional ML Kit offline translation through the official pinned CocoaPod, target-aware offline system TTS plus an optional verified Vietnamese neural voice pack, optional local Spleeter Clean Background separation, quality mix/remux/library, durable processing recovery, native AVPlayer PiP, local-only diagnostics, PrivacyInfo.xcprivacy, StoreKit 2 purchase handling with server-authoritative Release entitlement verification, DEBUG-only Xcode StoreKit Testing, and release binary/settings audits in macOS CI.
- `android/` — Jetpack Compose mirror with app-owned Photo Picker import, sherpa-onnx Whisper ASR, resumable/checksummed Whisper Tiny acquisition, silence-aware bounded ASR, optional pinned ML Kit offline translation, target-aware offline system TTS plus an optional verified Vietnamese neural voice pack, optional local Spleeter Clean Background separation through an app-owned JNI bridge to the pinned sherpa-onnx C API, normalized/soft-limited local mix, durable recovery, PiP, local-only diagnostics, and Google Play Billing 9.1.0 with server-authoritative purchase-token verification before Plus unlock/acknowledgement.
- `backend/` — Cloudflare Worker TypeScript boundary for health, transcript-only translation, free capability discovery, and fail-closed Apple/Google server entitlement verification. Media payloads are rejected by design; store-provider credentials remain Worker secrets.
- `docs/` — current product, architecture, and feature source of truth.

## Product boundary
This repository does not implement TikTok/YouTube/Douyin scraping, downloading, CAPTCHA bypass, or third-party media extraction. Import is from media the user already has as a local file.

## Run status
- Backend focused tests pass locally and keep the zero-media server boundary enforced.
- Android SDK/toolchain lives under `D:\LacViet\Android`; unit/lint/debug/release/AAB and 16 KB release gates are part of the local quality loop. GitHub Actions publishes `LingoPlay-Android-debug-apk` separately; release-test APK, AAB, and reports are separate artifacts so device testing does not require downloading one oversized bundle. Debug installs as `com.lingoplay.app.debug` and all development/device-test APKs use the committed public test identity for repeatable updates; that key must never sign a Play production artifact.
- iOS source is authored for SwiftUI/Swift 6. StoreKit Testing is configured locally for DEBUG only; Release Plus requires the server verification path. PrivacyInfo.xcprivacy declares Purchase History for entitlement app functionality, source-to-required-reason coverage, Release build settings, Mach-O size budgets, arm64, dSYM UUIDs, and PiP/background-audio project wiring are audited in macOS CI. Unsigned device-target builds remain verified there because this host is Windows.
- `scripts/verify_release_privacy.py` fails CI if the iOS required-reason privacy declarations, Android cleartext policy, or iOS privacy/PiP project wiring regress. `verify_ios_source_hardening.py`, `verify_ios_build_settings.py`, and `verify_ios_binary.py` add source, effective-Xcode-settings, and built-artifact release gates without unsafe linker/order-file guesses.
- Clean Background is opt-in and disabled by default. It becomes executable only when the pinned sherpa-onnx runtime and checksum-verified Spleeter model are both present; separated stems are temporary and cross-device output/performance certification remains a physical-device gate.
- Stage 21 account-independent billing engineering can be closed without store accounts, but production products/signing/provider credentials/TestFlight/Play Internal/notifications and real refund-revocation testing remain external blockers. See `docs/release/store-readiness.md`.

Optional neural-voice and offline-translation dependency/attribution details are recorded in `THIRD_PARTY_NOTICES.md`.

See `docs/index.md` for the canonical docs map.
