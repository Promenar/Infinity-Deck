---
name: business-analysis
description: 经营分析与市场调研合并包：预算对实际、同比环比的差异分解（价格、销量、结构）与瀑布图；编制利润表、资产负债表、现金流量表并标注重大差异；竞品分析简报（定位、定价、功能、市场策略）；把访谈、问卷、用户反馈综合为结构化洞察与建议。用于经营分析报告、财务汇报、竞品与市场调研、用户研究归纳。Variance analysis, budget vs actual, financial statements, P&L, competitive brief, user research synthesis.
license: Apache-2.0
---

# 经营分析与市场调研合并包

本技能把多个上游指南合并为一个入口。先按下表选出与任务匹配的成员，再用 `read_skill_file`（`name` 传 `business-analysis`，`path` 传表中路径）读取对应 `GUIDE.md`，只加载需要的成员。成员指南的主体为上游英文原文，开头的“本应用适配”一节优先于正文中与之冲突的说法。

| 成员 | 适用场景 | 指南 |
| :--- | :--- | :--- |
| `variance-analysis` | 预算对实际、环比同比的差异分解（价格/销量、费率/结构、人员、费用类别），重要性阈值，差异说明文字，瀑布图 | `members/variance-analysis/GUIDE.md` |
| `financial-statements` | 编制利润表、资产负债表、现金流量表，期间对比与重大差异标注，GAAP 列报要求与期末调整 | `members/financial-statements/GUIDE.md` |
| `competitive-brief` | 竞品或功能领域分析：定位、定价、功能对比矩阵、赢单输单分析、市场趋势与战略含义 | `members/competitive-brief/GUIDE.md` |
| `synthesize-research` | 把访谈记录、问卷、工单和反馈综合为主题、洞察、用户画像与机会评估 | `members/synthesize-research/GUIDE.md` |

成员之间互相引用时，指本包内的同名成员。

## 本应用的工作方式

本技能不连接 ERP、数据仓库、知识库、聊天、邮件或产品分析等连接器。上游指南中“~~类别 is connected”的分支一律不适用，改为：

- **财务与经营数据**：用户粘贴的数据，或工作区中的 CSV/Excel 文件。用 `read_file` 预览，用 `python_exec`（标准库 `csv`、`decimal`、`sqlite3`）计算，两个渠道都可用；金额核对也可用 `calculator`。侧载版可用 `shell_exec` 运行 `pip install openpyxl` 读取 `.xlsx`；Play 版请用户另存为 CSV。
- **公开信息**：`web_search` 找来源，`web_fetch` 阅读原文；记录链接与发布日期，结论以读过的原文为依据。
- **用户内部资料**：`knowledge_search` 检索用户资料库，或请用户提供文件。
- **图表**：在回复中输出 ```echarts 代码块（ECharts option JSON）；瀑布图写法见 `variance-analysis` 成员。
- **交付**：报告写入工作区 Markdown 文件，数据表另存 CSV，用 `artifact_publish` 交付。

财务相关成员只协助整理与分析，不构成财务、会计或投资建议；对外报告或申报前须由具备资质的专业人员复核，这一点要告知用户。

## 来源与许可

成员指南改编自 anthropics/knowledge-work-plugins（提交 8444efcd48f7012f09797778a36a33e73d0861f4），按 Apache-2.0 许可分发，每个成员目录保留上游 `LICENSE.txt`。修改说明见 `NOTICE.md`。
