# NOTICE

本技能包含改编自第三方开源项目的文件。

## 上游来源

- 仓库：https://github.com/oh-da/dataink
- 提交：f1b0039a928409a9ad62c33d908cb246ff62afad
- 许可：MIT License，Copyright (c) 2026 Ohad Dahan（全文见技能根目录及各成员目录的 `LICENSE.txt`，与上游仓库根目录 `LICENSE` 一致）

| 本技能路径 | 上游路径 | 许可 |
| --- | --- | --- |
| `members/dashboard-design` | `skills/dashboard-design/` | MIT |
| `members/data-storyteller` | `skills/data-storyteller/` | MIT |
| `members/data-visualization` | `skills/data-visualization/` | MIT |
| `members/infographic-creator` | `skills/infographic-creator/` | MIT |
| `members/table-design` | `skills/table-design/` | MIT |
| `members/visualization-review` | `skills/visualization-review/` | MIT |
| `assets/` | `assets/` | MIT |
| `scripts/` | `scripts/` | MIT |

## 修改说明

本目录文件经 Infinity Deck 修改以适配移动端应用内 Agent：

- 各成员 `SKILL.md` 更名为 `GUIDE.md`，由根 `SKILL.md` 统一路由；根 `SKILL.md` 为 Infinity Deck 新写。
- 删除成员 frontmatter 中的 `allowed-tools`；把 `${CLAUDE_PLUGIN_ROOT}` 脚本路径改为应用的 `$INFINITY_SKILLS/data-storytelling`，`$ARGUMENTS` 改为对话中的用户请求；把对其它插件技能的称呼改为本包成员。
- 在 `code-recipes.md` 新增 ECharts 主题写法，并注明 matplotlib、plotly 需要侧载版 Linux 会话。
- 在看板、信息图、表格、评审成员的输出步骤补充应用画布（离线、手机视口）与图片取色方式的说明。
- `brand-colors.md`、`brand-fonts.md` 的占位说明改为以对话中提供的品牌信息为准；`core-standards.md` 的品牌流程同步调整。
- `scripts/` 下的三个检查脚本未修改。
- 其余正文保留上游英文原文。
