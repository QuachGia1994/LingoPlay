#!/usr/bin/env python3
"""Inspect a built iOS .app for App Store size/symbol/debugging guardrails."""

from __future__ import annotations

import argparse
import plistlib
import subprocess
from pathlib import Path

TEXT_LIMIT = 500 * 1024 * 1024
BUNDLE_LIMIT = 4 * 1024 * 1024 * 1024
OTA_WARNING = 200 * 1024 * 1024
PAGE = 16 * 1024


def command(*args: str) -> str:
    completed = subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return completed.stdout.strip()


def directory_size(root: Path) -> int:
    return sum(path.stat().st_size for path in root.rglob("*") if path.is_file())


def is_macho(path: Path) -> bool:
    try:
        return "Mach-O" in command("file", "-b", str(path))
    except (subprocess.CalledProcessError, OSError):
        return False


def segments(path: Path) -> list[dict[str, int | str]]:
    output = command("otool", "-l", str(path))
    result: list[dict[str, int | str]] = []
    current: dict[str, int | str] | None = None
    for raw in output.splitlines():
        line = raw.strip()
        if line.startswith("Load command "):
            if current and current.get("segname"):
                result.append(current)
            current = {}
            continue
        if current is None:
            continue
        if line.startswith("segname "):
            current["segname"] = line.split(maxsplit=1)[1]
        elif line.startswith("vmaddr "):
            current["vmaddr"] = int(line.split()[1], 0)
        elif line.startswith("vmsize "):
            current["vmsize"] = int(line.split()[1], 0)
        elif line.startswith("fileoff "):
            current["fileoff"] = int(line.split()[1], 0)
        elif line.startswith("filesize "):
            current["filesize"] = int(line.split()[1], 0)
    if current and current.get("segname"):
        result.append(current)
    return result


def uuid_set(path: Path) -> set[str]:
    output = command("dwarfdump", "--uuid", str(path))
    values = set()
    for line in output.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[0] == "UUID:":
            values.add(fields[1].upper())
    return values


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("app", type=Path)
    parser.add_argument("--ipa", type=Path)
    parser.add_argument("--dsym", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--translation-base-url")
    args = parser.parse_args()

    app = args.app
    if not app.is_dir():
        raise SystemExit(f"FAIL iOS binary: missing app bundle {app}")

    with (app / "Info.plist").open("rb") as handle:
        info = plistlib.load(handle)
    executable_name = info.get("CFBundleExecutable")
    executable = app / str(executable_name)
    if not executable.is_file():
        raise SystemExit(f"FAIL iOS binary: missing executable {executable}")

    lines: list[str] = []

    def emit(message: str) -> None:
        print(message)
        lines.append(message)

    if args.translation_base_url is not None:
        expected_endpoint = args.translation_base_url.strip()
        if not expected_endpoint.startswith("https://"):
            raise SystemExit("FAIL iOS binary: expected translation endpoint must be non-empty HTTPS")
        actual_endpoint = info.get("LingoPlayTranslationAPIBaseURL")
        if actual_endpoint != expected_endpoint:
            raise SystemExit(
                "FAIL iOS binary: translation endpoint mismatch "
                f"actual={actual_endpoint!r} expected={expected_endpoint!r}"
            )
        emit(f"PASS bundled translation endpoint={actual_endpoint}")

    bundle_bytes = directory_size(app)
    if bundle_bytes > BUNDLE_LIMIT:
        raise SystemExit(f"FAIL iOS binary: uncompressed app bundle {bundle_bytes} exceeds 4 GB")
    emit(f"PASS app bundle bytes={bundle_bytes} <= {BUNDLE_LIMIT}")

    archs = command("lipo", "-archs", str(executable)).split()
    if "arm64" not in archs:
        raise SystemExit(f"FAIL iOS binary: arm64 missing from main executable architectures {archs}")
    emit(f"PASS main executable architectures={' '.join(archs)}")

    macho_files = [path for path in app.rglob("*") if path.is_file() and is_macho(path)]
    if executable not in macho_files:
        macho_files.insert(0, executable)
    text_total = 0
    misaligned: list[str] = []
    for binary in macho_files:
        for segment in segments(binary):
            if segment.get("segname") == "__TEXT":
                text_total += int(segment.get("vmsize", 0))
            name = str(segment.get("segname", ""))
            if name == "__PAGEZERO" or int(segment.get("filesize", 0)) <= 0:
                continue
            vmaddr = int(segment.get("vmaddr", 0))
            fileoff = int(segment.get("fileoff", 0))
            if vmaddr % PAGE or fileoff % PAGE:
                misaligned.append(f"{binary.relative_to(app)}:{name}:vmaddr={vmaddr:#x}:fileoff={fileoff:#x}")

    if text_total > TEXT_LIMIT:
        raise SystemExit(f"FAIL iOS binary: total __TEXT {text_total} exceeds 500 MB")
    emit(f"PASS total Mach-O __TEXT bytes={text_total} <= {TEXT_LIMIT}")
    emit(f"INFO Mach-O binaries={len(macho_files)}")

    frameworks = sorted((app / "Frameworks").glob("*.framework")) if (app / "Frameworks").is_dir() else []
    emit(f"INFO embedded dynamic framework directories={len(frameworks)}")
    for framework in frameworks:
        emit(f"INFO framework={framework.name}")

    if misaligned:
        emit(f"INFO 16KB segment diagnostic: {len(misaligned)} non-16KB vmaddr/fileoff entries; toolchain/App Store compatibility remains authoritative")
        for item in misaligned[:20]:
            emit(f"INFO alignment {item}")
    else:
        emit("PASS 16KB segment diagnostic: all file-backed segments are 16KB-aligned")

    if args.dsym is not None:
        dwarf = args.dsym / "Contents" / "Resources" / "DWARF" / executable.name
        if not dwarf.is_file():
            raise SystemExit(f"FAIL iOS binary: dSYM DWARF file missing at {dwarf}")
        app_uuids = uuid_set(executable)
        dsym_uuids = uuid_set(dwarf)
        if not app_uuids.intersection(dsym_uuids):
            raise SystemExit(f"FAIL iOS binary: dSYM UUID mismatch app={sorted(app_uuids)} dsym={sorted(dsym_uuids)}")
        emit(f"PASS dSYM UUID match count={len(app_uuids.intersection(dsym_uuids))}")

    if args.ipa is not None:
        if not args.ipa.is_file():
            raise SystemExit(f"FAIL iOS binary: missing IPA {args.ipa}")
        ipa_bytes = args.ipa.stat().st_size
        emit(f"INFO unsigned IPA bytes={ipa_bytes}")
        if ipa_bytes > OTA_WARNING:
            emit("WARN unsigned IPA exceeds 200 MB; Apple OTA warning is variant/app-thinning based, so confirm with signed App Thinning Size Report before submission")
        else:
            emit(f"PASS unsigned IPA bytes={ipa_bytes} <= 200 MB diagnostic threshold")

    emit("iOS binary release verification PASSED")
    if args.report is not None:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
