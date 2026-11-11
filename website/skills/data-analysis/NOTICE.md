# NOTICE

本技能包含改编自第三方开源项目的文件。

## 上游来源

- 仓库：https://github.com/anthropics/knowledge-work-plugins
- 提交：8444efcd48f7012f09797778a36a33e73d0861f4
- 许可：Apache License 2.0（全文见各成员目录的 `LICENSE.txt`，与上游仓库根目录 `LICENSE` 一致）

| 成员目录 | 上游路径 | 许可 |
| --- | --- | --- |
| `members/analyze` | `data/skills/analyze/SKILL.md` | Apache-2.0 |
| `members/explore-data` | `data/skills/explore-data/SKILL.md` | Apache-2.0 |
| `members/validate-data` | `data/skills/validate-data/SKILL.md` | Apache-2.0 |
| `members/statistical-analysis` | `data/skills/statistical-analysis/SKILL.md` | Apache-2.0 |
| `members/sql-queries` | `data/skills/sql-queries/SKILL.md` | Apache-2.0 |
| `members/create-viz` | `data/skills/create-viz/SKILL.md` | Apache-2.0 |
| `members/data-visualization` | `data/skills/data-visualization/SKILL.md` | Apache-2.0 |
| `members/build-dashboard` | `data/skills/build-dashboard/SKILL.md` | Apache-2.0 |

## 修改说明

本目录文件经 Infinity Deck 修改以适配移动端应用内 Agent：

- 各成员 `SKILL.md` 更名为 `GUIDE.md`，由根 `SKILL.md` 统一路由；根 `SKILL.md` 为 Infinity Deck 新写。
- 删除上游连接器说明（`CONNECTORS.md`）及正文中对数据仓库、笔记本、产品分析等 MCP 连接器的引用；删除斜杠命令用法示例与 `argument-hint`、`user-invocable` 等桌面端字段。
- 在各成员 `GUIDE.md` 开头新增“本应用适配”一节，并改写取数步骤：数据来自用户提供的文件，计算用内置 Python 标准库或 SQLite，第三方 Python 库限侧载版 Linux 会话，图表用 ECharts 代码块或离线画布。
- `build-dashboard` 补充画布不能联网时的绘图方式；上游 Chart.js 模板保留为导出 HTML 文件的写法。
- 其余正文保留上游英文原文。
