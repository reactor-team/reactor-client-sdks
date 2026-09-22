#!/usr/bin/env python3
"""Verify the three hand-written copies of the C ABI agree.

The exported surface of `reactor-ffi` is declared in three independent places:

  1. `crates/reactor-ffi/src/lib.rs`  — the `#[no_mangle] pub extern "C"` functions
     (`unsafe` for all but the few that take no pointer), which are the actual ABI.
  2. `crates/reactor-ffi/include/reactor_ffi.h` — the contract Go, C++, Kotlin and
     Swift bindings compile against. What is missing here does not exist for them.
  3. `sdks/python/reactor_sdk/_ffi.py` — the ctypes declarations. ctypes checks nothing
     against the header, so a mismatch here is silent undefined behaviour rather
     than a compile error.

Nothing keeps them in sync, and they have drifted before: the send half of frame
metadata reached the Rust and the ctypes copies but never the header, so bindings
could receive a frame's metadata and not attach any.

The C++ and Swift SDKs are consumers but not copies: C++ includes the header and
derives every signature with `decltype`, Swift imports it through a module map, so
each compiler catches what this script cannot. What is still worth checking there
is that they name no symbol Rust does not export, and that neither reaches for
`reactor_create`.

Rules enforced:
  * the header must declare exactly the functions Rust exports — no more, no less;
  * every function ctypes declares must be exported by Rust (a binding may use a
    subset, but it may not invent symbols);
  * every `reactor_*` symbol the C++ and Swift SDKs name must be exported by Rust;
  * neither may name `reactor_create`;
  * the C++ SDK must not redeclare any `reactor_*` function itself, and the Swift
    SDK must not bind one with `@_silgen_name` — both are a signature nobody
    checked, which is the drift the header exists to prevent.

Replacing this with generated bindings (cbindgen for the header, derived ctypes)
would make it unnecessary. Until then, this is the guard.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

RUST_SRC = REPO_ROOT / "crates/reactor-ffi/src/lib.rs"
HEADER = REPO_ROOT / "crates/reactor-ffi/include/reactor_ffi.h"
CTYPES_SRC = REPO_ROOT / "sdks/python/reactor_sdk/_ffi.py"
CPP_DIR = REPO_ROOT / "sdks/cpp"
ANDROID_NATIVE_DIR = REPO_ROOT / "sdks/android/native"
SWIFT_DIR = REPO_ROOT / "sdks/swift"
JAVA_DIR = REPO_ROOT / "sdks/java"

# `pub unsafe extern "C" fn reactor_foo(` — the exported ABI. `unsafe` is optional
# because a handful of exports have no pointer to uphold anything about (a clock
# read, say); marking those `unsafe` to satisfy a regex would be a safety claim
# made to a linter rather than to a caller.
RUST_EXPORT = re.compile(
    r'pub\s+(?:unsafe\s+)?extern\s+"C"\s+fn\s+(reactor_[a-z0-9_]+)\s*\('
)

# A `reactor_*` identifier in call/declaration position: followed by `(`.
# Typedefs read `typedef void (*reactor_on_status_fn)(...)`, where the name is
# followed by `)`, and parameters read `reactor_completion_fn completion`, where it
# is followed by an identifier — so requiring `(` excludes both.
HEADER_DECL = re.compile(r"\b(reactor_[a-z0-9_]+)\s*\(")

# The header, with parameters: `reactor_foo(const char *a, int b);`. Applied to
# comment-stripped text, the same `name(` requirement as HEADER_DECL excludes the
# function-pointer typedefs (`(*reactor_on_status_fn)(...)`, where the name is
# followed by `)`) and parameter declarations (`reactor_completion_fn completion`).
HEADER_PROTOTYPE = re.compile(r"\b(reactor_[a-z0-9_]+)\s*\(([^;{]*?)\)\s*;", re.S)

# The Java SDK declares each function as an enum constant carrying the C name and
# a FunctionDescriptor:
#
#     SEND_COMMAND("reactor_send_command", FunctionDescriptor.ofVoid(ADDRESS, …)),
#
# which is the reason this binding gets a check none of the others can have. A C++
# or ctypes declaration is not something a script can take apart, so for those the
# comparison is by name and the arity goes unchecked — and an unchecked arity is
# the failure this whole script exists to catch: a function that gained a
# parameter still links, still resolves, and corrupts the stack at the call.
# A FunctionDescriptor is a data structure, so here the arguments can be counted.
JAVA_DESCRIPTOR = re.compile(
    r'\(\s*"(reactor_[a-z0-9_]+)"\s*,\s*FunctionDescriptor\.(of|ofVoid)\s*\(([^()]*)\)',
    re.S,
)

# `lib.reactor_foo.restype = ...` / `lib.reactor_foo.argtypes = [...]`
CTYPES_DECL = re.compile(r"\blib\.(reactor_[a-z0-9_]+)\s*\.")

# Any `reactor_*` identifier in C++ source. Deliberately broader than the header
# and ctypes patterns: the C++ SDK reaches these through `decltype(&symbol)`, as
# a bare macro argument, and by calling them, and all three are references that
# have to resolve. Comments are stripped first — a file explaining *why* it does
# not call something must not read as calling it.
CPP_SYMBOL = re.compile(r"\b(reactor_[a-z0-9_]+)\b")

# `// …` and `/* … */`. Crude by design: this is only ever fed C++ the compiler
# has already accepted, so the pathological cases (a `//` inside a string
# literal) would have to be deliberate.
CPP_COMMENT = re.compile(r"//[^\n]*|/\*.*?\*/", re.DOTALL)

# Not functions. `reactor_ffi` is the library and its header; the SDK names both
# in an #include and in messages about rebuilding it.
# reactor_ffi and reactor_jni are library/target names — they appear in CMake
# references and in load-order prose, and neither is an exported function.
CPP_NON_FUNCTIONS = {"reactor_ffi", "reactor_jni"}

# Swift reaches the ABI the same way C++ does — through the header, with the
# compiler deriving every signature — so the same crude scan applies: any
# `reactor_*` identifier in a Swift file is a reference that has to resolve.
SWIFT_SYMBOL = CPP_SYMBOL

# Swift has no `extern "C"`, but it has one way to name a C symbol without the
# header: `@_silgen_name("reactor_foo")` binds a Swift declaration straight to a
# symbol, with a signature the compiler checks against nothing. That is the
# Swift-shaped version of the redeclaration the C++ check rejects.
SWIFT_SILGEN = re.compile(r'@_silgen_name\s*\(\s*"(reactor_[a-z0-9_]+)"')

# `reactor_ffi` is the library and its header; Swift names both in the module map
# and in messages about rebuilding it. `reactor_sdk` is the SwiftPM package name.
SWIFT_NON_FUNCTIONS = {"reactor_ffi", "reactor_sdk"}

# Java reaches the ABI through FunctionDescriptors rather than through the header,
# but any other `reactor_*` identifier in its sources would be a second, unchecked
# way to name a symbol — so the same crude scan applies.
JAVA_SYMBOL = CPP_SYMBOL
JAVA_NON_FUNCTIONS = {"reactor_ffi", "reactor_sdk"}

# A redeclaration in C++ would reintroduce the drift the header exists to
# prevent: `extern "C" void reactor_foo(...)` compiles against a signature nobody
# checked.
#
# Both spellings have to be read, and only one of them used to be. A pattern that
# ran from `extern "C"` to the declaration could not cross an opening brace, so
# the linkage-block form — the conventional one — went unseen, and the guard
# passed on exactly what it exists to reject.
CPP_LINKAGE = re.compile(r'extern\s+"C"\s*')


def cpp_redeclarations(text: str) -> list[str]:
    """`reactor_*` functions declared inside an `extern "C"` region.

    Comments are already stripped by the caller, so `extern "C"` is followed
    either by `{` (a linkage block) or by a single declaration ending at `;`.
    """
    found: list[str] = []
    for match in CPP_LINKAGE.finditer(text):
        rest = text[match.end() :]
        if rest.startswith("{"):
            # Counted rather than matched to the next `}`: a definition inside the
            # block brings braces of its own.
            depth = 0
            region = rest
            for index, char in enumerate(rest):
                if char == "{":
                    depth += 1
                elif char == "}":
                    depth -= 1
                    if depth == 0:
                        region = rest[1:index]
                        break
        else:
            # `extern "C"` not followed by a block is either a single declaration ending at `;`
            # or a single *definition*, whose body starts at `{`. Stopping at whichever comes
            # first matters: reading on into a body turns every call the function makes into a
            # false redeclaration, which is what `extern "C" JNIEXPORT … { return
            # reactor_abi_version(); }` in the Android bridge produced.
            end = min(
                (index for index in (rest.find(";"), rest.find("{")) if index != -1),
                default=len(rest),
            )
            region = rest[:end]
        found.extend(HEADER_DECL.findall(region))
    return found

# `reactor_create` takes its audio device mode from an environment variable. The
# C++ and Swift SDKs pin the synthetic module through `reactor_create_with_adm`
# instead, so that no env var can put a live microphone on the wire because a
# model happened to declare a sendonly audio track. Structural, not a convention:
# the symbol is absent from each SDK's table, and this is what keeps it absent.
FORBIDDEN = {"reactor_create"}
CPP_FORBIDDEN = FORBIDDEN


def read(path: Path) -> str:
    if not path.is_file():
        sys.exit(f"error: expected file not found: {path.relative_to(REPO_ROOT)}")
    return path.read_text(encoding="utf-8")


def cpp_sources() -> list[Path]:
    """Every C++ translation unit and header in the C++ SDK, tests included."""
    if not CPP_DIR.is_dir():
        return []
    return sorted(
        path
        for pattern in ("**/*.hpp", "**/*.cpp")
        for path in CPP_DIR.glob(pattern)
        # The build tree holds fetched dependencies (Catch2, nlohmann) whose own
        # sources are none of this script's business.
        if "build" not in path.relative_to(CPP_DIR).parts
    )


def android_native_sources() -> list[Path]:
    """The Android SDK's JNI bridge, which is C++ over the same canonical header.

    Scanned with the same rules as the C++ SDK: it includes reactor_ffi.h directly, so arity and
    types are the compiler's problem, and what this script adds is the half a compiler cannot see
    — a function the bridge names that Rust no longer exports.
    """
    if not ANDROID_NATIVE_DIR.is_dir():
        return []
    return sorted(
        path
        for pattern in ("**/*.hpp", "**/*.cpp")
        for path in ANDROID_NATIVE_DIR.glob(pattern)
    )


def swift_sources() -> list[Path]:
    """Every Swift file the Swift SDK owns, tests included.

    `Package.swift` at the repository root is not among them: it is the manifest,
    it names no ABI symbol, and reading it would only add the package name to the
    exclusion list. `.build` is SwiftPM's build tree — checked out dependencies
    are none of this script's business.
    """
    if not SWIFT_DIR.is_dir():
        return []
    return sorted(
        path
        for path in SWIFT_DIR.glob("**/*.swift")
        if ".build" not in path.relative_to(SWIFT_DIR).parts
    )


def java_sources() -> list[Path]:
    """Every Java file the Java SDK owns, tests included.

    Gradle's build trees (`build/`, `buildSrc/build/`) hold generated and copied
    sources that are none of this script's business.
    """
    if not JAVA_DIR.is_dir():
        return []
    return sorted(
        path
        for path in JAVA_DIR.glob("**/*.java")
        if "build" not in path.relative_to(JAVA_DIR).parts
    )


def kotlin_sources() -> list[Path]:
    """Every Kotlin file under the Java SDK, tests and the one example included."""
    if not JAVA_DIR.is_dir():
        return []
    return sorted(
        path
        for path in JAVA_DIR.glob("**/*.kt")
        if "build" not in path.relative_to(JAVA_DIR).parts
    )


def parameter_count(parameters: str) -> int:
    """How many parameters a C parameter list declares. `void` is none."""
    stripped = parameters.strip()
    if not stripped or stripped == "void":
        return 0
    return len([p for p in stripped.split(",") if p.strip()])


def header_arities(text: str) -> dict[str, int]:
    """Every exported function in the header, with how many parameters it takes."""
    return {
        name: parameter_count(parameters)
        for name, parameters in HEADER_PROTOTYPE.findall(text)
        if not name.endswith("_fn")
    }


def java_arities(text: str) -> dict[str, int]:
    """Every function the Java SDK declares, with the arity its descriptor gives it.

    `FunctionDescriptor.of(...)` names the return layout first and `ofVoid(...)`
    does not, so one of them counts one fewer argument than it lists.
    """
    arities: dict[str, int] = {}
    for name, kind, layouts in JAVA_DESCRIPTOR.findall(text):
        declared = len([layout for layout in layouts.split(",") if layout.strip()])
        arities[name] = declared - 1 if kind == "of" else declared
    return arities


def forbidden_problem(binding: str, hits: dict[str, str]) -> str:
    """The message for a symbol a binding is not allowed to name."""
    return f"reached for by the {binding} SDK and not allowed there:\n" + "\n".join(
        f"    {name}  ({where})\n"
        f"        reactor_create reads its audio device mode from an environment\n"
        f"        variable. Use reactor_create_with_adm with mode 0 so nothing can\n"
        f"        put a live microphone on the wire without being asked."
        for name, where in sorted(hits.items())
    )


def main() -> int:
    rust = set(RUST_EXPORT.findall(read(RUST_SRC)))
    # Function-pointer typedefs are part of the contract but are not functions.
    header = {n for n in HEADER_DECL.findall(read(HEADER)) if not n.endswith("_fn")}
    ctypes_decls = set(CTYPES_DECL.findall(read(CTYPES_SRC)))

    cpp_named: set[str] = set()
    cpp_redeclared: dict[str, str] = {}
    cpp_forbidden: dict[str, str] = {}
    for path in [*cpp_sources(), *android_native_sources()]:
        text = CPP_COMMENT.sub(" ", read(path))
        where = str(path.relative_to(REPO_ROOT))
        for name in CPP_SYMBOL.findall(text):
            # REACTOR_ABI_VERSION and friends are macros; reactor_*_fn are the
            # header's function-pointer typedefs, which a binding names when it
            # declares a callback. Neither is an exported function.
            if name.isupper() or name.endswith("_fn") or name in CPP_NON_FUNCTIONS:
                continue
            cpp_named.add(name)
            if name in CPP_FORBIDDEN:
                cpp_forbidden.setdefault(name, where)
        for name in cpp_redeclarations(text):
            cpp_redeclared.setdefault(name, where)

    swift_named: set[str] = set()
    swift_bound: dict[str, str] = {}
    swift_forbidden: dict[str, str] = {}
    for path in swift_sources():
        text = CPP_COMMENT.sub(" ", read(path))
        where = str(path.relative_to(REPO_ROOT))
        for name in SWIFT_SYMBOL.findall(text):
            # REACTOR_ABI_VERSION and friends are macros the header exports as
            # constants; reactor_*_fn are its function-pointer typedefs, which a
            # binding names when it declares a callback. Neither is a function.
            if name.isupper() or name.endswith("_fn") or name in SWIFT_NON_FUNCTIONS:
                continue
            swift_named.add(name)
            if name in FORBIDDEN:
                swift_forbidden.setdefault(name, where)
        for name in SWIFT_SILGEN.findall(text):
            swift_bound.setdefault(name, where)

    java_named: set[str] = set()
    java_forbidden: dict[str, str] = {}
    java_declared: dict[str, int] = {}
    for path in java_sources():
        text = CPP_COMMENT.sub(" ", read(path))
        where = str(path.relative_to(REPO_ROOT))
        java_declared.update(java_arities(text))
        for name in JAVA_SYMBOL.findall(text):
            # REACTOR_ABI_VERSION and friends are macros the header exports as
            # constants; reactor_*_fn are its function-pointer typedefs. Neither
            # is a function.
            if name.isupper() or name.endswith("_fn") or name in JAVA_NON_FUNCTIONS:
                continue
            java_named.add(name)
            if name in FORBIDDEN:
                java_forbidden.setdefault(name, where)

    # The Kotlin facade must name no native symbol at all. It forwards to the Java
    # binding and holds nothing of its own — that is what makes it cheap to keep
    # correct, and the moment one of these appears there it has quietly become a
    # second binding with its own copy of the ABI to keep in step.
    kotlin_symbols: dict[str, str] = {}
    for path in kotlin_sources():
        text = read(path)
        where = str(path.relative_to(REPO_ROOT))
        for name in JAVA_SYMBOL.findall(text):
            if name.isupper() or name.endswith("_fn") or name in JAVA_NON_FUNCTIONS:
                continue
            kotlin_symbols.setdefault(name, where)

    if not rust:
        sys.exit("error: found no exported reactor_* functions — has lib.rs moved?")

    problems: list[str] = []

    missing_from_header = sorted(rust - header)
    if missing_from_header:
        problems.append(
            "exported by Rust but absent from reactor_ffi.h "
            "(invisible to the Go / C++ / Kotlin / Swift bindings):\n"
            + "\n".join(f"    {name}" for name in missing_from_header)
        )

    stale_in_header = sorted(header - rust)
    if stale_in_header:
        problems.append(
            "declared in reactor_ffi.h but not exported by Rust "
            "(a binding calling these fails to link, or worse):\n"
            + "\n".join(f"    {name}" for name in stale_in_header)
        )

    unknown_in_ctypes = sorted(ctypes_decls - rust)
    if unknown_in_ctypes:
        problems.append(
            "declared in _ffi.py but not exported by Rust "
            "(ctypes resolves lazily, so this raises at import or calls nothing):\n"
            + "\n".join(f"    {name}" for name in unknown_in_ctypes)
        )

    unknown_in_cpp = sorted(cpp_named - rust)
    if unknown_in_cpp:
        problems.append(
            "named by the C++ or Android SDK but not exported by Rust "
            "(the link fails, or resolves to something else entirely):\n"
            + "\n".join(f"    {name}" for name in unknown_in_cpp)
        )

    if cpp_redeclared:
        problems.append(
            "redeclared by the C++ SDK instead of taken from reactor_ffi.h "
            "(a signature nobody checked, which is the drift the header prevents):\n"
            + "\n".join(f"    {name}  ({where})" for name, where in sorted(cpp_redeclared.items()))
        )

    if cpp_forbidden:
        problems.append(forbidden_problem("C++", cpp_forbidden))

    unknown_in_swift = sorted(swift_named - rust)
    if unknown_in_swift:
        problems.append(
            "named by the Swift SDK but not exported by Rust "
            "(the link fails, or resolves to something else entirely):\n"
            + "\n".join(f"    {name}" for name in unknown_in_swift)
        )

    if swift_bound:
        problems.append(
            "bound by the Swift SDK with @_silgen_name instead of imported from "
            "reactor_ffi.h (a signature nobody checked, which is the drift the "
            "header prevents):\n"
            + "\n".join(f"    {name}  ({where})" for name, where in sorted(swift_bound.items()))
        )

    if swift_forbidden:
        problems.append(forbidden_problem("Swift", swift_forbidden))

    if kotlin_symbols:
        problems.append(
            "The Kotlin facade names native symbols:\n"
            + "\n".join(
                f"    {name}  ({where})" for name, where in sorted(kotlin_symbols.items())
            )
            + "\n        It is a facade over the Java binding, not a binding. A native\n"
            "        symbol here means a second copy of the ABI to keep in step, which\n"
            "        is the one property this module exists to keep."
        )

    unknown_in_java = sorted(java_named - rust)
    if unknown_in_java:
        problems.append(
            "named by the Java SDK and not exported by reactor-ffi:\n"
            + "\n".join(f"    {name}" for name in unknown_in_java)
        )

    if java_forbidden:
        problems.append(forbidden_problem("Java", java_forbidden))

    # The check no other binding gets. Names agreeing is not the same as
    # signatures agreeing, and a wrong arity is invisible until the call.
    # Comments stripped first: the header is mostly prose, and a prototype quoted
    # inside an explanation is not a declaration.
    header_arity = header_arities(CPP_COMMENT.sub(" ", read(HEADER)))
    arity_mismatches = [
        (name, declared, header_arity[name])
        for name, declared in sorted(java_declared.items())
        if name in header_arity and declared != header_arity[name]
    ]
    if arity_mismatches:
        problems.append(
            "declared by the Java SDK with the wrong number of arguments:\n"
            + "\n".join(
                f"    {name}: FunctionDescriptor declares {declared}, "
                f"reactor_ffi.h takes {expected}\n"
                f"        A call through this descriptor corrupts the stack. It does\n"
                f"        not fail at load; it looks like a hang, or like the operation\n"
                f"        silently doing nothing."
                for name, declared, expected in arity_mismatches
            )
        )

    undeclared_arity = sorted(set(java_named) - set(java_declared))
    if undeclared_arity:
        problems.append(
            "named by the Java SDK outside its FunctionDescriptor table:\n"
            + "\n".join(f"    {name}" for name in undeclared_arity)
            + "\n        Every symbol this binding reaches must be declared in Ffi.java's\n"
            "        Symbol enum, where its arity can be checked against the header."
        )

    if problems:
        print("ABI parity check failed.\n", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}\n", file=sys.stderr)
        print(
            "Add the missing declarations, or remove the stale ones, so all three\n"
            "copies describe the same ABI.",
            file=sys.stderr,
        )
        return 1

    only_in_rust = sorted(rust - ctypes_decls)
    summary = (
        f"ABI parity OK — {len(rust)} exported functions, header in sync, "
        f"{len(cpp_named)} named by the C++ and Android SDKs, {len(swift_named)} by the Swift SDK, "
        f"{len(java_declared)} by the Java SDK (arity checked), "
        f"none by the Kotlin facade ({len(kotlin_sources())} files checked)"
    )
    if only_in_rust:
        # Not an error: the Python SDK is free to bind a subset.
        summary += f", {len(only_in_rust)} not bound by ctypes ({', '.join(only_in_rust)})"
    print(summary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
