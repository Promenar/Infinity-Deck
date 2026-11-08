"""Infinity Deck 内置 pip：运行标准库自带的 pip wheel，包安装到用户目录（PYTHONUSERBASE）。

只在侧载渠道开放（INFINITY_PIP=1）；Play 渠道返回 PIP_UNSUPPORTED。
Android 不允许从应用数据目录加载原生库，含原生扩展的包安装后无法导入，安装结束时列出提醒。
"""

import os
import runpy
import sys

UNSUPPORTED_EXIT = 2


def _bundled_wheel():
    """把标准库 zip 中 ensurepip 自带的 pip wheel 复制到 INFINITY_PIP_DIR（zip 内的 zip 无法直接导入）。"""
    from importlib import resources

    bundled = [entry for entry in resources.files("ensurepip").joinpath("_bundled").iterdir()
               if entry.name.startswith("pip-") and entry.name.endswith(".whl")]
    if len(bundled) != 1:
        raise RuntimeError("标准库中没有唯一的 pip wheel")
    directory = os.environ["INFINITY_PIP_DIR"]
    target = os.path.join(directory, bundled[0].name)
    if not os.path.isfile(target):
        os.makedirs(directory, exist_ok=True)
        temporary = target + ".tmp%d" % os.getpid()
        with open(temporary, "wb") as output:
            output.write(bundled[0].read_bytes())
        os.replace(temporary, target)
        for name in os.listdir(directory):
            if name != bundled[0].name and name.startswith("pip-"):
                os.remove(os.path.join(directory, name))
    return target


def _native_files(root):
    found = set()
    if not os.path.isdir(root):
        return found
    for directory, _, files in os.walk(root):
        for name in files:
            if name.endswith(".so") or ".so." in name:
                found.add(os.path.join(directory, name))
    return found


def main(argv):
    if os.environ.get("INFINITY_PIP") != "1":
        print("pip: PIP_UNSUPPORTED: 当前发行渠道不提供 pip，内置 Python 只能使用标准库与已打包模块；"
              "需要第三方包请改用 termux_exec 或 ssh_exec。", file=sys.stderr)
        return UNSUPPORTED_EXIT
    import site

    user_site = site.getusersitepackages()
    before = _native_files(user_site)
    wheel = _bundled_wheel()
    sys.path.insert(0, wheel)
    sys.argv = ["pip", *argv]
    code = 0
    try:
        runpy.run_module("pip", run_name="__main__", alter_sys=True)
    except SystemExit as exit_:
        code = exit_.code if isinstance(exit_.code, int) else (0 if exit_.code is None else 1)
    added = sorted(_native_files(user_site) - before)
    if added:
        print("pip: 警告：以下文件是原生扩展，Android 不允许从应用数据目录加载，导入对应模块会失败：", file=sys.stderr)
        for path in added[:10]:
            print("  " + os.path.relpath(path, user_site), file=sys.stderr)
        if len(added) > 10:
            print("  …共 %d 个" % len(added), file=sys.stderr)
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
