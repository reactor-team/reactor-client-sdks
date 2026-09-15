#!/usr/bin/env python3
"""Artifact regressions: namespace mismatch, architecture and ELF page alignment."""

import importlib.util
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "native_check", Path(__file__).with_name("check-kotlin-native.py")
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NativeArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.library = Path(self.temp.name) / "libreactor_ffi.so"
        self.jar = Path(self.temp.name) / "libwebrtc.jar"

    def artifacts(
        self, alignment=16384, machine=183, prefix="inc/reactor/", second_alignment=None
    ):
        count = 2 if second_alignment is not None else 1
        data = bytearray(64 + 56 * count)
        data[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", data, 18, machine)
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, count)
        for index, value in enumerate([alignment, second_alignment][:count]):
            struct.pack_into(
                "<IIQQQQQQ", data, 64 + index * 56, 1, 5, 0, 0, 0, 0, 0, value
            )
        data += b"\x00inc.reactor.org.webrtc.WebRtcClassLoader\x00"
        self.library.write_bytes(data)
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr(prefix + "org/webrtc/WebRtcClassLoader.class", b"fixture")

    def test_matching_artifacts(self):
        self.artifacts()
        self.assertIn("16 KB", module.check(self.library, self.jar))

    def test_jni_helper_elf_only_cli(self):
        self.artifacts()
        # The helper links the FFI but does not embed WebRTC's class-loader name.
        data = self.library.read_bytes().split(b"\x00inc.reactor")[0]
        self.library.write_bytes(data)
        result = subprocess.run(
            [
                sys.executable,
                str(Path(__file__).with_name("check-kotlin-native.py")),
                "--elf-only",
                str(self.library),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("16 KB", result.stdout)

    def test_p6_unrelocated_jar_is_rejected(self):
        self.artifacts(prefix="")
        with self.assertRaisesRegex(ValueError, "REA-6249"):
            module.check(self.library, self.jar)

    def test_every_load_segment_must_support_16k(self):
        self.artifacts(second_alignment=4096)
        with self.assertRaisesRegex(ValueError, "Every ELF LOAD"):
            module.check(self.library, self.jar)

    def test_wrong_architecture(self):
        self.artifacts(machine=62)
        with self.assertRaisesRegex(ValueError, "AArch64"):
            module.check(self.library, self.jar)

    def test_truncated_elf(self):
        self.artifacts()
        self.library.write_bytes(self.library.read_bytes()[:80])
        with self.assertRaisesRegex(ValueError, "program-header"):
            module.check(self.library, self.jar)


if __name__ == "__main__":
    unittest.main()
