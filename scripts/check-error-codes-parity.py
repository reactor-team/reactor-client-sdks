#!/usr/bin/env python3
"""Verify every SDK declares a class for every error code the core defines.

`crates/reactor-core/src/error.rs`'s `codes` module is the single source of
truth for the error codes this platform reports (see that file's module doc).
Every SDK on top of it hand-declares its own copy of that list as a typed
exception/error hierarchy:

  * `sdks/js/src/errors.ts`               — `static override readonly code = '...'`
  * `sdks/python/reactor_sdk/errors.py`    — `code = "..."` class attribute
  * `sdks/cpp/include/reactor/errors.hpp`  — the `REACTOR_ERROR_CLASSES(X)` macro,
    plus its own `codes::` namespace of string constants (a second hand-copy,
    independent of the macro, because C++ can't share Rust's consts directly)
  * `sdks/swift/Sources/Reactor/ReactorError.swift` — the `Code` constants,
    plus the `catch ReactorError.<code>` matchers (a second hand-copy again,
    and the one that decides what the advertised catch idiom can reach)

Nothing keeps these six copies in sync with the core or with each other, and
they have drifted before: `RECORDER_DISABLED` was added to `error.rs` and to
`errors.ts`, but never to Python's or C++'s copy — the two SDKs quietly fell
back to their generic base error for a code the platform genuinely reports.

Unlike the ABI's three hand-copies (`check-abi-parity.py`), where each
consumer has different rules (ctypes may bind a subset, C++ may name no
symbol Rust doesn't export), the rule for error codes is the same everywhere:
every core code needs a declared entry, full stop. So this script is a table
of (label, file, pattern) rather than one bespoke check per language — adding
a future SDK (Swift, Go, ...) means adding one entry to SDKS, not a new
function.

`recoverable` is deliberately not checked here: none of the SDKs compute it
per code (each reads it straight off the wire payload — the core is the only
place that decides it, via `code_is_recoverable()`), so there is no per-SDK
copy of it to drift.
"""

from __future__ import annotations

import re
import sys
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

CORE_SRC = REPO_ROOT / "crates/reactor-core/src/error.rs"

# `pub const NETWORK_ERROR: &str = "NETWORK_ERROR";` — nothing else in this
# file matches this shape, so there's no need to isolate the `pub mod codes
# { ... }` block it lives in.
CORE_CODE = re.compile(r'pub const [A-Z_]+: &str = "([A-Z_]+)";')

# The universal fallback: every SDK's base error class defaults to this code
# rather than giving it a dedicated subclass, so it is never expected in any
# SDK's declared list below.
FALLBACK_CODE = "INTERNAL_ERROR"


def screaming_snake(name: str) -> str:
    """`recorderDisabled` -> `RECORDER_DISABLED`."""
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).upper()


@dataclass(frozen=True)
class Sdk:
    label: str
    path: Path
    pattern: re.Pattern[str]  # first capture group is the declared CODE...
    # ...unless the declaration names a member instead of repeating the string,
    # as Swift's matcher list does; then this turns the name back into a code.
    normalize: Callable[[str], str] = field(default=lambda captured: captured)


# JS: `static override readonly code = 'NETWORK_ERROR';`. The base class's own
# `static readonly code: string = 'INTERNAL_ERROR';` has no `override`, so it
# is naturally excluded.
JS_CODE = re.compile(r"static override readonly code = '([A-Z_]+)';")

# Python: subclasses assign `code = "NETWORK_ERROR"` with no type annotation.
# The base class's `code: str = "INTERNAL_ERROR"` carries the annotation and
# is naturally excluded.
PY_CODE = re.compile(r'^    code = "([A-Z_]+)"$', re.MULTILINE)

# C++: the `REACTOR_ERROR_CLASSES(X)` macro's `X(ClassName, CODE)` entries.
CPP_CLASS_CODE = re.compile(r"X\(\w+,\s*([A-Z_]+)\)")

# C++'s own hand-copy of the code strings, independent of the macro above:
# `inline constexpr std::string_view NETWORK_ERROR = "NETWORK_ERROR";`.
CPP_NAMESPACE_CODE = re.compile(r'inline constexpr std::string_view \w+ = "([A-Z_]+)";')

# Swift: `public static let networkError = Code(rawValue: "NETWORK_ERROR")`.
SWIFT_CODE = re.compile(r'public static let \w+ = Code\(rawValue: "([A-Z_]+)"\)')

# Swift's second hand-copy: the matchers `catch ReactorError.networkError`
# resolves through, `public static var networkError: Code { .networkError }`.
# They name the member rather than repeat the string, hence `screaming_snake`.
# Checked separately from the constants above because a code declared in `Code`
# but missing a matcher is still broken — the SDK advertises the catch idiom as
# the way to handle it, and that idiom would not compile.
SWIFT_MATCHER = re.compile(r"public static var (\w+): Code \{ \.\w+ \}")

SWIFT_ERRORS = REPO_ROOT / "sdks/swift/Sources/Reactor/ReactorError.swift"

SDKS = [
    Sdk("JS", REPO_ROOT / "sdks/js/src/errors.ts", JS_CODE),
    Sdk("Python", REPO_ROOT / "sdks/python/reactor_sdk/errors.py", PY_CODE),
    Sdk("C++ (classes)", REPO_ROOT / "sdks/cpp/include/reactor/errors.hpp", CPP_CLASS_CODE),
    Sdk("C++ (codes::)", REPO_ROOT / "sdks/cpp/include/reactor/errors.hpp", CPP_NAMESPACE_CODE),
    Sdk("Swift (Code)", SWIFT_ERRORS, SWIFT_CODE),
    Sdk("Swift (matchers)", SWIFT_ERRORS, SWIFT_MATCHER, screaming_snake),
]


def read(path: Path) -> str:
    if not path.is_file():
        sys.exit(f"error: expected file not found: {path.relative_to(REPO_ROOT)}")
    return path.read_text(encoding="utf-8")


def main() -> int:
    core_codes = set(CORE_CODE.findall(read(CORE_SRC))) - {FALLBACK_CODE}
    if not core_codes:
        sys.exit("error: found no codes in error.rs — has the `codes` module moved?")

    problems: list[str] = []

    for sdk in SDKS:
        declared = {sdk.normalize(m) for m in sdk.pattern.findall(read(sdk.path))} - {
            FALLBACK_CODE
        }
        where = str(sdk.path.relative_to(REPO_ROOT))

        missing = sorted(core_codes - declared)
        if missing:
            problems.append(
                f"in error.rs but missing from {sdk.label} ({where}):\n"
                + "\n".join(f"    {code}" for code in missing)
            )

        stale = sorted(declared - core_codes)
        if stale:
            problems.append(
                f"declared by {sdk.label} ({where}) but not in error.rs "
                "(dead or renamed — nothing produces this code any more):\n"
                + "\n".join(f"    {code}" for code in stale)
            )

    if problems:
        print("Error-code parity check failed.\n", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}\n", file=sys.stderr)
        print(
            "Every code in crates/reactor-core/src/error.rs's `codes` module needs a\n"
            "matching declared entry in every SDK (JS, Python, and both of C++'s\n"
            "and Swift's copies).\n"
            "Add the missing declarations, or remove the stale ones.",
            file=sys.stderr,
        )
        return 1

    print(
        f"error-code parity OK — {len(core_codes)} codes, "
        f"every SDK declares a class for each"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
