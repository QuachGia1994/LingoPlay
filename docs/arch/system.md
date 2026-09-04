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
SwiftUI frontend. Local video import uses the system Photos Picker filtered to videos; large selected assets are transferred with a file representation and copied immediately into LingoPlay-owned cache before AVFoundation metadata inspection and local M4A audio preparation. ASR is behind a LingoPlay protocol backed by Argmax OSS/WhisperKit 1.1.0. Stage 8 adds explicit Whisper Tiny acquisition: the user starts installation, WhisperKit downloads into `Application Support/LingoPlay/Models/WhisperKit`, prewarm/load validates the model and tokenizer, and only then is a durable active-model pointer written. Later transcription receives that installed model descriptor and constructs WhisperKit with `download: false`, so inference itself does not silently fetch a model. Long audio uses WhisperKit incremental file loading. After ASR succeeds, `TranslationService` converts timestamped transcript segments into bounded JSON batches and calls the configured backend base URL from the generated `LingoPlayTranslationAPIBaseURL` Info.plist key. Provider credentials never exist in the app. Stage 5 uses `AVSpeechSynthesizer.write(_:toBufferCallback:)` with an installed offline system voice matching the selected target language to persist one local CAF speech clip per translated segment. Stage 6 builds a dub stem, mixes it with the original soundtrack through explicit `AVAudioMix` volume ramps, ducks the original around translated speech, and exports a self-contained final MP4 without re-encoding the video track. Fresh in-app playback uses one `AVPlayerItem` containing video + original audio + dub stem so Original↔Dub adjustment stays on one playback clock rather than synchronizing independent players. Stage 7 copies the final MP4 plus compact translation metadata into `Application Support/LingoPlay/Library`; reopened saved items play the self-contained final mix through one AVPlayer and can be exported with the native share sheet or deleted locally. Stage 9 pre-wires StoreKit 2 locally: verified `Transaction.currentEntitlements` plus `Transaction.updates` determine Plus state; a local `Products.storekit` catalog is attached to the generated Run scheme for development without requiring App Store Connect products yet.

### Android
Jetpack Compose frontend. Local video import uses `ActivityResultContracts.PickVisualMedia` filtered to `VideoOnly`, preferring the system Photo Picker and retaining its document-provider fallback on unsupported devices; LingoPlay keeps read access when the provider allows it, inspects metadata through platform media APIs, and uses `MediaExtractor` + `MediaMuxer` to copy the first supported audio track into app cache without video upload or video re-encode. ASR is backed by sherpa-onnx 1.13.7. Stage 8 installs Whisper Tiny multilingual INT8 explicitly by downloading only the encoder, decoder, and token runtime files from a pinned upstream revision. Each file resumes through HTTP Range into a `.part` file, is SHA-256 verified before rename, and the versioned directory becomes active only after the complete verified set is present. Wi-Fi-only and free-space gates run before acquisition. Compressed audio is decoded locally to mono PCM and fed to the offline Whisper recognizer in bounded 25-second chunks so a long podcast does not require one full-file `FloatArray`; each returned segment currently carries the real chunk time range rather than claiming sentence-level timestamps the Kotlin wrapper does not expose. After ASR succeeds, Android sends only segment JSON through `TranslationService`; the public backend base URL is injected through the `LINGOPLAY_TRANSLATION_API_BASE_URL` Gradle property into BuildConfig, with no provider secret in the APK. Stage 5 uses platform `TextToSpeech.synthesizeToFile` and `UtteranceProgressListener`, selecting only an installed voice matching the selected target language whose `isNetworkConnectionRequired` flag is false. Stage 6 no longer creates a full-duration PCM WAV: the original audio is decoded in bounded chunks, normalized/resampled when required by the AAC encoder, ducked around translated speech, mixed with TTS PCM in memory, and streamed directly into `MediaCodec` AAC. Final remux keeps the compressed video and writes video/audio samples in presentation-time order. Android playback uses one player clock on the already mixed MP4; unsafe dual-player live blending remains disabled until it can be implemented inside one playback graph. Stage 7 copies successful final MP4s plus translation metadata into app-specific Movies storage (with an internal-storage fallback), exposes them through a scoped `FileProvider` only when the user chooses Share, and supports reopen/delete without broad storage permission.

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
- iOS `PlusStore` loads `com.lingoplay.plus.weekly` and `com.lingoplay.plus.monthly`, grants Plus only from verified non-expired/non-revoked StoreKit 2 transactions, listens to `Transaction.updates`, and uses `AppStore.sync()` for Restore.
- `ios/Products.storekit` is a local StoreKit Testing catalog attached to the XcodeGen Run scheme. It enables local purchase/renewal/expiry testing before an Apple Developer account/App Store Connect products exist; it is not server authority and is not a replacement for future App Store server verification.
- Stage 14 adds Android Google Play Billing 9.1.0 pre-wiring for the same weekly/monthly product IDs. Only `PURCHASED` purchases grant local Plus; pending purchases do not. Current purchases are reconciled on connection/restore and acknowledged only after local entitlement delivery. No persisted boolean is treated as entitlement authority.
- A sideload/debug build with no matching Play Console product configuration reports products unavailable and does not fabricate price, purchase state, or entitlement.
- The backend `/v1/entitlements` response remains informational/free until account identity and server-side receipt/transaction verification are designed.

## Risk hardening after Stage 9
- Android model acquisition resolves redirect hops explicitly and reapplies the same `Range` header, so resumable downloads survive Hugging Face/CDN 3xx transitions.
- Android bounded ASR chunks remain memory-safe but prefer a quiet low-RMS boundary near the end of the chunk; continuous audio falls back to the hard budget boundary.
- Android retains the already extracted audio cache while ASR is blocked only by a missing model, then resumes recognition from that file after installation instead of re-demuxing the source video.
- iOS Wi-Fi-only model acquisition waits for an actual `NWPathMonitor` callback rather than a fixed-delay sample of `currentPath`.
- StoreKit 2 applies a verified active Plus entitlement before finishing the transaction, then reconciles against `currentEntitlements`; unverified transactions never grant access.
- Stage 6 media protections remain authoritative: original soundtrack is preserved/ducked, Android remux is PTS-interleaved, AAC capability fallback/resampling is active, playback is single-clock, and rendered cache is age/size bounded.

## Stage 10 quality boundary
- Android skips near-silent decoded chunks before Whisper, still uses bounded memory, and chooses low-energy chunk boundaries where available. Synthesized speech clips are RMS-normalized with a peak ceiling before mix; summed PCM uses a soft limiter rather than hard clipping.
- iOS keeps the original soundtrack and uses a gentler 120 ms duck envelope / higher background floor for less pumping around Vietnamese speech.
- `Clean Background` is deliberately reported as unavailable. The current artifact does not bundle a verified cross-platform source-separation engine, so the product does not pretend adaptive ducking is stem separation.

## Stage 11 lifecycle boundary
- Both clients copy selected video into app-owned local storage before processing and persist a small local recovery checkpoint. The checkpoint also carries the immutable processing configuration (source/target language, preferred voice, dubbing mode, subtitle mode), so Resume cannot silently adopt Settings changed after the original run began. If the process is interrupted, Home offers Resume/Discard; resume uses prepared audio when it still exists and otherwise restarts from the owned source video.
- Android Picture-in-Picture uses the same Activity/VideoView playback path. iOS playback uses `AVPlayerViewController` with Picture-in-Picture enabled on the same AVPlayer and a playback audio session.
- Recovery is the guarantee; unlimited background inference is not. No long-running WorkManager/BGProcessing architecture is presented as guaranteed immediate execution.

## Stage 12 privacy/release boundary
- iOS ships `PrivacyInfo.xcprivacy` with tracking disabled, no LingoPlay-collected data, and required-reason declarations for app-only UserDefaults, app-container file timestamps, and disk-space checks.
- Android disables cleartext traffic with a network-security config while backup/data extraction remains disabled.
- Both clients keep bounded local diagnostics containing only timestamps plus event codes; transcript/media names/error details are not written to the diagnostic log and the log is not uploaded.
- `scripts/verify_release_privacy.py` validates the privacy manifest, Android network policy, and iOS PiP/privacy project wiring in both platform CI workflows.

## Stage 13 interaction boundary
- User-facing dubbing preferences are durable and have real downstream effects: source-language choice configures Whisper language behavior, target-language choice drives translation/TTS, installed offline voice selection is honored, and dubbing mode changes actual duck floor/fade/dub gain.
- Subtitle mode is `Off`, translated-only, or bilingual and controls Player rendering on both clients. Playback speed changes the active platform player rather than only a displayed label.
- Android intentionally does not show a fake live Original↔Dub slider for its already-mixed MP4. Controls that cannot act are hidden or rendered without a chevron/action affordance; About and Plus surfaces are real screens/sheets.

## Stage 14 advanced-capability boundary
- Android Plus parity uses Google Play Billing 9.1.0 as described above; iOS remains StoreKit 2. Neither client treats a persisted local flag as billing authority. Android Billing connection startup is serialized and service disconnects schedule a single reconnect; subscription offer selection prefers the base-plan offer instead of arbitrary list ordering.
- Both clients define an explicit source-separation engine protocol/capability seam. `Clean Background` remains unavailable because this artifact still has no verified cross-platform native separator engine/model; adaptive ducking is not presented as stem separation.
- Advanced voice in the shipped build means choosing among installed system voices that can run on the selected local path. Unavailable neural/multi-speaker/source-separation features are not advertised as active.

## Stage 15 architecture/testability boundary
- Android Compose root owns app-level orchestration/navigation/state wiring only. Rendering is split by domain, dubbing preferences live in a plain state holder backed by a persistence interface, and the processing chain is delegated to `AndroidProcessingCoordinator` through a typed runtime boundary. Root code is structurally forbidden from directly invoking Whisper transcription, translation, TTS, or timeline mix services.
- iOS keeps `@Observable AppModel` as the app-facing state source, while view rendering is split by screen domain and pure preference/playback presentation logic lives in focused extensions/policy types. No ObservableObject/KMP rewrite is introduced.
- `contracts/product-contract.json` is the machine-readable product-policy contract. `verify_product_contract.py` checks native parity for language ordering, playback rates, dubbing-mode duck/gain/fade values, Plus product IDs, and the fact that Clean Background remains unverified/unavailable.
- `verify_architecture.py` enforces god-file removal, size/responsibility budgets, processing delegation, policy/test seams, iOS unit-test target wiring, and platform CI guardrails.
- Android JVM tests cover player-interaction policy and preference state with fake persistence. Coordinator instrumentation tests compile in CI and require a real Android runtime to execute. iOS XcodeGen now declares `LingoPlayTests`; macOS CI runs policy unit tests before the unsigned release build.

## Stage 15.1 iOS release hardening boundary
- Required-reason privacy coverage is source-aware: CI confirms that current UserDefaults, disk-space, and file-timestamp API use remains covered by the shipped manifest, while System Boot Time or Active Keyboards categories are not declared unless corresponding APIs actually enter the source.
- XcodeGen Release explicitly enables dead-code stripping. macOS CI verifies effective Xcode settings for whole-module Swift compilation, `-O`, dSYM generation, disabled assertions, and dead stripping rather than duplicating every Xcode preset as source text.
- Built-artifact CI checks arm64 presence, aggregate Mach-O `__TEXT` below 500 MB, uncompressed `.app` below 4 GB, matching executable/dSYM UUIDs, and reports embedded dynamic frameworks. Unsigned IPA size is diagnostic only; Apple's 200 MB OTA warning is variant/app-thinning based, and the canonical App Thinning Size Report remains a signed archive/export step.
- iOS 16 KB memory pages are treated as a runtime/toolchain fact and built Mach-O segment alignment is reported diagnostically. LingoPlay does not invent an App Store 16 KB Mach-O rejection rule equivalent to Android's ELF gate.
- Runtime image assets are constrained by a decoded-RGBA budget and source scanning rejects unbounded `UIImage(data:)`/`UIImage(contentsOfFile:)`-style decode paths. No Nuke dependency is added while the product only ships small asset-catalog images.
- No `/dev/null` exported-symbol linker list, unprofiled order file, forced strip-all, or LTO override is accepted without measured binary/launch evidence. Launch/order-file work remains a profiling task, not a speculative Release flag.

## Stage 15.2 lifecycle/CI hardening boundary
- Android processing orchestration is keyed only by stage/model availability, not by the mutable selected-media URI that is replaced with the durable Library item at completion. Completion can therefore publish the saved result and enter Player without self-cancelling its own Compose effect.
- System/gesture Back and visible Back controls share the same cleanup/recovery callbacks for Prepare and Processing, preventing lifecycle behavior from diverging by navigation input method. Recovery Resume clears stale presentation/error state before the immutable checkpoint config is re-run.
- Whisper model-registry lookup failures are attributed to the ASR step rather than escaping the coordinator, while coroutine cancellation semantics for suspend work remain unchanged.
- macOS CI is authoritative for Swift compilation on this Windows host. The first Stage 15.1 run proved `macos-26`, Xcode 26.6, package resolution, simulator selection, and effective Release settings are valid, then exposed two real source blockers: cross-file private `SavedVideoRow` access and `await` in a boolean autoclosure. Stage 15.2 fixes those source issues instead of changing valid runner/action versions based on stale external assumptions.

## Stage 15.3 live translation boundary
- The public backend endpoint is injected into Android and iOS CI from the repository variable `LINGOPLAY_TRANSLATION_API_BASE_URL`; both workflows fail before build when the variable is missing. Android passes it through Gradle into `BuildConfig.TRANSLATION_API_BASE_URL`; iOS passes it as the Xcode build setting consumed by the generated Info.plist key.
- Cloudflare Worker `lingoplay-api` binds Workers AI as `env.AI` and uses pinned `@cf/meta/m2m100-1.2b` for transcript translation. The request boundary remains JSON-only: source/target language plus segment ids/timestamps/text; media payloads remain rejected.
- The older external-provider URL/key path remains optional fallback plumbing. Provider absence is no longer the normal production path, and CI architecture checks guard the AI binding and client endpoint injection against regression.

## Stage 15.4–15.5 translation/TTS runtime boundary
- iOS resolves the production HTTPS translation endpoint from the built app Info.plist and retains a deterministic public fallback; CI inspects the built bundle rather than trusting source settings alone.
- Android, iOS, and the Worker remove bounded angle-bracket control/timestamp tokens and bracketed non-speech cues before translation/TTS. A conservative script-and-common-word heuristic corrects strongly English speech mislabeled by ASR (the observed `TH` case) without guessing when evidence is weak.
- Offline TTS still retries duration fitting only up to the 1.75× safe-rate cap. If speech remains longer, the measured clip is retained and its local speech/ducking window is extended; one overflowing segment no longer aborts the whole job. Existing overlap-capable mixers and soft limiting remain responsible for the final soundtrack.

## Stage 15.6 Android device-test identity
- Debug uses `com.lingoplay.app.debug`, so USB/CI testing installs beside the production package and cannot overwrite its model, recovery, Library, or preferences.
- Debug and CI release-test artifacts reuse `android/keystores/lingoplay-device-test.p12`. This intentionally public non-production identity prevents per-run signature rotation and supports repeatable test updates.
- The device-test key is never production authority. Signed Play artifacts require a separate protected upload/app-signing identity during Stage 21.

## Stage 15.7 iOS offline-TTS liveness boundary
- A physical iPhone run completed ASR and translation but stopped advancing after 30/37 offline TTS segments. The former checked-continuation bridge depended indefinitely on `AVSpeechSynthesizer.write` delivering its terminal zero-frame buffer and created a fresh synthesizer for every duration-fit attempt.
- iOS now uses one serial synthesizer per job. Each write has a workload-scaled 20–60 second watchdog; timeout or task cancellation resumes the continuation exactly once, cancels the watchdog, and stops the active synthesizer instead of leaving Processing at a permanent percentage.
- Processing publishes the active TTS segment (`n/total`). A legitimate slow duration-fit attempt is therefore distinguishable from a stalled callback, while final success still requires measured local audio for every translated segment.

## Current stage
Stages 1–15.7 source implementation is wired: local media ingestion, explicit model acquisition, bounded on-device ASR, live transcript-only translation with artifact sanitation and conservative language correction, target-aware offline system TTS with non-fatal duration overflow, quality-normalized soundtrack mixing/remux, durable recovery, single-clock playback with PiP, durable source/target/voice/mode/subtitle/speed preferences, local library/share/delete, StoreKit 2 plus Android Play Billing pre-wiring, release privacy/security and iOS built-artifact checks, cross-platform product-contract verification, architecture/testability guardrails, post-refactor lifecycle/CI hardening, and fail-closed translation-backend wiring. Home and Library remain backed by real saved outputs; final exported media preserves original soundtrack/BGM/SFX. Clean Background/source separation is intentionally unavailable rather than simulated. Android local build/test evidence is collected on Windows; MEIZU physical instrumentation evidence remains external, while macOS/iOS CI is used as the authoritative Swift build gate.
