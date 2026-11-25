# Infinity Deck 开发者/Agent 协同规范 (AGENTS.md)

本文件遵循 [AGENTS.md 开放标准](https://agents.md/)，为所有参与 Infinity Deck（曾用名 Infinity Workbench、Nexara）开发的 AI Agent 和人类开发者提供核心开发规范。

## 1. 核心规则引用
本项目严格遵循模型全局开发规则，包含：
- **语言规范**：所有解释、规划、文档、注释必须使用简体中文。技术转译时，禁止直接搬运大段英文日志。
- **闭环工作流**：在声称任务完成前，必须通过 **DIA 检查**。
- **文档治理 (DIA 机制)**：每次修改必须评估对核心及按需文档的影响。核心文档包含 `CHANGELOG.md`、`README.md`、`.agent/handover.md`。
- **全站 UI 门禁**：Infinity Deck 界面与评审原型禁止用裸文字替代按钮组件。复用项目按钮样式承载交互。带可见填充、描边或明确控件边界的按钮可只显示文字，图标按动作语义和组件规范使用。独立图标按钮须有可访问名称。原型和实际界面均验收。
- **全站动效与布局门禁**：交互中出现或消失的额外控件必须有完整的进出过渡，并遵循系统减少动态效果设置。谨慎使用会改变已渲染布局高度的控件，优先复用现有区域。确需改变高度时，核对过渡、滚动锚点和操作可达性。

## 2. 项目特定架构与文档注册表
`.agent/`（交接、计划、注册表）与 `.pdec`（开发执行契约）是本地治理目录，不进入本仓库：`.agent/` 本身是私有仓库 `Promenar/Infinity-Workbench-governance` 的工作区，`.pdec` 是指向 `.agent/pdec` 的符号链接。新机器上在仓库根执行 `git clone https://github.com/Promenar/Infinity-Workbench-governance.git .agent && ln -s .agent/pdec .pdec`；写入 handover 后在 `.agent/` 内单独提交推送。

本项目的完整文档治理体系和文档注册表已在以下文件中声明：
- **[文档注册表]** — [.agent/registry.md](.agent/registry.md)
- **[跨会话交接说明]** — [.agent/handover.md](.agent/handover.md)

## 3. 技术栈特定红线
本项目是一个包含 Android 原生 Compose UI (`native-ui`) 的多模块项目。
- 遵循 **Jetpack Compose 性能红线** 与 **滚动容器嵌套红线**：
  - 避免在第三方组件回调中叠加 `horizontalScroll()` / `verticalScroll()` 导致崩溃；
  - 网络/IO 必须在 `Dispatchers.IO`。
- **单元测试门禁**：对核心业务逻辑（数据转换、状态流转、ViewModel 等）的更改，必须同步编写并运行通过对应的单元测试。
- **Android/Gradle 编译清理**：当出现诡异的编译问题时，务必首先执行 `./gradlew clean`。

## 4. 项目排障沉淀

当前 Android 任务显式指定 `distribution` 风味；日常默认仅验证侧载（`sideload`）渠道，全量门禁日常使用标准命令包括 `native-ui/` 下的 `:app:testSideloadDebugUnitTest`、`:app:lintSideloadDebug`、`:app:assembleSideloadDebug`、多进程并行截图 `python3 scripts/ci/validate-screenshots-parallel.py`（等价全量 `:app:validateSideloadDebugScreenshotTest`），以及仓库根下的 `bash scripts/ci/android-static-checks.sh`、`python3 scripts/ci/check-native-page-alignment.py`；仅在用户显式要求、改动触及 Play 专属边界或准备 Play 发行时补跑 `:app:testPlayDebugUnitTest`、`:app:assemblePlayDebug` 与 `python3 scripts/ci/check-distribution-manifests.py`。两个渠道的 APK、mapping 与设备构建入口见 [docs/distribution-builds.md](docs/distribution-builds.md)。

验证与出包门禁按变更范围分为三档执行：

- **L1 局部快门禁（含日常签名包真机验收）**：适用于边界清晰、无跨模块交叉调用的局部 UI 调整、文案/样式微调或独立小修复（包含通过 `scripts/release/build-signed-local.py` 构建签名侧载 Release 包装到真机验收）。执行对应静态检查（`check-text-wrapping.py` 或 `bash scripts/ci/android-static-checks.sh`）、Compose Preview 对照、受影响组件的定向截图校验（`python3 scripts/ci/validate-screenshots-parallel.py --tests '<过滤>'` 或 `:app:validateSideloadDebugScreenshotTest --tests <过滤>`）、受影响类的定向 `:app:testSideloadDebugUnitTest --tests <过滤>`（纯视觉无逻辑改动仅编译核验 `:app:compileSideloadDebugKotlin`）以及签名包真机实测；可跳过全量单元测试、全量截图基线、全量 `:app:lintSideloadDebug` 与 `play` 渠道构建。
- **L2 高风险与共享边界回归**：适用于触及全站共享 UI 原语或全局排版（如 `Type.kt`、`InfinitySettingsItem`、`InfinityDialog`、`MarkdownText`）、Room Schema/迁移、备份恢复、安全/权限/审批、工具执行与协议路由、构建/清单/R8 混淆规则或 `play`/`sideload` 渠道分流的改动。默认只跑 `sideload` 渠道完整回归（分阶段执行全量 `:app:testSideloadDebugUnitTest`、多进程全量 `python3 scripts/ci/validate-screenshots-parallel.py` 与 `:app:lintSideloadDebug`）；**除非用户显式指定或本轮改动触及 Play 专属代码/测试/清单/渠道分流边界，禁止顺带运行 Play 单元测试与构建**。
- **L3 对外公开发版门禁（仅限 GitHub Release）**：仅适用于对外推送版本标签触发 `.github/workflows/release.yml` 发布 GitHub Release（不作用于日常构签名包真机验收）。发版目标须具备本机全量门禁与签名侧载 Release 真机验收通过证据；自最近一次全量门禁通过后若源码、资源与构建配置均无实质变动（仅同步版本号、`CHANGELOG.md` 或文档），直接复用既有全量证据并跳过重复执行，仅核验静态检查与发行元数据。

`release.yml` 保留静态契约、版本输入校验、API 31 / `minimum` deviceTest、API 36 minifiedTest 黑盒及侧载 API 31/36 的签名 APK 冷安装；签名构建只依赖静态检查和版本输入校验，只构建侧载 Release 并检查 16KB 页对齐。GitHub Release 只发布侧载 APK；版本名无后缀或为 `-beta` 时设为 Latest，其它后缀标为预发布。发布等待冷安装与两类设备作业全部通过。API 35/36 的 `full` deviceTest 由仅手动触发的 `device-regression.yml` 按需执行。

- **Markdown 渲染排障分层**：遇到“Markdown 标记存在但排版堆成一坨”时，必须先区分两类根因：
  - 文本中已有换行，但渲染库按 CommonMark 软换行规则吞掉换行；
  - 上游模型原始输出已经缺少真实换行，只剩标题、列表、表格等 Markdown 标记压在同一行。
- 对第一类问题优先检查渲染库配置（例如 `eolAsNewLine`、段落 padding）；对第二类问题优先检查流式/协议层是否丢换行，再考虑在 `MarkdownText` 预处理层做窄范围边界修复，且必须保护行内代码、链接和缩进代码块。

### 构建、测试加速与硬件并行纪律

本节为长期固化纪律，所有会话与压缩续跑均须严格遵守：

- **默认仅侧载（禁止擅自跑 Play）**：当前未在 Google Play 正式发版，日常开发、L1/L2 回归与真机签名出包一律只跑 `sideload` 渠道；除非用户当轮显式要求，或改动触及 `src/play/`、`src/testPlay/`、双渠道清单/权限分流逻辑或准备正式 Play 上架，否则禁止执行 `:app:testPlay*`、`:app:assemblePlay*` 或 `build-signed-local.py --play`。
- **常开构建缓存与配置缓存**：`native-ui/gradle.properties` 已固定开启 `org.gradle.caching=true`、`org.gradle.parallel=true`、`org.gradle.configuration-cache=true` 与 `org.gradle.configuration-cache.parallel=true`；严禁在命令中添加 `--no-daemon`、`--no-parallel`、`--no-build-cache` 或 `--no-configuration-cache`（排查缓存损坏除外）。新增或修改 `build.gradle.kts` 任务与 Provider 时，禁止在 `.map {}` / `provider {}` 中捕获顶层脚本属性、`project` 或 `gradle` 对象，确保配置缓存持续命中。
- **多核多进程并行执行测试与截图**：
  - 全量截图验证在多核主机**禁止**直接跑单进程串行的 `./gradlew :app:validateSideloadDebugScreenshotTest`；统一执行 `python3 scripts/ci/validate-screenshots-parallel.py`（自动按本机 CPU 与内存启动多进程 Layoutlib JVM 并行渲染，并复用配置缓存、增量编译与完整 XML 校验；支持 `--tests '<过滤>'` 定向校验、`--shards <N>` 调并发度与 `--update` 并行更新参考图）。
  - 完整 Sideload JVM 单测已在 `build.gradle.kts` 按多核并行（本机开发机默认上限 `12` 进程、CI 上限 `8` 进程，单进程 `1g` 堆，可用 `-Pinfinity.unitTestForks=<n>` 调节）。
- **分阶段跑重型 Gradle 任务（禁止一锅烩并行塞爆守护进程）**：严禁在单条 `./gradlew` 命令中同时拼接全量单测 + 全量截图 + Lint + 多变体 APK 打包（易触发 Gradle 守护进程 OOM 生成大 `.hprof` 并在 Worker 屏障处挂死）。迭代期使用 `--tests` 定向快验；全量回归按阶段串行切分执行（静态检查 → 单测 → 并行截图脚本 → 必要时 Lint / 打包），跨阶段复用配置缓存与常驻守护进程，切换开销 `<1s`。
- **延迟升版与复用增量产物**：`versionCode`/`versionName` 变更会触发 `BuildConfig` 重编译，务必在全部测试、截图与 Lint 通过后，于最终提交与出包前一次性升版；签名包用 `python3 scripts/release/build-signed-local.py --sha <提交>` 在仓库同级长期发行工作树 `Nexara-release` 构建，只装真机时只构建侧载渠道（不加 `--play`）。
- **其余日常效率守则**：截图参考先在 Compose Preview 确认窄屏、大字号与中英文布局再更新基线；改变既有行为前先检索并同步测试旧预期；纯 UI 且已有单测覆盖时跳过 `deviceTest` 变体直接真机验收；清理开发缓存只修剪过期条目，不整体删除活跃 `~/.gradle/caches`。

## 5. 交付 Git 闭环

- 用户已授权：每轮任务完成验证、准备向用户交付前，必须将本轮应纳管成果提交并推送当前工作分支，不再等待用户逐次提醒。
- 提交前必须复核暂存清单与 diff，排除密钥、签名材料、`secure_env/`、`artifacts/`、构建产物及任何 `.infinity-*`、`.nexara-*` 测试临时目录（如 `.infinity-workspace-*`、`.nexara-backup-test*`）；不得借机纳入无关或无法确认来源的修改。
- commit/push 授权不包含 tag、PR、GitHub Release、强推或历史改写；这些操作仍需用户明确授权。用户当轮明确要求暂停提交或不推送时，以最新指令为准。

## 6. 版本号规则

- 每次构建签名正式版装到真机，或对外发布安装包，都先把 `native-ui/app/build.gradle.kts` 的 `versionCode` 加 1。debug 构建、只跑门禁或重装同一提交的安装包不升版本。
- `versionName` 采用 `主.次.补丁-beta`：
  - 包含一组用户可见的新功能时，次版本号加 1，补丁号归零；
  - 只含修复时，补丁号加 1；
  - `-beta` 后缀保留到用户决定转为正式版。
- 升版本与出包在同一提交完成：
  - 把 `CHANGELOG.md` 顶部 `[Unreleased]` 的内容切为 `## [版本名] - YYYY-MM-DD` 小节，并新开空的 `[Unreleased]`；
  - 同步 README 与 `.agent/registry.md` 中的当前版本描述。
- 交付报告写明版本名、versionCode 与安装包 SHA-256。
- 对外发布：版本提交推送 `main` 后，经用户授权推送注释标签 `v<versionName>`，由 `.github/workflows/release.yml` 构建、验签并发布 GitHub Release；发布说明取自 `CHANGELOG.md` 对应版本小节。流程见 [docs/distribution-builds.md](docs/distribution-builds.md)。

---
*规范更新于 2026-11-25。请每次开启新会话时首先阅读本文件，并检查 `.agent/handover.md` 的 Next Steps！*
