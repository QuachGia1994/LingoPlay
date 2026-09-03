# Product

> updated 2026-09-03 · pre-release

## Positioning
LingoPlay is for consumers who want to understand foreign-language videos without learning editing software. The primary job is: import a local video, receive a Vietnamese dubbed version, and watch it immediately.

## Falsifiable USP
For supported devices, LingoPlay processes the video/audio media path locally and does not upload the user's video or audio to the backend; only compact translation and entitlement JSON crosses the network.

## Audience
Primary: mobile-first viewers consuming foreign-language entertainment, learning material, news, podcasts, and short-form video.

Secondary: language learners who benefit from bilingual subtitles and controllable original/dub audio blend.

## Revenue path
Free provides local import, basic Vietnamese system voice, standard playback, subtitles, and single-speaker fast dub. Plus is planned for natural Vietnamese voices, multi-speaker mapping, clean-dub/source separation, background/PiP playback for local media, dual-audio controls, smart speed, dictionary, summary, and offline model/cache management.

## Product boundaries
- No third-party media scraping/downloading in store builds.
- No video/audio storage or processing on the backend.
- No client-embedded provider API secrets.
- Consumer controls only; model/runtime tuning remains automatic.

## Validation before paid implementation
The visual foundation should be tested as a five-state interactive prototype first. The paid AI feature set is justified only if users understand the import → process → watch flow without explanation and identify natural voice/clean dub/background playback as valuable enough to pay for.
