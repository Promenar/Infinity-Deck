<p align="center">
  <img src="assets/branding/infinity-glow-icon.png" alt="Infinity Deck 发光点阵无穷图标" width="112" height="112">
</p>

<h1 align="center">Infinity Deck</h1>

<p align="center">
  <b>电脑关了，工作不断。</b><br>
  跑在手机上的 AI 智能体，接通你所有的仓库、服务器和算力。使用你自己的 Key，数据留在本机。
</p>

<p align="center">
  <a href="README.md">English</a> · <b>简体中文</b>
</p>

<p align="center">
  <a href="https://github.com/Promenar/Infinity-Deck/releases"><img src="https://img.shields.io/github/v/release/Promenar/Infinity-Deck?include_prereleases&label=%E4%B8%8B%E8%BD%BD&color=6366F1" alt="下载最新版"></a>
  <a href="https://infinityware.work"><img src="https://img.shields.io/badge/%E5%AE%98%E7%BD%91-infinityware.work-6366F1" alt="官网"></a>
  <img src="https://img.shields.io/badge/Android-12%2B-green.svg" alt="Android 12+">
  <img src="https://img.shields.io/badge/license-GPL--3.0-blue.svg" alt="许可证 GPL-3.0">
</p>

桌面上的 AI 编程工具很好用，可电脑一关，远程就断了。官方 App 的远程控制要那台电脑一直开着、在线；云端任务又进不了你的内网。

Infinity Deck 让手机自己干活：在手机里的真 Linux 跑命令，用 git 改你的仓库，通过应用自带的 Tailscale 直连你的服务器和家里的显卡，替你操作网页。用你自己的 API Key 接上 OpenAI、Claude、Gemini、DeepSeek，或家里跑着的模型，每一步先给你过目。对话、文档和设置都留在本机，模型请求直连你配置的服务商。

在路上、在床上，掏出手机接着干。

## 目录

- 🧭 [它和远程控制有什么不同](#它和远程控制有什么不同)
- ✨ [它能帮你做什么](#它能帮你做什么)
- 🔒 [你始终说了算](#你始终说了算)
- 🚀 [开始使用](#开始使用)
- 🧰 [全部功能](#全部功能)
- 💬 [常见问题](#常见问题)
- 📚 [文档](#文档)
- 🤝 [参与贡献](#参与贡献)

## 它和远程控制有什么不同

- **手机自己就能干活。** 内置 Alpine Linux，git、Python、Node.js、FFmpeg 都在手机上跑，不靠任何一台电脑在线。
- **直达你自己的机器。** 应用自带 Tailscale 节点，SSH、模型、MCP 和浏览器直接进你的 tailnet：机房的服务器、家里的显卡，不开公网端口，也不占系统 VPN。
- **模型随你选。** 使用你自己的 Key 接任何服务商，聊到一半可以换，也能接家里跑的模型。
- **为手机而做。** 命令审批卡、后台任务、消息排队、触摸锁、折叠屏外屏，单手、躺着、在地铁上都顺手。
- **和桌面工具一起用。** 坐在电脑前继续用你习惯的 AI 编程工具；离开时手机接着改同一个 git 仓库，电脑开着也能 SSH 上去借它的算力。

## 它能帮你做什么

<p align="center">
  <img src="assets/screenshots/zh/hero.webp" alt="展开：检视器固定在 Git 页签列出提交记录，会话让到左边；合上：外屏继续查看 502 排障报告" width="860">
</p>

<table>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/zh/ops-diagnosis.webp" alt="web-01 上的远程命令逐条等待批准">
      <h3>在地铁上修好线上故障</h3>
      网站报 502，你让它连上服务器去查。每条命令先给你过目，点一下才执行；修完它写好报告，合上手机在外屏接着看。
    </td>
    <td width="50%" valign="top">
      <img src="assets/screenshots/zh/ade-branch.webp" alt="工作方案卡提议在新工作树里开分支会话">
      <h3>代码随身改</h3>
      主会话修 bug，补测试的活派给分支会话，各自在独立的工作树里改，互不打架。分支、差异、提交在 Git 面板里一目了然。
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/zh/ade-subagents.webp" alt="两张子代理卡：一个定位代码改动点，一个审查测试覆盖缺口；主会话等待中，可转入后台">
      <h3>一句话，一支小团队</h3>
      提一个需求，它先列计划，再并行派出子代理：一个定位改动点，一个审查测试覆盖。主会话可以等结论，也可以转入后台接着干。谁做完了，结论自己交回来，还附上用时。
    </td>
    <td width="50%" valign="top">
      <img src="assets/screenshots/zh/linux-terminal.webp" alt="内置终端里运行 fastfetch：Alpine Linux">
      <h3>手机里有一台真 Linux</h3>
      跑 Python 脚本、用 FFmpeg 转视频、<code>apk add</code> 装个工具，都在应用里完成。不用 root，也不用装 Termux。
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/zh/fold-inspector.webp" alt="内屏三列布局，检视器固定在运行页签：计划第 4/6 步，1 个后台任务运行中">
      <h3>展开就是工作台</h3>
      内屏一分为三：导航、会话列表、会话。检视器固定时会话自动让位，取消固定就浮在会话上；任务、文件、Git、浏览器一点即达。
    </td>
    <td width="50%" valign="top">
      <p align="center"><img src="assets/screenshots/zh/outer-chat.webp" alt="外屏上正在阅读的 502 修复报告" width="190"></p>
      <h3>合上，接着看</h3>
      合上手机，会话挪到外屏接着走。地铁上把排障报告看完，单手回一句，回到桌前再展开继续。
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>资料和稿子都在手边</h3>
      导入 PDF、Word、Markdown，或直接挂载外部文件夹。文档、长期记忆和知识图谱一起检索，答案带引用。写完让它配图，生图在后台跑；成稿导出网页、A4 PDF 或长图。在文件管理器里打开 Markdown，直接进查看器，存下或发给会话都行。
    </td>
    <td width="50%" valign="top">
      <h3>家里的机器随叫随到</h3>
      家里显卡跑的模型、内网的服务器和 MCP 服务，经应用自带的 Tailscale 直接连，也支持自建 Headscale。对话、生图都能走自己的显卡。
    </td>
  </tr>
</table>

## 你始终说了算

- **每条命令先问你。** 默认要你批准才执行；删除文件无论哪一档都要你点头。
- **密码不经过 AI。** 需要密码时弹出保密输入卡，内容直接存进凭据，不进对话，显示时禁止截屏。
- **密钥统一进钥匙串。** SSH 密钥、Git 令牌、API Key 和网站登录都加密存储；查看须先过设备验证，30 秒后自动隐藏，复制后 60 秒清空剪贴板。
- **数据留在手机上。** 对话、文档和设置都在本机，模型请求直连你配置的服务商，不经过我们的服务器。
- **备份你来管。** 存到本地文件或 WebDAV，可以加密。
- **完全开源。** GPL-3.0。

## 开始使用

1. 从 [GitHub Releases](https://github.com/Promenar/Infinity-Deck/releases) 下载 APK 和对应的 `.sha256` 文件。
2. 核对 SHA-256 后安装。只安装 Releases 里的正式包，不要用 CI 临时制品或来源不明的重打包版本。
3. 在设置里添加一个模型提供商，就可以开始聊了。

需要 Android 12 及以上。内置 Linux 需要 arm64-v8a 设备，基础系统约 64 MB，加上默认工具（git、ripgrep、Python、Node.js、FFmpeg）约 470 MB，安装时可以取消勾选。

升级：在设置 › 关于里点“检查更新”，有新版时打开发行页下载，不会自动安装。

当前源码版本为 `v0.57.2-beta`（versionCode `156`），变更见[更新日志](CHANGELOG.md)。

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

## 全部功能

**对话与模型**

- 使用你自己的 Key 接入 OpenAI、Anthropic、Gemini、Vertex AI、DeepSeek、GLM、Kimi 和任何 OpenAI 兼容接口
- 聊到一半可以换模型；最多 16 个会话同时生成
- 生成中的下一句可以排队或插队
- 长对话压缩后，Agent 仍能检索和翻看原文
- 需要你拍板时弹出提问卡；锁屏、切后台不中断
- 触摸锁让手机直接揣进口袋
- 技能与 MCP 服务器；技能商店从官方目录或自行添加的目录安装经校验的技能
- 会话宠物：导入 `.idpet` 宠物包，站在输入浮岛上随任务状态和早晨、白天、晚上、深夜四个时段换姿势与台词，可点按、拖动

**智能体**

- 先列计划再动手
- 最多 32 个子代理并行，结论自动交回
- 适合单独做的工作开成分支会话
- 慢命令、生图与下载转入后台，跑完再叫醒会话
- 定时任务，单次到 cron

**运行环境与代码**

- 一键 Alpine Linux，不用 root，也不用 Termux
- 没装 Linux 也有内置命令、Python 和 JavaScript 沙箱
- 内置 git；Git 面板看分支、差异和提交
- Git 密码按命令注入，不写进仓库配置
- Agent 分段读取文本文件，单个最大 100 MiB

**服务器与私有网络**

- SSH 用主机别名执行命令、读写文件，单个文件最大 4 GiB
- SSH 密钥存在钥匙串：生成 Ed25519 或 RSA 4096，导入 OpenSSH、PEM 与 PKCS8 私钥，带口令的也行
- 公钥一次部署到多台主机，先验证登录，通过后主机才改用这把密钥
- 真终端和端口转发随时接手
- 应用自带 Tailscale 节点，支持自建 Headscale
- 模型、SSH、MCP 和浏览器直达 tailnet，不开公网端口，不占系统 VPN

**资料与知识**

- 资料页的「＋ 添加」菜单提供录音、导入文件和新建笔记。打开录音界面后，可手动开始或续录已有录音。Android 快捷设置可直接启动录音。安装 X-ASR 听写模型后可在本机实时转写中英混读，录音可按时间戳回听、加热词和重新识别。可在本机区分说话人、调整段落归属和管理声纹。整理可使用快速模型（需确认发送文本），侧载版也可使用本地 GGUF。
- 文档、长期记忆和知识图谱一起检索，答案带引用
- 图谱按作品分开，可按篇序查人物关系的变化
- 挂载外部文件夹；支持 PDF、Word、Markdown、HTML 等格式
- 其它应用里打开的 Markdown 可另存为、存入存储空间，或以附件、引用发送到会话
- 大 Markdown 分块渲染，16 MiB 以内显示正文

**网页与产出**

- 浏览器多标签，手机版与桌面版切换；Agent 操作时旁观、接手再交回
- 输入 `localhost:8888` 直接打开 Linux 或 Termux 里跑的服务
- 画布页面、可点击的多屏原型、带版本的产物
- 生图，可在画板上圈注和涂抹选区
- 制作面板把剧本、资产、分镜、出片和剪辑串成一条线：每个镜头的通道、参考图和提示词都能预览，审核按回合进行，批准、否决、建议只做记录，由“发送”一次交给智能体；按阶段、镜头和资产记录生成次数；智能体可以按尾帧串联续接镜头、用 ComfyUI 超分或补帧模板处理候选、生成多版配乐，在应用之外跑的脚本也会登记进展，未登记的媒体文件会被标出；可接火山方舟 Seedance、MiniMax 和自己的 ComfyUI 工作流
- 会话导出为网页、A4 PDF 或长图

**折叠屏**

- 内屏三列：导航、会话列表、会话
- 检视器装着任务、文件、Git 与浏览器，可固定在会话旁，也可浮在会话上
- 收起后边缘条常驻，一点回到任一页签
- 开合屏会话无缝接续

每一项的细节与边界见[功能详解](docs/guide/features.md)。

## 常见问题

**已经有官方 App 的远程控制了，为什么还要它？**

远程控制遥控的是你的电脑：那台电脑得一直开着、在线，手机只是遥控器。云端任务不需要你的电脑，但进不了你的内网，也只能用那一家的模型。Infinity Deck 在手机上就能执行，经 Tailscale 直达你自己的机器，模型随你接。两者也可以一起用：电脑开着时，让它 SSH 上去干重活。

**和手机上的聊天应用有什么区别？**

聊天应用只回答，这个会动手：在真 Linux 里跑命令、用 git 改仓库、SSH 登服务器、驱动浏览器，每一步先经你批准。

**需要 Termux 或 root 吗？**

都不需要。Linux 环境是应用内一键安装的 Alpine（arm64）。Termux 也能接，但完全可选。

**详细指南为什么是中文的？**

作者是中文开发者，指南从工作笔记里长出来。README 覆盖要点，应用界面本身也有英文。欢迎帮忙翻译。

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

## 参与贡献

发现问题或有想法，欢迎开 GitHub issue。想改代码请先读 [AGENTS.md](AGENTS.md)：项目跑着较重的验证门禁，大的改动先开 issue 聊清楚，能省下彼此的时间。

## 许可证

Infinity Deck 以 [GNU General Public License v3.0](LICENSE) 发布。设置列表、底部导航与外观页包含对同为 GPLv3 的 [Bettbox](https://github.com/appshubcc/Bettbox) 视觉实现的 Compose 转译；智能体头像符号来自 Google Material Symbols Rounded（Apache-2.0）。来源与范围见[第三方通知](docs/legal/THIRD_PARTY_NOTICES.md)。
