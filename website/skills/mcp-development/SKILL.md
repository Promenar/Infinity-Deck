---
name: mcp-development
description: MCP 开发合并包：设计和构建 Model Context Protocol 服务器，选择部署模型（远程 HTTP、本地 stdio、MCPB）与工具设计模式，处理鉴权、资源与提示词、elicitation；为 MCP 工具添加内嵌交互组件（MCP Apps）；把本地 MCP 服务器连同运行时打包为 .mcpb；并在本应用中联调服务器。需要侧载版 Linux 会话运行代码。用于“做一个 MCP 服务器”“把某个 API 包装成 MCP 工具”“MCP 应用/组件”“打包 MCPB”。MCP server, MCP tools, MCP apps, MCPB bundle, Model Context Protocol.
license: Apache-2.0
---

# MCP 开发合并包

本技能把多个上游 MCP 开发指南合并为一个入口。先按下表选出成员，再用 `read_skill_file`（`name` 传 `mcp-development`，`path` 传表中路径）读取对应 `GUIDE.md`。成员正文中的相对路径以成员目录为基准，例如 `build-mcp-server` 中的 `references/auth.md` 即 `members/build-mcp-server/references/auth.md`。成员指南为上游英文原文，开头的“本应用适配”一节优先于正文中与之冲突的说法。

| 成员 | 适用场景 | 指南 |
| :--- | :--- | :--- |
| `build-mcp-server` | 入口：梳理用途、选择部署模型（远程 HTTP / MCPB / 本地 stdio）与工具设计模式、框架选型、脚手架、鉴权、资源与提示词、elicitation、部署 | `members/build-mcp-server/GUIDE.md` |
| `build-mcp-app` | 为工具添加在聊天中渲染的交互组件（表单、选择器、看板、确认框） | `members/build-mcp-app/GUIDE.md` |
| `build-mcpb` | 把本地 MCP 服务器连同 Node/Python 运行时打包为 `.mcpb` 供桌面宿主安装 | `members/build-mcpb/GUIDE.md` |

上游内容以 Claude 宿主为主要目标（Claude Desktop、Claude Code、claude.ai 的连接器目录）。这些说法描述的是用户服务器的目标宿主，按用户的目标取舍；目标是 Infinity Deck 时以下文的兼容要求为准。

## 开发环境

- 写代码、设计工具与 schema：两个渠道都可以，用文件工具写入工作区。
- 安装依赖、运行与测试：需要**侧载版的 Linux 会话**。内置 Python 只能安装纯 Python 包，官方 Python SDK 与 FastMCP 依赖的 pydantic-core 等原生扩展无法使用；Play 版没有 Linux 环境。Play 版用户可以拿到代码后在自己的电脑或服务器上运行。
- 在 Linux 会话中用 `shell_exec` 安装运行时（需要用户审批与联网）：
  - TypeScript：`apk add nodejs npm`，之后按成员指南 `npm install`；
  - Python：`apk add python3 py3-pip`，用 `python3 -m venv .venv` 创建虚拟环境后 `pip install`；某个依赖编译失败时用 `apk search py3-<包名>` 找系统包，并改用 `--system-site-packages` 创建虚拟环境。
- 需要一直运行的服务器用 `shell_exec` 的 `background: true` 启动，主命令保持前台运行（例如 `npx tsx src/server.ts`），不要用 `nohup` 或 `&`；用返回的后台任务号查看日志与状态。
- 部署到云平台（例如 Cloudflare Workers 的 `wrangler deploy`）需要用户自己的账号登录；登录、令牌等秘密由用户在其账号中操作，不要让用户把令牌贴进对话。

## 在本应用中联调

1. 服务器监听本机回环地址，例如 `http://127.0.0.1:3000/mcp`，以后台任务运行。
2. 用 curl 模拟本应用的请求（Linux 会话中先 `apk add curl`）：

```sh
curl -sS http://127.0.0.1:3000/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/list' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientInfo":{"name":"Infinity Deck","version":"test"},"io.modelcontextprotocol/clientCapabilities":{}}}}'
```

   调用工具时把 `Mcp-Method` 改为 `tools/call`，加 `Mcp-Name: <工具名>` 请求头，`params` 中加入 `"name"` 与 `"arguments"`。
3. 两个请求都返回正确结果后，请用户在 设置 → 工具设置 → 添加 MCP 服务器 中填入该 URL；需要鉴权时配置 Bearer 令牌。
4. 在新对话中让模型调用工具，确认结果。回环地址上的 HTTP 服务器工具按进程类操作处理，调用时可能需要用户审批。

## 本应用 MCP 客户端的兼容要求

以下是 Infinity Deck 连接 MCP 服务器的方式，目标宿主包含本应用时，服务器须满足：

- **传输**：只连接 HTTP(S) URL（HTTPS、普通 HTTP 或本机回环），不启动 stdio 服务器、不安装 MCPB。URL 不能包含用户名密码或 `#` 片段；非 HTTPS 地址不跟随重定向。
- **无状态请求**：每次调用都是独立的 POST，请求头带 `MCP-Protocol-Version: 2026-07-28`，客户端信息放在 `params._meta` 中；不发送 `initialize`，也不携带会话 ID。服务器必须能在没有 `initialize` 的情况下直接响应 `tools/list` 与 `tools/call`，并接受该协议版本。使用 SDK 时选择无状态模式，并用上面的 curl 请求验证；所用 SDK 版本不接受该协议版本时，本应用无法连接，需要升级 SDK 或改用其它宿主测试。
- **方法**：只使用 `tools/list`（支持 `nextCursor` 分页）与 `tools/call`。resources、prompts、sampling、elicitation 与 MCP Apps 组件（`_meta.ui`）不被使用；工具结果必须在文本内容中给出完整、可读的信息。
- **响应**：`application/json` 或 `text/event-stream`。SSE 响应总量不超过 2 MiB、事件不超过 1000 个、单个事件不超过 256 KiB。
- **工具定义**：工具名匹配 `[A-Za-z0-9_.:/-]{1,128}` 且不重复；每个工具必须提供对象类型的 `inputSchema`。
- **错误**：`isError: true` 的结果作为工具错误反馈给模型；要求用户中途输入的结果不被支持。
- **鉴权**：通过设置中配置的 Bearer 令牌（`Authorization: Bearer ...`）。

## 来源与许可

成员指南改编自 anthropics/claude-plugins-official（提交 d4226d062928f8d9505dbdeadd10217d23361052，路径 `plugins/mcp-server-dev/skills/`），按 Apache-2.0 许可分发，每个成员目录保留上游 `LICENSE.txt`。修改说明见 `NOTICE.md`。
