#!/usr/bin/env python3
"""在长期保留的发行工作树里构建本机签名包，复用 Gradle 增量编译、守护进程与构建缓存。

工作树默认位于仓库同级的 `<仓库名>-release`，每次切换到目标提交而不是新建后丢弃；
签名变量从仓库根 `secure_env/secure.properties` 注入 Gradle 子进程，不打印、不写入日志。
只装真机时只构建侧载渠道；`--play` 同时构建 Play 渠道并执行发行清单检查。
"""
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def git(*args: str, cwd: Path = ROOT) -> str:
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def signing_env() -> dict:
    props = {}
    for line in (ROOT / "secure_env" / "secure.properties").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    env = dict(os.environ)
    env["INFINITY_KEYSTORE_PATH"] = str(ROOT / "secure_env" / "promenar.keystore")
    env["INFINITY_STORE_PASSWORD"] = props["KEYSTORE_PASSWORD"]
    env["INFINITY_KEY_ALIAS"] = props["KEY_ALIAS"]
    env["INFINITY_KEY_PASSWORD"] = props["KEY_PASSWORD"]
    return env


def prepare_worktree(worktree: Path, sha: str) -> None:
    if not worktree.exists():
        git("worktree", "add", "--detach", str(worktree), sha)
    else:
        dirty = git("status", "--porcelain", "--untracked-files=no", cwd=worktree)
        if dirty:
            sys.exit(f"发行工作树有已跟踪文件改动，拒绝切换：{worktree}")
        git("checkout", "--detach", "--quiet", sha, cwd=worktree)
    local_props = worktree / "native-ui" / "local.properties"
    if not local_props.exists():
        shutil.copyfile(ROOT / "native-ui" / "local.properties", local_props)


def has_user_gradle_jvmargs() -> bool:
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    props_file = gradle_home / "gradle.properties"
    if not props_file.is_file():
        return False
    for line in props_file.read_text(encoding="utf-8", errors="ignore").splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#") and stripped.startswith("org.gradle.jvmargs"):
            return True
    return False


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--sha", default="HEAD", help="要构建的提交，默认主检出的 HEAD")
    parser.add_argument("--play", action="store_true", help="同时构建 Play 渠道并检查发行清单")
    parser.add_argument("--worktree", type=Path, default=ROOT.parent / f"{ROOT.name}-release")
    args = parser.parse_args()

    sha = git("rev-parse", args.sha)
    worktree = args.worktree.resolve()
    prepare_worktree(worktree, sha)
    native = worktree / "native-ui"
    tasks = [":app:assembleSideloadRelease"] + ([":app:assemblePlayRelease"] if args.play else [])
    print(f"构建 {sha[:8]} · {' '.join(tasks)} · {worktree}", flush=True)
    # 复用用户级 gradle.properties 的统一守护进程参数，避免临时覆写分裂出第二个常驻 GradleDaemon
    cmd = ["./gradlew"]
    if not has_user_gradle_jvmargs():
        cmd.append("-Dorg.gradle.jvmargs=-Xmx8g -XX:MaxMetaspaceSize=1g")
    cmd.extend(tasks)
    subprocess.run(
        cmd,
        cwd=native, env=signing_env(), check=True,
    )
    sideload = native / "app/build/outputs/apk/sideload/release/app-sideload-release.apk"
    if args.play:
        subprocess.run([sys.executable, "../scripts/ci/check-distribution-manifests.py", "--build-type", "release"], cwd=native, check=True)
        subprocess.run([sys.executable, "../scripts/ci/check-native-page-alignment.py", "--build-type", "release"], cwd=native, check=True)
    else:
        subprocess.run([sys.executable, "../scripts/ci/check-native-page-alignment.py", str(sideload)], cwd=native, check=True)
    print(f"侧载包：{sideload}")
    if args.play:
        print(f"Play 包：{native / 'app/build/outputs/apk/play/release/app-play-release.apk'}")


if __name__ == "__main__":
    main()
