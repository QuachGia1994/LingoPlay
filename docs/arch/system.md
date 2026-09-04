# System architecture

> updated 2026-09-04 · pre-release

## Goal
Keep the heavy media path local while exposing a small server boundary for translation/provider access and entitlement state.

## Runtime topology
```mermaid
flowchart LR
    F[Local video file] --> C[Native client]
    C --> A[Local audio extraction]
    A --> I[On-device ASR / diarization / separation]
    I --> T[Transcript JSON]
    T --> B[Translation proxy]
    B --> T2[Translated segments JSON]
    T2 --> V[On-device Vietnamese TTS]
    V --> M[Local mix + duration fit]
    M --> R[Local video remux]
    R --> P[Player / offline library]
```

## Trust boundaries
- Video and audio bytes remain inside the app sandbox.
- Backend endpoints accept JSON only and explicitly reject media content types and oversized bodies.
- Translation provider credentials exist only in Worker secrets/environment bindings.
- Third-party video URL resolvers, downloaders, scraping, and CAPTCHA bypass are outside the product architecture.

## Native clients
### iOS
SwiftUI frontend. Local video import uses the system Photos Picker filtered to videos; large selected assets are transferred with a file representation and copied immediately into LingoPlay-owned cache before AVFoundation metadata inspection and local M4A audio preparation. ASR is behind a LingoPlay protocol backed by Argmax OSS/WhisperKit 1.1.0. Stage 8 adds explicit Whisper Tiny acquisition: the user starts installation, WhisperKit downloads into `Application Support/LingoPlay/Models/WhisperKit`, prewarm/load validates the model and tokenizer, and only then is a durable active-model pointer written. Later transcription receives that installed model descriptor and constructs WhisperKit with `download: false`, so inference itself does not silently fetch a model. Long audio uses WhisperKit incremental file loading. After ASR succeeds, `TranslationService` converts timestamped transcript segments into bounded JSON batches and calls the configured backend base URL from the generated `LingoPlayTranslationAPIBaseURL` Info.plist key. Provider credentials never exist in the app. Stage 5 uses `AVSpeechSynthesizer.write(_:toBufferCallback:)` with an installed Vietnamese system voice to persist one local CAF speech clip per translated segment. Stage 6 builds a dub stem, mixes it with the original soundtrack through explicit `AVAudioMix` volume ramps, ducks the original around translated speech, and exports a self-contained final MP4 without re-encoding the video track. Fresh in-app playback uses one `AVPlayerItem` containing video + original audio + dub stem so Original↔Dub adjustment stays on one playback clock rather than synchronizing independent players. Stage 7 copies the final MP4 plus compact translation metadata into `Application Support/LingoPlay/Library`; reopened saved items play the self-contained final mix through one AVPlayer and can be exported with the native share sheet or deleted locally. Stage 9 pre-wires StoreKit 2 locally: verified `Transaction.currentEntitlements` plus `Transaction.updates` determine Plus state; a local `Products.storekit` catalog is attached to the generated Run scheme for development without requiring App Store Connect products yet.

### Android
Jetpack Compose frontend. Local video import uses `ActivityResultContracts.PickVisualMedia` filtered to `VideoOnly`, preferring the system Photo Picker and retaining its document-provider fallback on unsupported devices; LingoPlay keeps read access when the provider allows it, inspects metadata through platform media APIs, and uses `MediaExtractor` + `MediaMuxer` to copy the first supported audio track into app cache without video upload or video re-encode. ASR is backed by sherpa-onnx 1.13.7. Stage 8 installs Whisper Tiny multilingual INT8 explicitly by downloading only the encoder, decoder, and token runtime files from a pinned upstream revision. Each file resumes through HTTP Range into a `.part` file, is SHA-256 verified before rename, and the versioned directory becomes active only after the complete verified set is present. Wi-Fi-only and free-space gates run before acquisition. Compressed audio is decoded locally to mono PCM and fed to the offline Whisper recognizer in bounded 25-second chunks so a long podcast does not require one full-file `FloatArray`; each returned segment currently carries the real chunk time range rather than claiming sentence-level timestamps the Kotlin wrapper does not expose. After ASR succeeds, Android sends only segment JSON through `TranslationService`; the public backend base URL is injected through the `LINGOPLAY_TRANSLATION_API_BASE_URL` Gradle property into BuildConfig, with no provider secret in the APK. Stage 5 uses platform `TextToSpeech.synthesizeToFile` and `UtteranceProgressListener`, selecting only an installed Vietnamese voice whose `isNetworkConnectionRequired` flag is false. Stage 6 no longer creates a full-duration PCM WAV: the original audio is decoded in bounded chunks, normalized/resampled when required by the AAC encoder, ducked around translated speech, mixed with TTS PCM in memory, and streamed directly into `MediaCodec` AAC. Final remux keeps the compressed video and writes video/audio samples in presentation-time order. Android playback uses one player clock on the already mixed MP4; unsafe dual-player live blending remains disabled until it can be implemented inside one playback graph. Stage 7 copies successful final MP4s plus translation metadata into app-specific Movies storage (with an internal-storage fallback), exposes them through a scoped `FileProvider` only when the user chooses Share, and supports reopen/delete without broad storage permission.

## Backend
Cloudflare Worker TypeScript with three initial routes:
- `GET /health` — liveness and media-boundary declaration.
- `POST /v1/translate` — validates timestamped segment JSON, forwards only compact transcript text/metadata to the configured provider, validates the provider's one-result-per-ID response, and returns normalized translated text.
- `GET /v1/entitlements` — returns a minimal plan/capability shape; production identity/billing verification is a later boundary.

The Worker must never accept multipart/form-data, audio/*, video/*, or opaque media blobs. Native clients batch at no more than 80 segments / 10,000 source characters per request, comfortably below the Worker limits of 240 segments / 24,000 source characters / 64 KiB body. Segment IDs are stable across the round trip; source timestamps remain client-owned and are combined with translated text locally.

## Model storage boundary
- iOS: `Application Support/LingoPlay/Models/WhisperKit/active-model.txt` points to the validated WhisperKit model directory under the same root. The installer writes this pointer only after download + prewarm/load validation; transcription then runs with network model download disabled.
- Android: `filesDir/lingoplay/models/sherpa-whisper/active-model.txt` points to a versioned verified directory containing the pinned encoder ONNX, decoder ONNX, and tokens text files. Legacy `.../current` lookup remains a compatibility fallback.
- Model acquisition is always an explicit user action because download size, network use, and storage impact are material. Import/processing never silently starts model acquisition.

## Billing boundary
- Stage 9 is iOS-only pre-wiring. `PlusStore` loads `com.lingoplay.plus.weekly` and `com.lingoplay.plus.monthly`, grants Plus only from verified non-expired/non-revoked StoreKit 2 transactions, listens to `Transaction.updates`, and uses `AppStore.sync()` for Restore.
- `ios/Products.storekit` is a local StoreKit Testing catalog attached to the XcodeGen Run scheme. It enables local purchase/renewal/expiry testing before an Apple Developer account/App Store Connect products exist; it is not server authority and is not a replacement for future App Store server verification.
- Android Play Billing is intentionally not introduced in Stage 9.
- The backend `/v1/entitlements` response remains informational/free until account identity and server-side receipt/transaction verification are designed.

## Risk hardening after Stage 9
- Android model acquisition resolves redirect hops explicitly and reapplies the same `Range` header, so resumable downloads survive Hugging Face/CDN 3xx transitions.
- Android bounded ASR chunks remain memory-safe but prefer a quiet low-RMS boundary near the end of the chunk; continuous audio falls back to the hard budget boundary.
- Android retains the already extracted audio cache while ASR is blocked only by a missing model, then resumes recognition from that file after installation instead of re-demuxing the source video.
- iOS Wi-Fi-only model acquisition waits for an actual `NWPathMonitor` callback rather than a fixed-delay sample of `currentPath`.
- StoreKit 2 applies a verified active Plus entitlement before finishing the transaction, then reconciles against `currentEntitlements`; unverified transactions never grant access.
- Stage 6 media protections remain authoritative: original soundtrack is preserved/ducked, Android remux is PTS-interleaved, AAC capability fallback/resampling is active, playback is single-clock, and rendered cache is age/size bounded.

## Current stage
Stages 1–9 source implementation is wired: local media ingestion, audio preparation, on-device ASR, explicit model acquisition, transcript-only translation, local Vietnamese TTS/duration fitting, production-safe soundtrack mixing/remux, real playback, branded launcher/native launch surfaces, durable local-library persistence, native share/export/deletion, and iOS StoreKit 2 Plus pre-wiring. Home and Library are backed by real saved outputs; saved Library items are offline-capable. Final exported media preserves the original soundtrack/BGM/SFX and ducks it around Vietnamese speech instead of replacing it with a silent-gap dub track. Android Stage 6 has representative MEIZU Lucky 08 physical codec evidence; Stage 8 model-download physical validation on that device remains pending only while the device is disconnected. iOS Stage 8/9 runtime/build evidence comes from the push-triggered Xcode GitHub workflow rather than claims made from the Windows host.
