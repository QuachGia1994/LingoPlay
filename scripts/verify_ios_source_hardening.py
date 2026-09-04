#!/usr/bin/env python3
"""iOS source-level release hardening checks that are safe to run on any host."""

from __future__ import annotations

import plistlib
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IOS_ROOT = ROOT / "ios" / "LingoPlay"
PROJECT = (ROOT / "ios" / "project.yml").read_text(encoding="utf-8")
SWIFT = "\n".join(path.read_text(encoding="utf-8") for path in IOS_ROOT.rglob("*.swift"))


def fail(message: str) -> None:
    raise SystemExit(f"FAIL iOS source hardening: {message}")


def require(condition: bool, label: str) -> None:
    if not condition:
        fail(label)
    print(f"PASS {label}")


def privacy_categories() -> dict[str, set[str]]:
    path = IOS_ROOT / "PrivacyInfo.xcprivacy"
    with path.open("rb") as handle:
        manifest = plistlib.load(handle)
    result: dict[str, set[str]] = {}
    for item in manifest.get("NSPrivacyAccessedAPITypes", []):
        category = item.get("NSPrivacyAccessedAPIType")
        if category:
            result[category] = set(item.get("NSPrivacyAccessedAPITypeReasons", []))
    return result


def verify_required_reason_coverage() -> None:
    found = privacy_categories()
    mappings = (
        (
            ("UserDefaults",),
            "NSPrivacyAccessedAPICategoryUserDefaults",
            "CA92.1",
            "UserDefaults",
        ),
        (
            ("volumeAvailableCapacityForImportantUsageKey", "volumeAvailableCapacityKey", "volumeAvailableCapacityForOpportunisticUsageKey"),
            "NSPrivacyAccessedAPICategoryDiskSpace",
            "E174.1",
            "disk-space API",
        ),
        (
            ("contentModificationDateKey", "creationDateKey", "attributeModificationDate", "attributesOfItem"),
            "NSPrivacyAccessedAPICategoryFileTimestamp",
            "C617.1",
            "file-timestamp API",
        ),
        (
            ("systemUptime", "mach_absolute_time"),
            "NSPrivacyAccessedAPICategorySystemBootTime",
            None,
            "system boot-time API",
        ),
        (
            ("activeInputModes", "UITextInputMode.activeInputModes"),
            "NSPrivacyAccessedAPICategoryActiveKeyboards",
            None,
            "active-keyboards API",
        ),
    )

    for needles, category, expected_reason, label in mappings:
        used = any(needle in SWIFT for needle in needles)
        if not used:
            print(f"PASS no undeclared {label} use")
            continue
        require(category in found, f"privacy manifest declares {category} for detected {label}")
        if expected_reason is not None:
            require(expected_reason in found[category], f"privacy manifest keeps approved reason {expected_reason} for {label}")


def png_dimensions(path: Path) -> tuple[int, int] | None:
    with path.open("rb") as handle:
        header = handle.read(24)
    if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        return None
    return struct.unpack(">II", header[16:24])


def verify_image_budget() -> None:
    max_runtime_decode = 8 * 1024 * 1024
    assets = IOS_ROOT / "Assets.xcassets"
    runtime_pngs = [path for path in assets.rglob("*.png") if "AppIcon.appiconset" not in path.as_posix()]
    for path in runtime_pngs:
        dims = png_dimensions(path)
        require(dims is not None, f"valid PNG asset {path.relative_to(ROOT)}")
        width, height = dims
        decoded = width * height * 4
        require(decoded <= max_runtime_decode, f"runtime image decoded RGBA budget {path.name}: {decoded} <= {max_runtime_decode}")
    loose = [path for path in IOS_ROOT.rglob("*.png") if "Assets.xcassets" not in path.as_posix()]
    require(not loose, "no loose runtime PNGs outside the asset catalog")

    unsafe_decode_patterns = (
        "UIImage(data:",
        "UIImage(contentsOfFile:",
        "CGImageSourceCreateImageAtIndex(",
    )
    for pattern in unsafe_decode_patterns:
        require(pattern not in SWIFT, f"no unbounded raw image decode pattern {pattern}")


def verify_linker_source_config() -> None:
    lowered = PROJECT.lower()
    require("exported_symbols_list" not in lowered, "no exported_symbols_list linker hack")
    require("/dev/null" not in PROJECT, "no /dev/null symbol export hack")
    require("ORDER_FILE" not in PROJECT, "no unprofiled order-file optimization")
    print("PASS LTO remains toolchain/default-driven until measured evidence exists")


def main() -> None:
    verify_required_reason_coverage()
    verify_image_budget()
    verify_linker_source_config()
    print("iOS source hardening verification PASSED")


if __name__ == "__main__":
    main()
