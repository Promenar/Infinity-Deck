#!/usr/bin/env python3
"""Infinity Deck 文档解析依赖的 R8 规则静态防回归测试。"""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
RULES = (ROOT / "native-ui/app/proguard-rules.pro").read_text(encoding="utf-8")


class R8DocumentRulesContractTest(unittest.TestCase):
    def test_log4j_reflective_message_factories_keep_only_public_no_arg_constructor(
        self,
    ) -> None:
        factories = (
            "DefaultFlowMessageFactory",
            "ReusableMessageFactory",
            "ParameterizedMessageFactory",
        )
        for factory in factories:
            self.assertIn(
                "-keepclassmembers,allowobfuscation class "
                f"org.apache.logging.log4j.message.{factory} {{\n"
                "    public <init>();\n"
                "}",
                RULES,
            )

        forbidden = (
            "-keep class org.apache.logging.log4j.**",
            "-keep class org.apache.logging.log4j.message.**",
            "-keep class org.apache.logging.log4j.message.DefaultFlowMessageFactory { *; }",
            "-keep class org.apache.logging.log4j.message.ReusableMessageFactory { *; }",
            "-keep class org.apache.logging.log4j.message.ParameterizedMessageFactory { *; }",
        )
        for rule in forbidden:
            self.assertNotIn(rule, RULES)

    def test_ooxml_schema_does_not_keep_every_member(self) -> None:
        self.assertNotRegex(
            RULES,
            re.compile(
                r"-keep[^\n]*org\.openxmlformats\.schemas\.wordprocessingml\.\*\*"
                r"\s*\{\s*\*;\s*\}",
                re.MULTILINE,
            ),
        )
        self.assertIn(
            "-keep,allowoptimization class "
            "org.apache.poi.schemas.ooxml.system.ooxml.TypeSystemHolder { *; }",
            RULES,
        )

    def test_commons_compress_zip_extra_fields_keep_only_public_no_arg_constructor(
        self,
    ) -> None:
        expected_header = (
            "-keepclassmembers,allowobfuscation class "
            "org.apache.commons.compress.archivers.zip.** implements "
            "org.apache.commons.compress.archivers.zip.ZipExtraField {"
        )
        self.assertIn(
            expected_header + "\n"
            "    public <init>();\n"
            "}",
            RULES,
        )
        self.assertEqual(
            [expected_header],
            re.findall(
                r"(?m)^-keep[^\n]*org\.apache\.commons\.compress[^\n]*$",
                RULES,
            ),
        )

        forbidden = (
            "-keep class org.apache.commons.compress.**",
            "-keep class org.apache.commons.compress.archivers.zip.**",
            "-keepclassmembers class org.apache.commons.compress.archivers.zip.** { *; }",
            "-dontwarn org.apache.commons.compress.**",
            "-dontwarn org.apache.commons.compress.archivers.zip.**",
        )
        for rule in forbidden:
            self.assertNotIn(rule, RULES)

    def test_document_dependencies_never_disable_all_warnings(self) -> None:
        forbidden = (
            "-dontwarn **",
            "-dontwarn org.apache.poi.**",
            "-dontwarn org.apache.xmlbeans.**",
            "-dontwarn org.openxmlformats.schemas.**",
        )
        for rule in forbidden:
            self.assertNotIn(rule, RULES)

    def test_only_known_optional_android_integrations_are_suppressed(self) -> None:
        required_exact_rules = (
            "-dontwarn aQute.bnd.annotation.spi.ServiceConsumer",
            "-dontwarn com.gemalto.jp2.JP2Decoder",
            "-dontwarn java.awt.Color",
            "-dontwarn javax.xml.stream.XMLStreamReader",
            "-dontwarn net.sf.saxon.Configuration",
            "-dontwarn org.osgi.framework.Bundle",
            "-dontwarn org.slf4j.impl.StaticLoggerBinder",
        )
        for rule in required_exact_rules:
            self.assertIn(rule, RULES)


if __name__ == "__main__":
    unittest.main(verbosity=2)
