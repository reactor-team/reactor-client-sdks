#!/usr/bin/env python3
# Copyright (c) 2026 Reactor Technologies, Inc. All rights reserved.
"""Test the parts of check-mise-lock.py that need no mise binary.

Rust is the one tool mise.lock records that mise.toml does not declare: its
request is rust-toolchain.toml. These tests cover that file against the lock,
and that a change to it alone is enough to run the check.
"""

import importlib.util
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check-mise-lock.py")
_spec = importlib.util.spec_from_file_location("check_mise_lock", SCRIPT)
check = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check)

TOOLCHAIN = """\
[toolchain]
channel = "1.97.1"
components = ["rustfmt", "clippy"]
targets = [
    "x86_64-unknown-linux-gnu",
    # A comment between entries, as the real file has.
    "aarch64-apple-darwin",
    "aarch64-apple-tvos",
]
"""
# What mise writes for TOOLCHAIN: each list sorted and comma-joined.
COMPONENTS = "clippy,rustfmt"
TARGETS = "aarch64-apple-darwin,aarch64-apple-tvos,x86_64-unknown-linux-gnu"
# The lock as it was before aarch64-apple-tvos joined the toolchain file.
TARGETS_BEFORE = "aarch64-apple-darwin,x86_64-unknown-linux-gnu"


def rust_entry(version="1.97.1", components=COMPONENTS, targets=TARGETS, profile=None):
    """One [[tools.rust]] entry, as tomllib parses it out of mise.lock.

    mise writes `profile` only when the toolchain file sets one.
    """
    options = {"components": components, "targets": targets}
    if profile is not None:
        options["profile"] = profile
    return {"version": version, "backend": "core:rust", "options": options}


class InDirectory(unittest.TestCase):
    """Run each test in its own empty directory, as the script runs in a root."""

    def setUp(self):
        self._previous = Path.cwd()
        self._directory = tempfile.TemporaryDirectory(prefix="mise lock ")
        self.root = Path(self._directory.name)
        os.chdir(self.root)

    def tearDown(self):
        os.chdir(self._previous)
        self._directory.cleanup()


class RustToolchainTests(InDirectory):
    """rust-toolchain.toml against the lock's Rust entries."""

    def setUp(self):
        super().setUp()
        Path("rust-toolchain.toml").write_text(TOOLCHAIN, encoding="utf-8")

    def _set_profile(self, profile):
        text = TOOLCHAIN.replace("[toolchain]\n", f'[toolchain]\nprofile = "{profile}"\n')
        Path("rust-toolchain.toml").write_text(text, encoding="utf-8")

    def test_matching_entry_is_accepted(self):
        self.assertEqual(check.rust_errors({"rust": [rust_entry()]}), [])

    def test_target_missing_from_the_lock_is_reported(self):
        errors = check.rust_errors({"rust": [rust_entry(targets=TARGETS_BEFORE)]})

        self.assertEqual(len(errors), 1)
        self.assertIn("aarch64-apple-tvos", errors[0])

    def test_target_only_in_the_lock_is_reported(self):
        stale = TARGETS + ",x86_64-pc-windows-msvc"

        errors = check.rust_errors({"rust": [rust_entry(targets=stale)]})

        self.assertEqual(len(errors), 1)
        self.assertIn("x86_64-pc-windows-msvc", errors[0])

    def test_component_missing_from_the_lock_is_reported(self):
        errors = check.rust_errors({"rust": [rust_entry(components="clippy")]})

        self.assertEqual(len(errors), 1)
        self.assertIn("rustfmt", errors[0])

    def test_profile_only_in_the_toolchain_file_is_reported(self):
        self._set_profile("minimal")

        errors = check.rust_errors({"rust": [rust_entry()]})

        self.assertEqual(len(errors), 1)
        self.assertIn("minimal", errors[0])

    def test_profile_only_in_the_lock_is_reported(self):
        errors = check.rust_errors({"rust": [rust_entry(profile="minimal")]})

        self.assertEqual(len(errors), 1)
        self.assertIn("minimal", errors[0])

    def test_other_profile_is_reported(self):
        self._set_profile("minimal")

        errors = check.rust_errors({"rust": [rust_entry(profile="default")]})

        self.assertEqual(len(errors), 1)
        self.assertIn("default", errors[0])

    def test_matching_profile_is_accepted(self):
        self._set_profile("minimal")

        self.assertEqual(check.rust_errors({"rust": [rust_entry(profile="minimal")]}), [])

    def test_other_version_is_reported(self):
        errors = check.rust_errors({"rust": [rust_entry(version="1.96.0")]})

        self.assertEqual(len(errors), 1)
        self.assertIn("1.96.0", errors[0])

    def test_lock_without_rust_is_reported(self):
        self.assertEqual(len(check.rust_errors({})), 1)

    def test_one_matching_entry_among_several_is_accepted(self):
        # `mise lock rust` adds the new entry and keeps the old one, and mise
        # installs from a lock like that. Deleting the old entry is tidying.
        entries = [rust_entry(targets=TARGETS_BEFORE), rust_entry()]

        self.assertEqual(check.rust_errors({"rust": entries}), [])

    def test_no_toolchain_file_means_nothing_to_check(self):
        Path("rust-toolchain.toml").unlink()

        self.assertEqual(check.rust_errors({}), [])


class SkipTests(InDirectory):
    """The check is skipped only when nothing it reads has changed."""

    def setUp(self):
        super().setUp()
        for name in ("mise.toml", "mise.lock"):
            Path(name).write_text(f"# {name}\n", encoding="utf-8")
        Path("rust-toolchain.toml").write_text(TOOLCHAIN, encoding="utf-8")
        self._git("init", "--quiet", "--initial-branch=main")
        self._git("add", ".")
        self._git(
            "-c", "user.name=test", "-c", "user.email=test@example.invalid",
            "-c", "commit.gpgsign=false",
            "commit", "--quiet", "--message=base",
        )

    def _git(self, *arguments):
        # Without the machine's own git config: its hooks and signing are not
        # this test's business.
        env = {**os.environ, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_SYSTEM": os.devnull}
        subprocess.run(["git", *arguments], cwd=self.root, env=env, check=True)

    def test_unchanged_files_are_skipped(self):
        self.assertTrue(check.unchanged_since("main"))

    def test_toolchain_change_alone_is_not_skipped(self):
        changed = TOOLCHAIN.replace('"aarch64-apple-tvos",\n', "")
        Path("rust-toolchain.toml").write_text(changed, encoding="utf-8")

        self.assertFalse(check.unchanged_since("main"))

    def test_removed_toolchain_file_is_not_skipped(self):
        Path("rust-toolchain.toml").unlink()

        self.assertFalse(check.unchanged_since("main"))

    def test_lock_change_alone_is_not_skipped(self):
        Path("mise.lock").write_text("# changed\n", encoding="utf-8")

        self.assertFalse(check.unchanged_since("main"))

    def test_unknown_base_is_not_skipped(self):
        self.assertFalse(check.unchanged_since("no-such-ref"))


if __name__ == "__main__":
    unittest.main()
