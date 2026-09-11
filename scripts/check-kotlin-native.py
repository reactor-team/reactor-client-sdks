#!/usr/bin/env python3
"""Reject incompatible Android artifacts before a JNI load can abort the process."""

import argparse
import re
import struct
import zipfile
from pathlib import Path


def check_elf(library):
    data = Path(library).read_bytes()
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected a 64-bit little-endian ELF library")
    if struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("Expected an Android AArch64 library")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    if size < 56 or offset + size * count > len(data):
        raise ValueError("Invalid ELF program-header table")
    loads = 0
    for index in range(count):
        kind, _, file_offset, address, _, _, _, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, offset + index * size
        )
        if kind == 1:
            loads += 1
            if alignment < 16384 or alignment & (alignment - 1):
                raise ValueError("Every ELF LOAD segment must align to at least 16 KB")
            if file_offset % 16384 != address % 16384:
                raise ValueError("ELF LOAD offset/address are not 16 KB congruent")
    if not loads:
        raise ValueError("ELF library has no LOAD segments")
    return data, loads


def check(library, jar):
    data, loads = check_elf(library)
    # InitClassLoader uses a dotted Java name embedded in the native image.
    names = set(
        re.findall(
            rb"(?:[A-Za-z_][A-Za-z_0-9]*\.)*org\.webrtc\.WebRtcClassLoader\x00", data
        )
    )
    if len(names) != 1:
        raise ValueError("Could not identify exactly one native WebRtcClassLoader name")
    name = names.pop()[:-1].decode("ascii")
    entry = name.replace(".", "/") + ".class"
    with zipfile.ZipFile(jar) as archive:
        if entry not in archive.namelist():
            raise ValueError(
                f"Native bootstrap requires {name}, but {entry} is absent from {jar}. "
                "Use a matching upstream WebRTC JAR; see REA-6249."
            )
    return f"AArch64 ELF: {loads} LOAD segments aligned to 16 KB; Java bootstrap class: {name}"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("library", type=Path)
    parser.add_argument("jar", type=Path, nargs="?")
    parser.add_argument("--elf-only", action="store_true")
    args = parser.parse_args()
    try:
        if args.elf_only:
            _, loads = check_elf(args.library)
            print(f"AArch64 ELF: {loads} LOAD segments aligned to 16 KB")
        elif args.jar is None:
            parser.error("jar is required unless --elf-only is used")
        else:
            print(check(args.library, args.jar))
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Android native preflight failed: {error}\n")
