# Mobile neural TTS decision record

> researched 2026-09-04 · Stage 17.1 · immutable upstream disposition

## Decision

Adopt sherpa-onnx 1.13.7 with the Vietnamese VITS/Piper VAIS1000 medium voice pack as an optional, explicit on-device download on Android and iOS. Keep installed platform voices as the default and safe fallback. Do not market the single pack as “premium” or “natural” until physical-device A/B quality and performance evidence exists.

## Accepted upstreams

| Component | Exact pin | License | Maintenance/runtime evidence | Disposition |
|---|---|---|---|---|
| k2-fsa/sherpa-onnx | tag v1.13.7, commit 917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e | Apache-2.0 | Native Kotlin/Android and Swift/iOS offline TTS APIs; active upstream with releases | Adapt behind LingoPlay TTS seam |
| rhasspy/piper-voices Vietnamese VAIS1000 medium model | revision 3d796cc2f2c884b3517c527507e084f7bb245aea | Piper Voices repository: MIT; underlying VAIS-1000 corpus: CC BY 4.0 | One Vietnamese VITS speaker, 22,050 Hz | Upstream voice provenance |
| tsolomko/SWCompression | tag 4.9.1, commit 03a68e67991815a267e28174a0a01fbe0cff937b | MIT | Active Swift package with BZip2 and TAR support; deployment target matches LingoPlay | iOS archive adapter |
| Apache Commons Compress | 1.28.0 | Apache-2.0 | Maintained Java BZip2/TAR streaming APIs | Android archive adapter |

Primary sources:

- https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.7
- https://huggingface.co/csukuangfj/vits-piper-vi_VN-vais1000-medium/tree/3d92898cf942406c23330da466a831d3d923c2a2
- https://huggingface.co/rhasspy/piper-voices/tree/3d796cc2f2c884b3517c527507e084f7bb245aea/vi/vi_VN/vais1000/medium
- https://github.com/tsolomko/SWCompression/tree/4.9.1
- https://commons.apache.org/proper/commons-compress/

## Exact voice-pack contract

- Archive: vits-piper-vi_VN-vais1000-medium.tar.bz2
- URL: https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-vi_VN-vais1000-medium.tar.bz2
- Compressed bytes: 67,154,040
- SHA-256: fa1367710767d36ed5cf13b4a449e20c35ffd12791c2e47c2e64142bfa55551a
- Model: vi_VN-vais1000-medium.onnx, 63,149,198 bytes (measured inside the pinned archive)
- Tokens: tokens.txt, 921 bytes
- Required phoneme data: espeak-ng-data
- App version key: vi-vais1000-medium-fa136771
- Observed archive shape: 397 entries, 81,146,991 total file bytes, 63,149,198-byte largest entry

The related csukuangfj conversion repository was reviewed at revision 3d92898cf942406c23330da466a831d3d923c2a2, but its current ONNX byte size differs from the official sherpa release archive. It is therefore a provenance/reference pin, not the runtime byte identity. Runtime identity is the archive size and SHA-256 above.

Both clients resume into a partial file, verify the exact compressed size and SHA-256, extract into a staging directory, validate required runtime files, then atomically publish a version pointer. Archive extraction rejects absolute/out-of-root paths, empty or dot components, backslashes, non-file/non-directory entries including links, more than 1,024 entries, entries over 80 MiB, or total expansion over 128 MiB. The pack is never bundled in the APK/IPA.

## Rejected or deferred candidates

| Candidate | Reason | Disposition |
|---|---|---|
| VieNeu-TTS v3 Turbo | Apache-2.0 and richer Vietnamese prosody/voices, but its official mobile Android/iOS SDK roadmap is still incomplete | Reference for later quality work |
| VieNeu-TTS.cpp | MIT native experiment, but very small adoption, no releases, and no production Android/iOS examples | Reject for current production runtime |
| VieNeu v4 hosted API | Proprietary/network path conflicts with offline voice boundary | Reject |
| Piper runtime | Current licensing/runtime path is unsuitable for this commercial mobile integration; original repository is archived | Reject as app runtime |
| VIVOS-derived packs | Non-commercial/share-alike licensing is incompatible with intended product use | Reject |
| Python/PyTorch-only Vietnamese TTS projects | No bounded native mobile runtime or package fit | Reject |

## Product and safety boundary

- Neural TTS is used only when the user explicitly selects the installed neural voice ID.
- Missing, deleted, or invalid neural files route to the installed offline system-voice path.
- The installer is user-triggered, Wi-Fi-aware, cancellable, resumable, checksum-verified, versioned, and deletable.
- The engine uses one TTS instance per job with one or two CPU threads.
- Voice cloning, arbitrary reference audio, emotion tags, and multi-speaker mapping remain disabled.
- Only synthesized local WAV clips enter the existing local duration-fit/mix/remux pipeline; no media is uploaded.
- Physical Android and iPhone output, real-time factor, peak memory, and thermal behavior are required before Stage 17 closes or quality marketing changes.
