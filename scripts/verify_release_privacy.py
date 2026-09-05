#!/usr/bin/env python3
from __future__ import annotations

import plistlib
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def verify_ios_privacy() -> None:
    path = ROOT / "ios" / "LingoPlay" / "PrivacyInfo.xcprivacy"
    if not path.is_file():
        fail("iOS PrivacyInfo.xcprivacy is missing")
    with path.open("rb") as handle:
        data = plistlib.load(handle)
    if data.get("NSPrivacyTracking") is not False:
        fail("iOS privacy manifest must declare NSPrivacyTracking=false")
    collected = data.get("NSPrivacyCollectedDataTypes", [])
    if len(collected) != 1:
        fail("iOS privacy manifest must declare exactly the Stage 21 purchase-history data type")
    purchase = collected[0]
    if purchase.get("NSPrivacyCollectedDataType") != "NSPrivacyCollectedDataTypePurchaseHistory":
        fail("iOS privacy manifest must declare Purchase History for server entitlement verification")
    if purchase.get("NSPrivacyCollectedDataTypeLinked") is not True:
        fail("purchase history must be declared linked to the store account")
    if purchase.get("NSPrivacyCollectedDataTypeTracking") is not False:
        fail("purchase history must not be used for tracking")
    if purchase.get("NSPrivacyCollectedDataTypePurposes") != ["NSPrivacyCollectedDataTypePurposeAppFunctionality"]:
        fail("purchase history must be limited to app functionality")

    expected = {
        "NSPrivacyAccessedAPICategoryUserDefaults": "CA92.1",
        "NSPrivacyAccessedAPICategoryFileTimestamp": "C617.1",
        "NSPrivacyAccessedAPICategoryDiskSpace": "E174.1",
    }
    found: dict[str, set[str]] = {}
    for item in data.get("NSPrivacyAccessedAPITypes", []):
        category = item.get("NSPrivacyAccessedAPIType")
        reasons = set(item.get("NSPrivacyAccessedAPITypeReasons", []))
        if category:
            found[category] = reasons
    for category, reason in expected.items():
        if reason not in found.get(category, set()):
            fail(f"iOS privacy manifest missing {category} reason {reason}")
    print("PASS iOS privacy manifest")


def verify_android_network_security() -> None:
    manifest = ROOT / "android" / "app" / "src" / "main" / "AndroidManifest.xml"
    root = ET.parse(manifest).getroot()
    application = root.find("application")
    if application is None:
        fail("Android application node is missing")
    if application.get(f"{ANDROID_NS}usesCleartextTraffic") != "false":
        fail("Android must disable cleartext traffic")
    if application.get(f"{ANDROID_NS}networkSecurityConfig") != "@xml/network_security_config":
        fail("Android networkSecurityConfig is not wired")

    config = ROOT / "android" / "app" / "src" / "main" / "res" / "xml" / "network_security_config.xml"
    network = ET.parse(config).getroot()
    base = network.find("base-config")
    if base is None or base.get("cleartextTrafficPermitted") != "false":
        fail("Android base network security config must reject cleartext")
    print("PASS Android network security")


def verify_ios_project_wiring() -> None:
    project = (ROOT / "ios" / "project.yml").read_text(encoding="utf-8")
    if "PrivacyInfo.xcprivacy" not in project or "buildPhase: resources" not in project:
        fail("XcodeGen project does not wire PrivacyInfo.xcprivacy as a resource")
    if "INFOPLIST_FILE: LingoPlay/Info.plist" not in project:
        fail("XcodeGen project does not merge the partial Info.plist")
    info_path = ROOT / "ios" / "LingoPlay" / "Info.plist"
    with info_path.open("rb") as handle:
        info = plistlib.load(handle)
    if "audio" not in info.get("UIBackgroundModes", []):
        fail("iOS partial Info.plist does not enable background audio for PiP")
    print("PASS iOS project privacy/PiP wiring")


def main() -> None:
    verify_ios_privacy()
    verify_android_network_security()
    verify_ios_project_wiring()
    print("Release privacy verification PASSED")


if __name__ == "__main__":
    main()
