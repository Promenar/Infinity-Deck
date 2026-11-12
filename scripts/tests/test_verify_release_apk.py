"""发行 APK 制品验证器的回归测试。"""

from __future__ import annotations

import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "verify-release-apk.py"
SPEC = importlib.util.spec_from_file_location("verify_release_apk", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
verify_release_apk = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify_release_apk)


class ApkFixtureTestCase(unittest.TestCase):
    def make_apk(self, entries: dict[str, bytes] | list[str]) -> Path:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        apk = Path(temporary_directory.name) / "fixture.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            if isinstance(entries, dict):
                for entry, content in entries.items():
                    archive.writestr(entry, content)
            else:
                for entry in entries:
                    archive.writestr(entry, b"fixture")
        return apk


class LocalInferenceArtifactVerificationTest(ApkFixtureTestCase):
    def test_rejects_local_inference_native_libraries_and_gguf_assets(self) -> None:
        forbidden_entries = (
            "lib/arm64-v8a/libinfinity_llama.so",
            "lib/x86_64/libllama.so",
            "lib/armeabi-v7a/libllama_android.so",
            "lib/arm64-v8a/libggml.so",
            "lib/x86/libggml-base.so",
            "assets/models/demo.GGUF",
        )

        for forbidden_entry in forbidden_entries:
            with self.subTest(entry=forbidden_entry):
                apk = self.make_apk(["AndroidManifest.xml", forbidden_entry])
                with self.assertRaisesRegex(
                    verify_release_apk.VerificationError,
                    "本地推理制品",
                ):
                    verify_release_apk.verify_local_inference_artifacts(apk, "play")

    def test_allows_ordinary_multi_abi_libraries_and_llama_text(self) -> None:
        apk = self.make_apk(
            [
                "lib/arm64-v8a/libcrypto.so",
                "lib/armeabi-v7a/libcrypto.so",
                "lib/x86/libcrypto.so",
                "lib/x86_64/libcrypto.so",
                "assets/docs/llama-release-notes.txt",
                "assets/model-catalog.json",
            ]
        )

        verify_release_apk.verify_local_inference_artifacts(apk, "play")

    def test_sideload_requires_a_bridge_for_each_abi_and_rejects_gguf(self) -> None:
        entries = [
            "lib/arm64-v8a/libinfinity_llama.so",
            "lib/x86_64/libinfinity_workspace_fs.so",
        ]
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "缺少本地推理制品"):
            verify_release_apk.verify_local_inference_artifacts(self.make_apk(entries), "sideload")
        entries.append("lib/x86_64/libinfinity_llama.so")
        verify_release_apk.verify_local_inference_artifacts(self.make_apk(entries), "sideload")
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "禁用的本地推理制品"):
            verify_release_apk.verify_local_inference_artifacts(self.make_apk(entries + ["assets/model.gguf"]), "sideload")

    def test_sherpa_only_exempts_the_verified_upstream_build_path(self) -> None:
        entry = "lib/arm64-v8a/libsherpa-onnx-jni.so"
        self.assertEqual([], verify_release_apk.scan_stream(io.BytesIO(b"/home/runner/work/"), entry))
        self.assertEqual([(entry, "Linux 用户绝对路径")],
                         verify_release_apk.scan_stream(io.BytesIO(b"/home/developer/source/"), entry))

    def test_recording_requires_complete_artifacts_and_fixed_model_bytes(self) -> None:
        native = [f"lib/arm64-v8a/{name}" for name in (
            "libonnxruntime.so", "libsherpa-onnx-jni.so", "libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so",
        )]
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "缺少离线听写制品"):
            verify_release_apk.verify_recording_artifacts(self.make_apk(native))
        assets = [f"assets/asr/{name}" for name in (
            "decoder-epoch-99-avg-1.onnx", "encoder-epoch-99-avg-1.int8.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt",
        )]
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "模型摘要不符"):
            verify_release_apk.verify_recording_artifacts(self.make_apk(native + assets))


class ZipAndSizeVerificationTest(ApkFixtureTestCase):
    def test_rejects_corrupt_zip(self) -> None:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        apk = Path(temporary_directory.name) / "broken.apk"
        apk.write_bytes(b"not-a-zip")

        with self.assertRaisesRegex(verify_release_apk.VerificationError, "有效 ZIP"):
            verify_release_apk.verify_zip(apk)

    def test_rejects_structurally_valid_zip_with_bad_crc_entry(self) -> None:
        apk = self.make_apk({"classes.dex": b"fixture-payload"})
        raw = apk.read_bytes()
        self.assertIn(b"fixture-payload", raw)
        apk.write_bytes(raw.replace(b"fixture-payload", b"fixture-payloaD", 1))

        with self.assertRaisesRegex(verify_release_apk.VerificationError, "ZIP 条目损坏"):
            verify_release_apk.verify_zip(apk)

    def test_rejects_empty_and_oversized_apk(self) -> None:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        apk = Path(temporary_directory.name) / "fixture.apk"
        apk.write_bytes(b"")
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "大小不合规"):
            verify_release_apk.verify_file_size(apk, 4)

        apk.write_bytes(b"12345")
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "大小不合规"):
            verify_release_apk.verify_file_size(apk, 4)

    def test_rejects_zip_bomb_layout_before_decompression(self) -> None:
        suspicious = mock.Mock(
            filename="assets/payload.bin",
            file_size=verify_release_apk.MAX_ZIP_ENTRY_UNCOMPRESSED_BYTES + 1,
            compress_size=1,
            is_dir=mock.Mock(return_value=False),
        )
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "解压规模"):
            verify_release_apk.verify_zip_entries([suspicious])


class BadgingVerificationTest(unittest.TestCase):
    def test_accepts_exact_package_identity(self) -> None:
        output = "package: name='com.promenar.infinity' versionCode='2' versionName='0.2-beta'\n"
        self.assertEqual(
            verify_release_apk.parse_badging(output),
            {
                "name": "com.promenar.infinity",
                "versionCode": "2",
                "versionName": "0.2-beta",
            },
        )

    def test_ignores_android_16_badging_metadata_field_suffixes(self) -> None:
        output = (
            "package: name='com.promenar.infinity' versionCode='2' "
            "versionName='0.2-beta' platformBuildVersionName='16' "
            "platformBuildVersionCode='36' compileSdkVersion='36' "
            "compileSdkVersionCodename='16'\n"
        )

        self.assertEqual(
            verify_release_apk.parse_badging(output),
            {
                "name": "com.promenar.infinity",
                "versionCode": "2",
                "versionName": "0.2-beta",
            },
        )

    def test_rejects_missing_or_duplicate_badging_fields(self) -> None:
        malformed = (
            "package: name='com.promenar.infinity' "
            "versionCode='2' versionCode='3' versionName='0.2-beta'\n"
        )
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "badging"):
            verify_release_apk.parse_badging(malformed)

        with self.assertRaisesRegex(verify_release_apk.VerificationError, "badging"):
            verify_release_apk.parse_badging("application-label:'Infinity Deck'\n")

        duplicated_package_lines = (
            "package: name='com.promenar.infinity' versionCode='2' "
            "versionName='0.2-beta'\n"
            "package: name='com.promenar.infinity' versionCode='2' "
            "versionName='0.2-beta'\n"
        )
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "badging"):
            verify_release_apk.parse_badging(duplicated_package_lines)

    @mock.patch.object(verify_release_apk, "find_android_tool", return_value=Path("/sdk/aapt"))
    @mock.patch.object(verify_release_apk, "run_tool", return_value="garbled")
    def test_badging_output_drift_fails_closed(self, _run: mock.Mock, _find: mock.Mock) -> None:
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "badging"):
            verify_release_apk.verify_badging(Path("app.apk"), "pkg", "2", "beta")

    @mock.patch.object(verify_release_apk, "find_android_tool", return_value=Path("/sdk/aapt"))
    @mock.patch.object(
        verify_release_apk,
        "run_tool",
        return_value="package: name='pkg' versionCode='3' versionName='beta'\n",
    )
    def test_rejects_wrong_version_identity(self, _run: mock.Mock, _find: mock.Mock) -> None:
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "版本不匹配"):
            verify_release_apk.verify_badging(Path("app.apk"), "pkg", "2", "beta")


class SignatureVerificationTest(unittest.TestCase):
    DIGEST = "ab" * 32

    def test_normalizes_colon_delimited_uppercase_fingerprint(self) -> None:
        delimited = ":".join(self.DIGEST.upper()[i : i + 2] for i in range(0, 64, 2))
        self.assertEqual(verify_release_apk.normalize_digest(delimited), self.DIGEST)

    def test_rejects_invalid_expected_fingerprint_before_invoking_tool(self) -> None:
        with mock.patch.object(verify_release_apk, "run_tool") as run:
            with self.assertRaisesRegex(verify_release_apk.VerificationError, "预期签名"):
                verify_release_apk.verify_signature(Path("app.apk"), "not-a-digest")
            run.assert_not_called()

    @mock.patch.object(verify_release_apk, "find_android_tool", return_value=Path("/sdk/apksigner"))
    @mock.patch.object(verify_release_apk, "run_tool")
    def test_accepts_build_tools_37_signature_output(
        self,
        run: mock.Mock,
        _find: mock.Mock,
    ) -> None:
        run.return_value = (
            "Verifies\n"
            "Number of signers: 1\n"
            f"V2 Signer: certificate SHA-256 digest: {self.DIGEST}\n"
        )

        verify_release_apk.verify_signature(Path("app.apk"), self.DIGEST)

    @mock.patch.object(verify_release_apk, "find_android_tool", return_value=Path("/sdk/apksigner"))
    @mock.patch.object(verify_release_apk, "run_tool")
    def test_rejects_build_tools_37_output_without_declared_signer_count(
        self,
        run: mock.Mock,
        _find: mock.Mock,
    ) -> None:
        run.return_value = f"V2 Signer: certificate SHA-256 digest: {self.DIGEST}\n"

        with self.assertRaisesRegex(verify_release_apk.VerificationError, "单一 signer"):
            verify_release_apk.verify_signature(Path("app.apk"), self.DIGEST)

    @mock.patch.object(verify_release_apk, "find_android_tool", return_value=Path("/sdk/apksigner"))
    @mock.patch.object(verify_release_apk, "run_tool")
    def test_rejects_wrong_certificate_and_multiple_signers(
        self,
        run: mock.Mock,
        _find: mock.Mock,
    ) -> None:
        run.return_value = f"Signer #1 certificate SHA-256 digest: {'cd' * 32}\n"
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "不一致"):
            verify_release_apk.verify_signature(Path("app.apk"), self.DIGEST)

        run.return_value = (
            f"Signer #1 certificate SHA-256 digest: {self.DIGEST}\n"
            f"Signer #2 certificate SHA-256 digest: {'cd' * 32}\n"
        )
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "单一 signer"):
            verify_release_apk.verify_signature(Path("app.apk"), self.DIGEST)

    @mock.patch.object(verify_release_apk, "find_android_tool", return_value=Path("/sdk/apksigner"))
    @mock.patch.object(verify_release_apk, "run_tool", return_value="Verified")
    def test_rejects_apksigner_output_without_certificate_digest(
        self,
        _run: mock.Mock,
        _find: mock.Mock,
    ) -> None:
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "未返回签名证书"):
            verify_release_apk.verify_signature(Path("app.apk"), self.DIGEST)

    @mock.patch.object(verify_release_apk.subprocess, "run")
    def test_tool_failure_is_redacted_and_fails_closed(self, run: mock.Mock) -> None:
        run.return_value = subprocess.CompletedProcess(["tool"], 7, stdout="secret output")
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "退出码 7") as raised:
            verify_release_apk.run_tool(["/sdk/tool", "argument"])
        self.assertNotIn("secret output", str(raised.exception))


class SensitiveContentVerificationTest(ApkFixtureTestCase):
    def test_rejects_each_sensitive_fixture_without_embedding_real_secrets(self) -> None:
        fixtures = {
            "Authorization Bearer": b"Authorization: Bearer token_fixture_0123456789",
            "private key": (
                b"-----BEGIN PRIVATE KEY-----\n"
                + b"A" * 96
                + b"\n-----END PRIVATE KEY-----"
            ),
            "encrypted private key": (
                b"-----BEGIN ENCRYPTED PRIVATE KEY-----\n"
                + b"B" * 96
                + b"\n-----END ENCRYPTED PRIVATE KEY-----"
            ),
            "OpenAI-style key": b"sk-fixture_0123456789_ABCDEFGH",
            "Google-style key": b"AIza" + b"F" * 35,
            "prompt fixture": b"INFINITY_PROMPT_FIXTURE=fixture-user-prompt",
            "response fixture": b"INFINITY_RESPONSE_FIXTURE=fixture-model-response",
            "WebDAV password": b"WEBDAV_PASSWORD_FIXTURE=fixture-password",
            "WebDAV plaintext password": b'webdav_password="fixture-password-0123"',
            "WebDAV URL": b"INFINITY_WEBDAV_TEST_URL=https://example.invalid/webdav-test",
            "absolute path": b"/Users/fixture-user/Projects/Infinity Deck",
            "secure env": b"config/secure_env/release.properties",
        }
        for label, value in fixtures.items():
            with self.subTest(label=label):
                apk = self.make_apk({"classes.dex": value})
                with self.assertRaisesRegex(verify_release_apk.VerificationError, "敏感信息"):
                    verify_release_apk.scan_sensitive_content(apk)

    def test_detects_sensitive_value_crossing_read_chunks(self) -> None:
        marker = b"Authorization: Bearer token_fixture_0123456789"
        prefix = b"x" * 13
        findings = verify_release_apk.scan_stream(
            io.BytesIO(prefix + marker),
            "classes.dex",
            chunk_bytes=17,
        )
        self.assertTrue(any(label == "Authorization Bearer 凭证" for _, label in findings))

    def test_does_not_create_artificial_match_across_zip_entries(self) -> None:
        apk = self.make_apk(
            {
                "assets/part-a.txt": b"Authorization: Bearer ",
                "assets/part-b.txt": b"token_fixture_0123456789",
            }
        )
        verify_release_apk.scan_sensitive_content(apk)

    def test_allows_ordinary_library_names_and_non_secret_ui_words(self) -> None:
        apk = self.make_apk(
            {
                "lib/arm64-v8a/libcrypto.so": b"Bearer token help and prompt response UI labels",
                "assets/providers.json": (
                    b'{"provider":"openai","endpointConfigured":false,'
                    b'"pemParserHeader":"-----BEGIN PRIVATE KEY-----"}'
                ),
            }
        )
        verify_release_apk.scan_sensitive_content(apk)

    def test_allows_linux_session_guest_path_but_rejects_other_home_paths(self) -> None:
        verify_release_apk.scan_sensitive_content(
            self.make_apk({"classes.dex": b"/home/session/canvas/a.html /workspace/a"})
        )
        with self.assertRaises(verify_release_apk.VerificationError):
            verify_release_apk.scan_sensitive_content(
                self.make_apk({"classes.dex": b"/home/session/a /home/fixture-user/src"})
            )


class PackagedPythonUpstreamContentTest(ApkFixtureTestCase):
    """官方 CPython 包自带构建机路径与地址常量；豁免只覆盖这些上游值与对应条目。"""

    upstream = {
        "lib/arm64-v8a/libpystdlib.so": (
            b"ipaddress 10.0.0.0/8 100.64.0.0/10 172.16.0.0/12 192.168.0.1 "
            b"/Users/runner/work/cpython /home/runner/work/cpython "
            b"venv/scripts/common/Activate.ps1 C:\\Users\\"
        ),
        "lib/x86_64/libpyext__zstd.so": b"/home/runner/work/zstd/lib",
        "lib/x86_64/libpython3.14.so": b"/Users/runner/work/cpython",
        "lib/arm64-v8a/libssl_python.so": b"/home/runner/openssl",
        "lib/arm64-v8a/libpysite.so": b"urllib3 url.py https://username:password@host.com:80/path",
    }

    def test_allows_upstream_build_paths_and_stdlib_address_constants(self) -> None:
        verify_release_apk.scan_sensitive_content(self.make_apk(self.upstream))

    def test_still_rejects_local_paths_credentials_and_out_of_scope_entries(self) -> None:
        cases = {
            "本机用户路径": {"lib/arm64-v8a/libpystdlib.so": b"/Users/fixture-user/Projects"},
            "凭据": {"lib/arm64-v8a/libpystdlib.so": b"sk-fixture_0123456789_ABCDEFGH"},
            "扩展中的私网地址": {"lib/arm64-v8a/libpyext__socket.so": b"192.168.0.1"},
            "扩展中的 Windows 路径": {"lib/arm64-v8a/libpyext__socket.so": b"C:\\Users\\"},
            "自编译启动器": {"lib/arm64-v8a/libnxpython.so": b"/home/runner/work"},
            "应用代码": {"classes.dex": b"/home/runner/work"},
            "伪装文件名": {"assets/libpystdlib.so": b"192.168.0.1"},
            "站点包中的真实口令 URL": {"lib/arm64-v8a/libpysite.so": b"https://deploy:s3cretValue@example.com/"},
            "站点包中的私网地址": {"lib/arm64-v8a/libpysite.so": b"192.168.0.1"},
            "站点包中的构建机路径": {"lib/arm64-v8a/libpysite.so": b"/home/runner/work"},
        }
        for label, entries in cases.items():
            with self.subTest(label=label):
                with self.assertRaisesRegex(verify_release_apk.VerificationError, "敏感信息"):
                    verify_release_apk.scan_sensitive_content(self.make_apk(entries))

    def test_upstream_allowance_does_not_hide_local_path_in_same_entry(self) -> None:
        entries = {"lib/x86_64/libpython3.14.so": b"/home/runner/work /home/fixture-user/src"}
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "Linux 用户绝对路径"):
            verify_release_apk.scan_sensitive_content(self.make_apk(entries))


def packaged_zip(entries: dict[str, bytes]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_STORED) as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return buffer.getvalue()


def pyc(magic: bytes = b"\x2b\x0e\r\n", flags: int = 0b01) -> bytes:
    return magic + flags.to_bytes(4, "little") + b"\x00" * 8 + b"code"


class PackagedPythonBytecodeTest(ApkFixtureTestCase):
    def python_entries(self, abi: str = "arm64-v8a", **overrides: bytes) -> dict[str, bytes]:
        stdlib = {name: pyc() for name in verify_release_apk.PYTHON_BYTECODE_MODULES["libpystdlib.so"]}
        site = {name: pyc() for name in verify_release_apk.PYTHON_BYTECODE_MODULES["libpysite.so"]}
        site["dulwich/repo.py"] = b"source"
        for name, content in overrides.items():
            target = site if name.startswith("site:") else stdlib
            target[name.split(":", 1)[1]] = content
        return {
            f"lib/{abi}/libpystdlib.so": packaged_zip(stdlib),
            f"lib/{abi}/libpysite.so": packaged_zip(site),
        }

    def test_accepts_unchecked_hash_bytecode_for_key_modules(self) -> None:
        verify_release_apk.verify_packaged_python_bytecode(self.make_apk(self.python_entries()))

    def test_rejects_python_zips_without_bytecode(self) -> None:
        entries = {
            "lib/arm64-v8a/libpystdlib.so": packaged_zip({"os.py": b"source"}),
            "lib/arm64-v8a/libpysite.so": packaged_zip({"infinity_git.py": b"source"}),
        }
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "缺少 os.pyc"):
            verify_release_apk.verify_packaged_python_bytecode(self.make_apk(entries))

    def test_rejects_checked_or_timestamp_pyc_and_mixed_magic(self) -> None:
        cases = {
            "不核对源码": self.python_entries(**{"std:os.pyc": pyc(flags=0b11)}),
            "不核对源码 ": self.python_entries(**{"site:infinity_git.pyc": pyc(flags=0)}),
            "magic 不一致": self.python_entries(**{"site:dulwich/repo.pyc": pyc(magic=b"\x00\x0e\r\n")}),
        }
        for message, entries in cases.items():
            with self.subTest(message=message):
                with self.assertRaisesRegex(verify_release_apk.VerificationError, message.strip()):
                    verify_release_apk.verify_packaged_python_bytecode(self.make_apk(entries))

    def test_rejects_missing_python_or_half_packaged_abi(self) -> None:
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "未包含打包 Python"):
            verify_release_apk.verify_packaged_python_bytecode(self.make_apk(["AndroidManifest.xml"]))
        entries = self.python_entries()
        del entries["lib/arm64-v8a/libpysite.so"]
        with self.assertRaisesRegex(verify_release_apk.VerificationError, "arm64-v8a 缺少 libpysite.so"):
            verify_release_apk.verify_packaged_python_bytecode(self.make_apk(entries))


def elf(machine: int) -> bytes:
    return b"\x7fELF\x02\x01\x01" + b"\x00" * 9 + (2).to_bytes(2, "little") + machine.to_bytes(2, "little") + b"payload"


class TailnetBinaryTest(ApkFixtureTestCase):
    """内置 Tailscale（Go 程序）：每个打包 Python 的 ABI 都须带对应架构的 libnxtailnet.so；豁免只覆盖其中的上游常量。"""

    def test_accepts_matching_binary_for_each_python_abi(self) -> None:
        entries = {
            "lib/arm64-v8a/libpystdlib.so": b"zip",
            "lib/arm64-v8a/libnxtailnet.so": elf(183),
            "lib/x86_64/libpysite.so": b"zip",
            "lib/x86_64/libnxtailnet.so": elf(62),
        }
        verify_release_apk.verify_tailnet_binary(self.make_apk(entries))

    def test_rejects_missing_wrong_architecture_or_non_elf_binary(self) -> None:
        cases = {
            "未包含 libnxtailnet.so": {"lib/arm64-v8a/libpystdlib.so": b"zip"},
            "x86_64 缺少 libnxtailnet.so": {
                "lib/arm64-v8a/libpystdlib.so": b"zip",
                "lib/arm64-v8a/libnxtailnet.so": elf(183),
                "lib/x86_64/libpystdlib.so": b"zip",
            },
            "架构与 ABI 不符": {"lib/arm64-v8a/libnxtailnet.so": elf(62)},
            "不是 ELF": {"lib/arm64-v8a/libnxtailnet.so": b"#!/bin/sh\necho fake tailnet"},
            "未知 ABI": {"lib/armeabi-v7a/libnxtailnet.so": elf(40)},
        }
        for message, entries in cases.items():
            with self.subTest(message=message):
                expected = "架构与 ABI 不符" if message == "未知 ABI" else message
                with self.assertRaisesRegex(verify_release_apk.VerificationError, expected):
                    verify_release_apk.verify_tailnet_binary(self.make_apk(entries))

    def test_allows_go_symbol_runs_and_reserved_ranges_only_in_tailnet_binary(self) -> None:
        content = (
            b"sk-configip-forwarding-offavailable-versionunknown 10.0.0.0 100.64.0.0 172.16.0.0 192.168.0.0 "
            b"100.115.92.0 100.100.100.100 10.076.76.10 10.276.76.10"
        )
        verify_release_apk.scan_sensitive_content(self.make_apk({"lib/arm64-v8a/libnxtailnet.so": content}))
        cases = {
            "真实密钥形态": {"lib/arm64-v8a/libnxtailnet.so": b"sk-proj-AbCdEf0123456789xyzQ"},
            "其它私网地址": {"lib/arm64-v8a/libnxtailnet.so": b"192.168.1.20"},
            "其它 CGNAT 地址": {"lib/arm64-v8a/libnxtailnet.so": b"100.64.12.7"},
            "口令 URL": {"lib/arm64-v8a/libnxtailnet.so": b"https://deploy:s3cretValue@example.com/"},
            "构建机路径": {"lib/arm64-v8a/libnxtailnet.so": b"/Users/fixture-user/go/pkg/mod"},
            "其它条目": {"lib/arm64-v8a/libother.so": b"sk-configip-forwarding-offavailable"},
            "伪装文件名": {"assets/libnxtailnet.so": b"100.64.0.0"},
        }
        for label, entries in cases.items():
            with self.subTest(label=label):
                with self.assertRaisesRegex(verify_release_apk.VerificationError, "敏感信息"):
                    verify_release_apk.scan_sensitive_content(self.make_apk(entries))


    def test_allows_only_lab_tailnet_subnet_in_app_dex(self) -> None:
        content = b"\x0c100.101.7.12\x00\x0b100.101.7.5\x00"
        verify_release_apk.scan_sensitive_content(self.make_apk({"classes.dex": content, "classes2.dex": content}))
        cases = {
            "其它 CGNAT 地址": {"classes.dex": b"100.101.8.12"},
            "私网地址": {"classes2.dex": b"192.168.1.20"},
            "非 dex 条目": {"assets/lab.json": b"100.101.7.12"},
            "伪装 dex": {"assets/classes.dex": b"100.101.7.12"},
        }
        for label, entries in cases.items():
            with self.subTest(label=label):
                with self.assertRaisesRegex(verify_release_apk.VerificationError, "敏感信息"):
                    verify_release_apk.scan_sensitive_content(self.make_apk(entries))


class ChecksumVerificationTest(ApkFixtureTestCase):
    def test_writes_standard_checksum_with_apk_basename(self) -> None:
        apk = self.make_apk({"AndroidManifest.xml": b"manifest"})
        output = apk.with_name("infinity.apk.sha256")
        digest = verify_release_apk.write_checksum(apk, output)

        self.assertEqual(digest, verify_release_apk.sha256_file(apk))
        self.assertEqual(output.read_text(encoding="utf-8"), f"{digest}  {apk.name}\n")


if __name__ == "__main__":
    unittest.main()
