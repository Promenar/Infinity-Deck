#!/usr/bin/env python3
"""从公开上游构建并签名 Infinity Deck 独立模型目录。"""

from __future__ import annotations

import argparse
import base64
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Any, Callable
import urllib.error
import urllib.parse
import urllib.request


DEFAULT_SOURCES = {
    "models-dev-models": "https://models.dev/models.json",
    "models-dev-api": "https://models.dev/api.json",
    "litellm": "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json",
    "openrouter": "https://openrouter.ai/api/v1/models?output_modalities=all",
}
DEFAULT_OFFICIAL_OVERRIDES = Path(__file__).resolve().with_name("official-model-overrides.json")
THIRD_PARTY_NOTICES = Path(__file__).resolve().with_name("third-party-notices.txt")
SOURCE_LICENSES = {
    "models-dev-models": "MIT: https://github.com/anomalyco/models.dev/blob/dev/LICENSE",
    "models-dev-api": "MIT: https://github.com/anomalyco/models.dev/blob/dev/LICENSE",
    "litellm": "MIT: https://github.com/BerriAI/litellm/blob/main/LICENSE",
    "openrouter": "OpenRouter Terms of Service: https://openrouter.ai/terms",
}
SOURCE_MAX_BYTES = 32 * 1024 * 1024
CATALOG_MAX_BYTES = 16 * 1024 * 1024
MANIFEST_MAX_BYTES = 64 * 1024
MAX_RECORDS = 30_000
MAX_TOKEN_COUNT = 2_147_483_647
MAX_ID_LENGTH = 512
OFFICIAL_RECORD_FIELDS = {
    "canonicalModelId",
    "displayName",
    "source",
    "exactAliases",
    "family",
    "workload",
    "reasoning",
    "tool_call",
    "structured_output",
    "prompt_caching",
    "web_search",
    "computer_use",
    "contextTokens",
    "inputTokens",
    "outputTokens",
    "knowledge",
    "modalities",
    "release_date",
    "last_updated",
    "deprecation_date",
    "status",
    "evidenceUrl",
    "reviewedAt",
}
WORKLOADS = {
    "GENERATIVE_TEXT",
    "EMBEDDING",
    "RERANK",
    "IMAGE_GENERATION",
    "SPEECH",
    "MUSIC",
    "REALTIME",
    "VIDEO",
    "UNKNOWN",
}
_LIVE_SEGMENT_RE = re.compile(r"(?:^|[-_])live(?:$|[-_])")
_ISO_YEAR_MONTH_RE = re.compile(r"^(20\d{2})-(0[1-9]|1[0-2])(?:-(0[1-9]|[12]\d|3[01]))?$")

# 一方官方厂商作用域优先级：仅这些可信官方作用域参与全局 canonical 合成与缺失字段补全，
# 避免无名转售/第三方代理的脏数据污染全局规范视图。
FIRST_PARTY_VENDOR_SCOPES: dict[str, tuple[str, ...]] = {
    "google": ("google", "gemini", "google-vertex"),
    "openai": ("openai",),
    "anthropic": ("anthropic",),
    "deepseek": ("deepseek",),
    "x-ai": ("xai",),
    "xai": ("xai",),
    "minimax": ("minimax-cn", "minimax", "minimax-cn-coding-plan", "minimax-coding-plan"),
    "z-ai": ("zhipuai-coding-plan", "zhipuai", "zai-coding-plan", "zai"),
    "zai": ("zhipuai-coding-plan", "zhipuai", "zai-coding-plan", "zai"),
    "zhipuai": ("zhipuai-coding-plan", "zhipuai", "zai-coding-plan", "zai"),
    "moonshotai": ("moonshotai-cn", "moonshotai", "kimi-code-plan-cn", "kimi-code-plan-global"),
    "qwen": ("alibaba-coding-plan-cn", "alibaba-cn", "alibaba"),
    "alibaba": ("alibaba-coding-plan-cn", "alibaba-cn", "alibaba"),
    "bytedance": ("volcengine-coding-plan", "volcengine", "byteplus"),
    "bytedance-seed": ("volcengine-coding-plan", "volcengine", "byteplus"),
    "volcengine": ("volcengine-coding-plan", "volcengine", "byteplus"),
    "opencode": ("opencode-go", "opencode"),
    "siliconflow": ("siliconflow-cn", "siliconflow"),
    "sensenova": ("sensenova", "siliconflow-cn", "siliconflow"),
    "xiaomi": ("opencode-go", "opencode", "xiaomi"),
    "stepfun": ("opencode-go", "opencode", "stepfun"),
    "meituan": ("opencode-go", "opencode"),
    "tencent": ("opencode-go", "opencode"),
}
PROMOTABLE_API_SCOPES: tuple[tuple[str, str], ...] = (
    ("google", "google"),
    ("deepseek", "deepseek"),
    ("openai", "openai"),
    ("anthropic", "anthropic"),
    ("xai", "xai"),
    ("minimax-cn", "minimax"),
    ("minimax", "minimax"),
    ("zhipuai-coding-plan", "zhipuai"),
    ("zhipuai", "zhipuai"),
    ("zai-coding-plan", "zai"),
    ("zai", "zai"),
    ("opencode-go", "opencode"),
    ("opencode", "opencode"),
    ("moonshotai-cn", "moonshotai"),
    ("moonshotai", "moonshotai"),
    ("alibaba-coding-plan-cn", "alibaba"),
    ("alibaba-cn", "alibaba"),
    ("alibaba", "alibaba"),
    ("volcengine-coding-plan", "volcengine"),
    ("volcengine", "volcengine"),
    ("siliconflow-cn", "siliconflow"),
    ("siliconflow", "siliconflow"),
)
PROMOTABLE_LITELLM_SCOPES: dict[str, str] = {
    "gemini": "google",
    "openai": "openai",
    "anthropic": "anthropic",
    "deepseek": "deepseek",
    "xai": "xai",
    "minimax": "minimax",
    "zai": "zai",
    "volcengine": "volcengine",
}


class CatalogError(RuntimeError):
    """表示输入或发布契约不满足严格校验。"""


@dataclass(frozen=True)
class SourceDocument:
    source_id: str
    url: str
    raw: bytes
    value: Any


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> None:
        return None


def _reject_duplicate_json_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise CatalogError(f"JSON 包含重复键: {key}")
        result[key] = value
    return result


def _decode_json(raw: bytes, label: str) -> Any:
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise CatalogError(f"{label} 不是有效 UTF-8") from exc
    try:
        return json.loads(text, object_pairs_hook=_reject_duplicate_json_keys)
    except CatalogError:
        raise
    except json.JSONDecodeError as exc:
        raise CatalogError(f"{label} 不是有效 JSON: 第 {exc.lineno} 行第 {exc.colno} 列") from exc


def read_source(location: str, *, max_bytes: int = SOURCE_MAX_BYTES) -> tuple[str, bytes, Any]:
    """读取一个 HTTPS 或本地 JSON 源，并在解析前实施字节上限。"""
    if max_bytes <= 0:
        raise CatalogError("源大小上限必须为正整数")
    parsed = urllib.parse.urlparse(location)
    if parsed.scheme:
        if parsed.scheme != "https":
            raise CatalogError(f"远程源必须使用 HTTPS: {location}")
        request = urllib.request.Request(
            location,
            headers={"Accept": "application/json", "User-Agent": "Infinity-Deck-model-catalog-builder/1"},
        )
        opener = urllib.request.build_opener(_NoRedirectHandler())
        try:
            with opener.open(request, timeout=60) as response:
                length_header = response.headers.get("Content-Length")
                if length_header:
                    try:
                        declared_length = int(length_header)
                    except ValueError as exc:
                        raise CatalogError(f"源返回无效 Content-Length: {location}") from exc
                    if declared_length > max_bytes:
                        raise CatalogError(f"源超过大小上限 {max_bytes} 字节: {location}")
                raw = response.read(max_bytes + 1)
                final_url = response.geturl()
        except CatalogError:
            raise
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise CatalogError(f"无法读取必需源 {location}: {type(exc).__name__}") from exc
        if final_url != location:
            raise CatalogError(f"必需源发生重定向: {location}")
        effective_url = location
    else:
        path = Path(location).expanduser().resolve()
        try:
            if not path.is_file():
                raise CatalogError(f"本地源不是普通文件: {path}")
            if path.stat().st_size > max_bytes:
                raise CatalogError(f"源超过大小上限 {max_bytes} 字节: {path}")
            with path.open("rb") as handle:
                raw = handle.read(max_bytes + 1)
        except CatalogError:
            raise
        except OSError as exc:
            raise CatalogError(f"无法读取必需源 {path}: {type(exc).__name__}") from exc
        effective_url = path.as_uri()
    if len(raw) > max_bytes:
        raise CatalogError(f"源超过大小上限 {max_bytes} 字节: {location}")
    if not raw:
        raise CatalogError(f"必需源为空: {location}")
    return effective_url, raw, _decode_json(raw, location)


def _require_object(value: Any, path: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise CatalogError(f"{path} 必须是对象")
    return value


def _required_text(value: Any, path: str) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > MAX_ID_LENGTH:
        raise CatalogError(f"{path} 必须是长度 1..{MAX_ID_LENGTH} 的字符串")
    return value


def _optional_text(record: dict[str, Any], field: str, path: str) -> str | None:
    if field not in record or record[field] is None:
        return None
    value = record[field]
    if not isinstance(value, str) or not value.strip() or len(value) > 4096:
        raise CatalogError(f"{path}.{field} 必须是非空字符串或 null")
    return value


def _optional_bool(record: dict[str, Any], field: str, path: str) -> bool | None:
    if field not in record or record[field] is None:
        return None
    value = record[field]
    if type(value) is not bool:
        raise CatalogError(f"{path}.{field} 必须是布尔值或 null")
    return value


def _token_value(value: Any, path: str) -> int | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise CatalogError(f"{path} 必须是正整数或 null")
    if isinstance(value, float) and (not math.isfinite(value) or not value.is_integer()):
        raise CatalogError(f"{path} 必须是正整数或 null")
    result = int(value)
    if result <= 0 or result > MAX_TOKEN_COUNT:
        raise CatalogError(f"{path} 超出允许范围 1..{MAX_TOKEN_COUNT}")
    return result


def _optional_token(record: dict[str, Any], field: str, path: str) -> int | None:
    if field not in record:
        return None
    return _token_value(record[field], f"{path}.{field}")


def _optional_upstream_token(record: dict[str, Any], field: str, path: str) -> int | None:
    """将上游用于“不适用”的零值转为未知，仍拒绝其它越界值。"""
    if field not in record or record[field] is None:
        return None
    value = record[field]
    if value == 0 and type(value) in (int, float):
        return None
    return _token_value(value, f"{path}.{field}")


def _modalities(record: dict[str, Any], path: str) -> dict[str, list[str]] | None:
    value = record.get("modalities")
    if value is None:
        return None
    obj = _require_object(value, f"{path}.modalities")
    result: dict[str, list[str]] = {}
    for direction in ("input", "output"):
        if direction not in obj or obj[direction] is None:
            continue
        entries = obj[direction]
        if not isinstance(entries, list) or len(entries) > 16:
            raise CatalogError(f"{path}.modalities.{direction} 必须是至多 16 项的数组")
        normalized: list[str] = []
        for index, entry in enumerate(entries):
            normalized.append(_required_text(entry, f"{path}.modalities.{direction}[{index}]").lower())
        if len(set(normalized)) != len(normalized):
            raise CatalogError(f"{path}.modalities.{direction} 包含重复值")
        result[direction] = normalized
    return result or None


def _classify_audio_workload(canonical_id: str | None) -> str:
    short_id = (canonical_id or "").lower().rsplit("/", 1)[-1]
    if "realtime" in short_id or "native-audio" in short_id or _LIVE_SEGMENT_RE.search(short_id):
        return "REALTIME"
    if short_id.startswith("lyria") or "music" in short_id:
        return "MUSIC"
    return "SPEECH"


def _workload(
    modalities: dict[str, list[str]] | None,
    mode: str | None = None,
    canonical_id: str | None = None,
) -> str | None:
    short_id = (canonical_id or "").lower().rsplit("/", 1)[-1]
    if mode is not None:
        normalized_mode = mode.lower()
        explicit = {
            "text": "GENERATIVE_TEXT",
            "chat": "GENERATIVE_TEXT",
            "completion": "GENERATIVE_TEXT",
            "embedding": "EMBEDDING",
            "rerank": "RERANK",
            "image": "IMAGE_GENERATION",
            "image_generation": "IMAGE_GENERATION",
            "audio": _classify_audio_workload(canonical_id),
            "audio_speech": _classify_audio_workload(canonical_id),
            "audio_transcription": _classify_audio_workload(canonical_id),
            "speech": "SPEECH",
            "transcription": _classify_audio_workload(canonical_id),
            "music": "MUSIC",
            "realtime": "REALTIME",
            "video": "VIDEO",
            "video_generation": "VIDEO",
        }.get(normalized_mode)
        return explicit or "UNKNOWN"
    outputs = set((modalities or {}).get("output", []))
    if short_id and (
        "realtime" in short_id or "native-audio" in short_id or _LIVE_SEGMENT_RE.search(short_id)
    ) and ("audio" in outputs or "audio" in set((modalities or {}).get("input", []))):
        return "REALTIME"
    if short_id.startswith("lyria") or (short_id and "music" in short_id and "audio" in outputs):
        return "MUSIC"
    if short_id and (
        short_id.endswith("-tts") or "-tts-" in short_id or short_id.startswith("tts-") or short_id.startswith("speech-")
    ):
        return "SPEECH"
    if "text" in outputs:
        return "GENERATIVE_TEXT"
    if outputs == {"image"}:
        return "IMAGE_GENERATION"
    if outputs == {"audio"}:
        return _classify_audio_workload(canonical_id)
    if outputs == {"video"}:
        return "VIDEO"
    return "UNKNOWN"


def _positive_number(value: Any) -> bool:
    if isinstance(value, bool) or value is None:
        return False
    if isinstance(value, (int, float)):
        return math.isfinite(value) and value > 0
    if isinstance(value, str):
        try:
            parsed = float(value)
        except ValueError:
            return False
        return math.isfinite(parsed) and parsed > 0
    return False


def _copy_common(
    source: dict[str, Any],
    path: str,
    *,
    canonical_id: str,
    display_name: str,
    source_name: str,
    provider_scope: str | None = None,
    aliases: list[str] | None = None,
) -> dict[str, Any]:
    record: dict[str, Any] = {
        "canonicalModelId": _required_text(canonical_id, f"{path}.canonicalModelId"),
        "displayName": _required_text(display_name, f"{path}.displayName"),
        "source": source_name,
    }
    if provider_scope is not None:
        record["providerScope"] = _required_text(provider_scope, f"{path}.providerScope")
    if aliases:
        unique_aliases = sorted(set(_required_text(alias, f"{path}.exactAliases") for alias in aliases))
        if canonical_id in unique_aliases:
            unique_aliases.remove(canonical_id)
        if unique_aliases:
            record["exactAliases"] = unique_aliases
    mappings = {
        "family": "family",
        "reasoning": "reasoning",
        "tool_call": "tool_call",
        "structured_output": "structured_output",
        "knowledge": "knowledge",
        "release_date": "release_date",
        "last_updated": "last_updated",
        "status": "status",
    }
    for upstream, output in mappings.items():
        if upstream in ("reasoning", "tool_call", "structured_output"):
            value = _optional_bool(source, upstream, path)
        else:
            value = _optional_text(source, upstream, path)
        if value is not None:
            record[output] = value
    modalities = _modalities(source, path)
    if modalities is not None:
        record["modalities"] = modalities
    explicit_type = _optional_text(source, "type", path)
    record["workload"] = _workload(modalities, explicit_type, canonical_id)
    cost_value = source.get("cost")
    if isinstance(cost_value, dict) and _positive_number(cost_value.get("cache_read")):
        record["prompt_caching"] = True
    limit_value = source.get("limit")
    if limit_value is not None:
        limits = _require_object(limit_value, f"{path}.limit")
        limit_map = {"context": "contextTokens", "input": "inputTokens", "output": "outputTokens"}
        for upstream, output in limit_map.items():
            value = _optional_upstream_token(limits, upstream, f"{path}.limit")
            if value is not None:
                record[output] = value
    return record


def _normalize_models_dev_models(document: SourceDocument) -> list[dict[str, Any]]:
    root = _require_object(document.value, document.source_id)
    records: list[dict[str, Any]] = []
    for key in sorted(root):
        path = f"{document.source_id}.{key}"
        item = _require_object(root[key], path)
        canonical_id = _required_text(item.get("id"), f"{path}.id")
        name = _required_text(item.get("name"), f"{path}.name")
        aliases = [canonical_id.split("/", 1)[1]] if canonical_id.count("/") == 1 else None
        records.append(
            _copy_common(
                item,
                path,
                canonical_id=canonical_id,
                display_name=name,
                source_name="MODELS_DEV",
                aliases=aliases,
            )
        )
    return records


def _normalize_models_dev_api(document: SourceDocument) -> list[dict[str, Any]]:
    root = _require_object(document.value, document.source_id)
    records: list[dict[str, Any]] = []
    for provider_key in sorted(root):
        provider_path = f"{document.source_id}.{provider_key}"
        provider = _require_object(root[provider_key], provider_path)
        provider_id = _required_text(provider.get("id", provider_key), f"{provider_path}.id")
        # OpenRouter 由更直接的必需源单独承载，避免把同一销售条目的两份快照合并。
        if provider_id == "openrouter":
            continue
        models = _require_object(provider.get("models"), f"{provider_path}.models")
        for model_key in sorted(models):
            path = f"{provider_path}.models.{model_key}"
            item = _require_object(models[model_key], path)
            remote_id = _required_text(item.get("id", model_key), f"{path}.id")
            name = _required_text(item.get("name", remote_id), f"{path}.name")
            records.append(
                _copy_common(
                    item,
                    path,
                    canonical_id=remote_id,
                    display_name=name,
                    source_name="MODELS_DEV",
                    provider_scope=provider_id,
                )
            )
    return records


def _litellm_modalities(item: dict[str, Any], path: str, mode: str | None = None) -> dict[str, list[str]]:
    inputs = ["text"]
    normalized_mode = mode.lower() if mode else None
    if normalized_mode in {"image", "image_generation"}:
        outputs = ["image"]
    elif normalized_mode in {"video", "video_generation"}:
        outputs = ["video"]
    elif normalized_mode in {"audio_speech", "speech"}:
        outputs = ["audio"]
    else:
        outputs = ["text"]
    flag_map = {
        "supports_vision": (inputs, "image"),
        "supports_audio_input": (inputs, "audio"),
        "supports_video_input": (inputs, "video"),
        "supports_pdf_input": (inputs, "pdf"),
        "supports_audio_output": (outputs, "audio"),
    }
    for field, (target, value) in flag_map.items():
        supported = _optional_bool(item, field, path)
        if supported is True and value not in target:
            target.append(value)
    return {"input": inputs, "output": outputs}


def _normalize_litellm(document: SourceDocument) -> list[dict[str, Any]]:
    root = _require_object(document.value, document.source_id)
    records: dict[str, dict[str, Any]] = {}
    aliases: dict[str, str] = {}
    for model_key in sorted(root):
        if model_key in {"sample_spec", "fallback_generalizations"}:
            continue
        path = f"{document.source_id}.{model_key}"
        item = _require_object(root[model_key], path)
        if "alias_of" in item:
            alias = _required_text(model_key, path)
            aliases[alias] = _required_text(item["alias_of"], f"{path}.alias_of")
            continue
        provider = _required_text(item.get("litellm_provider"), f"{path}.litellm_provider")
        display_name = item.get("display_name", model_key)
        record: dict[str, Any] = {
            "canonicalModelId": _required_text(model_key, f"{path}.canonicalModelId"),
            "displayName": _required_text(display_name, f"{path}.displayName"),
            "source": "LITELLM",
            "providerScope": provider,
        }
        bool_map = {
            "supports_reasoning": "reasoning",
            "supports_function_calling": "tool_call",
            "supports_response_schema": "structured_output",
            "supports_prompt_caching": "prompt_caching",
            "supports_web_search": "web_search",
            "supports_computer_use": "computer_use",
        }
        for upstream, output in bool_map.items():
            value = _optional_bool(item, upstream, path)
            if value is not None:
                record[output] = value
        deprecation_date = _optional_text(item, "deprecation_date", path)
        if deprecation_date is not None:
            record["deprecation_date"] = deprecation_date
        mode = _optional_text(item, "mode", path)
        modalities = _litellm_modalities(item, path, mode)
        record["modalities"] = modalities
        workload = _workload(modalities, mode, model_key)
        if workload:
            record["workload"] = workload
        input_tokens = _optional_upstream_token(item, "max_input_tokens", path)
        output_tokens = _optional_upstream_token(item, "max_output_tokens", path)
        if output_tokens is None:
            output_tokens = _optional_upstream_token(item, "max_tokens", path)
        if input_tokens is not None:
            record["inputTokens"] = input_tokens
        if output_tokens is not None:
            record["outputTokens"] = output_tokens
        records[model_key] = record
    for alias, target in aliases.items():
        if target not in records:
            raise CatalogError(f"{document.source_id}.{alias}.alias_of 必须指向普通模型条目: {target}")
        records[target].setdefault("exactAliases", []).append(alias)
    return list(records.values())


def _string_list(value: Any, path: str, *, max_items: int = 128) -> list[str]:
    if not isinstance(value, list) or len(value) > max_items:
        raise CatalogError(f"{path} 必须是至多 {max_items} 项的字符串数组")
    return [_required_text(item, f"{path}[{index}]") for index, item in enumerate(value)]


def _normalize_openrouter(document: SourceDocument) -> list[dict[str, Any]]:
    root = _require_object(document.value, document.source_id)
    data = root.get("data")
    if not isinstance(data, list):
        raise CatalogError(f"{document.source_id}.data 必须是数组")
    records: list[dict[str, Any]] = []
    for index, raw_item in enumerate(data):
        path = f"{document.source_id}.data[{index}]"
        item = _require_object(raw_item, path)
        canonical_id = _required_text(item.get("id"), f"{path}.id")
        display_name = _required_text(item.get("name"), f"{path}.name")
        record: dict[str, Any] = {
            "canonicalModelId": canonical_id,
            "displayName": display_name,
            "source": "OPENROUTER",
            "providerScope": "openrouter",
        }
        context = _optional_upstream_token(item, "context_length", path)
        if context is not None:
            record["contextTokens"] = context
        top_provider_value = item.get("top_provider")
        if top_provider_value is not None:
            top_provider = _require_object(top_provider_value, f"{path}.top_provider")
            output_tokens = _optional_upstream_token(top_provider, "max_completion_tokens", f"{path}.top_provider")
            if output_tokens is not None:
                record["outputTokens"] = output_tokens
        architecture_value = item.get("architecture")
        if architecture_value is not None:
            architecture = _require_object(architecture_value, f"{path}.architecture")
            modalities: dict[str, list[str]] = {}
            for upstream, output in (("input_modalities", "input"), ("output_modalities", "output")):
                if upstream in architecture and architecture[upstream] is not None:
                    modalities[output] = [value.lower() for value in _string_list(architecture[upstream], f"{path}.architecture.{upstream}", max_items=16)]
            if modalities:
                record["modalities"] = modalities
                workload = _workload(modalities, canonical_id=canonical_id)
                if workload:
                    record["workload"] = workload
        parameters_value = item.get("supported_parameters")
        if parameters_value is not None:
            parameters = set(_string_list(parameters_value, f"{path}.supported_parameters"))
            record["reasoning"] = bool(parameters & {"reasoning", "reasoning_effort", "include_reasoning"})
            record["tool_call"] = bool(parameters & {"tools", "tool_choice"})
            record["structured_output"] = bool(parameters & {"structured_outputs", "response_format"})
            if "web_search_options" in parameters or "web_search" in parameters:
                record["web_search"] = True
        pricing_value = item.get("pricing")
        if isinstance(pricing_value, dict) and _positive_number(pricing_value.get("input_cache_read")):
            record["prompt_caching"] = True
        knowledge = _optional_text(item, "knowledge_cutoff", path)
        if knowledge is not None:
            record["knowledge"] = knowledge
        expiration = _optional_text(item, "expiration_date", path)
        if expiration is not None:
            record["deprecation_date"] = expiration
        created = item.get("created")
        if created is not None:
            if isinstance(created, bool) or not isinstance(created, int) or created <= 0:
                raise CatalogError(f"{path}.created 必须是正整数 Unix 秒")
            try:
                record["release_date"] = datetime.fromtimestamp(created, tz=timezone.utc).date().isoformat()
            except (OverflowError, OSError, ValueError) as exc:
                raise CatalogError(f"{path}.created 超出时间范围") from exc
        records.append(record)
    return records


def _https_url(value: Any, path: str) -> str:
    url = _required_text(value, path)
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or not parsed.netloc:
        raise CatalogError(f"{path} 必须是完整 HTTPS URL")
    return url


def _review_date(value: Any, path: str) -> str:
    text = _required_text(value, path)
    try:
        parsed = datetime.strptime(text, "%Y-%m-%d").date()
    except ValueError as exc:
        raise CatalogError(f"{path} 必须是 YYYY-MM-DD 日期") from exc
    if parsed.isoformat() != text:
        raise CatalogError(f"{path} 必须是 YYYY-MM-DD 日期")
    return text


def _normalize_official_overrides(document: SourceDocument) -> tuple[dict[str, str], list[dict[str, Any]]]:
    root = _require_object(document.value, document.source_id)
    unexpected_root = set(root) - {"schemaVersion", "source", "records"}
    if unexpected_root:
        raise CatalogError(f"{document.source_id} 包含不允许字段: {', '.join(sorted(unexpected_root))}")
    if type(root.get("schemaVersion")) is not int or root["schemaVersion"] != 1:
        raise CatalogError(f"{document.source_id}.schemaVersion 必须为 1")
    source = _require_object(root.get("source"), f"{document.source_id}.source")
    unexpected_source = set(source) - {"id", "url", "license"}
    if unexpected_source:
        raise CatalogError(f"{document.source_id}.source 包含不允许字段: {', '.join(sorted(unexpected_source))}")
    source_id = _required_text(source.get("id"), f"{document.source_id}.source.id")
    if source_id != "infinity-official-overrides":
        raise CatalogError(f"{document.source_id}.source.id 必须为 infinity-official-overrides")
    source_metadata = {
        "id": source_id,
        "url": _https_url(source.get("url"), f"{document.source_id}.source.url"),
        "license": _required_text(source.get("license"), f"{document.source_id}.source.license"),
    }
    raw_records = root.get("records")
    if not isinstance(raw_records, list) or not raw_records or len(raw_records) > 1_000:
        raise CatalogError(f"{document.source_id}.records 必须是 1..1000 项数组")
    records: list[dict[str, Any]] = []
    for index, raw_record in enumerate(raw_records):
        path = f"{document.source_id}.records[{index}]"
        item = _require_object(raw_record, path)
        unexpected = set(item) - OFFICIAL_RECORD_FIELDS
        if unexpected:
            raise CatalogError(f"{path} 包含不允许字段: {', '.join(sorted(unexpected))}")
        if "providerScope" in item:
            raise CatalogError(f"{path} 包含不允许字段: providerScope")
        record: dict[str, Any] = {
            "canonicalModelId": _required_text(item.get("canonicalModelId"), f"{path}.canonicalModelId"),
            "displayName": _required_text(item.get("displayName"), f"{path}.displayName"),
            "source": _required_text(item.get("source"), f"{path}.source"),
        }
        if record["source"] != "INFINITY_OVERRIDE":
            raise CatalogError(f"{path}.source 必须为 INFINITY_OVERRIDE")
        aliases = item.get("exactAliases")
        if not isinstance(aliases, list) or not aliases or len(aliases) > 32:
            raise CatalogError(f"{path}.exactAliases 必须是 1..32 项数组")
        checked_aliases = sorted(_string_list(aliases, f"{path}.exactAliases", max_items=32))
        if len(set(checked_aliases)) != len(checked_aliases):
            raise CatalogError(f"{path}.exactAliases 包含重复值")
        if record["canonicalModelId"] in checked_aliases:
            raise CatalogError(f"{path}.exactAliases 不得重复 canonicalModelId")
        record["exactAliases"] = checked_aliases
        for field in ("family", "knowledge", "release_date", "last_updated", "deprecation_date", "status"):
            value = _optional_text(item, field, path)
            if value is not None:
                record[field] = value
        workload = _optional_text(item, "workload", path)
        if workload is not None:
            if workload == "AUDIO":
                workload = _classify_audio_workload(record["canonicalModelId"])
            if workload not in WORKLOADS:
                raise CatalogError(f"{path}.workload 不是 ModelWorkload 枚举名")
            record["workload"] = workload
        for field in ("reasoning", "tool_call", "structured_output", "prompt_caching", "web_search", "computer_use"):
            value = _optional_bool(item, field, path)
            if value is not None:
                record[field] = value
        for field in ("contextTokens", "inputTokens", "outputTokens"):
            value = _optional_token(item, field, path)
            if value is not None:
                record[field] = value
        modalities = _modalities(item, path)
        if modalities is not None:
            record["modalities"] = modalities
        record["evidenceUrl"] = _https_url(item.get("evidenceUrl"), f"{path}.evidenceUrl")
        record["reviewedAt"] = _review_date(item.get("reviewedAt"), f"{path}.reviewedAt")
        records.append(record)
    return source_metadata, records


NORMALIZERS: dict[str, Callable[[SourceDocument], list[dict[str, Any]]]] = {
    "models-dev-models": _normalize_models_dev_models,
    "models-dev-api": _normalize_models_dev_api,
    "litellm": _normalize_litellm,
    "openrouter": _normalize_openrouter,
}


def _validate_records(records: list[dict[str, Any]]) -> None:
    if not records:
        raise CatalogError("目录不得为空")
    if len(records) > MAX_RECORDS:
        raise CatalogError(f"目录记录数超过上限 {MAX_RECORDS}")
    identities: set[tuple[str, str, str]] = set()
    for index, record in enumerate(records):
        canonical_id = _required_text(record.get("canonicalModelId"), f"records[{index}].canonicalModelId")
        provider_scope = record.get("providerScope", "")
        if provider_scope:
            provider_scope = _required_text(provider_scope, f"records[{index}].providerScope")
        source = _required_text(record.get("source"), f"records[{index}].source")
        identity = (source, provider_scope, canonical_id)
        if identity in identities:
            scope = provider_scope or "canonical"
            raise CatalogError(f"重复记录身份: {scope}/{canonical_id}")
        identities.add(identity)


def _canonical_json(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def _read_third_party_notices() -> bytes:
    try:
        raw = THIRD_PARTY_NOTICES.read_bytes()
    except OSError as exc:
        raise CatalogError("无法读取 third-party-notices.txt") from exc
    if not raw or len(raw) > 256 * 1024:
        raise CatalogError("third-party-notices.txt 必须为 1..262144 字节")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise CatalogError("third-party-notices.txt 不是有效 UTF-8") from exc
    required = (
        "Copyright (c) 2025 models.dev",
        "Copyright (c) 2023 Berri AI",
        "https://openrouter.ai/terms",
        "Creative Commons Attribution 4.0",
        "https://github.com/OpenSenseNova/SenseNova6.8/blob/main/API_CN.md",
    )
    if any(marker not in text for marker in required):
        raise CatalogError("third-party-notices.txt 缺少必需许可或来源声明")
    forbidden = ("BEGIN PRIVATE KEY", "INFINITY_MODEL_CATALOG_SIGNING_KEY")
    if any(marker in text for marker in forbidden):
        raise CatalogError("third-party-notices.txt 包含禁止的秘密标记")
    return raw


def _parse_generated_at(value: str) -> str:
    if not value.endswith("Z"):
        raise CatalogError("generatedAt 必须是 UTC ISO8601 且以 Z 结尾")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise CatalogError("generatedAt 不是有效 ISO8601 时间") from exc
    if parsed.utcoffset() != timezone.utc.utcoffset(parsed):
        raise CatalogError("generatedAt 必须使用 UTC")
    normalized = parsed.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    if normalized != value:
        raise CatalogError("generatedAt 必须为无小数秒的 UTC ISO8601")
    return value


def _load_signing_key(signing_key: str | None, signing_key_env: str | None) -> tuple[Path, Callable[[], None]]:
    if bool(signing_key) == bool(signing_key_env):
        raise CatalogError("必须且只能指定 --signing-key 或 --signing-key-env")
    if signing_key:
        path = Path(signing_key).expanduser().resolve()
        if not path.is_file():
            raise CatalogError("签名私钥路径不是普通文件")
        if path.stat().st_size > 64 * 1024:
            raise CatalogError("签名私钥超过大小上限")
        return path, lambda: None
    assert signing_key_env is not None
    secret = os.environ.get(signing_key_env)
    if secret is None or not secret.strip():
        raise CatalogError(f"签名私钥环境变量未设置: {signing_key_env}")
    encoded = secret.encode("utf-8")
    if len(encoded) > 64 * 1024:
        raise CatalogError("签名私钥超过大小上限")
    handle = tempfile.NamedTemporaryFile(prefix="infinity-catalog-key-", suffix=".pem", delete=False)
    try:
        handle.write(encoded)
        handle.flush()
        path = Path(handle.name)
    finally:
        handle.close()
    os.chmod(path, 0o600)
    return path, lambda: path.unlink(missing_ok=True)


def _sign_payload(payload: bytes, key_path: Path) -> bytes:
    inspect = subprocess.run(
        ["openssl", "pkey", "-in", str(key_path), "-pubout", "-text_pub", "-noout"],
        text=True,
        capture_output=True,
    )
    if inspect.returncode != 0:
        raise CatalogError("OpenSSL 无法读取签名私钥")
    if "prime256v1" not in inspect.stdout and "P-256" not in inspect.stdout:
        raise CatalogError("签名私钥必须是 P-256")
    signed = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", str(key_path)],
        input=payload,
        capture_output=True,
    )
    if signed.returncode != 0 or not signed.stdout:
        raise CatalogError("OpenSSL SHA256withECDSA 签名失败")
    return signed.stdout


_VENDOR_ALIASES: dict[str, str] = {
    "x-ai": "xai",
    "z-ai": "zhipuai",
    "zai": "zhipuai",
    "zhipuai": "zhipuai",
    "bytedance-seed": "volcengine",
    "bytedance": "volcengine",
    "qwen": "alibaba",
    "moonshotai": "moonshotai",
    "gemini": "google",
}


def _norm_id(value: str) -> str:
    return value.strip().lower()


def _split_single_vendor(model_id: str) -> tuple[str | None, str]:
    if model_id.count("/") == 1:
        vendor, short = model_id.split("/", 1)
        return _norm_id(vendor), _norm_id(short)
    return None, _norm_id(model_id)


def _canonical_vendor(vendor: str | None) -> str | None:
    if vendor is None:
        return None
    return _VENDOR_ALIASES.get(vendor, vendor)


def _valid_knowledge_cutoff(knowledge: str | None, release_date: str | None) -> bool:
    if not knowledge:
        return False
    if not _ISO_YEAR_MONTH_RE.match(knowledge):
        return False
    if release_date and _ISO_YEAR_MONTH_RE.match(release_date):
        if knowledge[:7] > release_date[:7]:
            return False
    return True


def _backfill_missing_fields(
    target: dict[str, Any],
    donors: list[dict[str, Any]],
    knowledge_donors: list[dict[str, Any]],
) -> None:
    scalar_fields = (
        "structured_output",
        "prompt_caching",
        "web_search",
        "computer_use",
        "reasoning",
        "tool_call",
        "contextTokens",
        "inputTokens",
        "outputTokens",
        "release_date",
        "last_updated",
        "deprecation_date",
        "status",
    )
    for field in scalar_fields:
        if field in target:
            continue
        for donor in donors:
            if field in donor:
                target[field] = donor[field]
                break
    if target.get("workload") in (None, "UNKNOWN"):
        for donor in donors:
            donor_wl = donor.get("workload")
            if donor_wl and donor_wl != "UNKNOWN":
                target["workload"] = donor_wl
                break
    if "modalities" in target:
        target_inputs = list(target["modalities"].get("input", []))
        changed = False
        for donor in donors:
            for item in (donor.get("modalities") or {}).get("input", []):
                normalized_item = "pdf" if item == "file" else item
                if normalized_item not in target_inputs and len(target_inputs) < 16:
                    target_inputs.append(normalized_item)
                    changed = True
        if changed:
            target["modalities"] = {**target["modalities"], "input": target_inputs}
    if "knowledge" not in target:
        release_date = target.get("release_date")
        for donor in knowledge_donors:
            candidate = donor.get("knowledge")
            if _valid_knowledge_cutoff(candidate, release_date):
                target["knowledge"] = candidate
                break


def _enrich_global_catalog(records: list[dict[str, Any]]) -> None:
    """用一方官方作用域与 OpenRouter/LiteLLM 补全全局规范条目缺失字段，并提升未收录的一方模型。"""
    api_by_scope: dict[str, dict[str, dict[str, Any]]] = {}
    api_so_votes: dict[str, set[bool]] = {}
    api_so_count: dict[str, int] = {}
    litellm_by_scope: dict[str, dict[str, dict[str, Any]]] = {}
    openrouter_by_id: dict[str, dict[str, Any]] = {}
    openrouter_by_vendor_short: dict[tuple[str, str], dict[str, Any]] = {}

    for rec in records:
        scope = rec.get("providerScope")
        if not scope:
            continue
        src = rec["source"]
        cid = _norm_id(rec["canonicalModelId"])
        vendor, short = _split_single_vendor(rec["canonicalModelId"])
        if src == "MODELS_DEV":
            bucket = api_by_scope.setdefault(scope, {})
            bucket.setdefault(cid, rec)
            if short != cid:
                bucket.setdefault(short, rec)
            if "structured_output" in rec:
                api_so_votes.setdefault(short, set()).add(bool(rec["structured_output"]))
                api_so_count[short] = api_so_count.get(short, 0) + 1
        elif src == "LITELLM":
            bucket = litellm_by_scope.setdefault(scope, {})
            bucket.setdefault(cid, rec)
            if short != cid:
                bucket.setdefault(short, rec)
        elif src == "OPENROUTER":
            openrouter_by_id.setdefault(cid, rec)
            if vendor is not None:
                openrouter_by_vendor_short.setdefault((_canonical_vendor(vendor) or vendor, short), rec)

    def collect_donors(canonical_id: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        cid = _norm_id(canonical_id)
        vendor, short = _split_single_vendor(canonical_id)
        canon_vendor = _canonical_vendor(vendor)
        api_donors: list[dict[str, Any]] = []
        or_donors: list[dict[str, Any]] = []
        ll_donors: list[dict[str, Any]] = []

        scopes = FIRST_PARTY_VENDOR_SCOPES.get(vendor or "", ()) or FIRST_PARTY_VENDOR_SCOPES.get(canon_vendor or "", ())
        for scope in scopes:
            bucket = api_by_scope.get(scope)
            if bucket:
                hit = bucket.get(cid) or bucket.get(short)
                if hit is not None and hit not in api_donors:
                    api_donors.append(hit)
            ll_bucket = litellm_by_scope.get(scope)
            if ll_bucket:
                hit = ll_bucket.get(cid) or ll_bucket.get(f"{scope}/{short}") or ll_bucket.get(short)
                if hit is not None and hit not in ll_donors:
                    ll_donors.append(hit)

        or_hit = openrouter_by_id.get(cid)
        if or_hit is None and canon_vendor is not None:
            or_hit = openrouter_by_vendor_short.get((canon_vendor, short))
        if or_hit is not None:
            or_donors.append(or_hit)

        # OpenRouter 与一方官方作用域顺序：一般字段优先一方 API，知识截止优先 OpenRouter 官方字段再一方 API。
        general_donors = api_donors + or_donors + ll_donors
        knowledge_donors = or_donors + api_donors
        return general_donors, knowledge_donors

    global_records = [r for r in records if r["source"] == "MODELS_DEV" and "providerScope" not in r]
    covered_keys: set[str] = set()
    global_identities: set[str] = set()
    for rec in global_records:
        cid_norm = _norm_id(rec["canonicalModelId"])
        covered_keys.add(cid_norm)
        global_identities.add(rec["canonicalModelId"])
        for alias in rec.get("exactAliases", []):
            covered_keys.add(_norm_id(alias))
        donors, knowledge_donors = collect_donors(rec["canonicalModelId"])
        if donors:
            _backfill_missing_fields(rec, donors, knowledge_donors)
        if "structured_output" not in rec:
            _, short = _split_single_vendor(rec["canonicalModelId"])
            votes = api_so_votes.get(short)
            if votes == {True} and api_so_count.get(short, 0) >= 2:
                rec["structured_output"] = True

    # 提升未出现在 models-dev-models、但存在于可信一方作用域的模型为规范全局记录。
    promoted: list[dict[str, Any]] = []

    def try_promote(source_rec: dict[str, Any], target_vendor: str) -> None:
        raw_id = source_rec["canonicalModelId"]
        if any(ch in raw_id for ch in ("@", ":")):
            return
        if raw_id.count("/") > 1:
            return
        _, short_raw = raw_id.split("/", 1) if "/" in raw_id else (None, raw_id)
        short_norm = _norm_id(short_raw)
        canonical_id = f"{target_vendor}/{short_raw}"
        canonical_norm = _norm_id(canonical_id)
        if canonical_norm in covered_keys or short_norm in covered_keys or canonical_id in global_identities:
            return
        new_rec: dict[str, Any] = {
            "canonicalModelId": canonical_id,
            "displayName": source_rec["displayName"],
            "source": "MODELS_DEV",
            "exactAliases": [short_raw],
        }
        for field in (
            "family",
            "workload",
            "reasoning",
            "tool_call",
            "structured_output",
            "prompt_caching",
            "web_search",
            "computer_use",
            "contextTokens",
            "inputTokens",
            "outputTokens",
            "modalities",
            "release_date",
            "last_updated",
            "deprecation_date",
            "status",
        ):
            if field in source_rec:
                new_rec[field] = source_rec[field]
        if _valid_knowledge_cutoff(source_rec.get("knowledge"), source_rec.get("release_date")):
            new_rec["knowledge"] = source_rec["knowledge"]
        donors, knowledge_donors = collect_donors(canonical_id)
        if donors:
            _backfill_missing_fields(new_rec, donors, knowledge_donors)
        if "contextTokens" not in new_rec and "inputTokens" in new_rec:
            new_rec["contextTokens"] = new_rec["inputTokens"]
        covered_keys.add(canonical_norm)
        covered_keys.add(short_norm)
        global_identities.add(canonical_id)
        promoted.append(new_rec)

    for scope, target_vendor in PROMOTABLE_API_SCOPES:
        for rec in records:
            if rec["source"] == "MODELS_DEV" and rec.get("providerScope") == scope:
                try_promote(rec, target_vendor)

    for scope, target_vendor in PROMOTABLE_LITELLM_SCOPES.items():
        for rec in records:
            if rec["source"] == "LITELLM" and rec.get("providerScope") == scope:
                try_promote(rec, target_vendor)

    records.extend(promoted)

    # 同族 -highspeed 受控继承基座模型 knowledge 截止日期（仅当同厂商精确基座存在且满足时间合理性）。
    all_global = global_records + promoted
    global_by_vendor_short: dict[tuple[str, str], dict[str, Any]] = {}
    for rec in all_global:
        vendor, short = _split_single_vendor(rec["canonicalModelId"])
        if vendor is not None:
            global_by_vendor_short[(_canonical_vendor(vendor) or vendor, short)] = rec
    for rec in all_global:
        if "knowledge" in rec:
            continue
        vendor, short = _split_single_vendor(rec["canonicalModelId"])
        if vendor is None or not short.endswith("-highspeed"):
            continue
        base_short = short[: -len("-highspeed")]
        base_rec = global_by_vendor_short.get((_canonical_vendor(vendor) or vendor, base_short))
        if base_rec is not None:
            base_k = base_rec.get("knowledge")
            if _valid_knowledge_cutoff(base_k, rec.get("release_date")):
                rec["knowledge"] = base_k


def build_catalog(
    source_locations: dict[str, str],
    *,
    output_root: Path,
    key_id: str,
    catalog_version: int,
    generated_at: str,
    signing_key: str | None,
    signing_key_env: str | None,
    official_overrides: str | None = str(DEFAULT_OFFICIAL_OVERRIDES),
) -> Path:
    if set(source_locations) != set(DEFAULT_SOURCES):
        raise CatalogError("必须完整提供四个必需源")
    key_id = _required_text(key_id, "keyId")
    if catalog_version <= 0:
        raise CatalogError("catalogVersion 必须是正整数")
    generated_at = _parse_generated_at(generated_at)

    documents: list[SourceDocument] = []
    for source_id in DEFAULT_SOURCES:
        url, raw, value = read_source(source_locations[source_id])
        documents.append(SourceDocument(source_id, url, raw, value))

    records: list[dict[str, Any]] = []
    for document in documents:
        records.extend(NORMALIZERS[document.source_id](document))
    _enrich_global_catalog(records)
    canonical_records = {
        record["canonicalModelId"]: record
        for record in records
        if record["source"] == "MODELS_DEV" and "providerScope" not in record
    }
    override_source: dict[str, str] | None = None
    override_document: SourceDocument | None = None
    if official_overrides is not None:
        url, raw, value = read_source(official_overrides)
        override_document = SourceDocument("official-overrides", url, raw, value)
        override_source, override_records = _normalize_official_overrides(override_document)
        for override in override_records:
            upstream = canonical_records.get(override["canonicalModelId"])
            if upstream is None:
                records.append(override)
            else:
                # 即使上游已接管规范记录，仍将经核验的官方知识截止补全至缺失该字段的上游条目。
                if "knowledge" not in upstream and "knowledge" in override:
                    if _valid_knowledge_cutoff(override["knowledge"], upstream.get("release_date")):
                        upstream["knowledge"] = override["knowledge"]
                if override.get("workload") in {"RERANK", "EMBEDDING"}:
                    # 重排与嵌入模型需要工作负载、上下文和网关别名均已接管。
                    has_all_facts = (
                        upstream.get("workload") == override.get("workload")
                        and upstream.get("contextTokens") == override.get("contextTokens")
                        and set(override["exactAliases"]) <= set(upstream.get("exactAliases", []))
                    )
                    if not has_all_facts:
                        records.append(override)
    records.sort(key=lambda item: (item.get("providerScope", ""), item["canonicalModelId"], item["source"]))
    _validate_records(records)

    sources = [
        {
            "id": document.source_id,
            "url": document.url,
            "fetchedAt": generated_at,
            "sha256": hashlib.sha256(document.raw).hexdigest(),
            "license": SOURCE_LICENSES[document.source_id],
        }
        for document in documents
    ]
    if override_source is not None and override_document is not None:
        sources.append(
            {
                "id": override_source["id"],
                "url": override_source["url"],
                "fetchedAt": generated_at,
                "sha256": hashlib.sha256(override_document.raw).hexdigest(),
                "license": override_source["license"],
            }
        )
    catalog = {"schemaVersion": 3, "generatedAt": generated_at, "sources": sources, "records": records}
    catalog_bytes = _canonical_json(catalog)
    if len(catalog_bytes) > CATALOG_MAX_BYTES:
        raise CatalogError(f"目录超过大小上限 {CATALOG_MAX_BYTES} 字节")
    catalog_hash = hashlib.sha256(catalog_bytes).hexdigest()
    catalog_file = f"catalog-{catalog_hash}.json"
    payload = {
        "schemaVersion": 1,
        "catalogVersion": catalog_version,
        "generatedAt": generated_at,
        "catalogFile": catalog_file,
        "catalogBytes": len(catalog_bytes),
        "catalogSha256": catalog_hash,
        "recordCount": len(records),
    }
    payload_bytes = _canonical_json(payload)
    key_path, cleanup_key = _load_signing_key(signing_key, signing_key_env)
    try:
        signature = _sign_payload(payload_bytes, key_path)
    finally:
        cleanup_key()
    envelope = {
        "keyId": key_id,
        "payload": base64.b64encode(payload_bytes).decode("ascii"),
        "signature": base64.b64encode(signature).decode("ascii"),
    }
    manifest_bytes = _canonical_json(envelope)
    if len(manifest_bytes) > MANIFEST_MAX_BYTES:
        raise CatalogError(f"manifest 超过大小上限 {MANIFEST_MAX_BYTES} 字节")
    notices_bytes = _read_third_party_notices()

    output_root = output_root.resolve()
    parent = output_root / "model-catalog"
    parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".v1-build-", dir=parent))
    destination = parent / "v1"
    backup = parent / ".v1-previous"
    try:
        (staging / catalog_file).write_bytes(catalog_bytes)
        (staging / "manifest.json").write_bytes(manifest_bytes)
        (staging / "third-party-notices.txt").write_bytes(notices_bytes)
        if backup.exists():
            shutil.rmtree(backup)
        if destination.exists():
            os.replace(destination, backup)
        try:
            os.replace(staging, destination)
        except BaseException:
            if backup.exists() and not destination.exists():
                os.replace(backup, destination)
            raise
        if backup.exists():
            shutil.rmtree(backup)
    except BaseException:
        if staging.exists():
            shutil.rmtree(staging)
        raise
    return destination


def _default_timestamp() -> tuple[int, str]:
    now = datetime.now(timezone.utc).replace(microsecond=0)
    return int(now.timestamp()), now.isoformat().replace("+00:00", "Z")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    default_version, default_generated_at = _default_timestamp()
    parser = argparse.ArgumentParser(description="构建并签名 Infinity Deck 独立模型目录")
    parser.add_argument("--models-dev-models", default=DEFAULT_SOURCES["models-dev-models"])
    parser.add_argument("--models-dev-api", default=DEFAULT_SOURCES["models-dev-api"])
    parser.add_argument("--litellm", default=DEFAULT_SOURCES["litellm"])
    parser.add_argument("--openrouter", default=DEFAULT_SOURCES["openrouter"])
    parser.add_argument("--output-root", type=Path, default=Path("public"))
    parser.add_argument("--key-id", required=True)
    parser.add_argument("--catalog-version", type=int, default=default_version)
    parser.add_argument("--generated-at", default=default_generated_at)
    signing = parser.add_mutually_exclusive_group(required=True)
    signing.add_argument("--signing-key", help="P-256 PEM 私钥文件路径")
    signing.add_argument("--signing-key-env", help="保存 P-256 PEM 私钥的环境变量名")
    overrides = parser.add_mutually_exclusive_group()
    overrides.add_argument(
        "--official-overrides",
        default=str(DEFAULT_OFFICIAL_OVERRIDES),
        help="经官方资料核验的补充目录文件路径",
    )
    overrides.add_argument(
        "--no-official-overrides",
        action="store_true",
        help="仅供隔离测试使用，禁用默认官方补充",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    sources = {
        "models-dev-models": args.models_dev_models,
        "models-dev-api": args.models_dev_api,
        "litellm": args.litellm,
        "openrouter": args.openrouter,
    }
    try:
        destination = build_catalog(
            sources,
            output_root=args.output_root,
            key_id=args.key_id,
            catalog_version=args.catalog_version,
            generated_at=args.generated_at,
            signing_key=args.signing_key,
            signing_key_env=args.signing_key_env,
            official_overrides=None if args.no_official_overrides else args.official_overrides,
        )
    except CatalogError as exc:
        print(f"目录构建失败: {exc}", file=sys.stderr)
        return 1
    print(f"目录构建成功: {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
