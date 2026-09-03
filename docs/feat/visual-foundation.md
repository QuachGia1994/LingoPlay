# Visual foundation

> updated 2026-09-04 · pre-release

## Product flow
Launch shell — branded splash with indeterminate "Preparing LingoPlay" state; it does not pretend AI models are loading before model integration exists.

1. Home — brand promise, local import CTA, recent translated videos, processing progress.
2. Prepare — selected video, source auto-detect, Vietnamese target, voice, dubbing mode, subtitle style, one primary action.
3. Processing — five understandable AI stages with real state labels rather than technical implementation names.
4. Player — media-first player, bilingual subtitle card, signature Original ↔ Dub blend control, speed/subtitle actions.
5. Library & Settings — translated/offline media and simple consumer settings without model tuning controls.

## Visual language
- Dark graphite background with restrained purple→cyan accent.
- Soft gradient/glass surfaces; video and primary action remain the strongest hierarchy.
- Rounded media cards and bottom navigation.
- One generated LingoPlay play/speech/wave mark is shared by launcher icons, native launch surfaces, splash/loading UI, and in-app brand surfaces on iOS and Android.

## Interaction principles
- No editor timeline, track inspector, codec controls, model selectors, or developer terminology.
- The path from import to playback should be understandable without onboarding copy.
- Premium capabilities appear at the point of use, not as constant paywall interruptions.
- Original/Dub audio blend is the signature player interaction and must be reachable without opening settings.

## Current acceptance criteria
- iOS and Android expose the same branded splash plus five core product states and comparable information hierarchy.
- Home Import opens the native local-file picker instead of navigating directly to demo content.
- Prepare displays real selected filename, duration, size, and audio-track availability.
- Processing step 1 reflects real local audio preparation; step 2 now reflects the real ASR model state and shows genuine local transcript evidence when an installed model succeeds.
- When the speech model is absent, Processing shows a model-missing state and does not silently download one; when ASR completes, the screen exposes detected language, normalized text, and timestamped segment count.
- Successful dubbed outputs are copied from temporary render cache into durable app-owned local storage with compact subtitle metadata; Home, Library, and Offline show only those real saved outputs.
- Saved outputs can be reopened into the real player, shared/exported through the native system share surface, or deleted locally; empty library states never masquerade demo content as completed work.
- Backend and frontend share the same trust-boundary language: media stays local; only JSON translation requests leave the device.
- No scraping/downloader affordance is present.
