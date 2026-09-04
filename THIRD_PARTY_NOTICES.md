# Third-party notices

LingoPlay includes or downloads the following third-party components for its optional offline Vietnamese Neural Voice feature.

## sherpa-onnx

Copyright the sherpa-onnx contributors. Licensed under the Apache License 2.0.

Source and license: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.7

Pinned source commit: 917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e.

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
