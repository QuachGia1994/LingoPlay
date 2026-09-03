# Android quality baseline for 2027

> updated 2026-09-03 · pre-release

LingoPlay treats Android's 2027 Play quality requirements as release gates rather than advisory checks.

## Memory
- `android:largeHeap="false"`; the app does not request an artificially larger heap.
- Long-video ASR never materializes full-video PCM in one array. Audio is decoded to mono PCM in bounded chunks.
- The inference budget scales down by device memory class / low-RAM status:
  - low-RAM or <=192 MiB class: 1 thread, 10-second chunks
  - <=256 MiB: up to 2 threads, 15-second chunks
  - <=384 MiB: up to 3 threads, 20-second chunks
  - larger devices: up to 4 threads, 25-second chunks
- `onTrimMemory` / `onLowMemory` requests inference cancellation so native recognizer memory can be released when the UI is hidden or the system is under pressure.
- Video UI does not retain decoded full-resolution bitmaps; the current visual shell uses Compose primitives/placeholders.

## DEX/R8
- Release uses the AGP 9.3 `optimization { enable = true }` path, enabling R8 code optimization/shrinking/obfuscation and optimized resource shrinking.
- Non-transitive R classes remain enabled.
- JNI protection is deliberately narrow: only classes/members with native entry points are kept. The rest of application and sherpa Java/Kotlin bytecode remains eligible for R8 optimization.
- A release is not considered verified until optimized `assembleRelease` and `bundleRelease` complete and the mapping output is retained in CI.

## 16 KB native page size
- APK packaging uses modern uncompressed native-library packaging (`useLegacyPackaging = false`).
- Only 64-bit `arm64-v8a` and `x86_64` ABIs are shipped in this foundation.
- `scripts/verify_android_release.py` parses every shipped 64-bit ELF `PT_LOAD` segment and requires >=16384-byte alignment. For an APK it also checks the ZIP data offset of stored native libraries is 16 KB aligned.
- sherpa-onnx 1.13.7 release AAR was independently checked before app build: all 8 relevant arm64-v8a/x86_64 native libraries report 16384-byte `PT_LOAD` alignment.
- The final app APK is checked again after R8/packaging because dependency compliance alone does not prove final APK ZIP alignment.

## Local release gate
Run, in order:
1. `testDebugUnitTest`
2. `lintDebug`
3. `assembleDebug`
4. `assembleRelease`
5. `bundleRelease`
6. `python scripts/verify_android_release.py android/app/build/outputs/apk/release/app-release-unsigned.apk`

Only after all six gates pass is Android allowed to be pushed with the CI workflow enabled.

## Physical-device media gate
Stage 6 also has an instrumentation smoke harness under `android/app/src/androidTest` because MediaCodec/MediaMuxer behavior cannot be proven by JVM tests or CI alone. The harness packages deterministic H.264/AAC source fixtures plus small PCM TTS clips and invokes the real `TimelineMixService` on device.

Current physical evidence:
- MEIZU Lucky 08, Android 14 / API 34, arm64-v8a.
- Short 3-second mix/remux fixture: PASS.
- Sustained 120-second mix/remux fixture: PASS.
- Instrumentation result: 2 tests, 0 failures, 0 errors, 0 skipped.
- The 120-second run used the device Codec2 AAC decoder/encoder at 48 kHz, produced a final MP4 with both video and mixed audio, and reported 0 µs audio-track drift from `MPEG4Writer`.
- During the sustained run the app stayed `largeHeap=false`; observed memory was about 170 MiB PSS / 258 MiB RSS, with native heap around 19 MiB. Most remaining PSS was debug/test DEX rather than a full-media PCM allocation.
- Qualcomm/Flyme logs emit benign-looking `csd0 too small` and `Stop() called but track is not started or stopped` messages after the writer reports the MOOV atom written. Both generated MP4s pass track/duration assertions, so this is recorded as an OEM diagnostic warning rather than a Stage 6 failure. Do not remove the required muxer `stop()` call merely to silence vendor logging.

A broader OEM matrix remains desirable, but this representative physical-device gate closes the previously missing hardware-codec evidence for Stage 6.

## Performance follow-up
The debug APK survived a 150-event Monkey smoke without crash/ANR, but `gfxinfo` on the same Meizu reported 87/443 janky frames (19.64%), p50 19 ms, p95 26 ms, p99 150 ms, plus an initial 52-frame startup skip. Because this measurement is debug + Monkey and GPU time remained comparatively low, it is tracked as a UI/startup profiling follow-up rather than a media-pipeline blocker. Re-measure a profile/release-like build before setting a production frame-time budget.

## CI
GitHub Actions reproduces the same JDK 17 / Gradle wrapper / Android 37.0 / Build Tools 36 toolchain, reruns unit tests and lint, builds APK+AAB, runs the same 16 KB verifier, and uploads debug/release artifacts plus R8 mapping.
