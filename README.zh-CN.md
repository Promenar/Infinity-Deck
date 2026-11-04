<p align="center">
  <img src="assets/branding/infinity-glow-icon.png" alt="Infinity Workbench 发光点阵无穷图标" width="112" height="112">
</p>

<h1 align="center">Infinity Workbench</h1>

<p align="center">
  口袋里的完整工作台：BYOK 原生 Android 客户端 · 真 Linux · Git 与 SSH · Tailscale 私网 · 折叠屏优化。
</p>

<p align="center">
  <a href="README.md">English</a> · <b>简体中文</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/license-GPL--3.0-blue.svg" alt="许可证 GPL-3.0">
  <img src="https://img.shields.io/badge/platform-Android%2012%2B-green.svg" alt="平台 Android 12+">
  <img src="https://img.shields.io/badge/Kotlin-2.2-7F52FF.svg" alt="Kotlin 2.2">
  <img src="https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4.svg" alt="Jetpack Compose Material 3">
  <img src="https://img.shields.io/badge/version-0.18.0--beta-6366F1.svg" alt="版本 0.18.0-beta">
</p>

https://github.com/user-attachments/assets/c48800d9-34a5-4397-97f3-04e7ac21449d

<p align="center">
  <img src="assets/screenshots/zh/hero.webp" alt="展开：会话旁边的 Git 面板列出提交记录；合上：外屏继续查看 502 排障报告" width="860">
</p>

## 口袋里的完整工作台

Infinity Workbench 是用 Kotlin 与 Jetpack Compose 写成的原生 Android 客户端，模型自带密钥接入：OpenAI（Chat Completions 与 Responses）、Anthropic、Google Gemini、Vertex AI、DeepSeek、GLM、Kimi，或任意 OpenAI 兼容接口，会话中途也能切换模型。折叠屏展开后，首页分为导航、会话列表与会话三列，会话旁的浮动检视器随时打开任务、文件、Git、产物与浏览器；合上手机，外屏以单栏继续同一个会话。切到后台或锁屏，生成照常进行；打开触摸锁，屏幕全黑、只显示运行状态与进度并屏蔽触摸，手机可以直接揣进口袋。

<table>
  <tr>
    <td width="66%"><img src="assets/screenshots/zh/fold-inspector.webp" alt="展开：会话旁边的计划检视器列出已完成步骤、子代理与后台任务"></td>
    <td width="34%"><img src="assets/screenshots/zh/outer-chat.webp" alt="外屏：同一会话里的 502 排障报告"></td>
  </tr>
</table>

## 真 Linux，随任务取用（侧载版）

侧载版一键安装 Alpine Linux：proot 运行，无需 root，也不依赖 Termux。`apk add` 能装任意软件包，安装时默认勾选 git、ripgrep、Python、Node.js 与 FFmpeg。Agent 执行命令的系统就是你在终端里打开的那一套，路径完全一致。不装 Linux 也有两个渠道都提供的隔离运行时：QuickJS 与沙箱 Python。文件放在三类工作区之一：会话专用空间、内置文件系统，以及侧载版的设备文件系统。

<table>
  <tr>
    <td width="66%"><img src="assets/screenshots/zh/linux-terminal.webp" alt="内置终端运行 fastfetch：Alpine Linux v3.24 aarch64，Oryon CPU"></td>
    <td width="34%"><img src="assets/screenshots/zh/outer-terminal.webp" alt="外屏终端运行 fastfetch"></td>
  </tr>
</table>

## 随身的版本管理与运维（Git · SSH）

两个渠道都内置 git，侧载版的 Linux 里另有完整的 git 与 gh。Git 面板显示分支、相对上游的领先/落后、改动差异与最近提交；顺带的工作可以提成分支会话，在独立的 worktree 里进行。HTTPS 的 Git 凭据由应用的本机代理按命令注入，不写进仓库配置，也不进入模型上下文。

SSH 同样随身：生成 Ed25519 密钥，首次连接确认指纹，之后 Agent 只用主机别名执行命令、读写 SFTP，传输单文件最大 4 GiB 并在两端做 SHA-256 校验。你也可以打开真实的 PTY 终端或本地转发亲自接手。Agent 看不到密码和密钥；在“命令需批准”档下，每条远程命令都要等你批准。

<table>
  <tr>
    <td width="50%"><img src="assets/screenshots/zh/ade-git.webp" alt="Git 面板列出分支，以及各 worktree 由哪个会话使用"></td>
    <td width="50%"><img src="assets/screenshots/zh/ade-branch.webp" alt="工作方案提议新建 worktree 的分支会话，可选在此执行或派发到分支"></td>
  </tr>
  <tr>
    <td align="center">分支与 worktree 对应到会话</td>
    <td align="center">顺带工作提成分支会话</td>
  </tr>
  <tr>
    <td width="50%"><img src="assets/screenshots/zh/ops-diagnosis.webp" alt="web-01 上的远程命令排队等待批准：可逐条批准或拒绝，共 3 项"></td>
    <td width="50%"><img src="assets/screenshots/zh/ops-detail.webp" alt="修复报告：config.toml 把数据库指向从未创建的目录，已恢复路径并重启 api"></td>
  </tr>
  <tr>
    <td align="center">每条远程命令由你批准</td>
    <td align="center">根因、修复与验证写进一份报告</td>
  </tr>
</table>

## 接入私有网络与私有算力（Tailscale）

应用在自己的进程里运行一个 Tailscale 节点：不占用系统 VPN，可以和其它 VPN 同时使用，也支持自建的 Headscale。SSH 主机、命令、MCP 服务器与模型提供商都能直接访问 tailnet IP 和 MagicDNS 名称。添加提供商时，表单会识别接口地址属于 tailnet，还可以从 tailnet 设备里选一台网关，用家里或公司内网的模型网关驱动对话、向量化与重排。节点只在应用运行期间在线，退出即下线。

## 像在电脑前一样干活的 Agent

提一个需求，Agent 先列计划再动手。它可以在后台派出子代理（全应用最多 6 个并行），把顺带工作提成分支会话，把慢命令转成后台任务：默认最长 2 小时、上限 24 小时，结束后可唤醒会话继续。它也能创建单次、间隔或 cron 定时任务。最多 4 个会话同时生成；生成期间你可以继续写下一条排队，或者让它插队；需要你拍板时，Agent 会弹出提问卡。

内置的 Agent 浏览器可以在旁观看，也可以随时接手再交回；密码、支付卡与验证码字段只能由你填写，Agent 看不到内容。技能分内置、用户级与项目级三层，用 `/技能名` 直接调用，也能接入 MCP 服务器。Agent 还能管理智能体本身，比如新建助手或改写提示词：每次写入都要你批准并显示差异，提示词保留最近 20 个版本。

<table>
  <tr>
    <td width="50%"><img src="assets/screenshots/zh/ade-subagents.webp" alt="两个子代理并行梳理代码与测试缺口，主会话等待汇总"></td>
    <td width="50%"><img src="assets/screenshots/zh/ade-background.webp" alt="21 个测试通过后，400 万行的完整测试套件转入后台任务，对话继续进行"></td>
  </tr>
  <tr>
    <td align="center">子代理并行梳理代码</td>
    <td align="center">慢命令转为后台任务</td>
  </tr>
</table>

## 知识进来，成果出去

- **知识进来**：资料库支持向量与全文混合检索、重排与引用，并可建知识图谱；对话记忆按时间线检索，长期记忆跨会话记住偏好与项目事实。
- **成果出去**：Agent 可以做画布页面、可点击的多屏原型（带演示模式），交付的报告与文件登记为带版本的产物。
- **富内容**：Markdown、LaTeX、Mermaid、ECharts，会话内直接显示图片、视频、音频，也能调用模型生图，并可在画板上圈注、涂抹选区或手绘草图作为生图入参。
- **导出与用量**：会话可导出为 HTML、A4 分页 PDF 或长图 PNG；用量页给出费用估算与输入缓存命中率。

## 一切由你掌控

- **执行由你批准**：权限分“命令需批准”与“免批准”两档；“智能体可达范围”面板列出 Agent 此刻能碰到的目录、执行方与网络。
- **秘密不进对话**：密码与令牌走专用的保密输入/输出卡，显示原文时禁止截屏录屏；API Key 由 Android Keystore 保护。
- **本地优先**：会话、文档与配置存在本机，模型请求直连你配置的服务商，不经过我们的服务器。
- **备份可加密**：分层备份到本地文件或 WebDAV，可用 AES-256-GCM 加密。
- **出错不锁死**：启动自检某一步失败时，只隔离受影响的工作区、会话或子系统，其余照常使用。
- **开源**：以 GPL-3.0 发布，代码全部公开。

## 开始使用

**运行要求**：Android 12（API 31）及以上。内置 Linux 需要侧载版和 arm64-v8a 设备，基础系统约占 64 MB，默认加装 git、ripgrep、Python、Node.js 与 FFmpeg 共约 470 MB，可按需取消。打包的 Python 同样只在 arm64-v8a 设备上提供。

**两个渠道**：`sideload` 包含内置 Linux 与设备文件访问，`play` 不含这两项；Play 版的后台任务只在应用位于前台时保证运行。两者包名均为 `com.promenar.infinity`，可与旧版 Nexara 并存。

**安装**：从 [GitHub Releases](https://github.com/Promenar/Infinity-Workbench/releases) 下载 APK 与对应 `.sha256` 文件，核对 SHA-256 后安装。公开发布可能落后于源码；不要安装 CI 临时制品、Debug APK 或来源不明的重打包版本。

当前源码版本为 `v0.25.0-beta`（versionCode `67`），变更见[更新日志](CHANGELOG.md)。

## 从源码构建

需要 JDK 17 及以上、Android SDK、`PATH` 中的 `python3.14`（预编译打包 Python）和 Go 1.21 及以上（编译 Tailscale 库）。

```bash
cd native-ui
./gradlew :app:assembleSideloadDebug :app:assemblePlayDebug
./gradlew :app:testSideloadDebugUnitTest :app:lintSideloadDebug
```

Debug APK 输出在 `native-ui/app/build/outputs/apk/<渠道>/debug/`。离线构建、签名与发行检查见[开发构建与发行验证](docs/guide/development.md)和[发行渠道构建](docs/distribution-builds.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [功能详解](docs/guide/features.md) | 对话与渲染、上下文管理、RAG、Agent 工具、子代理、浏览器、后台与定时任务、导出与备份 |
| [密钥与隐私](docs/guide/security-privacy.md) | 密钥存储、备份加密、SSH/Git/Tailscale 凭据、网络安全、诊断事件 |
| [执行环境](docs/guide/execution-environments.md) | 内置 Linux、Termux、隔离脚本运行时、SSH |
| [工作区与外部文件夹](docs/guide/workspaces.md) | 工作区类型、Git、Agent 可达范围、文件夹挂载 |
| [安装、升级与数据迁移](docs/guide/install-and-upgrade.md) | 运行要求、数据迁移、APK 验真 |
| [开发构建与发行验证](docs/guide/development.md) | 构建命令、打包 Python 与 Tailscale、富内容安全策略、图标、技术栈 |

另见[架构说明](docs/ARCHITECTURE.md)与[使用指南索引](docs/guide/README.md)。

## 许可证

Infinity Workbench 以 [GNU General Public License v3.0](LICENSE) 发布。设置列表、底部导航与外观页包含对同为 GPLv3 的 [Bettbox](https://github.com/appshubcc/Bettbox) 视觉实现的 Compose 转译；智能体头像符号来自 Google Material Symbols Rounded（Apache-2.0）。来源与范围见[第三方通知](docs/legal/THIRD_PARTY_NOTICES.md)。
