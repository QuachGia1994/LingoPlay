#!/usr/bin/env python3
"""Stage 15 structural guardrails against god-file regression."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(condition: bool, label: str) -> None:
    if not condition:
        raise SystemExit(f"FAIL architecture: {label}")
    print(f"PASS {label}")


def size_under(relative: str, limit: int) -> None:
    path = ROOT / relative
    require(path.is_file(), f"exists {relative}")
    size = path.stat().st_size
    require(size <= limit, f"{relative} size {size} <= {limit}")


legacy = [
    ROOT / "android/app/src/main/java/com/lingoplay/app/LingoPlayScreens.kt",
    ROOT / "ios/LingoPlay/Screens.swift",
]
for path in legacy:
    require(not path.exists(), f"legacy god-file removed: {path.relative_to(ROOT)}")

size_under("android/app/src/main/java/com/lingoplay/app/LingoPlayApp.kt", 40_000)
size_under("ios/LingoPlay/AppModel.swift", 25_000)

android_ui = [
    "LingoPlayHomeScreens.kt",
    "LingoPlayProcessingScreen.kt",
    "LingoPlayPlayerScreen.kt",
    "LingoPlaySettingsScreens.kt",
    "LingoPlayUiComponents.kt",
]
for name in android_ui:
    size_under(f"android/app/src/main/java/com/lingoplay/app/{name}", 32_000)

ios_ui = [
    "HomePrepareViews.swift",
    "ProcessingView.swift",
    "PlayerView.swift",
    "LibrarySettingsViews.swift",
    "SharedViewComponents.swift",
]
for name in ios_ui:
    size_under(f"ios/LingoPlay/{name}", 32_000)

android_root = (ROOT / "android/app/src/main/java/com/lingoplay/app/LingoPlayApp.kt").read_text(encoding="utf-8")
require("fun SplashScreen(" not in android_root, "Android root does not render SplashScreen")
require("fun ProcessingScreen(" not in android_root, "Android root does not define ProcessingScreen")
require("fun SingleClockDubPlayer(" not in android_root, "Android root does not own player rendering")
require("LaunchedEffect(stageName, selectedMedia?.uri" not in android_root, "Android processing effect is not keyed by mutable media URI")
require("BackHandler(enabled = stage != Stage.HOME" in android_root, "Android root handles system Back for staged navigation")
for forbidden in (
    "SherpaWhisperSpeechRecognizer.transcribe(",
    "TranslationService.translate(",
    "SystemVietnameseTTSService.synthesize(",
    "TimelineMixService.render(",
):
    require(forbidden not in android_root, f"Android root delegates processing call: {forbidden}")
require((ROOT / "android/app/src/main/java/com/lingoplay/app/AndroidProcessingCoordinator.kt").is_file(), "Android processing coordinator exists")
require((ROOT / "android/app/src/androidTest/java/com/lingoplay/app/AndroidProcessingCoordinatorTest.kt").is_file(), "Android coordinator instrumentation test exists")

ios_model = (ROOT / "ios/LingoPlay/AppModel.swift").read_text(encoding="utf-8")
require(re.search(r"\bstruct\s+\w+View\b", ios_model) is None, "iOS AppModel contains no SwiftUI view structs")
require("DubbingPreferencePolicy.availableOfflineVoices()" not in ios_model, "iOS preference presentation extracted from AppModel core")

prefs_extension = ROOT / "ios/LingoPlay/AppModel+Preferences.swift"
playback_extension = ROOT / "ios/LingoPlay/AppModel+PlaybackPresentation.swift"
require(prefs_extension.is_file(), "iOS preferences extension exists")
require(playback_extension.is_file(), "iOS playback presentation extension exists")
require((ROOT / "ios/LingoPlay/PlaybackPresentationPolicy.swift").is_file(), "iOS playback presentation policy exists")
require((ROOT / "android/app/src/main/java/com/lingoplay/app/PlayerInteractionPolicy.kt").is_file(), "Android player interaction policy exists")
require((ROOT / "ios/LingoPlayTests/PolicyTests.swift").is_file(), "iOS policy unit tests exist")
home_prepare = (ROOT / "ios/LingoPlay/HomePrepareViews.swift").read_text(encoding="utf-8")
model_acquisition = (ROOT / "ios/LingoPlay/ModelAcquisition.swift").read_text(encoding="utf-8")
require("private struct SavedVideoRow" not in home_prepare, "iOS shared SavedVideoRow is cross-file accessible")
require("wifiOnly && !(await" not in model_acquisition, "iOS Wi-Fi async check avoids boolean autoclosure await")
for verifier in ("verify_ios_source_hardening.py", "verify_ios_build_settings.py", "verify_ios_binary.py"):
    require((ROOT / "scripts" / verifier).is_file(), f"iOS release verifier exists: {verifier}")

project_spec = (ROOT / "ios/project.yml").read_text(encoding="utf-8")
require("LingoPlayTests:" in project_spec, "XcodeGen unit-test target declared")
require("testTargets:" in project_spec and "- LingoPlayTests" in project_spec, "LingoPlay scheme includes unit tests")
require("DEAD_CODE_STRIPPING: YES" in project_spec, "iOS Release explicitly enables dead-code stripping")

android_workflow = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
ios_workflow = (ROOT / ".github/workflows/ios.yml").read_text(encoding="utf-8")
for name, workflow in (("Android", android_workflow), ("iOS", ios_workflow)):
    require("verify_product_contract.py" in workflow, f"{name} CI runs product contract gate")
    require("verify_architecture.py" in workflow, f"{name} CI runs architecture gate")
require("Run iOS unit tests" in ios_workflow, "iOS CI executes unit-test target")
require("verify_ios_source_hardening.py" in ios_workflow, "iOS CI runs source hardening gate")
require("verify_ios_build_settings.py" in ios_workflow, "iOS CI verifies effective Release build settings")
require("verify_ios_binary.py" in ios_workflow, "iOS CI audits built Mach-O/dSYM/size budgets")
require("LingoPlay-iOS-dSYM" in ios_workflow, "iOS CI publishes dSYM artifact")
require("LingoPlay-iOS-release-reports" in ios_workflow, "iOS CI publishes release reports")
require("compileDebugAndroidTestKotlin" in android_workflow, "Android CI compiles instrumentation tests")
android_build_spec = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
device_test_key = ROOT / "android/keystores/lingoplay-device-test.p12"
require(device_test_key.is_file(), "Android stable device-test signing key exists")
require('applicationIdSuffix = ".debug"' in android_build_spec, "Android Debug installs beside the production package")
require(
    'signingConfig = signingConfigs.getByName("deviceTest")' in android_build_spec,
    "Android Debug uses the stable device-test signing identity",
)
require("keytool -genkeypair" not in android_workflow, "Android CI does not rotate the test signing key per run")
require("keystores/lingoplay-device-test.p12" in android_workflow, "Android CI reuses stable device-test signing")
require("ORG_GRADLE_PROJECT_LINGOPLAY_TRANSLATION_API_BASE_URL" in android_workflow, "Android CI injects translation backend URL")
require("LINGOPLAY_TRANSLATION_API_BASE_URL" in ios_workflow, "iOS CI injects translation backend URL")
require("--translation-base-url" in ios_workflow, "iOS CI verifies endpoint inside built app bundle")
require("Verify translation backend wiring" in android_workflow, "Android CI fails closed when translation backend URL is missing")
require("Verify translation backend wiring" in ios_workflow, "iOS CI fails closed when translation backend URL is missing")
ios_info = (ROOT / "ios/LingoPlay/Info.plist").read_text(encoding="utf-8")
require("LingoPlayTranslationAPIBaseURL" in ios_info, "iOS source Info.plist declares translation endpoint key")
require("$(LINGOPLAY_TRANSLATION_API_BASE_URL)" in ios_info, "iOS source Info.plist expands translation endpoint build setting")
require(
    'LINGOPLAY_TRANSLATION_API_BASE_URL: "https://lingoplay-api.kim-phong619.workers.dev"' in project_spec,
    "iOS project has deterministic public production endpoint default",
)
translation_source = (ROOT / "ios/LingoPlay/Translation.swift").read_text(encoding="utf-8")
require("enum TranslationEndpointConfiguration" in translation_source, "iOS translation endpoint resolver exists")
require(
    "https://lingoplay-api.kim-phong619.workers.dev" in translation_source,
    "iOS translation resolver has production fallback",
)
android_translation = (ROOT / "android/app/src/main/java/com/lingoplay/app/Translation.kt").read_text(encoding="utf-8")
android_tts = (ROOT / "android/app/src/main/java/com/lingoplay/app/TextToSpeech.kt").read_text(encoding="utf-8")
ios_tts = (ROOT / "ios/LingoPlay/TextToSpeech.swift").read_text(encoding="utf-8")
for name, source in (("Android", android_translation), ("iOS", translation_source)):
    require("TranslationTextPolicy" in source, f"{name} translation text policy exists")
    require("stronglyEnglish" in source, f"{name} corrects strong English language evidence")
for name, source in (("Android", android_tts), ("iOS", ios_tts)):
    require("effectiveEndMs" in source, f"{name} extends local speech window on safe-rate overflow")
    require(
        "still longer than its source time window after safe speed fitting" not in source,
        f"{name} does not abort the whole job for one duration overflow",
    )
wrangler_spec = (ROOT / "backend/wrangler.jsonc").read_text(encoding="utf-8")
require('"binding": "AI"' in wrangler_spec, "Cloudflare Worker keeps Workers AI binding")
backend_source = (ROOT / "backend/src/index.ts").read_text(encoding="utf-8")
require('@cf/meta/m2m100-1.2b' in backend_source, "backend translation uses pinned Workers AI model")

print("Architecture verification PASSED")
