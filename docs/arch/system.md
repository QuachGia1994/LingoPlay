# System architecture

> updated 2026-09-03 · pre-release

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
SwiftUI frontend. Local video import uses the system file importer, security-scoped access only long enough to copy the selected video into LingoPlay cache, AVFoundation metadata inspection, and local M4A audio preparation. ASR is now behind a LingoPlay protocol backed by Argmax OSS/WhisperKit 1.1.0. Long audio uses WhisperKit incremental file loading, and the adapter initializes only from an Application Support model package containing the CoreML model components plus local tokenizer assets; `download` is disabled during inference setup.

### Android
Jetpack Compose frontend. Local video import uses Storage Access Framework `OpenDocument`, persistable read permission when the provider allows it, metadata inspection through platform media APIs, and `MediaExtractor` + `MediaMuxer` to copy the first supported audio track into app cache without video upload or video re-encode. ASR is now backed by sherpa-onnx 1.13.7. Compressed audio is decoded locally to mono PCM and fed to the offline Whisper recognizer in bounded 25-second chunks so a long podcast does not require one full-file `FloatArray`; each returned segment currently carries the real chunk time range rather than claiming sentence-level timestamps the Kotlin wrapper does not expose.

## Backend
Cloudflare Worker TypeScript with three initial routes:
- `GET /health` — liveness and media-boundary declaration.
- `POST /v1/translate` — validates segment JSON and proxies compact text to a configured translation provider later.
- `GET /v1/entitlements` — returns a minimal plan/capability shape; production identity/billing verification is a later boundary.

The Worker must never accept multipart/form-data, audio/*, video/*, or opaque media blobs.

## Model storage boundary
- iOS: `Application Support/LingoPlay/Models/WhisperKit/current` must contain the WhisperKit CoreML components and tokenizer data before ASR is considered installed.
- Android: `filesDir/lingoplay/models/sherpa-whisper/current` must contain encoder ONNX, decoder ONNX, and tokens text files before ASR is considered installed.
- Stage 3 never downloads a speech model implicitly. Model acquisition/selection is a separate product action because model size, network use, and storage impact are material user-visible costs.

## Current stage
Real local media ingestion, audio preparation, and ASR execution boundaries are wired on both native clients. Demo library/player content still exists only for visual navigation. The primary Import → Prepare → Processing path now requires an actual local video, displays real metadata, prepares audio locally, then either runs a genuinely installed on-device speech model or stops at a model-missing state. Translation/TTS/mix remain pending and no transcript is fabricated. Backend translation still returns an explicit `provider_not_configured` response when no provider binding exists rather than fabricating translated output.
