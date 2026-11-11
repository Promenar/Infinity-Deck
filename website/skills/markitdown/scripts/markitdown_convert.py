#!/usr/bin/env python3
"""安全批量调用 MarkItDown CLI，将本地文件转换为 Markdown。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

# 图片与音频在离线、无 LLM 的默认路径下只能得到元数据，不纳入批量转换。
SUPPORTED_EXTENSIONS = {
    ".pdf", ".docx", ".pptx", ".xlsx", ".xls", ".html", ".htm",
    ".csv", ".json", ".xml", ".epub", ".zip",
}
IGNORED_DIRS = {"__pycache__", ".git", ".venv", "venv", "node_modules"}
DEFAULT_MAX_FILES = 1000
DEFAULT_MAX_FILE_BYTES = 256 * 1024 * 1024
DEFAULT_MAX_ZIP_ENTRIES = 10_000
DEFAULT_MAX_ZIP_UNCOMPRESSED_BYTES = 1024 * 1024 * 1024
DEFAULT_MAX_ZIP_RATIO = 200.0
DEFAULT_TIMEOUT_SECONDS = 120
ZIP_CONTAINER_EXTENSIONS = {".zip", ".epub", ".docx", ".pptx", ".xlsx"}
SECRET_PATTERN = re.compile(
    r"(?i)(api[_-]?key|authorization|bearer|password|secret|token)"
    r"\s*[:=]\s*[^\s,;]+"
)


class SafetyError(ValueError):
    """输入或输出违反安全边界。"""


@dataclass(frozen=True)
class InputItem:
    source: Path
    relative_output: Path


def _is_relative_to(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _path_id(path: Path) -> str:
    return hashlib.sha256(str(path).encode("utf-8")).hexdigest()[:12]


def _redact_message(message: str, limit: int = 500) -> str:
    text = SECRET_PATTERN.sub(r"\1=[REDACTED]", str(message))
    text = re.sub(r"(?:[A-Za-z]:\\|/)[^\s:]+", "[PATH]", text)
    return text.replace("\r", " ").replace("\n", " ")[:limit]


def _validate_local_source(path: Path) -> Path:
    if path.is_symlink():
        raise SafetyError(f"拒绝输入符号链接: {path.name}")
    if not path.exists():
        raise SafetyError(f"输入不存在: {path.name}")
    return path.resolve(strict=True)


def _walk_directory(root: Path, recursive: bool) -> list[Path]:
    result: list[Path] = []
    for current, dir_names, file_names in os.walk(root, followlinks=False):
        current_path = Path(current)
        for name in list(dir_names):
            candidate = current_path / name
            if name in IGNORED_DIRS:
                dir_names.remove(name)
                continue
            if candidate.is_symlink():
                raise SafetyError(f"拒绝目录中的符号链接: {candidate.name}")
            if not recursive and current_path == root:
                dir_names.remove(name)
        for name in sorted(file_names):
            candidate = current_path / name
            if candidate.is_symlink():
                raise SafetyError(f"拒绝目录中的符号链接: {candidate.name}")
            if candidate.suffix.lower() in SUPPORTED_EXTENSIONS:
                result.append(candidate.resolve(strict=True))
        if not recursive:
            break
    return result


def collect_inputs(raw_inputs: list[str], recursive: bool, max_files: int) -> list[InputItem]:
    items: list[InputItem] = []
    seen_sources: set[Path] = set()
    seen_outputs: set[str] = set()
    for raw in raw_inputs:
        if "://" in raw:
            raise SafetyError("拒绝 URL 输入；默认路径只处理本地文件")
        source = _validate_local_source(Path(raw))
        if source.is_file():
            candidates = [(source, Path(source.name).with_suffix(".md"))]
        elif source.is_dir():
            candidates = [
                (child, child.relative_to(source).with_suffix(".md"))
                for child in _walk_directory(source, recursive)
            ]
        else:
            raise SafetyError(f"输入不是普通文件或目录: {source.name}")

        for child, relative in candidates:
            if child.suffix.lower() not in SUPPORTED_EXTENSIONS:
                if source.is_file():
                    raise SafetyError(f"不支持的文件类型: {child.suffix or '(无扩展名)'}")
                continue
            key = relative.as_posix().casefold()
            if key in seen_outputs and child not in seen_sources:
                raise SafetyError(f"多个输入会写入同一输出: {relative.name}")
            if child not in seen_sources:
                seen_sources.add(child)
                seen_outputs.add(key)
                items.append(InputItem(child, relative))
            if len(items) > max_files:
                raise SafetyError(f"输入文件数量超过上限 {max_files}")
    return items


def validate_zip(path: Path, max_entries: int, max_uncompressed: int, max_ratio: float) -> None:
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if len(infos) > max_entries:
            raise SafetyError(f"ZIP 条目数超过上限 {max_entries}")
        total = 0
        for info in infos:
            pure = Path(info.filename)
            if pure.is_absolute() or ".." in pure.parts:
                raise SafetyError("ZIP 包含绝对路径或路径穿越条目")
            if info.flag_bits & 0x1:
                raise SafetyError("拒绝加密 ZIP 条目")
            mode = info.external_attr >> 16
            if stat.S_ISLNK(mode):
                raise SafetyError("拒绝 ZIP 符号链接条目")
            total += info.file_size
            if total > max_uncompressed:
                raise SafetyError(f"ZIP 展开体积超过上限 {max_uncompressed} 字节")
            if info.file_size:
                ratio = info.file_size / max(info.compress_size, 1)
                if ratio > max_ratio:
                    raise SafetyError(f"ZIP 条目压缩比超过上限 {max_ratio:g}")


def validate_inputs(
    items: list[InputItem], max_file_bytes: int, max_zip_entries: int,
    max_zip_uncompressed: int, max_zip_ratio: float,
) -> None:
    for item in items:
        size = item.source.stat().st_size
        if size > max_file_bytes:
            raise SafetyError(f"文件超过单文件大小上限: {item.source.name}")
        if item.source.suffix.lower() in ZIP_CONTAINER_EXTENSIONS:
            validate_zip(item.source, max_zip_entries, max_zip_uncompressed, max_zip_ratio)


def _ensure_output_root(raw: str) -> Path:
    root = Path(raw).expanduser()
    if root.is_symlink():
        raise SafetyError("拒绝输出目录符号链接")
    root.mkdir(parents=True, exist_ok=True)
    if not root.is_dir():
        raise SafetyError("输出根不是目录")
    return root.resolve(strict=True)


def output_path(root: Path, relative: Path) -> Path:
    current = root
    for part in relative.parent.parts:
        current = current / part
        if current.is_symlink():
            raise SafetyError("拒绝输出路径中的符号链接")
        current.mkdir(exist_ok=True)
        if not current.is_dir():
            raise SafetyError("输出路径包含非目录对象")
    target = current / relative.name
    if not _is_relative_to(target.resolve(strict=False), root):
        raise SafetyError("输出路径越过输出根")
    if target.is_symlink():
        raise SafetyError("拒绝输出文件符号链接")
    return target


def detect_backend(items: list[InputItem], explicit: str | None = None) -> Path:
    # 用虚拟环境的 python 运行本脚本时，同一 bin/ 目录下的 markitdown 与当前解释器同源，视为可信。
    sibling = Path(sys.executable).parent / "markitdown"
    if not explicit and sibling.is_file() and os.access(sibling, os.X_OK):
        return sibling.resolve(strict=True)
    candidate = explicit or shutil.which("markitdown")
    if not candidate:
        raise SafetyError("未找到 markitdown CLI；请先按 SKILL.md 在 Linux 会话中安装")
    path = Path(candidate).expanduser().resolve(strict=True)
    if not path.is_file() or not os.access(path, os.X_OK):
        raise SafetyError("markitdown 后端不是可执行普通文件")
    cwd = Path.cwd().resolve()
    if _is_relative_to(path, cwd):
        raise SafetyError("拒绝从当前工作目录加载 markitdown 后端")
    for item in items:
        if _is_relative_to(path, item.source.parent):
            raise SafetyError("拒绝从输入目录加载 markitdown 后端")
    return path


def convert_one(cli: Path, source: Path, timeout: int) -> str:
    safe_env = {
        "PATH": os.environ.get("PATH", ""),
        "HOME": os.environ.get("HOME", ""),
        "LANG": os.environ.get("LANG", "C.UTF-8"),
        "LC_ALL": os.environ.get("LC_ALL", ""),
        "NO_PROXY": "*",
        "no_proxy": "*",
    }
    result = subprocess.run(
        [str(cli), str(source)], capture_output=True, text=True, check=False,
        timeout=timeout, env=safe_env,
    )
    if result.returncode != 0:
        raise RuntimeError(f"markitdown CLI 退出码 {result.returncode}: {_redact_message(result.stderr)}")
    return result.stdout


def write_output(path: Path, text: str, force: bool) -> bool:
    if path.is_symlink():
        raise SafetyError("拒绝输出文件符号链接")
    if not force:
        try:
            with path.open("x", encoding="utf-8") as handle:
                handle.write(text)
            return True
        except FileExistsError:
            return False
    if path.exists() and not path.is_file():
        raise SafetyError("输出目标不是普通文件")
    fd, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        if path.is_symlink():
            raise SafetyError("拒绝输出文件符号链接")
        os.replace(temp_name, path)
        return True
    except Exception:
        try:
            os.unlink(temp_name)
        except FileNotFoundError:
            pass
        raise


def append_log(path: Path, record: dict) -> None:
    if path.is_symlink():
        raise SafetyError("拒绝日志文件符号链接")
    path.parent.mkdir(parents=True, exist_ok=True)
    flags = os.O_WRONLY | os.O_CREAT | os.O_APPEND
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(path, flags, 0o600)
    with os.fdopen(fd, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, ensure_ascii=False) + "\n")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="安全批量调用 MarkItDown CLI")
    parser.add_argument("-i", "--input", nargs="+", required=True, help="本地输入文件或目录")
    parser.add_argument("-o", "--output-dir", required=True, help="Markdown 输出根目录")
    parser.add_argument("-r", "--recursive", action="store_true", help="递归扫描目录")
    parser.add_argument("--force", action="store_true", help="替换已有普通 Markdown 文件")
    parser.add_argument("--log", help="脱敏 JSONL 失败日志")
    parser.add_argument("--backend", help=argparse.SUPPRESS)
    parser.add_argument("--max-files", type=int, default=DEFAULT_MAX_FILES)
    parser.add_argument("--max-file-bytes", type=int, default=DEFAULT_MAX_FILE_BYTES)
    parser.add_argument("--max-zip-entries", type=int, default=DEFAULT_MAX_ZIP_ENTRIES)
    parser.add_argument("--max-zip-uncompressed-bytes", type=int, default=DEFAULT_MAX_ZIP_UNCOMPRESSED_BYTES)
    parser.add_argument("--max-zip-ratio", type=float, default=DEFAULT_MAX_ZIP_RATIO)
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if min(args.max_files, args.max_file_bytes, args.max_zip_entries,
               args.max_zip_uncompressed_bytes, args.timeout) <= 0 or args.max_zip_ratio <= 0:
            raise SafetyError("所有资源上限必须为正数")
        items = collect_inputs(args.input, args.recursive, args.max_files)
        if not items:
            raise SafetyError("没有找到可转换文件")
        validate_inputs(
            items, args.max_file_bytes, args.max_zip_entries,
            args.max_zip_uncompressed_bytes, args.max_zip_ratio,
        )
        root = _ensure_output_root(args.output_dir)
        targets = [(item, output_path(root, item.relative_output)) for item in items]
        if not args.force:
            existing = [target.name for _, target in targets if target.exists()]
            if existing:
                raise SafetyError(f"已有输出，未授权覆盖: {', '.join(existing[:5])}")
        cli = detect_backend(items, args.backend)
    except (OSError, SafetyError, zipfile.BadZipFile) as exc:
        print(f"[error] {_redact_message(exc)}", file=sys.stderr)
        return 2

    succeeded = 0
    failed = 0
    for item, target in targets:
        try:
            text = convert_one(cli, item.source, args.timeout)
            if not write_output(target, text, args.force):
                raise SafetyError("输出已存在且未授权覆盖")
            succeeded += 1
            print(f"[ok] {item.source.name} -> {item.relative_output.as_posix()}")
        except Exception as exc:  # 单文件失败不终止其余批次
            failed += 1
            message = _redact_message(exc)
            print(f"[fail] {item.source.name}: {message}", file=sys.stderr)
            if args.log:
                append_log(Path(args.log), {
                    "timestamp": datetime.now(timezone.utc).isoformat(),
                    "input_name": item.source.name,
                    "input_id": _path_id(item.source),
                    "error_type": type(exc).__name__,
                    "error": message,
                })

    print(f"汇总：成功 {succeeded}/{len(items)}，失败 {failed}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
