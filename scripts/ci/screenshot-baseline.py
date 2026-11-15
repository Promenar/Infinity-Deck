#!/usr/bin/env python3
"""只读校验 Linux 覆盖参考图，或校验后在新目录合成截图基准。"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path, PurePosixPath


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def reference_problem(directory: Path, name: str, label: str, expected: object) -> str | None:
    path = directory / name
    if not path.is_file():
        return f"{label}缺失：{name!r}"
    if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
        return f"{label}清单 SHA-256 无效：{name!r}"
    actual = digest(path)
    if expected != actual:
        return f"{label} SHA-256 失配：{name!r}（清单 {expected}，实际 {actual}）"
    return None


def validate(base: Path, overlay: Path) -> list[str]:
    for directory in (base, overlay):
        if directory.is_symlink() or any(path.is_symlink() for path in directory.rglob("*")):
            raise ValueError(f"截图参考目录不得包含符号链接：{str(directory)!r}")
        if not directory.is_dir():
            raise ValueError(f"截图参考目录不存在：{str(directory)!r}")
    manifest = json.loads((overlay / "manifest.json").read_text(encoding="utf-8"))
    if not isinstance(manifest, dict) or manifest.get("version") != 1 or not isinstance(manifest.get("entries"), dict):
        raise ValueError("Linux 截图参考清单格式无效")
    entries = manifest["entries"]
    if not entries:
        raise ValueError("Linux 截图参考清单不能为空")
    names = sorted(entries)
    problems = []
    for name in names:
        relative = PurePosixPath(name)
        if (relative.is_absolute() or ".." in relative.parts or relative.suffix != ".png"
                or relative.as_posix() != name or "\\" in name or Path(name).drive):
            problems.append(f"截图参考路径无效：{name!r}")
            continue
        hashes = entries[name]
        if not isinstance(hashes, dict):
            problems.append(f"截图参考哈希条目格式无效：{name!r}")
            continue
        for directory, label, key in ((base, "共享参考图", "base_sha256"), (overlay, "Linux 覆盖图", "linux_sha256")):
            problem = reference_problem(directory, name, label, hashes.get(key))
            if problem:
                problems.append(problem)
    actual = {path.relative_to(overlay).as_posix() for path in overlay.rglob("*.png")}
    for name in sorted(actual - entries.keys()):
        problems.append(f"Linux 覆盖文件与清单不一致（未登记）：{name!r}")
    if problems:
        details = "\n".join(f"- {problem}" for problem in problems)
        raise ValueError(f"Linux 截图覆盖校验失败，共 {len(problems)} 项。失配参考图须重新审阅。\n{details}")
    return names


def prepare(base: Path, overlay: Path, output: Path) -> None:
    resolved_base, resolved_overlay, output = base.resolve(), overlay.resolve(), output.resolve()
    if (output.exists() or output in (resolved_base, resolved_overlay)
            or resolved_base in output.parents or resolved_overlay in output.parents):
        raise ValueError("输出目录必须是输入目录以外的全新目录")
    entries = validate(base, overlay)
    shutil.copytree(resolved_base, output)
    for name in entries:
        shutil.copyfile(resolved_overlay / name, output / name)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--overlay", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check-only", action="store_true", help="只检查已登记的参考图路径和哈希")
    mode.add_argument("--output", type=Path, help="校验通过后合成基准的全新目录")
    args = parser.parse_args()
    try:
        if args.check_only:
            entries = validate(args.base, args.overlay)
            print(f"Linux 截图覆盖校验通过（{len(entries)} 条）")
        else:
            prepare(args.base, args.overlay, args.output)
    except (ValueError, OSError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
