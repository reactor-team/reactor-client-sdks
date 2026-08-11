"""Bundle libreactor_ffi into the wheel, and tag the wheel for its platform.

The SDK is pure Python — it reaches the native library through ctypes, never
through a CPython extension — so nothing here compiles anything. What it does is
turn what would otherwise be a `py3-none-any` wheel into a platform wheel carrying
the right binary, which is the only way `pip install reactor-sdk` can work without
asking users to build Rust.

Two consequences of ctypes worth knowing, because they shape the tags:

* No libpython is linked, so one wheel serves every supported interpreter. The
  Python tag stays `py3` and the ABI tag stays `none`; only the platform tag varies.
* The library is loaded by name at first use, so it only has to *be* in the package
  directory. `reactor/_ffi.py` looks there second, after ``REACTOR_FFI_LIB``.

Set ``REACTOR_FFI_LIB`` to bundle a specific binary. With nothing set, the build
looks for a release build in the Cargo workspace and falls back to a pure wheel if
there is none — which is what keeps ``pip install -e .`` and the test suite working
in a checkout with no Rust build.
"""

from __future__ import annotations

import os
import sys
import sysconfig
from pathlib import Path
from typing import Any

from hatchling.builders.hooks.plugin.interface import BuildHookInterface

#: Package-relative destination. Must match the names `reactor/_ffi.py` looks for.
LIB_NAMES = {
    "darwin": "libreactor_ffi.dylib",
    "win32": "reactor_ffi.dll",
}
DEFAULT_LIB_NAME = "libreactor_ffi.so"


def _expected_lib_name() -> str:
    return LIB_NAMES.get(sys.platform, DEFAULT_LIB_NAME)


def _platform_tag() -> str:
    """The wheel's platform tag.

    ``REACTOR_WHEEL_PLATFORM_TAG`` wins when set, and the release workflow always
    sets it. The inferred value is a fallback for local builds, and it is only a
    guess: ``sysconfig.get_platform()`` reports the floor *the interpreter* was built
    against, not the one the bundled library actually needs. Those differ — Rust
    targets macOS 11 on arm64 by default while libwebrtc needs 13 on x86_64 — and a
    tag that understates the requirement is the bad direction, because pip installs
    the wheel and the failure surfaces later as a load error.

    On Linux the inferred value is a bare ``linux_x86_64``, which PyPI rejects
    outright; the workflow runs ``auditwheel`` to find the manylinux tag the binary
    has actually earned.
    """
    override = os.environ.get("REACTOR_WHEEL_PLATFORM_TAG")
    if override:
        return override
    return sysconfig.get_platform().replace("-", "_").replace(".", "_")


def _locate_library(root: Path) -> Path | None:
    """Find the native library to bundle, or None to build a pure wheel."""
    override = os.environ.get("REACTOR_FFI_LIB")
    if override:
        candidate = Path(override)
        if not candidate.is_file():
            raise FileNotFoundError(f"REACTOR_FFI_LIB points at {candidate}, which does not exist")
        return candidate

    name = _expected_lib_name()
    # sdks/python -> repo root
    for target_dir in (root.parent.parent / "target" / "release",):
        candidate = target_dir / name
        if candidate.is_file():
            return candidate
    return None


class BundleNativeLibraryHook(BuildHookInterface):
    PLUGIN_NAME = "bundle-native-library"

    def initialize(self, version: str, build_data: dict[str, Any]) -> None:
        if self.target_name != "wheel":
            return

        library = _locate_library(Path(self.root))
        if library is None:
            self.app.display_warning(
                f"{_expected_lib_name()} not found — building a pure-Python wheel. "
                "It will need REACTOR_FFI_LIB set at runtime, or the library copied "
                "next to the installed package. Set REACTOR_FFI_LIB at build time, "
                "or run `cargo build -p reactor-ffi --release`, to bundle it."
            )
            return

        build_data["force_include"][str(library)] = f"reactor/{_expected_lib_name()}"

        # Not pure_python, because an `any` wheel carrying a macOS dylib would install
        # happily on Linux and fail at the first call.
        build_data["pure_python"] = False

        # Tagged explicitly rather than via infer_tag, which would read the *build
        # interpreter's* ABI and stamp something like cp313-cp313 — locking a wheel to
        # one interpreter version for no reason. ctypes links no libpython, so a single
        # wheel serves every interpreter the project supports; only the platform
        # varies.
        build_data["tag"] = f"py3-none-{_platform_tag()}"

        self.app.display_info(f"bundling {library} into a {build_data['tag']} wheel")
