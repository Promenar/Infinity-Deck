#!/usr/bin/env python3
"""多进程并行截图校验脚本单元测试。"""

from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "validate-screenshots-parallel.py"
SPEC = importlib.util.spec_from_file_location("validate_screenshots_parallel", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class ValidateScreenshotsParallelTest(unittest.TestCase):
    def test_default_shard_count_scales_safely_with_cpu_and_ram(self) -> None:
        self.assertEqual(MODULE.default_shard_count(cpus=18, ram_gib=48), 8)
        self.assertEqual(MODULE.default_shard_count(cpus=12, ram_gib=32), 5)
        self.assertEqual(MODULE.default_shard_count(cpus=4, ram_gib=16), 2)
        self.assertEqual(MODULE.default_shard_count(cpus=2, ram_gib=8), 2)

    def test_discover_filter_and_pack_shards_balances_by_weight(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "native-ui/app/src/screenshotTest/kotlin/com/example"
            src.mkdir(parents=True)
            (src / "HeavyScreenshotTest.kt").write_text(
                "package com.example\n@PreviewTest\nfun heavy() {}\n", encoding="utf-8"
            )
            (src / "LightAScreenshotTest.kt").write_text(
                "package com.example\n@PreviewTest\nfun lightA() {}\n", encoding="utf-8"
            )
            (src / "LightBScreenshotTest.kt").write_text(
                "package com.example\n@PreviewTest\nfun lightB() {}\n", encoding="utf-8"
            )
            (src / "Helper.kt").write_text("package com.example\nfun helper() {}\n", encoding="utf-8")

            ref = root / "native-ui/app/src/screenshotTestDebug/reference/com/example"
            for idx in range(10):
                p = ref / "HeavyScreenshotTestKt" / f"p{idx}.png"
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(b"png")
            for name in ("LightAScreenshotTestKt", "LightBScreenshotTestKt"):
                p = ref / name / "p0.png"
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(b"png")

            discovered = MODULE.discover_preview_classes(root)
            self.assertEqual([item.fqcn for item in discovered], [
                "com.example.HeavyScreenshotTestKt",
                "com.example.LightAScreenshotTestKt",
                "com.example.LightBScreenshotTestKt",
            ])
            self.assertEqual(discovered[0].weight, 10)

            buckets = MODULE.pack_shards(discovered, 2)
            self.assertEqual(len(buckets), 2)
            weights = sorted(sum(item.weight for item in bucket) for bucket in buckets)
            self.assertEqual(weights, [2, 10])

            filtered, selectors = MODULE.filter_preview_classes(discovered, ["*LightA*"])
            self.assertEqual([item.simple_name for item in filtered], ["LightAScreenshotTestKt"])
            self.assertEqual(selectors, {"com.example.LightAScreenshotTestKt": ["com.example.LightAScreenshotTestKt"]})

            filtered_m, selectors_m = MODULE.filter_preview_classes(discovered, ["HeavyScreenshotTestKt.heavy"])
            self.assertEqual([item.simple_name for item in filtered_m], ["HeavyScreenshotTestKt"])
            self.assertEqual(selectors_m, {"com.example.HeavyScreenshotTestKt": ["com.example.HeavyScreenshotTestKt#heavy"]})

    def test_build_shard_properties_overrides_isolated_paths(self) -> None:
        base = (
            "PreviewScreenshotTestEngineInput.ImageDiffer.threshold=1.0E-4\n"
            "PreviewScreenshotTestEngineInput.XmlReportInput.outputDirectory=/old/xml\n"
            "PreviewScreenshotTestEngineInput.previewImageOutputDir=/old/rendered\n"
            "PreviewScreenshotTestEngineInput.previewDiffImageOutputDir=/old/diffs\n"
            "PreviewScreenshotTestEngineInput.referenceImageDir=/old/ref\n"
            "PreviewScreenshotTestEngineInput.TestOption.recordingModeEnabled=false\n"
        )
        rendered = MODULE.build_shard_properties(
            base_properties_text=base,
            xml_dir=Path("/tmp/s0/xml"),
            rendered_dir=Path("/tmp/s0/rendered"),
            diff_dir=Path("/tmp/s0/diffs"),
            reference_dir=Path("/tmp/ref"),
            recording_mode=True,
        )
        self.assertIn("PreviewScreenshotTestEngineInput.ImageDiffer.threshold=1.0E-4", rendered)
        self.assertIn("PreviewScreenshotTestEngineInput.XmlReportInput.outputDirectory=/tmp/s0/xml", rendered)
        self.assertIn("PreviewScreenshotTestEngineInput.previewImageOutputDir=/tmp/s0/rendered", rendered)
        self.assertIn("PreviewScreenshotTestEngineInput.previewDiffImageOutputDir=/tmp/s0/diffs", rendered)
        self.assertIn("PreviewScreenshotTestEngineInput.referenceImageDir=/tmp/ref", rendered)
        self.assertIn("PreviewScreenshotTestEngineInput.TestOption.recordingModeEnabled=true", rendered)

    def test_validate_shard_xml_detects_missing_class_and_failures(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            xml_dir = Path(tmp)
            xml_path = xml_dir / "TEST-preview-screenshot-test-engine.xml"
            xml_path.write_text(
                '<testsuite name="preview" tests="2" failures="0" errors="0" skipped="0">'
                '<testcase classname="com.example.AlphaKt" name="one"/>'
                '<testcase classname="com.example.BetaKt" name="two"/>'
                "</testsuite>",
                encoding="utf-8",
            )
            count, observed, failures, err = MODULE.validate_shard_xml(xml_dir, ["AlphaKt", "BetaKt"])
            self.assertIsNone(err)
            self.assertEqual(count, 2)
            self.assertEqual(observed, {"AlphaKt": 1, "BetaKt": 1})
            self.assertEqual(failures, [])

            _, _, _, err_missing = MODULE.validate_shard_xml(xml_dir, ["AlphaKt", "BetaKt", "GammaKt"])
            self.assertIsNotNone(err_missing)
            self.assertIn("GammaKt", err_missing)


if __name__ == "__main__":
    unittest.main()
