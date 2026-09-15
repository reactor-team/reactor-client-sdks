#!/usr/bin/env python3
"""Release-switch and staging regressions, run by the normal Kotlin CI checks."""

import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent


def module(name):
    spec = importlib.util.spec_from_file_location(name, HERE / (name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


release = module("kotlin-release-version")
staging = module("kotlin-distribution")


class DistributionTest(unittest.TestCase):
    def test_missing_natives_do_not_create_distribution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(ValueError, "Missing real native"):
                staging.stage("linux-x64", root, root / "output", None)
            self.assertFalse((root / "output").exists())

    def test_version_bump_requires_matching_changelog(self):
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "push", "BEFORE": "abc"}):
            with patch.object(release.subprocess, "run") as run:
                run.return_value.returncode = 0
                run.return_value.stdout = "reactorVersion=0.1.0"
                with patch.object(
                    Path,
                    "read_text",
                    side_effect=["reactorVersion=0.2.0", "## [Unreleased]"],
                ):
                    with self.assertRaisesRegex(ValueError, "Missing CHANGELOG"):
                        release.detect()
                with patch.object(
                    Path,
                    "read_text",
                    side_effect=["reactorVersion=0.2.0", "## [0.2.0] - 2026-09-13"],
                ):
                    self.assertEqual(release.detect(), ("0.2.0", True))
                with patch.object(
                    Path, "read_text", return_value="reactorVersion=0.1.0"
                ):
                    self.assertEqual(release.detect(), ("0.1.0", False))

    def test_manual_and_pr_events_never_release(self):
        for event in ("pull_request", "workflow_dispatch"):
            with patch.dict(os.environ, {"GITHUB_EVENT_NAME": event, "BEFORE": "abc"}):
                with patch.object(
                    Path, "read_text", return_value="reactorVersion=1.0.0"
                ):
                    self.assertEqual(release.detect(), ("1.0.0", False))


if __name__ == "__main__":
    unittest.main()
