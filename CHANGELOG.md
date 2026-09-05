# Changelog

All notable changes to LingoPlay are documented here.

## [Unreleased]

### Added
- Stage 21 account-independent distribution hardening: the Worker now exposes fail-closed Apple App Store / Google Play subscription verification, iOS Release entitlement requires server confirmation while DEBUG Xcode StoreKit Testing remains local-only, and Android Play `PURCHASED` tokens must be server-verified before Plus unlock and acknowledgement.
- Store-readiness documentation records the exact external blockers while Apple Developer/App Store Connect and Play Console accounts are unavailable, including future Worker secret names, products, signing, notifications, sandbox/license-tester and refund/revocation evidence.

### Fixed
- Stage 22.2 media fidelity: delayed source-audio PTS is restored exactly once after zero-based ASR/diarization/cloning analysis; VFR/23.976/29.97 and no-audio fixtures guard remux timing; translated display text is separated from cue-free spoken TTS text; system/neural/ZipVoice duration fitting now trusts persisted audio frames; and 8 ms speech-edge fades reduce seam clicks without shifting timestamps or shortening clips.
- Stage 22.1 runtime integrity: recovery checkpoints now carry an explicit schema version and reject unknown future formats; Android/iOS model deletion is blocked until cancelled native processing actually returns; iOS retains processing task/run ownership through synchronous native cancellation; stale source-separation cache sessions are purged on next launch after process death.
- iOS Stage 21 DEBUG builds now expose immutable Plus product identifiers as nonisolated constants, fixing Swift 6 actor-isolation compilation in the local StoreKit entitlement path without weakening server authority in Release.
- Stage 20.1 Clean Background runtime integrity: Spleeter now processes bounded core windows with guard context and absolute-frame core cropping instead of hard-stitching independent 12-second outputs; Android also cleans a completed native separation result if coroutine cancellation lands before coordinator ownership transfers.
- Clean Background preserves the original source-audio timeline origin when inserting separated accompaniment. Android reads the generated PCM16 accompaniment WAV directly instead of depending on an OEM `audio/raw` MediaCodec decoder.
- The currently served official Spleeter archive envelope changed while retaining the same official two ONNX payloads. Both clients pin the current archive digest and exact inner model sizes/SHA-256 values; Android also accepts the prior known archive digest only when the extracted models match those exact inner pins.
- Stage 19 audit repairs: Resume cloning now requires saved opt-in and current consent; both reference and output languages must be EN/ZH. Unsupported sources or absent clear references use installed offline voices.
- Speaker attribution no longer hides a secondary speaker contributing at least 120 ms behind a 35% dominance threshold. Mixed chunks remain unknown and cannot supply clone references; words are not split without word timestamps.
- Reference extraction retains only selected short PCM windows. Hybrid/multi-voice failures clean earlier successful groups; Android also cleans sessions when dispatcher cancellation discards a completed native result.
- Final speech beyond the source-video end survives mix/remux and playback metadata, retaining the bounded iOS Vietnamese system-voice rate. iOS extends the composition with empty timeline tails instead of synthesizing video frames, and same-format mono PCM reference windows bypass unnecessary AVAudioConverter buffering.
- Cancelled iOS jobs remain owned until native return, replacement runs wait, checkpoint writes observe cancellation, and delayed recovery refresh cannot overwrite a newer run's UI.
- iOS Stage 19 Voice Cloning now passes model arguments in the order required by the pinned sherpa-onnx Swift API, fixing the Xcode compilation failure.
- Stage 19 Android multi-speaker processing now uses bounded 6-second ASR chunks instead of the normal long-form memory chunk size, so diarization labels survive onto transcript segments and clear single-speaker cloning references remain usable on-device.
- Android saved-video playback no longer stops its newly created VideoView during first composition. Player cleanup follows AndroidView release and media identity; device regressions cover cold entry, repeated entry, speed changes and file replacement.
- iOS app bundles now include resolved pre-release version/build metadata, avoiding the ML Kit model-downloader fallback identified in the iOS 27 crash. Host-app tests and the built-bundle verifier reject missing or unresolved versions.
- Stage 18.5 tracks every iOS processing run with a cancellable task plus immutable run/media identity, so Back/import/recovery replacement cannot let an old job save, clear a newer checkpoint, or navigate a different media item to Player.
- Neural Voice routing on Android and iOS now requires a Vietnamese target; stale/recovered neural voice identifiers for other target languages fall back to the matching offline system voice instead of failing inside the Vietnamese-only neural engine.
- Android Offline translation now rejects auto-detected languages outside the product-supported EN/VI/JA/ZH set instead of instructing users to download a model that Settings cannot install.
- Temporary system/neural TTS session audio is removed after mix and on failed synthesis, with cold-start stale-session cleanup on both platforms.
- Cloud translation normalizes regional BCP-47 language tags before Workers AI, bypasses provider inference for same-language jobs, gives iOS requests an explicit finite timeout, and reports cue-only iOS transcripts as local no-speech rather than a backend failure.

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
- Stage 17.1 optional Vietnamese neural TTS on Android and iOS: sherpa-onnx 1.13.7 plus a pinned VAIS1000 VITS voice pack, explicit install/delete, resumable download, exact size/SHA-256 verification, traversal/link/expansion-safe extraction, versioned activation, bounded CPU threads, and system-voice fallback.
- Stage 18 optional offline translation on Android/iOS with Google ML Kit (`17.0.3` / CocoaPod `8.0.0`), explicit Cloud/Offline selection, built-in English plus explicit model install/delete for Vietnamese, Japanese, and Chinese, recovery/Library route provenance, fail-closed missing-model behavior, telemetry disclosure, and Google Translate attribution.
- Stage 19 opt-in multi-speaker dubbing on Android/iOS with pinned local sherpa-onnx diarization packs, stable `speaker_1…` labels by first appearance, explicit unknown/overlap handling, persisted speaker→offline-voice mapping through recovery and Library metadata, and distinct per-speaker synthesis when matching installed voices exist.
- Stage 19 optional local Voice Cloning uses a separately downloadable pinned ZipVoice+Vocos pack for English/Chinese only. It is off by default, requires explicit ownership consent, derives clear non-overlap references only from the current processing run, never stores a reusable voicebank, and falls back to installed offline voices for overlap/unknown/non-reference segments.
- Stage 20 optional Clean Background on Android/iOS with the pinned sherpa-onnx Spleeter 2-stem FP16 pack. The model installs explicitly outside the base app with size/SHA-256 verification and bounded safe extraction; vocals feed ASR/diarization/cloning references, accompaniment feeds the final mix, transient stems are deleted with the processing run, and missing runtime/model fails closed.
- Android offline-voice enforcement: network-required TTS voices are rejected from the local dubbing pipeline and the TTS service query is declared for modern package visibility.
- Production-safe Stage 6 local media pipeline: timestamp-driven ducking, original-soundtrack preservation, Vietnamese TTS mixing, final self-contained MP4 remux, single-clock playback, bounded rendered-cache retention, and active bilingual subtitles.
- Android Stage 6 streaming audio mixer that decodes the original soundtrack in bounded PCM chunks, normalizes/resamples only as required by AAC encoder capabilities, mixes TTS in memory, streams directly to MediaCodec AAC, and PTS-interleaves final MP4 samples without a full-duration WAV intermediate.
- iOS Stage 6 AVAudioMix pipeline with explicit volume ramps for ducking, passthrough video remux, one AVPlayerItem containing original + dub audio for same-clock Original↔Dub control, and no dual-player hard-seek synchronization.
- Android physical-device Stage 6 instrumentation fixtures covering a short mix/remux and a sustained 120-second hardware-codec run; both pass on a MEIZU Lucky 08 (Android 14, arm64-v8a) with final video+audio track and duration assertions.
- Matching generated LingoPlay launcher/app-icon assets, native launch branding, and branded loading marks for iOS and Android.
- Durable Stage 7 local libraries on iOS and Android that save successful final MP4s with compact translation metadata, reopen them for playback, expose native share/export, delete local results, and report real saved-media storage usage.
- Stage 7.1 video-library import UX using Android Photo Picker `VideoOnly` and iOS Photos Picker file transfer, avoiding the generic folder browser as the primary import path and avoiding full-video in-memory loads.
- Persisted Midnight/High Contrast appearance and English/Tiếng Việt interface-language controls for the primary mobile shell.
- Stage 8 explicit Speech AI model acquisition on both clients: Whisper Tiny install/progress/cancel/delete UI, persistent activation, free-space/Wi-Fi gates, and offline-only inference after activation.
- Android Stage 8 resumable direct runtime-model download with HTTP Range `.part` files, exact per-file SHA-256 verification, versioned activation, and focused manifest/hash/progress unit tests.
- iOS Stage 8 WhisperKit Tiny acquisition with explicit download, prewarm/load validation, durable active-model pointer, and processing resume from already-prepared audio after installation.
- Stage 9 iOS StoreKit 2 Plus pre-wiring with weekly/monthly product IDs, product loading, verified-transaction entitlements, purchase/pending/cancel/restore handling, and transaction update reconciliation.
- Local `Products.storekit` subscription catalog plus XcodeGen Run-scheme StoreKit configuration so Plus purchase flows can be tested before an Apple Developer account/App Store Connect products exist.
- Stage 10 dubbing-quality hardening: near-silence ASR gating, silence-aware bounded chunk boundaries, Android speech RMS normalization, soft limiting, and smoother iOS soundtrack ducking.
- A truthful Clean Background capability state on both clients. The normal path remains adaptive ducking; Stage 20 enables real local source separation only when the executable runtime and checksum-verified optional model are both present.
- Stage 11 durable processing recovery: app-owned imported media, local checkpoints, Resume/Discard after interruption, Android Picture-in-Picture, and native iOS AVPlayerViewController Picture-in-Picture on the existing single player.
- Stage 12 local-only bounded diagnostics, iOS PrivacyInfo.xcprivacy required-reason declarations, Android cleartext-network blocking, and CI privacy/security verification.
- Stage 13 persisted dubbing preferences with real downstream behavior: source-language override, target-language selection constrained to installed offline voices, installed voice choice, three soundtrack/dub mix presets, Off/Translated/Bilingual subtitle modes, and real playback-speed control on both clients.
- Stage 13 interaction-completeness pass removes fake/dead affordances: Android no longer presents a disabled live-blend slider for its single mixed output, unavailable actions do not render as tappable controls, and About/Plus/settings rows now open real behavior.
- Stage 14 Android Google Play Billing 9.1.0 subscription pre-wiring for the same weekly/monthly Plus product IDs as iOS, with PURCHASED-only entitlement, pending-safe behavior, restore/reconciliation, and acknowledgement after local entitlement delivery.
- Stage 14 source-separation engine protocols/capability seams on both clients. Stage 20 now supplies the verified-runtime/model-gated implementation behind those seams without bundling the model into the base app.
- Product and architecture documentation defining the local-media trust boundary and zero-video-upload design.

### Changed
- Android Neural Voice now opens verified app-storage model paths without an AssetManager, preventing sherpa-onnx from aborting when the optional absolute-path voice pack is selected.
- Processing now continues from real audio preparation into real local ASR and then transcript-only translation when the corresponding model/backend are configured; missing infrastructure stops honestly without fake progress or output.
- Translation batches are capped below backend limits and preserve stable segment IDs plus source timing for TTS/duration fitting.
- Processing now advances from 80% to 100% only while real local soundtrack mixing and remux work is executing; Player opens only after a non-empty final media file exists.
- Overlong translated speech retries up to a 1.75× safe rate cap; under-length segments reserve explicit tail silence for the upcoming timeline mixer.
- Android Whisper audio decoding is bounded to 25-second chunks to avoid full-podcast PCM memory growth and the sherpa Whisper stream limit.
- Android build configuration now uses AGP 9 built-in Kotlin instead of the incompatible kotlin-android plugin.
- Android CI now builds and uploads an installable `app-debug.apk` in addition to the optimized release-test APK/AAB, so device testing and production-like artifact testing are both available.
- Home and Library no longer show demo completed videos; they render only real durable local outputs with honest empty states.
- Removed the redundant Offline tab because every durable Library item is already local/offline; the center bottom-bar affordance is now the explicit Import action instead of a duplicate Home button.
- Android dark-theme content propagation now establishes the correct light foreground at the root, with a brighter accessible secondary palette and an optional High Contrast mode.
- Android Plus presentation now mirrors the compact premium-icon treatment used by iOS instead of a text-only `PLUS` pill.
- Speech-model downloads remain explicit user actions; importing or processing media never silently starts a large model download.
- Current pre-release capabilities remain usable when StoreKit products are unavailable; Plus entitlement is derived only from verified active StoreKit transactions rather than a persisted local unlock boolean.
- Android Speech AI download now follows 301/302/303/307/308 redirects explicitly while preserving HTTP Range on every hop, so resumable Hugging Face/CDN downloads do not depend on implicit redirect behavior.
- iOS model Wi-Fi gating now waits for the first `NWPathMonitor` path callback instead of assuming the path is resolved after a fixed 300 ms delay.
- StoreKit 2 verified purchases now enable the local Plus entitlement before `Transaction.finish()`, then reconcile from `currentEntitlements` after finish.
- Android ASR chunking now prefers a low-energy quiet boundary within the final 2 seconds of each bounded chunk, reducing word/sentence cuts while retaining a hard-size fallback for continuous speech/music.
- Android Processing reuses already extracted audio after an explicit model install instead of demuxing the same source video again; the temporary audio is deleted after successful ASR or when a different media item is selected.
- Imported media is now copied into app-owned cache before processing so recovery never depends on a temporary Photo Picker grant. Obsolete preparation/recovery sessions explicitly clean their app-owned media.
- Processing copy no longer promises unlimited background execution. Both clients recover from a durable local boundary after interruption, while PiP is scoped to playback.
- Android release networking rejects cleartext HTTP; production translation/model endpoints must be HTTPS.
- Local diagnostics record only timestamped event codes and are never uploaded by LingoPlay.
- Android CI artifacts are split by purpose: debug APK, signed release-test APK, release AAB, and reports. The intermediate unsigned release APK is verified in CI but no longer uploaded, avoiding the previous oversized all-in-one artifact download.
- Post-Stage-12 risk hardening serializes Android processing runs across Back/Resume/new-import/discard boundaries, preserves prepared audio until recovery is cleared, gates PiP on prepared+playing state, and explicitly releases VideoView on disposal.
- Android ASR chunk-boundary silence detection is now relative to each chunk's RMS so quiet real speech is not mistaken for silence; very-low-level noise is no longer amplified by TTS normalization.
- Android recovery checkpoint replacement now prefers atomic filesystem move and app-owned import deletion is canonical-root constrained.
- iOS local diagnostics now rewrite atomically instead of appending in-place; return-home cleanup explicitly avoids the active Library URL.
- Translation/TTS is no longer hard-coded to Vietnamese in the client pipeline: selected target language flows into translation and an installed matching system voice; missing offline voice stops explicitly rather than falling back to network TTS.
- Android/iOS dubbing mode now changes actual duck floor, fade envelope, and dub gain instead of only changing a UI label; subtitle mode and playback speed likewise control the active player.
- Android Plus state is derived from current Google Play purchases rather than a persisted unlock boolean. Sideload/debug builds with no matching Play Console products report products unavailable instead of fabricating pricing or entitlement.
- Post-Stage-14 risk hardening snapshots source/target/voice/dubbing/subtitle configuration once per processing run, persists that immutable snapshot in Android/iOS recovery checkpoints, and reuses it after Resume so Settings changes cannot mix languages or audio policy across one job.
- Library metadata now records the generation dubbing mode. Android Player shows the saved mode for new items and an explicit legacy final-mix state for older items instead of relabeling them from current Settings; iOS fresh live-blend playback also receives the exact render-time mode.
- Android Billing connection startup is serialized, service disconnects schedule one reconnect, app-root disposal closes BillingClient, and multi-offer products prefer the base-plan offer rather than arbitrary first-offer ordering.
- Android now reconciles Plus entitlement on app-root startup, suppresses misleading Retry UI while Billing is still connecting/loading, and deduplicates in-flight purchase acknowledgements.
- Android MediaPlayer speed changes no longer apply non-zero PlaybackParams while paused/preparing; seek controls are disabled until the player is ready.
- iOS subtitle language badges now come from the active saved TranslationDocument, while AVPlayer defaultRate follows the persisted playback-speed preference across system/PiP resume. Invalid persisted rates fall back to 1.0x.
- Subtitle lookup now includes the exact segment end timestamp consistently on Android and iOS.
- Stage 15 removes the two largest UI god-files: Android rendering is split into domain screens/components while `LingoPlayApp` keeps orchestration only; iOS `Screens.swift` is replaced by focused Home/Prepare, Processing, Player, Library/Settings, and shared-component files.
- Android dubbing preferences now live in a plain testable state holder backed by a persistence interface, and the processing pipeline is delegated to a typed `AndroidProcessingCoordinator` runtime boundary instead of calling ASR/translation/TTS/mix directly from Compose root state.
- Cross-platform product policy is guarded by `contracts/product-contract.json` plus CI verification for language sets, playback rates, dubbing-mode numeric policy, Plus IDs, and Clean Background truthfulness; architecture size/responsibility budgets are also enforced in both platform workflows.
- Android player policy and dubbing-preference state have JVM regression tests; coordinator behavior has an Android instrumentation test source that is compiled in CI. iOS now has an XcodeGen unit-test target and policy tests that run on the macOS workflow before the unsigned release build.
- Stage 15.1 hardens the iOS release path with source-to-Privacy-Manifest checks, runtime image decode budgets, explicit Release dead-code stripping, and verification of effective Xcode Release settings instead of assuming optimization flags from project text.
- macOS CI now audits the built iOS app for arm64, the 500 MB aggregate Mach-O `__TEXT` ceiling, the 4 GB uncompressed bundle ceiling, dSYM UUID parity, embedded framework inventory, unsigned IPA size diagnostics, and a non-authoritative 16 KB segment-alignment diagnostic.
- Unsafe speculative optimizations are intentionally rejected: no `/dev/null` exported-symbol list, no unprofiled `ORDER_FILE`, no forced strip-all/LTO override, and no image/cache dependency added when the current source has no unbounded runtime image decode path.
- Stage 15.2 fixes an Android processing-completion cancellation regression by removing the mutable media URI from the processing `LaunchedEffect` key, adds native system/gesture Back handling with the same cleanup paths as visible Back controls, clears stale resumed-processing presentation state, and attributes Whisper model-registry lookup failures to ASR.
- Live macOS/Xcode 26.6 CI exposed and Stage 15.2 fixes two iOS compile blockers missed by static review: cross-file `SavedVideoRow` access control after the Stage 15 screen split and an `await` expression inside a boolean autoclosure in the Wi-Fi model-install gate. Live GitHub evidence also disproved the stale claim that `macos-26` or current Android action majors were unschedulable.
- Stage 15.3 wires the public translation endpoint into both Android and iOS CI builds and makes both workflows fail closed when `LINGOPLAY_TRANSLATION_API_BASE_URL` is missing.
- Stage 15.4 closes the remaining iOS artifact gap: the source Info.plist now expands a deterministic production endpoint, the runtime safely falls back to that public HTTPS endpoint, endpoint resolution has unit coverage, and CI inspects the built app bundle instead of trusting build settings alone.
- Stage 15.5 sanitizes ASR/provider control tokens on Android, iOS, and the Worker; corrects a strongly English transcript that ASR mislabeled as another language; and rejects cue-only speech instead of sending it to translation/TTS.
- Overlong offline speech no longer aborts an otherwise valid video after the 1.75× safe-rate retries. Both clients keep the final synthesized clip, extend the local ducking window to its measured duration, and let the existing overlap-safe mixer complete the job.
- The Cloudflare backend now binds Workers AI and translates transcript-only segment JSON with pinned `@cf/meta/m2m100-1.2b`; video/audio remain device-local. Backend regression coverage is 11 tests, and the legacy external-provider proxy remains available as an optional fallback.
- Android Debug now uses the side-by-side `com.lingoplay.app.debug` identity and a stable, intentionally public device-test signing key. Local/CI test APKs remain update-compatible without replacing production data; this key is forbidden for Play production signing.
- Stage 15.7 hardens iOS offline TTS liveness after a physical-device stall at segment 31/37: one serial `AVSpeechSynthesizer` is reused per job, every buffer-write attempt has a bounded 20–60 second watchdog and cancellation path, and Processing shows the active segment count instead of an ambiguous static percentage.
