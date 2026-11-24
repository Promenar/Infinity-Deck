<p align="center">
  <img src="assets/branding/infinity-glow-icon.png" alt="Infinity Deck icon" width="112" height="112">
</p>

<h1 align="center">Infinity Deck</h1>

<p align="center">
  <b>An open-source, local-first AI agent workbench on Android.</b><br>
  Alpine Linux container, Git, SSH, built-in Tailscale, on-device speech &amp; GGUF inference, hybrid RAG, and a media production pipeline in a single app.<br>
  Bring your own API key or local models. Your data and credentials stay on the device.
</p>

<p align="center">
  <b>English</b> · <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <a href="https://github.com/Promenar/Infinity-Deck/releases"><img src="https://img.shields.io/github/v/release/Promenar/Infinity-Deck?include_prereleases&label=download&color=6366F1" alt="Download latest release"></a>
  <a href="https://infinityware.work"><img src="https://img.shields.io/badge/website-infinityware.work-6366F1" alt="Website"></a>
  <img src="https://img.shields.io/badge/Android-12%2B-green.svg" alt="Android 12+">
  <img src="https://img.shields.io/badge/license-GPL--3.0-blue.svg" alt="License: GPL-3.0">
</p>

**Infinity Deck** is an open-source AI workbench for Android 12+, combining multi-agent orchestration, on-device shell and code execution, private tailnet connectivity, offline speech transcription, a local knowledge base, and multimedia production workflows:

- **Local execution layer**: One-tap rootless Alpine Linux (`arm64-v8a`, with `git`, `ripgrep`, Python, Node.js, and FFmpeg) alongside built-in Python 3.14, QuickJS, and a trimmed `arm64-v8a` FFmpeg/ffprobe toolchain — run scripts, process media, build projects, and transform files directly on the phone without keeping a remote desktop host awake.
- **Models and on-device inference**: Connect major cloud APIs and Coding Plan presets with your own keys, or run `llama.cpp` GGUF inference locally (independent `MAIN`, `EMBEDDING`, and `RERANK` slots with `16K / 8K / 4K` Prefix KV Cache) and an on-device `sherpa-onnx` speech pipeline (`X-ASR` streaming, two-pass `SenseVoice-Small int8` refinement with RAM-tier gating and RTF backoff, and speaker diarization).
- **Private networking, built-in proxy & remote ops**: Embedded userspace Tailscale node (compatible with self-hosted Headscale) paired with an independent userspace multi-protocol Built-in Proxy (`libnxproxy.so`, zero system VPN, zero RAM when off) — keeping Tailnet/LAN/Tailscale P2P strictly direct while letting you opt in per AI provider, auto-route the built-in browser and public web tools, and inject `$INFINITY_PROXY_*` on demand inside Alpine Linux.
- **Knowledge & creative workspace**: Four-section Library › Files manager with one-step workspace cleanup and `read_only / read_write` mounted folders, hybrid vector + memory + knowledge-graph retrieval with numbered citations, rich-format offline recordings (tables, Mermaid, ECharts, LaTeX, unbounded PNG/PDF), interactive web prototypes, universal zero-hardcoding ComfyUI workflow submitters (`14` `comfy_*` tools), and a short-film production panel with offline `Three.js` 3D previs and on-device MP4 + SRT export.

## Contents

- 🧭 [Architecture & Runtime Behavior](#architecture--runtime-behavior)
- ✨ [Core Workflows](#core-workflows)
- 🔒 [Security & Data Boundaries](#security--data-boundaries)
- 🚀 [Install & Build](#install--build)
- 🧰 [Capabilities](#capabilities)
- 💬 [FAQ](#faq)
- 📚 [Documentation](#documentation)
- 🤝 [Contributing](#contributing)

## Architecture & Runtime Behavior

- **Standalone on-device execution**: Command execution, Git repository operations, document parsing, and local model inference run directly on the phone; offload heavy workloads to home workstations or GPU hosts over SSH and Tailscale whenever they are online.
- **Multi-protocol model routing**: Native adapters for OpenAI Responses / Chat Completions, Anthropic Messages, Google Gemini / Vertex AI, DeepSeek, and OpenAI-compatible gateways, with mid-chat model switching and per-model connectivity probes.
- **Zero public exposure for private services**: Embedded Tailscale connects app traffic straight to private `tailnet` peers with no public port forwarding and no system VPN conflicts.
- **Human-in-the-loop controls**: Upfront execution plans, per-command approval cards, structured `ask_user` prompts, background job tracking, and message queueing/steering.
- **Adaptive foldable & tablet layout**: Three-column workspace (`Navigation · Chat List · Conversation`) on inner screens with a docked/expandable obsidian inspector and swipeable tabs; folds seamlessly to the cover screen with a 93% obsidian frosted drawer and preserved scroll and composer state for one-handed review.

## Core Workflows

<p align="center">
  <img src="assets/screenshots/en/hero.webp" alt="Unfolded three-column workbench with pinned Git commit history alongside the cover screen continuing the 502 fix report" width="860">
</p>

<table>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/ade-subagents.webp" alt="Two parallel sub-agents locating edits and auditing test coverage while the main chat waits or backgrounds">
      <h3>Task Planning &amp; Parallel Sub-Agents</h3>
      Before tackling complex tasks, the agent lays out step-by-step plans and spawns parallel read-only or scoped sub-agents (up to 32 active per chat, 8 per dispatch) to search codebases, check references, or review tests, returning structured findings and timings automatically.
    </td>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/ade-branch.webp" alt="Work plan proposing an isolated branch chat in a new Git worktree">
      <h3>Git Worktrees &amp; Isolated Branch Chats</h3>
      Fork side tasks into branch chats bound to isolated Git worktrees. Main and branch sessions edit files and run test suites in parallel without stepping on each other, inspected through the built-in Git panel for diffs, branches, and commit history.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/ops-diagnosis.webp" alt="Remote SSH commands waiting for individual user approval">
      <h3>SSH Remote Execution &amp; Command Approval</h3>
      Connect over SSH to inspect failing hosts. In default approval mode, every command displays the target host, exact arguments, and execution context for confirmation before running, producing a clean incident summary afterward.
    </td>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/linux-terminal.webp" alt="Alpine Linux running fastfetch inside the built-in terminal">
      <h3>Built-in Rootless Alpine Linux</h3>
      Deploy an `arm64-v8a` Alpine Linux userland in one tap with no root and no external Termux. Run Python, Node.js, FFmpeg, Git, or extend the environment with `apk add`.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/fold-inspector.webp" alt="Foldable inner screen three-column layout with pinned inspector">
      <h3>Foldable Workbench &amp; Pinned Inspector</h3>
      Unfolded screens switch to an adaptive multi-column workspace (`Navigation · 40dp circuit branch rail or chat list · Conversation`) paired with an obsidian frosted-glass inspector housing tasks, files, Git, browser, and on-demand artifact/state/production panels on a scrollable short-label strip.
    </td>
    <td width="50%" valign="top">
      <p align="center"><img src="assets/screenshots/en/outer-chat.webp" alt="Cover screen showing the 502 fix report for one-handed reading" width="190"></p>
      <h3>Seamless Fold/Unfold Continuity</h3>
      Fold the device and the active chat transitions to the cover screen with scroll position and draft state intact for one-handed review, command/lock approval taps, floating notice capsules, or quick follow-up prompts.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/recording-workbench.webp" alt="Inner-screen recording workbench with speaker-labeled transcript on the left and structured notes on the right">
      <h3>Offline Speech &amp; Recording Cleanup</h3>
      On-device streaming dictation (`X-ASR`), optional `SenseVoice-Small int8` two-pass refiner (with RAM-tier sizing and RTF circuit-breaker fallback), and local speaker diarization (`pyannote + CAM++`), producing live drafts, rich-format Markdown notes (GFM tables, Mermaid, ECharts, LaTeX), dual Edit/Preview editing, `/mnt/knowledge` CAS sync, and `1440px+` PNG/PDF export.
    </td>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/library-ask.webp" alt="Hybrid library search answer with numbered citations across documents, memory, and graph">
      <h3>Hybrid RAG Library &amp; Knowledge Graph</h3>
      Import PDF, Word, Markdown, and HTML files or mount folders with `read_only / read_write` approval tiers. Combines vector + keyword search, cross-session long-term memory, and entity knowledge graphs to generate cited answers jumping straight to highlighted passages.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/local-models.webp" alt="On-device GGUF model manager showing Main, Embeddings, and Reranker slots loaded with llama.cpp Vulkan">
      <h3>Model Routing &amp; On-Device GGUF</h3>
      Connect cloud providers and Coding Plan presets alongside `llama.cpp` GGUF inference managing three independent slots (`Main`, `Embedding`, `Rerank`) with automatic restart recovery and Tailscale private gateways in the unified picker.
    </td>
    <td width="50%" valign="top">
      <img src="assets/screenshots/en/production-storyboard.webp" alt="Short-film production panel displaying episode storyboard scenes, reference inputs, and Seedance generation plans">
      <h3>Canvas Prototypes, 3D Previs &amp; Film Panel</h3>
      Build clickable multi-screen web prototypes, generate or inpaint images via cloud APIs or ComfyUI with a 1×–5× / 90° swipeable lightbox gallery, and run scripts, dual-mode visual assets, locked-item approvals, offline `three.js` 3D storyboard previs (Beauty/Depth frame capture &amp; hardware MP4 export), and multi-channel video rough-cuts.
    </td>
  </tr>
</table>

## Security & Data Boundaries

- **Risk-based approval gates & catastrophic root-wipe hard-block**: In default **Approve by risk** mode, strictly parsed zero-side-effect read-only shell commands run directly while write/exec commands, file deletions, and outside-workspace writes prompt for confirmation; **No approvals** skips routine L1 actions while runtime/governance mutations (skills, agent config, ComfyUI/SSH/schedule configs) still require confirmation in both modes. Destructive root-wipe commands targeting `/`, `/sdcard`, `/storage/emulated/0`, app data roots, or unexpanded root variables are unconditionally blocked in both modes without an approval prompt.
- **Credential isolation from LLM context**: Passwords and tokens are entered via dedicated secret-input cards (`FLAG_SECURE` screenshot blocking enabled) straight into encrypted storage, never written into chat history or model prompts.
- **Android Keystore keychain**: API keys, SSH private keys, Git tokens, and site credentials are encrypted on device; plaintext reveal requires biometric or lockscreen verification, auto-hides after 30 seconds, and clears the clipboard after 60 seconds.
- **Local storage & direct requests**: Chat databases, RAG indexes, recordings, and preferences stay on the phone; LLM requests travel directly from the device to your configured endpoint with no intermediary proxy.
- **Encrypted backups**: Export encrypted local backup archives (including optional voiceprint and credential recovery) or sync via WebDAV.
- **Open-source license**: Released under GPL-3.0.

## Install & Build

### Prebuilt APK

1. Download the signed `.apk` and matching `.sha256` file from [GitHub Releases](https://github.com/Promenar/Infinity-Deck/releases/latest) or the [official download page](https://infinityware.work/en/download/).
2. Verify the SHA-256 checksum before installing (install only official Releases packages; avoid temporary CI artifacts or third-party repacks).
3. Launch the app, configure your cloud API keys in Settings, or download the built-in Linux environment, offline ASR models, and local GGUF models as needed.

- **Requirements**: Android 12 (API 31) or later. Built-in Linux and on-device GGUF inference require `arm64-v8a`; Alpine base occupies ~64 MB, or ~470 MB with the default toolchain (`git`, `ripgrep`, Python, Node.js, FFmpeg, each optional at install time).
- **App updates**: Check **Settings › About › Check for updates** to query the latest GitHub Release and open the download page; nothing downloads or installs silently in the background.
- **Current version**: `v0.75.0-beta` (`versionCode` `221`). Highlights: window-wide **Global Thick-Glass Light Infiltration** (`LocalAmbientLightField` + `ambientGlassInfiltration` with `235dp` physical distance falloff, 3-layer edge caustic / frosted micro-grain / subsurface bleed, phase-differentiated glow stronger for `APPROVAL` amber & `ERROR` red, automatic zero-bleed off under solid **Ambient light** power-saving mode, and renamed **Appearance › Interface effects** section with independent toggle); independent user-space **Built-in Proxy** (`libnxproxy.so`, zero system VPN, zero RAM when off, Tailscale P2P coexistence, YAML/Base64 subscriptions & share URIs, single-node & drag-to-reorder fallback list with edge auto-scroll, Advanced YAML editor with kernel validation, per-provider opt-in routing, automatic browser/Agent public routing, and Alpine Linux `$INFINITY_PROXY_*` on-demand injection); standalone zero-hardcoding **ComfyUI** universal submitter framework (`14` `comfy_*` tools) and bundled 16KB-aligned `arm64-v8a` **FFmpeg 9.1 / ffprobe** (`libx264`); unified real Android path model, **L1/L2** risk-tiered approvals with read-only allowlist & catastrophic root-wipe hard block, and Linux background job keep-alive hardening; unified 3-form **Chat Inspector** with swipeable tabs and sticky tab bars, full-site 4-step obsidian overlay system with zero-jump `LocalRowHeightGroup` isolation, four-section **Library › Files** workspace management, 3-tier hue-preserving color styles (**Soft / Standard / Bright**), unified `34dp` circular `+` buttons across Chat & Library, and 5-phase synchronized generation motion. Full version-by-version history is in [CHANGELOG.md](CHANGELOG.md) and [Releases](https://github.com/Promenar/Infinity-Deck/releases).

### Build from Source

Requires JDK 17+, Android SDK (API 36 / Build Tools 36.0.0 / NDK / CMake 3.22.1), `python3.14` on `PATH` (for precompiling the bundled Python runtime), and Go 1.21+ (for compiling the embedded Tailscale `libnxtailnet.so` and Built-in Proxy `libnxproxy.so` native binaries).

```bash
cd native-ui
./gradlew :app:assembleSideloadDebug
./gradlew :app:testSideloadDebugUnitTest :app:lintSideloadDebug
```

The debug APK is written to `native-ui/app/build/outputs/apk/sideload/debug/app-sideload-debug.apk`. Full build instructions, offline dependency checks, and release gates are documented in [Development and release verification](docs/guide/development.md) and [Distribution builds](docs/distribution-builds.md).

## Capabilities

**Chat & Model Layer**

- Connect OpenAI, Anthropic, Gemini, Vertex AI, DeepSeek, GLM, Kimi, Bailian, OpenRouter, SiliconFlow, xAI, and any compatible API with built-in Coding Plan presets (OpenCode Go/Zen, Bailian, Zhipu, Kimi, Ark) and fast protocol/endpoint variant switching
- On-device GGUF inference (`llama.cpp`): independent `MAIN`, `EMBEDDING`, and `RERANK` slots with restart auto-restore, `Q8_0` KV cache quantization, cross-turn Prefix KV reuse, and RAM-adaptive `16K / 8K / 4K` context sizing; loaded main model is injected directly into the session picker
- Switch models mid-session, run up to 16 concurrent generating chats, queue or steer follow-up messages during streaming, and edit previously sent user messages to regenerate or fork a branch session
- Original message search and pagination remain available even after automatic conversation compaction
- Unified obsidian frosted-glass chrome & 4-step overlay system with an unblurred ambient power-saving preset, 3-tier hue-preserving color styles (**Soft / Standard / Bright**), 5-phase synchronized generation motion, zero-jump floating notice capsules, and unified `34dp` circular `+` actions alongside 2 MiB rolling diagnostics and single-session trace export
- Structured question cards, foreground service keep-alive, and pocket touch-lock
- Skills & MCP server support with a built-in catalog installer, plus `.idpet` chat pet packs ([download](https://infinityware.work/en/download/#pets))

**Agents & Automation**

- Task plans with live step tracking
- Parallel sub-agent orchestration: up to 32 sub-agents per session (8 per batch) with rate-limit, offline, and timeout backoff retries
- Isolated branch sessions linked to dedicated Git worktrees
- Background execution queue for slow commands, daemons, image generation, and downloads (up to 32 per session) with completion wakeup
- One-shot timers and recurring Cron jobs

**Local Runtime & Code Tooling**

- One-tap rootless Alpine Linux (`arm64-v8a`) inside the app with cross-turn background keep-alive and `$INFINITY_PROXY_*` on-demand proxy variables, no Termux required
- Built-in shell, Python 3.14, QuickJS, and trimmed `arm64-v8a` FFmpeg 9.1 / ffprobe (`libx264`) sandboxes using unified real Android filesystem paths, L1/L2 risk-tiered approvals (zero-side-effect read-only allowlist + non-approvable catastrophic root-wipe hard block), and encrypted secret isolation
- Integrated Git client and visual inspector (branches, worktrees, diffs, commit log); file browser surfaces current branch and change counts
- Per-command Git credential env injection without touching repo config files
- Chunked reading of large text files up to 100 MiB

**Remote Hosts, Built-in Proxy & Private Networking**

- SSH command execution and SFTP transfers up to 4 GiB per file
- Embedded true terminal emulator with local port forwarding and manual session takeover
- Keychain supports generating Ed25519 / RSA 4096 keys or importing OpenSSH / PEM / PKCS8 keys (including passphrase-protected keys), batch public-key deployment, and pre-switch login checks
- Embedded Tailscale node (with self-hosted Headscale support) routing SSH, private model APIs, MCP, and the browser directly over `tailnet`
- Built-in multi-protocol user-space proxy (`libnxproxy.so`, zero system VPN, zero RAM when off) with loopback-only random HTTP/SOCKS5 auth, YAML/Base64 subscriptions & share-URI import, single-node & drag-to-reorder fallback modes, Advanced YAML editor with kernel validation, per-provider opt-in switches (fail-closed, default direct), automatic browser/Agent public routing, and Tailscale P2P coexistence

**Offline Recording & Audio Cleanup**

- On-device streaming ASR (`X-ASR` Chinese/English) with weighted custom hotwords, pause/resume, Android Quick Settings tile trigger, and 30-minute long-recording prompts
- Optional `SenseVoice-Small int8` two-pass refiner (237 MB) with device-tier strategy (`END_OF_UTTERANCE_AND_FALLBACK`, `PAUSE_REFINEMENT`, `STREAMING_ONLY`) and RTF > 0.65 circuit breaker
- On-device speaker diarization and local voiceprint library (included in encrypted backups) with timestamped audio scrubbing and inline speaker reassignment
- Real-time batch drafting during recording, selectable output languages (Chinese, English, Japanese, etc.), rich-format summaries with GFM tables, Mermaid diagrams, ECharts charts, and LaTeX math, inline Edit/Preview switching, and multi-select / search-result batch deletion
- Multi-format recording export (`PNG`, `PDF`, `MD`, `HTML`, `TXT` across Summary only, Both, or Transcript only) featuring unbounded `1440px+` canvas expansion for 1:1 single-PNG export and adaptive vector PDF pagination
- Authorized sessions accessing `/mnt/knowledge` can read/write recording Markdown documents (synced atomically via CAS back to the recording workbench) and read virtual `.wav` entries backed by the original audio files

**Library & Knowledge Graph**

- Four-section **Library › Files** workspace manager (**Built-in / App workspaces / External paths / Pinned**) with dual-entry obsidian context menus, one-step safe app-workspace cleanup, canonical path details, and PDF/Word/Markdown/HTML import or SAF / filesystem folder mounts
- Hybrid retrieval across vector embeddings, keyword search, cross-session active memory, and per-work entity knowledge graphs with clickable numbered citations
- Registered Android "Open with / Share" target for `.md` files with chunked rendering up to 16 MiB, Save As, Library import, and chat attachment support

**Browser, Canvas & Multimedia Production**

- Multi-tab built-in browser supporting mobile/desktop user-agent switching, direct `localhost` port access, `tailnet` URLs, automatic built-in proxy routing for public sites, agent observation, and manual login takeover
- Interactive multi-screen prototypes, canvas pages, and versioned document artifacts
- Cloud APIs and standalone zero-hardcoding **ComfyUI** universal submitter framework (`14` `comfy_*` tools, Linux / built-in Python dual-runtime auto-switching, `STATEFUL_CHAIN` branch isolation) with an integrated sketch & inpainting mask editor and 1×–5× / 90°-rotating swipeable lightbox gallery
- Production panel connecting scripts, dual-mode visual reference assets (`sheet` / `separate`), per-change approval gates for locked items, storyboards, multi-take generation, music/score versions, last-frame shot chaining, ComfyUI upscale/interpolation workflows, offline `three.js` 3D storyboard previs (camera/orbit views, 180-degree axis zone, Beauty/Depth PNG capture & hardware MP4 export), and on-device MP4 + SRT export
- Export any chat as standalone HTML, A4 PDF, or a long scrolling PNG

See the [feature guide](docs/guide/features.md) for detailed limits and specifications across modules.

## FAQ

**How is Infinity Deck different from API chat wrappers?**

Typical chat clients only assemble prompts and stream text. Infinity Deck embeds Alpine Linux, Git, SSH, a Tailscale userspace node, browser automation, hybrid RAG, and on-device speech/GGUF runtimes directly on the phone so agents can edit repositories, inspect servers, transcribe audio, and build artifacts under your approval.

**How does it differ from desktop remote-control mobile apps?**

Remote-control apps turn the phone into a thin viewport while requiring a desktop host to remain awake and reachable; pure cloud sandboxes cannot see your home LAN or local hardware. Infinity Deck runs locally offline or on the move, and bridges directly into your home GPU/NAS/servers over Tailscale and SSH whenever they are online.

**Does the built-in Linux require root or Termux?**

No. Alpine Linux deploys in one tap as an unprivileged `arm64-v8a` userspace environment inside the app. External Termux remains optional.

**Which languages are supported in documentation?**

Both `README.md` / `README.zh-CN.md` and the app UI support English and Simplified Chinese. Detailed architecture notes and deep-dive guides under `docs/guide/` are authored in Simplified Chinese; translation PRs are welcome.

## Documentation

| Document | Contents |
|---|---|
| [Features](docs/guide/features.md) | Chat & rendering, context management, library & knowledge graphs, agent tools, sub-agents, browser, background & cron jobs, production panel, export & backup |
| [Security & Privacy](docs/guide/security-privacy.md) | Keychain encryption, backup encryption, SSH/Git/Tailscale credential isolation, network security, diagnostic events |
| [Execution Environments](docs/guide/execution-environments.md) | Built-in Alpine Linux, Termux integration, isolated script sandboxes, SSH |
| [Workspaces & External Folders](docs/guide/workspaces.md) | Workspace modes, Git panel, agent reachability boundaries, external folder mounting |
| [Install, Upgrade & Migration](docs/guide/install-and-upgrade.md) | System requirements, data migration, DB schema chain, APK verification |
| [Development & Release Verification](docs/guide/development.md) | Build commands, bundled Python & Tailscale packaging, rich-content security, icon assets, tech stack |

See also [Architecture Overview](docs/ARCHITECTURE.md), [Model Catalog Spec](docs/model-catalog.md), [Distribution Builds](docs/distribution-builds.md), and the [Guide Index](docs/guide/README.md).

## Contributing

Bug reports and feature proposals are welcome via GitHub Issues. Before submitting a Pull Request, please read [AGENTS.md](AGENTS.md) to review repository coding standards, unit tests, screenshot baselines, and device gate requirements; for large architectural or UI changes, open an issue first to align on design.

## License

Infinity Deck is licensed under the [GNU General Public License v3.0](LICENSE). Settings lists, navigation bars, and theme appearance screens include Compose adaptations from [Bettbox](https://github.com/appshubcc/Bettbox) (also GPL-3.0); agent avatar icons are sourced from Google Material Symbols Rounded (Apache-2.0). Full attribution and scope are listed in [THIRD_PARTY_NOTICES.md](docs/legal/THIRD_PARTY_NOTICES.md).
