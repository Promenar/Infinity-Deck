"""用宿主 clang 和模拟桥验证两个 C 客户端的请求、帧和退出契约。"""

import fcntl
import json
import os
from pathlib import Path
import pty
import signal
import socket
import struct
import subprocess
import tempfile
import termios
import unittest

SOURCES = Path(__file__).resolve().parents[1]
TIMEOUT = 8


def read_exact(stream, size):
    data = bytearray()
    while len(data) < size:
        chunk = stream.read(size - len(data))
        if not chunk:
            raise EOFError(f"帧截断：{len(data)}/{size} 字节")
        data.extend(chunk)
    return bytes(data)


def frame(kind, data=b""):
    return struct.pack(">BI", kind, len(data)) + data


def read_frame(stream):
    kind, size = struct.unpack(">BI", read_exact(stream, 5))
    return kind, read_exact(stream, size)


class BridgeProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        temporary = tempfile.TemporaryDirectory(prefix="nx-tools-", dir="/tmp")
        cls.addClassCleanup(temporary.cleanup)
        cls.folder = Path(temporary.name)
        cls.git_ssh = cls.folder / "git-ssh"
        cls.shim = cls.folder / "rg"
        for binary, source, arguments in (
            (cls.git_ssh, "git-ssh-helper.c", []),
            (cls.shim, "tool-shim.c", ["-DNX_SHIM_HOST_TEST"]),
        ):
            subprocess.run([
                "clang", "-std=c11", "-Wall", "-Wextra", "-Werror", *arguments,
                str(SOURCES / source), str(SOURCES / "bridge-protocol.c"), "-o", str(binary),
            ], check=True)

    def listen(self, unix=False):
        server = socket.socket(socket.AF_UNIX if unix else socket.AF_INET)
        self.addCleanup(server.close)
        path = str(self.folder / "bridge.sock")
        if unix:
            self.addCleanup(Path(path).unlink, missing_ok=True)
        server.bind(path if unix else ("127.0.0.1", 0))
        server.listen()
        server.settimeout(TIMEOUT)
        return server

    def open_client(self, binary, args, server, env=None, stdin=subprocess.PIPE):
        if binary == self.git_ssh:
            connection = {"INFINITY_GIT_SSH_BRIDGE": f"127.0.0.1:{server.getsockname()[1]}",
                          "INFINITY_GIT_NONCE": 'fixture"\\'}
        else:
            connection = {"INFINITY_TOOL_BRIDGE_SOCKET": server.getsockname(),
                          "INFINITY_TOOL_BRIDGE_NONCE": 'fixture"\\'}
        process = subprocess.Popen(
            [binary, *args], cwd=self.folder, env=connection | (env or {}),
            stdin=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        self.addCleanup(self.close_process, process)
        peer, _ = server.accept()
        self.addCleanup(peer.close)
        peer.settimeout(TIMEOUT)
        stream = peer.makefile("rb")
        self.addCleanup(stream.close)
        return process, peer, stream

    @staticmethod
    def close_process(process):
        if process.poll() is None:
            process.kill()
        process.wait(timeout=TIMEOUT)
        for pipe in (process.stdin, process.stdout, process.stderr):
            if pipe:
                pipe.close()

    def test_git_ssh_serializes_argv_and_relays_full_binary_frames(self):
        request_command = "git-upload-pack '仓库\\\"\n'"
        process, peer, stream = self.open_client(
            self.git_ssh, ["-p", "2222", "-l", "alice", "-o", "SendEnv=GIT_PROTOCOL", "-6",
                           "--", "ignored@[fe80::abcd]", request_command],
            self.listen(), {"GIT_PROTOCOL": "version=2"},
        )
        self.assertEqual(json.loads(stream.readline()), {
            "nonce": 'fixture"\\', "host": "fe80::abcd", "port": 2222, "user": "alice",
            "command": request_command, "protocol": 2,
        })
        peer.sendall(b' { "other":{"ok":false}, "ok" : true }\r\n' + frame(1, b"out\0"))
        process.stdin.write(b"ready\0stdin")
        process.stdin.close()
        process.stdin = None
        self.assertEqual(read_frame(stream), (0, b"ready\0stdin"))
        self.assertEqual(read_frame(stream), (4, b""))
        for fragment in (frame(2, b"error")[:3], frame(2, b"error")[3:], frame(3, struct.pack(">i", 37))):
            peer.sendall(fragment)
        self.assertEqual(process.communicate(timeout=TIMEOUT), (b"out\0", b"error"))
        self.assertEqual(process.returncode, 37)

    def test_git_ssh_defaults_and_rejection_decode_unicode(self):
        process, peer, stream = self.open_client(self.git_ssh, ["git@example.test", "git-upload-pack 'r'"],
                                                 self.listen(), {"GIT_PROTOCOL": "version=1"})
        request = json.loads(stream.readline())
        self.assertEqual((request["user"], request["host"], request["port"], request["protocol"]),
                         ("git", "example.test", None, 1))
        peer.sendall(b'{"message":"\\u62d2\\u7edd \\uD83D\\uDEAB \\"a\\"","ok":false}\n')
        self.assertEqual(process.communicate(timeout=TIMEOUT), (b"", '拒绝 🚫 "a"\n'.encode()))
        self.assertEqual(process.returncode, 255)

    def test_git_ssh_supports_git_openssh_variant_probe(self):
        process = subprocess.run([self.git_ssh, "-G", "-p", "2222", "git@example.test"],
                                 env={}, capture_output=True, timeout=TIMEOUT)
        self.assertEqual((process.returncode, process.stdout, process.stderr), (0, b"", b""))

    def test_git_ssh_refuses_invalid_invocations(self):
        for args, env in (
            ([], {}),
            (["x", "cmd"], {"INFINITY_GIT_SSH_BRIDGE": "0.0.0.0:1234", "INFINITY_GIT_NONCE": "fixture"}),
            (["-p", "65536", "x", "cmd"],
             {"INFINITY_GIT_SSH_BRIDGE": "127.0.0.1:1234", "INFINITY_GIT_NONCE": "fixture"}),
            (["-o", "ProxyCommand=no", "x", "cmd"],
             {"INFINITY_GIT_SSH_BRIDGE": "127.0.0.1:1234", "INFINITY_GIT_NONCE": "fixture"}),
        ):
            process = subprocess.run([self.git_ssh, *args], env=env, capture_output=True, timeout=TIMEOUT)
            self.assertEqual(process.returncode, 255)
            self.assertTrue(process.stderr.decode().strip())

    def test_shim_filters_environment_and_relays_signals_and_tty_resize(self):
        master, terminal = pty.openpty()
        self.addCleanup(os.close, master)
        self.addCleanup(os.close, terminal)
        fcntl.ioctl(terminal, termios.TIOCSWINSZ, struct.pack("HHHH", 41, 119, 0, 0))
        process, peer, stream = self.open_client(
            self.shim, ["--", '匹配"a\n'], self.listen(unix=True),
            {"TERM": "xterm", "NO_COLOR": "", "https_proxy": "http://localhost:8042",
             "GIT_EXEC_PATH": "/ignored", "GIT_SSH": "ignored", "LD_PRELOAD": "", "PATH": "/ignored",
             "GIT_CONFIG_COUNT": "0", "RSYNC_RSH": "ignored"},
            stdin=terminal,
        )
        self.assertEqual(json.loads(stream.readline()), {
            "v": 1, "nonce": 'fixture"\\', "tool": "rg", "cwd": str(self.folder.resolve()),
            "argv": ["--", '匹配"a\n'],
            "env": {"TERM": "xterm", "NO_COLOR": "", "https_proxy": "http://localhost:8042"},
            "tty": {"in": True, "out": False, "err": False, "cols": 119, "rows": 41},
        })
        peer.sendall(b'{"ok":true}\n' + frame(1, b"ready"))
        self.assertEqual(read_exact(process.stdout, 5), b"ready")
        os.write(master, b"status\n")
        self.assertEqual(read_frame(stream), (0, b"status\n"))
        for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
            process.send_signal(signum)
            self.assertEqual(read_frame(stream), (5, struct.pack(">i", signum)))
        fcntl.ioctl(terminal, termios.TIOCSWINSZ, struct.pack("HHHH", 57, 134, 0, 0))
        process.send_signal(signal.SIGWINCH)
        self.assertEqual(read_frame(stream), (6, struct.pack(">HH", 134, 57)))
        peer.sendall(frame(2, b"done") + frame(3, struct.pack(">i", -15)))
        self.assertEqual(process.communicate(timeout=TIMEOUT), (b"", b"done"))
        self.assertEqual(process.returncode, 143)

    def test_shim_relays_signal_with_sustained_input_and_stdout_backpressure(self):
        with open("/dev/zero", "rb") as stdin:
            process, peer, stream = self.open_client(self.shim, [], self.listen(unix=True), stdin=stdin)
            json.loads(stream.readline())
            output = bytes(range(256)) * 256
            peer.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, len(output) * 4)
            peer.sendall(b'{"ok":true}\n' + frame(1, output))
            self.assertEqual(read_frame(stream), (0, bytes(65536)))
            process.send_signal(signal.SIGINT)
            for _ in range(32):
                kind, data = read_frame(stream)
                if kind == 5:
                    self.assertEqual(data, struct.pack(">i", signal.SIGINT))
                    break
                self.assertEqual((kind, data), (0, bytes(65536)))
            else:
                self.fail("持续 I/O 中未及时收到信号帧")
            peer.sendall(frame(3, struct.pack(">i", 0)))
            self.assertEqual(process.communicate(timeout=TIMEOUT), (output, b""))
            self.assertEqual(process.returncode, 0)

    def test_shim_refuses_oversize_header_and_missing_nonce(self):
        process = subprocess.run([self.shim], env={}, capture_output=True, timeout=TIMEOUT)
        self.assertEqual(process.returncode, 127)
        self.assertIn(b"INFINITY_TOOL_BRIDGE_NONCE", process.stderr)
        process = subprocess.run([self.shim, "\n" * 110000, "\t" * 110000],
                                 env={"INFINITY_TOOL_BRIDGE_NONCE": "fixture"},
                                 capture_output=True, timeout=TIMEOUT)
        self.assertEqual(process.returncode, 126)
        self.assertIn(b"256 KiB", process.stderr)

    def test_shim_rejects_malformed_bridge_reply_or_frame(self):
        cases = (
            (b'{"ok":false,"error":"denied"}\n', b"denied\n"),
            (b'{"ok":true,"ok":false}\n', None),
            (b'{"ok":true,"meta":{"other":1,}}\n', None),
            (b'{"ok":true}\n' + struct.pack(">BI", 1, 65537), None),
            (b'{"ok":true}\n' + frame(3, b"short"), None),
            (b'{"ok":true}\n' + frame(1, b"x")[:-1], None),
        )
        for payload, expected in cases:
            with self.subTest(payload=payload):
                with tempfile.TemporaryDirectory(prefix="nxb-", dir="/tmp") as socket_dir:
                    with socket.socket(socket.AF_UNIX) as server:
                        server.settimeout(TIMEOUT)
                        server.bind(str(Path(socket_dir) / "tool.sock"))
                        server.listen()
                        process, peer, stream = self.open_client(self.shim, [], server)
                        self.assertEqual(json.loads(stream.readline())["tty"],
                                         {"in": False, "out": False, "err": False, "cols": 0, "rows": 0})
                        peer.sendall(payload)
                        peer.shutdown(socket.SHUT_WR)
                        _, error = process.communicate(timeout=TIMEOUT)
                        self.assertEqual(process.returncode, 126)
                        if expected:
                            self.assertEqual(error, expected)
                        else:
                            self.assertTrue(error.decode().strip())


if __name__ == "__main__":
    unittest.main()
