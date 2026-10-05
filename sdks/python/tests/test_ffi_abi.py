"""The ABI gate in `_ffi._load()`, exercised with fake libraries.

No real build is needed: a stale `REACTOR_FFI_LIB` is exactly a library whose
symbols resolve and whose `reactor_abi_version()` answers the wrong number, so
a handful of plain callables stands in for ctypes' view of it.
"""

from __future__ import annotations

import pytest

from reactor_sdk import _ffi


def _fake_lib(abi_version: int | None) -> object:
    """A loaded-library stand-in: every attribute ctypes assigns is settable,
    and `reactor_abi_version` answers `abi_version` — or is absent, as a
    library old enough to predate the version call would be.
    """

    class FakeLib:
        pass

    lib = FakeLib()
    if abi_version is not None:

        def reactor_abi_version() -> int:
            return abi_version

        lib.reactor_abi_version = reactor_abi_version
    return lib


def test_a_library_speaking_a_stale_abi_is_rejected() -> None:
    """The case the gate exists for: `_load()` used to accept this library and
    pass `auto_resume_tracks=True` where the old `reactor_create` read its
    callbacks pointer.
    """
    with pytest.raises(_ffi.AbiMismatchError) as error:
        _ffi._check_abi_version(_fake_lib(abi_version=_ffi.ABI_VERSION - 1))

    message = str(error.value)
    # Both numbers, or the message sends the reader to the wrong place.
    assert str(_ffi.ABI_VERSION) in message
    assert str(_ffi.ABI_VERSION - 1) in message
    # And what to do about it.
    assert "cargo build -p reactor-ffi --release" in message


def test_a_library_without_reactor_abi_version_is_rejected() -> None:
    with pytest.raises(_ffi.AbiMismatchError) as error:
        _ffi._check_abi_version(_fake_lib(abi_version=None))

    assert "reactor_abi_version" in str(error.value)


def test_a_library_speaking_the_current_abi_passes() -> None:
    _ffi._check_abi_version(_fake_lib(abi_version=_ffi.ABI_VERSION))


def test_the_gate_runs_before_any_other_signature_is_applied(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """`_load()` stops at the gate: a stale library never gets to the signature
    block, so `reactor_create` is never reached with shifted arguments.

    The fake exports nothing else, so if the gate did not fire first the very
    next line — `lib.reactor_create.restype = ...` — would raise
    `AttributeError`, which `pytest.raises` would not accept here.
    """
    lib = _fake_lib(abi_version=_ffi.ABI_VERSION - 1)
    monkeypatch.setattr(_ffi, "_find_lib", lambda: "/fake/libreactor_ffi.so")
    monkeypatch.setattr(_ffi.ctypes, "CDLL", lambda _path: lib)

    with pytest.raises(_ffi.AbiMismatchError):
        _ffi._load()
