#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("verify", HERE / "kotlin-release-verify.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class ReleaseVerifyTest(unittest.TestCase):
    def test_metadata_and_android_archive(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / "sdks/kotlin").mkdir(parents=True)
            (root / "sdks/kotlin/gradle.properties").write_text("reactorVersion=1.2.3\n")
            (root / "sdks/kotlin/CHANGELOG.md").write_text("## [1.2.3]\n")
            maven = root / "maven/inc/reactor"
            for module in ("reactor-core", "reactor-desktop", "reactor-android"):
                directory = maven / module / "1.2.3"
                directory.mkdir(parents=True)
                (directory / f"{module}-1.2.3.pom").write_text("<project />")
            aar = maven / "reactor-android/1.2.3/reactor-android-1.2.3.aar"
            with __import__("zipfile").ZipFile(aar, "w") as archive:
                for name in ("jni/arm64-v8a/libreactor_ffi.so", "jni/arm64-v8a/libreactor_jni.so", "assets/reactor/LICENSE", "assets/reactor/NOTICE"):
                    archive.writestr(name, "ok")
            self.assertEqual(verify.version(root), "1.2.3")
            verify.verify_consumers(root, root / "maven", "1.2.3")


if __name__ == "__main__":
    unittest.main()
