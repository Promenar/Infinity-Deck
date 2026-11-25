#!/usr/bin/env python3
"""多进程并行运行 Compose 截图校验（或基线更新）。

通过 Gradle 任务 :app:exportSideloadDebugScreenshotRunnerConfig 复用增量编译、
配置缓存与上游 AGP 截图引擎配置，再按参考图数量将 @PreviewTest 类均衡分配到多个
独立 JVM 进程并行渲染，每个分片写入隔离的 XML / rendered / diffs 目录，彻底绕过
PreviewScreenshotValidationTask 单进程串行限制并复用多核硬件。
"""

from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
import fnmatch
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
NATIVE_UI = ROOT / "native-ui"
RUNNER_CONFIG = NATIVE_UI / "app/build/tmp/parallel-screenshot-runner/config/runner.json"
RUN_ROOT = NATIVE_UI / "app/build/tmp/parallel-screenshot-runner/run"
RUNNER_MAIN = "com.promenar.infinity.screenshot.ScreenshotShardRunner"
PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$", re.MULTILINE)
VALID_CLASS_RE = re.compile(r"[A-Za-z_][A-Za-z_0-9]*")


@dataclass(frozen=True)
class PreviewTestClass:
    fqcn: str
    simple_name: str
    weight: int


@dataclass(frozen=True)
class ShardResult:
    index: int
    expected_classes: list[str]
    selectors: list[str]
    returncode: int
    duration_seconds: float
    xml_dir: Path
    diff_dir: Path
    log_path: Path
    passed_count: int
    observed_classes: dict[str, int]
    error_message: str | None
    failures: list[str]


def detect_ram_gib() -> int | None:
    if sys.platform == "darwin":
        try:
            out = subprocess.check_output(["sysctl", "-n", "hw.memsize"], text=True).strip()
            return max(1, int(out) // (1024 ** 3))
        except Exception:
            return None
    meminfo = Path("/proc/meminfo")
    if meminfo.is_file():
        for line in meminfo.read_text(encoding="utf-8", errors="ignore").splitlines():
            if line.startswith("MemTotal:"):
                parts = line.split()
                if len(parts) >= 2 and parts[1].isdigit():
                    return max(1, int(parts[1]) // (1024 ** 2))
    return None


def default_shard_count(cpus: int | None = None, ram_gib: int | None = None) -> int:
    cpu_count = cpus if cpus is not None else (os.cpu_count() or 4)
    by_cpu = max(2, cpu_count // 2)
    ram = ram_gib if ram_gib is not None else detect_ram_gib()
    if ram is not None:
        # 每个 Layoutlib JVM 进程（-Xmx2g + 原生/元空间开销）预留约 6 GiB 宿主机安全余量。
        by_ram = max(2, ram // 6)
        return min(8, by_cpu, by_ram)
    return min(8, by_cpu)


def discover_preview_classes(root: Path, reference_dir: Path | None = None) -> list[PreviewTestClass]:
    app = root / "native-ui/app/src"
    source = app / "screenshotTest"
    reference = reference_dir if reference_dir is not None else (app / "screenshotTestDebug/reference")
    discovered: list[PreviewTestClass] = []
    seen_simple: set[str] = set()

    for path in sorted(source.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        if "@PreviewTest" not in text:
            continue
        simple_name = path.stem + "Kt"
        if not VALID_CLASS_RE.fullmatch(simple_name) or simple_name in seen_simple:
            raise ValueError(f"截图源文件名无效或重复：{simple_name}")
        seen_simple.add(simple_name)
        pkg_match = PACKAGE_RE.search(text)
        fqcn = f"{pkg_match.group(1)}.{simple_name}" if pkg_match else simple_name
        matches = list(reference.rglob(simple_name)) if reference.exists() else []
        if len(matches) > 1:
            raise ValueError(f"截图参考目录存在重名类：{simple_name}")
        weight = max(1, sum(1 for _ in matches[0].rglob("*.png"))) if matches else 1
        discovered.append(PreviewTestClass(fqcn=fqcn, simple_name=simple_name, weight=weight))

    if not discovered:
        raise ValueError("未发现任何 @PreviewTest 截图源文件")
    return discovered


def filter_preview_classes(
    classes: list[PreviewTestClass],
    patterns: list[str],
) -> tuple[list[PreviewTestClass], dict[str, list[str]]]:
    """按 --tests 模式过滤截图类，支持通配符与 ClassName.methodName / fqcn#methodName。"""
    if not patterns:
        return classes, {item.fqcn: [item.fqcn] for item in classes}

    selected_map: dict[str, PreviewTestClass] = {}
    selectors_by_fqcn: dict[str, list[str]] = {}

    for raw in patterns:
        pattern = raw.strip()
        if not pattern:
            continue
        method_name: str | None = None
        class_pattern = pattern
        if "#" in pattern:
            class_pattern, method_name = pattern.split("#", 1)
        elif "." in pattern and not pattern.endswith("*"):
            head, tail = pattern.rsplit(".", 1)
            # 简单类名.方法名 或 包名.类名.方法名（方法名不以 Kt 结尾且类名含大写字母）
            if tail and not tail.endswith("Kt") and any(c.isupper() for c in head.rsplit(".", 1)[-1]):
                class_pattern, method_name = head, tail

        matched_any = False
        for item in classes:
            stem = item.simple_name.removesuffix("Kt")
            fqcn_no_kt = item.fqcn.removesuffix("Kt")
            candidates = (item.fqcn, item.simple_name, stem, fqcn_no_kt)
            if any(
                fnmatch.fnmatchcase(candidate, class_pattern)
                or candidate.endswith(f".{class_pattern}")
                for candidate in candidates
            ):
                matched_any = True
                selected_map[item.fqcn] = item
                selector = f"{item.fqcn}#{method_name}" if method_name else item.fqcn
                selectors = selectors_by_fqcn.setdefault(item.fqcn, [])
                if selector not in selectors:
                    selectors.append(selector)
        if not matched_any:
            raise ValueError(f"--tests 过滤未匹配任何截图测试类：{pattern}")

    selected = sorted(selected_map.values(), key=lambda item: item.fqcn)
    return selected, selectors_by_fqcn


def pack_shards(classes: list[PreviewTestClass], shard_count: int) -> list[list[PreviewTestClass]]:
    if shard_count < 1:
        raise ValueError("分片数必须 >= 1")
    effective = min(shard_count, len(classes))
    buckets: list[list[PreviewTestClass]] = [[] for _ in range(effective)]
    totals = [0] * effective
    for item in sorted(classes, key=lambda entry: (-entry.weight, entry.simple_name)):
        target = min(range(effective), key=lambda idx: (totals[idx], idx))
        buckets[target].append(item)
        totals[target] += item.weight
    return [sorted(bucket, key=lambda entry: entry.simple_name) for bucket in buckets]


def escape_properties_value(value: str) -> str:
    return value.replace("\\", "\\\\")


def build_shard_properties(
    base_properties_text: str,
    xml_dir: Path,
    rendered_dir: Path,
    diff_dir: Path,
    reference_dir: Path,
    recording_mode: bool,
) -> str:
    overrides = {
        "PreviewScreenshotTestEngineInput.XmlReportInput.isEnabled": "true",
        "PreviewScreenshotTestEngineInput.XmlReportInput.outputDirectory": escape_properties_value(str(xml_dir)),
        "PreviewScreenshotTestEngineInput.previewImageOutputDir": escape_properties_value(str(rendered_dir)),
        "PreviewScreenshotTestEngineInput.previewDiffImageOutputDir": escape_properties_value(str(diff_dir)),
        "PreviewScreenshotTestEngineInput.referenceImageDir": escape_properties_value(str(reference_dir)),
        "PreviewScreenshotTestEngineInput.TestOption.recordingModeEnabled": "true" if recording_mode else "false",
    }
    output_lines: list[str] = []
    seen: set[str] = set()
    for line in base_properties_text.splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key = line.split("=", 1)[0].strip()
            if key in overrides:
                output_lines.append(f"{key}={overrides[key]}")
                seen.add(key)
                continue
        output_lines.append(line)
    for key, value in overrides.items():
        if key not in seen:
            output_lines.append(f"{key}={value}")
    return "\n".join(output_lines) + "\n"


def validate_shard_xml(
    xml_dir: Path,
    expected_classes: list[str],
) -> tuple[int, dict[str, int], list[str], str | None]:
    files = sorted(xml_dir.glob("TEST-*.xml"))
    if not files:
        return 0, {}, [], f"缺少截图测试 XML 报告：{xml_dir}"
    observed: Counter[str] = Counter()
    failures: list[str] = []
    for path in files:
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else root.findall(".//testsuite")
        for suite in suites:
            cases = suite.findall("testcase")
            if int(suite.get("tests", "0")) != len(cases):
                return 0, dict(observed), failures, f"截图测试报告项目数不一致：{path.name}"
            for case in cases:
                classname = case.get("classname", "")
                simple = classname.rsplit(".", 1)[-1]
                name = case.get("name", "")
                for tag in ("error", "failure", "skipped"):
                    node = case.find(tag)
                    if node is not None:
                        msg = (node.get("message") or (node.text or "").strip().splitlines()[0] if (node.get("message") or (node.text or "").strip()) else tag)
                        failures.append(f"{simple}.{name} [{tag}]: {msg}")
                observed[simple] += 1
            if not failures and any(int(suite.get(key, "0")) for key in ("errors", "failures", "skipped")):
                return 0, dict(observed), failures, f"截图测试报告存在非零 errors/failures/skipped：{path.name}"
    if failures:
        return sum(observed.values()), dict(observed), failures, f"存在 {len(failures)} 项失败或跳过"
    if set(observed) != set(expected_classes):
        missing = sorted(set(expected_classes) - set(observed))
        extra = sorted(set(observed) - set(expected_classes))
        return sum(observed.values()), dict(observed), failures, f"截图类与报告不一致：缺少 {missing}，多余 {extra}"
    return sum(observed.values()), dict(observed), [], None


def run_shard(
    index: int,
    bucket: list[PreviewTestClass],
    selectors_by_fqcn: dict[str, list[str]],
    runner_cfg: dict,
    base_properties_text: str,
    reference_dir: Path,
    heap: str,
    recording_mode: bool,
    run_root: Path,
) -> ShardResult:
    shard_dir = run_root / f"shard-{index}"
    xml_dir = shard_dir / "xml"
    rendered_dir = shard_dir / "rendered"
    diff_dir = shard_dir / "diffs"
    for directory in (xml_dir, rendered_dir, diff_dir):
        directory.mkdir(parents=True, exist_ok=True)

    selectors: list[str] = []
    for item in bucket:
        selectors.extend(selectors_by_fqcn[item.fqcn])
    classes_file = shard_dir / "classes.txt"
    classes_file.write_text("\n".join(selectors) + "\n", encoding="utf-8")

    config_file = shard_dir / "preview_screenshot_config.properties"
    config_file.write_text(
        build_shard_properties(
            base_properties_text=base_properties_text,
            xml_dir=xml_dir,
            rendered_dir=rendered_dir,
            diff_dir=diff_dir,
            reference_dir=reference_dir,
            recording_mode=recording_mode,
        ),
        encoding="utf-8",
    )

    java_exe = runner_cfg["javaExecutable"]
    java_major = int(runner_cfg.get("javaMajorVersion", 21))
    classpath = os.pathsep.join(runner_cfg["classpath"])
    jvm_args = [
        f"-Xmx{heap}",
        "-Duser.language=en",
        "-Duser.country=US",
        "-Duser.timezone=Asia/Shanghai",
        "-Dlayoutlib.thread.profile.slow-rendering.enable=false",
        f"-DPreviewScreenshotTestEngineInput.configFile={config_file}",
    ]
    if java_major >= 21:
        jvm_args.append("--enable-native-access=ALL-UNNAMED")
    if java_major >= 24:
        jvm_args.append("--sun-misc-unsafe-memory-access=allow")

    cmd = [
        java_exe,
        *jvm_args,
        "-cp",
        classpath,
        RUNNER_MAIN,
        "--classes-file",
        str(classes_file),
    ]
    log_path = shard_dir / "worker.log"
    started = time.monotonic()
    with log_path.open("w", encoding="utf-8") as log_file:
        proc = subprocess.run(
            cmd,
            cwd=NATIVE_UI,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            check=False,
        )
    elapsed = time.monotonic() - started
    expected_classes = [item.simple_name for item in bucket]
    passed_count, observed, failures, xml_err = validate_shard_xml(xml_dir, expected_classes)
    error_message = xml_err
    if proc.returncode != 0 and error_message is None:
        error_message = f"分片进程退出码非零：{proc.returncode}"
    return ShardResult(
        index=index,
        expected_classes=expected_classes,
        selectors=selectors,
        returncode=proc.returncode,
        duration_seconds=elapsed,
        xml_dir=xml_dir,
        diff_dir=diff_dir,
        log_path=log_path,
        passed_count=passed_count,
        observed_classes=observed,
        error_message=error_message,
        failures=failures,
    )


def prepare_runner_config(reference_dir: Path | None) -> dict:
    env = dict(os.environ)
    if reference_dir is not None:
        env["INFINITY_SCREENSHOT_REFERENCE_DIR"] = str(reference_dir)
    cmd = ["./gradlew", ":app:exportSideloadDebugScreenshotRunnerConfig"]
    started = time.monotonic()
    subprocess.run(cmd, cwd=NATIVE_UI, env=env, check=True)
    elapsed = time.monotonic() - started
    print(f"[prepare] Gradle 配置导出完成（{elapsed:.1f}s）", flush=True)
    if not RUNNER_CONFIG.is_file():
        raise FileNotFoundError(f"未生成并行运行器配置：{RUNNER_CONFIG}")
    return json.loads(RUNNER_CONFIG.read_text(encoding="utf-8"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="多进程并行执行 Compose 截图测试")
    parser.add_argument(
        "--shards",
        type=int,
        default=default_shard_count(),
        help="并行 JVM 分片数（默认按本机 CPU 与内存自动计算）",
    )
    parser.add_argument(
        "--heap",
        default="2g",
        help="每个截图分片 JVM 的最大堆（默认 2g）",
    )
    parser.add_argument(
        "--tests",
        action="append",
        default=[],
        help="按类或方法过滤（支持通配符，可重复传入），如 --tests '*PipelineBubble*'",
    )
    parser.add_argument(
        "--reference-dir",
        type=Path,
        default=None,
        help="覆盖截图参考目录（默认读取 INFINITY_SCREENSHOT_REFERENCE_DIR 或仓库共享基线）",
    )
    parser.add_argument(
        "--update",
        action="store_true",
        help="开启 recordingModeEnabled=true 并行更新参考图基线",
    )
    parser.add_argument(
        "--skip-prepare",
        action="store_true",
        help="跳过 Gradle 编译与配置导出，直接复用最近一次导出的 runner.json",
    )
    args = parser.parse_args(argv)

    env_ref = os.environ.get("INFINITY_SCREENSHOT_REFERENCE_DIR", "").strip()
    override_ref = args.reference_dir.resolve() if args.reference_dir else (Path(env_ref).resolve() if env_ref else None)

    total_started = time.monotonic()
    if args.skip_prepare:
        if not RUNNER_CONFIG.is_file():
            print(f"找不到 {RUNNER_CONFIG}，请去掉 --skip-prepare 后重试。", file=sys.stderr)
            return 2
        runner_cfg = json.loads(RUNNER_CONFIG.read_text(encoding="utf-8"))
    else:
        runner_cfg = prepare_runner_config(override_ref)

    reference_dir = override_ref or Path(runner_cfg["defaultReferenceDir"])
    base_config_path = Path(runner_cfg["baseConfigFile"])
    base_properties_text = base_config_path.read_text(encoding="utf-8")

    all_classes = discover_preview_classes(ROOT, reference_dir)
    selected_classes, selectors_by_fqcn = filter_preview_classes(all_classes, args.tests)
    buckets = pack_shards(selected_classes, args.shards)
    total_weight = sum(item.weight for item in selected_classes)

    if RUN_ROOT.exists():
        shutil.rmtree(RUN_ROOT)
    RUN_ROOT.mkdir(parents=True, exist_ok=True)

    mode_label = "更新基线" if args.update else "校验"
    print(
        f"[run] {mode_label} {len(selected_classes)} 个测试类（参考权重 ~{total_weight} 图）· "
        f"{len(buckets)} 进程并行 · 单进程堆 {args.heap}",
        flush=True,
    )

    results: list[ShardResult] = []
    with ThreadPoolExecutor(max_workers=len(buckets)) as executor:
        futures = {
            executor.submit(
                run_shard,
                index,
                bucket,
                selectors_by_fqcn,
                runner_cfg,
                base_properties_text,
                reference_dir,
                args.heap,
                args.update,
                RUN_ROOT,
            ): index
            for index, bucket in enumerate(buckets)
        }
        for future in as_completed(futures):
            result = future.result()
            results.append(result)
            status = "PASS" if result.error_message is None else "FAIL"
            print(
                f"  [shard {result.index + 1}/{len(buckets)}] {status} · "
                f"{result.passed_count} 项 · {len(result.expected_classes)} 类 · {result.duration_seconds:.1f}s",
                flush=True,
            )

    results.sort(key=lambda item: item.index)
    total_elapsed = time.monotonic() - total_started
    total_tests = sum(item.passed_count for item in results)
    failed_shards = [item for item in results if item.error_message is not None]

    if failed_shards:
        print(f"\n截图并行{mode_label}失败（耗时 {total_elapsed:.1f}s）：", file=sys.stderr)
        for shard in failed_shards:
            print(f"  - shard {shard.index + 1}: {shard.error_message}（日志：{shard.log_path}）", file=sys.stderr)
            for detail in shard.failures[:20]:
                print(f"      * {detail}", file=sys.stderr)
            diffs = sorted(shard.diff_dir.rglob("*.png")) if shard.diff_dir.exists() else []
            for diff in diffs[:10]:
                print(f"      diff: {diff}", file=sys.stderr)
        return 1

    expected_all = {item.simple_name for item in selected_classes}
    observed_all: set[str] = set()
    for shard in results:
        observed_all.update(shard.observed_classes.keys())
    if observed_all != expected_all:
        print(
            f"汇总类校验失败：期望 {len(expected_all)} 类，实际 {len(observed_all)} 类",
            file=sys.stderr,
        )
        return 1

    print(
        f"[done] 全部通过：{total_tests} 项，{len(observed_all)} 个测试类，"
        f"{len(buckets)} 分片并行总耗时 {total_elapsed:.1f}s",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
