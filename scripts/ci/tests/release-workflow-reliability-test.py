#!/usr/bin/env python3
"""发行 workflow 可靠性纯文本契约测试。"""

from __future__ import annotations

import os
import json
import re
import shlex
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
ANDROID_CI = (ROOT / ".github/workflows/android-ci.yml").read_text(encoding="utf-8")
RELEASE = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
STATIC_CHECKS = (ROOT / "scripts/ci/android-static-checks.sh").read_text(encoding="utf-8")
SMOKE = (ROOT / "scripts/ci/android-release-apk-smoke.sh").read_text(encoding="utf-8")
UPGRADE_SMOKE = (ROOT / "scripts/ci/android-release-upgrade-smoke.sh").read_text(encoding="utf-8")
APP_BUILD = (ROOT / "native-ui/app/build.gradle.kts").read_text(encoding="utf-8")
METADATA = str((ROOT / "scripts/ci/release-metadata.py"))


def braced_block_after(source: str, marker: str) -> str:
    marker_index = source.index(marker)
    start = source.index("{", marker_index)
    depth = 0
    for index in range(start, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    raise AssertionError(f"unclosed block after {marker}")


def workflow_job(name: str, source: str = RELEASE) -> str:
    match = re.search(
        rf"^  {re.escape(name)}:\n(?P<body>.*?)(?=^  [a-z][a-z0-9-]*:\n|\Z)",
        source,
        flags=re.MULTILINE | re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"missing workflow job: {name}")
    return match.group("body")


def job_level_value(job: str, key: str) -> str | None:
    match = re.search(rf"^    {re.escape(key)}:\s*(.+)$", job, flags=re.MULTILINE)
    return match.group(1).strip() if match else None


def continued_shell_commands(script: str, executable: str) -> list[list[str]]:
    commands: list[list[str]] = []
    current: list[str] | None = None
    for line in script.splitlines():
        stripped = line.strip()
        if current is None:
            if re.match(rf"{re.escape(executable)}(?:\s|$)", stripped) is None:
                continue
            current = [stripped.removesuffix("\\").rstrip()]
        else:
            current.append(stripped.removesuffix("\\").rstrip())
        if not stripped.endswith("\\"):
            commands.append(shlex.split(" ".join(current)))
            current = None
    if current is not None:
        raise AssertionError(f"unterminated shell command: {executable}")
    return commands


class ReleaseWorkflowReliabilityTest(unittest.TestCase):
    def test_jvm_diagnostic_is_manual_unsigned_and_passes_filter_as_data(self) -> None:
        source = (ROOT / ".github/workflows/jvm-test-diagnostics.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", source)
        self.assertIn("contents: read", source)
        self.assertNotIn("secrets.", source)
        self.assertIn("TEST_FILTER: ${{ inputs.test_filter }}", source)
        self.assertIn('--tests "$TEST_FILTER"', source)
        self.assertNotIn("--tests ${{ inputs.", source)
        self.assertIn("test-results/testSideloadDebugUnitTest", source)
        self.assertIn("if: always()", source)

    def test_local_gate_documents_list_both_distribution_unit_tests(self) -> None:
        # 发行流水线不跑单测，两个渠道的 JVM 单测只由本机门禁覆盖；门禁清单不能再次只列侧载。
        agents_gate = next(
            line for line in (ROOT / "AGENTS.md").read_text(encoding="utf-8").splitlines() if "日常使用" in line
        )
        distribution_gate = next(
            line
            for line in (ROOT / "docs/distribution-builds.md").read_text(encoding="utf-8").splitlines()
            if "本机必跑" in line
        )
        for gate in (agents_gate, distribution_gate):
            self.assertIn(":app:testSideloadDebugUnitTest", gate)
            self.assertIn(":app:testPlayDebugUnitTest", gate)

    def test_jvm_unit_tests_disable_robolectric_conscrypt(self) -> None:
        # Robolectric 默认在非 Apple Silicon 主机把 Conscrypt 插到全局提供者首位，会让 Linux 与本机的 TLS 测试行为分叉。
        test_block = braced_block_after(APP_BUILD, "tasks.withType<Test> {")
        self.assertIn('systemProperty("robolectric.conscryptMode", "OFF")', test_block)

    def test_gradle_memory_diagnostics_is_manual_unsigned_and_bounded(self) -> None:
        source = (ROOT / ".github/workflows/gradle-memory-diagnostics.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", source)
        self.assertNotIn("secrets.", source)
        self.assertIn("contents: read", source)
        self.assertIn("combined", source)
        self.assertIn("staged", source)
        self.assertIn("timeout-minutes:", source)
        self.assertIn("GC.heap_info", source)
        self.assertIn("VM.flags", source)
        self.assertIn(":app:compileSideloadReleaseKotlin", source)
        self.assertIn(":app:compilePlayReleaseKotlin", source)
        self.assertIn("if: always()", source)
        self.assertNotIn("--no-daemon", source)
        self.assertNotIn("--no-parallel", source)
        self.assertNotIn("--no-build-cache", source)

    def test_gradle_memory_staged_compile_profile_keeps_all_four_variants(self) -> None:
        source = (ROOT / ".github/workflows/gradle-memory-diagnostics.yml").read_text(encoding="utf-8")
        self.assertIn("inputs.profile == 'staged-compile'", source)
        block = source.split('elif [[ "$MODE" == staged-compile ]]; then', 1)[1].split("          else", 1)[0]
        commands = [line.strip() for line in block.splitlines() if line.strip().startswith("./gradlew ")]
        self.assertEqual(len(commands), 4)
        for variant in ("SideloadDebug", "PlayDebug", "SideloadRelease", "PlayRelease"):
            self.assertEqual(sum(f":app:compile{variant}Kotlin" in line for line in commands), 1)

    def test_android_ci_builds_reuse_gradle_daemon_and_cache(self) -> None:
        paths = (
            ".github/workflows/release.yml",
            ".github/workflows/device-regression.yml",
            ".github/workflows/screenshot-validate.yml",
            ".github/workflows/screenshot-diagnostics.yml",
            ".github/workflows/screenshot-reference-candidate.yml",
            "scripts/ci/android-device-core-e2e.sh",
            "scripts/ci/android-minified-blackbox-smoke.sh",
        )
        for path in paths:
            source = (ROOT / path).read_text(encoding="utf-8")
            with self.subTest(path=path):
                self.assertNotIn("--no-daemon", source)
                self.assertNotIn("--no-build-cache", source)

    def test_screenshot_shards_cover_sources_once_including_new_tests(self) -> None:
        script = ROOT / "scripts/ci/screenshot-shards.py"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "native-ui/app/src/screenshotTest/kotlin"
            source.mkdir(parents=True)
            for name in ("Alpha", "Beta", "New"):
                (source / f"{name}ScreenshotTest.kt").write_text("@PreviewTest\n", encoding="utf-8")
            reference = root / "native-ui/app/src/screenshotTestDebug/reference/com/example/AlphaScreenshotTestKt"
            reference.mkdir(parents=True)
            for index in range(5):
                (reference / f"{index}.png").touch()
            shards = [json.loads(subprocess.check_output(
                [sys.executable, str(script), "--root", str(root), "--shard", str(index), "--count", "2"],
                text=True,
            )) for index in range(2)]
            all_classes = [name for shard in shards for name in shard]
            self.assertCountEqual(all_classes, ["AlphaScreenshotTestKt", "BetaScreenshotTestKt", "NewScreenshotTestKt"])
            self.assertEqual(len(all_classes), len(set(all_classes)))
            self.assertIn(["AlphaScreenshotTestKt"], shards)

    def test_linux_reference_candidate_is_manual_and_evidence_only(self) -> None:
        source = (ROOT / ".github/workflows/screenshot-reference-candidate.yml").read_text(encoding="utf-8")
        trigger = source.split("permissions:", 1)[0]
        self.assertIn("  workflow_dispatch:", trigger)
        self.assertNotIn("  push:", trigger)
        self.assertIn("contents: read", source)
        self.assertNotIn("secrets.", source)
        self.assertNotIn("git push", source)
        self.assertIn(":app:updateSideloadDebugScreenshotTest", source)
        self.assertIn("baseline-before", source)
        self.assertIn("app/src/screenshotTestDebug/reference", source)
        self.assertIn("fail-fast: false", source)
        self.assertIn('args+=(--tests "*$class*")', source)
        self.assertIn("generation-outcome.txt", source)
        self.assertIn("if: always()", source)

    def test_screenshot_diagnostics_is_manual_read_only_and_preserves_failure_evidence(self) -> None:
        workflow = ROOT / ".github/workflows/screenshot-diagnostics.yml"
        self.assertTrue(workflow.is_file(), "截图诊断入口必须存在")
        source = workflow.read_text(encoding="utf-8")
        trigger = source.split("permissions:", 1)[0]
        self.assertIn("  workflow_dispatch:", trigger)
        self.assertNotIn("  push:", trigger)
        self.assertNotIn("  pull_request:", trigger)
        self.assertIn("contents: read", source)
        self.assertNotIn("secrets.", source)
        self.assertNotIn("environment: release", source)
        self.assertNotIn("--no-build-cache", source)
        self.assertNotIn("--no-daemon", source)
        self.assertIn(":app:validateSideloadDebugScreenshotTest", source)
        self.assertIn("preview_filter:", source)
        self.assertIn("default: '*emptyChatReleasePreview*'", source)
        self.assertIn("PREVIEW_FILTER: ${{ inputs.preview_filter }}", source)
        self.assertIn('--tests "$PREVIEW_FILTER"', source)
        self.assertNotIn("--tests ${{ inputs.", source)
        self.assertNotIn("updateSideloadDebugScreenshotTest", source)
        self.assertIn("if: always()", source)
        self.assertIn("native-ui/app/build/reports/screenshotTest/preview/debug/sideload/", source)
        self.assertIn("native-ui/app/build/outputs/screenshotTest-results/preview/debug/sideload/", source)

    def test_static_checks_gate_pr_and_release_build(self) -> None:
        self.assertIn("bash scripts/ci/android-static-checks.sh", ANDROID_CI)
        static_job = workflow_job("static-checks")
        self.assertIn("bash scripts/ci/android-static-checks.sh", static_job)
        self.assertIn("static-checks", job_level_value(workflow_job("build-release"), "needs"))
        self.assertNotIn("./gradlew", ANDROID_CI)
        self.assertNotIn("android-emulator-runner@", ANDROID_CI)
        for check in ("update-model-catalog.py", "check-text-wrapping.py", "check-distribution-manifests-test.py",
                      "check-native-page-alignment-test.py", "release-workflow-reliability-test.py",
                      "android-device-core-e2e-contract-test.sh", "android-minified-blackbox-contract-test.sh"):
            self.assertIn(check, STATIC_CHECKS)
        self.assertIn("set -euo pipefail", STATIC_CHECKS)
        self.assertNotIn("continue-on-error: true", ANDROID_CI)

    def test_static_checks_stop_when_first_check_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            stub = Path(directory) / "python3"
            stub.write_text("#!/bin/sh\nprintf 'fixture\\n'\nexit 42\n", encoding="utf-8")
            stub.chmod(0o755)
            result = subprocess.run(
                ["bash", str(ROOT / "scripts/ci/android-static-checks.sh")],
                env={**os.environ, "PATH": f"{directory}{os.pathsep}{os.environ['PATH']}"},
                capture_output=True, text=True, check=False,
            )
        self.assertEqual(result.returncode, 42)
        self.assertEqual(result.stdout.splitlines(), ["fixture"])

    def test_android_ci_runs_only_for_pull_requests_and_manual_dispatch(self) -> None:
        trigger = ANDROID_CI.split("\npermissions:", 1)[0]
        self.assertIn("  pull_request:\n", trigger)
        self.assertIn("  workflow_dispatch:\n", trigger)
        self.assertNotIn("  push:", trigger)

    def test_release_triggers_on_version_tags_and_manual_candidate(self) -> None:
        self.assertRegex(
            RELEASE,
            r'(?m)^on:\n  workflow_dispatch:\n  push:\n    tags:\n      - "v\*"$',
        )
        self.assertIn("group: release-${{ github.ref_name }}", RELEASE)
        self.assertNotRegex(RELEASE, r"\d+\.\d+\.\d+-beta", "发行工作流不得写死版本号")

    def test_release_identity_comes_from_android_source_through_job_outputs(self) -> None:
        validate_job = workflow_job("validate-release-inputs")
        self.assertIn("python3 scripts/ci/release-metadata.py", validate_job)
        self.assertIn('--github-output "${GITHUB_OUTPUT}"', validate_job)
        for key in ("tag", "version-name", "version-code", "prerelease"):
            self.assertIn(f"{key}: ${{{{ steps.metadata.outputs.{key} }}}}", validate_job)
        build = workflow_job("build-release")
        self.assertIn('--expected-version-code "${VERSION_CODE}"', build)
        self.assertIn('--expected-version-name "${VERSION_NAME}"', build)
        self.assertIn("VERSION_CODE: ${{ needs.validate-release-inputs.outputs.version-code }}", build)
        smoke = workflow_job("signed-apk-smoke")
        self.assertIn("INFINITY_EXPECTED_VERSION_CODE: ${{ needs.validate-release-inputs.outputs.version-code }}", smoke)
        self.assertIn("INFINITY_EXPECTED_VERSION_NAME: ${{ needs.validate-release-inputs.outputs.version-name }}", smoke)
        for job_name in ("build-release", "signed-apk-smoke", "publish"):
            with self.subTest(job=job_name):
                self.assertIn("validate-release-inputs", job_level_value(workflow_job(job_name), "needs"))

    def test_release_builds_and_publishes_only_the_sideload_apk(self) -> None:
        build = workflow_job("build-release")
        publish = workflow_job("publish")
        smoke = workflow_job("signed-apk-smoke")
        self.assertIn("outputs/apk/sideload/release/app-sideload-release.apk", build)
        self.assertIn('apk="dist/infinity-${RELEASE_TAG}.apk"', build)
        self.assertIn('"dist/infinity-${tag}.apk"', publish)
        self.assertIn('"dist/infinity-${tag}.apk.sha256"', publish)
        self.assertIn('distribution: sideload\n            apk-suffix: ""', smoke)
        for job in (build, publish, smoke):
            self.assertNotRegex(job, r"(?i)play")
        self.assertIn("APK_NAME: infinity-${{ needs.validate-release-inputs.outputs.tag }}${{ matrix.apk-suffix }}.apk", smoke)
        self.assertIn('scripts/ci/android-release-apk-smoke.sh "dist/${APK_NAME}"', smoke)
        self.assertIn('INFINITY_EXPECTED_DISTRIBUTION: ${{ matrix.distribution }}', smoke)
        self.assertIn('--distribution "${distribution}"', build)
        self.assertIn('check-native-page-alignment.py --build-type release', build)

    def test_signing_reads_existing_release_environment_secrets(self) -> None:
        build = workflow_job("build-release")
        self.assertEqual(job_level_value(build, "environment"), "release")
        for name in ("KEYSTORE_BASE64", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"):
            self.assertIn(f"INFINITY_{name}: ${{{{ secrets.NEXARA_{name} }}}}", build)
        self.assertNotIn("secrets.INFINITY_", RELEASE)
        self.assertNotIn("vars.INFINITY_", RELEASE)
        self.assertIn("INFINITY_SIGNING_CERT_SHA256: ${{ vars.NEXARA_SIGNING_CERT_SHA256 }}", workflow_job("signed-apk-smoke"))
        for name in ("INFINITY_KEYSTORE_PATH", "INFINITY_STORE_PASSWORD", "INFINITY_KEY_ALIAS", "INFINITY_KEY_PASSWORD"):
            self.assertIn(f'providers.environmentVariable("{name}")', APP_BUILD)

    def test_signed_apk_smoke_defaults_match_source_and_allow_explicit_override(self) -> None:
        version_name = re.search(r'versionName\s*=\s*"([^"\n]+)"', APP_BUILD).group(1)
        version_code = re.search(r'versionCode\s*=\s*(\d+)', APP_BUILD).group(1)
        assignments = "\n".join(
            re.findall(r"^(?:APP_BUILD_FILE|EXPECTED_VERSION_(?:CODE|NAME))=.*$", SMOKE, flags=re.MULTILINE)
        )
        self.assertEqual(len(assignments.splitlines()), 3)
        shell = assignments + '\nprintf "%s\\n%s\\n" "$EXPECTED_VERSION_CODE" "$EXPECTED_VERSION_NAME"'
        for environment, expected in (
            ({}, [version_code, version_name]),
            ({"INFINITY_EXPECTED_VERSION_CODE": "99", "INFINITY_EXPECTED_VERSION_NAME": "test-only"}, ["99", "test-only"]),
        ):
            with self.subTest(environment=environment):
                result = subprocess.run(
                    ["/bin/bash", "-c", shell],
                    env={**environment, "REPO_ROOT": str(ROOT), "PATH": "/usr/bin:/bin"},
                    text=True,
                    capture_output=True,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.splitlines(), expected)

    def test_tag_must_match_source_version_and_come_from_main(self) -> None:
        validate_job = workflow_job("validate-release-inputs")
        tag_branch = validate_job.split('test "${GITHUB_EVENT_NAME}" = "push"', 1)[1]
        ordered_checks = (
            'test "${GITHUB_REF_TYPE}" = "tag"',
            '[[ "${GITHUB_REF_NAME}" != "${EXPECTED_TAG}" ]]',
            'git cat-file -t "refs/tags/${GITHUB_REF_NAME}"',
            '[[ "${tag_object_type}" != "tag" ]]',
            '[[ "${tag_target_type}" != "commit" ]]',
            'test "${tag_target_sha}" = "${GITHUB_SHA}"',
            "git fetch --no-tags origin '+refs/heads/main:refs/remotes/origin/main'",
            'git merge-base --is-ancestor "${tag_target_sha}" origin/main',
        )
        positions = [tag_branch.index(check) for check in ordered_checks]
        self.assertEqual(positions, sorted(positions))

    def test_workflow_dispatch_candidate_is_main_only_and_never_publishes(self) -> None:
        validate_job = workflow_job("validate-release-inputs")
        candidate_start = validate_job.index('if [[ "${GITHUB_EVENT_NAME}" == "workflow_dispatch" ]]')
        candidate_end = validate_job.index("\n          fi", candidate_start)
        candidate_branch = validate_job[candidate_start:candidate_end]
        ordered_checks = (
            'test "${GITHUB_REF_TYPE}" = "branch"',
            'test "${GITHUB_REF_NAME}" = "main"',
            "exit 0",
        )
        positions = [candidate_branch.index(check) for check in ordered_checks]
        self.assertEqual(positions, sorted(positions))
        self.assertLess(validate_job.index("release-metadata.py"), candidate_start, "候选模式也要先校验 CHANGELOG")
        self.assertEqual(
            job_level_value(workflow_job("publish"), "if"),
            "${{ github.event_name == 'push' && github.ref_type == 'tag' }}",
        )

    def test_candidate_mode_keeps_all_pre_publish_jobs_enabled(self) -> None:
        for job_name in (
            "device-e2e",
            "minified-blackbox",
            "build-release",
            "signed-apk-smoke",
        ):
            with self.subTest(job=job_name):
                self.assertIsNone(job_level_value(workflow_job(job_name), "if"))
        signed_job = workflow_job("signed-apk-smoke")
        self.assertIn("- api-level: 31", signed_job)
        self.assertIn("- api-level: 36", signed_job)
        self.assertEqual(
            job_level_value(workflow_job("publish"), "needs"),
            "[validate-release-inputs, signed-apk-smoke, device-e2e, minified-blackbox]",
        )

    def test_upgrade_smoke_script_keeps_install_r_only_path(self) -> None:
        self.assertIn("64124d620f1875ac448941c5c8161bfc7f4bdabdc592f0b0d53e59530623b232", UPGRADE_SMOKE)
        self.assertIn("UPGRADE_WORKSPACE_SENTINEL", UPGRADE_SMOKE)
        self.assertNotIn("sentinel-hash", UPGRADE_SMOKE)
        self.assertNotIn("root-hash", UPGRADE_SMOKE)
        upgrade_phase = UPGRADE_SMOKE.split("# 覆盖升级核心动作", 1)[1]
        self.assertIn('install -r --no-streaming "${NEW_APK}"', upgrade_phase)
        self.assertNotRegex(upgrade_phase, r"(?m)^\s*adb_cmd\s+uninstall\b")
        self.assertNotRegex(upgrade_phase, r"(?m)^\s*adb_cmd\s+shell\s+pm\s+clear\b")
        self.assertIn("cmd package resolve-activity --brief", UPGRADE_SMOKE)
        self.assertIn('adb_cmd shell am start -W -n "${component}"', UPGRADE_SMOKE)
        self.assertNotIn("adb_cmd shell monkey", UPGRADE_SMOKE)

    def test_cross_job_artifact_name_survives_failed_job_rerun(self) -> None:
        self.assertIn("signed-release-apk-${{ github.run_id }}", RELEASE)
        self.assertNotIn("signed-release-apk-${{ github.run_attempt }}", RELEASE)
        signed_upload = RELEASE.split("- name: 上传已验签发行产物", 1)[1].split("\n\n", 1)[0]
        self.assertIn("overwrite: true", signed_upload)

    def test_release_workflow_includes_minified_blackbox_job_and_dependency(self) -> None:
        self.assertIn("minified-blackbox", RELEASE)
        self.assertIn("Release 等价 minifiedTest 黑盒回归", RELEASE)
        self.assertIn("scripts/ci/android-minified-blackbox-smoke.sh", RELEASE)
        self.assertIn("artifacts/android-minified-blackbox-api-${{ matrix.api-level }}-x86_64/", RELEASE)
        self.assertNotIn("INFINITY_DEVICE_E2E_BUILD_TYPE: minifiedTest", RELEASE)
        self.assertIn("build-release:", RELEASE)
        self.assertEqual(
            job_level_value(workflow_job("build-release"), "needs"),
            "[validate-release-inputs, static-checks]",
        )
        self.assertEqual(
            re.findall(r"^          - api-level: (\d+)$", workflow_job("minified-blackbox"), re.MULTILINE),
            ["36"],
        )

    def test_screenshot_validation_remains_available_outside_release(self) -> None:
        self.assertNotIn("screenshot-gate", RELEASE)
        self.assertNotIn("screenshot-validate.yml", RELEASE)
        self.assertNotIn("matrix.shard", RELEASE)
        source = (ROOT / ".github/workflows/screenshot-validate.yml").read_text(encoding="utf-8")
        self.assertIn("screenshot-baseline.py", source)
        self.assertIn("screenshot-shards.py", source)
        self.assertIn("screenshot-results.py", source)
        self.assertIn("test-results/validateSideloadDebugScreenshotTest/*.xml", source)
        self.assertIn("INFINITY_SCREENSHOT_REFERENCE_DIR", source)
        self.assertIn(":app:validateSideloadDebugScreenshotTest", source)
        self.assertIn('args+=(--tests "*$class*")', source)
        self.assertIn("set -euo pipefail", source)
        self.assertNotIn(":app:updateSideloadDebugScreenshotTest", source)

    def test_build_release_reuses_gradle_cache_and_only_builds_release_once(self) -> None:
        build_job = workflow_job("build-release")
        build_step = build_job.split("        working-directory: native-ui\n", 1)[1]
        build_step = build_step.split("- name: 删除临时 keystore", 1)[0]
        commands = continued_shell_commands(build_step, "./gradlew")
        self.assertEqual(
            commands,
            [
                ["./gradlew", "--stacktrace", ":app:assembleSideloadRelease"],
            ],
        )
        errexit = re.search(r"(?m)^\s*set -euo pipefail\s*$", build_step)
        self.assertIsNotNone(errexit)
        assert errexit is not None
        self.assertLess(errexit.start(), build_step.index("./gradlew"))
        self.assertNotRegex(build_step, r"(?m)^\s*set\s+\+e\b")
        self.assertEqual(len(continued_shell_commands(build_job, "./gradlew")), 1)
        for removed in ("UnitTest", "lint", "assembleSideloadDebug", "assemblePlayDebug", "screenshotTest"):
            self.assertNotIn(removed, build_job)
        self.assertIn("python3 ../scripts/ci/check-native-page-alignment.py --build-type release", build_step)
        self.assertIn("app/build/outputs/apk/sideload/release/app-sideload-release.apk", build_step)

    def test_r8_evidence_and_download_integrity_are_mandatory(self) -> None:
        self.assertIn('outputs/mapping/${distribution}Release', RELEASE)
        self.assertIn('distribution=sideload', workflow_job("build-release"))
        self.assertIn('test -s "${mapping_path}"', RELEASE)
        for filename in ("mapping.txt", "seeds.txt", "usage.txt", "configuration.txt"):
            self.assertIn(filename, RELEASE)
        self.assertGreaterEqual(RELEASE.count("sha256sum -c"), 2)

    def test_signed_minified_apk_is_cold_installed_on_minimum_and_latest_sdk(self) -> None:
        signed_job = RELEASE.split("  signed-apk-smoke:", 1)[1].split("\n  publish:", 1)[0]
        self.assertIn("冷安装 smoke", signed_job)
        self.assertEqual(
            re.findall(
                r'^          - api-level: (\d+)\n            distribution: (\w+)\n            apk-suffix: "([^"]*)"$',
                signed_job, re.MULTILINE,
            ),
            [("31", "sideload", ""), ("36", "sideload", "")],
        )
        self.assertIn("android-release-apk-smoke.sh", signed_job)
        self.assertNotIn("android-device-core-e2e.sh", signed_job)

    def test_upgrade_smoke_reads_back_every_required_data_sentinel(self) -> None:
        for sentinel in (
            "Upgrade Agent",
            "Upgrade Session",
            "UPGRADE_MESSAGE_SENTINEL",
            "upgrade.txt",
            "Generic_OpenAI_Compat",
            "https://upgrade.invalid/v1/chat/completions",
            "upgrade-model",
            "Upgrade Provider",
            "UPGRADE_USER_SENTINEL",
            "64124d620f1875ac448941c5c8161bfc7f4bdabdc592f0b0d53e59530623b232",
        ):
            with self.subTest(sentinel=sentinel):
                self.assertGreaterEqual(
                    UPGRADE_SMOKE.count(sentinel),
                    2,
                    msg=f"升级脚本必须写入并回读 {sentinel}",
                )

    def test_upgrade_smoke_apk_field_requires_a_full_attribute_boundary(self) -> None:
        expression = r"s/^package:.*[[:space:]]name='\([^']*\)'.*$/\1/p"
        self.assertIn("[[:space:]]${field}=", UPGRADE_SMOKE)
        sample = (
            "package: name='com.promenar.infinity' versionCode='1' "
            "versionName='0.1' compileSdkVersionCodename='16'\n"
        )
        completed = subprocess.run(
            ["sed", "-n", expression],
            input=sample,
            text=True,
            capture_output=True,
            check=True,
        )
        self.assertEqual(completed.stdout.strip(), "com.promenar.infinity")

    def test_upgrade_smoke_uses_public_v17_database_contract_and_current_v21_target(self) -> None:
        self.assertIn('remote_db="${data_dir}/databases/infinity.db"', UPGRADE_SMOKE)
        self.assertIn("公开旧版 Room schema 不是实测 v17", UPGRADE_SMOKE)
        self.assertIn("3311ec5f07e8df42c02fd09163c49f6e", UPGRADE_SMOKE)
        self.assertNotIn("name_customized,description_customized", UPGRADE_SMOKE)
        self.assertIn('upgraded_remote_db="${data_dir}/databases/infinity_v2.db"', UPGRADE_SMOKE)
        self.assertIn("覆盖升级后 Room schema 不是 v21", UPGRADE_SMOKE)
        self.assertIn("b59d4265cdf25db536fb91226cd85ab3", UPGRADE_SMOKE)
        self.assertIn("UPGRADE_ATTACHMENT_SENTINEL", UPGRADE_SMOKE)
        self.assertIn("WAL_ONLY_SENTINEL", UPGRADE_SMOKE)
        self.assertIn('push "${wal_fixture}/${db_name}-wal" "${remote_db}-wal"', UPGRADE_SMOKE)
        self.assertIn("向量任务工作区哨兵丢失", UPGRADE_SMOKE)
        self.assertIn('workspace_root="${data_dir}/files/workspaces/upgrade-session"', UPGRADE_SMOKE)
        self.assertIn("公开 v17 工作区根未经过真实仓库身份认领", UPGRADE_SMOKE)
        self.assertIn('${workspace_root}/.infinity_root_identity', UPGRADE_SMOKE)
        self.assertIn("wait_for_upgraded_startup", UPGRADE_SMOKE)
        self.assertIn("INFINITY_UPGRADE_STARTUP_TIMEOUT_SECONDS", UPGRADE_SMOKE)
        self.assertIn("PRAGMA user_version", UPGRADE_SMOKE)
        self.assertNotIn('adb_cmd shell sleep 4', UPGRADE_SMOKE)

    def test_upgrade_smoke_accepts_current_and_legacy_apksigner_labels(self) -> None:
        expression = (
            r"s/^(Signer #[0-9]+|V[0-9]+ Signer):? certificate SHA-256 digest: "
            r"([[:xdigit:]]{64})$/\2/p"
        )
        self.assertIn("Signer #[0-9]+|V[0-9]+ Signer", UPGRADE_SMOKE)
        digest = "00be4cdd8378aafbd70ebc43e971523791cc07deead631e06dcf155deeee3802"
        sample = (
            f"Signer #1 certificate SHA-256 digest: {digest}\n"
            f"V2 Signer: certificate SHA-256 digest: {digest}\n"
        )
        completed = subprocess.run(
            ["sed", "-n", "-E", expression],
            input=sample,
            text=True,
            capture_output=True,
            check=True,
        )
        self.assertEqual(completed.stdout.splitlines(), [digest, digest])

    def test_smoke_requires_modern_zip_alignment(self) -> None:
        self.assertIn('ZIPALIGN="${BUILD_TOOLS_DIR}/zipalign"', SMOKE)
        self.assertIn("zipalign", SMOKE)
        self.assertIn("-c -P 16 4 \"${APK_PATH}\"", SMOKE)

    def run_smoke_resumed_activity_match(self, dump_line: str, package: str = "com.promenar.infinity") -> bool:
        function_match = re.search(
            r"(?ms)^is_expected_resumed_activity\(\) \{\n.*?^\}",
            SMOKE,
        )
        self.assertIsNotNone(function_match)
        assert function_match is not None
        with tempfile.TemporaryDirectory() as tmp_dir:
            dump_path = Path(tmp_dir) / "activities.txt"
            dump_path.write_text(dump_line + "\n", encoding="utf-8")
            runner = f'''PACKAGE_NAME="$1"
{function_match.group(0)}
is_expected_resumed_activity "$2"
'''
            result = subprocess.run(
                ["bash", "-c", runner, "resumed-activity-test", package, str(dump_path)],
                text=True,
                capture_output=True,
                check=False,
            )
            return result.returncode == 0

    def test_smoke_resumed_activity_parser_accepts_api31_and_modern_formats(self) -> None:
        accepted = (
            "topResumedActivity: ActivityRecord{a1 u0 "
            "com.promenar.infinity/com.promenar.infinity.MainActivity}",
            "mResumedActivity = ActivityRecord{a2 u0 "
            "com.promenar.infinity/com.promenar.infinity.MainActivity}",
        )
        for dump_line in accepted:
            with self.subTest(dump_line=dump_line):
                self.assertTrue(self.run_smoke_resumed_activity_match(dump_line))

    def test_smoke_resumed_activity_parser_rejects_wrong_package_and_non_resumed_records(self) -> None:
        rejected = (
            "topResumedActivity: ActivityRecord{a1 u0 "
            "com.promenar.other/com.promenar.infinity.MainActivity}",
            "mResumedActivity=ActivityRecord{a2 u0 "
            "com.promenar.infinity2/com.promenar.infinity.MainActivity}",
            "mFocusedActivity: ActivityRecord{a3 u0 "
            "com.promenar.infinity/com.promenar.infinity.MainActivity}",
            "mLastResumedActivity: ActivityRecord{a4 u0 "
            "com.promenar.infinity/com.promenar.infinity.MainActivity}",
        )
        for dump_line in rejected:
            with self.subTest(dump_line=dump_line):
                self.assertFalse(self.run_smoke_resumed_activity_match(dump_line))

    def test_smoke_reuses_fail_closed_apk_identity_and_signer_verifier(self) -> None:
        verifier_call = 'python3 "${REPO_ROOT}/scripts/verify-release-apk.py"'
        self.assertIn(verifier_call, SMOKE)
        for option in (
            "--expected-package",
            "--expected-version-code",
            "--expected-version-name",
            "--expected-cert-sha256",
            "--distribution",
        ):
            self.assertIn(option, SMOKE)
        self.assertLess(SMOKE.find(verifier_call), SMOKE.find('adb install --no-streaming'))
        self.assertNotIn("Signer #1 certificate SHA-256 digest", SMOKE)

    def test_real_llm_task_never_reuses_stale_test_outputs(self) -> None:
        task = braced_block_after(
            APP_BUILD,
            'tasks.register<Test>("realLlmIntegrationTest")',
        )
        self.assertIn("outputs.upToDateWhen { false }", task)
        self.assertIn("outputs.cacheIf { false }", task)

    def test_publish_is_idempotent_and_checks_existing_asset_hashes(self) -> None:
        publish_job = RELEASE.split("\n  publish:", 1)[1]
        self.assertIn("gh release view", publish_job)
        self.assertIn("gh release download", publish_job)
        self.assertIn("sha256sum", publish_job)
        self.assertIn("gh release upload", publish_job)
        self.assertIn("gh release create", publish_job)

    def test_publish_keeps_release_draft_until_all_assets_are_verified(self) -> None:
        publish_job = RELEASE.split("\n  publish:", 1)[1]
        create_idx = publish_job.index("gh release create")
        upload_idx = publish_job.index("gh release upload")
        verification_idx = publish_job.index("上传后 Release 资产")
        publish_idx = publish_job.index("gh release edit")
        self.assertIn("--draft", publish_job[create_idx:upload_idx])
        self.assertLess(create_idx, upload_idx)
        self.assertLess(upload_idx, verification_idx)
        self.assertLess(verification_idx, publish_idx)
        self.assertIn("--draft=false", publish_job[publish_idx:])
        self.assertIn("--notes-file release-notes.md", publish_job[publish_idx:])
        self.assertIn('--prerelease="${PRERELEASE}"', publish_job[publish_idx:])
        self.assertIn('--latest="${latest}"', publish_job[publish_idx:])
        self.assertIn('if [[ "${PRERELEASE}" == "true" ]]; then latest=false; else latest=true; fi', publish_job)

    def test_publish_notes_come_from_changelog_before_release_commands(self) -> None:
        publish_job = RELEASE.split("\n  publish:", 1)[1]
        notes_idx = publish_job.index("python3 scripts/ci/release-metadata.py --notes release-notes.md")
        self.assertLess(notes_idx, publish_job.index("gh release create"))
        self.assertIn("### 安装包", publish_job)
        self.assertNotIn("docs/release/", RELEASE)

    def run_release_metadata(self, build: str, changelog: str) -> tuple[subprocess.CompletedProcess[str], str]:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_root = Path(tmp_dir)
            (tmp_root / "build.gradle.kts").write_text(build, encoding="utf-8")
            (tmp_root / "CHANGELOG.md").write_text(changelog, encoding="utf-8")
            notes = tmp_root / "notes.md"
            result = subprocess.run(
                [
                    sys.executable,
                    METADATA,
                    "--build-file", str(tmp_root / "build.gradle.kts"),
                    "--changelog", str(tmp_root / "CHANGELOG.md"),
                    "--notes", str(notes),
                ],
                text=True,
                capture_output=True,
                check=False,
            )
            return result, notes.read_text(encoding="utf-8") if notes.exists() else ""

    def test_release_metadata_extracts_only_the_matching_changelog_section(self) -> None:
        build = '        versionCode = 21\n        versionName = "0.7.1-beta"\n'
        changelog = (
            "# Changelog\n\n## [Unreleased]\n\n- 未发布\n\n"
            "## [0.7.1-beta] - 2026-11-01\n\n### 修复\n\n- 修复 A\n\n"
            "## [0.7.0-beta] - 2026-10-30\n\n- 旧版本\n"
        )
        result, notes = self.run_release_metadata(build, changelog)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            result.stdout.splitlines(),
            ["tag=v0.7.1-beta", "version-name=0.7.1-beta", "version-code=21", "prerelease=false"],
        )
        self.assertEqual(notes, "### 修复\n\n- 修复 A\n")

    def test_release_metadata_marks_stable_versions_and_reads_last_section(self) -> None:
        build = '        versionCode = 30\n        versionName = "1.0.0"\n'
        result, notes = self.run_release_metadata(build, "## [1.0.0] - 2026-12-01\n\n- 正式版\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("prerelease=false", result.stdout.splitlines())
        self.assertEqual(notes, "- 正式版\n")

    def test_release_metadata_marks_only_non_beta_suffixes_as_prerelease(self) -> None:
        for version_name, prerelease in (
            ("1.0.0", "false"),
            ("0.34.0-beta", "false"),
            ("0.35.0-alpha", "true"),
            ("1.0.0-rc.1", "true"),
            ("0.34.0-beta.2", "true"),
        ):
            with self.subTest(version=version_name):
                build = f'        versionCode = 1\n        versionName = "{version_name}"\n'
                result, _ = self.run_release_metadata(build, f"## [{version_name}] - 2026-11-07\n\n- A\n")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(f"prerelease={prerelease}", result.stdout.splitlines())

    def test_release_metadata_fails_closed(self) -> None:
        build = '        versionCode = 21\n        versionName = "0.7.1-beta"\n'
        cases = (
            (build, "## [0.7.0-beta] - 2026-10-30\n\n- 旧版本\n"),
            (build, "## [0.7.1-beta] - 2026-11-01\n\n## [0.7.0-beta] - 2026-10-30\n\n- 旧版本\n"),
            (build, "## [0.7.1-beta] - 2026-11-01\n\n- A\n\n## [0.7.1-beta] - 2026-11-02\n\n- B\n"),
            (build, "## [0.7.1-beta-rc] - 2026-11-01\n\n- 前缀相同的另一版本\n"),
            ('        versionCode = 21\n', "## [0.7.1-beta]\n\n- A\n"),
            ('        versionCode = 21\n        versionName = "beta"\n', "## [beta]\n\n- A\n"),
        )
        for build_source, changelog in cases:
            with self.subTest(build=build_source, changelog=changelog):
                result, _ = self.run_release_metadata(build_source, changelog)
                self.assertNotEqual(result.returncode, 0)

    def test_release_metadata_accepts_current_repository_sources(self) -> None:
        result = subprocess.run([sys.executable, METADATA], text=True, capture_output=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        version_name = re.search(r'versionName\s*=\s*"([^"\n]+)"', APP_BUILD).group(1)
        self.assertIn(f"tag=v{version_name}", result.stdout.splitlines())

    def test_actions_are_sha_pinned_and_permissions_are_minimal(self) -> None:
        screenshot_workflow = (ROOT / ".github/workflows/screenshot-validate.yml").read_text(encoding="utf-8")
        regression = (ROOT / ".github/workflows/device-regression.yml").read_text(encoding="utf-8")
        for workflow in (ANDROID_CI, RELEASE, screenshot_workflow, regression):
            for action_ref in re.findall(r"uses:\s+([^\s#]+)", workflow):
                if action_ref == "./.github/workflows/screenshot-validate.yml":
                    continue
                self.assertRegex(action_ref, r"@[0-9a-f]{40}$")
        self.assertIn("permissions:\n  contents: read", RELEASE)
        self.assertIn("permissions:\n      contents: write", RELEASE)
        self.assertIn("if: always()", RELEASE)
        self.assertIn('rm -f "${RUNNER_TEMP}/infinity-release.jks"', RELEASE)

    def test_every_gradle_job_installs_registered_go_for_tailnet(self) -> None:
        version = re.search(r'val tailnetGoVersion = "([0-9.]+)"', APP_BUILD)
        self.assertIsNotNone(version)
        regression = (ROOT / ".github/workflows/device-regression.yml").read_text(encoding="utf-8")
        for workflow in (RELEASE, regression):
            jobs = re.findall(r"^  [a-z][a-z0-9-]*:\n(.*?)(?=^  [a-z][a-z0-9-]*:\n|\Z)", workflow, flags=re.MULTILINE | re.DOTALL)
            gradle_jobs = [job for job in jobs if "actions/setup-java@" in job]
            self.assertTrue(gradle_jobs)
            for job in gradle_jobs:
                self.assertRegex(job, r"uses: actions/setup-go@[0-9a-f]{40} # v\d+")
                self.assertIn(f'go-version: "{version.group(1)}"', job)
                self.assertIn("cache-dependency-path: native-ui/app/tailnet/go.sum", job)

    def test_release_device_e2e_only_runs_minimum_sdk(self) -> None:
        job = workflow_job("device-e2e")
        self.assertEqual(re.findall(r"^          - api-level: (\d+)$", job, re.MULTILINE), ["31"])
        self.assertEqual(re.findall(r"^            e2e-scope: (\w+)$", job, re.MULTILINE), ["minimum"])

    def test_full_device_regression_is_manual_unsigned_and_reuses_e2e_steps(self) -> None:
        workflow = ROOT / ".github/workflows/device-regression.yml"
        self.assertTrue(workflow.is_file(), "完整设备回归必须有独立手动入口")
        source = workflow.read_text(encoding="utf-8")
        trigger = source.split("\npermissions:", 1)[0]
        self.assertEqual(re.findall(r"^  ([a-z][a-z_-]*):", trigger, re.MULTILINE), ["workflow_dispatch"])
        self.assertIn("permissions:\n  contents: read", source)
        self.assertNotIn("secrets.", source)
        self.assertNotIn("environment: release", source)
        self.assertNotIn("device-regression.yml", RELEASE)
        job = workflow_job("device-e2e", source)
        self.assertIsNone(job_level_value(job, "needs"))
        self.assertIsNone(job_level_value(job, "if"))
        self.assertEqual(re.findall(r"^          - api-level: (\d+)$", job, re.MULTILINE), ["35", "36"])
        self.assertEqual(re.findall(r"^            e2e-scope: (\w+)$", job, re.MULTILINE), ["full", "full"])
        self.assertIn("fail-fast: false", job)
        self.assertEqual(job_level_value(job, "timeout-minutes"), "150")
        self.assertEqual(
            job.split("    steps:\n", 1)[1],
            workflow_job("device-e2e").split("    steps:\n", 1)[1].replace(
                "name: release-device-e2e-api-", "name: device-regression-api-",
            ),
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
