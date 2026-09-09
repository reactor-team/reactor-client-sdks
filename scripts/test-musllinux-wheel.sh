#!/bin/sh
# Run in a fresh Python Alpine container, without the build toolchain or prebuilts.
set -eu
python -m venv /tmp/smoke
PY=/tmp/smoke/bin/python
"$PY" -m pip install --quiet --upgrade pip
"$PY" -m pip install --no-index /io/dist/*.whl
cd /tmp
unset REACTOR_FFI_LIB PYTHONPATH
"$PY" - <<'PY'
import pathlib
import sysconfig

import reactor_sdk
from reactor_sdk import _ffi

library = pathlib.Path(_ffi._find_lib()).resolve()
site = pathlib.Path(sysconfig.get_paths()['purelib']).resolve()
assert library.is_relative_to(site), (library, site)
lib = _ffi.get_lib()
assert lib.reactor_status(None) == b'disconnected'
assert lib.reactor_destroy(None) == 0
print(f'{reactor_sdk.__version__}: bundled library loads and runs on musl: {library}')
PY
"$PY" -m pip install --quiet pytest pytest-asyncio
"$PY" -m pytest -v -o cache_dir=/tmp/pytest-cache /io/sdks/python/tests/test_ffi_bindings.py \
    /io/sdks/python/tests/test_client.py
