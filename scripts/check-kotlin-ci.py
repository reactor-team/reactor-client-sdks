#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["PyYAML==6.0.2"]
# ///
"""Regression checks for Kotlin's CI routing and required-check wiring."""

from fnmatch import fnmatchcase
from pathlib import Path

import yaml

root = Path(__file__).resolve().parent.parent
jobs = yaml.safe_load((root / ".github/workflows/ci.yml").read_text())["jobs"]
steps = jobs["changes"]["steps"]
filter_step = next(step for step in steps if step.get("id") == "filter")
filters = yaml.safe_load(filter_step["with"]["filters"])


def flatten(items):
    for item in items:
        if isinstance(item, list):
            yield from flatten(item)
        else:
            yield item


patterns = list(flatten(filters["kotlin"]))
# These routing checks intentionally cover only literal paths and /** suffixes,
# which have the same semantics in fnmatch and paths-filter's picomatch. Refuse
# richer expressions rather than claim to emulate the workflow's glob engine.
for pattern in patterns:
    literal = pattern.removesuffix("/**")
    assert not any(char in literal for char in "*?[]{}!()"), pattern

for path in [
    "sdks/kotlin/reactor-core/src/main/kotlin/Reactor.kt",
    "sdks/kotlin/gradle/libs.versions.toml",
    "scripts/kotlin.sh",
    "scripts/check-kotlin-ci.py",
    "scripts/kotlin-native.sh",
    "scripts/check-kotlin-native.py",
    "scripts/test-kotlin-native.py",
    "crates/reactor-ffi/include/reactor_ffi.h",
    "crates/reactor-core/src/error.rs",
    "Cargo.toml",
    "Cargo.lock",
    "rust-toolchain.toml",
    "mise.toml",
    "mise.lock",
    ".github/workflows/ci.yml",
    ".github/actions/setup-libwebrtc-cxx/action.yml",
]:
    assert any(fnmatchcase(path, pattern) for pattern in patterns), path

for path in [
    "sdks/python/reactor_sdk/client.py",
    "sdks/js/src/client.ts",
    "sdks/cpp/src/client.cpp",
    "sdks/swift/Sources/Reactor/Reactor.swift",
    "README.md",
]:
    assert not any(fnmatchcase(path, pattern) for pattern in patterns), path

assert jobs["changes"]["outputs"]["kotlin"] == "${{ steps.filter.outputs.kotlin }}"
gate = jobs["ci-complete"]
assert gate["if"] == "always()"
result_check = "\n".join(step.get("run", "") for step in gate["steps"])
for name in ("kotlin", "kotlin-android"):
    assert name in gate["needs"], name
    assert f"needs.{name}.result" in result_check, name
    assert jobs[name]["needs"] == "changes"
    assert jobs[name]["if"] == (
        "github.event_name == 'push' || needs.changes.outputs.kotlin == 'true'"
    ), name
print("Kotlin CI routing and required-check wiring passed")
