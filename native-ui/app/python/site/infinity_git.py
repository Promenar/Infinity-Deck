"""Infinity Workbench 内置 git：把常用 git 子命令转交 dulwich 命令行。

不支持的子命令与全局选项返回 GIT_UNSUPPORTED 与指引，退出码 2；
其它失败输出单行 fatal 信息，退出码 128，与 git 一致。

远端认证（由 Infinity Workbench 内置执行后端按命令注入环境变量，令牌与私钥始终留在 App 进程内）：
  INFINITY_GIT_NONCE：本条命令的一次性 nonce，命令结束即失效；
  INFINITY_GIT_PROXY 与 INFINITY_GIT_CREDENTIAL_HOSTS：HTTPS 凭据注入代理地址（127.0.0.1:<端口>）与已配置凭据的主机键
      （逗号分隔，非 443 端口写作 主机:端口）。对这些主机的 HTTPS 请求改发到
      http://127.0.0.1:<端口>/<主机键>/<路径>，带 X-Infinity-Git-Nonce 请求头，由代理加 Authorization 后转发；
      其它主机保持直连。
  INFINITY_GIT_SSH_BRIDGE：SSH 桥地址（127.0.0.1:<端口>）。SSH 远端（git@主机:路径、ssh://）改由自定义 dulwich
      SSH vendor 连接 SSH 桥，协议见 _BridgeConnection；没有该变量时 SSH 远端返回 GIT_UNSUPPORTED。

App 内部只读检查入口（不在用法说明与 SUPPORTED 中，供 Git 变更面板使用，不经过 shell_exec）：

    python -m infinity_git --infinity-inspect <操作> --output <文件> [--repo <目录>] [参数]

只读取仓库，不写索引、配置与工作区；结果是写入 --output 的单个 UTF-8 JSON 对象（先写 <文件>.tmp 再改名），
写出后退出码 0。参数错误时不写文件，向 stderr 输出原因，退出码 2。
--repo 默认为当前目录，必须是仓库根（含 .git），不向上查找，避免读取工作区之外的仓库；
不是仓库或是裸仓库时输出 {"version": 1, "repository": false}。
INFINITY_WORKSPACE_KIND=shared 时，仓库配置未设置的 core.symlinks、core.filemode、core.ignorecase
只在内存中按共享存储取值（false、false、true），不写回配置。

操作与输出（除 repository 外均带 "version": 1、"repository": true）：
  status [--limit N]：{"repository_name": 脱敏的 owner/repo 或仓库根目录名,
      "branch": 分支名或 null, "detached": 游离 HEAD, "head": 提交 sha 或 null, "unborn": 尚无提交,
      "staged" | "unstaged" | "untracked": [{"path", "change"}], "<组>_truncated": 布尔}
      change 取 added、modified、deleted；已暂存为 HEAD 对索引，未暂存为索引对工作区，未跟踪逐个列出文件（不含忽略项）。
      每组最多 N 条（默认 2000，上限 10000），超出时截断标志为 true。
  read --blob <版本>:<路径> [--blob ...]：{"blobs": [{"rev", "path", "exists", "mode", "symlink", "submodule",
      "binary", "size", "truncated", "text"}]}。版本取 HEAD、INDEX、完整提交 sha 或 <sha>^（第一个父提交）。
      text 为 UTF-8 文本（无法解码的字节替换为 U+FFFD）；二进制（前 8000 字节含 NUL）或超过 512 KiB 时 text 为 null，
      超限时 truncated 为 true。子模块返回 "Subproject commit <sha>" 文本，符号链接返回链接目标文本。
  log [--limit N]：{"commits": [{"sha", "short_sha", "author_name", "author_email", "author_time", "author_tz",
      "title", "parents"}], "truncated": 布尔}。默认 50 条，上限 500。
  commit --commit <sha> [--limit N]：{"commit": {同 log 条目, "message"}, "files": [{"path", "change", "old_path"}],
      "files_truncated": 布尔}。与第一个父提交比较（根提交与空树比较），change 另含 renamed（old_path 为原路径）。
  失败时输出 {"version": 1, "repository": true, "error": {"code", "message"}}，code 取 INVALID_ARGUMENT、
  NOT_FOUND、INSPECT_FAILED。
"""

import io
import os
import re
import sys
import time

_ENTRY_WALL_MS = time.time_ns() // 1_000_000
_ENTRY_MONOTONIC = time.perf_counter_ns()

UNSUPPORTED_EXIT = 2
FATAL_EXIT = 128

# 只开放日常版本管理所需的子命令；服务端、维护与需要外部程序的子命令不开放。
SUPPORTED = (
    "add", "blame", "branch", "cat-file", "checkout", "cherry-pick", "clean", "clone", "commit", "config",
    "describe", "diff", "fetch", "for-each-ref", "grep", "init", "log", "ls-files", "ls-remote", "ls-tree",
    "merge", "merge-base", "mv", "pull", "push", "rebase", "reflog", "remote", "reset", "restore", "rev-list",
    "rev-parse", "revert", "rm", "shortlog", "show", "show-ref", "stash", "status", "switch", "tag",
)
# 会写入提交对象的子命令：没有身份时像 git 一样拒绝，而不是落成 u0_aXXX@localhost。
NEEDS_IDENTITY = ("commit", "merge", "cherry-pick", "revert", "rebase", "stash")
NETWORK = ("clone", "fetch", "pull", "push", "ls-remote")
FALLBACK = "完整 git 请改用 termux_exec 或 ssh_exec。"
_SCP_LIKE = re.compile(r"^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:")


def _patch_midx_offsets():
    """按 git 的读法解析多包索引（multi-pack-index）的对象偏移。

    git 只在偏移超过 32 位时才写大偏移表 LOFF；2–4 GiB 之间的偏移直接以 32 位存放，最高位为 1。
    dulwich 把所有最高位为 1 的偏移都当作 LOFF 下标，遇到没有 LOFF 的索引就无法读取仓库。
    这里与 git 一致：只有存在 LOFF 时，最高位才表示大偏移表下标。
    """
    import struct

    from dulwich import midx

    index_class = getattr(midx, "MultiPackIndex", None)
    if index_class is None or getattr(index_class, "_infinity_git_offsets", False):
        return

    def pack_info(self, index):
        if index < 0 or index >= self.object_count:
            raise IndexError("Index %d out of range" % index)
        entry = self._ooff_offset + index * 8
        pack_id, pack_offset = struct.unpack(">LL", self._contents[entry : entry + 8])
        if pack_offset & 0x80000000 and midx.CHUNK_LOFF in self._chunks:
            position = self._loff_offset + (pack_offset & 0x7FFFFFFF) * 8
            (pack_offset,) = struct.unpack(">Q", self._contents[position : position + 8])
        return pack_id, pack_offset

    index_class._get_pack_info = pack_info
    index_class._infinity_git_offsets = True


def _unsupported(message):
    print("git: GIT_UNSUPPORTED: " + message, file=sys.stderr)
    return UNSUPPORTED_EXIT


def _fatal(message):
    print("fatal: " + message, file=sys.stderr)
    return FATAL_EXIT


def _version():
    from dulwich import __version__

    version = __version__ if isinstance(__version__, str) else ".".join(str(part) for part in __version__)
    return "git version dulwich-" + version + " (Infinity Workbench builtin)"


def _usage():
    return "\n".join([
        "用法：git [-C <目录>] <子命令> [参数]（Infinity Workbench 内置，基于 dulwich）",
        "支持的子命令：" + " ".join(SUPPORTED),
        "clone [--depth <N>] [-b <分支>] [--single-branch | --no-single-branch] [--no-tags] [--bare] <地址> [<目录>]",
        "  --depth 隐含 --single-branch：只取远端默认分支（或 -b 指定的分支），标签只跟随指向已抓取提交的标签；",
        "  --no-single-branch 取全部分支与标签；--no-tags 不取标签。单分支克隆后 fetch 仍只取该分支。",
        "fetch [<远端>] [--depth <N>] [--tags | --no-tags]：省略远端时取当前分支的上游远端或 origin。",
        "工作区：私有工作区计入应用备份上限且卸载即删除，clone 前会提示改用项目文件夹或共享文件夹；",
        "  共享文件夹的仓库自动设置 core.symlinks=false、core.filemode=false、core.ignorecase=true，",
        "  符号链接检出为普通文件，仅大小写不同的路径只保留一个。",
        "远端：HTTPS 与 SSH（git@<主机>:<路径>、ssh://）。",
        "  私有仓库的 HTTPS 凭据请在 Infinity Workbench 设置 → 执行环境与连接 → Git 凭据 中按主机保存（用户名 + 令牌），内置 git 自动使用；",
        "  不要把令牌写进 URL、命令或文件（URL 中自带的凭据仍可用但不推荐，会写入仓库配置）。",
        "  SSH 远端使用 设置 → 工具 → SSH 主机 中已固定主机密钥的主机（例如主机 github.com、用户 git），私钥不离开 App。",
        FALLBACK,
    ])


def _ssh_url(value):
    return value.startswith("ssh://") or value.startswith("git+ssh://") or bool(_SCP_LIKE.match(value))


ENV_NONCE = "INFINITY_GIT_NONCE"
ENV_PROXY = "INFINITY_GIT_PROXY"
ENV_CREDENTIAL_HOSTS = "INFINITY_GIT_CREDENTIAL_HOSTS"
ENV_SSH_BRIDGE = "INFINITY_GIT_SSH_BRIDGE"
NONCE_HEADER = "X-Infinity-Git-Nonce"
PROXY_ERROR_HEADER = "X-Infinity-Git-Error"
CREDENTIAL_SETTINGS = "设置 → 执行环境与连接 → Git 凭据"
SSH_UNAVAILABLE = "当前环境没有 SSH 桥（只在 Infinity Workbench 的 shell_exec / python_exec 中可用），请改用 HTTPS 地址。"


class _RemoteAuthError(Exception):
    """远端认证相关的失败：消息是给用户看的完整说明，不含令牌、nonce 或私钥。

    不继承 dulwich 的 GitProtocolError：dulwich 的 clone 会吞掉 GitProtocolError 并以 0 退出。
    """

    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


def _loopback_address(value):
    """解析 127.0.0.1:<端口>；其它地址一律不用，避免环境变量把流量引到别处。"""
    host, _, port = (value or "").strip().rpartition(":")
    if host != "127.0.0.1" or not port.isdigit() or not 0 < int(port) < 65536:
        return None
    return host, int(port)


def _nonce():
    value = os.environ.get(ENV_NONCE, "").strip()
    return value if re.fullmatch(r"[A-Za-z0-9_-]{16,128}", value) else None


def _https_host_key(url):
    """https URL 的凭据主机键（小写主机名，非 443 端口为 主机:端口）；不是 https 时返回 None。"""
    from urllib.parse import urlsplit

    try:
        parts = urlsplit(url)
        port = parts.port
    except ValueError:
        return None
    if parts.scheme.lower() != "https" or not parts.hostname:
        return None
    host = parts.hostname.lower()
    return host if port in (None, 443) else "%s:%d" % (host, port)


class _CredentialProxy:
    """把已配置凭据主机的 HTTPS 地址改写到本机凭据注入代理，并能把代理地址还原为原地址。"""

    def __init__(self, port, nonce, hosts):
        self.origin = "http://127.0.0.1:%d" % port
        self.prefix = self.origin + "/"
        self.nonce = nonce
        self.hosts = hosts

    @classmethod
    def from_environment(cls):
        address = _loopback_address(os.environ.get(ENV_PROXY))
        nonce = _nonce()
        hosts = {item.strip().lower() for item in os.environ.get(ENV_CREDENTIAL_HOSTS, "").split(",") if item.strip()}
        if address is None or nonce is None or not hosts:
            return None
        return cls(address[1], nonce, hosts)

    def outbound(self, url):
        """已配置主机的 https URL 改写为代理 URL（不带 URL 中的用户信息）；其它 URL 返回 None。"""
        from urllib.parse import urlsplit

        key = _https_host_key(url)
        if key is None or key not in self.hosts:
            return None
        parts = urlsplit(url)
        return self.prefix + key + (parts.path or "/") + ("?" + parts.query if parts.query else "")

    def inbound(self, url):
        """代理 URL 还原为原 https URL；urllib3 未重定向时只给出路径，按代理地址补全。"""
        if url and url.startswith("/"):
            url = self.origin + url
        if url and url.startswith(self.prefix):
            return "https://" + url[len(self.prefix):]
        return url


_UPSTREAM_AUTH_CODES = ("CREDENTIAL_REJECTED", "ACCESS_DENIED")


def _proxy_failure(resp, url, key):
    """把代理或远端的失败状态转成说明；返回 None 表示按 dulwich 原有方式处理。"""
    code = resp.headers.get(PROXY_ERROR_HEADER)
    if code:
        try:
            detail = resp.read(4096).decode("utf-8", "replace").strip()
        except Exception:
            detail = ""
        if code in _UPSTREAM_AUTH_CODES and detail:
            # 上游认证失败由代理按凭据来源写好处理办法，原样转给用户。
            return _RemoteAuthError(code, detail)
        return _RemoteAuthError(code, "Git 凭据代理拒绝请求：%s" % (detail or "无说明"))
    if resp.status == 401:
        return _RemoteAuthError(
            "CREDENTIAL_REJECTED",
            "%s 拒绝了已保存的 Git 凭据（HTTP 401）。请在 %s 中检查该主机的用户名与令牌是否有效。" % (key, CREDENTIAL_SETTINGS),
        )
    if resp.status == 403:
        return _RemoteAuthError(
            "ACCESS_DENIED",
            "%s 拒绝访问 %s（HTTP 403）：令牌可能缺少该仓库的读写权限或已过期，请在 %s 中更新。" % (key, url, CREDENTIAL_SETTINGS),
        )
    return None


def _install_credential_proxy(proxy):
    """接管 dulwich HTTP 客户端的请求：只改写已配置凭据主机，其它请求原样直连。"""
    from dulwich.client import Urllib3HttpGitClient
    from dulwich.errors import GitProtocolError, NotGitRepository

    original = Urllib3HttpGitClient.__dict__["_http_request"]
    original = getattr(original, "_infinity_original", original)

    def _http_request(self, url, headers=None, data=None, raise_for_status=True):
        target = proxy.outbound(url)
        if target is None:
            return original(self, url, headers, data, raise_for_status)
        merged = dict(headers or {})
        merged[NONCE_HEADER] = proxy.nonce
        resp, read = original(self, target, merged, data, False)
        if raise_for_status and resp.status != 200:
            failure = _proxy_failure(resp, url, _https_host_key(url))
            if failure is not None:
                raise failure
            if resp.status == 404:
                raise NotGitRepository
            raise GitProtocolError("unexpected http resp %d for %s" % (resp.status, url))
        # 重定向后的地址还原成原主机，dulwich 据此更新后续请求的基址，再次经过这里改写。
        final = proxy.inbound(resp.geturl() or target)
        resp.redirect_location = final if final != url else ""
        return resp, read

    _http_request._infinity_original = original
    Urllib3HttpGitClient._http_request = _http_request


class _BridgeStderr:
    """SSH 远端 stderr：dulwich 只在远端挂断后 readlines()，这时等待桥送完全部 stderr。"""

    def __init__(self, connection):
        self._connection = connection

    def readlines(self):
        self._connection.wait(10)
        return [line + b"\n" for line in bytes(self._connection.stderr_data).splitlines()]

    def read(self, size=-1):
        self._connection.wait(10)
        data = bytes(self._connection.stderr_data)
        return data if size is None or size < 0 else data[:size]

    def close(self):
        pass


class _BridgeConnection:
    """经 Infinity Workbench SSH 桥执行一条远端 git 命令，提供 dulwich SSH vendor 需要的 read/write/close/can_read/stderr。

    协议：先发送一行 JSON {"nonce", "host", "port", "user", "command", "protocol"}，
    收到一行 JSON {"ok": true} 或 {"ok": false, "code", "message"}；之后双方交换帧
    （1 字节类型 + 4 字节大端长度 + 载荷，单帧最多 64 KiB）：
    客户端发送 0 = stdin 数据、4 = stdin 结束；服务端发送 1 = stdout、2 = stderr、3 = 退出码（4 字节有符号大端）。
    """

    MAX_FRAME = 65536

    def __init__(self, address, nonce, host, port, username, command, protocol_version):
        import json
        import socket
        import threading

        try:
            text = command.decode("utf-8") if isinstance(command, bytes) else command
        except UnicodeDecodeError:
            raise _RemoteAuthError("COMMAND_REJECTED", "远端仓库路径不是有效的 UTF-8。")
        try:
            self._sock = socket.create_connection(address, timeout=30)
        except OSError as error:
            raise _RemoteAuthError("SSH_BRIDGE_UNAVAILABLE", "无法连接 Infinity Workbench SSH 桥（%s）。" % type(error).__name__)
        try:
            request = {"nonce": nonce, "host": host, "port": port, "user": username, "command": text,
                       "protocol": protocol_version or 0}
            self._sock.sendall((json.dumps(request, ensure_ascii=False) + "\n").encode("utf-8"))
            self._input = self._sock.makefile("rb")
            # 远端握手与认证可能较慢，等待回复期间不设超时，由命令超时兜底。
            self._sock.settimeout(None)
            line = self._input.readline(8193)
            try:
                reply = json.loads(line.decode("utf-8")) if line else {}
            except ValueError:
                reply = {}
            if not isinstance(reply, dict) or reply.get("ok") is not True:
                reply = reply if isinstance(reply, dict) else {}
                raise _RemoteAuthError(
                    str(reply.get("code") or "SSH_BRIDGE_FAILED"),
                    str(reply.get("message") or "Infinity Workbench SSH 桥没有建立远端连接。"),
                )
        except BaseException:
            self._sock.close()
            raise
        read_end, self._pipe = os.pipe()
        self._stdout = os.fdopen(read_end, "rb")
        self.read = self._stdout.read
        self.stderr_data = bytearray()
        self.exit_code = None
        self._done = threading.Event()
        self._write_lock = threading.Lock()
        self._closed = False
        self._thread = threading.Thread(target=self._pump, name="infinity-git-ssh", daemon=True)
        self._thread.start()

    @property
    def stderr(self):
        return _BridgeStderr(self)

    def _pump(self):
        try:
            while True:
                head = self._input.read(5)
                if len(head) < 5:
                    break
                kind, size = head[0], int.from_bytes(head[1:], "big")
                if size > self.MAX_FRAME:
                    break
                payload = self._input.read(size) if size else b""
                if len(payload) < size:
                    break
                if kind == 1:
                    view = memoryview(payload)
                    while view:
                        view = view[os.write(self._pipe, view):]
                elif kind == 2:
                    self.stderr_data += payload
                elif kind == 3 and size == 4:
                    self.exit_code = int.from_bytes(payload, "big", signed=True)
                    break
                else:
                    break
        except (OSError, ValueError):
            pass
        finally:
            try:
                os.close(self._pipe)
            except OSError:
                pass
            self._done.set()

    def wait(self, timeout):
        return self._done.wait(timeout)

    def _frame(self, kind, data=b""):
        with self._write_lock:
            self._sock.sendall(bytes([kind]) + len(data).to_bytes(4, "big") + data)

    def write(self, data):
        data = bytes(data)
        for start in range(0, len(data), self.MAX_FRAME):
            self._frame(0, data[start:start + self.MAX_FRAME])
        return len(data)

    def can_read(self):
        import select

        return len(select.select([self._stdout.fileno()], [], [], 0)[0]) > 0

    def close(self, timeout=10):
        if self._closed:
            return
        self._closed = True
        try:
            self._frame(4)
        except OSError:
            pass
        self._done.wait(timeout)
        try:
            self._sock.close()
        finally:
            self._stdout.close()
            self._thread.join(1)


class _BridgeVendor:
    """dulwich SSH vendor：远端命令经 Infinity Workbench SSH 桥执行，认证使用 App 中已保存的 SSH 主机。"""

    def __init__(self, address, nonce):
        self.address = address
        self.nonce = nonce

    def run_command(self, host, command, username=None, port=None, password=None, key_filename=None,
                    ssh_command=None, protocol_version=None):
        if password is not None or key_filename is not None:
            raise _RemoteAuthError(
                "SSH_OPTION_REJECTED", "内置 git 的 SSH 远端只使用 设置 → 工具 → SSH 主机 中保存的认证，不接受密码或密钥文件。"
            )
        if protocol_version is None:
            from dulwich.protocol import DEFAULT_GIT_PROTOCOL_VERSION_FETCH

            protocol_version = DEFAULT_GIT_PROTOCOL_VERSION_FETCH
        return _BridgeConnection(self.address, self.nonce, host, port, username, command, protocol_version)

    @classmethod
    def from_environment(cls):
        address = _loopback_address(os.environ.get(ENV_SSH_BRIDGE))
        nonce = _nonce()
        if address is None or nonce is None:
            return None
        return cls(address, nonce)


def _install_remote_auth():
    """按环境变量接上 HTTPS 凭据代理与 SSH 桥。"""
    import dulwich.client

    proxy = _CredentialProxy.from_environment()
    if proxy is not None:
        _install_credential_proxy(proxy)
    vendor = _BridgeVendor.from_environment()
    if vendor is not None:
        dulwich.client.get_ssh_vendor = lambda: vendor


def _has_identity():
    from dulwich.repo import Repo

    def configured(kind):
        name = os.environ.get("GIT_%s_NAME" % kind)
        email = os.environ.get("GIT_%s_EMAIL" % kind)
        return bool(name and email)

    if configured("AUTHOR") and configured("COMMITTER"):
        return True
    try:
        config = Repo.discover(".").get_config_stack()
    except Exception:
        return True  # 不在仓库中时交给 dulwich 报告原因
    try:
        return bool(config.get((b"user",), b"name")) and bool(config.get((b"user",), b"email"))
    except KeyError:
        return False


def _has_message(args):
    for arg in args:
        if arg in ("-m", "--message", "-C", "--reuse-message", "-c", "--reedit-message", "--amend"):
            return True
        if arg.startswith("--message=") or (arg.startswith("-") and not arg.startswith("--") and "m" in arg[1:]):
            return True
    return False


class _ProgressCollapser(io.RawIOBase):
    """只保留以回车刷新的进度行的最终状态：输出不接终端，逐次刷新会把真正的结果挤出输出尾部。"""

    def __init__(self, raw):
        super().__init__()
        self._raw = raw
        self._line = bytearray()
        self._carriage = False

    def writable(self):
        return True

    def write(self, data):
        for byte in bytes(data):
            if self._carriage:
                self._carriage = False
                if byte != 10:
                    self._line.clear()
            if byte == 13:
                self._carriage = True
            elif byte == 10:
                self._raw.write(bytes(self._line) + b"\n")
                self._line.clear()
            else:
                self._line.append(byte)
        return len(data)

    def finish(self):
        if self._line:
            self._raw.write(bytes(self._line) + b"\n")
            self._line.clear()
        self._raw.flush()


def _collapse_progress():
    """返回结束时调用的收尾函数；必须在导入 dulwich.cli 之前替换，其参数默认值在导入时绑定 sys.stderr.buffer。"""
    if sys.stderr is None or sys.stderr.isatty():
        return lambda: None
    original = sys.stderr
    collapser = _ProgressCollapser(original.buffer)
    sys.stderr = io.TextIOWrapper(io.BufferedWriter(collapser), encoding=original.encoding or "utf-8",
                                  errors="backslashreplace", line_buffering=True)

    def finish():
        sys.stderr.flush()
        collapser.finish()
        sys.stderr = original

    return finish


_COUNT_SHORTHAND = re.compile(r"^-([0-9]+)$")
HEAD_REF = b"HEAD"
HEADS_PREFIX = b"refs/heads/"
TAGS_PREFIX = b"refs/tags/"
PEELED_SUFFIX = b"^{}"
# git 默认只取分支与标签；dulwich 的 clone/fetch 默认要远端通告的全部引用，
# GitHub 会通告每个 PR 的 refs/pull/*，--depth 1 克隆热门仓库也会下载数百 MB。
DEFAULT_FETCH_REFS = (HEAD_REF, HEADS_PREFIX, TAGS_PREFIX)
# dulwich.cli clone 不认识的 git 选项，由包装层消化后剔除。
CLONE_WRAPPER_FLAGS = ("--single-branch", "--no-single-branch", "--no-tags")


class _FetchPolicy:
    """一次 clone/fetch 的抓取范围，由包装层按 git 语义算出，供 dulwich fetch 钩子使用。

    single_branch 为真时只抓 branches 列出的分支；branches 为 None 表示远端 HEAD 指向的默认分支。
    tags 取 "all"（全部标签）、"follow"（只跟随指向已抓取对象的标签）或 "none"。
    """

    def __init__(self, single_branch=False, branches=None, tags="all", clone=False, shared=False):
        self.single_branch = single_branch
        self.branches = branches
        self.tags = tags
        self.clone = clone
        self.shared = shared
        self.clone_path = None
        self.bare = False


_POLICY = None


class _RemoteRefMissing(Exception):
    pass


def _clone_policy(args, shared=False):
    """按 git clone 语义解析抓取范围：--depth 隐含 --single-branch，single-branch 时标签改为自动跟随。"""
    depth = branch = single = None
    no_tags = bare = False
    index = 0
    while index < len(args):
        arg = args[index]
        name, has_value, value = arg.partition("=")
        if arg in ("--depth", "-b", "--branch") and index + 1 < len(args):
            name, value, has_value = arg, args[index + 1], True
            index += 1
        if has_value and name == "--depth":
            depth = value
        elif has_value and name in ("-b", "--branch"):
            branch = value
        elif arg == "--single-branch":
            single = True
        elif arg == "--no-single-branch":
            single = False
        elif arg == "--no-tags":
            no_tags = True
        elif arg == "--bare":
            bare = True
        index += 1
    if single is None:
        try:
            single = depth is not None and int(depth) > 0
        except ValueError:
            single = False
    policy = _FetchPolicy(
        single_branch=single,
        branches=[branch.encode()] if single and branch else None,
        tags="none" if no_tags else ("follow" if single else "all"),
        clone=True,
        shared=shared,
    )
    policy.bare = bare
    return policy


def _fetch_location(args):
    """返回 fetch 参数中的远端位置（第一个位置参数），没有时返回 None。"""
    index = 0
    while index < len(args):
        arg = args[index]
        if arg in ("--depth", "--shallow-since", "--shallow-exclude"):
            index += 2
            continue
        if not arg.startswith("-"):
            return arg
        index += 1
    return None


def _default_remote():
    """像 git 一样取当前分支的上游远端，没有时用 origin。"""
    from dulwich.repo import Repo

    try:
        repo = Repo.discover(".")
    except Exception:
        return "origin"
    try:
        head = repo.refs.read_ref(HEAD_REF) or b""
        if head.startswith(b"ref: " + HEADS_PREFIX):
            branch = head[len(b"ref: " + HEADS_PREFIX):].strip()
            return repo.get_config().get((b"branch", branch), b"remote").decode()
    except (KeyError, ValueError):
        pass
    finally:
        repo.close()
    return "origin"


def _fetch_policy(args, shared=False):
    """按远端配置的 fetch refspec 与 tagOpt 决定 fetch 范围：单分支克隆后的 fetch 仍只取该分支。"""
    from dulwich.repo import Repo

    policy = _FetchPolicy(shared=shared)
    location = _fetch_location(args)
    tag_option = None
    refspecs = []
    if location is not None and "--all" not in args:
        try:
            repo = Repo.discover(".")
        except Exception:
            repo = None
        if repo is not None:
            try:
                config = repo.get_config()
                section = (b"remote", location.encode())
                if config.has_section(section):  # URL 或未配置的远端：保持全部分支与标签
                    try:
                        refspecs = list(config.get_multivar(section, b"fetch"))
                    except KeyError:
                        pass
                    try:
                        tag_option = config.get(section, b"tagOpt")
                    except KeyError:
                        pass
            finally:
                repo.close()
    branches = []
    for refspec in refspecs:
        source = refspec.lstrip(b"+").split(b":", 1)[0]
        if not source.startswith(HEADS_PREFIX):
            continue
        if b"*" in source:
            branches = []
            break
        branches.append(source[len(HEADS_PREFIX):])
    if branches:
        policy.single_branch = True
        policy.branches = branches
        policy.tags = "follow"
    if "--tags" in args or (tag_option == b"--tags" and "--no-tags" not in args):
        policy.tags = "all"
    elif "--no-tags" in args or tag_option == b"--no-tags":
        policy.tags = "none"
    return policy


def _wanted_refs(policy, refs):
    """从远端通告的引用中挑出本次要抓取的引用。"""
    wanted = {}
    for name, sha in refs.items():
        if sha is None or name.endswith(PEELED_SUFFIX):
            continue
        if name.startswith(TAGS_PREFIX):
            if policy.tags == "all":
                wanted[name] = sha
        elif not policy.single_branch and (name == HEAD_REF or name.startswith(HEADS_PREFIX)):
            wanted[name] = sha
    if not policy.single_branch:
        return wanted
    if policy.branches is None:
        if refs.get(HEAD_REF) is not None:
            wanted[HEAD_REF] = refs[HEAD_REF]
        return wanted
    for branch in policy.branches:
        candidates = [HEADS_PREFIX + branch]
        if policy.clone:
            candidates.append(TAGS_PREFIX + branch)  # git clone -b 也接受标签
        for name in candidates:
            if refs.get(name) is not None:
                wanted[name] = refs[name]
                break
        else:
            if policy.clone:
                raise _RemoteRefMissing("Remote branch %s not found in upstream origin" % branch.decode())
    return wanted


def _fetch_ref_prefix(policy):
    if not policy.single_branch:
        return [prefix for prefix in DEFAULT_FETCH_REFS if policy.tags == "all" or prefix != TAGS_PREFIX]
    prefixes = [HEAD_REF]
    if policy.branches is None:
        # 默认分支名要等 symref 才知道；通告里必须带上它，dulwich 才会建立远端跟踪引用与本地分支。
        prefixes.append(HEADS_PREFIX)
    for branch in policy.branches or ():
        prefixes += [HEADS_PREFIX + branch, TAGS_PREFIX + branch]
    if policy.tags == "all":
        prefixes.append(TAGS_PREFIX)
    return prefixes


def _remote_tags(client, path, protocol_version):
    """返回远端标签及其剥离后指向的对象；本地远端的通告不含剥离值，直接打开仓库剥离。"""
    from dulwich.client import LocalGitClient

    listing = client.get_refs(path, protocol_version=protocol_version, ref_prefix=[TAGS_PREFIX])
    tags, peeled = {}, {}
    for name, sha in listing.refs.items():
        if sha is None or not name.startswith(TAGS_PREFIX):
            continue
        if name.endswith(PEELED_SUFFIX):
            peeled[name[:-len(PEELED_SUFFIX)]] = sha
        else:
            tags[name] = sha
    if isinstance(client, LocalGitClient):
        with client._open_repo(path) as repo:
            for name in tags:
                try:
                    peeled[name] = repo.get_peeled(name)
                except KeyError:
                    pass
    return tags, peeled


def _follow_tags(original, client, path, target, kwargs, fetched):
    """git 的标签自动跟随：只取指向本地已有对象（已抓取历史）的标签。

    附注标签对象不在第一次抓取的包里，需要第二次抓取；此时 graph walker 以已抓到的提交为 have，
    服务端只发送标签对象本身，不会重新发送它指向的快照。
    """
    from dulwich.client import LocalGitClient
    from dulwich.objects import Tag

    store = target.object_store
    tags, peeled = _remote_tags(client, path, kwargs.get("protocol_version"))
    followed, pending = {}, {}
    for name, sha in tags.items():
        if sha in store:
            followed[name] = sha
        elif peeled.get(name) is not None and peeled[name] in store:
            pending[name] = sha
    if not pending:
        return followed
    if isinstance(client, LocalGitClient):
        # dulwich 的本地服务端遇到浅仓库会丢弃 have（repo.find_missing_objects），第二次抓取会重发整份快照；
        # 本地远端直接复制标签对象链即可。
        with client._open_repo(path) as source:
            for sha in set(pending.values()):
                while sha not in store:
                    item = source.object_store[sha]
                    store.add_object(item)
                    if not isinstance(item, Tag):
                        break
                    sha = item.object[1]
        followed.update(pending)
        return followed
    heads = [sha for sha in fetched if sha in store]
    heads += [sha for sha in target.refs.as_dict(HEADS_PREFIX.rstrip(b"/")).values() if sha in store]
    walker = type(target).get_graph_walker
    target.get_graph_walker = lambda heads_arg=None: walker(target, heads=heads_arg or heads)
    try:
        original(
            client, path, target,
            determine_wants=lambda refs, depth=None: sorted(set(pending.values())),
            progress=kwargs.get("progress"),
            ref_prefix=[TAGS_PREFIX],
            protocol_version=kwargs.get("protocol_version"),
        )
    finally:
        del target.get_graph_walker
    followed.update({name: sha for name, sha in pending.items() if sha in store})
    return followed


def _policy_fetch(original, client, path, target, policy, kwargs):
    if policy.clone:
        policy.clone_path = getattr(target, "path", None)
        if policy.shared:
            # clone 在 fetch 之后才检出并重新读取仓库配置，这里写入才能让 core.symlinks=false 对首次检出生效。
            _write_shared_config(target, force=True)
    selected = {}

    def determine_wants(refs, depth=None):
        selected.clear()
        selected.update(_wanted_refs(policy, refs))
        return target.object_store.determine_wants_all(selected, depth=depth)

    kwargs["ref_prefix"] = _fetch_ref_prefix(policy)
    if policy.tags == "follow" and hasattr(client, "_fetch_capabilities"):
        # 协议 v0/v1 下让服务端随包附上指向所发对象的附注标签（git 自动跟随的主路径）；
        # dulwich 在协议 v2 不发送 include-tag，由 _follow_tags 的第二次抓取补齐。
        from dulwich.protocol import CAPABILITY_INCLUDE_TAG

        client._fetch_capabilities.add(CAPABILITY_INCLUDE_TAG)
    result = original(client, path, target, determine_wants=determine_wants, **kwargs)
    store = target.object_store
    if policy.single_branch and policy.branches is None and HEAD_REF in selected:
        head_target = result.symrefs.get(HEAD_REF)
        if head_target and head_target.startswith(HEADS_PREFIX):
            selected[head_target] = selected[HEAD_REF]  # 抓取的正是 HEAD 指向的提交
    # 只交回本次选中且对象已在本地的引用：dulwich 会把交回的分支与标签全部导入为远端跟踪引用和本地标签。
    refs = {name: sha for name, sha in selected.items() if sha in store}
    if policy.tags == "follow":
        try:
            refs.update(_follow_tags(original, client, path, target, kwargs, list(refs.values())))
        except Exception as error:  # 标签跟随失败不影响分支本身
            print("warning: 跳过标签自动跟随：%s" % (str(error).strip() or type(error).__name__), file=sys.stderr)
    result.refs = refs
    if policy.clone and policy.single_branch:
        _record_single_branch(target, policy, refs)
    return result


def _record_single_branch(target, policy, refs):
    """像 git clone --single-branch 一样把 fetch refspec 收窄为该分支，--no-tags 记为 tagOpt。"""
    branches = [name[len(HEADS_PREFIX):] for name in refs if name.startswith(HEADS_PREFIX)]
    config = target.get_config()
    section = (b"remote", b"origin")
    if not config.has_section(section):
        return
    if len(branches) == 1:
        branch = branches[0]
        config.set(section, b"fetch", b"+refs/heads/" + branch + b":refs/remotes/origin/" + branch)
    if policy.tags == "none":
        config.set(section, b"tagOpt", b"--no-tags")
    config.write_to_path()


def _limit_fetched_refs():
    from dulwich.client import GitClient, LocalGitClient

    # LocalGitClient 覆盖了 fetch 且忽略 ref_prefix，两处都要接管。
    for cls in (GitClient, LocalGitClient):
        original = cls.__dict__["fetch"]
        if not getattr(original, "_infinity_limited", False):
            cls.fetch = _wrap_fetch(original)


def _wrap_fetch(original):
    def fetch(self, path, target, *args, **kwargs):
        policy = _POLICY
        if args or kwargs.get("determine_wants") is not None or policy is None:
            # pull 等自带 determine_wants 的调用只限制通告范围。
            if not args and kwargs.get("ref_prefix") is None:
                kwargs["ref_prefix"] = list(DEFAULT_FETCH_REFS)
            return original(self, path, target, *args, **kwargs)
        kwargs.pop("determine_wants", None)
        return _policy_fetch(original, self, path, target, policy, kwargs)

    fetch._infinity_limited = True
    return fetch


WORKSPACE_KINDS = ("private", "project", "shared")
SHARED_CORE_CONFIG = ((b"symlinks", b"false"), (b"filemode", b"false"), (b"ignorecase", b"true"))
PRIVATE_CLONE_HINT = (
    "提示：当前是会话私有工作区，内容计入应用备份上限（16 MiB / 10 000 项），"
    "克隆产生的 .git 对象会使备份超限失败，且卸载应用即删除。"
    "建议改用自选工作区（项目文件夹或共享文件夹）后再克隆。"
)


def _workspace_kind():
    kind = os.environ.get("INFINITY_WORKSPACE_KIND", "").strip().lower()
    return kind if kind in WORKSPACE_KINDS else "private"


def _write_shared_config(repo, force):
    """共享存储大小写不敏感、不支持符号链接、chmod 无效；force 为假时只补写缺失项。"""
    config = repo.get_config()
    changed = False
    for name, value in SHARED_CORE_CONFIG:
        if not force:
            try:
                config.get((b"core",), name)
                continue
            except KeyError:
                pass
        config.set((b"core",), name, value)
        changed = True
    if changed:
        config.write_to_path()


def _open_repo(path, discover):
    from dulwich.repo import Repo

    try:
        return Repo.discover(path) if discover else Repo(path)
    except Exception:
        return None


def _apply_shared_config(path, discover, force):
    repo = _open_repo(path, discover)
    if repo is None:
        return
    try:
        _write_shared_config(repo, force=force)
    finally:
        repo.close()


def _init_path(args):
    for arg in args:
        if not arg.startswith("-"):
            return arg
    return "."


def _shared_checkout_notes(path, discover=True):
    """共享存储上符号链接检出为普通文件、仅大小写不同的路径互相覆盖，检出后说明受影响的路径。"""
    import stat as stat_module

    from dulwich.object_store import iter_tree_contents
    from dulwich.objects import Commit

    repo = _open_repo(path, discover)
    if repo is None:
        return
    try:
        if repo.bare:
            return
        try:
            head = repo[HEAD_REF]
            while not isinstance(head, Commit):
                head = repo[head.object[1]]
        except (KeyError, AttributeError):
            return
        links, names = [], {}
        for entry in iter_tree_contents(repo.object_store, head.tree):
            if stat_module.S_ISLNK(entry.mode):
                links.append(entry.path.decode("utf-8", "replace"))
            parts = entry.path.split(b"/")
            for depth in range(1, len(parts) + 1):
                prefix = b"/".join(parts[:depth])
                names.setdefault(prefix.lower(), set()).add(prefix)
    finally:
        repo.close()
    collisions = sorted(sorted(group) for group in names.values() if len(group) > 1)
    if links:
        print(
            "提示：共享存储不支持符号链接，%d 个符号链接已检出为内容是链接目标的普通文件：%s"
            % (len(links), _sample(links)),
            file=sys.stderr,
        )
    if collisions:
        shown = [" 与 ".join(name.decode("utf-8", "replace") for name in group) for group in collisions]
        print(
            "提示：共享存储大小写不敏感，%d 组路径仅大小写不同，工作区中每组只会保留一个：%s"
            % (len(collisions), _sample(shown, separator="；")),
            file=sys.stderr,
        )


def _sample(items, limit=5, separator="、"):
    shown = separator.join(items[:limit])
    return shown + (" 等 %d 项" % len(items) if len(items) > limit else "")


def _rewrite(command, args):
    """把常见 git 写法转换为 dulwich 接受的形式；无法表达时返回错误信息。"""
    if command == "log":
        # git 的 -<N> 等同 -n <N>；dulwich 会把它当成路径，静默输出空结果。
        rewritten = []
        for arg in args:
            match = _COUNT_SHORTHAND.match(arg)
            rewritten += ["-n", match.group(1)] if match else [arg]
        return rewritten, None
    if command == "branch":
        aliases = {"-a": "--all", "-r": "--remotes"}
        return [aliases.get(arg, arg) for arg in args], None
    if command == "add":
        return [("." if arg in ("-A", "--all") else arg) for arg in args], None
    if command == "clone":
        # 抓取范围选项由包装层的 fetch 钩子实现，dulwich.cli 不认识，原样传入会报错。
        rewritten = [arg for arg in args if arg not in CLONE_WRAPPER_FLAGS]
        # 本地源像 git 一样记为绝对路径，否则在克隆目录中 pull/fetch 会按错误的相对位置查找。
        for index, arg in enumerate(rewritten):
            if arg.startswith("-"):
                continue
            if index > 0 and rewritten[index - 1] in ("--depth", "-b", "--branch", "--refspec", "--filter", "--protocol"):
                continue
            if "://" not in arg and os.path.isdir(arg):
                rewritten[index] = os.path.abspath(arg)
            break
        return rewritten, None
    if command == "fetch":
        # dulwich 的 fetch 必须给出远端；git 默认取当前分支的上游远端或 origin。
        if "--all" in args or _fetch_location(args) is not None:
            return list(args), None
        try:
            remote = _default_remote()
        except ImportError:
            remote = "origin"
        return list(args) + [remote], None
    if command == "commit":
        rewritten = []
        index = 0
        while index < len(args):
            arg = args[index]
            if arg in ("-F", "--file") and index + 1 < len(args):
                with open(args[index + 1], encoding="utf-8") as message:
                    rewritten += ["-m", message.read()]
                index += 2
                continue
            rewritten.append(arg)
            index += 1
        if not _has_message(rewritten):
            return None, "内置 git 没有编辑器，提交需用 -m <信息> 或 -F <文件> 提供提交信息。"
        return rewritten, None
    return args, None


def _remote(args):
    """dulwich 只实现 remote add；列出、查看与修改地址、删除由这里读写仓库配置。返回 None 表示交给 dulwich。"""
    if args and args[0] == "add":
        return None
    from dulwich.repo import Repo

    repo = Repo.discover(".")
    config = repo.get_config()
    remotes = [section[1].decode() for section in config.sections() if len(section) == 2 and section[0] == b"remote"]

    def url(name):
        try:
            return config.get((b"remote", name.encode()), b"url").decode()
        except KeyError:
            return None

    if not args or args in (["-v"], ["--verbose"]):
        for name in remotes:
            if args:
                print("%s\t%s (fetch)\n%s\t%s (push)" % (name, url(name), name, url(name)))
            else:
                print(name)
        return 0
    action, rest = args[0], args[1:]
    if action == "get-url" and len(rest) == 1:
        if url(rest[0]) is None:
            return _fatal("No such remote '%s'" % rest[0])
        print(url(rest[0]))
        return 0
    if action == "set-url" and len(rest) == 2:
        if url(rest[0]) is None:
            return _fatal("No such remote '%s'" % rest[0])
        if _ssh_url(rest[1]) and _BridgeVendor.from_environment() is None:
            return _unsupported(SSH_UNAVAILABLE + FALLBACK)
        config.set((b"remote", rest[0].encode()), b"url", rest[1].encode())
        config.write_to_path()
        return 0
    if action in ("remove", "rm") and len(rest) == 1:
        if url(rest[0]) is None:
            return _fatal("No such remote '%s'" % rest[0])
        del config[(b"remote", rest[0].encode())]
        config.write_to_path()
        return 0
    return _unsupported("remote 只支持：remote [-v]、add、get-url、set-url、remove。" + FALLBACK)


INSPECT_FLAG = "--infinity-inspect"
INSPECT_VERSION = 1
INSPECT_USAGE_EXIT = 2
INSPECT_DEFAULT_ENTRIES = 2000
INSPECT_MAX_ENTRIES = 10000
INSPECT_DEFAULT_COMMITS = 50
INSPECT_MAX_COMMITS = 500
INSPECT_MAX_BLOB_BYTES = 512 * 1024
INSPECT_MAX_MESSAGE_BYTES = 64 * 1024
INSPECT_BINARY_SNIFF = 8000
INSPECT_MAX_TRACKING_COUNT = 1000
INSPECT_MAX_GRAPH_COMMITS = 10000
INSPECT_MAX_STAT_FILES = 200
INSPECT_MAX_STAT_BYTES = 4 * 1024 * 1024
INSPECT_MAX_DIFF_WORK = 2000000
INSPECT_OPERATIONS = ("status", "read", "log", "commit", "branches")
_HEX_SHA = re.compile(r"^(?:[0-9a-f]{40}|[0-9a-f]{64})$")
_GITLINK_MODE = 0o160000


class _InspectError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


class _InspectUsage(Exception):
    pass


def _inspect_args(argv):
    """解析检查入口参数；格式错误抛出 _InspectUsage。"""
    import argparse

    class Parser(argparse.ArgumentParser):
        def error(self, message):
            raise _InspectUsage(message)

    parser = Parser(prog="infinity_git " + INSPECT_FLAG, add_help=False)
    parser.add_argument("operation", choices=INSPECT_OPERATIONS)
    parser.add_argument("--output", required=True)
    parser.add_argument("--repo", default=".")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--blob", action="append", default=[])
    parser.add_argument("--commit")
    parser.add_argument("--cache-dir")
    parser.add_argument("--no-stat-cache", action="store_true")
    parser.add_argument("--trust-ctime", action="store_true")
    parser.add_argument("--no-tracking-cache", action="store_true")
    parser.add_argument("--legacy-tracking", action="store_true")
    parser.add_argument("--legacy-untracked", action="store_true")
    options = parser.parse_args(argv)
    if options.limit is not None and options.limit < 1:
        raise _InspectUsage("--limit 必须为正整数")
    if options.operation == "read" and not options.blob:
        raise _InspectUsage("read 需要至少一个 --blob <版本>:<路径>")
    if options.operation == "commit" and not options.commit:
        raise _InspectUsage("commit 需要 --commit <sha>")
    return options


def _inspect(argv):
    try:
        options = _inspect_args(argv)
    except _InspectUsage as error:
        print("infinity_git: %s: %s" % (INSPECT_FLAG, error), file=sys.stderr)
        return INSPECT_USAGE_EXIT
    try:
        result = _inspect_run(options)
    except _InspectError as error:
        result = _inspect_failure(error.code, error.message)
    except Exception as error:  # 损坏的仓库等：仍以 JSON 报告，调用方据 error 显示
        detail = (str(error).strip() or type(error).__name__).splitlines()[0]
        result = _inspect_failure("INSPECT_FAILED", "%s: %s" % (type(error).__name__, detail))
    import json

    temporary = options.output + ".tmp"
    with open(temporary, "w", encoding="utf-8") as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
    os.replace(temporary, options.output)
    return 0


def _inspect_failure(code, message):
    return {"version": INSPECT_VERSION, "repository": True, "error": {"code": code, "message": message}}


def _inspect_open(path):
    """只打开 path 本身的仓库：不向上查找，裸仓库视为不是仓库。"""
    from dulwich.errors import NotGitRepository
    from dulwich.repo import Repo

    if not os.path.lexists(os.path.join(path, ".git")):
        return None
    try:
        mapping_text = os.environ.get("INFINITY_GIT_LINUX_PATH_MAP")
        if mapping_text is None:
            repo = Repo(path)
        else:
            import json

            try:
                mappings = json.loads(mapping_text)
            except (TypeError, ValueError):
                raise _InspectError("UNSAFE_GIT_PATH", "Linux Git 路径映射无效") from None
            repo = Repo(path, **_inspect_mapped_control_paths(path, mappings))
    except NotGitRepository:
        return None
    if repo.bare:
        repo.close()
        return None
    if _workspace_kind() == "shared":
        _inspect_shared_config(repo)
    return repo


def _inspect_mapped_control_paths(path, mappings):
    """把 Linux 的 gitdir/commondir 映射到装配点授权的宿主根，不改写仓库指针文件。"""
    import stat

    def fail():
        raise _InspectError("UNSAFE_GIT_PATH", "Linux Git 指针不在可信映射内或经过符号链接")

    if not isinstance(mappings, dict) or not mappings:
        fail()
    trusted = {}
    for guest, host in mappings.items():
        if (not isinstance(guest, str) or not isinstance(host, str) or not guest.startswith("/")
                or not os.path.isabs(host) or "\0" in guest or "\0" in host
                or any(part in (".", "..") for part in guest.split("/"))):
            fail()
        host = os.path.abspath(host)
        if os.path.realpath(host) != host or not os.path.isdir(host):
            fail()
        trusted[guest.rstrip("/") or "/"] = host

    def inside(target, root):
        return target == root or target.startswith(root.rstrip(os.sep) + os.sep)

    def checked_host(target, directory=True):
        target = os.path.abspath(target)
        roots = [host for host in trusted.values() if inside(target, host)]
        if not roots:
            fail()
        root = max(roots, key=len)
        relative = os.path.relpath(target, root)
        current = root
        for part in ([] if relative == "." else relative.split(os.sep)):
            current = os.path.join(current, part)
            try:
                mode = os.lstat(current).st_mode
            except OSError:
                fail()
            if stat.S_ISLNK(mode):
                fail()
        if directory and not os.path.isdir(target):
            fail()
        return target

    def mapped(raw, parent):
        if not raw or "\0" in raw or "\n" in raw or "\r" in raw:
            fail()
        if not raw.startswith("/"):
            return checked_host(os.path.join(parent, raw))
        guest = os.path.normpath(raw)
        prefixes = [prefix for prefix in trusted if prefix == "/" or inside(guest, prefix)]
        # 运行时绑定绝不能回退到 rootfs 中的同名占位目录。
        if (inside(guest, "/workspace") or inside(guest, "/mnt")) and not any(prefix != "/" for prefix in prefixes):
            fail()
        if not prefixes:
            fail()
        prefix = max(prefixes, key=len)
        return checked_host(os.path.join(trusted[prefix], guest[len(prefix):].lstrip("/")))

    def read_pointer(file):
        checked_host(file, directory=False)
        if not stat.S_ISREG(os.lstat(file).st_mode):
            fail()
        with open(file, "rb") as handle:
            data = handle.read(4097)
        if len(data) > 4096:
            fail()
        try:
            return data.decode("utf-8").rstrip("\r\n")
        except UnicodeError:
            fail()

    worktree = checked_host(path)
    dotgit = os.path.join(worktree, ".git")
    checked_host(dotgit, directory=False)
    if os.path.isdir(dotgit):
        control = dotgit
    else:
        pointer = read_pointer(dotgit)
        if not pointer.startswith("gitdir: "):
            fail()
        control = mapped(pointer[len("gitdir: "):], worktree)
    common_pointer = os.path.join(control, "commondir")
    common = mapped(read_pointer(common_pointer), control) if os.path.lexists(common_pointer) else control
    # 外部对象库会让 dulwich 再按宿主路径寻址；本入口只支持当前可信仓库的对象库。
    alternates = os.path.join(common, "objects", "info", "alternates")
    if os.path.lexists(alternates) and read_pointer(alternates).strip():
        fail()
    for directory in {control, common}:
        for name in ("config", "config.worktree", "HEAD", "index", "packed-refs", "shallow", "objects", "refs"):
            metadata = os.path.join(directory, name)
            if os.path.lexists(metadata):
                checked_host(metadata, directory=False)
    return {"controldir": control, "commondir": common, "worktree": worktree}


def _inspect_shared_config(repo):
    """共享存储兼容配置只在内存中补齐：仓库未显式设置的项按共享存储取值，优先于全局配置。"""
    from dulwich.config import ConfigDict

    local = repo.get_config()
    defaults = ConfigDict()
    for name, value in SHARED_CORE_CONFIG:
        try:
            local.get((b"core",), name)
        except KeyError:
            defaults.set((b"core",), name, value)
    original = repo.get_config_stack

    def stacked():
        stack = original()
        stack.backends.insert(1, defaults)
        return stack

    repo.get_config_stack = stacked


def _inspect_run(options):
    timings = None
    if options.operation == "status":
        started = time.perf_counter_ns()
        from dulwich import index as _index  # noqa: F401
        if options.legacy_untracked:
            from dulwich import porcelain as _porcelain  # noqa: F401
        from dulwich import repo as _repo  # noqa: F401
        try:
            launch_ms = int(os.environ.get("INFINITY_GIT_START_MS", _ENTRY_WALL_MS))
        except ValueError:
            launch_ms = _ENTRY_WALL_MS
        timings = {"startup": max(0, _ENTRY_WALL_MS - launch_ms), "imports": (time.perf_counter_ns() - started) / 1_000_000}
    repo = _inspect_open(options.repo)
    if repo is None:
        return {"version": INSPECT_VERSION, "repository": False}
    try:
        if options.operation == "status":
            body = _inspect_status(repo, _clamp(options.limit, INSPECT_DEFAULT_ENTRIES, INSPECT_MAX_ENTRIES), timings,
                                   cache_dir=options.cache_dir, options=options)
            timings["total"] = timings["startup"] + (time.perf_counter_ns() - _ENTRY_MONOTONIC) / 1_000_000
            body["timings_ms"] = {key: round(value, 2) for key, value in timings.items()}
        elif options.operation == "read":
            body = {"blobs": [_inspect_blob(repo, *_split_blob_spec(spec)) for spec in options.blob]}
        elif options.operation == "log":
            body = _inspect_log(repo, _clamp(options.limit, INSPECT_DEFAULT_COMMITS, INSPECT_MAX_COMMITS))
        elif options.operation == "branches":
            body = _inspect_branches(repo, _clamp(options.limit, INSPECT_DEFAULT_ENTRIES, INSPECT_MAX_ENTRIES))
        else:
            body = _inspect_commit(repo, options.commit, _clamp(options.limit, INSPECT_DEFAULT_ENTRIES, INSPECT_MAX_ENTRIES))
    finally:
        repo.close()
    result = {"version": INSPECT_VERSION, "repository": True}
    result.update(body)
    return result


def _clamp(value, default, maximum):
    return default if value is None else min(value, maximum)


def _text(data):
    return data.decode("utf-8", "replace")


def _split_blob_spec(spec):
    rev, separator, path = spec.partition(":")
    if not separator or not rev:
        raise _InspectError("INVALID_ARGUMENT", "--blob 格式应为 <版本>:<路径>：%s" % spec)
    return rev, path


def _tree_path(path):
    """把调用方给出的仓库相对路径转为树路径；拒绝绝对路径、空片段与 . / .. 片段。"""
    if not path or "\0" in path or "\\" in path or path.startswith("/"):
        raise _InspectError("INVALID_ARGUMENT", "路径无效：%s" % path)
    parts = path.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise _InspectError("INVALID_ARGUMENT", "路径无效：%s" % path)
    return path.encode("utf-8")


def _peel_commit(repo, sha):
    from dulwich.objects import Commit, Tag

    item = repo[sha]
    while isinstance(item, Tag):
        item = repo[item.object[1]]
    if not isinstance(item, Commit):
        raise _InspectError("INVALID_ARGUMENT", "不是提交：%s" % _text(sha))
    return item


def _head_commit(repo):
    try:
        sha = repo.refs[HEAD_REF]
    except KeyError:
        return None
    return _peel_commit(repo, sha)


def _head_state(repo):
    try:
        raw = repo.refs.read_ref(HEAD_REF) or b""
    except KeyError:
        raw = b""
    branch, detached = None, False
    if raw.startswith(b"ref: "):
        target = raw[len(b"ref: "):].strip()
        if target.startswith(HEADS_PREFIX):
            branch = _text(target[len(HEADS_PREFIX):])
        else:
            branch = _text(target)
    elif raw:
        detached = True
    commit = _head_commit(repo)
    return {
        "branch": branch,
        "detached": detached,
        "head": _text(commit.id) if commit is not None else None,
        "unborn": commit is None,
    }


def _limited(iterable, limit):
    """取前 limit 项并报告是否还有更多。"""
    items = []
    for item in iterable:
        if len(items) >= limit:
            return items, True
        items.append(item)
    return items, False


class _TrackingGraph:
    """整次请求共享提交读取预算与父节点缓存，浅克隆以 shallow 边界为根。"""
    def __init__(self, repo):
        self.repo = repo
        self.parents = {}
        self.provider = repo.parents_provider()

    def reachable(self, start):
        pending, seen = [start], set()
        while pending:
            sha = pending.pop()
            if sha in seen:
                continue
            if sha not in self.parents:
                if len(self.parents) >= INSPECT_MAX_GRAPH_COMMITS:
                    return None
                try:
                    self.parents[sha] = self.provider.get_parents(sha)
                except KeyError:
                    return None
            seen.add(sha)
            pending.extend(self.parents[sha])
        return seen

    def linear_difference(self, left, right):
        """线性历史在相遇点停止；出现合并时回退到完整可达集合。"""
        positions = [{}, {}]
        current = [left, right]
        while current[0] is not None or current[1] is not None:
            for side in (0, 1):
                sha = current[side]
                if sha is None:
                    continue
                if sha in positions[1 - side]:
                    counts = [len(positions[0]), len(positions[1])]
                    counts[1 - side] = positions[1 - side][sha]
                    return tuple(counts)
                if sha in positions[side]:
                    return None
                positions[side][sha] = len(positions[side])
                if sha not in self.parents:
                    if len(self.parents) >= INSPECT_MAX_GRAPH_COMMITS:
                        return None
                    try:
                        self.parents[sha] = self.provider.get_parents(sha)
                    except KeyError:
                        return None
                parents = self.parents[sha]
                if len(parents) > 1:
                    return False
                current[side] = parents[0] if parents else None
        return len(positions[0]), len(positions[1])

    def tracking(self, branch, sha, cache=None, legacy=False):
        result = {"upstream": None, "ahead": None, "behind": None, "upstream_missing": False, "tracking_truncated": False}
        if branch is None or sha is None:
            return result
        config = self.repo.get_config_stack()
        try:
            remote = config.get((b"branch", branch), b"remote")
            merge = config.get((b"branch", branch), b"merge")
        except KeyError:
            return result
        if not remote or not merge.startswith(HEADS_PREFIX):
            return result
        name = merge[len(HEADS_PREFIX):]
        ref = merge if remote == b"." else b"refs/remotes/" + remote + b"/" + name
        result["upstream"] = _text(name if remote == b"." else remote + b"/" + name)
        try:
            upstream = self.repo.refs[ref]
        except KeyError:
            result["upstream_missing"] = True
            return result
        if sha == upstream:
            result.update(ahead=0, behind=0)
            return result
        key = _text(sha) + ":" + _text(upstream)
        saved = cache.get(key) if cache is not None else None
        if isinstance(saved, list) and len(saved) == 3 and all(isinstance(x, int) and x >= 0 for x in saved[:2]) and isinstance(saved[2], bool):
            result.update(ahead=saved[0], behind=saved[1], tracking_truncated=saved[2])
            return result
        counts = False if legacy else self.linear_difference(sha, upstream)
        if counts is False:
            local, other = self.reachable(sha), self.reachable(upstream)
            counts = None if local is None or other is None else (len(local - other), len(other - local))
        if counts is None:
            # 历史不可完整遍历时不把已见节点误报为精确计数。
            result["tracking_truncated"] = True
            return result
        ahead, behind = counts
        result.update(
            ahead=min(ahead, INSPECT_MAX_TRACKING_COUNT), behind=min(behind, INSPECT_MAX_TRACKING_COUNT),
            tracking_truncated=ahead > INSPECT_MAX_TRACKING_COUNT or behind > INSPECT_MAX_TRACKING_COUNT,
        )
        if cache is not None:
            cache[key] = [result["ahead"], result["behind"], result["tracking_truncated"]]
        return result


def _inspect_branches(repo, limit):
    state = _head_state(repo)
    branches = []
    for ref in repo.refs.keys(base=HEADS_PREFIX.rstrip(b"/")):
        commit = _peel_commit(repo, repo.refs[HEADS_PREFIX + ref])
        branches.append({
            "name": _text(ref), "current": not state["detached"] and state["branch"] == _text(ref),
            "short_sha": _text(commit.id[:7]), "author_time": commit.author_time,
            "title": _commit_summary(commit)["title"], "_sha": commit.id, "_branch": ref,
        })
    branches.sort(key=lambda item: (-item["author_time"], item["name"]))
    truncated = len(branches) > limit
    graph = _TrackingGraph(repo)
    branches = branches[:limit]
    for item in branches:
        item.update(graph.tracking(item.pop("_branch"), item.pop("_sha")))
    return {"branches": branches, "truncated": truncated}


class _LinesSkipped(Exception):
    pass


class _LineStats:
    """只为已返回的状态条目读取文本，三组共用文件、字节与差异计算预算。"""
    def __init__(self, repo, index, head):
        self.repo, self.index, self.head = repo, index, head
        self.files = 0
        self.bytes_left = INSPECT_MAX_STAT_BYTES
        self.work_left = INSPECT_MAX_DIFF_WORK

    def spend_work(self):
        if self.work_left <= 0:
            raise _LinesSkipped("budget")
        self.work_left -= 1

    def blob(self, mode, sha):
        import stat as stat_module
        from dulwich.objects import Blob

        if mode == _GITLINK_MODE:
            raise _LinesSkipped("submodule")
        if stat_module.S_ISLNK(mode):
            raise _LinesSkipped("symlink")
        blob = self.repo[sha]
        if not isinstance(blob, Blob):
            raise _LinesSkipped("not_file")
        size = blob.raw_length()
        if size > INSPECT_MAX_BLOB_BYTES:
            raise _LinesSkipped("too_large")
        if size > self.bytes_left:
            raise _LinesSkipped("budget")
        self.bytes_left -= size
        return self.text(blob.as_raw_string())

    def text(self, data):
        if b"\0" in data[:INSPECT_BINARY_SNIFF]:
            raise _LinesSkipped("binary")
        return data

    def at_head(self, path):
        from dulwich.errors import NotTreeError
        from dulwich.objects import SubmoduleEncountered

        if self.head is None:
            return b""
        try:
            mode, sha = self.repo[self.head.tree].lookup_path(self.repo.__getitem__, path)
        except (KeyError, NotTreeError, SubmoduleEncountered):
            return b""
        return self.blob(mode, sha)

    def at_index(self, path):
        from dulwich.index import ConflictedIndexEntry

        try:
            entry = self.index[path]
        except KeyError:
            return b""
        if isinstance(entry, ConflictedIndexEntry):
            raise _LinesSkipped("conflict")
        return self.blob(entry.mode, entry.sha)

    def at_worktree(self, path):
        import stat as stat_module

        # 每层目录与最终文件都拒绝符号链接，避免统计读取工作区之外的内容。
        directory = os.open(self.repo.path, os.O_RDONLY | os.O_DIRECTORY)
        try:
            parts = path.split(b"/")
            for part in parts[:-1]:
                next_directory = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
                os.close(directory)
                directory = next_directory
            info = os.stat(parts[-1], dir_fd=directory, follow_symlinks=False)
            if stat_module.S_ISLNK(info.st_mode):
                raise _LinesSkipped("symlink")
            if not stat_module.S_ISREG(info.st_mode):
                raise _LinesSkipped("not_file")
            if info.st_size > INSPECT_MAX_BLOB_BYTES:
                raise _LinesSkipped("too_large")
            if info.st_size > self.bytes_left:
                raise _LinesSkipped("budget")
            descriptor = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
            with os.fdopen(descriptor, "rb") as stream:
                if not stat_module.S_ISREG(os.fstat(stream.fileno()).st_mode):
                    raise _LinesSkipped("not_file")
                ceiling = min(INSPECT_MAX_BLOB_BYTES, self.bytes_left)
                data = stream.read(ceiling + 1)
            if len(data) > ceiling:
                raise _LinesSkipped("too_large" if ceiling == INSPECT_MAX_BLOB_BYTES else "budget")
            self.bytes_left -= len(data)
            return self.text(data)
        except FileNotFoundError:
            return b""
        finally:
            os.close(directory)

    def count(self, old, new):
        from infinity_git_numstat import numstat

        return numstat(old, new, self.spend_work)

    def add(self, group, item):
        item.update(added_lines=None, removed_lines=None)
        try:
            if self.files >= INSPECT_MAX_STAT_FILES or self.bytes_left <= 0:
                raise _LinesSkipped("budget")
            self.files += 1
            path = _tree_path(item["path"])
            if group == "staged":
                old, new = self.at_head(path), self.at_index(path)
            elif group == "unstaged":
                old, new = self.at_index(path), self.at_worktree(path)
            else:
                old, new = b"", self.at_worktree(path)
            item["added_lines"], item["removed_lines"] = self.count(old, new)
        except _LinesSkipped as skipped:
            item["lines_skipped"] = str(skipped)
        except (OSError, KeyError, _InspectError):
            item["lines_skipped"] = "read_error"


def _repository_name(repo):
    """只返回远端路径末两段；凭据、主机、查询参数与完整 URL 均不进入结果。"""
    from urllib.parse import urlsplit

    fallback = os.path.basename(os.path.abspath(repo.path))
    config = repo.get_config()
    remotes = [section[1] for section in config.sections() if len(section) == 2 and section[0] == b"remote"]
    remote = b"origin" if b"origin" in remotes else remotes[0] if len(remotes) == 1 else None
    if remote is None:
        return fallback
    try:
        url = _text(config.get((b"remote", remote), b"url"))
        if "://" in url:
            parsed = urlsplit(url)
            if not parsed.hostname:
                return fallback
            path = parsed.path
        elif re.match(r"^(?:[A-Za-z0-9._-]+@)?[A-Za-z0-9.-]+:", url):
            path = url.split(":", 1)[1]
        else:
            return fallback
    except (KeyError, ValueError):
        return fallback
    parts = path.strip("/").split("/")
    if len(parts) < 2:
        return fallback
    owner, name = parts[-2:]
    name = name.removesuffix(".git")
    if any(part in ("", ".", "..") or not re.fullmatch(r"[\w.-]+", part) for part in (owner, name)):
        return fallback
    return owner + "/" + name


def _cache_fingerprint(path):
    try:
        info = os.stat(path)
        return [info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns]
    except OSError:
        return None


class _StatusDiskCache:
    """只在工作区以外写入可丢弃的应用缓存。"""
    def __init__(self, root, repo, head, config):
        import hashlib

        self.directory = None
        digest = hashlib.sha256()
        for backend in getattr(config, "backends", []):
            for section in sorted(backend.sections()):
                if section[0].lower() not in (b"core", b"filter"):
                    continue
                for name, value in backend.items(section):
                    for part in (*section, name, value):
                        digest.update(len(part).to_bytes(4, "big"))
                        digest.update(part)
        self.key = [head, _cache_fingerprint(repo.index_path()),
                    _cache_fingerprint(os.path.join(repo.path, ".gitattributes")),
                    _cache_fingerprint(os.path.join(repo.controldir(), "info", "attributes")),
                    _cache_fingerprint(os.path.join(repo.controldir(), "config")),
                    _cache_fingerprint(os.path.join(repo.controldir(), "shallow")), digest.hexdigest()]
        if root:
            root = os.path.realpath(root)
            work = os.path.realpath(repo.path)
            control = os.path.realpath(repo.controldir())
            if any(os.path.commonpath((root, item)) == item for item in (work, control)):
                return
            identity = hashlib.sha256(os.fsencode(work)).hexdigest()
            candidate = os.path.join(root, identity)
            resolved = os.path.realpath(candidate)
            if any(os.path.commonpath((resolved, item)) == item for item in (work, control)):
                return
            self.directory = candidate

    def read(self, name, keyed=True):
        import json

        if self.directory is None:
            return {}
        try:
            with open(os.path.join(self.directory, name + ".json"), encoding="utf-8") as handle:
                data = json.load(handle)
            if not isinstance(data, dict) or (keyed and data.get("key") != self.key):
                return {}
            return data.get("items") if isinstance(data.get("items"), dict) else {}
        except (OSError, ValueError, TypeError):
            return {}

    def write(self, name, items):
        import json
        import tempfile

        if self.directory is None:
            return
        temporary = None
        try:
            os.makedirs(self.directory, mode=0o700, exist_ok=True)
            descriptor, temporary = tempfile.mkstemp(prefix=".status-", dir=self.directory)
            with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
                json.dump({"key": self.key, "items": items}, handle, separators=(",", ":"))
            os.replace(temporary, os.path.join(self.directory, name + ".json"))
        except (OSError, ValueError, TypeError):
            pass
        finally:
            if temporary is not None:
                try:
                    os.unlink(temporary)
                except OSError:
                    pass


def _cached_unstaged(index, root, normalizer, trust_ctime, cached):
    import stat
    from dulwich import index as index_module

    updated = {}
    for path, entry in index.iteritems():
        if isinstance(entry, index_module.ConflictedIndexEntry):
            yield path
            continue
        full_path = index_module._tree_to_fs_path(root, path)
        try:
            info = os.lstat(full_path)
            if stat.S_ISDIR(info.st_mode):
                if index_module._has_directory_changed(path, entry):
                    yield path
                continue
            if not stat.S_ISREG(info.st_mode) and not stat.S_ISLNK(info.st_mode):
                continue
            if index_module._stat_matches_entry(info, entry, trust_ctime):
                continue
            key = os.fsdecode(path)
            previous = cached.get(key)
            # 符号链接的目标可以变化，始终按现有行为重新读取。
            if stat.S_ISREG(info.st_mode) and isinstance(previous, list) and len(previous) == 3 and previous[:2] == [info.st_size, info.st_mtime_ns] and isinstance(previous[2], str) and _HEX_SHA.fullmatch(previous[2]):
                sha = previous[2].encode("ascii")
            else:
                blob = index_module.blob_from_path_and_stat(full_path, info)
                if normalizer is not None:
                    blob = normalizer(blob, path)
                sha = blob.get_id(index.object_format)
            if stat.S_ISREG(info.st_mode):
                updated[key] = [info.st_size, info.st_mtime_ns, _text(sha)]
            if sha != entry.sha:
                yield path
        except FileNotFoundError:
            yield path
    cached.clear()
    cached.update(updated)


def _scandir_untracked(repo, index, config):
    import unicodedata
    from dulwich.ignore import IgnoreFilterManager

    ignore = IgnoreFilterManager.from_repo(repo, config=config)
    precompose = config.get_boolean(b"core", b"precomposeunicode", False)
    pending = [(repo.path, "")]
    while pending:
        directory, prefix = pending.pop()
        try:
            with os.scandir(directory) as scan:
                entries = list(scan)
        except OSError:
            continue
        if prefix and any(entry.name == ".git" for entry in entries):
            continue
        for entry in entries:
            if entry.name == ".git":
                continue
            name = unicodedata.normalize("NFC", entry.name) if precompose else entry.name
            relative = prefix + name
            try:
                is_directory = entry.is_dir(follow_symlinks=True)
                if is_directory:
                    if not entry.is_symlink() and not ignore.may_prune_directory(relative + "/"):
                        pending.append((entry.path, relative + "/"))
                elif relative.encode("utf-8") not in index and not ignore.is_ignored(relative):
                    yield relative
            except FileNotFoundError:
                continue


def _inspect_status(repo, limit, timings=None, cache_dir=None, options=None):
    from dulwich.index import ConflictedIndexEntry, get_unstaged_changes

    timings = timings if timings is not None else {}
    started = time.perf_counter_ns()
    config = repo.get_config_stack()
    index = repo.open_index(config=config)
    timings["index"] = (time.perf_counter_ns() - started) / 1_000_000
    head = _head_commit(repo)
    result = _head_state(repo)
    disk = _StatusDiskCache(cache_dir, repo, result["head"], config)
    option = lambda name: bool(getattr(options, name, False))
    tracking_cache = None if option("no_tracking_cache") or option("legacy_tracking") else disk.read("tracking")
    result["repository_name"] = _repository_name(repo)
    started = time.perf_counter_ns()
    result.update(_TrackingGraph(repo).tracking(
        result["branch"].encode("utf-8") if result["branch"] is not None else None,
        head.id if head is not None else None,
        tracking_cache, option("legacy_tracking"),
    ))
    if tracking_cache is not None:
        disk.write("tracking", tracking_cache)
    timings["tracking"] = (time.perf_counter_ns() - started) / 1_000_000
    try:
        result["last_fetch_seconds"] = int(os.stat(os.path.join(repo.controldir(), "FETCH_HEAD")).st_mtime)
    except FileNotFoundError:
        result["last_fetch_seconds"] = None

    def staged():
        for (old_path, new_path), _modes, _shas in index.changes_from_tree(
            repo.object_store, head.tree if head is not None else None
        ):
            if old_path is None:
                yield {"path": _text(new_path), "change": "added"}
            elif new_path is None:
                yield {"path": _text(old_path), "change": "deleted"}
            else:
                yield {"path": _text(new_path), "change": "modified"}

    normalizer = repo.get_blob_normalizer(config=config)
    shared = _workspace_kind() == "shared" or os.path.realpath(repo.path).startswith("/storage/emulated/")
    trust_ctime = True if option("trust_ctime") else config.get_boolean(b"core", b"trustctime", True) if not shared else False
    root = os.fsencode(repo.path)
    stat_cache = disk.read("files") if not option("no_stat_cache") else None

    def unstaged():
        # refresh_stat 为 None：不回写索引的 stat 缓存，保持只读。
        normalize = normalizer.checkin_normalize if normalizer is not None else None
        changes = get_unstaged_changes(index, root, normalize, False, trust_ctime, None, None) if stat_cache is None else _cached_unstaged(index, root, normalize, trust_ctime, stat_cache)
        for tree_path in changes:
            entry = index[tree_path]
            on_disk = os.path.lexists(os.path.join(root, tree_path.replace(b"/", os.fsencode(os.sep))))
            change = "modified" if on_disk or isinstance(entry, ConflictedIndexEntry) else "deleted"
            yield {"path": _text(tree_path), "change": change}

    def untracked():
        if option("legacy_untracked"):
            from dulwich.porcelain import get_untracked_paths
            paths = get_untracked_paths(repo.path, repo.path, index, exclude_ignored=True, untracked_files="all",
                                        precompose_unicode=config.get_boolean(b"core", b"precomposeunicode", False), repo=repo)
        else:
            paths = _scandir_untracked(repo, index, config)
        for path in paths:
            yield {"path": path.replace(os.sep, "/"), "change": "added"}

    stats = _LineStats(repo, index, head)
    result["file_counts"] = {"tracked": len(index)}
    timings["lines"] = 0.0
    for name, entries in (("staged", staged()), ("unstaged", unstaged()), ("untracked", untracked())):
        started = time.perf_counter_ns()
        items, truncated = _limited(entries, limit)
        result[name] = sorted(items, key=lambda item: item["path"])
        result[name + "_truncated"] = truncated
        result["file_counts"][name] = len(items)
        timings[name] = (time.perf_counter_ns() - started) / 1_000_000
        started = time.perf_counter_ns()
        for item in result[name]:
            stats.add(name, item)
        timings["lines"] += (time.perf_counter_ns() - started) / 1_000_000
    if stat_cache is not None:
        disk.write("files", stat_cache)
    return result


def _resolve_tree(repo, rev):
    """返回版本对应的树 sha；版本合法但对象不存在（未提交、根提交的父提交）时返回 None。"""
    if rev == "HEAD":
        commit = _head_commit(repo)
        return commit.tree if commit is not None else None
    parent = rev.endswith("^")
    sha = rev[:-1] if parent else rev
    if not _HEX_SHA.match(sha):
        raise _InspectError("INVALID_ARGUMENT", "版本应为 HEAD、INDEX、完整提交 sha 或 <sha>^：%s" % rev)
    try:
        commit = _peel_commit(repo, sha.encode("ascii"))
    except KeyError:
        raise _InspectError("NOT_FOUND", "提交不存在：%s" % sha)
    if not parent:
        return commit.tree
    if not commit.parents:
        return None
    return _peel_commit(repo, commit.parents[0]).tree


def _inspect_blob(repo, rev, path):
    import stat as stat_module

    from dulwich.errors import NotTreeError
    from dulwich.index import ConflictedIndexEntry
    from dulwich.objects import Blob, SubmoduleEncountered

    tree_path = _tree_path(path)
    result = {
        "rev": rev, "path": path, "exists": False, "mode": None, "symlink": False, "submodule": False,
        "binary": False, "size": 0, "truncated": False, "text": None,
    }
    if rev == "INDEX":
        index = repo.open_index(config=repo.get_config_stack())
        try:
            entry = index[tree_path]
        except KeyError:
            return result
        if isinstance(entry, ConflictedIndexEntry):
            entry = entry.this or entry.other or entry.ancestor
            if entry is None:
                return result
        mode, sha = entry.mode, entry.sha
    else:
        tree = _resolve_tree(repo, rev)
        if tree is None:
            return result
        try:
            mode, sha = repo[tree].lookup_path(repo.__getitem__, tree_path)
        except (KeyError, NotTreeError, SubmoduleEncountered):
            return result
    result["mode"] = "%06o" % mode
    if mode == _GITLINK_MODE:
        text = "Subproject commit %s\n" % _text(sha)
        result.update(exists=True, submodule=True, size=len(text), text=text)
        return result
    item = repo.object_store[sha]
    if not isinstance(item, Blob):
        return result  # 目录不是文件
    data = item.as_raw_string()
    binary = b"\0" in data[:INSPECT_BINARY_SNIFF]
    truncated = len(data) > INSPECT_MAX_BLOB_BYTES
    result.update(
        exists=True, symlink=stat_module.S_ISLNK(mode), binary=binary, size=len(data), truncated=truncated,
        text=None if binary or truncated else _text(data),
    )
    return result


def _identity(raw):
    name, _, email = _text(raw).partition("<")
    return name.strip(), email.rstrip(">").strip()


def _commit_summary(commit):
    message = commit.message or b""
    name, email = _identity(commit.author)
    return {
        "sha": _text(commit.id),
        "short_sha": _text(commit.id[:7]),
        "author_name": name,
        "author_email": email,
        "author_time": commit.author_time,
        "author_tz": commit.author_timezone,
        "title": _text(message.strip().split(b"\n", 1)[0]) if message.strip() else "",
        "parents": [_text(parent) for parent in commit.parents],
    }


def _inspect_log(repo, limit):
    head = _head_commit(repo)
    if head is None:
        return {"commits": [], "truncated": False}
    walker = repo.get_walker(include=[head.id], max_entries=limit + 1)
    commits, truncated = _limited((_commit_summary(entry.commit) for entry in walker), limit)
    return {"commits": commits, "truncated": truncated}


def _inspect_commit(repo, sha, limit):
    from dulwich.diff_tree import (
        CHANGE_ADD, CHANGE_COPY, CHANGE_DELETE, CHANGE_RENAME, RenameDetector, tree_changes,
    )

    if not _HEX_SHA.match(sha):
        raise _InspectError("INVALID_ARGUMENT", "--commit 应为完整提交 sha：%s" % sha)
    try:
        commit = _peel_commit(repo, sha.encode("ascii"))
    except KeyError:
        raise _InspectError("NOT_FOUND", "提交不存在：%s" % sha)
    parent_tree = _peel_commit(repo, commit.parents[0]).tree if commit.parents else None
    store = repo.object_store
    names = {CHANGE_ADD: "added", CHANGE_DELETE: "deleted", CHANGE_RENAME: "renamed", CHANGE_COPY: "added"}

    def files():
        for change in tree_changes(store, parent_tree, commit.tree, rename_detector=RenameDetector(store)):
            kind = names.get(change.type, "modified")
            new_path = change.new.path if change.new is not None and change.new.path is not None else None
            old_path = change.old.path if change.old is not None and change.old.path is not None else None
            item = {"path": _text(new_path if new_path is not None else old_path), "change": kind}
            if kind == "renamed":
                item["old_path"] = _text(old_path)
            yield item

    items, truncated = _limited(files(), limit)
    summary = _commit_summary(commit)
    summary["message"] = _text((commit.message or b"")[:INSPECT_MAX_MESSAGE_BYTES])
    return {"commit": summary, "files": items, "files_truncated": truncated}


def main(argv):
    args = list(argv)
    try:
        _patch_midx_offsets()
    except ImportError:
        pass  # 没有 dulwich 时由各子命令报告原因
    if args and args[0] == INSPECT_FLAG:
        return _inspect(args[1:])
    while args and args[0].startswith("-"):
        option = args.pop(0)
        if option in ("--version", "-v"):
            print(_version())
            return 0
        if option in ("-h", "--help"):
            print(_usage())
            return 0
        if option == "-C" and args:
            try:
                os.chdir(args.pop(0))
            except OSError as error:
                return _fatal("无法进入目录：%s" % error)
            continue
        if option in ("--no-pager", "-P"):
            continue
        return _unsupported("不支持全局选项 %s（只支持 -C <目录>）。%s" % (option, FALLBACK))
    if not args or args[0] == "help":
        print(_usage())
        return 0
    command, rest = args[0], args[1:]
    if command == "version":
        print(_version())
        return 0
    if command not in SUPPORTED:
        return _unsupported("子命令 %s 不在内置 git 支持范围内。支持：%s。%s" % (command, " ".join(SUPPORTED), FALLBACK))
    if command in NETWORK and any(_ssh_url(arg) for arg in rest) and _BridgeVendor.from_environment() is None:
        return _unsupported(SSH_UNAVAILABLE + FALLBACK)
    if command in ("clone", "fetch") and any(arg in ("-h", "--help") for arg in rest):
        print(_usage())
        return 0
    original_rest = rest
    try:
        rest, problem = _rewrite(command, rest)
    except OSError as error:
        return _fatal("无法读取提交信息文件：%s" % error)
    if problem:
        return _unsupported(problem)
    if command in NEEDS_IDENTITY and not _has_identity():
        print(
            "git: GIT_IDENTITY_MISSING: 未设置提交身份。请先运行\n"
            "  git config user.name \"你的名字\"\n"
            "  git config user.email \"you@example.com\"\n"
            "（加 --global 写入 $HOME/.gitconfig，对所有工作区生效）",
            file=sys.stderr,
        )
        return FATAL_EXIT

    if command == "remote":
        try:
            handled = _remote(rest)
        except Exception as error:
            return _fatal("%s: %s" % (type(error).__name__, (str(error).strip() or type(error).__name__).splitlines()[0]))
        if handled is not None:
            return handled

    kind = _workspace_kind()
    shared = kind == "shared"
    if command == "clone" and kind == "private":
        print(PRIVATE_CLONE_HINT, file=sys.stderr)
    if shared and command not in ("clone", "init"):
        _apply_shared_config(".", discover=True, force=False)
    policy = None
    if command == "clone":
        policy = _clone_policy(original_rest, shared=shared)
    elif command == "fetch":
        policy = _fetch_policy(rest, shared=shared)

    # 没有终端与编辑器：需要编辑器的路径立即失败，而不是等待超时。
    os.environ.setdefault("GIT_EDITOR", "false")
    os.environ.setdefault("GIT_TERMINAL_PROMPT", "0")
    finish = _collapse_progress()
    try:
        result = _run_dulwich(command, rest, policy)
        if result == 0 and shared:
            _after_shared_command(command, rest, policy)
        return result
    finally:
        finish()


def _after_shared_command(command, rest, policy):
    if command == "init":
        _apply_shared_config(_init_path(rest), discover=False, force=True)
    elif command == "clone" and policy.clone_path:
        _apply_shared_config(policy.clone_path, discover=False, force=True)
        if not policy.bare:
            _shared_checkout_notes(policy.clone_path, discover=False)
    elif command in ("checkout", "switch"):
        _shared_checkout_notes(".")


def _run_dulwich(command, rest, policy=None):
    global _POLICY
    from dulwich import cli

    if command in ("clone", "fetch"):
        _limit_fetched_refs()
    if command in NETWORK:
        _install_remote_auth()

    _POLICY = policy
    try:
        result = cli.main(["--no-pager", command, *rest])
    except KeyboardInterrupt:
        return 130
    except SystemExit:
        raise
    except _RemoteRefMissing as error:
        return _fatal(str(error))
    except _RemoteAuthError as error:
        return _fatal("%s: %s" % (error.code, error.message))
    except FileNotFoundError as error:
        if "ssh" in str(error):
            return _unsupported(SSH_UNAVAILABLE + FALLBACK)
        return _fatal(str(error))
    except Exception as error:  # dulwich 以异常报告大多数失败
        detail = str(error).strip() or type(error).__name__
        return _fatal("%s: %s" % (type(error).__name__, detail.splitlines()[0]))
    finally:
        _POLICY = None
    return result or 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
