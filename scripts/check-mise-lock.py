#!/usr/bin/env python3
"""Verify mise.lock is complete against the declared tools.

Declared tools + versions come from `mise ls --local -J`, and the expected
platform set from `mise settings get lockfile_platforms` -- both mise's own view
of the config -- so this script parses mise.lock as TOML and, for Rust alone,
rust-toolchain.toml.

For every declared tool it asserts mise.lock has a version-matching
[[tools.<name>]] entry covering every lockfile platform. A `latest` spec (only
rustup uses one) is matched by presence, not version -- the lock still pins a
concrete version, and platform coverage is still enforced. Backends with no
per-platform binary artifacts (go:, npm:, cargo:, pipx:, core:) get a lock entry
with no platforms.* sub-tables; their coverage is skipped.

Rust is requested by rust-toolchain.toml, not mise.toml, so it is checked
separately: see rust_errors().

The check is skipped when mise.toml, mise.lock AND rust-toolchain.toml are
byte-identical to the merge-base with BASE_REF (default origin/main), so PRs
that don't touch the toolchain aren't blocked by a pre-existing lock state on
main.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:  # pragma: no cover - py<3.11
    import tomli as tomllib  # type: ignore

BASE_REF = os.environ.get("BASE_REF", "origin/main")
RUST_TOOLCHAIN = "rust-toolchain.toml"
# The Rust options mise records in the lock: two lists and one plain value.
_RUST_LISTS = ("components", "targets")
_RUST_SCALARS = ("profile",)
_WATCHED = ("mise.toml", "mise.lock", RUST_TOOLCHAIN)
# Backends with no per-platform binary artifacts (mise emits no platforms.*).
_NO_PLATFORM_BACKENDS = ("go:", "npm:", "cargo:", "pipx:", "core:")

# Tools that legitimately ship no binary for a lockfile platform, so `mise lock`
# cannot produce an entry for it. Exempt the specific tool -> platforms pair
# rather than dropping the platform for every tool. Keep this tiny and justified
# -- a stale exemption hides a real gap.
_PLATFORM_EXEMPTIONS: dict[str, set[str]] = {
    # jdx/hk only releases aarch64-apple-darwin; no x86_64-apple-darwin asset
    # exists in any published release. With MISE_LOCKED=1, mise skips tools that
    # have no lock entry for the current platform, so the macos-x64 CI runners
    # simply run without hk (which is fine -- hk is only needed for git hooks).
    "hk": {"macos-x64"},
}


def die(msg: str) -> None:
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def run_json(cmd: list[str]) -> object:
    r = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if r.returncode != 0:
        die(f"`{' '.join(cmd)}` failed: {r.stderr.strip()}")
    try:
        return json.loads(r.stdout)
    except json.JSONDecodeError as e:
        die(f"`{' '.join(cmd)}` did not return JSON: {e}")


def git_show(spec: str) -> bytes | None:
    r = subprocess.run(["git", "show", spec], capture_output=True, check=False)
    return r.stdout if r.returncode == 0 else None


def unchanged_since(base_ref: str) -> bool:
    """Whether every file this check reads is byte-identical to the merge-base."""
    base = subprocess.run(
        ["git", "merge-base", "HEAD", base_ref],
        capture_output=True, text=True, check=False,
    )
    if base.returncode != 0:
        return False
    base_sha = base.stdout.strip()

    def on_disk(name: str) -> bytes | None:
        return Path(name).read_bytes() if Path(name).is_file() else None

    return all(git_show(f"{base_sha}:{name}") == on_disk(name) for name in _WATCHED)


def mise_list(values: list) -> str:
    """A list as mise records it: trimmed, deduplicated, sorted, comma-joined."""
    return ",".join(sorted({str(v).strip() for v in values} - {""}))


def rust_errors(locked: dict) -> list[str]:
    """Check rust-toolchain.toml against the lock's Rust entries.

    mise locks the file's version, components, targets and profile, and
    `mise install --locked` refuses the request unless one entry matches all
    four as text.
    """
    path = Path(RUST_TOOLCHAIN)
    if not path.is_file():
        return []
    toolchain = tomllib.loads(path.read_text(encoding="utf-8")).get("toolchain", {})
    version = str(toolchain.get("channel", ""))
    wanted = {key: mise_list(toolchain.get(key, [])) for key in _RUST_LISTS}
    wanted.update({key: str(toolchain.get(key, "")) for key in _RUST_SCALARS})

    def recorded(entry: dict) -> dict[str, str]:
        options = entry.get("options", {})
        return {key: str(options.get(key, "")) for key in wanted}

    entries = locked.get("rust", [])
    same_version = [e for e in entries if str(e.get("version", "")) == version]
    if any(recorded(e) == wanted for e in same_version):
        return []

    if not same_version:
        found = ", ".join(str(e.get("version", "?")) for e in entries) or "none"
        detail = f"no entry for that version (found: {found})"
    else:
        have = recorded(same_version[-1])
        parts = []
        for key in _RUST_LISTS:
            if have[key] == wanted[key]:
                continue
            want_items = [v for v in wanted[key].split(",") if v]
            have_items = [v.strip() for v in have[key].split(",") if v.strip()]
            missing = [v for v in want_items if v not in have_items]
            extra = sorted({v for v in have_items if v not in want_items})
            if missing:
                parts.append(f"{key} not in the lock: {', '.join(missing)}")
            if extra:
                parts.append(f"{key} only in the lock: {', '.join(extra)}")
            if not missing and not extra:
                parts.append(f'{key} in the lock has to read exactly "{wanted[key]}"')
        for key in _RUST_SCALARS:
            if have[key] != wanted[key]:
                parts.append(
                    f"{key} is {wanted[key] or 'not set'} in {RUST_TOOLCHAIN}"
                    f" and {have[key] or 'not set'} in the lock"
                )
        detail = "; ".join(parts)
    return [
        f"rust@{version}: mise.lock does not match {RUST_TOOLCHAIN}: {detail}\n"
        "    Run `mise lock rust`. It adds a matching [[tools.rust]] entry and keeps\n"
        "    the old one, which is then unused and can be deleted."
    ]


def declared_tools() -> dict[str, str]:
    """Tool -> requested spec, from mise's view of the root mise.toml [tools].

    Filtered to mise.toml sources, so idiomatic version files (rust-toolchain.toml
    -> rust) and the global config are excluded, matching the [tools] table.
    """
    data = run_json(["mise", "ls", "--local", "-J"])
    out: dict[str, str] = {}
    for name, entries in data.items():  # type: ignore[union-attr]
        for e in entries:
            src = e.get("source") or {}
            if src.get("type") == "mise.toml":
                out[name] = str(e.get("requested_version") or e.get("version") or "")
                break
    return out


def lockfile_platforms() -> set[str]:
    """Expected platform set from `mise settings`; empty -> caller uses the lock union."""
    r = subprocess.run(
        ["mise", "settings", "get", "lockfile_platforms"],
        capture_output=True, text=True, check=False,
    )
    if r.returncode == 0 and r.stdout.strip():
        try:
            val = json.loads(r.stdout)
            if isinstance(val, list):
                return {str(p) for p in val}
        except json.JSONDecodeError:
            pass
    return set()


def platforms_in(entry: dict) -> set[str]:
    return {k.removeprefix("platforms.") for k in entry if k.startswith("platforms.")}


def version_matches(spec: str, locked: str) -> bool:
    return (locked + ".").startswith(spec + ".")


def main() -> int:
    os.chdir(os.environ.get("MISE_LOCK_CHECK_ROOT", "."))
    for f in ("mise.toml", "mise.lock"):
        if not Path(f).is_file():
            die(f"{f} not found in {Path.cwd()}")

    lock_bytes = Path("mise.lock").read_bytes()

    if unchanged_since(BASE_REF):
        print(f"✓ mise.lock check skipped: no changes vs {BASE_REF}")
        return 0

    declared = declared_tools()
    if not declared:
        die("`mise ls --local -J` reported no mise.toml tools")
    locked = tomllib.loads(lock_bytes.decode("utf-8")).get("tools", {})

    expected = lockfile_platforms()
    if not expected:  # fall back to the lockfile's own platform union
        for entries in locked.values():
            for entry in entries:
                expected |= platforms_in(entry)

    errors: list[str] = []
    for tool, spec in declared.items():
        entries = locked.get(tool, [])
        if spec == "latest":
            match = entries[0] if entries else None
        else:
            match = next(
                (e for e in entries
                 if version_matches(spec, str(e.get("version", "")))),
                None,
            )
        if match is None:
            found = ", ".join(e.get("version", "?") for e in entries) or "none"
            errors.append(f"{tool}@{spec}: no matching entry in mise.lock (found: {found})")
            continue
        if str(match.get("backend", "")).startswith(_NO_PLATFORM_BACKENDS):
            continue
        missing = expected - platforms_in(match) - _PLATFORM_EXEMPTIONS.get(tool, set())
        if missing:
            errors.append(
                f"{tool}@{match.get('version')}: missing platforms: {','.join(sorted(missing))}"
            )

    # A Rust error carries its own hint; `mise lock` is for the tool errors.
    tool_errors = len(errors)
    errors += rust_errors(locked)

    if errors:
        print("mise.lock is out of date:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        if tool_errors:
            print("\nRun: mise lock", file=sys.stderr)
        return 1

    print(f"✓ mise.lock OK: {len(declared)} tool(s), {len(expected)} platform(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
