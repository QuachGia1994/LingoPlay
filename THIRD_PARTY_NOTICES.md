# Third-party notices

LingoPlay includes or downloads the following third-party components for optional offline neural voice and translation features.

## Google ML Kit Translation

LingoPlay optionally integrates Google ML Kit Translation as proprietary related software under the Google APIs Terms of Service and the ML Kit Terms. Android pins `com.google.mlkit:translate:17.0.3`; iOS pins the official `GoogleMLKit/Translate` CocoaPod 8.0.0.

ML Kit translation input and output text is processed on-device. The SDK may still contact Google to download or update language models/runtime components and may send performance/utilization metrics as described by Google. Users explicitly install/delete language models and explicitly select Offline mode; LingoPlay does not silently fall back from Offline to Cloud.

Product and terms: https://developers.google.com/ml-kit/language/translation

ML Kit terms: https://developers.google.com/ml-kit/terms

Translation terms: https://developers.google.com/ml-kit/language/translation/translation-terms

Attribution requirements: https://docs.cloud.google.com/translate/attribution

Translated output produced by this optional path is labeled “Powered by Google Translate.” Google and Google Translate are trademarks of Google LLC. No endorsement is implied.

## sherpa-onnx

Copyright the sherpa-onnx contributors. Licensed under the Apache License 2.0.

Source and license: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.7

Pinned source commit: 917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e.

### Stage 19 speaker diarization model packs

The optional Speaker AI feature downloads sherpa-onnx release artifacts only after explicit user action: `sherpa-onnx-pyannote-segmentation-3-0.tar.bz2` and `nemo_en_titanet_small.onnx`. LingoPlay pins their exact sizes and SHA-256 identities in `contracts/product-contract.json`; they are not bundled in the base application. Speaker analysis runs locally and LingoPlay does not upload the analyzed audio.

Upstream model examples/releases: https://github.com/k2-fsa/sherpa-onnx

### Stage 20 Spleeter two-stem source-separation model pack

The optional Clean Background feature downloads sherpa-onnx release artifact `sherpa-onnx-spleeter-2stems-fp16.tar.bz2` only after explicit user action. LingoPlay pins the archive at 35,271,738 bytes with SHA-256 `c6c5c4307673bc6813ddf58d4efdff57c26d2dfc3f25b05c7a32db453d70aca6`; it is not bundled in the base application. Separation runs locally, produces transient vocals/accompaniment stems for the current processing run, and does not upload user audio.

Spleeter source is published by Deezer under the MIT License. LingoPlay uses the sherpa-onnx converted/runtime-compatible model path and retains sherpa-onnx's Apache-2.0 runtime notice separately above.

Spleeter source and license: https://github.com/deezer/spleeter

sherpa-onnx source-separation releases/examples: https://github.com/k2-fsa/sherpa-onnx

### Stage 19 ZipVoice/Vocos cloning model packs

The optional local Voice Cloning feature downloads the sherpa-onnx `sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2` pack together with `vocos_24khz.onnx` only after explicit user action. LingoPlay pins exact byte sizes and SHA-256 identities in `contracts/product-contract.json`; the packs are not bundled in the base application. The feature is limited by LingoPlay to English/Chinese output, is disabled by default, requires explicit ownership/permission consent, and uses only an ephemeral clear single-speaker reference from the current processing run. LingoPlay does not persist a reusable cloned-voice profile or upload the reference audio.

Upstream ZipVoice/Vocos examples/releases: https://github.com/k2-fsa/sherpa-onnx

These model-pack provenance notes do not add a license claim beyond the licenses published by the respective upstream artifacts; redistribution/release review must retain the upstream notices applicable to the exact downloaded model versions.

## Vietnamese VAIS1000 VITS/Piper voice pack

Voice pack: vits-piper-vi_VN-vais1000-medium.

The original voice files are published in the rhasspy/piper-voices repository, which declares the MIT license. Original source revision: 3d796cc2f2c884b3517c527507e084f7bb245aea.

Original voice source: https://huggingface.co/rhasspy/piper-voices/tree/3d796cc2f2c884b3517c527507e084f7bb245aea/vi/vi_VN/vais1000/medium

LingoPlay downloads the official sherpa-onnx release archive and pins its runtime identity by exact compressed size (67,154,040 bytes) plus SHA-256 fa1367710767d36ed5cf13b4a449e20c35ffd12791c2e47c2e64142bfa55551a.

A related sherpa conversion/repack repository was reviewed at revision 3d92898cf942406c23330da466a831d3d923c2a2. It is retained as a provenance reference and is not treated as byte-identical to the pinned release archive.

Repack reference: https://huggingface.co/csukuangfj/vits-piper-vi_VN-vais1000-medium/tree/3d92898cf942406c23330da466a831d3d923c2a2

The model card identifies its training corpus as “VAIS-1000: Vietnamese Speech Synthesis Corpus” and gives the corpus license as Creative Commons Attribution 4.0 International (CC BY 4.0).

Corpus source: https://ieee-dataport.org/documents/vais-1000-vietnamese-speech-synthesis-corpus

Corpus license: https://creativecommons.org/licenses/by/4.0/

Attribution: VAIS-1000 corpus creators as identified by the linked IEEE DataPort record; Piper Voices and sherpa-onnx model-pack contributors for the published voice and conversion. No endorsement by the original authors is implied. LingoPlay may download this pack only after an explicit user action; the pack is not stored in this repository or bundled in the application binary.

## SWCompression

Copyright the SWCompression contributors. Licensed under the MIT License.

Source and license: https://github.com/tsolomko/SWCompression/tree/4.9.1

Pinned source commit: 03a68e67991815a267e28174a0a01fbe0cff937b.

## Apache Commons Compress

Copyright The Apache Software Foundation. Licensed under the Apache License 2.0.

Source and license: https://commons.apache.org/proper/commons-compress/

Version used: 1.28.0.
