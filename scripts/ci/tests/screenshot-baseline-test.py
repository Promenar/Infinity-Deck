#!/usr/bin/env python3
"""Linux 截图参考图校验与合成契约。"""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "screenshot-baseline.py"


class ScreenshotBaselineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base = self.root / "base"
        self.overlay = self.root / "overlay"
        self.output = self.root / "output"
        self.base.mkdir()
        self.overlay.mkdir()
        (self.base / "one.png").write_bytes(b"base")
        (self.base / "two.png").write_bytes(b"same")
        (self.overlay / "one.png").write_bytes(b"linux")
        self.manifest = {"version": 1, "entries": {"one.png": {
            "base_sha256": hashlib.sha256(b"base").hexdigest(),
            "linux_sha256": hashlib.sha256(b"linux").hexdigest(),
        }}}
        self.save_manifest()

    def save_manifest(self):
        (self.overlay / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def run_script(self, *mode):
        return subprocess.run([sys.executable, str(SCRIPT), "--base", str(self.base), "--overlay", str(self.overlay),
                               *(mode or ("--output", str(self.output)))], capture_output=True, text=True)

    def input_state(self):
        return {path.relative_to(self.root): path.read_bytes() if path.is_file() else None
                for path in self.root.rglob("*")}

    def test_check_only_is_read_only(self):
        before = self.input_state()
        result = self.run_script("--check-only")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("1 条", result.stdout)
        self.assertEqual(self.input_state(), before)

    def test_both_modes_list_all_mismatches_without_writing(self):
        (self.base / "one.png").write_bytes(b"changed base")
        (self.overlay / "one.png").write_bytes(b"changed linux")
        (self.overlay / "extra.png").write_bytes(b"unlisted")
        self.manifest["entries"]["two.png"] = self.manifest["entries"]["one.png"]
        self.manifest["entries"]["../outside.png"] = self.manifest["entries"]["one.png"]
        self.save_manifest()
        before = self.input_state()
        for mode in ("--check-only", "--output"):
            with self.subTest(mode=mode):
                args = (mode,) if mode == "--check-only" else (mode, str(self.output))
                result = self.run_script(*args)
                self.assertEqual(result.returncode, 1, result.stderr)
                for diagnostic in ("共享参考图 SHA-256 失配：'one.png'", "Linux 覆盖图 SHA-256 失配：'one.png'",
                                   "Linux 覆盖图缺失：'two.png'", "截图参考路径无效：'../outside.png'",
                                   "未登记）：'extra.png'", "须重新审阅"):
                    self.assertIn(diagnostic, result.stderr)
                for content in (b"base", b"linux", b"changed base", b"changed linux"):
                    self.assertIn(hashlib.sha256(content).hexdigest(), result.stderr)
                self.assertEqual(self.input_state(), before)

    def test_check_only_cannot_take_an_output_directory(self):
        result = self.run_script("--check-only", "--output", str(self.output))
        self.assertEqual(result.returncode, 2)
        self.assertFalse(self.output.exists())

    def test_merges_without_changing_inputs(self):
        self.assertEqual(self.run_script().returncode, 0)
        self.assertEqual((self.output / "one.png").read_bytes(), b"linux")
        self.assertEqual((self.output / "two.png").read_bytes(), b"same")
        self.assertEqual((self.base / "one.png").read_bytes(), b"base")

    def test_stale_base_fails_before_writing_output(self):
        (self.base / "one.png").write_bytes(b"changed")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("须重新审阅", result.stderr)
        self.assertFalse(self.output.exists())

    def test_unlisted_overlay_fails(self):
        (self.overlay / "extra.png").write_bytes(b"extra")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("与清单不一致", result.stderr)
        self.assertFalse(self.output.exists())

    def test_changed_linux_candidate_fails(self):
        (self.overlay / "one.png").write_bytes(b"changed")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("须重新审阅", result.stderr)
        self.assertFalse(self.output.exists())

    def test_missing_linux_candidate_fails(self):
        (self.overlay / "one.png").unlink()
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Linux 覆盖图缺失", result.stderr)
        self.assertFalse(self.output.exists())

    def test_output_must_not_exist(self):
        self.output.mkdir()
        (self.output / "keep").write_text("keep")
        self.assertNotEqual(self.run_script().returncode, 0)
        self.assertEqual((self.output / "keep").read_text(), "keep")

    def test_manifest_cannot_escape_reference_directory(self):
        self.manifest["entries"]["../outside.png"] = self.manifest["entries"]["one.png"]
        self.save_manifest()
        self.assertNotEqual(self.run_script().returncode, 0)
        self.assertFalse(self.output.exists())

    def test_symlinked_baseline_is_rejected(self):
        (self.base / "two.png").unlink()
        (self.base / "two.png").symlink_to(self.root / "outside")
        (self.root / "outside").write_bytes(b"private")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("符号链接", result.stderr)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
