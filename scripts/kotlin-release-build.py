#!/usr/bin/env python3
"""Build a desktop JNI binary against the already-built, matching Rust FFI."""

from __future__ import annotations

import os
import platform
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
sdk = root / "sdks/kotlin"
output = root / "target/kotlin-release"
output.mkdir(parents=True, exist_ok=True)
java = Path(os.environ["JAVA_HOME"])
subprocess.run(
    [
        str(sdk / ("gradlew.bat" if os.name == "nt" else "gradlew")),
        "--no-daemon",
        ":reactor-core:classes",
    ],
    cwd=sdk,
    check=True,
)
classes = sdk / "reactor-core/build/classes/kotlin/main"
with (output / "jni_generated.h").open("w") as header:
    subprocess.run(
        [
            sys.executable,
            str(root / "scripts/kotlin-jni-headers.py"),
            str(java / "bin/javap"),
            str(classes),
            "inc.reactor.sdk.internal.NativeAbi",
            str(classes),
            "inc.reactor.sdk.internal.NativeClient",
        ],
        stdout=header,
        check=True,
    )
ffi = root / "target/release"
macos_flags = []
if sys.platform == "darwin":
    floor = os.environ.get("MACOSX_DEPLOYMENT_TARGET") or ("11.0" if platform.machine() == "arm64" else "13.0")
    macos_flags = [f"-DCMAKE_OSX_DEPLOYMENT_TARGET={floor}"]
subprocess.run(
    [
        "cmake",
        "-S",
        str(sdk / "native"),
        "-B",
        str(output / "build"),
        f"-DREACTOR_FFI_DIR={ffi}",
        f"-DREACTOR_JNI_HEADERS={output}",
        f"-DCMAKE_INSTALL_PREFIX={output / 'lib'}",
        "-DCMAKE_BUILD_TYPE=Release",
        *macos_flags,
    ],
    check=True,
)
subprocess.run(
    [
        "cmake",
        "--build",
        str(output / "build"),
        "--config",
        "Release",
        "--parallel",
        "2",
    ],
    check=True,
)
subprocess.run(
    ["cmake", "--install", str(output / "build"), "--config", "Release"], check=True
)
name = (
    "reactor_ffi.dll"
    if os.name == "nt"
    else "libreactor_ffi.dylib"
    if sys.platform == "darwin"
    else "libreactor_ffi.so"
)
shutil.copy2(ffi / name, output / "lib" / name)
if sys.platform == "darwin":
    subprocess.run(
        [
            "install_name_tool",
            "-id",
            "@rpath/libreactor_ffi.dylib",
            str(output / "lib" / name),
        ],
        check=True,
    )
    jni = output / "lib/libreactor_jni.dylib"
    dependencies = subprocess.check_output(["otool", "-L", str(jni)], text=True)
    for line in dependencies.splitlines()[1:]:
        dependency = line.strip().split(" (", 1)[0]
        if dependency.endswith("/libreactor_ffi.dylib"):
            subprocess.run(["install_name_tool", "-change", dependency, "@rpath/libreactor_ffi.dylib", str(jni)], check=True)
    for library in (output / "lib").glob("*.dylib"):
        subprocess.run(["codesign", "--force", "--sign", "-", str(library)], check=True)
