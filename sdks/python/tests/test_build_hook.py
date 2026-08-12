"""Tests for the wheel build hook.

`hatch_build.py` decides two things that are easy to get quietly wrong: which
binary goes into the wheel, and what platform the wheel claims to be for. A wrong
tag is the worse of the two, because pip installs the wheel and the failure only
surfaces when the library is loaded on a user's machine.

The hook lives next to pyproject.toml rather than inside the package, so it is
imported by path here.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

_HOOK_PATH = Path(__file__).resolve().parent.parent / "hatch_build.py"


def _load_hook():
    spec = importlib.util.spec_from_file_location("reactor_hatch_build", _HOOK_PATH)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


hook = _load_hook()


class TestExpectedLibraryName:
    @pytest.mark.parametrize(
        ("platform", "expected"),
        [
            ("darwin", "libreactor_ffi.dylib"),
            ("win32", "reactor_ffi.dll"),
            ("linux", "libreactor_ffi.so"),
            ("freebsd14", "libreactor_ffi.so"),
        ],
    )
    def test_matches_what_ffi_looks_for(
        self, platform: str, expected: str, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """These names must agree with `reactor_sdk/_ffi.py`; a mismatch produces a wheel
        carrying a library the loader will not look for."""
        monkeypatch.setattr(hook.sys, "platform", platform)
        assert hook._expected_lib_name() == expected

    def test_the_names_agree_with_the_loader(self) -> None:
        from reactor_sdk import _ffi

        source = _HOOK_PATH.read_text()
        loader_source = Path(_ffi.__file__).read_text()
        for name in ("libreactor_ffi.dylib", "reactor_ffi.dll", "libreactor_ffi.so"):
            assert name in source, f"{name} missing from the build hook"
            assert name in loader_source, f"{name} missing from the loader"


class TestPlatformTag:
    def test_the_override_wins(self, monkeypatch: pytest.MonkeyPatch) -> None:
        monkeypatch.setenv("REACTOR_WHEEL_PLATFORM_TAG", "macosx_13_0_x86_64")
        assert hook._platform_tag() == "macosx_13_0_x86_64"

    def test_falls_back_to_the_build_platform(self, monkeypatch: pytest.MonkeyPatch) -> None:
        monkeypatch.delenv("REACTOR_WHEEL_PLATFORM_TAG", raising=False)
        monkeypatch.setattr(hook.sysconfig, "get_platform", lambda: "macosx-13.0-arm64")
        assert hook._platform_tag() == "macosx_13_0_arm64"

    def test_the_tag_is_wheel_safe(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """Wheel filenames are dash-delimited, so a tag may not contain dashes or
        dots — those would produce an unparseable filename."""
        monkeypatch.delenv("REACTOR_WHEEL_PLATFORM_TAG", raising=False)
        tag = hook._platform_tag()
        assert "-" not in tag and "." not in tag, tag


class TestLocateLibrary:
    def test_the_override_is_used(self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
        lib = tmp_path / "libreactor_ffi.so"
        lib.write_bytes(b"\x00")
        monkeypatch.setenv("REACTOR_FFI_LIB", str(lib))
        assert hook._locate_library(tmp_path) == lib

    def test_a_missing_override_is_an_error(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Silently building a pure wheel here would ship a release asset with no
        library in it — fail the build instead."""
        monkeypatch.setenv("REACTOR_FFI_LIB", str(tmp_path / "nope.so"))
        with pytest.raises(FileNotFoundError, match="does not exist"):
            hook._locate_library(tmp_path)

    def test_nothing_found_returns_none(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Which is what keeps a plain `pip install -e .` working in a checkout with
        no Rust build."""
        monkeypatch.delenv("REACTOR_FFI_LIB", raising=False)
        root = tmp_path / "sdks" / "python"
        root.mkdir(parents=True)
        assert hook._locate_library(root) is None

    def test_finds_a_release_build_in_the_workspace(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        monkeypatch.delenv("REACTOR_FFI_LIB", raising=False)
        monkeypatch.setattr(hook.sys, "platform", "linux")
        root = tmp_path / "sdks" / "python"
        root.mkdir(parents=True)
        release = tmp_path / "target" / "release"
        release.mkdir(parents=True)
        lib = release / "libreactor_ffi.so"
        lib.write_bytes(b"\x00")

        assert hook._locate_library(root) == lib
