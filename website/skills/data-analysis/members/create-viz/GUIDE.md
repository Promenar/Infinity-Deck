---
name: create-viz
description: Create publication-quality visualizations with Python. Use when turning query results or a DataFrame into a chart, selecting the right chart type for a trend or comparison, generating a plot for a report or presentation, or needing an interactive chart with hover and zoom.
---

# create-viz - Create Visualizations

> **本应用适配（Infinity Deck）**：本技能不连接数据仓库或 MCP 连接器。数据来自用户提供的 CSV、Excel、JSON 或数据库导出文件（工作区中用 `read_file` 预览），或用户粘贴的查询结果。计算优先用 `python_exec` 的标准库（`csv`、`statistics`、`sqlite3`），两个渠道都可用；pandas、numpy、scipy、matplotlib、seaborn、plotly 只在侧载版 Linux 会话中可用，详见根目录 `SKILL.md`。
>
> 出图方式：默认在回复中输出 ```echarts 代码块（ECharts option JSON），两个渠道都可用，可交互；需要 PNG 文件或上游的 matplotlib/seaborn/plotly 代码时，仅限侧载版 Linux 会话，生成后保存到工作区，用 `view_media` 自查再用 `artifact_publish` 交付。下文第 4 步的 Python 代码适用于后一种情况，第 3、5 步的选图与设计原则对 ECharts 同样适用。

Create publication-quality data visualizations using Python. Generates charts from data with best practices for clarity, accuracy, and design.

## Workflow

### 1. Understand the Request

Determine:

- **Data source**: Query results, pasted data, CSV/Excel file, or data to be queried
- **Chart type**: Explicitly requested or needs to be recommended
- **Purpose**: Exploration, presentation, report, dashboard component
- **Audience**: Technical team, executives, external stakeholders

### 2. Get the Data

**If data is in a workspace file:**
1. Preview it with `read_file`, then load and aggregate it with `python_exec` (standard library or `sqlite3`; pandas only in sideload Linux sessions)
2. Clean and prepare as needed (type conversions, null handling)

**If data is pasted:**
1. Parse the pasted values
2. Clean and prepare as needed

**If data is from a previous analysis in the conversation:**
1. Reference the existing data

### 3. Select Chart Type

If the user didn't specify a chart type, recommend one based on the data and question:

| Data Relationship | Recommended Chart |
|---|---|
| Trend over time | Line chart |
| Comparison across categories | Bar chart (horizontal if many categories) |
| Part-to-whole composition | Stacked bar or area chart (avoid pie charts unless <6 categories) |
| Distribution of values | Histogram or box plot |
| Correlation between two variables | Scatter plot |
| Two-variable comparison over time | Dual-axis line or grouped bar |
| Geographic data | Choropleth map |
| Ranking | Horizontal bar chart |
| Flow or process | Sankey diagram |
| Matrix of relationships | Heatmap |

Explain the recommendation briefly if the user didn't specify.

### 4. Generate the Visualization

Pick the output based on the need:

- **ECharts code block**: Default in this app. Interactive, rendered directly in the reply, works on both channels. Put the aggregated data into the option JSON and follow the design rules in step 5.
- **matplotlib + seaborn** (sideload Linux session only): Static, publication-quality PNG files.
- **plotly** (sideload Linux session only): Standalone interactive HTML files.

**Code requirements:**

```python
import matplotlib.pyplot as plt
import seaborn as sns
import pandas as pd

# Set professional style
plt.style.use('seaborn-v0_8-whitegrid')
sns.set_palette("husl")

# Create figure with appropriate size
fig, ax = plt.subplots(figsize=(10, 6))

# [chart-specific code]

# Always include:
ax.set_title('Clear, Descriptive Title', fontsize=14, fontweight='bold')
ax.set_xlabel('X-Axis Label', fontsize=11)
ax.set_ylabel('Y-Axis Label', fontsize=11)

# Format numbers appropriately
# - Percentages: '45.2%' not '0.452'
# - Currency: '$1.2M' not '1200000'
# - Large numbers: '2.3K' or '1.5M' not '2300' or '1500000'

# Remove chart junk
ax.spines['top'].set_visible(False)
ax.spines['right'].set_visible(False)

plt.tight_layout()
plt.savefig('chart_name.png', dpi=150, bbox_inches='tight')
```

### 5. Apply Design Best Practices

**Color:**
- Use a consistent, colorblind-friendly palette
- Use color meaningfully (not decoratively)
- Highlight the key data point or trend with a contrasting color
- Grey out less important reference data

**Typography:**
- Descriptive title that states the insight, not just the metric (e.g., "Revenue grew 23% YoY" not "Revenue by Month")
- Readable axis labels (not rotated 90 degrees if avoidable)
- Data labels on key points when they add clarity

**Layout:**
- Appropriate whitespace and margins
- Legend placement that doesn't obscure data
- Sorted categories by value (not alphabetically) unless there's a natural order

**Accuracy:**
- Y-axis starts at zero for bar charts
- No misleading axis breaks without clear notation
- Consistent scales when comparing panels
- Appropriate precision (don't show 10 decimal places)

### 6. Save and Present

1. For ECharts, the code block in the reply is the chart; for files, save the PNG/HTML with a descriptive name in the workspace, check it with `view_media` (PNG), and deliver it with `artifact_publish`
2. Provide the option JSON or code used so they can modify it
3. Suggest variations (different chart type, different grouping, zoomed time range)

## Examples

```
Show monthly revenue for the last 12 months as a line chart with the trend highlighted
```

```
Here's our NPS data by product: [pastes data]. Create a horizontal bar chart ranking products by score.
```

```
Use orders.csv and create a heatmap of order volume by day-of-week and hour
```

## Tips

- ECharts charts in the reply already support hover and zoom; ask for a PNG file if you need a static image (sideload Linux session)
- Specify "presentation" if you need larger fonts and higher contrast
- You can request multiple charts at once (e.g., "create a 2x2 grid of charts showing...")
- Chart files are saved to the workspace and delivered as artifacts
