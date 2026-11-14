#!/usr/bin/env python3
"""验证 Infinity Deck 发行 APK 的渠道制品、完整性、版本、签名与常见秘密泄漏。"""

from __future__ import annotations

import argparse
import hashlib
import io
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile


# 为多 ABI ONNX Runtime、侧载 llama.cpp 与 Linux 运行时保留有界空间。
MAX_APK_BYTES = 160 * 1024 * 1024
READ_CHUNK_BYTES = 1024 * 1024
SCAN_OVERLAP_BYTES = 512
MAX_ZIP_ENTRIES = 100_000
MAX_ZIP_ENTRY_UNCOMPRESSED_BYTES = 512 * 1024 * 1024
MAX_ZIP_TOTAL_UNCOMPRESSED_BYTES = 1024 * 1024 * 1024
MAX_SUSPICIOUS_COMPRESSION_RATIO = 1_000
MIN_RATIO_CHECK_BYTES = 1024 * 1024

SENSITIVE_PATTERNS: tuple[tuple[str, re.Pattern[bytes]], ...] = (
    ("macOS 用户绝对路径", re.compile(rb"/Users/[A-Za-z0-9._-]+/")),
    # /home/session 是 Linux 会话在 Alpine 内的固定会话目录，不是构建机或用户路径。
    ("Linux 用户绝对路径", re.compile(rb"/home/(?!session/)[A-Za-z0-9._-]+/")),
    ("Windows 本机绝对路径", re.compile(rb"(?i)[A-Z]:[\\/](?:Users|Infinity Deck)[\\/]")),
    ("本机 secure_env 路径", re.compile(rb"(?i)(?:secure_env|promenar\.keystore|secure\.properties)")),
    (
        "Authorization Bearer 凭证",
        re.compile(rb"(?i)Authorization\s*[:=]\s*Bearer\s+[A-Za-z0-9._~+/=-]{8,}"),
    ),
    (
        "完整私钥 PEM 材料",
        re.compile(
            rb"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"
            rb"[\r\n ]+[A-Za-z0-9+/=\r\n ]{64,}"
            rb"-----END (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"
        ),
    ),
    ("疑似 OpenAI 兼容密钥", re.compile(rb"sk-[A-Za-z0-9_-]{20,}")),
    ("疑似 Google API 密钥", re.compile(rb"AIza[A-Za-z0-9_-]{35}")),
    ("疑似 Groq API 密钥", re.compile(rb"gsk_[A-Za-z0-9_-]{20,}")),
    ("疑似 xAI API 密钥", re.compile(rb"xai-[A-Za-z0-9_-]{20,}")),
    ("Prompt 测试 fixture", re.compile(rb"(?i)INFINITY_PROMPT_FIXTURE\s*[:=]")),
    ("响应测试 fixture", re.compile(rb"(?i)INFINITY_RESPONSE_FIXTURE\s*[:=]")),
    ("WebDAV 明文口令 fixture", re.compile(rb"(?i)WEBDAV_PASSWORD_FIXTURE\s*[:=]")),
    (
        "WebDAV 明文口令",
        re.compile(rb'''(?i)webdav[_-]?password["']?\s*[:=]\s*["'][^"'\s]{8,}["']'''),
    ),
    ("WebDAV 测试 URL fixture", re.compile(rb"(?i)INFINITY_WEBDAV_TEST_URL\s*[:=]")),
    (
        "URL 内嵌明文口令",
        re.compile(rb"(?i)https?://[^\s/:@]{1,64}:[^\s/@]{4,128}@"),
    ),
    (
        "私网或 CGNAT 测试地址",
        re.compile(
            rb"(?<!\d)(?:10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|"
            rb"172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2}|"
            rb"100\.(?:6[4-9]|[7-9]\d|1[01]\d|12[0-7])(?:\.\d{1,3}){2})(?!\d)"
        ),
    ),
)

# 打包的官方 CPython（构建时按 SHA-256 校验）自带上游内容：python.org 构建机 runner 用户的路径、
# ipaddress/random/urllib 中的保留地址常量与文档示例、venv 的 PowerShell 激活脚本。
# 只对这些条目放行这些具体值；凭据类模式与其它用户路径照常报警，自编译的 libnxpython.so 不在豁免内。
UPSTREAM_BUILD_PATHS: dict[str, re.Pattern[bytes]] = {
    "macOS 用户绝对路径": re.compile(rb"/Users/runner/"),
    "Linux 用户绝对路径": re.compile(rb"/home/runner/"),
}
PACKAGED_PYTHON_LIBRARY_PATTERN = re.compile(
    r"lib/[^/]+/(?:libpyext_[A-Za-z0-9_]+|libpython3\.\d+|lib[a-z0-9]+_python)\.so\Z"
)
PACKAGED_PYTHON_STDLIB_PATTERN = re.compile(r"lib/[^/]+/libpystdlib\.so\Z")
STDLIB_ALLOWANCES: dict[str, re.Pattern[bytes]] = {
    **UPSTREAM_BUILD_PATHS,
    "Windows 本机绝对路径": re.compile(rb"(?i)C:[\\/]Users[\\/]"),
    "私网或 CGNAT 测试地址": re.compile(rb".+", re.DOTALL),
}

# 内置 git 的纯 Python 依赖（PyPI wheel，构建时按 SHA-256 校验）：urllib3 文档示例中的占位口令 URL。
PACKAGED_PYTHON_SITE_PATTERN = re.compile(r"lib/[^/]+/libpysite\.so\Z")
SITE_ALLOWANCES: dict[str, re.Pattern[bytes]] = {
    "URL 内嵌明文口令": re.compile(rb"https?://username:password@"),
}

# 内置 Tailscale 节点（app/tailnet 的 Go 程序，依赖由 go.sum 固定）：Go 符号表中相邻的小写标识符会拼出 sk- 前缀，
# Tailscale 与 Go 标准库中的保留地址段常量（含 MagicDNS 解析地址 100.100.100.100 与 ChromeOS ARC 网段）。
# 只对 libnxtailnet.so 放行这些具体值；真实 API 密钥含大写或数字，口令 URL、用户路径等其它模式照常报警。
TAILNET_BINARY_PATTERN = re.compile(r"lib/([^/]+)/libnxtailnet\.so\Z")
TAILNET_ALLOWANCES: dict[str, re.Pattern[bytes]] = {
    "疑似 OpenAI 兼容密钥": re.compile(rb"sk-[a-z-]+"),
    "私网或 CGNAT 测试地址": re.compile(
        rb"10\.0\.0\.0|100\.64\.0\.0|172\.16\.0\.0|192\.168\.0\.0|100\.115\.92\.0|100\.100\.100\.100|10\.[02]76\.76\.10"
    ),
}
# 应用 dex：视觉实验室的虚构 Tailscale 设备使用固定的 100.101.7.0/24 地址（ui/lab/tailnet/LabTailnet.kt）。
# 只对 classes*.dex 放行这一网段；其它私网与 CGNAT 地址照常报警。
APP_DEX_PATTERN = re.compile(r"classes\d*\.dex\Z")
APP_DEX_ALLOWANCES: dict[str, re.Pattern[bytes]] = {
    "私网或 CGNAT 测试地址": re.compile(rb"100\.101\.7\.\d{1,3}"),
}
# ELF e_machine：发行 APK 中每个带打包 Python 的 ABI 都须有对应架构的 libnxtailnet.so。
TAILNET_ELF_MACHINES = {"arm64-v8a": 183, "x86_64": 62}

# 打包 Python 须带构建期预编译的哈希 pyc：缺少时每次启动解释器都重新编译，内置 git 与 Git 面板在真机上慢约十倍。
PYTHON_BYTECODE_MODULES: dict[str, tuple[str, ...]] = {
    "libpystdlib.so": ("os.pyc", "encodings/__init__.pyc", "sitecustomize.pyc"),
    "libpysite.so": ("infinity_git.pyc", "dulwich/__init__.pyc", "dulwich/repo.pyc"),
}
PYTHON_BYTECODE_ENTRY_PATTERN = re.compile(r"lib/([^/]+)/(libpystdlib\.so|libpysite\.so)\Z")
# PEP 552 标志位：bit0 为哈希 pyc、bit1 为需要核对源码；打包 zip 只读安装，使用不核对源码的哈希 pyc。
UNCHECKED_HASH_PYC_FLAGS = 0b01

LOCAL_INFERENCE_LIBRARY_PATTERN = re.compile(
    r"(?:libinfinity_llama|libllama.*|libggml.*)\.so\Z",
    re.IGNORECASE,
)


class VerificationError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--expected-package", required=True)
    parser.add_argument("--expected-version-code", required=True)
    parser.add_argument("--expected-version-name", required=True)
    parser.add_argument("--expected-cert-sha256", required=True)
    parser.add_argument("--checksum-output", type=Path)
    parser.add_argument("--max-size-bytes", type=int, default=MAX_APK_BYTES)
    parser.add_argument("--distribution", choices=("sideload", "play"), default="sideload")
    return parser.parse_args()


def normalize_digest(value: str) -> str:
    return re.sub(r"[^0-9a-fA-F]", "", value).lower()


def android_build_tools_version(path: Path) -> tuple[int, ...]:
    numbers = re.findall(r"\d+", path.parent.name)
    return tuple(int(number) for number in numbers) if numbers else (0,)


def find_android_tool(name: str) -> Path:
    direct = shutil.which(name)
    if direct:
        return Path(direct)

    roots = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    candidates: list[Path] = []
    for raw_root in roots:
        if not raw_root:
            continue
        build_tools = Path(raw_root) / "build-tools"
        if build_tools.is_dir():
            candidates.extend(build_tools.glob(f"*/{name}"))
            candidates.extend(build_tools.glob(f"*/{name}.bat"))
            candidates.extend(build_tools.glob(f"*/{name}.exe"))
    if not candidates:
        raise VerificationError(f"找不到 Android SDK 工具：{name}")
    return max(candidates, key=android_build_tools_version)


def run_tool(command: list[str]) -> str:
    effective_command = command
    if os.name == "nt" and Path(command[0]).suffix.lower() in {".bat", ".cmd"}:
        effective_command = ["cmd.exe", "/d", "/s", "/c", subprocess.list2cmdline(command)]
    completed = subprocess.run(
        effective_command,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if completed.returncode != 0:
        raise VerificationError(f"工具执行失败：{Path(command[0]).name}（退出码 {completed.returncode}）")
    return completed.stdout


def verify_file_size(apk: Path, max_size_bytes: int) -> int:
    size = apk.stat().st_size
    if size <= 0 or size > max_size_bytes:
        raise VerificationError(f"APK 大小不合规：{size} bytes，上限 {max_size_bytes} bytes")
    return size


def verify_zip_entries(entries) -> None:
    entries = list(entries)
    if len(entries) > MAX_ZIP_ENTRIES:
        raise VerificationError(f"APK ZIP 条目过多：{len(entries)}")

    total_uncompressed = 0
    for info in entries:
        if info.is_dir():
            continue
        if info.file_size < 0 or info.compress_size < 0:
            raise VerificationError(f"APK ZIP 条目大小无效：{info.filename}")
        total_uncompressed += info.file_size
        if info.file_size > MAX_ZIP_ENTRY_UNCOMPRESSED_BYTES:
            raise VerificationError(f"APK ZIP 条目解压规模异常：{info.filename}")
        if (
            info.file_size >= MIN_RATIO_CHECK_BYTES
            and info.file_size > max(info.compress_size, 1) * MAX_SUSPICIOUS_COMPRESSION_RATIO
        ):
            raise VerificationError(f"APK ZIP 条目压缩率异常：{info.filename}")
    if total_uncompressed > MAX_ZIP_TOTAL_UNCOMPRESSED_BYTES:
        raise VerificationError(f"APK ZIP 总解压规模异常：{total_uncompressed} bytes")


def verify_zip(apk: Path) -> None:
    try:
        with zipfile.ZipFile(apk) as archive:
            verify_zip_entries(archive.infolist())
            corrupt = archive.testzip()
    except (OSError, zipfile.BadZipFile) as error:
        raise VerificationError("APK 不是有效 ZIP 文件") from error
    if corrupt is not None:
        raise VerificationError(f"APK ZIP 条目损坏：{corrupt}")


def verify_local_inference_artifacts(apk: Path, distribution: str) -> None:
    """两渠道禁止捆绑 GGUF，仅侧载的 64 位 ABI 含 llama.cpp JNI。"""
    with zipfile.ZipFile(apk) as archive:
        names = {info.filename.replace("\\", "/") for info in archive.infolist()}
    supported_abis = {"arm64-v8a", "x86_64"}
    allowed_bridges = {f"lib/{abi}/libinfinity_llama.so" for abi in supported_abis} if distribution == "sideload" else set()
    forbidden = []
    for name in names:
        basename = name.rsplit("/", 1)[-1]
        if basename.lower().endswith(".gguf") or (LOCAL_INFERENCE_LIBRARY_PATTERN.fullmatch(basename) and name not in allowed_bridges):
            forbidden.append(name)
    if forbidden:
        raise VerificationError(f"APK 包含禁用的本地推理制品：{', '.join(sorted(forbidden))}")
    if distribution == "sideload":
        abis = {"arm64-v8a"} | {abi for abi in supported_abis if f"lib/{abi}/libinfinity_workspace_fs.so" in names}
        missing = {f"lib/{abi}/libinfinity_llama.so" for abi in abis} - names
        if missing:
            raise VerificationError(f"侧载 APK 缺少本地推理制品：{', '.join(sorted(missing))}")


def verify_recording_artifacts(apk: Path) -> None:
    """确认听写词表、语音活动检测及原生运行时完整，X-ASR 权重由应用按需下载。"""
    assets = {
        "bpe.vocab": "28fc94d67aae53d8c58010fcfb16fc8c2f8dd263e03f3490c98210e354e8f914",
        "silero_vad.onnx": "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
    }
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        forbidden = {name for name in names if name.rsplit("/", 1)[-1] in {
            "encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt",
        }}
        if forbidden:
            raise VerificationError(f"APK 包含听写模型权重：{', '.join(sorted(forbidden))}")
        abis = {"arm64-v8a"} | {name.split("/")[1] for name in names if name.endswith("/libinfinity_workspace_fs.so")}
        libraries = ("libonnxruntime.so", "libsherpa-onnx-jni.so", "libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so")
        required = {f"lib/{abi}/{lib}" for abi in abis for lib in libraries}
        required.update(f"assets/asr/{name}" for name in assets)
        if missing := required - names:
            raise VerificationError(f"APK 缺少离线听写制品：{', '.join(sorted(missing))}")
        for name, expected in assets.items():
            digest = hashlib.sha256()
            with archive.open(f"assets/asr/{name}") as stream:
                for chunk in iter(lambda: stream.read(READ_CHUNK_BYTES), b""):
                    digest.update(chunk)
            if digest.hexdigest() != expected:
                raise VerificationError(f"离线听写模型摘要不符：{name}")


def verify_linux_runtime(apk: Path, distribution: str, properties: Path | None = None) -> None:
    """侧载要求完整 Linux 制品与固定摘要；Play 禁止携带 Linux 资产或原生程序。"""
    properties = properties or Path(__file__).resolve().parents[1] / "native-ui/app/linux/linux-runtime.properties"
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        linux_entries = {name for name in names if name.startswith("assets/linux/") or
                         re.fullmatch(r"lib/[^/]+/libnxproot(?:-loader)?\.so", name)}
        if distribution == "play":
            if linux_entries:
                raise VerificationError("Play APK 包含 Linux 运行时制品")
            return
        if distribution != "sideload":
            raise VerificationError("未知发行渠道")
        values = dict(line.split("=", 1) for line in properties.read_text(encoding="utf-8").splitlines()
                      if line.strip() and not line.lstrip().startswith("#"))
        asset = "assets/" + values["alpine.asset"]
        required = {"lib/arm64-v8a/libnxproot.so", "lib/arm64-v8a/libnxproot-loader.so", asset}
        missing = required - names
        if missing:
            raise VerificationError(f"侧载 APK 缺少 Linux 制品：{', '.join(sorted(missing))}")
        digest = hashlib.sha256()
        with archive.open(asset) as stream:
            for chunk in iter(lambda: stream.read(READ_CHUNK_BYTES), b""):
                digest.update(chunk)
        if digest.hexdigest() != values["alpine.sha256"].strip().lower():
            raise VerificationError("Linux rootfs asset SHA-256 不符")


def verify_packaged_python_bytecode(apk: Path) -> None:
    """确认每个 ABI 的打包 Python zip 带有关键模块的不核对源码哈希 pyc，且同一 ABI 内 magic 一致。"""
    abis: dict[str, dict[str, bytes]] = {}
    libraries: dict[str, set[str]] = {}
    problems: list[str] = []
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            match = PYTHON_BYTECODE_ENTRY_PATTERN.fullmatch(info.filename.replace("\\", "/"))
            if match is None:
                continue
            abi, library = match.groups()
            libraries.setdefault(abi, set()).add(library)
            try:
                with zipfile.ZipFile(io.BytesIO(archive.read(info))) as packaged:
                    names = set(packaged.namelist())
                    for module in PYTHON_BYTECODE_MODULES[library]:
                        if module not in names:
                            problems.append(f"{abi}/{library} 缺少 {module}")
                            continue
                        header = packaged.read(module)[:8]
                        if len(header) < 8 or int.from_bytes(header[4:8], "little") != UNCHECKED_HASH_PYC_FLAGS:
                            problems.append(f"{abi}/{library} 的 {module} 不是不核对源码的哈希 pyc")
                            continue
                        abis.setdefault(abi, {})[f"{library}:{module}"] = header[:4]
            except zipfile.BadZipFile:
                problems.append(f"{abi}/{library} 不是有效 zip")
    if not libraries:
        problems.append("APK 未包含打包 Python")
    for abi, present in sorted(libraries.items()):
        for library in sorted(set(PYTHON_BYTECODE_MODULES) - present):
            problems.append(f"{abi} 缺少 {library}")
    for abi, headers in sorted(abis.items()):
        if len(set(headers.values())) > 1:
            problems.append(f"{abi} 打包 Python 的 pyc magic 不一致")
    if problems:
        raise VerificationError("打包 Python 字节码校验失败：" + "；".join(sorted(problems)))


def verify_tailnet_binary(apk: Path) -> None:
    """确认每个带打包 Python 的 ABI 都有内置 Tailscale 可执行文件，且是对应架构的 ELF。"""
    python_abis: set[str] = set()
    binaries: dict[str, bytes] = {}
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            name = info.filename.replace("\\", "/")
            python = PYTHON_BYTECODE_ENTRY_PATTERN.fullmatch(name)
            if python is not None:
                python_abis.add(python.group(1))
            tailnet = TAILNET_BINARY_PATTERN.fullmatch(name)
            if tailnet is not None:
                with archive.open(info) as stream:
                    binaries[tailnet.group(1)] = stream.read(20)
    problems: list[str] = []
    if not binaries:
        problems.append("APK 未包含 libnxtailnet.so")
    for abi in sorted(python_abis - set(binaries)):
        problems.append(f"{abi} 缺少 libnxtailnet.so")
    for abi, header in sorted(binaries.items()):
        expected = TAILNET_ELF_MACHINES.get(abi)
        if len(header) < 20 or header[:4] != b"\x7fELF":
            problems.append(f"{abi}/libnxtailnet.so 不是 ELF 可执行文件")
        elif expected is None or int.from_bytes(header[18:20], "little") != expected:
            problems.append(f"{abi}/libnxtailnet.so 的架构与 ABI 不符")
    if problems:
        raise VerificationError("内置 Tailscale 校验失败：" + "；".join(problems))


def parse_badging(output: str) -> dict[str, str]:
    package_lines = [line for line in output.splitlines() if line.startswith("package: ")]
    if len(package_lines) != 1:
        raise VerificationError("aapt badging 缺少唯一且完整的包身份字段")
    package_line = package_lines[0]
    matches = re.findall(
        r"(?:^|\s)(name|versionCode|versionName)='([^']*)'",
        package_line,
    )
    fields = dict(matches)
    if len(matches) != 3 or len(fields) != 3 or any(not value for value in fields.values()):
        raise VerificationError("aapt badging 缺少唯一且完整的包身份字段")
    return fields


def verify_badging(apk: Path, expected_package: str, version_code: str, version_name: str) -> None:
    output = run_tool([str(find_android_tool("aapt")), "dump", "badging", str(apk)])
    fields = parse_badging(output)
    expected = {
        "name": expected_package,
        "versionCode": version_code,
        "versionName": version_name,
    }
    if fields != expected:
        raise VerificationError(f"APK 包版本不匹配：实际 {fields!r}，预期 {expected!r}")


def verify_signature(apk: Path, expected_digest: str) -> None:
    expected = normalize_digest(expected_digest)
    if len(expected) != 64 or not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise VerificationError("预期签名证书 SHA-256 格式无效")
    output = run_tool(
        [str(find_android_tool("apksigner")), "verify", "--verbose", "--print-certs", str(apk)]
    )
    legacy_matches = re.findall(
        r"Signer #(\d+) certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)",
        output,
    )
    scheme_matches = re.findall(
        r"^V\d+(?:\.\d+)? Signer(?: #(\d+))?: certificate SHA-256 digest:\s*"
        r"([0-9A-Fa-f:]+)$",
        output,
        flags=re.MULTILINE,
    )
    declared_signers = re.findall(r"^Number of signers:\s*(\d+)\s*$", output, flags=re.MULTILINE)
    if len(declared_signers) > 1 or (declared_signers and declared_signers[0] != "1"):
        raise VerificationError("发行 APK 必须且只能包含单一 signer")
    if scheme_matches and not legacy_matches and len(declared_signers) != 1:
        raise VerificationError("发行 APK 必须提供明确的单一 signer 计数")
    if not legacy_matches and not scheme_matches:
        raise VerificationError("apksigner 未返回签名证书 SHA-256")
    legacy_signer_numbers = [number for number, _ in legacy_matches]
    if legacy_signer_numbers and legacy_signer_numbers != ["1"]:
        raise VerificationError("发行 APK 必须且只能包含单一 signer")
    digests = {
        normalize_digest(digest)
        for _, digest in [*legacy_matches, *scheme_matches]
    }
    if len(digests) != 1:
        raise VerificationError("发行 APK 必须且只能包含单一 signer")
    actual = next(iter(digests))
    if len(actual) != 64:
        raise VerificationError("apksigner 返回的证书 SHA-256 格式无效")
    if actual != expected:
        raise VerificationError("APK 签名证书与登记的发行证书不一致")


SHERPA_NATIVE_PATTERN = re.compile(r"lib/[^/]+/(?:libonnxruntime|libsherpa-onnx-(?:c-api|cxx-api|jni))\.so\Z")


def upstream_allowances(entry_name: str) -> dict[str, re.Pattern[bytes]]:
    if SHERPA_NATIVE_PATTERN.fullmatch(entry_name):
        return {"Linux 用户绝对路径": re.compile(rb"/home/runner/")}
    if TAILNET_BINARY_PATTERN.fullmatch(entry_name):
        return TAILNET_ALLOWANCES
    if PACKAGED_PYTHON_STDLIB_PATTERN.fullmatch(entry_name):
        return STDLIB_ALLOWANCES
    if PACKAGED_PYTHON_SITE_PATTERN.fullmatch(entry_name):
        return SITE_ALLOWANCES
    if PACKAGED_PYTHON_LIBRARY_PATTERN.fullmatch(entry_name):
        return UPSTREAM_BUILD_PATHS
    if APP_DEX_PATTERN.fullmatch(entry_name):
        return APP_DEX_ALLOWANCES
    return {}


def scan_stream(
    stream,
    entry_name: str,
    *,
    chunk_bytes: int = READ_CHUNK_BYTES,
) -> list[tuple[str, str]]:
    findings: list[tuple[str, str]] = []
    allowances = upstream_allowances(entry_name)
    tail = b""
    while True:
        chunk = stream.read(chunk_bytes)
        if not chunk:
            break
        candidate = tail + chunk
        for label, pattern in SENSITIVE_PATTERNS:
            allowed = allowances.get(label)
            if allowed is None:
                if pattern.search(candidate):
                    findings.append((entry_name, label))
            elif any(not allowed.fullmatch(match.group()) for match in pattern.finditer(candidate)):
                findings.append((entry_name, label))
        tail = candidate[-SCAN_OVERLAP_BYTES:]
    return findings


def scan_sensitive_content(apk: Path) -> None:
    findings: set[tuple[str, str]] = set()
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            normalized_name = info.filename.replace("\\", "/")
            encoded_name = normalized_name.encode("utf-8", errors="ignore")
            for label, pattern in SENSITIVE_PATTERNS:
                if pattern.search(encoded_name):
                    findings.add((normalized_name, label))
            if info.is_dir():
                continue
            with archive.open(info) as stream:
                findings.update(scan_stream(stream, normalized_name))
    if findings:
        summary = ", ".join(f"{entry} [{label}]" for entry, label in sorted(findings))
        raise VerificationError(f"APK 敏感信息扫描失败：{summary}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(READ_CHUNK_BYTES), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_checksum(apk: Path, checksum_output: Path) -> str:
    digest = sha256_file(apk)
    checksum_output.parent.mkdir(parents=True, exist_ok=True)
    checksum_output.write_text(f"{digest}  {apk.name}\n", encoding="utf-8")
    return digest


def main() -> int:
    args = parse_args()
    apk = args.apk.resolve()
    if not apk.is_file():
        raise VerificationError(f"APK 不存在：{apk}")
    size = verify_file_size(apk, args.max_size_bytes)

    verify_zip(apk)
    verify_local_inference_artifacts(apk, args.distribution)
    verify_recording_artifacts(apk)
    verify_packaged_python_bytecode(apk)
    verify_tailnet_binary(apk)
    verify_linux_runtime(apk, args.distribution)
    verify_badging(apk, args.expected_package, args.expected_version_code, args.expected_version_name)
    verify_signature(apk, args.expected_cert_sha256)
    scan_sensitive_content(apk)

    checksum_output = args.checksum_output or apk.with_suffix(apk.suffix + ".sha256")
    digest = write_checksum(apk, checksum_output)
    print(f"APK 验证通过：{apk.name}，{size} bytes，SHA-256 {digest}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except VerificationError as error:
        print(f"APK 验证失败：{error}", file=sys.stderr)
        raise SystemExit(1)
