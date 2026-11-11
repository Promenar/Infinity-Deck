# NOTICE

本技能包含改编自第三方开源项目的文件。

## 上游来源

- 仓库：https://github.com/anthropics/claude-plugins-official
- 提交：d4226d062928f8d9505dbdeadd10217d23361052
- 许可：Apache License 2.0（全文见各成员目录的 `LICENSE.txt`，与上游 `plugins/mcp-server-dev/LICENSE` 一致）

| 成员目录 | 上游路径 | 许可 |
| --- | --- | --- |
| `members/build-mcp-server` | `plugins/mcp-server-dev/skills/build-mcp-server/` | Apache-2.0 |
| `members/build-mcp-app` | `plugins/mcp-server-dev/skills/build-mcp-app/` | Apache-2.0 |
| `members/build-mcpb` | `plugins/mcp-server-dev/skills/build-mcpb/` | Apache-2.0 |

## 修改说明

本目录文件经 Infinity Deck 修改以适配移动端应用内 Agent：

- 各成员 `SKILL.md` 更名为 `GUIDE.md`，由根 `SKILL.md` 统一路由；根 `SKILL.md` 为 Infinity Deck 新写，包含应用内开发环境、联调步骤与本应用 MCP 客户端的兼容要求。
- 删除成员 frontmatter 的 `version` 字段；在各成员 `GUIDE.md` 开头新增“本应用适配”一节。
- `build-mcp-server/GUIDE.md`：宿主文档加载改为按目标宿主进行；部署模型问题新增 Infinity Deck 用户一项；测试阶段增加在本应用中联调的入口。
- `build-mcp-server/references/remote-http-scaffold.md`：用户接入表新增 Infinity Deck 一行；注明 Inspector 交互界面在应用的 Linux 会话中不可用。
- 其余正文与参考文件保留上游英文原文。
