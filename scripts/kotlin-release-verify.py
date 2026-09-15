#!/usr/bin/env python3
"""Verify a Kotlin SDK release candidate before registry publication."""

from __future__ import annotations

import argparse
import re
from pathlib import Path
import zipfile


def version(root: Path) -> str:
    properties = (root / "sdks/kotlin/gradle.properties").read_text()
    match = re.search(r"^reactorVersion=(\S+)$", properties, re.MULTILINE)
    if not match:
        raise ValueError("sdks/kotlin/gradle.properties has no reactorVersion")
    value = match[1]
    changelog = (root / "sdks/kotlin/CHANGELOG.md").read_text()
    if f"## [{value}]" not in changelog:
        raise ValueError(f"CHANGELOG.md has no release heading for {value}")
    return value


def verify_consumers(repo: Path, artifact_root: Path, sdk_version: str) -> None:
    expected = [
        artifact_root / "inc/reactor/reactor-core" / sdk_version / f"reactor-core-{sdk_version}.pom",
        artifact_root / "inc/reactor/reactor-desktop" / sdk_version / f"reactor-desktop-{sdk_version}.pom",
        artifact_root / "inc/reactor/reactor-android" / sdk_version / f"reactor-android-{sdk_version}.pom",
    ]
    missing = [str(path) for path in expected if not path.is_file()]
    if missing:
        raise ValueError("Missing published consumer metadata: " + ", ".join(missing))
    aars = list((artifact_root / "inc/reactor/reactor-android" / sdk_version).glob("*.aar"))
    if not aars:
        raise ValueError("Android publication has no AAR")
    with zipfile.ZipFile(aars[0]) as archive:
        names = set(archive.namelist())
        required = {
            "jni/arm64-v8a/libreactor_ffi.so",
            "jni/arm64-v8a/libreactor_jni.so",
            "assets/reactor/LICENSE",
            "assets/reactor/NOTICE",
        }
        missing = sorted(required - names)
        if missing:
            raise ValueError("Android AAR is incomplete: " + ", ".join(missing))
    print(f"Kotlin release verified: {sdk_version} ({len(aars)} Android AAR)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--maven", type=Path)
    args = parser.parse_args()
    release_version = version(args.root)
    if args.maven:
        verify_consumers(args.root, args.maven, release_version)
    else:
        print(f"Kotlin release metadata verified: {release_version}")
