"""发布前以 NDK llvm-readelf 验证 ELF，并复用发行敏感信息扫描规则。"""

import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys

SYSTEM_LIBRARIES = {"libc.so", "libm.so", "libdl.so", "libz.so"}


def main(verifier: str, readelf: str, directory: str, *host_roots: str) -> None:
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("release_verifier", verifier)
    assert spec is not None and spec.loader is not None
    release = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(release)
    host_paths = {os.fsencode(candidate) + b"/" for root in host_roots
                  for candidate in (Path(root).absolute(), Path(root).resolve()) if candidate != Path("/")}
    failures = []
    for library in release.PACKAGED_HOST_TOOL_LIBRARIES:
        file = Path(directory) / library
        with file.open("rb") as stream:
            release.verify_host_tool_elf(stream, file.stat().st_size, library)
            stream.seek(0)
            failures.extend(f"{library}：{label}" for _, label in release.scan_stream(
                stream, release.HOST_TOOL_ABI_DIRECTORY + library
            ))
        data = file.read_bytes()
        if any(path in data for path in host_paths):
            failures.append(f"{library}：包含实际构建机路径")
        output = subprocess.check_output([readelf, "-lW", "-dW", str(file)], text=True)
        aligns = [int(line.split()[-1], 0) for line in output.splitlines()
                  if line.lstrip().startswith("LOAD ")]
        if not aligns or min(aligns) < 16384:
            failures.append(f"{library}：llvm-readelf 报告 PT_LOAD 对齐不足 16 KiB")
        needed = set(re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", output))
        if library == "libnxtoolshim.so":
            if needed or re.search(r"^\s*INTERP\s", output, re.MULTILINE):
                failures.append(f"{library}：静态 shim 包含动态依赖或解释器")
        elif "libc.so" not in needed or needed - SYSTEM_LIBRARIES or "(RPATH)" in output or "(RUNPATH)" in output:
            failures.append(f"{library}：动态依赖或搜索路径无效，DT_NEEDED={sorted(needed)}")
        print(f"{library} {file.stat().st_size} bytes")
    if failures:
        raise SystemExit("\n".join(failures))


if __name__ == "__main__":
    main(*sys.argv[1:])
