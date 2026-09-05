#!/usr/bin/env python3
"""Fail when Android/iOS product behavior constants drift from the shared contract."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = json.loads((ROOT / "contracts/product-contract.json").read_text(encoding="utf-8"))
ANDROID_PREFS = (ROOT / "android/app/src/main/java/com/lingoplay/app/DubbingPreferences.kt").read_text(encoding="utf-8")
IOS_PREFS = (ROOT / "ios/LingoPlay/DubbingPreferences.swift").read_text(encoding="utf-8")
ANDROID_PLUS = (ROOT / "android/app/src/main/java/com/lingoplay/app/AndroidPlusStore.kt").read_text(encoding="utf-8")
ANDROID_PLUS_ENTITLEMENT = (ROOT / "android/app/src/main/java/com/lingoplay/app/PlusEntitlementService.kt").read_text(encoding="utf-8")
IOS_PLUS = (ROOT / "ios/LingoPlay/PlusStore.swift").read_text(encoding="utf-8")
IOS_PLUS_ENTITLEMENT = (ROOT / "ios/LingoPlay/PlusEntitlementService.swift").read_text(encoding="utf-8")
BACKEND_ENTITLEMENTS = (ROOT / "backend/src/entitlements.ts").read_text(encoding="utf-8")
BACKEND_INDEX = (ROOT / "backend/src/index.ts").read_text(encoding="utf-8")
ANDROID_SEPARATION = (ROOT / "android/app/src/main/java/com/lingoplay/app/SourceSeparation.kt").read_text(encoding="utf-8")
ANDROID_SEPARATION_MODEL = (ROOT / "android/app/src/main/java/com/lingoplay/app/SourceSeparationModel.kt").read_text(encoding="utf-8")
ANDROID_COORDINATOR = (ROOT / "android/app/src/main/java/com/lingoplay/app/AndroidProcessingCoordinator.kt").read_text(encoding="utf-8")
IOS_SEPARATION = (ROOT / "ios/LingoPlay/SourceSeparation.swift").read_text(encoding="utf-8")
IOS_SEPARATION_MODEL = (ROOT / "ios/LingoPlay/SourceSeparationModel.swift").read_text(encoding="utf-8")
IOS_APP_MODEL = (ROOT / "ios/LingoPlay/AppModel.swift").read_text(encoding="utf-8")
IOS_SOURCE_SEPARATION_PIPELINE = (ROOT / "ios/LingoPlay/AppModel+SourceSeparation.swift").read_text(encoding="utf-8")
ANDROID_NEURAL_ACQUISITION = (ROOT / "android/app/src/main/java/com/lingoplay/app/NeuralVoiceAcquisition.kt").read_text(encoding="utf-8")
ANDROID_NEURAL_RUNTIME = (ROOT / "android/app/src/main/java/com/lingoplay/app/NeuralTextToSpeech.kt").read_text(encoding="utf-8")
ANDROID_SETTINGS = (
    (ROOT / "android/app/src/main/java/com/lingoplay/app/LingoPlaySettingsScreens.kt").read_text(encoding="utf-8")
    + (ROOT / "android/app/src/main/java/com/lingoplay/app/LingoPlayStage19Settings.kt").read_text(encoding="utf-8")
    + (ROOT / "android/app/src/main/java/com/lingoplay/app/LingoPlayStage20Settings.kt").read_text(encoding="utf-8")
)
IOS_NEURAL_ACQUISITION = (ROOT / "ios/LingoPlay/NeuralVoiceAcquisition.swift").read_text(encoding="utf-8")
IOS_NEURAL_RUNTIME = (ROOT / "ios/LingoPlay/NeuralTextToSpeech.swift").read_text(encoding="utf-8")
IOS_SETTINGS = (
    (ROOT / "ios/LingoPlay/LibrarySettingsViews.swift").read_text(encoding="utf-8")
    + (ROOT / "ios/LingoPlay/Stage19ModelSettingsViews.swift").read_text(encoding="utf-8")
    + (ROOT / "ios/LingoPlay/Stage20SettingsView.swift").read_text(encoding="utf-8")
)
ANDROID_OFFLINE_TRANSLATION = (ROOT / "android/app/src/main/java/com/lingoplay/app/OfflineTranslation.kt").read_text(encoding="utf-8")
IOS_OFFLINE_TRANSLATION = (ROOT / "ios/LingoPlay/OfflineTranslation.swift").read_text(encoding="utf-8")
ANDROID_SPEAKER = (ROOT / "android/app/src/main/java/com/lingoplay/app/SpeakerDiarization.kt").read_text(encoding="utf-8")
IOS_SPEAKER = (ROOT / "ios/LingoPlay/SpeakerDiarization.swift").read_text(encoding="utf-8")
ANDROID_CLONING = (ROOT / "android/app/src/main/java/com/lingoplay/app/VoiceCloning.kt").read_text(encoding="utf-8")
IOS_CLONING = (ROOT / "ios/LingoPlay/VoiceCloning.swift").read_text(encoding="utf-8")
ANDROID_TTS_CACHE = (ROOT / "android/app/src/main/java/com/lingoplay/app/TTSCachePolicy.kt").read_text(encoding="utf-8")
IOS_TTS_CACHE = (ROOT / "ios/LingoPlay/TTSCachePolicy.swift").read_text(encoding="utf-8")
ANDROID_BUILD = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
IOS_PODFILE = (ROOT / "ios/Podfile").read_text(encoding="utf-8")


def require(condition: bool, label: str) -> None:
    if not condition:
        raise SystemExit(f"FAIL product contract: {label}")
    print(f"PASS {label}")


def close(a: float, b: float) -> bool:
    return abs(a - b) < 1e-6


def android_mode_values(enum_name: str) -> tuple[float, float, int]:
    line = next((line for line in ANDROID_PREFS.splitlines() if line.strip().startswith(f"{enum_name}(")), None)
    require(line is not None, f"Android mode {enum_name} exists")
    match = re.search(r",\s*([0-9.]+)f,\s*([0-9.]+)f,\s*(\d+)\),?\s*$", line or "")
    require(match is not None, f"Android mode {enum_name} tuple parses")
    return float(match.group(1)), float(match.group(2)), int(match.group(3))


def ios_property_block(property_name: str) -> str:
    marker = f"    var {property_name}:"
    start = IOS_PREFS.index(marker)
    end = IOS_PREFS.index("\n    }\n", start) + len("\n    }\n")
    return IOS_PREFS[start:end]


def ios_case_value(property_name: str, case_name: str) -> float:
    block = ios_property_block(property_name)
    direct = re.search(rf"case \.{re.escape(case_name)}:\s*([0-9.]+)", block)
    if direct:
        return float(direct.group(1))
    combined = re.search(r"case ([^:]+):\s*([0-9.]+)", block)
    for cases, value in re.findall(r"case ([^:]+):\s*([0-9.]+)", block):
        names = [item.strip().lstrip(".") for item in cases.split(",")]
        if case_name in names:
            return float(value)
    raise SystemExit(f"FAIL product contract: iOS {property_name} missing case {case_name}")


require(CONTRACT.get("schemaVersion") == 1, "contract schema v1")

source = CONTRACT["languages"]["source"]
target = CONTRACT["languages"]["target"]
require(source == ["auto", "en", "vi", "ja", "zh"], "source language order")
require(target == ["vi", "en", "ja", "zh"], "target language order")
require('AUTO(null, "Auto Detect")' in ANDROID_PREFS, "Android Auto Detect remains null")
require('case auto' in IOS_PREFS and 'var code: String? { self == .auto ? nil : rawValue }' in IOS_PREFS, "iOS Auto Detect remains nil")
for code in target:
    require(f'("{code}",' in ANDROID_PREFS, f"Android target language {code}")
    require(f'case {code}' in IOS_PREFS, f"iOS target language {code}")

speeds = CONTRACT["playbackSpeeds"]
android_speeds = ", ".join(f"{value}f" for value in speeds)
ios_speeds = ", ".join(str(value) for value in speeds)
require(f"listOf({android_speeds})" in ANDROID_PREFS, "Android playback speeds")
require(f"[{ios_speeds}]" in IOS_PREFS, "iOS playback speeds")

offline_translation = CONTRACT["offlineTranslation"]
require(offline_translation["modes"] == ["cloud", "offline"], "translation modes remain explicit")
require(
    set(offline_translation["supportedLanguages"]) == set(target)
    and len(offline_translation["supportedLanguages"]) == len(target),
    "offline translation language parity",
)
require(offline_translation["builtInLanguages"] == ["en"], "English remains the built-in translation language")
require(offline_translation["downloadableLanguages"] == ["vi", "ja", "zh"], "downloadable translation language order")
require(offline_translation["explicitModelLifecycle"] is True, "offline models require explicit lifecycle")
require(offline_translation["silentCloudFallback"] is False, "offline translation forbids silent cloud fallback")
require('implementation("com.google.mlkit:translate:17.0.3")' in ANDROID_BUILD, "Android pins ML Kit Translate 17.0.3")
require("pod 'GoogleMLKit/Translate', '8.0.0'" in IOS_PODFILE, "iOS pins official ML Kit Translate pod 8.0.0")
for code in offline_translation["supportedLanguages"]:
    require(f'"{code}"' in ANDROID_OFFLINE_TRANSLATION, f"Android offline translation language {code}")
    require(f'"{code}"' in IOS_OFFLINE_TRANSLATION, f"iOS offline translation language {code}")
require('code == "en" || downloaded.contains' in ANDROID_OFFLINE_TRANSLATION, "Android treats English as built in")
require('require(code != "en")' in ANDROID_OFFLINE_TRANSLATION, "Android never manages English as a remote model")
require('var result: Set<String> = ["en"]' in IOS_OFFLINE_TRANSLATION, "iOS treats English as built in")
require('guard normalizedCode != "en"' in IOS_OFFLINE_TRANSLATION, "iOS never creates an English remote model")
for name, source in (("Android", ANDROID_OFFLINE_TRANSLATION), ("iOS", IOS_OFFLINE_TRANSLATION)):
    require("will not switch to cloud automatically" in source, f"{name} missing-model error forbids fallback")
require("Powered by Google Translate" in ANDROID_SETTINGS, "Android shows Google Translate attribution")
require("Powered by Google Translate" in IOS_SETTINGS, "iOS shows Google Translate attribution")

mode_names = {
    "balanced": ("BALANCED", "balanced"),
    "speechFocus": ("SPEECH_FOCUS", "speechFocus"),
    "originalFocus": ("ORIGINAL_FOCUS", "originalFocus"),
}
for contract_name, values in CONTRACT["dubbingModes"].items():
    android_name, ios_name = mode_names[contract_name]
    a_floor, a_gain, a_fade = android_mode_values(android_name)
    require(close(a_floor, values["duckFloor"]), f"Android {contract_name} duck floor")
    require(close(a_gain, values["dubGain"]), f"Android {contract_name} dub gain")
    require(a_fade == values["duckFadeMs"], f"Android {contract_name} duck fade")

    require(close(ios_case_value("duckFloor", ios_name), values["duckFloor"]), f"iOS {contract_name} duck floor")
    require(close(ios_case_value("dubVolume", ios_name), values["dubGain"]), f"iOS {contract_name} dub gain")
    require(close(ios_case_value("duckFadeSeconds", ios_name), values["duckFadeMs"] / 1000.0), f"iOS {contract_name} duck fade")

weekly = CONTRACT["plus"]["weeklyProductId"]
monthly = CONTRACT["plus"]["monthlyProductId"]
require(f'WEEKLY_ID = "{weekly}"' in ANDROID_PLUS, "Android weekly Plus ID")
require(f'MONTHLY_ID = "{monthly}"' in ANDROID_PLUS, "Android monthly Plus ID")
require(f'weeklyID = "{weekly}"' in IOS_PLUS, "iOS weekly Plus ID")
require(f'monthlyID = "{monthly}"' in IOS_PLUS, "iOS monthly Plus ID")
plus_contract = CONTRACT["plus"]
require(plus_contract["productionAuthority"] == "server", "Plus production authority is server")
require(plus_contract["verificationPath"] == "/v1/entitlements/verify", "Plus verification endpoint is pinned")
require(plus_contract["iosLocalTestingAuthority"] == "xcode-debug-only", "iOS local StoreKit authority is debug-only")
require(plus_contract["releaseFailsClosedWithoutServerVerification"] is True, "release Plus fails closed without server verification")
require('url.pathname === "/v1/entitlements/verify"' in BACKEND_INDEX, "backend exposes server entitlement verification endpoint")
require('authority: "server"' in BACKEND_ENTITLEMENTS, "backend entitlement response is server-authoritative")
for product_id in (weekly, monthly):
    require(product_id in BACKEND_ENTITLEMENTS, f"backend recognizes Plus product {product_id}")
require("PlusEntitlementService.verifyGoogle" in ANDROID_PLUS, "Android routes purchased Play tokens to backend verification")
require("isPlus = purchased.isNotEmpty()" not in ANDROID_PLUS, "Android never grants Plus from PURCHASED state alone")
require('authority == "server"' in ANDROID_PLUS_ENTITLEMENT and 'reason == "active"' in ANDROID_PLUS_ENTITLEMENT, "Android accepts only active server authority")
require("entitlementService.verify(transaction: transaction)" in IOS_PLUS, "iOS routes StoreKit transactions to backend verification")
require("#if DEBUG" in IOS_PLUS and "transaction.environment == .xcode" in IOS_PLUS, "iOS Xcode StoreKit fallback is debug-only")
require('authority == "server"' in IOS_PLUS_ENTITLEMENT and 'reason == "active"' in IOS_PLUS_ENTITLEMENT, "iOS accepts only active server authority")
for state in plus_contract["googleActiveStates"]:
    require(state in BACKEND_ENTITLEMENTS, f"backend recognizes Google entitlement state {state}")

clean_background = CONTRACT["cleanBackground"]
require(CONTRACT["capabilities"]["cleanBackgroundVerified"] is False, "cross-device Clean Background certification remains pending")
require(clean_background["enabledByDefault"] is False, "Clean Background remains opt-in")
require(clean_background["localOnly"] is True, "Clean Background is local-only")
require(clean_background["bundledInBaseApp"] is False, "separator model stays outside base app")
require(clean_background["referencePersistence"] == "ephemeral-current-run", "separated stems are ephemeral")
require(clean_background["stems"] == ["vocals", "accompaniment"], "two-stem contract remains vocals/accompaniment")
for name, source in (("Android", ANDROID_SEPARATION_MODEL), ("iOS", IOS_SEPARATION_MODEL)):
    require(f'{clean_background["archiveBytes"]:_}' in source, f"{name} separator archive size pinned")
    require(clean_background["archiveSha256"] in source, f"{name} separator archive hash pinned")
    require(clean_background["previousArchiveSha256"] in source, f"{name} separator previous envelope hash recognized")
    for stem_name, model_file in clean_background["modelFiles"].items():
        require(model_file["name"] in source, f"{name} {stem_name} model filename pinned")
        require(f'{model_file["bytes"]:_}' in source, f"{name} {stem_name} model size pinned")
        require(model_file["sha256"] in source, f"{name} {stem_name} model hash pinned")
    require(clean_background["version"] in source, f"{name} separator version pinned")
require('getBoolean("clean_background_enabled", false)' in ANDROID_PREFS, "Android Clean Background defaults off")
require('UserDefaults.standard.bool(forKey: "lingoplay.cleanBackgroundEnabled")' in IOS_APP_MODEL, "iOS Clean Background defaults off")
require("SourceSeparationNative.runtimeAvailable" in ANDROID_SEPARATION and "SourceSeparationModelStore.find(context) != null" in ANDROID_SEPARATION, "Android capability requires runtime plus verified installed model")
require("SourceSeparationRuntime.isAvailable" in IOS_SEPARATION and "SourceSeparationModelStore().model() != nil" in IOS_SEPARATION, "iOS capability requires runtime plus verified installed model")
require("cleanBackgroundEnabled" in ANDROID_COORDINATOR and "analysisAudio = stems.voice" in ANDROID_COORDINATOR and "backgroundAudio = stems.background" in ANDROID_COORDINATOR, "Android routes vocals for analysis and accompaniment for mix")
require("analysisAudioURL: stems.voiceURL" in IOS_SOURCE_SEPARATION_PIPELINE and "backgroundAudioURL: stems.backgroundURL" in IOS_SOURCE_SEPARATION_PIPELINE, "iOS routes vocals for analysis and accompaniment for mix")
require("cleanup()" in ANDROID_SEPARATION and "cleanup()" in IOS_SEPARATION, "both clients expose transient stem cleanup")
require("Clean Background Model" in ANDROID_SETTINGS and "Clean Background Model" in IOS_SETTINGS, "both clients expose explicit separator model lifecycle UI")

neural = CONTRACT["offlineNeuralVoice"]
require(neural["languageCode"] == "vi", "contract neural voice remains Vietnamese-only")
require(neural["enabledByDefault"] is False, "contract neural voice requires explicit selection")
require(neural["bundledInBaseApp"] is False, "contract neural pack stays outside base app")
for name, source in (
    ("Android", ANDROID_NEURAL_ACQUISITION),
    ("iOS", IOS_NEURAL_ACQUISITION),
):
    require(neural["id"] in source, f"{name} neural voice ID matches contract")
    require(f'{neural["archiveBytes"]:_}' in source, f"{name} neural archive size matches contract")
    require(neural["archiveSha256"] in source, f"{name} neural archive hash matches contract")
    require(f'{neural["modelBytes"]:_}' in source, f"{name} neural model size matches archive evidence")
    require(neural["version"] in source, f"{name} neural version follows archive digest")
    require(neural["sourceRevision"] in source, f"{name} neural source revision matches contract")
require(
    "preferredVoiceId == NeuralVoicePackManifest.voiceId" in ANDROID_NEURAL_RUNTIME
    and "neuralVoiceInstalled" in ANDROID_NEURAL_RUNTIME
    and "targetLanguage.substringBefore('-')" in ANDROID_NEURAL_RUNTIME,
    "Android neural route requires explicit installed Vietnamese selection",
)
require(
    "preferredVoiceIdentifier == NeuralVoicePackManifest.voiceIdentifier" in IOS_NEURAL_RUNTIME
    and "neuralVoiceInstalled" in IOS_NEURAL_RUNTIME
    and 'baseLanguage == "vi"' in IOS_NEURAL_RUNTIME,
    "iOS neural route requires explicit installed Vietnamese selection",
)
require(neural["modelLicense"] == "MIT", "contract records original Piper voice license")
require(neural["datasetLicense"] == "CC-BY-4.0", "contract records VAIS-1000 corpus license")
require(CONTRACT["capabilities"]["neuralVoiceEmotionEnabled"] is False, "contract neural emotion remains disabled")

multi = CONTRACT["multiSpeaker"]
require(multi["enabledByDefault"] is False, "multi-speaker remains opt-in")
require(multi["stableLabelsByFirstAppearance"] is True, "speaker labels are stable by first appearance")
require(multi["overlapPolicy"] == "unknown", "overlap policy never fabricates identity")
for name, source in (("Android", ANDROID_SPEAKER), ("iOS", IOS_SPEAKER)):
    require(f'{multi["segmentationArchiveBytes"]:_}' in source, f"{name} speaker segmentation archive size pinned")
    require(multi["segmentationArchiveSha256"] in source, f"{name} speaker segmentation archive hash pinned")
    require(f'{multi["segmentationModelBytes"]:_}' in source, f"{name} speaker segmentation model size pinned")
    require(multi["segmentationModelSha256"] in source, f"{name} speaker segmentation model hash pinned")
    require(f'{multi["embeddingModelBytes"]:_}' in source, f"{name} speaker embedding size pinned")
    require(multi["embeddingModelSha256"] in source, f"{name} speaker embedding hash pinned")
require('"speaker_${labels.size + 1}"' in ANDROID_SPEAKER, "Android stable speaker labels")
require('"speaker_\\(orderedLabels.count + 1)"' in IOS_SPEAKER, "iOS stable speaker labels")
require("SpeakerAttribution(null" in ANDROID_SPEAKER, "Android overlap remains unknown")
require("speakerID: nil" in IOS_SPEAKER, "iOS overlap remains unknown")
require('get() = enumValue("speaker_mode", SpeakerMode.SINGLE)' in ANDROID_PREFS, "Android multi-speaker defaults off")
require('?? "single") ?? .single' in (ROOT / "ios/LingoPlay/AppModel.swift").read_text(encoding="utf-8"), "iOS multi-speaker defaults off")

cloning = CONTRACT["voiceCloning"]
require(CONTRACT["capabilities"]["voiceCloningEnabled"] is True, "contract voice cloning capability enabled")
require(cloning["enabledByDefault"] is False, "voice cloning remains opt-in")
require(cloning["localOnly"] is True, "voice cloning is local-only")
require(cloning["requiresExplicitConsent"] is True, "voice cloning requires explicit consent")
require(cloning["supportedLanguages"] == ["en", "zh"], "voice cloning language boundary")
require(cloning["referencePersistence"] == "ephemeral-current-run", "voice cloning references are ephemeral")
for name, source in (("Android", ANDROID_CLONING), ("iOS", IOS_CLONING)):
    require(f'{cloning["archiveBytes"]:_}' in source, f"{name} cloning archive size pinned")
    require(cloning["archiveSha256"] in source, f"{name} cloning archive hash pinned")
    require(f'{cloning["vocoderBytes"]:_}' in source, f"{name} cloning vocoder size pinned")
    require(cloning["vocoderSha256"] in source, f"{name} cloning vocoder hash pinned")
    require('"en"' in source and '"zh"' in source, f"{name} cloning limited to EN/ZH")
    require("overlappingSpeaker" in source, f"{name} cloning rejects overlap")
require('getBoolean("voice_cloning_enabled", false)' in ANDROID_PREFS, "Android cloning consent defaults off")
require('UserDefaults.standard.bool(forKey: "lingoplay.voiceCloningEnabled")' in (ROOT / "ios/LingoPlay/AppModel.swift").read_text(encoding="utf-8"), "iOS cloning consent defaults off")
require("voiceCloningEnabled" in ANDROID_CLONING or "voiceCloningEnabled" in (ROOT / "android/app/src/main/java/com/lingoplay/app/AndroidProcessingCoordinator.kt").read_text(encoding="utf-8"), "Android cloning is consent-gated")
require("voiceCloningEnabled" in (ROOT / "ios/LingoPlay/AppModel.swift").read_text(encoding="utf-8"), "iOS cloning is consent-gated")
require('"clone-tts"' in ANDROID_TTS_CACHE, "Android cloned speech cache is purgeable")
require('"CloneTTS"' in IOS_TTS_CACHE, "iOS cloned speech cache is purgeable")
require("Use only voices you own or have" in ANDROID_SETTINGS, "Android cloning ownership disclosure")
require("Use only voices you own or have" in IOS_SETTINGS, "iOS cloning ownership disclosure")

print("Product contract verification PASSED")
