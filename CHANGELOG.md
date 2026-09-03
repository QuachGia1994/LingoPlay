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
- Native transcript translation clients on iOS and Android with bounded segment batching, timestamp preservation, explicit endpoint-missing/failure states, and Vietnamese translation previews.
- Translation proxy contract tests proving only transcript JSON is forwarded and malformed provider output is rejected rather than fabricated.
- Local Vietnamese system-TTS stage on iOS and Android, including per-segment audio files, measured duration fitting, capped speech-rate retries, and explicit timeline silence instead of truncating words.
- Android offline-voice enforcement: network-required TTS voices are rejected from the local dubbing pipeline and the TTS service query is declared for modern package visibility.
- Production-safe Stage 6 local media pipeline: timestamp-driven ducking, original-soundtrack preservation, Vietnamese TTS mixing, final self-contained MP4 remux, single-clock playback, bounded rendered-cache retention, and active bilingual subtitles.
- Android Stage 6 streaming audio mixer that decodes the original soundtrack in bounded PCM chunks, normalizes/resamples only as required by AAC encoder capabilities, mixes TTS in memory, streams directly to MediaCodec AAC, and PTS-interleaves final MP4 samples without a full-duration WAV intermediate.
- iOS Stage 6 AVAudioMix pipeline with explicit volume ramps for ducking, passthrough video remux, one AVPlayerItem containing original + dub audio for same-clock Original↔Dub control, and no dual-player hard-seek synchronization.
- Android physical-device Stage 6 instrumentation fixtures covering a short mix/remux and a sustained 120-second hardware-codec run; both pass on a MEIZU Lucky 08 (Android 14, arm64-v8a) with final video+audio track and duration assertions.
- Matching generated LingoPlay launcher/app-icon assets, native launch branding, and branded loading marks for iOS and Android.
- Durable Stage 7 local libraries on iOS and Android that save successful final MP4s with compact translation metadata, reopen them for playback, expose native share/export, delete local results, and report real saved-media storage usage.
- Product and architecture documentation defining the local-media trust boundary and zero-video-upload design.

### Changed
- Processing now continues from real audio preparation into real local ASR and then transcript-only translation when the corresponding model/backend are configured; missing infrastructure stops honestly without fake progress or output.
- Translation batches are capped below backend limits and preserve stable segment IDs plus source timing for TTS/duration fitting.
- Processing now advances from 80% to 100% only while real local soundtrack mixing and remux work is executing; Player opens only after a non-empty final media file exists.
- Overlong translated speech retries up to a 1.75× safe rate cap; under-length segments reserve explicit tail silence for the upcoming timeline mixer.
- Android Whisper audio decoding is bounded to 25-second chunks to avoid full-podcast PCM memory growth and the sherpa Whisper stream limit.
- Android build configuration now uses AGP 9 built-in Kotlin instead of the incompatible kotlin-android plugin.
- Android CI now builds and uploads an installable `app-debug.apk` in addition to the optimized release-test APK/AAB, so device testing and production-like artifact testing are both available.
- Home, Library, and Offline no longer show demo completed videos; they render only real durable local outputs with honest empty states.
