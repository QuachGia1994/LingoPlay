#!/usr/bin/env python3
import argparse
import io
import struct
import sys
import zipfile
from pathlib import Path

PAGE = 16 * 1024
PT_LOAD = 1
ELFCLASS64 = 2
EM_AARCH64 = 183
EM_X86_64 = 62


def parse_elf_alignment(data: bytes):
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    elf_class = data[4]
    endian = data[5]
    if endian == 1:
        order = "<"
    elif endian == 2:
        order = ">"
    else:
        raise ValueError("unknown ELF endianness")

    if elf_class == ELFCLASS64:
        machine = struct.unpack_from(order + "H", data, 18)[0]
        phoff = struct.unpack_from(order + "Q", data, 32)[0]
        phentsize = struct.unpack_from(order + "H", data, 54)[0]
        phnum = struct.unpack_from(order + "H", data, 56)[0]
        aligns = []
        for i in range(phnum):
            off = phoff + i * phentsize
            if off + phentsize > len(data):
                break
            p_type = struct.unpack_from(order + "I", data, off)[0]
            if p_type == PT_LOAD:
                aligns.append(struct.unpack_from(order + "Q", data, off + 48)[0])
        return machine, aligns
    raise ValueError(f"unsupported ELF class {elf_class}")


def local_data_offset(fp, info: zipfile.ZipInfo):
    fp.seek(info.header_offset)
    header = fp.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise ValueError("invalid ZIP local header")
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_len + extra_len


def main():
    parser = argparse.ArgumentParser(description="Verify Android release native libraries for 16 KB compatibility.")
    parser.add_argument("artifact", type=Path, help="Release APK or AAB")
    args = parser.parse_args()

    artifact = args.artifact.resolve()
    if not artifact.is_file():
        print(f"FAIL: artifact not found: {artifact}")
        return 2

    failures = []
    checked = []
    is_apk = artifact.suffix.lower() == ".apk"

    with artifact.open("rb") as raw, zipfile.ZipFile(raw) as zf:
        for info in zf.infolist():
            name = info.filename
            if not name.endswith(".so"):
                continue
            if "/arm64-v8a/" not in name and "/x86_64/" not in name:
                continue
            data = zf.read(info)
            try:
                machine, aligns = parse_elf_alignment(data)
            except ValueError as exc:
                failures.append(f"{name}: {exc}")
                continue
            if machine not in (EM_AARCH64, EM_X86_64):
                continue
            if not aligns:
                failures.append(f"{name}: no PT_LOAD segments")
                continue
            bad = [align for align in aligns if align < PAGE]
            if bad:
                failures.append(f"{name}: ELF PT_LOAD alignment below 16 KB: {bad}")
            if is_apk and info.compress_type == zipfile.ZIP_STORED:
                data_offset = local_data_offset(raw, info)
                if data_offset % PAGE != 0:
                    failures.append(f"{name}: APK data offset {data_offset} is not 16 KB aligned")
            checked.append((name, aligns))

    if not checked:
        failures.append("no 64-bit native libraries found; expected sherpa-onnx/ONNX Runtime")

    print(f"Artifact: {artifact}")
    print(f"64-bit native libraries checked: {len(checked)}")
    for name, aligns in checked:
        print(f"PASS ELF {name}: PT_LOAD alignments={aligns}")

    if failures:
        print("\n16 KB verification FAILED:")
        for item in failures:
            print(f" - {item}")
        return 1

    print("\n16 KB verification PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
