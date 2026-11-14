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

当前 Android 任务显式指定 `distribution` 风味：日常使用 `:app:testSideloadDebugUnitTest`、`:app:testPlayDebugUnitTest`、`:app:lintSideloadDebug`、`:app:assembleSideloadDebug`、`:app:validateSideloadDebugScreenshotTest`；另构建 `:app:assemblePlayDebug` 并执行 `python3 scripts/ci/check-distribution-manifests.py` 与 `python3 scripts/ci/check-native-page-alignment.py`。Gradle 在 `native-ui/` 运行，Python 检查在仓库根运行。两个渠道的 APK、mapping 与设备构建入口见 [docs/distribution-builds.md](docs/distribution-builds.md)。

发行前须在本机跑完全量门禁，并安装本机签名的侧载 Release 包完成真机验收；发行流水线只验证本机不覆盖的环境。`release.yml` 保留静态契约、版本输入校验、API 31 / `minimum` deviceTest、API 36 minifiedTest 黑盒及侧载 API 31/36 的签名 APK 冷安装；签名构建只依赖静态检查和版本输入校验，只构建侧载 Release 并检查 16KB 页对齐。GitHub Release 只发布侧载 APK；版本名无后缀或为 `-beta` 时设为 Latest，其它后缀标为预发布。发布等待冷安装与两类设备作业全部通过。API 35/36 的 `full` deviceTest 由仅手动触发的 `device-regression.yml` 按需执行。

- **Markdown 渲染排障分层**：遇到“Markdown 标记存在但排版堆成一坨”时，必须先区分两类根因：
  - 文本中已有换行，但渲染库按 CommonMark 软换行规则吞掉换行；
  - 上游模型原始输出已经缺少真实换行，只剩标题、列表、表格等 Markdown 标记压在同一行。
- 对第一类问题优先检查渲染库配置（例如 `eolAsNewLine`、段落 padding）；对第二类问题优先检查流式/协议层是否丢换行，再考虑在 `MarkdownText` 预处理层做窄范围边界修复，且必须保护行内代码、链接和缩进代码块。

### 构建效率

构建与出包必须复用 Gradle 增量编译、守护进程和构建缓存，不为“干净”付出冷构建代价：

- 签名包用 `python3 scripts/release/build-signed-local.py --sha <提交>` 构建。脚本在仓库同级的长期发行工作树 `Nexara-release` 中切换到目标提交，不新建也不丢弃工作树，并且不加 `--no-daemon`、`--no-parallel`。
- 只装真机时只构建侧载渠道；对外发布或用户要求时，加 `--play` 同时构建 Play 渠道并检查发行清单。
- 截图参考先在预览里确认窄屏、大字号与中英文布局，再只运行一次 `:app:updateSideloadDebugScreenshotTest`。
- 改变既有行为前，先检索测试中的旧预期并同步修改，避免第一轮定向测试白跑。
- 改动只涉及界面、逻辑已有单测覆盖时，跳过 `deviceTest` 变体构建，直接在真机验收。
- 清理开发缓存时只修剪过期条目，不整体删除活跃的 `~/.gradle/caches`。

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
*规范更新于 2026-11-14。请每次开启新会话时首先阅读本文件，并检查 `.agent/handover.md` 的 Next Steps！*
