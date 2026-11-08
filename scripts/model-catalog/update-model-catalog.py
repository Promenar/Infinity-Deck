#!/usr/bin/env python3
"""模型目录快照下载与离线验收脚本（纯标准库）。"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import tempfile
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence


SOURCE_URL = "https://models.dev/models.json"
MANIFEST_SOURCE = SOURCE_URL
LICENSE = "MIT"
SCHEMA_VERSION = 2
INT_MAX_VALUE = 2_147_483_647
KNOWN_MODALITIES = {"text", "image", "audio", "video", "pdf"}
KNOWN_SOURCE_FIELDS = {
    "id",
    "name",
    "reasoning",
    "attachment",
    "tool_call",
    "structured_output",
    "knowledge",
    "release_date",
    "last_updated",
    "limit",
    "modalities",
    "family",
    "status",
}
KNOWN_LIMIT_FIELDS = {"context", "input", "output"}
USER_AGENT = "InfinityModelCatalogUpdater/1.0 (+https://github.com/Promenar/Infinity-Deck)"
ALLOWED_RECORD_FIELDS = {
    "canonicalModelId",
    "displayName",
    "family",
    "reasoning",
    "attachment",
    "tool_call",
    "structured_output",
    "knowledge",
    "release_date",
    "last_updated",
    "contextTokens",
    "inputTokens",
    "outputTokens",
    "modalities",
    "status",
}
REQUIRED_RECORD_FIELDS = {
    "canonicalModelId",
    "displayName",
    "family",
    "reasoning",
    "attachment",
    "tool_call",
    "structured_output",
    "knowledge",
    "release_date",
    "last_updated",
    "modalities",
}
REQUIRED_MANIFEST_FIELDS = {
    "source",
    "license",
    "fetchedAt",
    "sourceBytes",
    "sourceSha256",
    "catalogSha256",
    "recordCount",
    "schemaVersion",
    "unknownFieldCount",
}
MANIFEST_FETCHED_AT_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
DIFF_KEYS = [
    "added",
    "removed",
    "renamed",
    "reasoning_changed",
    "context_changed",
    "output_changed",
    "deprecated_changed",
    "exact_id_conflicts",
    "overrides_covered_by_upstream",
    "total_record_count",
    "unknown_field_count",
]
ExactIdConflicts = dict[str, list[str]]


class ModelCatalogError(RuntimeError):
    pass


def _ensure_mapping(name: str, value: Any) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ModelCatalogError(f"{name} 必须为 object 格式")
    return value


def _ensure_str(name: str, value: Any, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str):
        raise ModelCatalogError(f"{name} 必须为字符串")
    if not allow_empty and not value.strip():
        raise ModelCatalogError(f"{name} 不能为空")
    return value


def _ensure_bool(name: str, value: Any) -> bool | None:
    if value is None:
        return None
    if not isinstance(value, bool):
        raise ModelCatalogError(f"{name} 必须为布尔值")
    return value


def _ensure_text_or_none(name: str, value: Any) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ModelCatalogError(f"{name} 必须为字符串或空")
    return value


def _ensure_non_negative_int(name: str, value: Any) -> int | None:
    if value is None:
        return None
    if isinstance(value, bool):
        raise ModelCatalogError(f"{name} 必须为整数")
    if isinstance(value, int):
        pass
    elif isinstance(value, float):
        if math.isnan(value) or math.isinf(value):
            raise ModelCatalogError(f"{name} 不能为 NaN")
        raise ModelCatalogError(f"{name} 必须为整数")
    else:
        raise ModelCatalogError(f"{name} 必须为整数")
    if value < 0:
        raise ModelCatalogError(f"{name} 不能为负数")
    if value > INT_MAX_VALUE:
        raise ModelCatalogError(f"{name} 超过 Int.MAX_VALUE")
    return value


def _ensure_modalities(source: Any) -> dict[str, list[str]]:
    modalities = _ensure_mapping("modalities", source)
    if set(modalities.keys()) != {"input", "output"}:
        extra = set(modalities.keys()) - {"input", "output"}
        if extra:
            raise ModelCatalogError(f"modalities 只允许 input/output，发现额外字段: {sorted(extra)!r}")
        raise ModelCatalogError("modalities 必须包含 input/output")
    input_modalities = modalities.get("input")
    output_modalities = modalities.get("output")
    if not isinstance(input_modalities, list) or not isinstance(output_modalities, list):
        raise ModelCatalogError("modalities 必须包含 input/output 数组")

    result: dict[str, list[str]] = {}
    for key, value, output_field in (
        ("输入模态", input_modalities, "input"),
        ("输出模态", output_modalities, "output"),
    ):
        normalized: list[str] = []
        for modality in value:
            if not isinstance(modality, str):
                raise ModelCatalogError(f"{key}包含非字符串")
            if modality not in KNOWN_MODALITIES:
                raise ModelCatalogError(f"{key}出现非法 modality: {modality}")
            normalized.append(modality)
        result[output_field] = normalized
    return result


def _ensure_limit(value: Any) -> dict[str, int]:
    limit = _ensure_mapping("limit", value)
    context_tokens = _ensure_non_negative_int("contextTokens", limit.get("context"))
    input_tokens = _ensure_non_negative_int("inputTokens", limit.get("input"))
    output_tokens = _ensure_non_negative_int("outputTokens", limit.get("output"))
    result: dict[str, int] = {}
    if context_tokens is not None:
        result["contextTokens"] = context_tokens
    if input_tokens is not None:
        result["inputTokens"] = input_tokens
    if output_tokens is not None:
        result["outputTokens"] = output_tokens
    return result


def normalize_remote_model_id(raw_canonical: str) -> str:
    canonical = raw_canonical.strip()
    if "::" in canonical:
        canonical = canonical.split("::", 1)[1]
    canonical = canonical.removeprefix("models/")
    return canonical.lower()


def runtime_short_alias(raw_canonical: str) -> str:
    canonical = _ensure_str("canonicalModelId", raw_canonical, allow_empty=False)
    _, _, remainder = canonical.partition("/")
    return remainder if remainder else canonical


_canonicalize_catalog_id = normalize_remote_model_id


def _normalize_source_record(
    source_record: Mapping[str, Any],
) -> tuple[dict[str, Any], int]:
    unknown_count = 0
    for field in source_record:
        if field not in KNOWN_SOURCE_FIELDS:
            unknown_count += 1

    canonical = _ensure_str("canonicalModelId", source_record.get("id"), allow_empty=False)
    display_name = _ensure_str("displayName", source_record.get("name"), allow_empty=False)

    limit_raw = source_record.get("limit", {})
    limit = _ensure_limit(limit_raw)
    for field in _ensure_mapping("limit", limit_raw):
        if field not in KNOWN_LIMIT_FIELDS:
            unknown_count += 1

    modalities = _ensure_modalities(source_record.get("modalities", {"input": [], "output": []}))
    status = _ensure_text_or_none("status", source_record.get("status"))
    knowledge = _ensure_text_or_none("knowledge", source_record.get("knowledge"))
    family = _ensure_text_or_none("family", source_record.get("family"))

    record: dict[str, Any] = {
        "canonicalModelId": canonical,
        "displayName": display_name,
        "family": family,
        "reasoning": _ensure_bool("reasoning", source_record.get("reasoning")),
        "attachment": _ensure_bool("attachment", source_record.get("attachment")),
        "tool_call": _ensure_bool("tool_call", source_record.get("tool_call")),
        "structured_output": _ensure_bool("structured_output", source_record.get("structured_output")),
        "knowledge": knowledge,
        "release_date": _ensure_text_or_none("release_date", source_record.get("release_date")),
        "last_updated": _ensure_text_or_none("last_updated", source_record.get("last_updated")),
        "modalities": modalities,
    }
    record.update(limit)
    if status is not None:
        record["status"] = status
    return record, unknown_count


def normalize_catalog_with_stats(source: Any) -> tuple[list[dict[str, Any]], int]:
    source_obj = _ensure_mapping("models.json", source)
    records: list[dict[str, Any]] = []
    seen_raw: set[str] = set()
    seen_normalized: set[str] = set()
    unknown_field_count = 0

    for _key, raw_value in source_obj.items():
        source_record = _ensure_mapping("provider记录", raw_value)
        canonical = _ensure_str("canonicalModelId", source_record.get("id"), allow_empty=False)
        if canonical in seen_raw:
            raise ModelCatalogError(f"重复的 canonicalModelId: {canonical}")
        seen_raw.add(canonical)
        canonicalized = _canonicalize_catalog_id(canonical)
        if canonicalized in seen_normalized:
            raise ModelCatalogError(f"canonicalModelId 归一化后重复: {canonical}")
        seen_normalized.add(canonicalized)

        normalized_record, unknown_count = _normalize_source_record(source_record)
        unknown_field_count += unknown_count
        records.append(normalized_record)

    records.sort(key=lambda item: item["canonicalModelId"])
    return records, unknown_field_count


def _build_exact_id_conflict_map(records: Sequence[Mapping[str, Any]]) -> dict[str, set[str]]:
    index: dict[str, set[str]] = {}
    for record in records:
        canonical = _ensure_str("canonicalModelId", record.get("canonicalModelId"), allow_empty=False)
        for normalized_id in (
            _canonicalize_catalog_id(canonical),
            _canonicalize_catalog_id(runtime_short_alias(canonical)),
        ):
            index.setdefault(normalized_id, set()).add(canonical)
    return index


def _normalize_catalog_for_update(source: Any) -> tuple[list[dict[str, Any]], int, ExactIdConflicts]:
    source_obj = _ensure_mapping("models.json", source)
    records: list[dict[str, Any]] = []
    seen_raw: set[str] = set()
    unknown_field_count = 0

    for _key, raw_value in source_obj.items():
        source_record = _ensure_mapping("provider记录", raw_value)
        canonical = _ensure_str("canonicalModelId", source_record.get("id"), allow_empty=False)
        if canonical in seen_raw:
            raise ModelCatalogError(f"重复的 canonicalModelId: {canonical}")
        seen_raw.add(canonical)

        normalized_record, unknown_count = _normalize_source_record(source_record)
        unknown_field_count += unknown_count
        records.append(normalized_record)

    records.sort(key=lambda item: item["canonicalModelId"])
    exact_id_conflicts_map = detect_exact_id_conflicts(records)
    return records, unknown_field_count, exact_id_conflicts_map


def detect_exact_id_conflicts(records: Sequence[Mapping[str, Any]]) -> ExactIdConflicts:
    conflicts = _build_exact_id_conflict_map(records)
    return {
        normalized_id: sorted_canonicals
        for normalized_id, sorted_canonicals in sorted(
            ((k, sorted(v)) for k, v in conflicts.items() if len(v) > 1),
            key=lambda item: item[0],
        )
    }


def validate_normalized_record(record: Mapping[str, Any]) -> None:
    if not isinstance(record, Mapping):
        raise ModelCatalogError("模型目录记录必须为 object")
    if any(field not in ALLOWED_RECORD_FIELDS for field in record):
        extra = next(field for field in record if field not in ALLOWED_RECORD_FIELDS)
        raise ModelCatalogError(f"发现非法字段: {extra}")
    for name in REQUIRED_RECORD_FIELDS:
        if name not in record:
            raise ModelCatalogError(f"缺少必填字段: {name}")

    _ensure_str("canonicalModelId", record.get("canonicalModelId"), allow_empty=False)
    _ensure_str("displayName", record.get("displayName"), allow_empty=False)
    for name in ("family", "release_date", "last_updated", "knowledge", "status"):
        if record.get(name) is not None and not isinstance(record[name], str):
            raise ModelCatalogError(f"{name} 必须为字符串或空")

    _ensure_non_negative_int("contextTokens", record.get("contextTokens"))
    _ensure_non_negative_int("inputTokens", record.get("inputTokens"))
    _ensure_non_negative_int("outputTokens", record.get("outputTokens"))
    _ensure_modalities(record.get("modalities"))

    for key in ("reasoning", "attachment", "tool_call", "structured_output"):
        _ensure_bool(key, record[key])


def _canonical_json_bytes(payload: Any) -> bytes:
    return (json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def _load_json_bytes(payload: bytes) -> Any:
    def _no_duplicate_object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ModelCatalogError(f"JSON 包含重复键: {key}")
            result[key] = value
        return result

    try:
        return json.loads(
            payload.decode("utf-8"),
            object_pairs_hook=_no_duplicate_object_pairs,
            parse_constant=_reject_invalid_json_constant,
        )
    except json.JSONDecodeError as error:
        raise ModelCatalogError(f"models.json 非法 JSON: {error}") from error


def _reject_invalid_json_constant(text: str) -> None:
    raise ModelCatalogError(f"JSON 包含非法常量: {text}")


def _fsync_file(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _should_fsync_directory() -> bool:
    return os.name != "nt"


def write_deterministic_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path: Path | None = None
    data = _canonical_json_bytes(payload)
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as handle:
            tmp_path = Path(handle.name)
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_path, path)
        if _should_fsync_directory():
            _fsync_file(path.parent)
    finally:
        if tmp_path is not None and tmp_path.exists():
            tmp_path.unlink(missing_ok=True)


def _write_text_atomic(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path: Path | None = None
    data = content.encode("utf-8")
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as handle:
            tmp_path = Path(handle.name)
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_path, path)
        if _should_fsync_directory():
            _fsync_file(path.parent)
    finally:
        if tmp_path is not None and tmp_path.exists():
            tmp_path.unlink(missing_ok=True)


def _write_payload_to_temp_file(parent: Path, filename: str, payload: bytes) -> Path:
    parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="wb",
        dir=parent,
        prefix=f".{filename}.",
        suffix=".tmp",
        delete=False,
    ) as handle:
        handle.write(payload)
        handle.flush()
        os.fsync(handle.fileno())
        return Path(handle.name)


def _replace_path_with_fsync(tmp_path: Path, target_path: Path) -> None:
    os.replace(tmp_path, target_path)
    if _should_fsync_directory():
        _fsync_file(target_path.parent)


def _restore_path(path: Path, previous_payload: bytes | None) -> None:
    if previous_payload is None:
        if path.exists():
            path.unlink()
            if _should_fsync_directory():
                _fsync_file(path.parent)
        return

    tmp_path = _write_payload_to_temp_file(path.parent, path.name, previous_payload)
    try:
        _replace_path_with_fsync(tmp_path, path)
    finally:
        if tmp_path.exists():
            tmp_path.unlink(missing_ok=True)


def _write_catalog_and_manifest_atomically(
    snapshot_path: Path,
    manifest_path: Path,
    snapshot_payload: bytes,
    manifest_payload: bytes,
) -> None:
    snapshot_tmp_path: Path | None = None
    manifest_tmp_path: Path | None = None
    old_snapshot: bytes | None = None
    old_manifest: bytes | None = None
    snapshot_replaced = False
    manifest_replaced = False

    try:
        if snapshot_path.exists():
            old_snapshot = snapshot_path.read_bytes()
        if manifest_path.exists():
            old_manifest = manifest_path.read_bytes()

        snapshot_tmp_path = _write_payload_to_temp_file(snapshot_path.parent, snapshot_path.name, snapshot_payload)
        manifest_tmp_path = _write_payload_to_temp_file(manifest_path.parent, manifest_path.name, manifest_payload)

        os.replace(snapshot_tmp_path, snapshot_path)
        snapshot_replaced = True
        if _should_fsync_directory():
            _fsync_file(snapshot_path.parent)

        os.replace(manifest_tmp_path, manifest_path)
        manifest_replaced = True
        if _should_fsync_directory():
            _fsync_file(manifest_path.parent)
    except Exception as write_error:
        rollback_failures: list[str] = []
        for label, path, previous_payload, replaced in (
            ("snapshot", snapshot_path, old_snapshot, snapshot_replaced),
            ("manifest", manifest_path, old_manifest, manifest_replaced),
        ):
            if not replaced:
                continue
            try:
                _restore_path(path, previous_payload)
            except Exception as rollback_error:
                rollback_failures.append(
                    f"{label}={rollback_error.__class__.__name__}"
                )
        if rollback_failures:
            raise ModelCatalogError(
                "事务写入失败，回滚持久化确认失败: "
                + ", ".join(rollback_failures)
            ) from write_error
        raise
    finally:
        if snapshot_tmp_path is not None and snapshot_tmp_path.exists():
            snapshot_tmp_path.unlink(missing_ok=True)
        if manifest_tmp_path is not None and manifest_tmp_path.exists():
            manifest_tmp_path.unlink(missing_ok=True)


def sha256_hex(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _fetch_source(url: str) -> tuple[bytes, str]:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = response.read()
    return payload, sha256_hex(payload)


def _utc_now_iso() -> str:
    return datetime.now(tz=timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _assert_utc_timestamp(value: Any) -> None:
    if not isinstance(value, str) or not MANIFEST_FETCHED_AT_RE.fullmatch(value):
        raise ModelCatalogError("manifest.fetchedAt 必须为 UTC 时间，形如 2026-07-17T00:00:00Z")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ModelCatalogError("manifest.fetchedAt 不是合法 UTC 时间") from error
    if parsed.tzinfo != timezone.utc:
        raise ModelCatalogError("manifest.fetchedAt 不是合法 UTC 时间")


def _assert_sha256_hex(value: Any, *, context: str) -> None:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ModelCatalogError(f"{context} 必须是小写 hex 的 sha256 值")


def _decode_kotlin_quoted_string(raw_value: str) -> str:
    return bytes(raw_value, "utf-8").decode("unicode_escape")


def _is_identifier_char(char: str) -> bool:
    return char.isalnum() or char == "_"


def _is_word_boundary(source: str, index: int) -> bool:
    if index <= 0:
        return True
    if _is_identifier_char(source[index - 1]):
        return False
    if index + len("exactModel") >= len(source):
        return index + len("exactModel") == len(source)
    if _is_identifier_char(source[index + len("exactModel")]):
        return False
    return True


def _previous_identifier(source: str, index: int) -> str:
    cursor = index - 1
    while cursor >= 0 and source[cursor].isspace():
        cursor -= 1
    if cursor < 0:
        return ""
    end = cursor + 1
    while cursor >= 0 and _is_identifier_char(source[cursor]):
        cursor -= 1
    return source[cursor + 1 : end]


def _is_exact_model_declaration(source: str, index: int) -> bool:
    return _previous_identifier(source, index) == "fun"


def _read_quoted_kotlin_string(source: str, start: int) -> str:
    if source[start] != '"':
        raise ModelCatalogError("字符串字面量不合法")

    cursor = start + 1
    raw_chars: list[str] = []
    while cursor < len(source):
        char = source[cursor]
        if char == "\\":
            if cursor + 1 >= len(source):
                raise ModelCatalogError("字符串字面量不完整")
            raw_chars.append(source[cursor])
            raw_chars.append(source[cursor + 1])
            cursor += 2
            continue
        if char == '"':
            return "".join(raw_chars)
        raw_chars.append(char)
        cursor += 1

    raise ModelCatalogError("字符串字面量未关闭")


def _read_raw_kotlin_string(source: str, start: int) -> str:
    if source[start : start + 3] != '"""':
        raise ModelCatalogError("raw 字符串字面量不合法")

    cursor = start + 3
    end = source.find('"""', cursor)
    if end < 0:
        raise ModelCatalogError("raw 字符串字面量未关闭")
    return source[cursor:end]


def _extract_kotlin_string_arg(block: str, name: str) -> str | None:
    def skip_line_comment(cursor: int) -> int:
        newline = block.find("\n", cursor + 2)
        return len(block) if newline < 0 else newline + 1

    def skip_block_comment(cursor: int) -> int:
        depth = 1
        cursor += 2
        while cursor < len(block) and depth > 0:
            if block.startswith("/*", cursor):
                depth += 1
                cursor += 2
            elif block.startswith("*/", cursor):
                depth -= 1
                cursor += 2
            else:
                cursor += 1
        if depth:
            raise ModelCatalogError("Kotlin 块注释未关闭")
        return cursor

    def skip_quoted_string(cursor: int) -> int:
        cursor += 1
        while cursor < len(block):
            if block[cursor] == "\\":
                cursor += 2
            elif block[cursor] == '"':
                return cursor + 1
            else:
                cursor += 1
        raise ModelCatalogError("字符串字面量未关闭")

    def skip_raw_string(cursor: int) -> int:
        end = block.find('"""', cursor + 3)
        if end < 0:
            raise ModelCatalogError("raw 字符串字面量未关闭")
        return end + 3

    def skip_char_literal(cursor: int) -> int:
        cursor += 1
        while cursor < len(block):
            if block[cursor] == "\\":
                cursor += 2
            elif block[cursor] == "'":
                return cursor + 1
            else:
                cursor += 1
        raise ModelCatalogError("字符字面量未关闭")

    def skip_trivia(cursor: int) -> int:
        while cursor < len(block):
            if block[cursor].isspace():
                cursor += 1
            elif block.startswith("//", cursor):
                cursor = skip_line_comment(cursor)
            elif block.startswith("/*", cursor):
                cursor = skip_block_comment(cursor)
            else:
                break
        return cursor

    cursor = 0
    paren_depth = 0
    bracket_depth = 0
    brace_depth = 0
    while cursor < len(block):
        if block.startswith("//", cursor):
            cursor = skip_line_comment(cursor)
            continue
        if block.startswith("/*", cursor):
            cursor = skip_block_comment(cursor)
            continue
        if block.startswith('"""', cursor):
            cursor = skip_raw_string(cursor)
            continue
        if block[cursor] == '"':
            cursor = skip_quoted_string(cursor)
            continue
        if block[cursor] == "'":
            cursor = skip_char_literal(cursor)
            continue

        char = block[cursor]
        if char == "(":
            paren_depth += 1
        elif char == ")":
            paren_depth = max(0, paren_depth - 1)
        elif char == "[":
            bracket_depth += 1
        elif char == "]":
            bracket_depth = max(0, bracket_depth - 1)
        elif char == "{":
            brace_depth += 1
        elif char == "}":
            brace_depth = max(0, brace_depth - 1)
        elif (
            paren_depth == 0
            and bracket_depth == 0
            and brace_depth == 0
            and block.startswith(name, cursor)
            and (cursor == 0 or not _is_identifier_char(block[cursor - 1]))
            and (
                cursor + len(name) == len(block)
                or not _is_identifier_char(block[cursor + len(name)])
            )
        ):
            value_cursor = skip_trivia(cursor + len(name))
            if value_cursor >= len(block) or block[value_cursor] != "=":
                cursor += len(name)
                continue
            value_cursor = skip_trivia(value_cursor + 1)
            if value_cursor >= len(block):
                raise ModelCatalogError(f"{name} 参数解析不完整")
            if block.startswith('"""', value_cursor):
                return _read_raw_kotlin_string(block, value_cursor)
            if block[value_cursor] == '"':
                return _decode_kotlin_quoted_string(
                    _read_quoted_kotlin_string(block, value_cursor)
                )
            raise ModelCatalogError(f"{name} 不是字符串字面量")

        cursor += 1

    return None


def _iter_exact_model_blocks(source: str) -> list[str]:
    blocks: list[str] = []
    length = len(source)
    index = 0

    in_line_comment = False
    in_block_comment = 0
    in_string = False
    in_raw_string = False
    in_char = False
    in_string_escape = False
    in_char_escape = False

    while index < length:
        char = source[index]

        if in_line_comment:
            if char == "\n":
                in_line_comment = False
            index += 1
            continue

        if in_raw_string:
            if source.startswith('"""', index):
                in_raw_string = False
                index += 3
            else:
                index += 1
            continue

        if in_block_comment > 0:
            if source.startswith("/*", index):
                in_block_comment += 1
                index += 2
                continue
            if source.startswith("*/", index):
                in_block_comment -= 1
                index += 2
                continue
            index += 1
            continue

        if in_char:
            if in_char_escape:
                in_char_escape = False
                index += 1
                continue
            if char == "\\":
                in_char_escape = True
                index += 1
                continue
            if char == "'":
                in_char = False
            index += 1
            continue

        if in_string:
            if in_string_escape:
                in_string_escape = False
                index += 1
                continue
            if char == "\\":
                in_string_escape = True
                index += 1
                continue
            if char == '"':
                in_string = False
            index += 1
            continue

        if char == "/" and index + 1 < length:
            pair = source[index : index + 2]
            if pair == "//":
                in_line_comment = True
                index += 2
                continue
            if pair == "/*":
                in_block_comment = 1
                index += 2
                continue

        if char == "'" and (
            index == 0 or not _is_identifier_char(source[index - 1])
        ):
            in_char = True
            index += 1
            continue

        if source.startswith('"""', index):
            in_raw_string = True
            index += 3
            continue

        if char == '"':
            in_string = True
            index += 1
            continue

        if source.startswith("exactModel", index) and _is_word_boundary(source, index):
            if not _is_exact_model_declaration(source, index):
                open_paren = source.find("(", index + len("exactModel"))
                if open_paren >= 0:
                    depth = 0
                    inner_index = open_paren + 1
                    inner_line_comment = False
                    inner_block_comment = 0
                    inner_string = False
                    inner_raw_string = False
                    inner_char = False
                    inner_string_escape = False
                    inner_char_escape = False
                    while inner_index < length:
                        inner_char_value = source[inner_index]
                        if inner_line_comment:
                            if inner_char_value == "\n":
                                inner_line_comment = False
                            inner_index += 1
                            continue
                        if inner_raw_string:
                            if source.startswith('"""', inner_index):
                                inner_raw_string = False
                                inner_index += 3
                            else:
                                inner_index += 1
                            continue
                        if inner_block_comment > 0:
                            if source.startswith("/*", inner_index):
                                inner_block_comment += 1
                                inner_index += 2
                                continue
                            if source.startswith("*/", inner_index):
                                inner_block_comment -= 1
                                inner_index += 2
                                continue
                            inner_index += 1
                            continue
                        if inner_char:
                            if inner_char_escape:
                                inner_char_escape = False
                                inner_index += 1
                                continue
                            if inner_char_value == "\\":
                                inner_char_escape = True
                                inner_index += 1
                                continue
                            if inner_char_value == "'":
                                inner_char = False
                            inner_index += 1
                            continue
                        if inner_string:
                            if inner_string_escape:
                                inner_string_escape = False
                                inner_index += 1
                                continue
                            if inner_char_value == "\\":
                                inner_string_escape = True
                                inner_index += 1
                                continue
                            if inner_char_value == '"':
                                inner_string = False
                            inner_index += 1
                            continue
                        if inner_char_value == "/" and inner_index + 1 < length:
                            pair = source[inner_index : inner_index + 2]
                            if pair == "//":
                                inner_line_comment = True
                                inner_index += 2
                                continue
                            if pair == "/*":
                                inner_block_comment = 1
                                inner_index += 2
                                continue
                        if inner_char_value == "'" and (
                            inner_index == 0 or not _is_identifier_char(source[inner_index - 1])
                        ):
                            inner_char = True
                            inner_index += 1
                            continue
                        if source.startswith('"""', inner_index):
                            inner_raw_string = True
                            inner_index += 3
                            continue
                        if inner_char_value == '"':
                            inner_string = True
                            inner_index += 1
                            continue
                        if inner_char_value == "(":
                            depth += 1
                        elif inner_char_value == ")":
                            if depth == 0:
                                blocks.append(source[open_paren + 1 : inner_index])
                                break
                            depth -= 1
                        inner_index += 1
                    index = inner_index + 1
                    continue
            index += len("exactModel")
            continue

        index += 1

    return blocks


def parse_infinity_overrides(source_path: Path) -> list[tuple[str, str]]:
    raw = source_path.read_text(encoding="utf-8")
    overrides: list[tuple[str, str]] = []

    for block in _iter_exact_model_blocks(raw):
        exact_alias = _extract_kotlin_string_arg(block, "exactAlias")
        if exact_alias is None:
            continue
        canonical_id = _extract_kotlin_string_arg(block, "canonicalId") or exact_alias
        overrides.append((exact_alias, canonical_id))

    if not overrides:
        raise ModelCatalogError("未解析到任意 exactAlias")
    return overrides


def _build_upstream_alias_index(records: Sequence[Mapping[str, Any]]) -> set[str]:
    aliases: set[str] = set()
    for record in records:
        canonical = _ensure_str("canonicalModelId", record.get("canonicalModelId"), allow_empty=False)
        aliases.add(_canonicalize_catalog_id(canonical))
        aliases.add(_canonicalize_catalog_id(runtime_short_alias(canonical)))
    return aliases


def count_overrides_covered_by_upstream(
    records: Sequence[Mapping[str, Any]],
    overrides: Sequence[tuple[str, str]],
) -> int:
    upstream_aliases = _build_upstream_alias_index(records)
    covered = 0
    for exact_alias, canonical_id in overrides:
        alias_set = {
            _canonicalize_catalog_id(exact_alias),
            _canonicalize_catalog_id(canonical_id),
        }
        if alias_set & upstream_aliases:
            covered += 1
    return covered


def generate_model_catalog_diff(
    base_records: Sequence[Mapping[str, Any]],
    current_records: Sequence[Mapping[str, Any]],
    *,
    unknown_field_count: int,
    exact_id_conflicts: ExactIdConflicts | None = None,
    overrides_covered_by_upstream: int = 0,
) -> dict[str, Any]:
    resolved_exact_id_conflicts = exact_id_conflicts or {}
    base_map = {record["canonicalModelId"]: record for record in base_records}
    current_map = {record["canonicalModelId"]: record for record in current_records}

    base_ids = set(base_map)
    current_ids = set(current_map)
    shared = sorted(base_ids & current_ids)

    added = sorted(current_ids - base_ids)
    removed = sorted(base_ids - current_ids)
    renamed: list[str] = []
    reasoning_changed: list[str] = []
    context_changed: list[str] = []
    output_changed: list[str] = []
    deprecated_changed: list[str] = []

    for canonical in shared:
        base = base_map[canonical]
        current = current_map[canonical]
        if base["displayName"] != current["displayName"]:
            renamed.append(canonical)
        if base["reasoning"] != current["reasoning"]:
            reasoning_changed.append(canonical)
        if base.get("contextTokens") != current.get("contextTokens"):
            context_changed.append(canonical)
        if base.get("outputTokens") != current.get("outputTokens"):
            output_changed.append(canonical)

        base_deprecated = str(base.get("status", "")).strip().lower() == "deprecated"
        current_deprecated = str(current.get("status", "")).strip().lower() == "deprecated"
        if base_deprecated != current_deprecated:
            deprecated_changed.append(canonical)

    diff: dict[str, Any] = {
        "added": added,
        "removed": removed,
        "renamed": renamed,
        "reasoning_changed": reasoning_changed,
        "context_changed": context_changed,
        "output_changed": output_changed,
        "deprecated_changed": deprecated_changed,
        "exact_id_conflicts": resolved_exact_id_conflicts,
        "overrides_covered_by_upstream": overrides_covered_by_upstream,
        "total_record_count": len(current_records),
        "unknown_field_count": unknown_field_count,
    }
    for key in DIFF_KEYS:
        if key not in diff:
            raise AssertionError(f"diff 缺少必需键: {key}")
    return diff


def render_catalog_diff_markdown(diff: Mapping[str, Any]) -> str:
    lines: list[str] = ["# 模型目录差异报告"]
    for key in DIFF_KEYS:
        if key not in diff:
            raise ModelCatalogError(f"diff 缺少必需键: {key}")
        value = diff[key]
        lines.append(f"## {key}")
        if isinstance(value, list):
            if value:
                for item in sorted(value):
                    lines.append(f"- {item}")
            else:
                lines.append("- (0)")
        elif isinstance(value, dict):
            if not value:
                lines.append("- (0)")
            else:
                for normalized_id in sorted(value):
                    lines.append(f"- {normalized_id}")
                    for canonical in sorted(value[normalized_id]):
                        lines.append(f"  - {canonical}")
        else:
            lines.append(f"- {key}: {value}")
    lines.append("")
    return "\n".join(lines)


def normalize_catalog(source: Any) -> list[dict[str, Any]]:
    records, _ = normalize_catalog_with_stats(source)
    return records


def load_existing_catalog(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    payload = path.read_bytes()
    records = _load_json_bytes(payload)
    if not isinstance(records, list):
        raise ModelCatalogError("模型目录文件必须为数组")
    if payload != _canonical_json_bytes(records):
        raise ModelCatalogError("模型目录文件未使用确定性 JSON 格式（sort_keys/indent/newline）")

    canonicals: list[str] = []
    normalized = set[str]()
    for item in records:
        validate_normalized_record(item)
        canonicals.append(_ensure_str("canonicalModelId", item.get("canonicalModelId"), allow_empty=False))
        normalized_id = _canonicalize_catalog_id(item["canonicalModelId"])
        if normalized_id in normalized:
            raise ModelCatalogError("canonicalModelId 归一化后重复")
        normalized.add(normalized_id)

    if canonicals != sorted(canonicals):
        raise ModelCatalogError("canonicalModelId 未按字典序严格排序")

    return records


def run_update(
    source_url: str,
    output_path: Path,
    manifest_path: Path,
    *,
    diff_report: Path | None = None,
    overrides_source: Path | None = None,
) -> None:
    if source_url != MANIFEST_SOURCE:
        raise ModelCatalogError(
            f"更新来源必须为 {MANIFEST_SOURCE}"
        )
    payload, source_sha = _fetch_source(source_url)
    parsed = _load_json_bytes(payload)
    current_records, unknown_field_count, exact_id_conflicts = _normalize_catalog_for_update(parsed)
    catalog_sha = sha256_hex(_canonical_json_bytes(current_records))

    overrides_covered_by_upstream = 0
    existing_records: list[dict[str, Any]] = []

    if diff_report is not None:
        if overrides_source is None:
            raise ModelCatalogError("生成差异报告时必须同时提供 --overrides-source")
        existing_records = load_existing_catalog(output_path)
        overrides = parse_infinity_overrides(overrides_source)
        overrides_covered_by_upstream = count_overrides_covered_by_upstream(
            current_records,
            overrides,
        )

    manifest = {
        "source": source_url,
        "license": LICENSE,
        "fetchedAt": _utc_now_iso(),
        "sourceBytes": len(payload),
        "sourceSha256": source_sha,
        "catalogSha256": catalog_sha,
        "recordCount": len(current_records),
        "schemaVersion": SCHEMA_VERSION,
        "unknownFieldCount": unknown_field_count,
    }

    if exact_id_conflicts:
        if diff_report is None:
            raise ModelCatalogError(
                f"exact_id_conflicts 非零，阻断更新，不写入新 snapshot/manifest: {exact_id_conflicts}"
            )
        conflict_report = generate_model_catalog_diff(
            existing_records,
            current_records,
            unknown_field_count=unknown_field_count,
            exact_id_conflicts=exact_id_conflicts,
            overrides_covered_by_upstream=overrides_covered_by_upstream,
        )
        _write_text_atomic(diff_report, render_catalog_diff_markdown(conflict_report))
        raise ModelCatalogError(
            f"exact_id_conflicts 非零，阻断更新，不写入新 snapshot/manifest: {exact_id_conflicts}"
        )

    if diff_report is not None:
        diff = generate_model_catalog_diff(
            existing_records,
            current_records,
            unknown_field_count=unknown_field_count,
            exact_id_conflicts=exact_id_conflicts,
            overrides_covered_by_upstream=overrides_covered_by_upstream,
        )
        _write_text_atomic(diff_report, render_catalog_diff_markdown(diff))

    existing_manifest = None
    existing_snapshot = None
    if manifest_path.exists():
        try:
            manifest_payload = manifest_path.read_bytes()
            existing_manifest = _load_json_bytes(manifest_payload)
        except Exception:
            existing_manifest = None
    if output_path.exists():
        try:
            existing_snapshot = output_path.read_bytes()
        except Exception:
            existing_snapshot = None

    manifest_matches = (
        existing_manifest is not None
        and existing_snapshot is not None
        and _canonical_json_bytes(current_records) == existing_snapshot
        and existing_manifest.get("source") == manifest["source"]
        and existing_manifest.get("license") == manifest["license"]
        and existing_manifest.get("schemaVersion") == manifest["schemaVersion"]
        and existing_manifest.get("sourceBytes") == manifest["sourceBytes"]
        and existing_manifest.get("sourceSha256") == manifest["sourceSha256"]
        and existing_manifest.get("catalogSha256") == manifest["catalogSha256"]
        and existing_manifest.get("recordCount") == manifest["recordCount"]
        and existing_manifest.get("unknownFieldCount") == manifest["unknownFieldCount"]
    )

    if manifest_matches:
        return

    _write_catalog_and_manifest_atomically(
        output_path,
        manifest_path,
        _canonical_json_bytes(current_records),
        _canonical_json_bytes(manifest),
    )

def run_check(input_path: Path, manifest_path: Path) -> None:
    manifest_payload = manifest_path.read_bytes()
    manifest = _load_json_bytes(manifest_payload)
    for key in REQUIRED_MANIFEST_FIELDS:
        if key not in manifest:
            raise ModelCatalogError(f"manifest 缺少字段: {key}")
    if any(field not in REQUIRED_MANIFEST_FIELDS for field in manifest):
        extra = next(field for field in manifest if field not in REQUIRED_MANIFEST_FIELDS)
        raise ModelCatalogError(f"manifest 包含非法字段: {extra}")

    if manifest["source"] != MANIFEST_SOURCE:
        raise ModelCatalogError("manifest.source 与预期不一致")
    if manifest["license"] != LICENSE:
        raise ModelCatalogError("manifest.license 非法")
    if (
        not isinstance(manifest["schemaVersion"], int)
        or isinstance(manifest["schemaVersion"], bool)
        or manifest["schemaVersion"] != SCHEMA_VERSION
    ):
        raise ModelCatalogError("manifest.schemaVersion 非法")
    if not isinstance(manifest["recordCount"], int) or isinstance(manifest["recordCount"], bool) or manifest["recordCount"] < 0:
        raise ModelCatalogError("manifest.recordCount 必须为非负整数")
    if not isinstance(manifest["sourceBytes"], int) or isinstance(manifest["sourceBytes"], bool) or manifest["sourceBytes"] < 0:
        raise ModelCatalogError("manifest.sourceBytes 必须为非负整数")
    if not isinstance(manifest["unknownFieldCount"], int) or isinstance(manifest["unknownFieldCount"], bool) or manifest["unknownFieldCount"] < 0:
        raise ModelCatalogError("manifest.unknownFieldCount 必须为非负整数")
    _assert_utc_timestamp(manifest["fetchedAt"])
    _assert_sha256_hex(manifest["sourceSha256"], context="manifest.sourceSha256")
    _assert_sha256_hex(manifest["catalogSha256"], context="manifest.catalogSha256")

    payload = input_path.read_bytes()
    models = _load_json_bytes(payload)
    if not isinstance(models, list):
        raise ModelCatalogError("模型目录文件必须为数组")

    if payload != _canonical_json_bytes(models):
        raise ModelCatalogError("模型目录文件未使用确定性 JSON 格式（sort_keys/indent/newline）")

    expected_manifest_payload = _canonical_json_bytes(
        {
            "source": manifest["source"],
            "license": manifest["license"],
            "fetchedAt": manifest["fetchedAt"],
            "sourceBytes": manifest["sourceBytes"],
            "sourceSha256": manifest["sourceSha256"],
            "catalogSha256": manifest["catalogSha256"],
            "recordCount": manifest["recordCount"],
            "schemaVersion": manifest["schemaVersion"],
            "unknownFieldCount": manifest["unknownFieldCount"],
        }
    )
    if manifest_payload != expected_manifest_payload:
        raise ModelCatalogError("manifest 文件未使用确定性 JSON 格式（sort_keys/indent/newline）")

    canonicals: list[str] = []
    normalized_canonicals: set[str] = set()
    for item in models:
        validate_normalized_record(item)
        canonicals.append(item["canonicalModelId"])
        normalized = _canonicalize_catalog_id(item["canonicalModelId"])
        if normalized in normalized_canonicals:
            raise ModelCatalogError("canonicalModelId 归一化后重复")
        normalized_canonicals.add(normalized)

        if len(canonicals) != len(set(canonicals)):
            duplicate = item["canonicalModelId"]
            raise ModelCatalogError(f"重复的 canonicalModelId: {duplicate}")

    if canonicals != sorted(canonicals):
        raise ModelCatalogError("canonicalModelId 未按字典序严格排序")
    exact_id_conflicts = detect_exact_id_conflicts(models)
    if exact_id_conflicts:
        detail_items = ", ".join(
            f"{canonicalized_key}: {', '.join(exact_id_conflicts[canonicalized_key])}"
            for canonicalized_key in sorted(exact_id_conflicts)
        )
        raise ModelCatalogError(
            f"exact_id_conflicts 非零，离线目录不可加载: [{detail_items}]"
        )
    if len(models) != int(manifest["recordCount"]):
        raise ModelCatalogError("recordCount 与目录条目数不一致")

    if manifest["catalogSha256"] != sha256_hex(payload):
        raise ModelCatalogError("models 快照 SHA-256 与预期不一致")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="更新 models.dev 离线目录快照")
    parser.add_argument("--source-url", default=SOURCE_URL)
    parser.add_argument("--output")
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--input")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--diff-report")
    parser.add_argument("--overrides-source")
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    manifest_path = Path(args.manifest)
    if args.check:
        if args.diff_report or args.overrides_source:
            raise ModelCatalogError("check 模式不支持 --diff-report 或 --overrides-source")
        if not args.input:
            raise ModelCatalogError("--check 模式需要 --input")
        run_check(Path(args.input), manifest_path)
        return

    if not args.output:
        raise ModelCatalogError("更新模式需要 --output")
    if not args.diff_report or not args.overrides_source:
        raise ModelCatalogError(
            "更新模式必须同时提供 --diff-report 与 --overrides-source"
        )
    run_update(
        args.source_url,
        Path(args.output),
        manifest_path,
        diff_report=Path(args.diff_report) if args.diff_report else None,
        overrides_source=Path(args.overrides_source) if args.overrides_source else None,
    )


if __name__ == "__main__":
    try:
        main()
    except ModelCatalogError as error:
        raise SystemExit(str(error))
