# LingoPlay

LingoPlay is a consumer mobile app for translating and dubbing local video on-device. The video/audio media path stays on the phone; the backend receives only small JSON translation requests and entitlement metadata.

## Current foundation
- `ios/` — SwiftUI product flow with real local file import, metadata inspection, and AVFoundation audio preparation before the ASR boundary.
- `android/` — Jetpack Compose mirror using SAF local file import plus MediaExtractor/MediaMuxer audio preparation.
- `backend/` — Cloudflare Worker TypeScript boundary for health, translation, and entitlement APIs. Media payloads are rejected by design.
- `docs/` — current product, architecture, and feature source of truth.

## Product boundary
This repository does not implement TikTok/YouTube/Douyin scraping, downloading, CAPTCHA bypass, or third-party media extraction. Import is from media the user already has as a local file.

## Run status
- Backend focused tests pass on Node 26 and keep the zero-media server boundary enforced.
- Android targets AGP 9.3.0 with built-in Kotlin, Compose compiler 2.3.21, Compose BOM 2026.08.00, and compileSdk 37. Gradle configuration succeeds locally; compile/tests require an Android SDK path, which is not installed/configured on this Windows host yet.
- iOS source is authored for SwiftUI/Swift 6 and is statically reviewable on Windows; runtime/build verification requires Xcode/macOS.

See `docs/index.md` for the canonical docs map.
