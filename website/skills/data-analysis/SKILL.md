---
name: data-analysis
description: 数据分析合并包：回答数据问题与指标查询、趋势归因与分组对比、正式数据报告；探查与校验数据集质量，统计分析与异常检测，编写和转换 SQL（含用 SQLite 直接查询 CSV），生成图表与可筛选的交互式看板，分享前做方法与结论复核。数据来自用户提供的 CSV、Excel、JSON 或数据库导出文件。Data analysis, explore dataset, data quality, statistics, SQL, charts, dashboard, validate analysis.
license: Apache-2.0
---

# 数据分析合并包

本技能把多个上游数据分析指南合并为一个入口。先按下表选出与任务匹配的成员，再用 `read_skill_file`（`name` 传 `data-analysis`，`path` 传表中路径）读取对应 `GUIDE.md`，只加载需要的成员。成员指南的主体为上游英文原文，开头的“本应用适配”一节优先于正文中与之冲突的说法。

| 成员 | 适用场景 | 指南 |
| :--- | :--- | :--- |
| `analyze` | 回答数据问题：单个指标查询、趋势或下滑归因、分群对比、正式数据报告 | `members/analyze/GUIDE.md` |
| `explore-data` | 初次拿到表或文件：结构、空值率、分布、重复与可疑值、推荐分析维度 | `members/explore-data/GUIDE.md` |
| `validate-data` | 分享前复核：方法、口径、计算抽查、常见分析陷阱、结论是否站得住 | `members/validate-data/GUIDE.md` |
| `statistical-analysis` | 描述统计、趋势与季节性、异常检测、假设检验、相关与因果的谨慎表述 | `members/statistical-analysis/GUIDE.md` |
| `sql-queries` | 编写与优化 SQL、方言转换（SQLite、PostgreSQL、Snowflake、BigQuery 等）、窗口函数、留存与漏斗 | `members/sql-queries/GUIDE.md` |
| `create-viz` | 把数据做成一张图：选图、出图、设计要点 | `members/create-viz/GUIDE.md` |
| `data-visualization` | 图表选型参考、配色与无障碍、Python 绘图代码模式 | `members/data-visualization/GUIDE.md` |
| `build-dashboard` | 多图表、筛选器、KPI 卡片与明细表组成的交互式看板 | `members/build-dashboard/GUIDE.md` |

成员之间互相引用时（例如“use the `sql-queries` skill”），指本包内的同名成员。图表叙事、看板版式与图表评审可另用 `data-storytelling` 技能。

## 本应用的数据处理方式

本技能不连接数据仓库或第三方 SaaS。上游指南中“connected data warehouse / MCP server”的分支一律不适用，按下列方式取得与处理数据：

1. **获取数据**：用户提供的 CSV、TSV、JSON、Excel 或数据库导出文件，放在工作区中。用 `list_files` 定位、`read_file` 预览前几十行；用户把查询结果粘贴在对话里也可以。用户只描述了表结构时，为其编写 SQL，由用户在自己的数据库中运行后把结果交回。
2. **计算**（两个渠道都可用）：用 `python_exec` 运行内置 Python 标准库代码：
   - `csv`、`json` 读取文件，`statistics` 计算均值、中位数、标准差、分位数，`collections.Counter` 统计频次；
   - `sqlite3` 把 CSV 导入内存数据库后用 SQL 分组、连接、窗口函数（SQLite 3.25 起支持），适合按 `sql-queries` 成员的写法分析；
   - 文件路径相对工作区根，或用 `os.environ['INFINITY_WORKSPACE']` 拼接。
3. **Excel 文件**：侧载版可以先用 `shell_exec` 运行 `pip install openpyxl`（纯 Python 包）后读取 `.xlsx`；Play 版请用户把工作表另存为 CSV，或用标准库 `zipfile` + `xml.etree` 解析简单表格。
4. **pandas、numpy、scipy、matplotlib、seaborn、plotly**（上游代码示例使用的库）：只在**侧载版的 Linux 会话**可用。先用 `shell_exec` 安装：`apk add py3-pandas py3-numpy py3-scipy py3-matplotlib`，seaborn、plotly 在 `python3 -m venv --system-site-packages` 创建的虚拟环境中用 pip 安装。内置 Python 与 Play 版不能安装这些带原生扩展的包；此时把上游的 pandas 写法改写为标准库或 SQLite 实现，结果相同。
5. **图表**（两个渠道都可用）：
   - 对话中直接输出 ```echarts 代码块（内容为 ECharts option JSON），应用会渲染为可交互图表；
   - 流程或结构图用 ```mermaid 代码块；
   - 侧载版 Linux 会话可用 matplotlib 生成 PNG，保存到工作区后用 `view_media` 自查，再用 `artifact_publish` 交付。
6. **看板与交互页面**：用 `canvas_create` 创建画布，之后用 `canvas_update` 修改。画布不能联网：不能加载 CDN 脚本、外部字体或图片，图表用内联 SVG 或 Canvas 2D 绘制，图片与字体只能用 data: URI；页面需在 320–420 CSS px 宽的手机视口内可读。细节见 `build-dashboard` 成员的适配说明。
7. **交付**：报告写成 Markdown 文件、数据表另存 CSV，用 `artifact_publish` 交付；对话中同时给出关键结论。

`shell_exec`、`python_exec` 调用可能需要用户审批，被拒绝时不要重复调用，改为说明需要的操作。

## 来源与许可

成员指南改编自 anthropics/knowledge-work-plugins（提交 8444efcd48f7012f09797778a36a33e73d0861f4），按 Apache-2.0 许可分发，每个成员目录保留上游 `LICENSE.txt`。修改说明见 `NOTICE.md`。
