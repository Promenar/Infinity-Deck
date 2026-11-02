# 开发构建与发行验证

> 所属：[使用指南](README.md)。仓库首页只介绍概览，本文给出完整说明与边界。

当前源码候选为 `v0.5.1-beta`（versionCode `16`），主要变化见 [CHANGELOG](../../CHANGELOG.md) 的 0.5.1-beta 至 0.4.0-beta 小节。资料页（原“知识库”）使用集中式任务状态微件、平铺目录下钻和会话工作区隔离，页面采用统一操作菜单。上一候选 `v0.2.2-beta` 的源码检查、截图、设备和签名制品证据见[发行验证记录](../release/v0.2.2-beta-validation.md)；历史审计结论见[发行需求验收总账](../audit/20260922-requirements-acceptance.md)，不能替代当前候选验收。公开 tag 与 Release 需要单独授权。

Android 开发调试与设备验收使用本机启动的专用 AVD；执行位置和设备选择边界见 [PDEC 契约](../../.pdec/README.md)。

移动端承接桌面项目与创作任务的验收入口见[移动工作流能力测试方案](../testing/mobile-workflow-capability-checklist.md)：按模型与 Agent、技能、文件、执行、网络、服务、检索、生命周期分层覆盖基础能力，端到端任务按轻、中、重负载分级判定，并提供精简回归组与分批执行顺序。清单定义测试目标，通过状态以绑定具体 APK 和环境的执行记录为准。

## 构建命令

```bash
cd native-ui
./gradlew :app:assembleSideloadDebug :app:assemblePlayDebug
./gradlew :app:testSideloadDebugUnitTest :app:testPlayDebugUnitTest :app:lintSideloadDebug
./gradlew :app:validateSideloadDebugScreenshotTest
python3 ../scripts/ci/check-distribution-manifests.py
python3 ../scripts/ci/check-native-page-alignment.py
```

发行前须在本机跑完全量门禁；发行流水线只验证本机不覆盖的环境。完成上述单测、lint、两个渠道 Debug 构建、侧载截图与两个 Python 检查后，须在真机安装本机签名的侧载 Release 包验收。

`release.yml` 保留静态契约与版本输入校验，设备覆盖为 API 31 / `minimum` deviceTest 和 API 36 Release 等价 R8 minifiedTest 黑盒。签名构建在静态检查及版本输入校验通过后与设备作业并行，依次用两次 Gradle 调用分别构建两个渠道的 Release（合并为一次调用会使 Kotlin 编译超出托管 runner 内存），再执行两个 Python 检查的 `--build-type release` 模式。实际签名 APK 冷安装覆盖侧载 / API 31、侧载 / API 36、Play / API 36；版本输入校验、冷安装与两类设备作业全部通过才可发布。Linux 截图验证保留独立工作流入口。

设备相关功能可按需手动触发 `device-regression.yml`，运行 API 35/36 的 `full` deviceTest 完整业务回归；它不阻断发行，沿用托管环境的 Tailscale 控制面登录与 SSH 会话覆盖边界，详见[发行渠道构建说明](../distribution-builds.md)。合并到 `main` 后可用 `gh workflow run release.yml --ref main` 验证候选；该入口构建并冷安装签名产物，不发布 Release。

首次构建会从 python.org 下载官方 CPython Android 包（每个 ABI 约 23 MB）并按 SHA-256 校验，之后使用 Gradle 缓存。构建期需要 PATH 中有 `python3.14`，用它预编译打包 Python 的字节码（也可用 `-Pinfinity.hostPython=<路径>` 指定）。离线方式与打包结构见[打包 Python](../distribution-builds.md#打包-python)。Debug 构建不需要发行签名变量。Release 构建必须通过进程环境提供 `INFINITY_KEYSTORE_PATH`、`INFINITY_STORE_PASSWORD`、`INFINITY_KEY_ALIAS` 和 `INFINITY_KEY_PASSWORD`；仓库不保存 keystore 或密码。

内置 Tailscale 由 `native-ui/app/tailnet/` 的 Go 程序编译为 `libnxtailnet.so`：构建需要 PATH 中有 Go 1.21 及以上（经 `GOTOOLCHAIN` 自动切换到固定的 Go 1.27.1，依赖由 `go.sum` 锁定；首次构建需要能访问 Go 模块代理，或预先填好模块缓存后设 `GOPROXY=off`），可用 `-Pinfinity.goExecutable=<路径>` 指定 go。临时没有 Go 时可加 `-Pinfinity.tailnet=false` 跳过，此时设置页提示安装包未包含内置 Tailscale，发行校验会拒绝这样的 APK。详见[内置 Tailscale](../distribution-builds.md#内置-tailscale)。

`distribution` 维度包含 `sideload` 与 `play`，二者共享包名，沿用各构建类型的后缀。仅侧载清单声明所有文件访问权限；`DistributionCapabilities` 提供渠道和能力开关，尚未提供该权限对应的文件管理功能。设置中的“导出诊断”首行包含渠道及版本。Debug APK 位于 `app/build/outputs/apk/<渠道>/debug/`；签名构建、设备测试及完整任务/产物表见[发行渠道构建说明](../distribution-builds.md)。

聊天富内容使用受控 HTTPS 本地资源与公网 HTTPS 子资源；拒绝文件、内容提供器、内网地址与页面跳转。KaTeX、Mermaid、ECharts 和知识图谱模板保留本地加载；预览中的链接直接阻止，不会自动打开外部应用。安全策略及设备验收入口见[构建与渲染说明](../distribution-builds.md#富内容-webview)。

应用图标为[点阵无穷矢量母版](../../assets/branding/infinity-dots-master.svg)：深蓝径向渐变背景上，扭转的点阵丝带组成无穷符号，亮点用径向渐变光晕模拟发光，日夜一致；另有单色主题图层与实线通知图标。母版由 `scripts/branding/infinity_icon.py` 按参数生成并提交，各变体通过 Gradle Variant API 自动转成 VectorDrawable，IDE、ADE、命令行和 CI 的正常构建均使用同一母版，构建时无需 Python 或图像工具。制作规格、覆盖防护和验证方法见[图标契约](../../assets/branding/design-spec.md)。

### Metro CLI/TUI

Infinity Workbench 没有面向最终用户、与 Android UI 等价的 CLI。仓库中的 `scripts/infinity-metro-tui.js` 是 **Debug 构建专用的开发者日志终端**，用于解析 `adb logcat` 中的脱敏 Metro 事件，不替代应用功能：

```bash
node scripts/infinity-metro-tui.js --help
node scripts/infinity-metro-tui.js --serial emulator-5554
adb logcat -s INFINITY_METRO | node scripts/infinity-metro-tui.js --stdin --no-color
```

该 TUI 支持中文帮助、设备/Tag 选择、标准输入、TTY/非 TTY 输出、明确退出码和缺失字段容错。Release 会关闭并剥离 Metro/调试日志入口；发行版仅保留应用内有界、脱敏且由用户主动分享的诊断事件文件。

## 技术栈

| 层级 | 选型 |
|---|---|
| 语言/UI | Kotlin、Jetpack Compose、Material 3 |
| 架构 | MVVM、Repository、Flow/Coroutines |
| 数据 | Room、SQLite/FTS5、设备文件系统 |
| 网络 | OkHttp、Ktor、SSE |
| 安全 | Android Keystore、AES-256-GCM、PBKDF2-HMAC-SHA256 |
| 导航 | Compose Navigation |
| 构建/发行 | Gradle、R8、GitHub Actions、GitHub Release |

架构快速参考见 [docs/ARCHITECTURE.md](../ARCHITECTURE.md)，当前发行说明见 [docs/release/v0.2.2-beta.md](../release/v0.2.2-beta.md)，发行验证状态见 [docs/release/v0.2.2-beta-validation.md](../release/v0.2.2-beta-validation.md)。
