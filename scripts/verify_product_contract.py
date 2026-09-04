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
IOS_PLUS = (ROOT / "ios/LingoPlay/PlusStore.swift").read_text(encoding="utf-8")
ANDROID_SEPARATION = (ROOT / "android/app/src/main/java/com/lingoplay/app/SourceSeparation.kt").read_text(encoding="utf-8")
IOS_SEPARATION = (ROOT / "ios/LingoPlay/SourceSeparation.swift").read_text(encoding="utf-8")
ANDROID_NEURAL_ACQUISITION = (ROOT / "android/app/src/main/java/com/lingoplay/app/NeuralVoiceAcquisition.kt").read_text(encoding="utf-8")
ANDROID_NEURAL_RUNTIME = (ROOT / "android/app/src/main/java/com/lingoplay/app/NeuralTextToSpeech.kt").read_text(encoding="utf-8")
ANDROID_SETTINGS = (ROOT / "android/app/src/main/java/com/lingoplay/app/LingoPlaySettingsScreens.kt").read_text(encoding="utf-8")
IOS_NEURAL_ACQUISITION = (ROOT / "ios/LingoPlay/NeuralVoiceAcquisition.swift").read_text(encoding="utf-8")
IOS_NEURAL_RUNTIME = (ROOT / "ios/LingoPlay/NeuralTextToSpeech.swift").read_text(encoding="utf-8")
IOS_SETTINGS = (ROOT / "ios/LingoPlay/LibrarySettingsViews.swift").read_text(encoding="utf-8")


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

require(CONTRACT["capabilities"]["cleanBackgroundVerified"] is False, "contract Clean Background remains unverified")
require("val engine: SourceSeparationEngine = UnavailableSourceSeparationEngine" in ANDROID_SEPARATION, "Android Clean Background unavailable engine")
require("UnavailableSourceSeparationEngine()" in IOS_SEPARATION, "iOS Clean Background unavailable engine")

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
    "preferredVoiceId == NeuralVoicePackManifest.voiceId && neuralVoiceInstalled" in ANDROID_NEURAL_RUNTIME,
    "Android neural route requires explicit installed selection",
)
require(
    "preferredVoiceIdentifier == NeuralVoicePackManifest.voiceIdentifier && neuralVoiceInstalled" in IOS_NEURAL_RUNTIME,
    "iOS neural route requires explicit installed selection",
)
require(neural["modelLicense"] == "MIT", "contract records original Piper voice license")
require(neural["datasetLicense"] == "CC-BY-4.0", "contract records VAIS-1000 corpus license")
require(CONTRACT["capabilities"]["neuralVoiceEmotionEnabled"] is False, "contract neural emotion remains disabled")
require(CONTRACT["capabilities"]["voiceCloningEnabled"] is False, "contract voice cloning remains disabled")
require("Emotion and cloning are unavailable" in ANDROID_SETTINGS, "Android discloses emotion/cloning unavailable")
require("emotion and voice cloning are not enabled" in IOS_SETTINGS, "iOS discloses emotion/cloning unavailable")

print("Product contract verification PASSED")
