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

## CI
GitHub Actions reproduces the same JDK 17 / Gradle wrapper / Android 37.0 / Build Tools 36 toolchain, reruns unit tests and lint, builds APK+AAB, runs the same 16 KB verifier, and uploads debug/release artifacts plus R8 mapping.
