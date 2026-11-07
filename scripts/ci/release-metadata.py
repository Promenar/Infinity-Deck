#!/usr/bin/env python3
"""从 Android 源码与 CHANGELOG 读取发行元数据。

版本名与版本号以 native-ui/app/build.gradle.kts 为准，标签固定为 v<versionName>；
发布说明取 CHANGELOG.md 中 `## [<versionName>]` 小节的正文。
无后缀与 `-beta` 后缀按正式发行公开并设为 Latest；其它后缀（如 `-alpha`、`-rc.1`）标为 prerelease。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERSION_NAME_PATTERN = re.compile(r'^\s*versionName\s*=\s*"([^"\n]+)"\s*$', re.MULTILINE)
VERSION_CODE_PATTERN = re.compile(r"^\s*versionCode\s*=\s*(\d+)\s*$", re.MULTILINE)
SEMVER_NAME = re.compile(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?")
LATEST_SUFFIXES = ("", "beta")


def read_version(build_file: Path) -> tuple[str, int]:
    source = build_file.read_text(encoding="utf-8")
    names = VERSION_NAME_PATTERN.findall(source)
    codes = VERSION_CODE_PATTERN.findall(source)
    if len(names) != 1 or len(codes) != 1:
        raise SystemExit(f"{build_file} 必须恰好声明一个 versionName 与一个 versionCode")
    if not SEMVER_NAME.fullmatch(names[0]):
        raise SystemExit(f"versionName {names[0]!r} 不符合 主.次.补丁[-后缀]")
    return names[0], int(codes[0])


def is_latest_release(version_name: str) -> bool:
    suffix = version_name.partition("-")[2]
    return suffix in LATEST_SUFFIXES


def changelog_section(changelog: str, version_name: str) -> str:
    heading = re.compile(rf"^## \[{re.escape(version_name)}\](?:\s+-\s+\d{{4}}-\d{{2}}-\d{{2}})?\s*$", re.MULTILINE)
    matches = list(heading.finditer(changelog))
    if len(matches) != 1:
        raise SystemExit(f"CHANGELOG 必须恰好有一个 ## [{version_name}] 小节，实际 {len(matches)} 个")
    start = matches[0].end()
    following = re.compile(r"^## ", re.MULTILINE).search(changelog, start)
    body = changelog[start:following.start() if following else len(changelog)].strip()
    if not body:
        raise SystemExit(f"CHANGELOG 的 ## [{version_name}] 小节为空")
    return body + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-file", type=Path, default=ROOT / "native-ui/app/build.gradle.kts")
    parser.add_argument("--changelog", type=Path, default=ROOT / "CHANGELOG.md")
    parser.add_argument("--notes", type=Path, help="把本版发布说明写入该文件")
    parser.add_argument("--github-output", type=Path, help="以 key=value 追加到 GITHUB_OUTPUT")
    args = parser.parse_args(argv)

    version_name, version_code = read_version(args.build_file)
    notes = changelog_section(args.changelog.read_text(encoding="utf-8"), version_name)
    if args.notes:
        args.notes.write_text(notes, encoding="utf-8")

    values = {
        "tag": f"v{version_name}",
        "version-name": version_name,
        "version-code": str(version_code),
        "prerelease": "false" if is_latest_release(version_name) else "true",
    }
    lines = "".join(f"{key}={value}\n" for key, value in values.items())
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(lines)
    sys.stdout.write(lines)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
