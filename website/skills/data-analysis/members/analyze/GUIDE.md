---
name: analyze
description: Answer data questions -- from quick lookups to full analyses. Use when looking up a single metric, investigating what's driving a trend or drop, comparing segments over time, or preparing a formal data report for stakeholders.
---

# analyze - Answer Data Questions

> **本应用适配（Infinity Deck）**：本技能不连接数据仓库或 MCP 连接器。数据来自用户提供的 CSV、Excel、JSON 或数据库导出文件（工作区中用 `read_file` 预览），或用户粘贴的查询结果。计算优先用 `python_exec` 的标准库（`csv`、`statistics`、`sqlite3`），两个渠道都可用；pandas、numpy、scipy、matplotlib、seaborn、plotly 只在侧载版 Linux 会话中可用，详见根目录 `SKILL.md`。

Answer a data question, from a quick lookup to a full analysis to a formal report.

## Workflow

### 1. Understand the Question

Parse the user's question and determine:

- **Complexity level**:
  - **Quick answer**: Single metric, simple filter, factual lookup (e.g., "How many users signed up last week?")
  - **Full analysis**: Multi-dimensional exploration, trend analysis, comparison (e.g., "What's driving the drop in conversion rate?")
  - **Formal report**: Comprehensive investigation with methodology, caveats, and recommendations (e.g., "Prepare a quarterly business review of our subscription metrics")
- **Data requirements**: Which tables, metrics, dimensions, and time ranges are needed
- **Output format**: Number, table, chart, narrative, or combination

### 2. Gather Data

**If the user has provided a data file (CSV, Excel, JSON, database export) in the workspace:**

1. Locate it with `list_files` and preview the header and first rows with `read_file`
2. Load it with `python_exec` — for SQL-style work, import the rows into an in-memory `sqlite3` database and query it
3. If a query fails, debug and retry (check column names, types, and SQLite syntax)
4. If results look unexpected, run sanity checks before proceeding

**If no data file is available:**

1. Ask the user to provide data in one of these ways:
   - Paste query results directly
   - Save a CSV or Excel file to the workspace
   - Describe the schema so you can write queries for them to run in their own database
2. If writing queries for manual execution, use the `sql-queries` member for dialect-specific best practices
3. Once data is provided, proceed with analysis

### 3. Analyze

- Calculate relevant metrics, aggregations, and comparisons
- Identify patterns, trends, outliers, and anomalies
- Compare across dimensions (time periods, segments, categories)
- For complex analyses, break the problem into sub-questions and address each

### 4. Validate Before Presenting

Before sharing results, run through validation checks:

- **Row count sanity**: Does the number of records make sense?
- **Null check**: Are there unexpected nulls that could skew results?
- **Magnitude check**: Are the numbers in a reasonable range?
- **Trend continuity**: Do time series have unexpected gaps?
- **Aggregation logic**: Do subtotals sum to totals correctly?

If any check raises concerns, investigate and note caveats.

### 5. Present Findings

**For quick answers:**
- State the answer directly with relevant context
- Include the query used (collapsed or in a code block) for reproducibility

**For full analyses:**
- Lead with the key finding or insight
- Support with data tables and/or visualizations
- Note methodology and any caveats
- Suggest follow-up questions

**For formal reports:**
- Executive summary with key takeaways
- Methodology section explaining approach and data sources
- Detailed findings with supporting evidence
- Caveats, limitations, and data quality notes
- Recommendations and suggested next steps

### 6. Visualize Where Helpful

When a chart would communicate results more effectively than a table:

- Use the `data-visualization` member to select the right chart type
- Render it as an ```echarts code block in the reply, or build it into a canvas dashboard (`build-dashboard` member); matplotlib PNGs are available in sideload Linux sessions
- Follow visualization best practices for clarity and accuracy

## Examples

**Quick answer:**
```
How many new users signed up in December? (signups.csv)
```

**Full analysis:**
```
What's causing the increase in support ticket volume over the past 3 months? Break down by category and priority.
```

**Formal report:**
```
Prepare a data quality assessment of our customer export -- completeness, consistency, and any issues we should address.
```

## Tips

- Be specific about time ranges, segments, or metrics when possible
- If you know the file or column names, mention them to speed up the process
- For complex questions, the agent may break them into multiple queries
- Results are always validated before presentation -- if something looks off, the agent will flag it
