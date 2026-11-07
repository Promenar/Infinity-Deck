<p align="center">
  <img src="assets/branding/infinity-glow-icon.png" alt="Infinity Workbench 发光点阵无穷图标" width="112" height="112">
</p>

<h1 align="center">Infinity Workbench</h1>

<p align="center">
  <b>把终端、代码仓库、服务器和你的 AI 一起装进手机。</b>
</p>

<p align="center">
  <a href="README.md">English</a> · <b>简体中文</b>
</p>

<p align="center">
  <a href="https://github.com/Promenar/Infinity-Workbench/releases"><img src="https://img.shields.io/github/v/release/Promenar/Infinity-Workbench?include_prereleases&label=%E4%B8%8B%E8%BD%BD&color=6366F1" alt="下载最新版"></a>
  <img src="https://img.shields.io/badge/Android-12%2B-green.svg" alt="Android 12+">
  <img src="https://img.shields.io/badge/license-GPL--3.0-blue.svg" alt="许可证 GPL-3.0">
</p>

https://github.com/user-attachments/assets/c48800d9-34a5-4397-97f3-04e7ac21449d

<p align="center">
  <img src="assets/screenshots/zh/hero.webp" alt="展开：会话旁边的 Git 面板列出提交记录；合上：外屏继续查看 502 排障报告" width="860">
</p>

用你自己的 API Key，接上 OpenAI、Claude、Gemini、DeepSeek 或家里跑着的模型。它会动手：在手机里的 Linux 里跑命令，改你的代码，登上你的服务器，替你操作网页，每一步都先给你看。

折叠屏展开是一张工作台，合上在外屏接着聊。

## 它能帮你做什么

<table>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/zh/ops-diagnosis.webp" alt="web-01 上的远程命令逐条等待批准">
      <h3>在地铁上修好线上故障</h3>
      网站报 502，你让它连上服务器去查。每条命令先给你过目，点一下才执行；修完它写好报告，合上手机在外屏接着看。
    </td>
    <td width="50%" valign="top">
      <img src="assets/screenshots/zh/linux-terminal.webp" alt="内置终端里运行 fastfetch：Alpine Linux">
      <h3>手机里有一台真 Linux</h3>
      跑 Python 脚本、用 FFmpeg 转视频、<code>apk add</code> 装个工具，都在应用里完成。不用 root，也不用装 Termux。
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/zh/ade-branch.webp" alt="工作方案卡提议在新工作树里开分支会话">
      <h3>代码随身改</h3>
      主会话修 bug，补测试的活派给分支会话，各自在独立的工作树里改，互不打架。分支、差异、提交在 Git 面板里一目了然。
    </td>
    <td width="50%" valign="top">
      <img src="assets/screenshots/zh/tailnet-gateway.webp" alt="提供商表单里从 tailnet 设备中选择模型网关">
      <h3>用上家里的显卡</h3>
      家里电脑跑着模型？连上 Tailscale，手机直接拿它聊天、做检索。不开公网端口，也不占系统 VPN。
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/zh/library-ask.webp" alt="问资料：带编号引用的答案与命中列表">
      <h3>问你自己的资料</h3>
      把论文、合同或整部小说的文件夹放进来，直接提问。答案标着出处，点一下跳到原文那一段。写长篇的，还能问“第 12 章时这两个人是什么关系”。
    </td>
    <td width="50%" valign="top">
      <img src="assets/screenshots/zh/browser-agent.webp" alt="Agent 在 CI 页面上点击重跑失败的作业">
      <h3>替你跑网页</h3>
      让它去 CI 页面重跑失败的作业，你在旁边看着，随时接手。碰到登录页它会停下，密码由你自己输入，它看不到。
    </td>
  </tr>
</table>

## 你始终说了算

- **每条命令先问你。** 默认要你批准才执行；删除文件无论哪一档都要你点头。
- **密码不经过 AI。** 需要密码时弹出保密输入卡，内容直接存进凭据，不进对话，显示时禁止截屏。
- **数据留在手机上。** 对话、文档和设置都在本机，模型请求直连你配置的服务商，不经过我们的服务器。
- **备份你来管。** 存到本地文件或 WebDAV，可以加密。
- **完全开源。** GPL-3.0。

## 全部功能

| | |
|---|---|
| **模型** | 自带密钥接入 OpenAI、Anthropic、Gemini、Vertex AI、DeepSeek、GLM、Kimi 和任何 OpenAI 兼容接口；聊到一半可以换模型 |
| **Agent** | 先列计划再动手；最多 6 个子代理在后台并行；适合单独做的工作开成分支会话；慢命令转入后台，跑完再叫醒会话；定时任务；技能与 MCP |
| **对话** | 最多 4 个会话同时生成；生成中的下一句可以排队或插队；需要你拍板时弹出提问卡；锁屏、切后台不中断；触摸锁让手机直接揣进口袋 |
| **运行环境** | 一键安装 Alpine Linux；没装 Linux 也有内置命令、Python 和 JavaScript 沙箱；也能接 Termux |
| **代码** | 内置 git；Git 面板看分支、差异和提交；Git 密码按命令注入，不写进仓库配置 |
| **服务器** | SSH 用主机别名执行命令、读写文件，单个文件最大 4 GiB；真终端和端口转发随时接手 |
| **私有网络** | 应用自带 Tailscale 节点，支持自建 Headscale；看得到每台设备是直连还是中继，可以持续 Ping；模型、SSH、MCP 都能直接访问 tailnet |
| **资料** | 文档、长期记忆和知识图谱一起检索，答案带引用；挂载外部文件夹；图谱按作品分开，可按篇序查人物关系的变化；支持 PDF、Word、Markdown、HTML 等格式 |
| **浏览器** | 多标签，手机版与桌面版网页切换；Agent 操作时你可以旁观、接手再交回 |
| **产出** | 画布页面、可点击的多屏原型、带版本的产物；生图，可在画板上圈注和涂抹选区；会话导出为网页、A4 PDF 或长图 |
| **折叠屏** | 内屏三列：导航、会话列表、会话；检视器常驻任务、文件、Git 与浏览器；开合屏会话无缝接续 |

每一项的细节与边界见[功能详解](docs/guide/features.md)。

## 开始使用

1. 从 [GitHub Releases](https://github.com/Promenar/Infinity-Workbench/releases) 下载 APK 和对应的 `.sha256` 文件。
2. 核对 SHA-256 后安装。只安装 Releases 里的正式包，不要用 CI 临时制品或来源不明的重打包版本。
3. 在设置里添加一个模型提供商，就可以开始聊了。

需要 Android 12 及以上。内置 Linux 需要 arm64-v8a 设备，基础系统约 64 MB，加上默认工具（git、ripgrep、Python、Node.js、FFmpeg）约 470 MB，安装时可以取消勾选。

当前源码版本为 `v0.34.0-beta`（versionCode `94`），变更见[更新日志](CHANGELOG.md)。

<details>
<summary><b>从源码构建</b></summary>

需要 JDK 17 及以上、Android SDK、`PATH` 中的 `python3.14`（预编译打包 Python）和 Go 1.21 及以上（编译 Tailscale 库）。

```bash
cd native-ui
./gradlew :app:assembleSideloadDebug
./gradlew :app:testSideloadDebugUnitTest :app:lintSideloadDebug
```

Debug APK 输出在 `native-ui/app/build/outputs/apk/sideload/debug/`。离线构建、签名与发行检查见[开发构建与发行验证](docs/guide/development.md)和[发行渠道构建](docs/distribution-builds.md)。

</details>

## 文档

| 文档 | 内容 |
|---|---|
| [功能详解](docs/guide/features.md) | 对话与渲染、上下文管理、资料与图谱、Agent 工具、子代理、浏览器、后台与定时任务、导出与备份 |
| [密钥与隐私](docs/guide/security-privacy.md) | 密钥存储、备份加密、SSH/Git/Tailscale 凭据、网络安全、诊断事件 |
| [执行环境](docs/guide/execution-environments.md) | 内置 Linux、Termux、隔离脚本运行时、SSH |
| [工作区与外部文件夹](docs/guide/workspaces.md) | 工作区类型、Git、Agent 可达范围、文件夹挂载 |
| [安装、升级与数据迁移](docs/guide/install-and-upgrade.md) | 运行要求、数据迁移、APK 验真 |
| [开发构建与发行验证](docs/guide/development.md) | 构建命令、打包 Python 与 Tailscale、富内容安全策略、图标、技术栈 |

另见[架构说明](docs/ARCHITECTURE.md)与[使用指南索引](docs/guide/README.md)。

## 许可证

Infinity Workbench 以 [GNU General Public License v3.0](LICENSE) 发布。设置列表、底部导航与外观页包含对同为 GPLv3 的 [Bettbox](https://github.com/appshubcc/Bettbox) 视觉实现的 Compose 转译；智能体头像符号来自 Google Material Symbols Rounded（Apache-2.0）。来源与范围见[第三方通知](docs/legal/THIRD_PARTY_NOTICES.md)。
