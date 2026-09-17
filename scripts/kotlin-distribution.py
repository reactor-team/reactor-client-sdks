#!/usr/bin/env python3
"""Stage already-built native binaries. Never compiles or downloads at SDK runtime."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path

PLATFORMS = {
    "macos-arm64": ("libreactor_ffi.dylib", "libreactor_jni.dylib"),
    "macos-x64": ("libreactor_ffi.dylib", "libreactor_jni.dylib"),
    "linux-arm64": ("libreactor_ffi.so", "libreactor_jni.so"),
    "linux-x64": ("libreactor_ffi.so", "libreactor_jni.so"),
    "windows-x64": ("reactor_ffi.dll", "reactor_jni.dll"),
    "android": ("libreactor_ffi.so", "libreactor_jni.so"),
}


def stage(
    platform: str, source: Path, destination: Path, webrtc_jar: Path | None
) -> None:
    root = Path(__file__).resolve().parents[1]
    names = PLATFORMS[platform]
    for name in names:
        if not (source / name).is_file():
            raise ValueError(f"Missing real native binary: {source / name}")
    if platform == "android" and (webrtc_jar is None or not webrtc_jar.is_file()):
        raise ValueError("Android requires the matching libwebrtc.jar")
    output = destination / platform
    binary_dir = output / "jniLibs" / "arm64-v8a" if platform == "android" else output
    binary_dir.mkdir(parents=True, exist_ok=True)
    for name in names:
        shutil.copy2(source / name, binary_dir / name)
    if platform == "android":
        shutil.copy2(webrtc_jar, output / "libwebrtc.jar")
    notice_dir = output / "assets/reactor" if platform == "android" else output
    notice_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(root / "LICENSE", notice_dir / "LICENSE")
    shutil.copy2(root / "sdks/kotlin/distribution/NOTICE", notice_dir / "NOTICE")
    checksums = {
        str(file.relative_to(output)): hashlib.sha256(file.read_bytes()).hexdigest()
        for file in sorted(output.rglob("*"))
        if file.is_file() and file.name != "SHA256SUMS.json"
    }
    (output / "SHA256SUMS.json").write_text(json.dumps(checksums, indent=2) + "\n")
    print(f"Staged {platform} in {output}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform", choices=PLATFORMS)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--webrtc-jar", type=Path)
    args = parser.parse_args()
    stage(args.platform, args.source, args.destination, args.webrtc_jar)


if __name__ == "__main__":
    main()
