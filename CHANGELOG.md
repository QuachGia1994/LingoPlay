# Changelog

All notable changes to LingoPlay are documented here.

## [Unreleased]

### Added
- Initial native SwiftUI and Jetpack Compose product foundations for the LingoPlay consumer flow.
- Real local video picking, metadata inspection, and local audio demux boundaries on iOS and Android.
- Android SAF ingestion with persistable read access plus MediaExtractor/MediaMuxer audio preparation.
- iOS security-scoped file ingestion with sandbox caching plus AVFoundation audio preparation.
- Real on-device speech-recognition adapters: WhisperKit 1.1.0 on iOS and sherpa-onnx 1.13.7 on Android, each gated by validated local model files.
- ASR transcript/state UI with detected language, normalized text, and timestamped segment evidence when recognition succeeds.
- Minimal JSON-only backend boundary for translation and entitlement requests.
- Product and architecture documentation defining the local-media trust boundary and zero-video-upload design.

### Changed
- Processing now continues from real audio preparation into real local ASR when a model is installed, or stops honestly at a model-missing state without implicit model download.
- Android Whisper audio decoding is bounded to 25-second chunks to avoid full-podcast PCM memory growth and the sherpa Whisper stream limit.
- Android build configuration now uses AGP 9 built-in Kotlin instead of the incompatible kotlin-android plugin.
