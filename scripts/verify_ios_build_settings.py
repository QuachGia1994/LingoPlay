#!/usr/bin/env python3
"""Verify effective iOS Release settings emitted by Xcode/XcodeGen."""

from __future__ import annotations

import argparse
from pathlib import Path


def parse_settings(text: str, target: str = "LingoPlay") -> dict[str, str]:
    lines = text.splitlines()
    header = f"Build settings for action build and target {target}:"
    try:
        start = next(index for index, line in enumerate(lines) if line.strip() == header) + 1
    except StopIteration as error:
        raise SystemExit(f"FAIL iOS build settings: missing target block {target!r}") from error

    end = next(
        (
            index
            for index in range(start, len(lines))
            if lines[index].strip().startswith("Build settings for action build and target ")
        ),
        len(lines),
    )
    result: dict[str, str] = {}
    for raw in lines[start:end]:
        line = raw.strip()
        if " = " not in line:
            continue
        key, value = line.split(" = ", 1)
        if key and key.replace("_", "").isalnum():
            result[key] = value.strip()
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("settings_file", type=Path)
    args = parser.parse_args()
    if not args.settings_file.is_file():
        raise SystemExit(f"FAIL iOS build settings: missing {args.settings_file}")

    settings = parse_settings(args.settings_file.read_text(encoding="utf-8", errors="replace"))

    expected = {
        "SWIFT_COMPILATION_MODE": "wholemodule",
        "SWIFT_OPTIMIZATION_LEVEL": "-O",
        "DEAD_CODE_STRIPPING": "YES",
        "DEBUG_INFORMATION_FORMAT": "dwarf-with-dsym",
        "ENABLE_NS_ASSERTIONS": "NO",
    }
    for key, value in expected.items():
        actual = settings.get(key)
        if actual != value:
            raise SystemExit(f"FAIL iOS build settings: {key}={actual!r}, expected {value!r}")
        print(f"PASS {key}={actual}")

    linker_flags = settings.get("OTHER_LDFLAGS", "")
    if "exported_symbols_list" in linker_flags or "/dev/null" in linker_flags:
        raise SystemExit("FAIL iOS build settings: unsafe exported-symbol linker flags are active")
    print("PASS no unsafe exported-symbol linker flags")

    order_file = settings.get("ORDER_FILE", "").strip()
    if order_file:
        raise SystemExit(f"FAIL iOS build settings: unprofiled ORDER_FILE is active: {order_file}")
    print("PASS no unprofiled ORDER_FILE")

    for key in ("LLVM_LTO", "STRIP_INSTALLED_PRODUCT", "STRIP_STYLE", "STRIP_SWIFT_SYMBOLS", "LD_EXPORT_SYMBOLS"):
        print(f"INFO {key}={settings.get(key, '<unset>')}")

    print("iOS Release build-settings verification PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
