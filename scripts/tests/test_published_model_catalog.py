"""独立发布模型目录的行为测试。"""

from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/model-catalog/build-published-catalog.py"
OFFICIAL_OVERRIDES = ROOT / "scripts/model-catalog/official-model-overrides.json"
THIRD_PARTY_NOTICES = ROOT / "scripts/model-catalog/third-party-notices.txt"
FIXTURES = Path(__file__).resolve().parent / "fixtures/published-model-catalog"
GENERATED_AT = "2026-09-22T05:00:00Z"
CATALOG_VERSION = "1790053200"
KEY_ID = "infinity-catalog-p256-20260922"


class PublishedModelCatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.key_dir = tempfile.TemporaryDirectory()
        cls.private_key = Path(cls.key_dir.name) / "catalog-key.pem"
        cls.public_key = Path(cls.key_dir.name) / "catalog-public.pem"
        subprocess.run(
            ["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(cls.private_key)],
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["openssl", "pkey", "-in", str(cls.private_key), "-pubout", "-out", str(cls.public_key)],
            check=True,
            capture_output=True,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.key_dir.cleanup()

    def run_builder(
        self,
        output_root: Path,
        *,
        sources: dict[str, Path] | None = None,
        signing_env: bool = False,
        include_default_overrides: bool = False,
        official_overrides: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        selected = sources or {
            "models-dev-models": FIXTURES / "models-dev-models.json",
            "models-dev-api": FIXTURES / "models-dev-api.json",
            "litellm": FIXTURES / "litellm.json",
            "openrouter": FIXTURES / "openrouter.json",
        }
        command = [
            "python3",
            str(SCRIPT),
            "--models-dev-models",
            str(selected["models-dev-models"]),
            "--models-dev-api",
            str(selected["models-dev-api"]),
            "--litellm",
            str(selected["litellm"]),
            "--openrouter",
            str(selected["openrouter"]),
            "--output-root",
            str(output_root),
            "--key-id",
            KEY_ID,
            "--catalog-version",
            CATALOG_VERSION,
            "--generated-at",
            GENERATED_AT,
        ]
        environment = os.environ.copy()
        if signing_env:
            environment["TEST_CATALOG_SIGNING_KEY"] = self.private_key.read_text(encoding="utf-8")
            command.extend(["--signing-key-env", "TEST_CATALOG_SIGNING_KEY"])
        else:
            command.extend(["--signing-key", str(self.private_key)])
        if official_overrides is not None:
            command.extend(["--official-overrides", str(official_overrides)])
        elif not include_default_overrides:
            command.append("--no-official-overrides")
        return subprocess.run(command, text=True, capture_output=True, env=environment)

    def test_builds_scoped_catalog_and_verifiable_manifest(self) -> None:
        """捕获字段合并、false 丢失、作用域丢失或签名不覆盖原始 payload 的缺陷。"""
        with tempfile.TemporaryDirectory() as tmp:
            result = self.run_builder(Path(tmp))
            self.assertEqual(result.returncode, 0, result.stderr)
            publish_dir = Path(tmp) / "model-catalog/v1"
            manifest_bytes = (publish_dir / "manifest.json").read_bytes()
            self.assertLessEqual(len(manifest_bytes), 64 * 1024)
            envelope = json.loads(manifest_bytes)
            self.assertEqual(set(envelope), {"keyId", "payload", "signature"})
            self.assertEqual(envelope["keyId"], KEY_ID)

            payload_bytes = base64.b64decode(envelope["payload"], validate=True)
            payload = json.loads(payload_bytes)
            self.assertEqual(payload["schemaVersion"], 1)
            self.assertEqual(payload["catalogVersion"], int(CATALOG_VERSION))
            self.assertEqual(payload["generatedAt"], GENERATED_AT)
            self.assertRegex(payload["catalogFile"], r"^catalog-[0-9a-f]{64}\.json$")

            catalog_bytes = (publish_dir / payload["catalogFile"]).read_bytes()
            self.assertEqual(payload["catalogBytes"], len(catalog_bytes))
            self.assertEqual(payload["catalogSha256"], hashlib.sha256(catalog_bytes).hexdigest())
            self.assertLessEqual(len(catalog_bytes), 16 * 1024 * 1024)
            catalog = json.loads(catalog_bytes)
            self.assertEqual(catalog["schemaVersion"], 3)
            self.assertEqual(catalog["generatedAt"], GENERATED_AT)
            self.assertEqual(payload["recordCount"], len(catalog["records"]))
            self.assertEqual(len(catalog["sources"]), 4)
            self.assertEqual(
                {source["id"]: source["license"] for source in catalog["sources"]},
                {
                    "models-dev-models": "MIT: https://github.com/anomalyco/models.dev/blob/dev/LICENSE",
                    "models-dev-api": "MIT: https://github.com/anomalyco/models.dev/blob/dev/LICENSE",
                    "litellm": "MIT: https://github.com/BerriAI/litellm/blob/main/LICENSE",
                    "openrouter": "OpenRouter Terms of Service: https://openrouter.ai/terms",
                },
            )

            records = {
                (item["source"], item.get("providerScope"), item["canonicalModelId"]): item
                for item in catalog["records"]
            }
            canonical = records[("MODELS_DEV", None, "acme/text-pro")]
            self.assertEqual(canonical["exactAliases"], ["text-pro"])
            self.assertIs(canonical["reasoning"], False)
            self.assertIs(canonical["structured_output"], False)
            self.assertEqual(canonical["contextTokens"], 128000)
            self.assertEqual(canonical["inputTokens"], 96000)
            self.assertEqual(canonical["outputTokens"], 32000)
            self.assertNotIn("reasoning", records[("MODELS_DEV", None, "acme/unknown-state")])
            audio = records[("MODELS_DEV", None, "acme/audio-transcribe")]
            self.assertNotIn("contextTokens", audio)
            self.assertNotIn("outputTokens", audio)
            self.assertEqual(records[("MODELS_DEV", None, "other/text-pro")]["exactAliases"], ["text-pro"])
            self.assertNotIn("exactAliases", records[("MODELS_DEV", None, "org/team/model")])
            self.assertEqual(records[("MODELS_DEV", None, "acme/image-gen")]["workload"], "IMAGE_GENERATION")
            self.assertEqual(records[("MODELS_DEV", None, "acme/input-only")]["workload"], "UNKNOWN")
            self.assertEqual(records[("MODELS_DEV", None, "acme/decision-canonical")]["workload"], "UNKNOWN")

            provider = records[("MODELS_DEV", "acme", "text-pro")]
            self.assertIs(provider["tool_call"], False)
            self.assertEqual(provider["outputTokens"], 8192)
            lite = records[("LITELLM", "acme", "text-pro")]
            self.assertIs(lite["tool_call"], False)
            self.assertIs(lite["structured_output"], True)
            self.assertIs(lite["reasoning"], True)
            self.assertEqual(records[("LITELLM", "acme", "acme/text-pro")]["outputTokens"], 4000)
            self.assertEqual(records[("LITELLM", "acme", "decision-model")]["workload"], "UNKNOWN")
            self.assertEqual(records[("LITELLM", "acme", "image-model")]["workload"], "IMAGE_GENERATION")
            router = records[("OPENROUTER", "openrouter", "acme/text-pro")]
            self.assertEqual(router["outputTokens"], 24000)
            self.assertEqual(router["knowledge"], "2025-12-31")
            self.assertNotIn(("MODELS_DEV", "openrouter", "acme/text-pro"), records)

            signature_path = Path(tmp) / "signature.der"
            payload_path = Path(tmp) / "payload.json"
            signature_path.write_bytes(base64.b64decode(envelope["signature"], validate=True))
            payload_path.write_bytes(payload_bytes)
            verify = subprocess.run(
                [
                    "openssl",
                    "dgst",
                    "-sha256",
                    "-verify",
                    str(self.public_key),
                    "-signature",
                    str(signature_path),
                    str(payload_path),
                ],
                text=True,
                capture_output=True,
            )
            self.assertEqual(verify.returncode, 0, verify.stderr)
            self.assertIn("Verified OK", verify.stdout)

            notice = (publish_dir / "third-party-notices.txt").read_text(encoding="utf-8")
            self.assertEqual(notice, THIRD_PARTY_NOTICES.read_text(encoding="utf-8"))
            self.assertIn("Copyright (c) 2025 models.dev", notice)
            self.assertIn("Copyright (c) 2023 Berri AI", notice)
            self.assertIn("https://openrouter.ai/terms", notice)
            self.assertIn("Creative Commons Attribution 4.0", notice)
            self.assertIn("https://github.com/OpenSenseNova/SenseNova6.8/blob/main/API_CN.md", notice)
            self.assertNotIn("BEGIN PRIVATE KEY", notice)
            self.assertNotIn("INFINITY_MODEL_CATALOG_SIGNING_KEY", notice)

    def test_same_inputs_produce_same_catalog_bytes(self) -> None:
        """捕获依赖当前时间、输入对象顺序或非确定遍历顺序的输出。"""
        with tempfile.TemporaryDirectory() as left, tempfile.TemporaryDirectory() as right:
            first = self.run_builder(Path(left))
            second = self.run_builder(Path(right), signing_env=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            self.assertEqual(second.returncode, 0, second.stderr)
            first_manifest = json.loads((Path(left) / "model-catalog/v1/manifest.json").read_bytes())
            second_manifest = json.loads((Path(right) / "model-catalog/v1/manifest.json").read_bytes())
            self.assertEqual(first_manifest["payload"], second_manifest["payload"])
            first_payload = json.loads(base64.b64decode(first_manifest["payload"]))
            second_payload = json.loads(base64.b64decode(second_manifest["payload"]))
            self.assertEqual(first_payload, second_payload)
            self.assertEqual(
                (Path(left) / "model-catalog/v1" / first_payload["catalogFile"]).read_bytes(),
                (Path(right) / "model-catalog/v1" / second_payload["catalogFile"]).read_bytes(),
            )

    def test_malformed_required_source_leaves_no_publishable_directory(self) -> None:
        """捕获必需源失败后仍生成 manifest 或覆盖最后有效发布的缺陷。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            publish_dir = root / "model-catalog/v1"
            publish_dir.mkdir(parents=True)
            previous = b'{"previous":true}\n'
            (publish_dir / "manifest.json").write_bytes(previous)
            malformed = root / "malformed.json"
            malformed.write_text("{broken", encoding="utf-8")
            sources = {
                "models-dev-models": FIXTURES / "models-dev-models.json",
                "models-dev-api": malformed,
                "litellm": FIXTURES / "litellm.json",
                "openrouter": FIXTURES / "openrouter.json",
            }
            result = self.run_builder(root, sources=sources)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual((publish_dir / "manifest.json").read_bytes(), previous)
            self.assertEqual(list(publish_dir.iterdir()), [publish_dir / "manifest.json"])

    def test_rejects_duplicate_identity_and_known_field_type_error(self) -> None:
        """捕获重复稳定身份或已知字段类型错误被静默接受的缺陷。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = json.loads((FIXTURES / "models-dev-models.json").read_text(encoding="utf-8"))
            source["duplicate-key"] = dict(source["acme/text-pro"])
            duplicate = root / "duplicate.json"
            duplicate.write_text(json.dumps(source), encoding="utf-8")
            sources = {
                "models-dev-models": duplicate,
                "models-dev-api": FIXTURES / "models-dev-api.json",
                "litellm": FIXTURES / "litellm.json",
                "openrouter": FIXTURES / "openrouter.json",
            }
            duplicate_result = self.run_builder(root / "duplicate-output", sources=sources)
            self.assertNotEqual(duplicate_result.returncode, 0)
            self.assertIn("重复记录身份", duplicate_result.stderr)

            source.pop("duplicate-key")
            source["acme/text-pro"]["reasoning"] = "yes"
            wrong_type = root / "wrong-type.json"
            wrong_type.write_text(json.dumps(source), encoding="utf-8")
            sources["models-dev-models"] = wrong_type
            type_result = self.run_builder(root / "type-output", sources=sources)
            self.assertNotEqual(type_result.returncode, 0)
            self.assertIn("reasoning", type_result.stderr)

    def test_rejects_source_larger_than_configured_strict_limit(self) -> None:
        """捕获下载器无界读取或超过上限后仍解析输入的缺陷。"""
        import importlib.util

        specification = importlib.util.spec_from_file_location("published_catalog_builder", SCRIPT)
        self.assertIsNotNone(specification)
        self.assertIsNotNone(specification.loader)
        module = importlib.util.module_from_spec(specification)
        sys.modules[specification.name] = module
        specification.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as tmp:
            oversized = Path(tmp) / "oversized.json"
            oversized.write_bytes(b"{" + b" " * 64 + b"}")
            with self.assertRaisesRegex(module.CatalogError, "大小上限"):
                module.read_source(str(oversized), max_bytes=32)

    def test_workload_uses_explicit_mode_or_output_not_text_input(self) -> None:
        """捕获把文生图、未知显式模式或只有文字输入的模型误标为聊天模型的缺陷。"""
        import importlib.util

        specification = importlib.util.spec_from_file_location("published_catalog_workload", SCRIPT)
        self.assertIsNotNone(specification)
        self.assertIsNotNone(specification.loader)
        module = importlib.util.module_from_spec(specification)
        sys.modules[specification.name] = module
        specification.loader.exec_module(module)
        self.assertEqual(module._workload({"input": ["text"], "output": ["image"]}), "IMAGE_GENERATION")
        self.assertEqual(module._workload({"input": ["text"], "output": ["text"]}, "decision"), "UNKNOWN")
        self.assertEqual(module._workload({"input": ["text"]}), "UNKNOWN")
        self.assertEqual(module._workload({"input": ["text"], "output": ["text"]}, "chat"), "GENERATIVE_TEXT")

    def test_default_official_overrides_add_only_verified_canonical_facts(self) -> None:
        """捕获官方补充遗漏、把输入额度冒充上下文或把 Sense provider 能力抬升为全局事实。"""
        with tempfile.TemporaryDirectory() as tmp:
            result = self.run_builder(Path(tmp), include_default_overrides=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            publish_dir = Path(tmp) / "model-catalog/v1"
            envelope = json.loads((publish_dir / "manifest.json").read_bytes())
            payload = json.loads(base64.b64decode(envelope["payload"], validate=True))
            catalog = json.loads((publish_dir / payload["catalogFile"]).read_bytes())
            source = next(item for item in catalog["sources"] if item["id"] == "infinity-official-overrides")
            self.assertEqual(source["sha256"], hashlib.sha256(OFFICIAL_OVERRIDES.read_bytes()).hexdigest())
            self.assertEqual(
                source["url"],
                "https://github.com/Promenar/Infinity-Deck/blob/main/scripts/model-catalog/official-model-overrides.json",
            )
            self.assertEqual(
                source["license"],
                "Google documentation: CC BY 4.0; SenseNova official API documentation: no repository license declared",
            )
            records = {
                item["canonicalModelId"]: item
                for item in catalog["records"]
                if item["source"] == "INFINITY_OVERRIDE"
            }
            self.assertEqual(set(records), {"google/gemini-3.8-flash", "sensenova/sensenova-6.8-flash-lite"})

            gemini = records["google/gemini-3.8-flash"]
            self.assertEqual(gemini["exactAliases"], ["gemini-3.8-flash"])
            self.assertNotIn("providerScope", gemini)
            self.assertNotIn("contextTokens", gemini)
            self.assertEqual(gemini["inputTokens"], 1_048_576)
            self.assertEqual(gemini["outputTokens"], 65_536)
            self.assertIs(gemini["reasoning"], True)
            self.assertIs(gemini["tool_call"], True)
            self.assertIs(gemini["structured_output"], True)
            self.assertEqual(gemini["last_updated"], "2026-09-02")

            sense = records["sensenova/sensenova-6.8-flash-lite"]
            self.assertEqual(sense["displayName"], "SenseNova 6.8 Flash Lite Preview")
            self.assertEqual(sense["exactAliases"], ["sensenova-6.8-flash-lite"])
            self.assertIs(sense["reasoning"], True)
            self.assertEqual(sense["status"], "preview")
            for unknown in ("contextTokens", "inputTokens", "outputTokens", "tool_call", "structured_output"):
                self.assertNotIn(unknown, sense)

    def test_rejects_unapproved_fields_in_official_overrides(self) -> None:
        """捕获受版本控制补充文件夹带 provider scope、命令或未知拼写后被静默发布的缺陷。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            malicious = root / "official-overrides.json"
            malicious.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "source": {
                            "id": "infinity-official-overrides",
                            "url": "https://example.invalid/override.json",
                            "license": "Factual citation",
                        },
                        "records": [
                            {
                                "canonicalModelId": "evil/model",
                                "displayName": "Evil",
                                "source": "INFINITY_OVERRIDE",
                                "providerScope": "evil",
                                "command": "ignored",
                                "evidenceUrl": "https://example.invalid/evidence",
                                "reviewedAt": "2026-09-22",
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            result = self.run_builder(root / "public", official_overrides=malicious)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("不允许字段", result.stderr)
            self.assertFalse((root / "public/model-catalog/v1/manifest.json").exists())

    def test_canonical_upstream_exact_id_retires_matching_curated_record(self) -> None:
        """捕获上游已接管后人工 Preview 记录仍以高优先级长期覆盖 canonical 事实的缺陷。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            canonical = json.loads((FIXTURES / "models-dev-models.json").read_text(encoding="utf-8"))
            canonical["google/gemini-3.8-flash"] = {
                "id": "google/gemini-3.8-flash",
                "name": "Upstream Gemini 3.8 Flash",
                "reasoning": True,
                "tool_call": True,
                "structured_output": True,
                "modalities": {"input": ["text", "image"], "output": ["text"]},
                "limit": {"input": 1048576, "output": 65536},
            }
            canonical_path = root / "models.json"
            canonical_path.write_text(json.dumps(canonical), encoding="utf-8")
            sources = {
                "models-dev-models": canonical_path,
                "models-dev-api": FIXTURES / "models-dev-api.json",
                "litellm": FIXTURES / "litellm.json",
                "openrouter": FIXTURES / "openrouter.json",
            }
            result = self.run_builder(root / "public", sources=sources, include_default_overrides=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            publish_dir = root / "public/model-catalog/v1"
            envelope = json.loads((publish_dir / "manifest.json").read_bytes())
            payload = json.loads(base64.b64decode(envelope["payload"], validate=True))
            catalog = json.loads((publish_dir / payload["catalogFile"]).read_bytes())
            matching = [
                record
                for record in catalog["records"]
                if record["canonicalModelId"] == "google/gemini-3.8-flash"
                and record.get("providerScope") is None
            ]
            self.assertEqual(len(matching), 1)
            self.assertEqual(matching[0]["source"], "MODELS_DEV")
            self.assertEqual(matching[0]["displayName"], "Upstream Gemini 3.8 Flash")
            curated_ids = {
                record["canonicalModelId"]
                for record in catalog["records"]
                if record["source"] == "INFINITY_OVERRIDE"
            }
            self.assertEqual(curated_ids, {"sensenova/sensenova-6.8-flash-lite"})


if __name__ == "__main__":
    unittest.main()
