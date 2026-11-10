<p align="center">
  <img src="assets/branding/infinity-glow-icon.png" alt="Infinity Deck glowing dot-matrix infinity icon" width="112" height="112">
</p>

<h1 align="center">Infinity Deck</h1>

<p align="center">
  <b>Laptop off. Work goes on.</b><br>
  The AI agent that runs on your phone, with every repo, server and GPU you own in reach. Bring your own key. Your data stays on your device.
</p>

<p align="center">
  <b>English</b> · <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <a href="https://github.com/Promenar/Infinity-Deck/releases"><img src="https://img.shields.io/github/v/release/Promenar/Infinity-Deck?include_prereleases&label=download&color=6366F1" alt="Download the latest release"></a>
  <img src="https://img.shields.io/badge/Android-12%2B-green.svg" alt="Android 12+">
  <img src="https://img.shields.io/badge/license-GPL--3.0-blue.svg" alt="License: GPL-3.0">
</p>

AI coding tools on the desktop are great, until the computer is off and remote access goes with it. Remote control from the official apps needs that computer switched on and online, and cloud tasks can't reach your private network.

Infinity Deck does the work on the phone itself. It runs commands in a real Linux on your phone, edits your repos over git, reaches your servers and the GPU at home through its own built-in Tailscale, and clicks through web pages. Bring your own API key — OpenAI, Claude, Gemini, DeepSeek, or a model running on hardware you own — and it shows you every step before it runs. Chats, documents and settings stay on your device; model requests go straight to the provider you chose.

On the train or in bed, pick up the phone and keep going.

## Contents

- 🧭 [How it differs from remote control](#how-it-differs-from-remote-control)
- ✨ [What it can do for you](#what-it-can-do-for-you)
- 🔒 [You're always in charge](#youre-always-in-charge)
- 🚀 [Get started](#get-started)
- 🧰 [Everything it does](#everything-it-does)
- 💬 [FAQ](#faq)
- 📚 [Documentation](#documentation)
- 🤝 [Get involved](#get-involved)

## How it differs from remote control

- **The phone does the work.** A built-in Alpine Linux runs git, Python, Node.js and FFmpeg on the phone, with no computer that has to stay online.
- **Straight to your own machines.** A Tailscale node inside the app takes SSH, models and MCP straight into your tailnet — the servers you run, the GPU at home — with no open ports and no system VPN.
- **Any model you like.** Bring a key for any provider, switch mid-chat, or point it at a model on your own hardware.
- **Made for the phone.** Approval cards, background tasks, a message queue, touch lock and the foldable cover screen work one-handed, lying down or on the train.
- **Works alongside your desktop tools.** Keep your usual AI coding tools at the desk; when you leave, the phone picks up the same git repo, and while the computer is on it can SSH in and borrow its power.

## What it can do for you

<p align="center">
  <img src="assets/screenshots/en/hero.webp" alt="Unfolded: the inspector pinned on the Git tab lists commits while the chat makes room on the left. Folded: the 502 fix report continues on the cover screen" width="860">
</p>

<table>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/ops-diagnosis.webp" alt="Remote commands on web-01 wait for approval one by one">
      <h3>Fix a production outage from the train</h3>
      Your site returns 502, so you ask it to log in to the server and find out why. Every command waits for your tap before it runs. When it's fixed, it writes up a report you can keep reading on the cover screen.
    </td>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/ade-branch.webp" alt="Work plan proposing a branch chat in a new worktree">
      <h3>Your code goes where you go</h3>
      The main chat fixes the bug while a branch chat writes the tests, each in its own worktree so they never collide. Branches, diffs and commits are all in the Git panel.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/ade-subagents.webp" alt="Two sub-agent cards, one locating the change and one reviewing test coverage, while the main chat waits with a Background button">
      <h3>One ask, a small team</h3>
      Ask for a change and it plans first, then sends sub-agents out in parallel — one to find the code, another to review the tests. The main chat can wait for their findings or move to the background and keep going. When each is done, its findings come back on their own, with the time it took.
    </td>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/linux-terminal.webp" alt="fastfetch in the built-in terminal: Alpine Linux">
      <h3>A real Linux in your pocket</h3>
      Run a Python script, convert a video with FFmpeg or <code>apk add</code> the tool you need, all inside the app. No root, no Termux.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/fold-inspector.webp" alt="Inner screen in three columns with the inspector pinned on the Run tab: the plan at step 4 of 6 with one background task running">
      <h3>Unfold a workbench</h3>
      The inner screen splits into three — navigation, chat list, conversation. Pin the inspector and the chat makes room for it; unpin it and it floats over the chat. Tasks, files, Git and the browser are one tap away.
    </td>
    <td width="50%" valign="top">
      <p align="center"><img src="assets/screenshots/en/outer-chat.webp" alt="Cover screen showing the 502 fix report mid-read" width="190"></p>
      <h3>Fold it and carry on</h3>
      Close the phone and the chat moves to the cover screen. Finish reading the incident report on the train, answer with one hand, unfold to go deeper when you're back.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>Your notes and drafts, close at hand</h3>
      Import PDF, Word or Markdown, or mount an outside folder. It searches documents, long-term memory and the knowledge graph together and cites its answers. Ask for artwork when the draft is done and the images generate in the background; export the result as a web page, an A4 PDF or a long image.
    </td>
    <td width="50%" valign="top">
      <h3>Your machines at home, on call</h3>
      Models on your home GPU, servers on your private network and MCP services connect through the built-in Tailscale, with self-hosted Headscale supported. Chat and image generation can both run on your own GPU.
    </td>
  </tr>
</table>

## You're always in charge

- **It asks before every command.** By default nothing runs without your approval, and deleting files always needs your OK, whatever mode you're in.
- **Passwords never pass through the AI.** When one is needed, a secret input card stores it straight into the credential. It never enters the chat, and screenshots are blocked while it's shown.
- **Your data stays on your phone.** Chats, documents and settings live on the device, and model requests go straight to the provider you chose, never through a server of ours.
- **You own your backups.** Save them to a local file or WebDAV, encrypted if you like.
- **Fully open source.** GPL-3.0.

## Get started

1. Download the APK and its `.sha256` file from [GitHub Releases](https://github.com/Promenar/Infinity-Deck/releases).
2. Check the SHA-256 and install. Only install packages from Releases, never CI artifacts or repackaged builds from elsewhere.
3. Add a model provider in Settings and start chatting.

Requires Android 12 or later. The built-in Linux needs an arm64-v8a device and takes about 64 MB, or about 470 MB with the default tools (git, ripgrep, Python, Node.js, FFmpeg), each of which you can uncheck.

The source tree is at `v0.41.7-beta` (versionCode `116`). See the [changelog](CHANGELOG.md) for what changed.

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

## Everything it does

**Chat & models**

- Bring your own key for OpenAI, Anthropic, Gemini, Vertex AI, DeepSeek, GLM, Kimi or any OpenAI-compatible endpoint
- Switch models mid-chat; up to 16 chats generating at once
- Queue your next message or let it jump the line
- Long chats stay searchable: after compaction the agent can search and page through the original messages
- Question cards when it needs your call; keeps going with the screen locked
- Touch lock so the phone can go straight into your pocket
- Skills and MCP servers

**Agents**

- Plans before it acts
- Up to 32 sub-agents working in parallel; conclusions return on their own
- Side work becomes a branch chat
- Slow commands, image generation and downloads move to the background and wake the chat when done
- Scheduled tasks, one-shot to cron

**Runtime & code**

- One-tap Alpine Linux — no root, no Termux
- Built-in shell, Python and JavaScript sandboxes even without Linux
- Built-in git; branches, diffs and commits in the Git panel
- Git passwords injected per command, never written to repo config

**Servers & private network**

- SSH by host alias to run commands and read or write files, up to 4 GiB per file
- Take over any time in a real terminal or with port forwarding
- A Tailscale node inside the app, with self-hosted Headscale support
- Models, SSH and MCP reach your tailnet directly — no open ports, no system VPN

**Documents & knowledge**

- Search documents, long-term memory and the knowledge graph together, with cited answers
- One graph per work — follow how two characters stand with each other, chapter by chapter
- Mount outside folders; PDF, Word, Markdown, HTML and more

**Web & output**

- Browser tabs, mobile or desktop sites; watch the agent, take over and hand it back
- Canvas pages, clickable multi-screen prototypes and versioned artifacts
- Image generation with a drawing board for markup and masks
- Export chats as a web page, A4 PDF or long image

**Foldables**

- Three columns on the inner screen: navigation, chat list and chat
- An inspector for tasks, files, Git and the browser, pinned beside the chat or floating over it
- When it's closed, an edge strip reopens any tab
- Chats carry on as you fold and unfold

Details and limits for each item are in the [feature guide](docs/guide/features.md) (in Chinese).

## FAQ

**The official apps already have remote control. Why this?**

Remote control drives your computer: that computer has to stay on and online, and the phone is only the remote. Cloud tasks don't need your computer, but they can't reach your private network and only run that vendor's models. Infinity Deck runs on the phone itself, reaches your own machines through Tailscale and talks to any model you choose. The two also work together: while your computer is on, let it SSH in for the heavy lifting.

**How is this different from a chat app?**

Chat apps answer. This one does the work: it runs commands in a real Linux, edits your repos over git, logs in to your servers over SSH and drives a browser — with your approval before anything runs.

**Do I need Termux or root?**

No. The Linux environment is a one-tap Alpine install inside the app (arm64). Termux works too, but it's optional.

**Why are the detailed guides in Chinese?**

The project is built by a Chinese-speaking developer and the guides grew out of working notes. The README covers the essentials and the app interface itself is in English as well. Help translating is welcome.

## Documentation

| Guide | Covers | Language |
|---|---|---|
| [Features](docs/guide/features.md) | Chat and rendering, context management, documents and the knowledge graph, agent tools, sub-agents, browser, background and scheduled tasks, export and backup | Chinese |
| [Security and privacy](docs/guide/security-privacy.md) | Key storage, backup encryption, SSH, Git and Tailscale credentials, network security, diagnostics | Chinese |
| [Execution environments](docs/guide/execution-environments.md) | Built-in Linux, Termux, sandboxed script runtimes, SSH | Chinese |
| [Workspaces](docs/guide/workspaces.md) | Workspace types, Git, what agents can reach, mounted folders | Chinese |
| [Install and upgrade](docs/guide/install-and-upgrade.md) | Requirements, data migration, APK verification | Chinese |
| [Development](docs/guide/development.md) | Build commands, bundled Python and Tailscale, rich-content security, icons, tech stack | Chinese |

See also the [architecture overview](docs/ARCHITECTURE.md) and the [guide index](docs/guide/README.md).

## Get involved

Bugs and ideas are welcome as GitHub issues. If you'd like to change code, read [AGENTS.md](AGENTS.md) first — the project runs a heavy verification gate, so opening an issue to talk it through before a large PR saves everyone time.

## License

Infinity Deck is released under the [GNU General Public License v3.0](LICENSE). The settings list, navigation bar and appearance page include Compose adaptations of [Bettbox](https://github.com/appshubcc/Bettbox), also GPLv3. Agent avatar symbols come from Google's Material Symbols Rounded (Apache-2.0). Sources and scope are listed in the [third-party notices](docs/legal/THIRD_PARTY_NOTICES.md).
