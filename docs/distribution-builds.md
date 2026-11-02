# 发行渠道构建与富内容渲染

`distribution` 为 `play` 与 `sideload`。两者共享代码与 `com.promenar.infinity`，Debug、DeviceTest、MinifiedTest 的后缀分别为 `.debug`、`.deviceTest`、`.minifiedTest`。同构建类型的两个渠道会覆盖彼此，不能并列安装。`DistributionCapabilities` 描述制品能力，不能代表系统已经授予权限。Infinity Workbench 与旧应用使用不同应用 ID，可并存安装；数据通过旧应用导出备份、新应用导入迁移，加密备份使用用户密码解密。

只有 `src/sideload/AndroidManifest.xml` 声明 `MANAGE_EXTERNAL_STORAGE`。真实路径目录后端位于 `main` 源集，两个渠道都用它读写 `filesDir/projects/` 下的 Infinity Workbench 项目文件夹工作区；共享存储真实路径（外部挂载与共享文件夹工作区）只在侧载渠道开放，并需取得系统“所有文件访问”授权；Play 渠道的外部挂载仅使用 SAF。同样只有侧载渠道声明 `SCHEDULE_EXACT_ALARM`（`BuildConfig.EXACT_ALARM_AVAILABLE`），定时任务可在用户授权后使用精确闹钟；Play 渠道由 JobScheduler 择时、最短 15 分钟，后台任务也只在应用可见时保证运行（`DistributionCapabilities.backgroundKeepAliveAvailable`）。两个渠道都声明 `RECEIVE_BOOT_COMPLETED` 以在开机后重新排期，`scripts/ci/check-distribution-manifests.py` 校验这两项差异。设置页“导出诊断”生成的 JSONL 首行 `app.build` 包含 `distribution`、`allFilesAccessAvailable` 与版本。

构建插件使用 Kotlin 2.4.10 编译器、Compose 与序列化插件，应用源码保留 2.2 语言级别；协程运行和测试库为 1.11.0。QuickJS 1.0.15 的 Android JNI 在两个渠道共用，JVM 单测仅在运行时替换为桌面 JNI。AAR 自带 JNI 消费端保留规则；项目不使用脚本对象反射绑定。隔离服务设备回归类为 `com.promenar.infinity.data.script.ScriptRuntimeDeviceTest`，设备测试默认 ABI 与 `infinity.deviceE2eAbi` 配置一致；ARM64 设备构建需显式传 `-Pinfinity.deviceE2eAbi=arm64-v8a`。

在 `native-ui/` 串行执行：

```bash
./gradlew :app:testSideloadDebugUnitTest :app:testPlayDebugUnitTest
./gradlew :app:lintSideloadDebug
./gradlew :app:assembleSideloadDebug :app:assemblePlayDebug
./gradlew :app:assembleSideloadMinifiedTest :app:assemblePlayMinifiedTest
./gradlew -Pinfinity.deviceE2e=true -Pinfinity.deviceE2eAbi=arm64-v8a :app:assembleSideloadDeviceTest :app:assembleSideloadDeviceTestAndroidTest :mainactivity-e2e:assembleDeviceTest
./gradlew :app:validateSideloadDebugScreenshotTest :app:validatePlayDebugScreenshotTest
python3 ../scripts/ci/check-distribution-manifests.py
python3 ../scripts/ci/check-native-page-alignment.py
```

发行前须在本机跑完全量门禁；发行流水线只验证本机不覆盖的环境。本机必跑 `:app:testSideloadDebugUnitTest`、`:app:lintSideloadDebug`、`:app:assembleSideloadDebug`、`:app:validateSideloadDebugScreenshotTest`、`:app:assemblePlayDebug` 及两个 Python 发行检查，并安装本机签名的侧载 Release 包完成真机验收。

本机截图验证共用 `app/src/screenshotTestDebug/reference/`，不按渠道复制像素。独立的 `screenshot-validate.yml` 保留 Linux 分片验证能力：先校验 `reference-linux-overlay/manifest.json`，再将其中已审阅的跨平台差异合成为临时参考目录，检查各类的测试结果并保留报告；基准或覆盖文件变化时必须重新审阅，截图比较阈值保持不变。设备构建只生成 APK；设备脚本会连接并修改设备，需在已授权的专用本机 AVD 上另行执行。`mainactivity-e2e` 以 `missingDimensionStrategy` 固定匹配侧载应用，其任务名保持 `assembleDeviceTest`。黑盒夹具为独立应用，保持 `:blackbox-fixture:assembleDebug`。

需要排查托管 Linux 的截图差异时，可手动运行 GitHub Actions 的“Android 截图诊断”。该工作流使用截图验证任务，默认检查空会话预览，也可通过 `preview_filter` 指定其他预览；产物包含参考图、渲染结果与报告。它不使用发行签名材料，也不替代全量截图或发行候选验证。

“Android 截图 Linux 参考图候选”可手动分片生成 Linux 渲染结果，并将每个分片的原参考图、候选及源文件清单作为 Actions 产物保留，供逐项比较和审阅；生成操作只发生在临时 runner，不自动提交或更改仓库。本机全量截图门禁仍须独立完成。全部分片成功且清单覆盖所有截图源文件后，候选才具备完整性。

排查发行构建的 JVM 内存不足时，可手动运行“Android Gradle 编译内存诊断”。它不使用签名材料，可对比组合与分阶段任务，也可单独诊断分阶段变体编译，并保留 Gradle、Kotlin 进程的堆和宿主内存采样；该诊断不生成或发布签名包，也不替代发行门禁。

排查 Linux 托管环境的 JVM 单测时，可手动运行“Android JVM 单测诊断”，指定测试过滤条件并读取保留的 XML 和测试报告；可选采样测试进程的 JVM 参数和堆使用。该入口不使用签名材料，不替代完整单测。

侧载与 Play 的 Debug JVM 单测按测试类分到多个进程并行，每个进程配置 1 GiB 堆；默认进程数取 CPU 核心数的一半且不超过 4，内存紧张时可用 `-Pinfinity.unitTestForks=<n>` 下调（传 1 即恢复单进程）。Gradle 和 Kotlin 守护进程继续使用各自的内存配置。

发行签名构建在版本输入校验与静态检查通过后启动，与设备作业并行；同一次 Gradle 调用执行 `:app:assembleSideloadRelease :app:assemblePlayRelease`，复用守护进程和缓存，随后执行两个 Python 检查的 `--build-type release` 模式。发行构建失败时保留 Gradle problems、清单合并日志及 R8 输出供诊断。

依赖 Android 文件描述符与目录身份实现的工作区安全测试在设备回归中执行，覆盖根目录替换、符号链接竞争、回滚和旧回收目录恢复；不依赖 Android 桥接的协调逻辑仍由 JVM 单测覆盖。排查设备测试时可手动运行“Android 工作区设备诊断”，检查七项测试及执行日志，该诊断不替代完整设备门禁。

| 变体 | APK 相对 `native-ui/` 的路径 | mapping |
|---|---|---|
| sideloadDebug | `app/build/outputs/apk/sideload/debug/app-sideload-debug.apk` | 无 |
| playDebug | `app/build/outputs/apk/play/debug/app-play-debug.apk` | 无 |
| sideloadDeviceTest | `app/build/outputs/apk/sideload/deviceTest/app-sideload-deviceTest.apk` | 无 |
| sideloadDeviceTestAndroidTest | `app/build/outputs/apk/androidTest/sideload/deviceTest/app-sideload-deviceTest-androidTest.apk` | 无 |
| mainactivity-e2e DeviceTest | `mainactivity-e2e/build/outputs/apk/deviceTest/mainactivity-e2e-deviceTest.apk` | 无 |
| sideloadMinifiedTest | `app/build/outputs/apk/sideload/minifiedTest/app-sideload-minifiedTest.apk` | `app/build/outputs/mapping/sideloadMinifiedTest/mapping.txt` |
| playMinifiedTest | `app/build/outputs/apk/play/minifiedTest/app-play-minifiedTest.apk` | `app/build/outputs/mapping/playMinifiedTest/mapping.txt` |
| sideloadRelease | `app/build/outputs/apk/sideload/release/app-sideload-release.apk` | `app/build/outputs/mapping/sideloadRelease/mapping.txt` |
| playRelease | `app/build/outputs/apk/play/release/app-play-release.apk` | `app/build/outputs/mapping/playRelease/mapping.txt` |

本机签名构建入口为 `python3 scripts/release/build-signed-local.py --sha <提交> [--play]`：它在仓库同级的长期发行工作树 `Nexara-release` 中切换到目标提交，复用 Gradle 增量编译、守护进程与构建缓存，并从 `secure_env/` 向 Gradle 子进程注入签名变量；默认只构建侧载渠道并检查 16KB 对齐，`--play` 同时构建 Play 渠道并检查发行清单。Release 构建需要安全注入既有四个 `INFINITY_*` 签名变量，在获得签名构建授权的主控会话执行 `:app:assembleSideloadRelease :app:assemblePlayRelease`，随后执行 `python3 ../scripts/ci/check-distribution-manifests.py --build-type release` 与 `python3 ../scripts/ci/check-native-page-alignment.py --build-type release`。不把秘密写入命令、文档或报告。

Android CI 在 PR 与手动触发时运行 `scripts/ci/android-static-checks.sh`，检查内置模型目录、文本换行和工作流脚本契约；该入口也由发行工作流执行。发行工作流 `.github/workflows/release.yml` 只在推送 `v*` 注释标签（或在 `main` 上手动触发候选验证）时运行，日常推送不会构建 APK。`scripts/ci/release-metadata.py` 从 `native-ui/app/build.gradle.kts` 读取 versionName 与 versionCode，要求标签等于 `v<versionName>`、指向 `main` 历史中的提交，并从 `CHANGELOG.md` 的 `## [<versionName>]` 小节生成发布说明；该小节缺失或为空时拒绝发布。

发行流水线的设备覆盖为 API 31 / `minimum` 的未混淆 deviceTest 核心可达性，以及 API 36 的 Release 等价 R8 minifiedTest 黑盒。签名构建使用 release 环境的 `NEXARA_*` 签名机密，整理侧载主资产 `dist/infinity-v<版本>.apk` 和 Play 资产 `dist/infinity-v<版本>-play.apk`，二者各有 `.sha256`；R8 证据分别进入 `dist/r8/sideload/` 与 `dist/r8/play/`，只作为保留 3 天的 Actions 制品。实际签名 APK 冷安装覆盖侧载 / API 31、侧载 / API 36、Play / API 36，校验对应哈希、包名、版本与发行证书。`publish` 等待版本输入校验、全部冷安装、API 31 deviceTest 及 API 36 minifiedTest 黑盒通过，再以 draft 上传资产、逐个回读哈希，最后公开 Release；版本名带 `-` 后缀时标为 prerelease。手动触发只做候选验证，不发布。

改动设备相关功能时，维护者可通过 `device-regression.yml` 的 `workflow_dispatch` 运行 API 35/36 的 `full` deviceTest 完整业务回归。该工作流复用 `scripts/ci/android-device-core-e2e.sh`、工具链与证据上传步骤，使用独立并发组，不阻断发行；沿用托管模拟器的覆盖边界：跳过 Tailscale 控制面交互登录，未配置 SSH 会话时跳过 SSH 设备用例。

`scripts/ci/android-release-upgrade-smoke.sh` 依赖已下线的 v0.1 公开基线，目前不在工作流中运行，可在本机用同签名旧包手动执行。发行不代表 Play 商店审核通过。

发布步骤：完成版本提交并推送 `main` 后，在用户授权下执行 `git tag -a v<版本> -m "Infinity Workbench v<版本>"` 与 `git push origin v<版本>`。

## 打包 Python

两个渠道都随 APK 打包官方 CPython Android 嵌入包（版本与 SHA-256 见 `native-ui/app/python/python-android.properties`，与 python.org 发布页及 sigstore 包内摘要一致）。官方只提供 arm64-v8a 与 x86_64：设了 `abiFilters` 的变体（Debug、DeviceTest、MinifiedTest）按过滤准备；未设过滤的 Release 通用包只带 arm64-v8a，x86_64 Python 约 11.5 MB 只服务 x86_64 设备，不放进通用包。armeabi-v7a、x86 与 x86_64 设备上的 Release 没有 Python。

构建输入下载遇到连接错误或 HTTP 408、429、5xx 时最多尝试三次；其他 HTTP 错误直接失败。成功响应仍须通过固定 SHA-256 校验才能写入目标，摘要不符不会重试或采用。下载重试回归可运行 `python3 scripts/ci/tests/verified-download-retry-test.py`。

构建任务 `fetchPackagedPython<ABI>` 下载到 Gradle 用户目录的 `caches/infinity-python-android/`，摘要不符时失败且不落盘；缓存命中时不联网。离线构建可用 `-Pinfinity.pythonDistDir=<目录>` 指向已放好官方压缩包的目录，文件仍按 SHA-256 校验。`preparePackagedPython<ABI>` 把压缩包整理到 `app/build/generated/packagedPython/<ABI>/` 并作为生成的 jniLibs 目录接入，不入库：

- `libpython3.14.so` 与 `lib*_python.so`（OpenSSL、SQLite）原样保留；
- 标准库纯 Python 部分存为不压缩 zip `libpystdlib.so`（解释器启动早期 zlib 尚不可用），裁剪 `test/`、`idlelib/`、`tkinter/`、`turtledemo/`、`pydoc_data/`，根目录附 `python/sitecustomize.py`；预编译后只放 `.pyc`（无法编译的文件保留源码），标准库回溯不显示源码行；
- `lib-dynload` 中的扩展改名为 `libpyext_<模块>.so`，由 `sitecustomize.py` 的 meta_path finder 从 `INFINITY_PYEXT_DIR` 加载；
- 启动器 `python/pylauncher.c` 用 AGP 所选 NDK 的 clang 编译为 `libnxpython.so`（`-Wl,-rpath,$ORIGIN`，16 KB 页对齐）。

内置 git 的纯 Python 依赖单独打包：`fetchPackagedPythonWheel<名称>` 按 `native-ui/app/python/python-site.properties` 中的 PyPI 地址与 SHA-256 下载 dulwich、urllib3 的 `py3-none-any` wheel（缓存在同一目录的 `wheels/` 下，`-Pinfinity.pythonDistDir` 同样适用）；`preparePackagedSite<ABI>` 把 wheel 内容（去掉 `*.data/`、`__pycache__` 与 `dulwich/tests/`）与 `python/site/` 中的 `infinity_git.py`、`infinity_pip.py` 合成不压缩 zip `libpysite.so`，含原生库、路径越界或模块重名时构建失败；预编译后每个 `.py` 旁另有 `.pyc`，源码保留，内置 git 出错时回溯仍有源码行。两个 zip 都不是 ELF，`keepDebugSymbols` 跳过符号剥离。

两个 zip 都在构建期预编译字节码：zipimport 只认 `.py` 旁的同名 `.pyc`，且官方包不带 pyc；不预编译时每次启动解释器都要重新编译导入的模块，真机（OPPO PKH110）上导入 dulwich 约 1.1 秒、一次 `--infinity-inspect status` 约 2 秒，预编译后分别约 0.15 秒与 0.2 秒，`shell_exec` 中的每条 `git` 命令同样提速约十倍。`python/compile-bytecode.py` 生成不核对源码的哈希 pyc（PEP 552，zip 随 APK 只读安装），`co_filename` 取 zip 内相对路径，不写入构建机路径。宿主解释器须与打包 CPython 的 pyc magic 一致：默认使用 PATH 中的 `python3.14`，可用 `-Pinfinity.hostPython=<路径>` 指定；magic 登记在 `python-android.properties` 的 `pycMagic`，构建时与官方包 `include/python3.14/internal/pycore_magic_number.h` 核对，宿主不一致或找不到解释器时构建失败。`-Pinfinity.pythonBytecode=false` 跳过预编译，只用于没有 Python 3.14 的临时环境，发行校验会拒绝这样的 APK。不在首次运行时把 pyc 写入数据目录：数据目录可被命令工具改写，免审批运行的 Git 检查会加载被篡改的字节码。CI 的 Gradle 作业用 `actions/setup-python` 安装 3.14。

同一输入与同一宿主 Python 版本生成的 zip 与启动器字节一致。`packaging.jniLibs.useLegacyPackaging = true` 让安装器把全部原生库解压到 `nativeLibraryDir`，启动器才能被 execve；代价是 APK 内原生库改为压缩存放、安装后多占约 38 MB（单 ABI，其中预编译字节码约 5.4 MB）。运行时由 `data/exec/PackagedPython` 定位启动器、标准库并给出解释器环境变量。

设备回归类为 `com.promenar.infinity.data.exec.PackagedPythonDeviceTest`，已纳入 `android-device-core-e2e.sh`：检查原生库已解压、版本与 `BuildConfig.PACKAGED_PYTHON_VERSION` 一致、ssl/sqlite3/hashlib/zlib/ctypes/decimal/socket 可用且扩展来自 `nativeLibraryDir`、`test` 包已裁剪，以及退出码与 stderr 原样返回。

发行校验 `scripts/verify-release-apk.py` 的敏感信息扫描对这些条目有窄豁免：`libpython3.*`、`lib*_python.so`、`libpyext_*.so` 与 `libpystdlib.so` 放行 python.org 构建机的 `/Users/runner/`、`/home/runner/`；`libpystdlib.so` 另放行标准库中的保留地址常量与 venv 激活脚本里的 `C:\Users\` 示例；`libpysite.so` 只放行 urllib3 文档中的占位地址 `https://username:password@`。凭据类模式、其它用户路径和自编译的 `libnxpython.so` 不在豁免内。校验还要求每个 ABI 同时带 `libpystdlib.so` 与 `libpysite.so`，`os`、`encodings`、`sitecustomize`、`infinity_git`、`dulwich`、`dulwich.repo` 有不核对源码的哈希 pyc，且同一 ABI 内 magic 一致。Release 通用 APK 实测约 34.7 MB（其中内置 git 依赖约 2.5 MB，预编译字节码合计约 3.6 MB），校验上限 50 MB。

升级 Python：在 python.org 发布页、sigstore 包内摘要与本地下载三处核对 SHA-256 后修改 `python-android.properties`，按新包的 `pycore_magic_number.h` 更新 `pycMagic`，准备同主次版本的宿主解释器（本机与 CI 的 `python-version`），再运行设备回归。升级 dulwich 或 urllib3：核对 PyPI 文件页的 SHA-256 与 dulwich 的依赖要求后修改 `python-site.properties`，再运行 `BuiltinExecDeviceTest`（含本地提交、裸仓库推拉、HTTPS 克隆与侧载 pip 安装）与 `com.promenar.infinity.data.git.remote.GitRemoteAuthDeviceTest`（`android-device-core-e2e.sh` 的 `git-remote-auth` 阶段：本机 dulwich 智能 HTTP 上游经凭据注入代理 clone/push、未配置主机直连、SSH 远端经 SSH 桥 clone/push，不需要外网）。

## 内置 Tailscale

两个渠道都打包 `libnxtailnet.so`：`native-ui/app/tailnet/` 的 Go 程序（tailscale.com 的 tsnet，版本由 `go.mod`/`go.sum` 锁定），由应用以子进程启动，经 stdin/stdout 的 JSON 行协议控制。ABI 规则与打包 Python 相同：设了 `abiFilters` 的变体按过滤打包，未设过滤的 Release 通用包只带 arm64-v8a。arm64-v8a 以 `GOOS=android GOARCH=arm64` 编译；x86_64 以 `GOOS=linux GOARCH=amd64` 编译（Go 的 android/amd64 必须 cgo 外部链接），两者都 `CGO_ENABLED=0` 静态链接，`feature/androidbin` 提供合成网卡、经系统 dnsproxyd 的 DNS 与系统 CA 证书。

构建任务 `buildTailnet<ABI>` 输出到 `app/build/generated/tailnet/<ABI>/`，作为生成的 jniLibs 目录接入，不入库。任务固定 `GOTOOLCHAIN=go1.27.1`、`GOFLAGS=-mod=readonly`，使用 `-trimpath -buildvcs=false -ldflags=-s -w -buildid= -R 0x10000`（`-R` 显式固定 64 KB 段对齐，见[16 KB 页大小](#16-kb-页大小)），同一输入得到相同产物，二进制不含构建机路径；宿主只需 Go 1.21 及以上，`-Pinfinity.goExecutable=<路径>` 指定 go。首次构建由 Go 模块代理下载工具链与依赖，离线构建需预先填好 `GOMODCACHE` 并设 `GOPROXY=off`。`-Pinfinity.tailnet=false` 跳过编译，只用于没有 Go 的临时环境：设置页提示安装包未包含内置 Tailscale，发行校验拒绝这样的 APK。CI 的 Gradle 作业用 `actions/setup-go`（按 SHA 固定）安装 Go 1.27.1。Go 侧单元测试：在 `native-ui/app/tailnet/` 运行 `go test -race ./...` 与 `go vet ./...`。

可执行文件已剥离符号，`keepDebugSymbols` 保持原样。运行时只传入 `HOME`、`TMPDIR`，节点状态目录位于 `noBackupFilesDir/tailnet`，不进备份；更换控制服务器会清空该目录。本机代理只监听 127.0.0.1，同一端口同时提供 HTTP（CONNECT 与绝对 URI）和 SOCKS5，用户名与口令每次启动随机生成；tailnet IP 与 MagicDNS 名称经 tsnet 转发，其它目标由子进程直连（域名经 Android 系统 DNS 解析），节点未连接时 tailnet 目标返回 503，不回落到公网。

发行校验 `scripts/verify-release-apk.py` 要求每个带打包 Python 的 ABI 都有 `libnxtailnet.so`，且 ELF 架构与 ABI 一致。敏感信息扫描对 `libnxtailnet.so` 只放行两类上游常量：`sk-` 开头的小写字母与连字符组合（Go 符号拼接产生的误报）与登记的私有及保留网段地址（如 `10.0.0.0`、`100.64.0.0`、`192.168.0.0`、`100.100.100.100`）；凭据类模式与构建机路径不在豁免内。第三方许可原文见 `docs/legal/tailnet/`。

升级 tailscale.com 或 Go：在 `native-ui/app/tailnet/` 修改 `go.mod` 后运行 `go mod tidy` 更新 `go.sum`，同步 `build.gradle.kts` 的 `tailnetGoVersion` 与两个工作流的 `go-version`，重新生成 `docs/legal/tailnet/go-modules-LICENSES.txt`，再运行 `TailnetDeviceTest`（沙箱启动、待登录链接、代理凭据与回环监听、公网直连、stdin 关闭后自行退出；需要设备能访问公网）。

## GitHub 浏览器登录

“Git 凭据 › 用浏览器登录 GitHub”使用 GitHub OAuth App 的设备码流程（Device Flow），应用里只放 client_id，不需要 client_secret。client_id 由 Gradle 属性 `infinity.githubOAuthClientId` 在构建时写入 `BuildConfig.GITHUB_OAUTH_CLIENT_ID`，只允许字母、数字、`.`、`_` 与 `-`。client_id 是公开标识，`native-ui/gradle.properties` 写入了 Infinity Workbench 官方 OAuth App 的值；自建分发可在 `~/.gradle/gradle.properties` 或命令行 `-Pinfinity.githubOAuthClientId=<client_id>` 覆盖，置空时界面不显示该入口，只保留手动添加令牌。

OAuth App 需在 GitHub 的 App 设置中勾选 “Enable Device Flow”。应用申请 `repo` 范围；授权得到的令牌以主机 `github.com`、GitHub 登录名为用户名存入 Git 凭据，内置 git 的 HTTPS 凭据注入代理按 `Authorization: Basic` 使用，与手动保存的个人访问令牌走同一路径。

OAuth App 不勾选 “Expire user access tokens”，令牌没有固定有效期，但仍会因用户撤销授权、一年未使用、同一用户同一范围超过 10 个令牌、密钥扫描吊销或删除 OAuth App 而失效。上游返回 401 时代理以 `CREDENTIAL_REJECTED` 拒绝并在凭据上记录 `rejectedAtMillis`，设置页提示重新登录；GitHub 登录凭据的 403 以 `ACCESS_DENIED` 提示仓库权限或组织的 OAuth 应用访问限制，不标记失效。

## 内置 Linux 环境

只有侧载渠道且变体包含 arm64-v8a 时接入 Linux 制品；Play 变体与纯 x86_64 变体不接入，运行时分别报告 `Unavailable(DISTRIBUTION)` 与 `Unavailable(UNSUPPORTED_ABI)`（`DistributionCapabilities.linuxRuntimeAvailable`）。固定输入登记在 `native-ui/app/linux/linux-runtime.properties`：termux/proot 提交、talloc 2.4.4 与 Alpine 3.24.3 aarch64 minirootfs 的下载地址和 SHA-256。talloc 使用 MacPorts 的 Samba 原始包镜像，仍校验固定摘要。

- `fetchLinuxRuntime<Proot|Talloc|Alpine>` 下载到 `app/build/linux-downloads/` 并校验摘要；`buildLinuxRuntime` 用 AGP 所选 NDK 与 `app/linux/CMakeLists.txt` 编译 `libnxproot.so`（proot 与入口）和 `libnxproot-loader.so`；`prepareLinuxRuntimeAssets` 把原始归档放为 `assets/linux/alpine-minirootfs.tar.gz.bin`（`.bin` 后缀防止资产合并器解压），摘要与解包大小写入 BuildConfig。
- 两个库随 `useLegacyPackaging` 解压到 `nativeLibraryDir` 后执行（W^X），rootfs 在安装时解到 `filesDir/linux/alpine`。
- 本地终端的 PTY 桥接 `libtermux.so` 由 `app/src/main/cpp/termux/termux.c`（Termux terminal-emulator v0.118.0 原样源码，来源与哈希见同目录 README）经项目 CMake 以 16 KB 页对齐重新编译，覆盖 AAR 自带的 4 KB 对齐副本；只在侧载变体打包，Play 变体排除。
- 侧载清单额外声明 `ACCESS_NETWORK_STATE`（安装时读取当前网络 DNS）与受 `MANAGE_DOCUMENTS` 保护的 `${applicationId}.linux.documents` DocumentsProvider（只暴露 `/root`）；`scripts/ci/check-distribution-manifests.py` 校验侧载具备、Play 缺席。
- `scripts/verify-release-apk.py` 需传入渠道参数：侧载 APK 必须同时含两个库与 rootfs 资产且摘要与 properties 一致，Play APK 不得含任一 ABI 的 Linux 库或资产。Debug 侧载 APK 增加约 4.1 MB（压缩后）。

设备回归类：`com.promenar.infinity.data.linux.LinuxRuntimeDeviceTest`（安装、诊断、工作区读写、重置、卸载）、`LinuxTerminalDeviceTest`（PTY 输出与进程组清理）、`LinuxFileIoDeviceTest`（fd 固定、不覆盖移动）。

## 16 KB 页大小

两个渠道的 64 位原生库（arm64-v8a、x86_64）全部按不小于 16 KB 的 PT_LOAD 段对齐链接，16 KB 页设备无需兼容模式，4 KB 页设备上调试包也不再弹出“Android 应用兼容性”对话框。原生库以 `useLegacyPackaging` 解压安装，系统只检查解压后的 ELF 段对齐，不要求 APK 内 ZIP 偏移对齐。

| 库 | 来源 | 对齐保证 |
|---|---|---|
| `libinfinity_workspace_fs.so`、`libtermux.so`，推理开启时的 llama/ggml | `app/src/main/cpp/CMakeLists.txt` | 全局 `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`，覆盖 FetchContent 子工程 |
| `libnxproot.so`、`libnxproot-loader.so` | `app/linux/CMakeLists.txt` | 各目标 `-z,max-page-size=16384` |
| `libnxpython.so` | `python/pylauncher.c` | `-Wl,-z,max-page-size=16384` |
| `libnxtailnet.so` | Go 内部链接器（`CGO_ENABLED=0`） | `-ldflags=-R 0x10000`，64 KB |
| `libpython3.*.so`、`libpyext_*.so` 等 | python.org 官方 Android 预编译包 | 上游已 16 KB 对齐，无法重链，由下方检查兜底 |
| AndroidX 等依赖 AAR 自带库 | Maven 依赖 | 由下方检查兜底 |

`scripts/ci/check-native-page-alignment.py` 逐个读取两个渠道 APK 内 64 位 ELF 的程序头，任一 PT_LOAD 对齐小于 16 KB、未压缩存放的库 ZIP 偏移未按 16 KB 对齐或 APK 中没有 64 位原生库时失败；`libpystdlib.so`、`libpysite.so` 是借 `.so` 名随安装解压的 Python 归档而非 ELF，跳过。发行工作流在构建后执行该检查；Android CI 运行检查器的静态契约测试。升级 terminal-emulator 依赖时须同步替换 `termux.c`；若 AAR 内的库改为 16 KB 对齐，可删除本地源码与 CMake 目标。

调试包弹出兼容性对话框时，系统列出的“未知错误”不代表这些库有问题：系统对齐检查（`NativeLibraryHelper.checkApkAlignment`，Android 17 真机实测）把 APK 内当前 ABI 的每个库都加入列表，已对齐库的错误码为 0，界面没有对应文案便显示为“未知错误”，真正触发对话框的是标为“LOAD 区段未对齐”的库。`adb shell dumpsys package <包名>` 的 `pageSizeCompat` 为 0 表示未进入兼容模式；对话框窗口由系统进程持有，重装应用后需先关闭旧对话框再复测。

## 富内容 WebView

富内容池及所有派生客户端统一应用协议、地址与导航策略。打包的 KaTeX、Mermaid、ECharts、知识图谱模板通过 `https://appassets.androidplatform.net/assets/` 受控读取；其它应用资产、路径穿越和缺失文件直接拒绝。复用现有 OkHttp，无新增 AndroidX WebKit 依赖。

初始 HTML 作为内存快照注册到随机 HTTPS 文档 URL，仅该完整 URL 的主框架 GET 请求可读取；更换内容后旧 URL 失效。元数据与正文使用单个不可变快照发布到 WebView 请求线程，ECharts 在首次加载前安装客户端。内嵌图片和字体仅作子资源解析，限制 4 MiB；HTML、脚本和顶层 `data:` 请求拒绝。公网单资源限制 16 MiB，重定向最多六次请求。

公网 HTTPS 子资源经独立、无 Cookie/认证的 OkHttp 客户端读取；系统解析返回的全部地址均须为公网，实际连接使用该解析结果。自动代理和自动重定向关闭，每次跳转重新判定协议、地址与 DNS，拒绝 HTTPS 降级与转入本地资源。默认 WebView 网络直连关闭。阻止文件、内容提供器与非预期协议，关闭文件 URL 跨域访问、DOM 存储和地理定位；不注册原生 JS 桥接。

页面顶层导航与链接直接阻止，避免不可信模型内容主动打开外部应用；PNG 导出由 Android 原生绘制与 MediaStore 完成，不依赖页面导航或文件读取。CSP 禁止子框架、对象、Worker、表单提交、媒体与 WebSocket，仅允许渲染所需的内联脚本/样式、HTTPS 脚本/样式/图片/字体，以及内嵌图片/字体数据。PlantUML 保留 `https://kroki.io/plantuml/svg/...`。

主控在专用设备分别检查：离线公式与 Mermaid/ECharts、知识图谱模板、公共 CDN HTML 与 HTTPS 重定向、HTML PNG 导出、PlantUML；尝试访问环回、局域网、链路本地、混合 DNS 与公网转内网重定向应失败，点击链接不跳转。设备运行证据与 JVM 判定测试分别记录，编译通过不能代替运行验收。

历史发行账本与已完成计划中的旧任务、路径属于当时证据；当前可执行入口以本说明、工作流和 PDEC 为准。

## 本机设备验收

Google APIs ARM64 模拟器使用 `arm64-v8a`，API 31 执行 `minimum`，API 35 执行 `full`。两个范围均运行原有核心/恢复/布局合同，以及 SAF 实际授权往返、隔离 QuickJS 全部 8 例、富内容 WebView 全部 7 例，以及内置执行、自选工作区目录读写与 git、工作区选择器（共享卷用例在未授予所有文件访问时跳过）和 Git 只读检查。`full` 另运行侧载真实路径、v1/v2 及独立 v18→v23 迁移、旧库提升、通知/备份恢复/导入等合同。富内容用例只使用打包的 KaTeX、Mermaid 与 ECharts，读取 DOM/JS 结果，不依赖外网或像素比较。

在仓库根完成静态检查与 ARM64 测试包构建：

```bash
(cd native-ui && ./gradlew --no-daemon :app:testSideloadDebugUnitTest :app:testPlayDebugUnitTest :app:lintSideloadDebug)
(cd native-ui && ./gradlew --no-daemon -Pinfinity.deviceE2e=true -Pinfinity.deviceE2eAbi=arm64-v8a :app:assembleSideloadDeviceTest :app:assembleSideloadDeviceTestAndroidTest :mainactivity-e2e:assembleDeviceTest)
bash -n scripts/ci/android-device-core-e2e.sh
python3 scripts/ci/check-text-wrapping.py
python3 scripts/ci/test-ssh-device-session.py
```

在单独终端从仓库根启动宿主 SSH 服务，保持前台运行：

```bash
cd native-ui
./gradlew --no-daemon :app:runSshTestServer --args='--port 2222 --echo-port 2223'
```

`--root <空目录>` 可指定 SFTP 根；省略时自动创建临时目录，服务退出时回收。指定目录必须为空，退出后保留供检查。服务只监听宿主 `127.0.0.1:2222` 与 `127.0.0.1:2223`，随机主机密钥只在内存，一次性密码和指纹只写入 `native-ui/app/build/ssh-test-server/session.json`（目录 0700、文件 0600）。MINA 使用独立宿主 Java 类路径，不进入任何 APK。不要打印会话文件或开启命令跟踪；服务退出会删除会话文件，启动新服务后必须使用新文件。

服务提供固定合成 exec 命令：`device-exec` 返回 stdout/stderr 与退出码 7，`device-sleep` 用于超时，`device-active` 返回正在休眠的命令数，`device-secret` 用于脱敏验证；PTY shell 输出 `pty-ready:<TERM>`，按行回显 `echo:<输入>`，`exit` 关闭。服务不执行宿主 shell。SFTP 限定在临时根，direct-tcpip 仅允许连接宿主 `127.0.0.1` 的回显端口，禁用远端监听、Agent 与 X11 转发。

宿主客户端自测在另一终端从仓库根运行 `python3 scripts/ci/selftest-ssh-server.py`，通过系统自带 `ssh`/`sftp` 验证 exec、SFTP、PTY 与转发。密码经 0700 临时 askpass 脚本读取会话文件，只交给客户端，不进入命令行、日志或报告。服务的前台输入关闭也会触发回收；受限终端中还可运行 `python3 scripts/ci/ssh-device-session.py stop --session native-ui/app/build/ssh-test-server/session.json`，删除当前会话租约后服务自动关闭端口并退出。

在另一终端从仓库根设置两台已获授权的专用模拟器序列号，分别串行运行：

```bash
adb devices -l
export INFINITY_SSH_TEST_SESSION="$PWD/native-ui/app/build/ssh-test-server/session.json"
export INFINITY_DEVICE_E2E_ABI=arm64-v8a

export ANDROID_SERIAL='<API31 实际序列号>'
INFINITY_DEVICE_E2E_SCOPE=minimum bash scripts/ci/android-device-core-e2e.sh

export ANDROID_SERIAL='<API35 实际序列号>'
INFINITY_DEVICE_E2E_SCOPE=full bash scripts/ci/android-device-core-e2e.sh
```

设备脚本沿用 `ANDROID_SERIAL`，会构建、安装测试包并清空测试应用数据。SSH 只在 `full` 且设置了会话文件时运行，否则明确打印“跳过 SSH 设备用例”。脚本通过 `adb reverse --no-rebind tcp:2222 tcp:2222` 将设备 `127.0.0.1` 接到宿主回环；按会话中的实际端口建立，成功或失败退出均移除自身规则，已有同端口规则不会被覆盖。没有采用 `10.0.2.2` 路线；ADB 连通性及 API 31/35 WebView、PTY、前台通知的实际结果必须由主控运行验证。

脚本在 Python 进程内读取会话，经 `adb shell` 标准输入传入 `-e sshPort`、`sshEchoPort`、`sshFingerprint`、`sshPassword` 与 `sshUsername`；密码不进入宿主命令行参数。instrumentation、logcat、dumpsys 和窗口 XML 在输出或落盘前脱敏。手动直接运行 `SshDeviceTest` 且缺少必要参数会以 assumption 明确跳过；脚本配置了 SSH 后，任何跳过、空套件、失败或超时都不能判定通过。

默认产物分别写入 `artifacts/android-device-api-31-arm64-v8a/` 与 `artifacts/android-device-api-35-arm64-v8a/`，含逐阶段 instrumentation 输出、矩阵、退出码、脱敏日志与截图；SSH 阶段输出为 `ssh-runtime.txt`。可用 `ANDROID_E2E_ARTIFACT_DIR` 设置独立目录，`ANDROID_E2E_TIMEOUT_SECONDS` 调整每阶段超时（默认 120 秒；工作区文件测试套件默认 240 秒）。两台验收结束后，在服务终端按 Ctrl-C 关闭宿主夹具。
