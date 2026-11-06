<p align="center">
  <img src="assets/branding/infinity-glow-icon.png" alt="Infinity Workbench glowing dot-matrix infinity icon" width="112" height="112">
</p>

<h1 align="center">Infinity Workbench</h1>

<p align="center">
  <b>Your terminal, your repos, your servers and your AI, together on your phone.</b>
</p>

<p align="center">
  <b>English</b> · <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <a href="https://github.com/Promenar/Infinity-Workbench/releases"><img src="https://img.shields.io/github/v/release/Promenar/Infinity-Workbench?include_prereleases&label=download&color=6366F1" alt="Download the latest release"></a>
  <img src="https://img.shields.io/badge/Android-12%2B-green.svg" alt="Android 12+">
  <img src="https://img.shields.io/badge/license-GPL--3.0-blue.svg" alt="License: GPL-3.0">
</p>

https://github.com/user-attachments/assets/97ec8287-b32f-452f-8bcd-1bffa4f789e6

<p align="center">
  <img src="assets/screenshots/en/hero.webp" alt="Unfolded: the chat beside the Git panel listing commits. Folded: the 502 fix report continues on the cover screen" width="860">
</p>

Bring your own API key and connect OpenAI, Claude, Gemini, DeepSeek or a model running at home. Then let it do the work: it runs commands in a Linux system on your phone, edits your code, logs in to your servers and clicks through web pages, and shows you every step first.

Unfold your phone and it becomes a workbench. Fold it and the chat carries on on the cover screen.

## What it can do for you

<table>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/ops-diagnosis.webp" alt="Remote commands on web-01 wait for approval one by one">
      <h3>Fix a production outage from the train</h3>
      Your site returns 502, so you ask it to log in to the server and find out why. Every command waits for your tap before it runs. When it's fixed, it writes up a report you can keep reading on the cover screen.
    </td>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/linux-terminal.webp" alt="fastfetch in the built-in terminal: Alpine Linux">
      <h3>A real Linux in your pocket</h3>
      Run a Python script, convert a video with FFmpeg or <code>apk add</code> the tool you need, all inside the app. No root, no Termux.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/ade-branch.webp" alt="Work plan proposing a branch chat in a new worktree">
      <h3>Your code goes where you go</h3>
      The main chat fixes the bug while a branch chat writes the tests, each in its own worktree so they never collide. Branches, diffs and commits are all in the Git panel.
    </td>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/tailnet-gateway.webp" alt="Picking a model gateway from tailnet devices in the provider form">
      <h3>Use the GPU you have at home</h3>
      Got a model running on your desktop? Join your Tailscale network and chat with it or search with it straight from your phone. No open ports, and your system VPN stays free.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/library-ask.webp" alt="Ask the library: an answer with numbered citations and matching results">
      <h3>Ask your own documents</h3>
      Drop in a folder of papers, contracts or a whole novel, then just ask. Every answer cites its sources, and one tap takes you to the exact passage. Writing a long story? Ask how two characters stand with each other in chapter 12.
    </td>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/browser-agent.webp" alt="The agent clicking Re-run failed jobs on a CI page">
      <h3>Let it handle the web for you</h3>
      Send it to your CI page to re-run the failed jobs, and watch or take over whenever you like. When it reaches a login page it stops, and you type the password yourself. It never sees it.
    </td>
  </tr>
</table>

## You're always in charge

- **It asks before every command.** By default nothing runs without your approval, and deleting files always needs your OK, whatever mode you're in.
- **Passwords never pass through the AI.** When one is needed, a secret input card stores it straight into the credential. It never enters the chat, and screenshots are blocked while it's shown.
- **Your data stays on your phone.** Chats, documents and settings live on the device, and model requests go straight to the provider you chose, never through a server of ours.
- **You own your backups.** Save them to a local file or WebDAV, encrypted if you like.
- **Fully open source.** GPL-3.0.

## Everything it does

| | |
|---|---|
| **Models** | Bring your own key for OpenAI, Anthropic, Gemini, Vertex AI, DeepSeek, GLM, Kimi or any OpenAI-compatible endpoint; switch models mid-chat |
| **Agents** | Plans before it acts; up to 6 sub-agents working in the background; side work becomes a branch chat; slow commands move to the background and wake the chat when done; scheduled tasks; skills and MCP |
| **Chat** | Up to 4 chats generating at once; queue your next message or let it jump the line; question cards when it needs your call; keeps going with the screen locked or the app in the background; touch lock so the phone can go straight into your pocket |
| **Runtime** | One-tap Alpine Linux; built-in shell, Python and JavaScript sandboxes even without Linux; Termux works too |
| **Code** | Built-in git; the Git panel shows branches, diffs and commits; Git passwords are injected per command and never written to repo config |
| **Servers** | SSH by host alias to run commands and read or write files, up to 4 GiB per file; take over any time in a real terminal or with port forwarding |
| **Private network** | A Tailscale node inside the app, with self-hosted Headscale support; see whether each device is direct or relayed and ping it continuously; models, SSH and MCP reach your tailnet directly |
| **Documents** | Search documents, long-term memory and the knowledge graph together, with cited answers; mount outside folders; one graph per work, with relationships you can follow chapter by chapter; PDF, Word, Markdown, HTML and more |
| **Browser** | Tabs, mobile or desktop sites; watch the agent, take over and hand it back |
| **Output** | Canvas pages, clickable multi-screen prototypes and versioned artifacts; image generation with a drawing board for markup and masks; export chats as a web page, A4 PDF or long image |
| **Foldables** | Three columns on the inner screen: navigation, chat list and chat; an inspector for tasks, files, Git and the browser; chats carry on as you fold and unfold |

Details and limits for each item are in the [feature guide](docs/guide/features.md) (in Chinese).

## Get started

1. Download the APK and its `.sha256` file from [GitHub Releases](https://github.com/Promenar/Infinity-Workbench/releases).
2. Check the SHA-256 and install. Only install packages from Releases, never CI artifacts or repackaged builds from elsewhere.
3. Add a model provider in Settings and start chatting.

Requires Android 12 or later. The built-in Linux needs an arm64-v8a device and takes about 64 MB, or about 470 MB with the default tools (git, ripgrep, Python, Node.js, FFmpeg), each of which you can uncheck.

The source tree is at `v0.33.4-beta` (versionCode `93`). See the [changelog](CHANGELOG.md) for what changed.

<details>
<summary><b>Build from source</b></summary>

You need JDK 17 or newer, the Android SDK, `python3.14` on `PATH` (it precompiles the bundled Python) and Go 1.21 or newer (it builds the Tailscale library).

```bash
cd native-ui
./gradlew :app:assembleSideloadDebug
./gradlew :app:testSideloadDebugUnitTest :app:lintSideloadDebug
```

The debug APK is written to `native-ui/app/build/outputs/apk/sideload/debug/`. Offline builds, signing and release checks are covered in [Development and release verification](docs/guide/development.md) and [Distribution builds](docs/distribution-builds.md).

</details>

## Documentation

The detailed guides are written in Chinese.

| Guide | Covers |
|---|---|
| [Features](docs/guide/features.md) | Chat and rendering, context management, documents and the knowledge graph, agent tools, sub-agents, browser, background and scheduled tasks, export and backup |
| [Security and privacy](docs/guide/security-privacy.md) | Key storage, backup encryption, SSH, Git and Tailscale credentials, network security, diagnostics |
| [Execution environments](docs/guide/execution-environments.md) | Built-in Linux, Termux, sandboxed script runtimes, SSH |
| [Workspaces](docs/guide/workspaces.md) | Workspace types, Git, what agents can reach, mounted folders |
| [Install and upgrade](docs/guide/install-and-upgrade.md) | Requirements, data migration, APK verification |
| [Development](docs/guide/development.md) | Build commands, bundled Python and Tailscale, rich-content security, icons, tech stack |

See also the [architecture overview](docs/ARCHITECTURE.md) and the [guide index](docs/guide/README.md).

## License

Infinity Workbench is released under the [GNU General Public License v3.0](LICENSE). The settings list, navigation bar and appearance page include Compose adaptations of [Bettbox](https://github.com/appshubcc/Bettbox), also GPLv3. Agent avatar symbols come from Google's Material Symbols Rounded (Apache-2.0). Sources and scope are listed in the [third-party notices](docs/legal/THIRD_PARTY_NOTICES.md).
