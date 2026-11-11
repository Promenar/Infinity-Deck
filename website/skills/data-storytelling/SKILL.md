---
name: data-storytelling
description: 数据叙事与图表设计合并包（基于 Tufte、Few、Knaflic 方法论）：为报告、汇报材料和经营分析设计叙事结构与行动标题，选择和优化图表，设计 KPI 看板布局、数据表格与信息图，并按可访问性、数据诚信和清晰度规则评审已有图表，附对比度与色盲安全配色检查脚本。用于“这份汇报怎么讲”“该用什么图”“看板怎么排”“帮我检查这张图”“配色是否色盲友好”。Data storytelling, chart selection, dashboard layout, table design, infographic, visualization review, color contrast.
license: MIT
---

# 数据叙事与图表设计合并包（DataInk）

本技能把 DataInk 的多个指南合并为一个入口。先按下表选出与任务匹配的成员，再用 `read_skill_file`（`name` 传 `data-storytelling`，`path` 传表中路径）读取对应 `GUIDE.md`，只加载需要的成员。成员指南为上游英文原文，已按本应用环境做了少量改写。

| 成员 | 适用场景 | 指南 |
| :--- | :--- | :--- |
| `data-storyteller` | 汇报、报告、执行摘要的叙事结构：受众与冲突、三幕结构、行动标题、横向与纵向逻辑检验 | `members/data-storyteller/GUIDE.md` |
| `data-visualization` | 选图、去除冗余、配色与强调、无障碍检查、按主题模板出图 | `members/data-visualization/GUIDE.md` |
| `dashboard-design` | KPI 看板：受众与目的、指标排序、组件选择、注意力布局、5 秒测试 | `members/dashboard-design/GUIDE.md` |
| `table-design` | 数据表格：行列结构、数字格式、对齐、分隔与汇总行 | `members/table-design/GUIDE.md` |
| `infographic-creator` | 信息图与一页式数据总结：故事板、版式、配色与重点 | `members/infographic-creator/GUIDE.md` |
| `visualization-review` | 评审已有图表或看板：P0 诚信与无障碍、P1 清晰度、P2 打磨，给出修改建议 | `members/visualization-review/GUIDE.md` |

## 路径与共用资料

- 成员正文中的相对路径以该成员目录为基准；用 `read_skill_file` 时换算为相对技能根目录的路径，例如 `references/chart-types.md`（在 `data-visualization` 中）即 `members/data-visualization/references/chart-types.md`，`../../assets/core-standards.md` 即 `assets/core-standards.md`。
- `assets/core-standards.md` 是所有成员共用的规则（无障碍、数据诚信、强调与层级、RTL 文字、品牌流程），设计任何图表前先读。`assets/accessible-palettes.md` 提供色盲安全配色。
- `assets/brand-colors.md` 与 `assets/brand-fonts.md` 是品牌占位模板：用户在对话中给出品牌色或字体时以对话为准，否则使用默认配色。
- 成员正文中的 “`<名称>` member” 指本包内的同名成员。实际计算与取数可另用 `data-analysis` 技能。

## 检查脚本

`scripts/` 下三个脚本只依赖 Python 标准库，两个渠道都可用：

- `check_contrast.py '#前景' '#背景'`：WCAG 对比度（文字 4.5:1、大字号与图形元素 3:1）。
- `check_palette.py '#色1,#色2,...' [--bg '#背景']`：正常视觉与三类色觉缺陷下的配色区分度。
- `check_rtl.py '<文字>'`：检测希伯来文、阿拉伯文并给出渲染处理方式。

运行方式（调用需要用户审批）：

- 内置模式用 `shell_exec`：`python3 "$INFINITY_SKILLS/data-storytelling/scripts/check_contrast.py" '#333333' '#FFFFFF'`。成员正文中的命令就是这种写法。
- 也可以用 `python_exec`：

```python
import os, runpy, sys
script = os.path.join(os.environ['INFINITY_SKILLS'], 'data-storytelling', 'scripts', 'check_contrast.py')
sys.argv = [script, '#333333', '#FFFFFF']
try:
    runpy.run_path(script, run_name='__main__')
except SystemExit as exit_code:
    print('exit', exit_code.code)
```

- Linux 会话中 `INFINITY_SKILLS` 为 `/mnt/skills`；系统未装 Python 时先 `apk add python3`。

退出码：`check_contrast.py` 与 `check_palette.py` 通过为 0、不通过为 1；`check_rtl.py` 无 RTL 文字为 0、检测到 RTL 为 3。

## 本应用的输出方式

- **图表**：默认在回复中输出 ```echarts 代码块（ECharts option JSON），应用会渲染并自动适配浅色与暗色主题，主题写法见 `members/data-visualization/references/code-recipes.md` 的 ECharts 一节。matplotlib、plotly 写法需要侧载版 Linux 会话。
- **看板、信息图、交互页面**：用 `canvas_create` 创建画布、`canvas_update` 修改。画布不能联网（外部脚本、字体、图片都会被拦截，图片与字体只能用 data: URI），视口宽 320–420 CSS px，要同时适配浅色与暗色。
- **叙事结构与评审报告**：直接在对话中输出，需要文件时写入工作区并用 `artifact_publish` 交付。
- 评审图片形式的图表时用 `view_media` 查看。

上游插件写文件后自动触发的检查钩子不包含在本技能中；需要评审时显式使用 `visualization-review` 成员。

## 来源与许可

改编自 oh-da/dataink（提交 f1b0039a928409a9ad62c33d908cb246ff62afad），按 MIT 许可分发。各成员目录与技能根目录保留上游 `LICENSE.txt`。修改说明见 `NOTICE.md`。
