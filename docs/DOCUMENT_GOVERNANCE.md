# 文档治理方案

> **版本**: 2.0.0
> **制定日期**: 2026-05-13 · **最后更新**: 2026-05-19
> **状态**: ✅ 清理完成 — 三根体系已建立

---

## 1. 当前文档结构

```
Infinity Deck/
│
├── docs/                              ← 公共项目文档（唯一出口）
│   ├── PRD.md                         # 产品需求文档 v2.0
│   ├── ARCHITECTURE_DESIGN.md         # 全局架构设计
│   ├── ARCHITECTURE.md                # 架构快速参考（含 ADR 索引）
│   ├── IMPLEMENTATION_ANALYSIS.md     # 当前实现分析与进度
│   ├── DOCUMENT_GOVERNANCE.md         # 本文档（文档治理方案）
│   ├── ADR/                           # 架构决策记录（18 项）
│   ├── audit/                         # 专项审计报告（19 份）
│   └── archive/                       # 已归档文档
│       └── CLEANUP_PLAN.md            # RN 残余清理记录
│
├── .agent/                            ← Agent 工作区（DIA 必需）
│   ├── registry.md                    # 文档注册表
│   ├── handover.md                    # 跨会话交接
│   ├── checklists/
│   │   └── CODE_REVIEW.md             # 代码评审清单
│   ├── plans/                         # 活跃实施计划（24 个）
│   └── plans/archive/                 # 已归档计划（24 个）
│
├── native-ui/                         ← Kotlin/Compose 原生项目
│   ├── AGENTS.md                      # 迁移技术规范（归档参考用）
│   └── app/...
│
├── AGENTS.md                          # 开发者与 Agent 协同开发规范
├── CHANGELOG.md                       # 全局版本变更记录
├── README.md                          # 项目门面
└── LICENSE
```

## 2. 已完成清理（2026-05-18 DIA 全站清理）

| 操作 | 内容 |
|------|------|
| ✅ 已删除 | `.agent/docs/` 全部 57 个 RN 时代文档 |
| ✅ 已删除 | `.agent/memory/` 全部 4 个 RN 时代记忆文件 |
| ✅ 已删除 | `.agent/README.md`、`.agent/PROJECT_RULES.md`、`.agent/todo.md` |
| ✅ 已删除 | `.qoder/` repowiki 145+ 文件 |
| ✅ 已删除 | `.roo/skills/` 旧维护技能目录 |
| ✅ 已合并 | `native-ui/.agent/plans/` → 根 `.agent/plans/`（5 个 unique plans） |
| ✅ 已合并 | `native-ui/docs/audit/` → 根 `docs/audit/`（3 个审计文档） |
| ✅ 已删除 | `native-ui/.agent/` 和 `native-ui/docs/` 目录 |
| ✅ 已合并 | `native-ui/CHANGELOG.md` → 根 `CHANGELOG.md`（7 条唯一变更） |

## 3. 2026-05-19 本轮文档优化

| 操作 | 文件 | 原因 |
|------|------|------|
| ✅ 已移动 | `docs/IMPLEMENTATION_PLAN.md` → `.agent/plans/archive/20260512-markdown-rendering-plan.md` | P0+P1 已完成的历史计划 |
| ✅ 已移动 | `docs/MARKDOWN_RENDERING_AUDIT.md` → `docs/audit/20260512-markdown-rendering-audit.md` | 已完成的审计报告 |
| ✅ 已移动 | `docs/plans/RAG_INDICATOR_MULTI_SESSION_EXECUTION.md` → `.agent/plans/20260517-rag-indicator-execution.md` | 计划文档统一至 .agent/plans/ |
| ✅ 已移除 | `docs/plans/` 空目录 | 已无文件 |
| ✅ 已标注 | `native-ui/AGENTS.md` — 归档参考 | 迁移规范，核心内容已整合至架构文档 |

## 4. DIA 检查表（与全局规则 §4.3 对齐）

```
□ 数据结构是否变更？       → 更新 IMPLEMENTATION_ANALYSIS.md
□ 接口/API 是否变更？      → 更新 ARCHITECTURE_DESIGN.md
□ UI 组件是否变更？        → 更新 IMPLEMENTATION_ANALYSIS.md
□ 架构/文件结构是否变更？   → 更新 ARCHITECTURE.md
□ 有用户可见的功能变更？    → 更新 CHANGELOG.md
□ 做出了架构权衡决策？     → 新增 ADR 文件
□ 新增/删除文档文件？      → 更新 registry.md
```

## 5. 核心原则

> **Kotlin/Compose 项目的文档策略**：架构全景图 + ADR 决策记录 + 分阶段计划。不追求代码级自动生成文档，IDE 本身已是代码导航的最佳工具。

---

**文档维护者**: AI Assistant
**最后更新**: 2026-05-19
