"""Infinity Workbench Python 沙箱宿主：在 isolatedProcess 中运行用户代码。

隔离进程看不到应用数据与工作区物理目录，没有可写目录与网络。用户代码通过 `import infinity`
发起能力调用（读写工作区、受限 fetch），由控制通道交给 Infinity Workbench 主进程执行与审批。

控制通道是进程原始的 stdin/stdout，每行一个 JSON 帧：
- 宿主 → 沙箱：{"t":"start","code":...,"stdin":...,"args":[...]}、{"t":"cap_result","id":N,"value":...}
  或 {"t":"cap_result","id":N,"errorCode":...,"error":...}
- 沙箱 → 宿主：{"t":"out","fd":1|2,"d":...}、{"t":"cap","id":N,"op":...,"args":{...}}、
  {"t":"done","exit":N,"result":...,"result_error":...}

用户代码的 fd 0/1/2 在执行前换成管道，C 扩展或 os.write 写出的内容同样经 out 帧转发，不会混入控制帧。
"""

import builtins
import codecs
import io
import json
import os
import sys
import threading
import traceback
import types

RESULT_LIMIT = 16 * 1024
OUT_CHUNK = 8 * 1024


class InfinityError(Exception):
    """能力调用被拒绝或失败；code 为 Infinity Workbench 错误码（如 APPROVAL_DENIED、FILE_LIMIT）。"""

    def __init__(self, code, message):
        super().__init__("%s: %s" % (code, message))
        self.code = code
        self.message = message


class _Channel:
    def __init__(self, read_fd, write_fd):
        self._reader = os.fdopen(read_fd, "rb")
        self._write_fd = write_fd
        self._write_lock = threading.Lock()
        self._call_lock = threading.Lock()
        self._next_id = 0

    def send(self, frame):
        data = (json.dumps(frame, ensure_ascii=False) + "\n").encode("utf-8")
        with self._write_lock:
            view = memoryview(data)
            while view:
                written = os.write(self._write_fd, view)
                view = view[written:]

    def receive(self):
        line = self._reader.readline()
        if not line:
            raise EOFError("控制通道已关闭")
        return json.loads(line.decode("utf-8"))

    def call(self, operation, arguments):
        # 能力调用串行执行：宿主一次只处理一个请求，结果按 id 对应。
        with self._call_lock:
            self._next_id += 1
            call_id = self._next_id
            self.send({"t": "cap", "id": call_id, "op": operation, "args": arguments})
            while True:
                frame = self.receive()
                if frame.get("t") == "cap_result" and frame.get("id") == call_id:
                    break
            if frame.get("errorCode"):
                raise InfinityError(frame["errorCode"], frame.get("error") or "能力调用失败")
            return frame.get("value")


def _pump(channel, fd, source):
    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    while True:
        chunk = os.read(source, OUT_CHUNK)
        text = decoder.decode(chunk, final=not chunk)
        if text:
            channel.send({"t": "out", "fd": fd, "d": text})
        if not chunk:
            break
    os.close(source)


def _feed(target, data):
    try:
        view = memoryview(data)
        while view:
            written = os.write(target, view)
            view = view[written:]
    except OSError:
        pass
    finally:
        os.close(target)


def _module(channel, args):
    module = types.ModuleType("infinity", "Infinity Workbench 工作区与网络能力（沙箱模式）。路径为工作区虚拟路径，如 /data.csv、/mnt/<别名>/x。")

    def call(operation, **arguments):
        return channel.call(operation, {key: value for key, value in arguments.items() if value is not None})

    def read(path):
        """读取 UTF-8 文本文件，返回 {"path","content","sha256"}。"""
        return call("read", path=str(path))

    def read_text(path):
        return read(path)["content"]

    def stat(path):
        """返回条目元数据；不存在时返回 None。"""
        return call("stat", path=str(path))

    def list_dir(path="/"):
        return call("list", path=str(path))

    def write(path, content, expected_sha256):
        """覆盖已有文件；expected_sha256 须与当前内容一致，否则拒绝。"""
        return call("write", path=str(path), content=str(content), expected_sha256=expected_sha256)

    def create(path, content=""):
        return call("create", path=str(path), content=str(content))

    def save(path, content):
        """文件不存在时新建，存在时读取当前哈希后覆盖。"""
        if stat(path) is None:
            return create(path, content)
        return write(path, content, read(path)["sha256"])

    def mkdir(path):
        return call("mkdir", path=str(path))

    def delete(path):
        """永久删除工作区文件或目录，需要用户批准。"""
        return call("delete", path=str(path))

    def fetch(url, method="GET", headers=None, body=None):
        """需 allow_network=true；返回 {"url","status","headers","body"}，body 为文本。"""
        return call("fetch", url=str(url), method=method, headers=headers, body=body)

    module.read = read
    module.read_text = read_text
    module.stat = stat
    module.list = list_dir
    module.write = write
    module.create = create
    module.save = save
    module.mkdir = mkdir
    module.delete = delete
    module.fetch = fetch
    module.args = tuple(args)
    module.InfinityError = InfinityError
    return module


def _encode_result(namespace):
    if "result" not in namespace:
        return None, None
    try:
        encoded = json.dumps(namespace["result"], ensure_ascii=False, default=str)
    except (TypeError, ValueError) as error:
        return None, "result 无法序列化为 JSON：%s" % error
    if len(encoded.encode("utf-8")) > RESULT_LIMIT:
        return None, "result 超过 %d 字节上限，请改为写入文件或打印摘要" % RESULT_LIMIT
    return json.loads(encoded), None


def main():
    channel = _Channel(os.dup(0), os.dup(1))
    start = channel.receive()
    if start.get("t") != "start":
        return 2

    stdin_read, stdin_write = os.pipe()
    out_read, out_write = os.pipe()
    err_read, err_write = os.pipe()
    os.dup2(stdin_read, 0)
    os.dup2(out_write, 1)
    os.dup2(err_write, 2)
    for fd in (stdin_read, out_write, err_write):
        os.close(fd)
    sys.stdin = io.TextIOWrapper(io.FileIO(0, "rb", closefd=False), encoding="utf-8", errors="replace")
    sys.stdout = io.TextIOWrapper(io.FileIO(1, "wb", closefd=False), encoding="utf-8", errors="backslashreplace",
                                  line_buffering=True)
    sys.stderr = io.TextIOWrapper(io.FileIO(2, "wb", closefd=False), encoding="utf-8", errors="backslashreplace",
                                  line_buffering=True)
    pumps = [threading.Thread(target=_pump, args=(channel, fd, source), daemon=True)
             for fd, source in ((1, out_read), (2, err_read))]
    for pump in pumps:
        pump.start()
    threading.Thread(target=_feed, args=(stdin_write, (start.get("stdin") or "").encode("utf-8")), daemon=True).start()

    sys.modules["infinity"] = _module(channel, start.get("args") or [])
    sys.argv = ["<sandbox>", *[str(arg) for arg in start.get("args") or []]]
    namespace = {"__name__": "__main__", "__builtins__": builtins}
    exit_code = 0
    try:
        exec(compile(start.get("code") or "", "<sandbox>", "exec"), namespace)
    except SystemExit as stop:
        if stop.code is None:
            exit_code = 0
        elif isinstance(stop.code, int):
            exit_code = stop.code
        else:
            print(stop.code, file=sys.stderr)
            exit_code = 1
    except BaseException:
        traceback.print_exc()
        exit_code = 1
    result, result_error = _encode_result(namespace)

    for stream in (sys.stdout, sys.stderr):
        try:
            stream.flush()
        except (OSError, ValueError):
            pass
    devnull = os.open(os.devnull, os.O_WRONLY)
    os.dup2(devnull, 1)
    os.dup2(devnull, 2)
    os.close(devnull)
    for pump in pumps:
        pump.join(timeout=5)
    channel.send({"t": "done", "exit": exit_code, "result": result, "result_error": result_error})
    return 0


if __name__ == "__main__":
    sys.exit(main())
