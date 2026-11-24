# 侧载 arm64 宿主工具

本目录构建 11 个 Android AArch64 ELF。Sideload arm64 APK 将它们压缩存入
`lib/arm64-v8a/`，安装后由 Android 解压到 `nativeLibraryDir`。Play APK 的发行
校验会拒绝这 11 个文件。既有 FFmpeg/ffprobe 沿用自己的构建入口。

## 构建与缓存

使用项目的 Android NDK 与 API 31 目标、Bash、GNU Make、Patch、Python 3、Tar、
Unzip、Perl shasum，以及 Rust 1.96 或以上版本。保持 NDK、rustc 和 Cargo
版本一致可重建同一工具链下的产物。本次 macOS arm64 验证使用 NDK
`28.2.13676358`、rustc/Cargo `1.98.0`。

从 `native-ui/` 运行：

```sh
rustup target add aarch64-linux-android
./gradlew :app:buildPackagedHostToolsArm64V8a
./gradlew :app:assembleSideloadDebug :app:assemblePlayDebug
```

Gradle 根据 [源码清单](host-tools-android.properties) 下载并核对 SHA-256。
`$GRADLE_USER_HOME/caches/infinity-host-tools/src/` 保存源码包，`built/<fingerprint>/`
保存校验后的 ELF 和逐文件 SHA-256。指纹涵盖工具链版本、源码包、构建输入和发行
校验规则。Rust 依赖由上游 `Cargo.lock` 锁定，并复用 Cargo 下载缓存。
`-Pinfinity.hostToolsCacheDir=/path/to/cache` 可更改缓存根目录。
`-Pinfinity.hostTools=false` 可在本地构建时关闭自动打包。

同目录的构建脚本会剥离符号、通过 NDK `llvm-readelf -lW -dW` 核对 16 KiB
页对齐与动态依赖，并复用 APK 发行校验的敏感信息规则。上游源码、版本和校验值均可从
源码清单恢复。Git 使用上游 C 实现，以及静态、仅启用 HTTP 传输的 libcurl。
应用侧 Git 凭据代理负责到源站的 HTTPS 和凭据注入。运行时
`GIT_EXEC_PATH` 目录应将 `git` 指向 `libnxgit.so`，将 `git-remote-http` 和
`git-remote-https` 指向 `libnxgit-remote-https.so`。Git 会按命令名重入自身以启动
传输 helper。SSH helper 支持 Git 的 OpenSSH 参数探测。

静态 shim 随对应工具名启动，例如 Alpine 中名为 `rg` 的入口指向
`libnxtoolshim.so`。它向 Unix socket 转发当前目录、参数、白名单环境、终端状态和帧。
信号与数据共用有序帧流，发送受同一 socket 的背压约束。Linux 上通过
`/proc/self/fd` 独立打开管道和终端的非阻塞描述符。无法独立打开时保留继承
描述符的行为。宿主工具桥与工具入口的安装由后续运行时工作包提供。

## 验证

从仓库根运行：

```sh
python3 native-ui/app/host-tools/tests/test_bridge_protocol.py
python3 -m unittest scripts/tests/test_verify_release_apk.py
python3 scripts/ci/check-native-page-alignment.py
python3 scripts/ci/check-distribution-manifests.py
bash scripts/ci/android-static-checks.sh
```

宿主协议测试用 clang 分别编译 Git SSH helper 和 shim 的 POSIX 测试适配层，
连接 TCP/Unix 模拟桥。AArch64 shim 在目标端使用 Linux 系统调用入口。
