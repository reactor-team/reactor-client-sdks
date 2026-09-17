#!/usr/bin/env python3
"""Exercise Maven artifacts from an external checkout while producer build trees are hidden."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path)
    parser.add_argument("platform")
    parser.add_argument("--android", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    sdk = root / "sdks/kotlin"
    version = next(
        line.split("=", 1)[1]
        for line in (sdk / "gradle.properties").read_text().splitlines()
        if line.startswith("reactorVersion=")
    )
    # Keep the rehearsal logs/artifacts available for inspection after the run.
    external = Path(tempfile.mkdtemp(prefix="reactor-distribution-"))
    consumer = external / "consumer"
    shutil.copytree(sdk / "distribution/consumer", consumer)
    shutil.copytree(sdk / "gradle/wrapper", consumer / "gradle/wrapper")
    for name in ("gradlew", "gradlew.bat"):
        shutil.copy2(sdk / name, consumer / name)
    repository = args.repository.resolve()
    relocated = external / "maven"
    hidden = []
    try:
        shutil.move(str(repository), relocated)
        # A baked-in load path cannot accidentally pass by finding the producer's output.
        for path in [root / "target", *sdk.glob("*/build")]:
            if path.exists():
                destination = path.with_name(path.name + "-distribution-hidden")
                if destination.exists():
                    raise RuntimeError(f"Refusing to replace {destination}")
                path.rename(destination)
                hidden.append((path, destination))
        environment = os.environ.copy()
        environment.pop("REACTOR_NATIVE_DIR", None)
        command = [
            str(consumer / ("gradlew.bat" if os.name == "nt" else "gradlew")),
            "--no-daemon",
            f"-PstagedRepository={relocated}",
            f"-PsdkVersion={version}",
            f"-PreactorDesktopPlatform={args.platform}",
        ]
        subprocess.run(
            [*command, ":desktop:run"], cwd=consumer, env=environment, check=True
        )
        subprocess.run(
            [*command, ":desktop:run", "-PomitNative=true", "--args=Missing"],
            cwd=consumer,
            env=environment,
            check=True,
        )
        bad = external / "invalid-native"
        bad.mkdir()
        environment["REACTOR_NATIVE_DIR"] = str(bad)
        subprocess.run(
            [*command, ":desktop:run", "--args=Missing"],
            cwd=consumer,
            env=environment,
            check=True,
        )
        suffix = (
            "dll"
            if args.platform.startswith("windows")
            else "dylib"
            if args.platform.startswith("macos")
            else "so"
        )
        prefix = "" if suffix == "dll" else "lib"
        for name in ("reactor_ffi", "reactor_jni"):
            (bad / f"{prefix}{name}.{suffix}").write_text("invalid binary")
        subprocess.run(
            [*command, ":desktop:run", "--args=Cannot initialize"],
            cwd=consumer,
            env=environment,
            check=True,
        )
        environment.pop("REACTOR_NATIVE_DIR")
        if args.android:
            subprocess.run(
                [
                    *command,
                    "-PincludeAndroid=true",
                    ":android:assembleRelease",
                    ":android:connectedReleaseAndroidTest",
                ],
                cwd=consumer,
                env=environment,
                check=True,
            )
        print(f"External distribution consumers passed: {external}")
    finally:
        for original, destination in reversed(hidden):
            destination.rename(original)
        if relocated.exists():
            shutil.move(str(relocated), repository)


if __name__ == "__main__":
    main()
