# System architecture

> updated 2026-09-05 · Stage 21 account-independent release engineering

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
- Translation and store-provider verification credentials exist only in Worker secrets/environment bindings. Production billing requests contain only store transaction/purchase identifiers, never media.
- Third-party video URL resolvers, downloaders, scraping, and CAPTCHA bypass are outside the product architecture.

## Native clients
### iOS
SwiftUI frontend. Local video import uses the system Photos Picker filtered to videos; large selected assets are transferred with a file representation and copied immediately into LingoPlay-owned cache before AVFoundation metadata inspection and local M4A audio preparation. ASR is behind a LingoPlay protocol backed by Argmax OSS/WhisperKit 1.1.0. Stage 8 adds explicit Whisper Tiny acquisition: the user starts installation, WhisperKit downloads into `Application Support/LingoPlay/Models/WhisperKit`, prewarm/load validates the model and tokenizer, and only then is a durable active-model pointer written. Later transcription receives that installed model descriptor and constructs WhisperKit with `download: false`, so inference itself does not silently fetch a model. Long audio uses WhisperKit incremental file loading. After ASR succeeds, `TranslationService` converts timestamped transcript segments into bounded JSON batches and calls the configured backend base URL from the generated `LingoPlayTranslationAPIBaseURL` Info.plist key. Provider credentials never exist in the app. Stage 5 uses `AVSpeechSynthesizer.write(_:toBufferCallback:)` with an installed offline system voice matching the selected target language to persist one local CAF speech clip per translated segment. Stage 6 builds a dub stem, mixes it with the original soundtrack through explicit `AVAudioMix` volume ramps, ducks the original around translated speech, and exports a self-contained final MP4 without re-encoding the video track. Fresh in-app playback uses one `AVPlayerItem` containing video + original audio + dub stem so Original↔Dub adjustment stays on one playback clock rather than synchronizing independent players. Stage 7 copies the final MP4 plus compact translation metadata into `Application Support/LingoPlay/Library`; reopened saved items play the self-contained final mix through one AVPlayer and can be exported with the native share sheet or deleted locally. Stage 21 keeps StoreKit 2 as the purchase surface but makes production entitlement server-authoritative: verified non-revoked/non-expired StoreKit transactions are forwarded by transaction ID to the Worker, and Release unlocks Plus only after the Worker confirms the transaction against Apple. A local `Products.storekit` catalog remains attached to the generated Run scheme as DEBUG-only Xcode StoreKit authority while no App Store Connect products exist.

### Android
Jetpack Compose frontend. Local video import uses `ActivityResultContracts.PickVisualMedia` filtered to `VideoOnly`, preferring the system Photo Picker and retaining its document-provider fallback on unsupported devices; LingoPlay keeps read access when the provider allows it, inspects metadata through platform media APIs, and uses `MediaExtractor` + `MediaMuxer` to copy the first supported audio track into app cache without video upload or video re-encode. ASR is backed by sherpa-onnx 1.13.7. Stage 8 installs Whisper Tiny multilingual INT8 explicitly by downloading only the encoder, decoder, and token runtime files from a pinned upstream revision. Each file resumes through HTTP Range into a `.part` file, is SHA-256 verified before rename, and the versioned directory becomes active only after the complete verified set is present. Wi-Fi-only and free-space gates run before acquisition. Compressed audio is decoded locally to mono PCM and fed to the offline Whisper recognizer in bounded 25-second chunks so a long podcast does not require one full-file `FloatArray`; each returned segment currently carries the real chunk time range rather than claiming sentence-level timestamps the Kotlin wrapper does not expose. After ASR succeeds, Android sends only segment JSON through `TranslationService`; the public backend base URL is injected through the `LINGOPLAY_TRANSLATION_API_BASE_URL` Gradle property into BuildConfig, with no provider secret in the APK. Stage 5 uses platform `TextToSpeech.synthesizeToFile` and `UtteranceProgressListener`, selecting only an installed voice matching the selected target language whose `isNetworkConnectionRequired` flag is false. Stage 6 no longer creates a full-duration PCM WAV: the original audio is decoded in bounded chunks, normalized/resampled when required by the AAC encoder, ducked around translated speech, mixed with TTS PCM in memory, and streamed directly into `MediaCodec` AAC. Final remux keeps the compressed video and writes video/audio samples in presentation-time order. Android playback uses one player clock on the already mixed MP4; unsafe dual-player live blending remains disabled until it can be implemented inside one playback graph. Stage 7 copies successful final MP4s plus translation metadata into app-specific Movies storage (with an internal-storage fallback), exposes them through a scoped `FileProvider` only when the user chooses Share, and supports reopen/delete without broad storage permission.

## Backend
Cloudflare Worker TypeScript with four release-boundary routes:
- `GET /health` — liveness and media-boundary declaration.
- `POST /v1/translate` — validates timestamped segment JSON, forwards only compact transcript text/metadata to the configured provider, validates the provider's one-result-per-ID response, and returns normalized translated text.
- `GET /v1/entitlements` — returns the unauthenticated free capability baseline.
- `POST /v1/entitlements/verify` — accepts only an Apple transaction ID or Google Play purchase token, queries the corresponding authenticated store API, validates app/package and exact Plus product IDs, and returns a server-authoritative active/free result. Missing provider credentials and provider failures fail closed.

The Worker must never accept multipart/form-data, audio/*, video/*, or opaque media blobs. Native clients batch at no more than 80 segments / 10,000 source characters per request, comfortably below the Worker limits of 240 segments / 24,000 source characters / 64 KiB body. Segment IDs are stable across the round trip; source timestamps remain client-owned and are combined with translated text locally.

## Model storage boundary
- iOS ASR: `Application Support/LingoPlay/Models/WhisperKit/active-model.txt` points to the validated WhisperKit model directory under the same root. The installer writes this pointer only after download + prewarm/load validation; transcription then runs with network model download disabled.
- iOS neural TTS: `Application Support/LingoPlay/Models/NeuralVoice/active-model.txt` points only to the exact validated VAIS1000 voice-pack version after archive SHA-256, safe extraction, and runtime-file checks.
- Android ASR: `filesDir/lingoplay/models/sherpa-whisper/active-model.txt` points to a versioned verified directory containing the pinned encoder ONNX, decoder ONNX, and tokens text files. Legacy `.../current` lookup remains a compatibility fallback.
- Android neural TTS: `filesDir/lingoplay/models/neural-voice/active-model.txt` points only to the exact validated VAIS1000 voice-pack version after archive SHA-256, safe streaming extraction, and runtime-file checks.
- Model acquisition is always an explicit user action because download size, network use, and storage impact are material. Import/processing never silently starts model acquisition.

## Billing boundary
- iOS `PlusStore` loads `com.lingoplay.plus.weekly` and `com.lingoplay.plus.monthly`, listens to `Transaction.updates`, uses `AppStore.sync()` for Restore, and treats StoreKit verification as necessary but not sufficient in production. Release entitlement is granted only after `PlusEntitlementService` sends the transaction ID/environment to `/v1/entitlements/verify` and receives `authority=server`, `plan=plus`, `reason=active`.
- `ios/Products.storekit` remains attached to the XcodeGen Run scheme for development before an Apple Developer account exists. `.xcode` transactions are accepted only behind `#if DEBUG`; Release has no local StoreKit authority.
- Android Google Play Billing 9.1.0 provides products and purchase tokens for the same weekly/monthly IDs. `PURCHASED` no longer grants Plus by itself: the token must be confirmed by `/v1/entitlements/verify`; pending/on-hold/expired/mismatched/unverifiable purchases remain locked. Acknowledgement occurs only after server verification.
- The Worker uses App Store Server API transaction lookup or Google Play Android Publisher `subscriptionsv2` lookup. Exact bundle/package IDs and Plus product IDs are enforced; expiry/revocation/state are checked at verification time. Provider secrets are not present in clients.
- A sideload/debug build with no matching Play Console product configuration reports products unavailable and does not fabricate price, purchase state, or entitlement. Without Apple/Google server credentials, the verification endpoint fails closed; this is expected until developer accounts exist.
- Store notification delivery (App Store Server Notifications v2 / Play RTDN), production products/signing, and real sandbox/license-tester evidence remain explicit external account blockers documented in `docs/release/store-readiness.md`.

## Risk hardening after Stage 9
- Android model acquisition resolves redirect hops explicitly and reapplies the same `Range` header, so resumable downloads survive Hugging Face/CDN 3xx transitions.
- Android bounded ASR chunks remain memory-safe but prefer a quiet low-RMS boundary near the end of the chunk; continuous audio falls back to the hard budget boundary.
- Android retains the already extracted audio cache while ASR is blocked only by a missing model, then resumes recognition from that file after installation instead of re-demuxing the source video.
- iOS Wi-Fi-only model acquisition waits for an actual `NWPathMonitor` callback rather than a fixed-delay sample of `currentPath`.
- StoreKit 2 transactions are finished only after DEBUG Xcode StoreKit authorization or successful production server verification. Unverified, expired, revoked, provider-unreachable, or server-unconfirmed transactions never grant Release access.
- Stage 6 media protections remain authoritative: original soundtrack is preserved/ducked, Android remux is PTS-interleaved, AAC capability fallback/resampling is active, playback is single-clock, and rendered cache is age/size bounded.

## Stage 10 quality boundary
- Android skips near-silent decoded chunks before Whisper, still uses bounded memory, and chooses low-energy chunk boundaries where available. Synthesized speech clips are RMS-normalized with a peak ceiling before mix; summed PCM uses a soft limiter rather than hard clipping.
- iOS keeps the original soundtrack and uses a gentler 120 ms duck envelope / higher background floor for less pumping around Vietnamese speech.
- The normal Stage 10 path remains adaptive soundtrack ducking and does not claim stem separation. Stage 20 adds a separate opt-in Clean Background path that is enabled only when the native runtime and checksum-verified Spleeter model are both present.

## Stage 11 lifecycle boundary
- Both clients copy selected video into app-owned local storage before processing and persist a small local recovery checkpoint. The checkpoint also carries the immutable processing configuration (source/target language, preferred voice, dubbing mode, subtitle mode), so Resume cannot silently adopt Settings changed after the original run began. If the process is interrupted, Home offers Resume/Discard; resume uses prepared audio when it still exists and otherwise restarts from the owned source video.
- Android playback lifetime is keyed by the final MP4 path. `AndroidView.onRelease` stops only the view leaving composition; publishing the initial nullable view reference and normal recomposition never stop playback. Changing files creates a fresh player with fresh readiness/position state. Physical-device Compose tests exercise initial play, repeated entry, speed changes and file replacement.
- Android Picture-in-Picture uses the same Activity/VideoView playback path. iOS playback uses `AVPlayerViewController` with Picture-in-Picture enabled on the same AVPlayer and a playback audio session.
- Recovery is the guarantee; unlimited background inference is not. No long-running WorkManager/BGProcessing architecture is presented as guaranteed immediate execution.

## Stage 12 privacy/release boundary
- iOS ships `PrivacyInfo.xcprivacy` with tracking disabled, declares Purchase History as linked/non-tracking App Functionality data because Stage 21 sends the Store transaction identifier to LingoPlay for entitlement verification, and keeps required-reason declarations for app-only UserDefaults, app-container file timestamps, and disk-space checks.
- Android disables cleartext traffic with a network-security config while backup/data extraction remains disabled.
- Both clients keep bounded local diagnostics containing only timestamps plus event codes; transcript/media names/error details are not written to the diagnostic log and the log is not uploaded.
- `scripts/verify_release_privacy.py` validates the privacy manifest, Android network policy, and iOS PiP/privacy project wiring in both platform CI workflows.

## Stage 13 interaction boundary
- User-facing dubbing preferences are durable and have real downstream effects: source-language choice configures Whisper language behavior, target-language choice drives translation/TTS, installed offline voice selection is honored, and dubbing mode changes actual duck floor/fade/dub gain.
- Subtitle mode is `Off`, translated-only, or bilingual and controls Player rendering on both clients. Playback speed changes the active platform player rather than only a displayed label.
- Android intentionally does not show a fake live Original↔Dub slider for its already-mixed MP4. Controls that cannot act are hidden or rendered without a chevron/action affordance; About and Plus surfaces are real screens/sheets.

## Stage 14 advanced-capability boundary
- Android Plus uses Google Play Billing 9.1.0 and iOS uses StoreKit 2 for store interaction, while Stage 21 moves Release entitlement authority to the Worker-backed store verification path. Neither client treats a persisted local flag, StoreKit verification alone, or Play `PURCHASED` alone as production authority. Android Billing connection startup is serialized and service disconnects schedule a single reconnect; subscription offer selection prefers the base-plan offer instead of arbitrary list ordering.
- Both clients retain the Stage 14 source-separation seam. Stage 20 now supplies a real local Spleeter implementation behind it; capability is runtime + verified-model gated, off by default, and independent from the normal adaptive-ducking path.
- Stage 14 shipped only installed system voices. Later stages add the explicit Vietnamese neural preset, opt-in multi-speaker/EN-ZH cloning, and Stage 20 source separation; emotion synthesis remains unavailable.

## Stage 15 architecture/testability boundary
- Android Compose root owns app-level orchestration/navigation/state wiring only. Rendering is split by domain, dubbing preferences live in a plain state holder backed by a persistence interface, and the processing chain is delegated to `AndroidProcessingCoordinator` through a typed runtime boundary. Root code is structurally forbidden from directly invoking Whisper transcription, translation, TTS, or timeline mix services.
- iOS keeps `@Observable AppModel` as the app-facing state source, while view rendering is split by screen domain and pure preference/playback presentation logic lives in focused extensions/policy types. No ObservableObject/KMP rewrite is introduced.
- `contracts/product-contract.json` is the machine-readable product-policy contract. `verify_product_contract.py` checks native parity for language ordering, playback rates, dubbing-mode duck/gain/fade values, Plus product IDs, Clean Background model/runtime pins, opt-in defaults, transient-stem behavior, and the separate cross-device certification flag.
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

## Stage 17.1 optional neural-TTS boundary
- Android and iOS expose one optional Vietnamese VAIS1000 VITS/Piper voice pack through the existing offline-TTS seam. The user must explicitly install and then explicitly select it; installed platform voices remain the default and safe fallback.
- Both installers resume partial HTTPS downloads, verify the exact 67,154,040-byte archive and SHA-256 before extraction, reject traversal/links/oversized expansion, validate model/tokens/espeak data, and publish only a versioned active pointer. Delete removes the entire voice-pack root.
- sherpa-onnx is pinned to 1.13.7 on both platforms. Android uses the existing local AAR plus Apache Commons Compress 1.28.0; iOS resolves the exact SherpaOnnx and SWCompression 4.9.1 Swift packages.
- Neural inference is Vietnamese-only, runs with one engine instance per job and one or two CPU threads, and feeds measured WAV clips into the existing duration-fit/mix/remux path. Emotion, cloning, arbitrary reference audio, and multi-speaker mapping remain disabled.
- Attribution and upstream disposition are recorded in `THIRD_PARTY_NOTICES.md` and `docs/research/mobile-neural-tts.md`. The pack is not bundled in source, APK, or IPA.
- Source/build gates are necessary but not sufficient: Stage 17 remains open until MEIZU and iPhone runs verify real audio, real-time factor, memory, thermal behavior, final remux, and export.

## Stage 18 offline-translation boundary
- Users explicitly select `Cloud` or `Offline`; the immutable processing snapshot, recovery checkpoint, and Library metadata preserve that route. Neither client silently falls back across the privacy boundary.
- Cloud retains the transcript-only Cloudflare request path. Offline uses Google ML Kit Translation pinned to Android 17.0.3 and the official iOS CocoaPod 8.0.0 for `en`, `vi`, `ja`, and `zh`; media and transcript input/output stay on-device during inference.
- English support is built in; optional Vietnamese/Japanese/Chinese models are downloaded/deleted only from Settings and checked before inference. Missing source/target models stop with a visible error. Same-language translation is a local identity transform.
- ML Kit remains proprietary related software and may contact Google for model/runtime updates plus performance/utilization metrics. Settings/About disclose this, and offline translated text is rendered with visible Google Translate attribution.
- The built iOS app must contain resolved numeric `CFBundleVersion` and `CFBundleShortVersionString` values from the existing pre-release identity (`0.0.0`, build `1`). MLKitCommon 13.0.0 reads these before its legacy CFBundle fallback; empty metadata must fail host-app tests and the Release bundle verifier before an IPA is accepted.
- iOS CI generates the Xcode project, installs CocoaPods 1.16.2, resolves `GoogleMLKit/Translate` 8.0.0, and builds/tests the workspace. Source gates reject bypassing the Pods workspace or reintroducing an implicit cloud route.

## Stage 18.5 runtime-integrity boundary
- iOS processing is one tracked task per run with a UUID run identity plus immutable media/config snapshot. Back/import/recovery replacement cancels the old task, and every async stage/progress callback rejects stale run IDs before mutating state, saving Library output, clearing recovery, or navigating.
- iOS recovery records an optional processing run ID. Completion clears only the checkpoint owned by that run, preventing an older cancelled job from deleting a newer retry checkpoint, including retries of the same source media.
- Neural TTS routing is target-language aware on both platforms: the downloadable VAIS1000 path is eligible only for Vietnamese output. A stale neural voice preference on EN/JA/ZH routes to an installed system voice instead of entering the Vietnamese-only engine.
- Android Offline translation rejects detected languages outside the product-supported EN/VI/JA/ZH set before model lookup, so users are never told to install an unsupported model that Settings cannot offer.
- Cloud translation normalizes source/target BCP-47 tags to base language codes before Workers AI and short-circuits same-language jobs locally. iOS cloud requests use an explicit 60-second request timeout and cue-only transcripts fail as local no-speech instead of a fabricated backend-response error.
- System/neural TTS UUID session files are transient. Failed synthesis deletes its own session immediately, successful processing deletes generated clips after timeline mix/playback-session construction, and cold start removes stale TTS sessions left by interrupted processes.
- Stage 18.5 does not add speculative Android non-AAC transcoding or change the measured duration-overlap policy without a failing real-media fixture; those remain evidence-driven follow-ups rather than unverified rewrites.

## Stage 19.2 audit repairs
- Cloning Resume applies checkpoint opt-in AND current Settings consent. This revalidates permission while preserving the remaining immutable checkpoint settings and speaker map.
- ZipVoice requires EN/ZH for detected reference speech and output. Unsupported reference sources and transcripts with no eligible reference use ordinary offline voices; no cloud fallback or reusable voicebank is introduced.
- Any secondary speaker contributing at least 120 ms makes the whole ASR chunk unknown, even below 35% of the primary duration. This is conservative attribution, not word-level diarization or guaranteed acoustic purity. Labels remain ordered within each diarization result; they do not authenticate identity across independent reclustering.
- Android reference extraction decodes bounded chunks once and retains only selected <=15-second windows. iOS reads selected AVAudioFile frame ranges off MainActor and directly copies already-matching mono Float32 PCM instead of routing it through AVAudioConverter. Native diarization still accepts a full PCM array with the existing 30-minute Android / 15-minute iOS cap; those caps are not measured guarantees against device memory pressure.
- Parent TTS orchestration owns successful group output until transfer. A later clone/system/neural failure or cancellation removes every accumulated session. Android native session ownership lives outside the dispatcher hop so cancelled result delivery cannot orphan generated audio.
- Mix/remux retains the complete dubbed audio track after the source video ends. iOS extends original-audio/video composition tracks with empty timeline ranges to the dubbed duration, so output and Library duration reflect the extended media without synthesizing extra video frames. Linh's Vietnamese system rate remains 0.82 baseline and 1.18 maximum fit multiplier; existing overlap placement remains unchanged.
- Swift recovery writes check cancellation at the actor storage boundary. A cancelled processing task remains owned until native return; replacement runs await it and generation-scoped recovery refresh rejects stale UI writes. Native synchronous inference has cooperative checks before/after calls; Task cancellation is not a native hard-abort watchdog.

## Stage 20 Clean Background boundary
- Clean Background is explicit and defaults off in both native preference stores. The immutable processing config and recovery checkpoint preserve that choice; Resume recomputes separation from durable prepared audio rather than persisting a reusable stem library.
- Both clients pin the currently served `sherpa-onnx-spleeter-2stems-fp16.tar.bz2` envelope at 35,271,738 bytes with SHA-256 `d54561979bd2e08a51e7dbd99ac36bb47564e089eefd403636dbca93e811bba2`; the prior published envelope digest `c6c5c4307673bc6813ddf58d4efdff57c26d2dfc3f25b05c7a32db453d70aca6` remains accepted only when the extracted ONNX files match the separately pinned official sizes/hashes. Install/delete is explicit, outside the base app, storage-gated, traversal/link/expansion constrained, and activation is versioned only after validation.
- iOS uses the pinned sherpa-onnx 1.13.7 Swift/C surface. Android keeps the same 1.13.7 AAR and adds a minimal app-owned JNI/CMake bridge to its exported source-separation C API rather than adding a second inference runtime.
- Separation consumes prepared audio in bounded chunks and writes temporary `vocals.wav` plus `accompaniment.wav`. Vocals feed ASR, diarization and cloning-reference extraction; accompaniment replaces the original soundtrack input for final mix. When Clean Background is off, the existing audio path is unchanged.
- Temporary stems stay owned by the active processing run and are deleted on success, failure, cancellation or stale-result rejection. Missing executable runtime or verified model fails closed and never silently falls back after the user explicitly enabled Clean Background.
- Capability UI reports executable readiness only when runtime + verified installed model are present. `cleanBackgroundVerified=false` remains a separate cross-device quality/performance certification flag until physical Android and iPhone output evidence exists.

## Current stage
Stage 18 is functionally closed. Stage 19.2 engineering/CI closure is complete on `871c853`: Android run `33949966315` and iOS run `33949966327` both succeeded, including the focused iOS Stage 19 runtime regressions. Stage 20 source-separation engineering is being closed with source/unit/build/CI evidence; physical Meizu/iPhone output and performance certification are intentionally excluded from the current closure request. Stage 21 production distribution remains separate.
