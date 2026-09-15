#!/usr/bin/env python3
"""Audit native runtime dependencies before staging a desktop Maven artifact."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess


def output(*command: str) -> str:
    return subprocess.check_output(command, text=True)


def check(platform: str, directory: Path) -> None:
    if platform.startswith("macos"):
        floor = (11, 0) if platform.endswith("arm64") else (13, 0)
        expected_arch = "arm64" if platform.endswith("arm64") else "x86_64"
        for library in directory.glob("*.dylib"):
            assert expected_arch in output("lipo", "-archs", str(library)), library
            for version in re.findall(
                r"\bminos ([0-9.]+)", output("otool", "-l", str(library))
            ):
                assert tuple(map(int, version.split(".")[:2])) <= floor, (
                    library,
                    version,
                )
            for line in output("otool", "-L", str(library)).splitlines()[1:]:
                dependency = line.strip().split(" ", 1)[0]
                assert dependency.startswith(
                    ("@rpath/", "@loader_path/", "/usr/lib/", "/System/Library/")
                ), (library, dependency)
    elif platform.startswith("linux"):
        for library in directory.glob("*.so"):
            symbols = output("readelf", "--version-info", str(library))
            for version in re.findall(r"\bGLIBC_([0-9.]+)", symbols):
                assert tuple(map(int, version.split("."))) <= (2, 34), (
                    library,
                    version,
                )
            assert "GLIBCXX_" not in symbols, f"Bundle a static C++ runtime: {library}"
            dynamic = output("readelf", "-d", str(library))
            assert "/io/" not in dynamic and "/target/" not in dynamic, dynamic
    else:
        for library in directory.glob("*.dll"):
            dependencies = output("dumpbin", "/DEPENDENTS", str(library))
            assert not re.search(r"(?:VCRUNTIME|MSVCP|CONCRT)\d", dependencies, re.I), (
                dependencies
            )
    print(f"Native runtime dependencies verified: {platform}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform")
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    check(args.platform, args.directory)
