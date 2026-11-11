# NOTICE

本技能包含改编自第三方开源项目的文件。

## 上游来源

- 仓库：https://github.com/anthropics/knowledge-work-plugins
- 提交：8444efcd48f7012f09797778a36a33e73d0861f4
- 许可：Apache License 2.0（全文见各成员目录的 `LICENSE.txt`，与上游仓库根目录 `LICENSE` 一致）

| 成员目录 | 上游路径 | 许可 |
| --- | --- | --- |
| `members/variance-analysis` | `finance/skills/variance-analysis/SKILL.md` | Apache-2.0 |
| `members/financial-statements` | `finance/skills/financial-statements/SKILL.md` | Apache-2.0 |
| `members/competitive-brief` | `product-management/skills/competitive-brief/SKILL.md` | Apache-2.0 |
| `members/synthesize-research` | `product-management/skills/synthesize-research/SKILL.md` | Apache-2.0 |

## 修改说明

本目录文件经 Infinity Deck 修改以适配移动端应用内 Agent：

- 各成员 `SKILL.md` 更名为 `GUIDE.md`，由根 `SKILL.md` 统一路由；根 `SKILL.md` 为 Infinity Deck 新写。
- 删除上游连接器说明（`CONNECTORS.md`）及正文中对 ERP、数据仓库、知识库、聊天、用户反馈、产品分析、会议转写等连接器的引用；删除斜杠命令用法与 `argument-hint` 字段；把对不存在的 `/flux` 命令的引用改为本包的 `variance-analysis` 成员。
- 在各成员 `GUIDE.md` 开头新增“本应用适配”一节，取数方式改为用户提供的文件、联网检索与用户资料库检索。
- `variance-analysis` 新增 ECharts 瀑布图写法。
- 其余正文保留上游英文原文。
