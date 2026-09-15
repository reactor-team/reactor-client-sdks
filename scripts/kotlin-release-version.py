#!/usr/bin/env python3
"""Detect the version switch across a complete push range. Never publishes."""

from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess

MANIFEST = "sdks/kotlin/gradle.properties"


def version(text: str) -> str:
    match = re.search(r"^reactorVersion=(\d+\.\d+\.\d+(?:-SNAPSHOT)?)$", text, re.M)
    if not match:
        raise ValueError("Expected one valid reactorVersion")
    return match[1]


def detect() -> tuple[str, bool]:
    current = version(Path(MANIFEST).read_text())
    before = os.environ.get("BEFORE", "")
    if os.environ.get("GITHUB_EVENT_NAME") != "push" or not before.strip("0"):
        return current, False
    previous = subprocess.run(
        ["git", "show", f"{before}:{MANIFEST}"], capture_output=True, text=True
    )
    if previous.returncode != 0:
        # Introducing the scaffold is a baseline, not a release bump.
        return current, False
    changed = version(previous.stdout) != current
    if changed:
        if current.endswith("-SNAPSHOT"):
            raise ValueError("Release bumps must not use SNAPSHOT versions")
        changelog = Path("sdks/kotlin/CHANGELOG.md").read_text()
        if not re.search(
            r"^## \[" + re.escape(current) + r"\](?: |$)", changelog, re.M
        ):
            raise ValueError(f"Missing CHANGELOG heading for {current}")
    return current, changed


if __name__ == "__main__":
    current, bumped = detect()
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        output.write(f"version={current}\nbumped={str(bumped).lower()}\n")
