# Infinity Deck — 产品需求文档 (PRD)

> **版本**: 2.1.0（Kotlin 原生发行候选）
> **更新时间**: 2026-08-25
> **状态**: `v0.2.1-beta` 本地发行候选 — 核心功能与数据继承门禁已进入最终验证
> **前身**: `.agent/docs/PRODUCT_REQUIREMENTS.md` (v1.2.1, RN 时代，已归档)

---

## 1. 项目概览与定位

### 1.1 项目身份

| 属性 | 值 |
|------|-----|
| **名称** | Infinity Deck |
| **定位** | Android 端 BYOK 开源 AI 客户端 — 对标桌面端 LobeHub / Cherry Studio |
| **平台** | Android（Kotlin/Jetpack Compose 原生），远期渐进式迁移 CMP 跨端 |
| **许可证** | GPL-3.0 |
| **当前版本** | `0.7.0-beta`（versionCode 20，`main`） |

### 1.2 项目精神（Why Infinity Deck）

Infinity Deck 诞生于一个明确的市场空白：

> **桌面端**有 LobeHub、Cherry Studio 等界面精美、自由接入第三方 API 的开源 AI 客户端。但 **Android 端**缺少同时满足以下三者的产品：
> 1. **BYOK**（Bring Your Own Key）— 自由接入第三方模型 API
> 2. **界面精美** — 原生 Material Design 3 视觉水平
> 3. **功能完整** — 不只对话，还涵盖 RAG、知识图谱、Agent

LobeHub 的 WebApp 可在移动端使用，但纯网页架构的性能牺牲过大。大部分本地客户端（包括 Cherry Studio）也未能将 RAG 和知识图谱完美结合。

**Infinity Deck 的核心命题**：做 Android 端的 "LobeHub + Cherry Studio 融合体"，BYOK 自由、MD3 视觉、RAG+KG 记忆引擎三位一体。

### 1.3 目标用户画像

| 用户类型 | 核心场景 | 关键需求 |
|---------|---------|---------|
| **小说/文学创作者** | 长篇创作、角色扮演、互动小说 | 超长上下文管理、RAG 情节检索、KG 角色关系网 |
| **知识工作者** | 跨文档调研、技术问答、知识管理 | BYOK 接入最佳模型、RAG 跨文档检索、Token 成本追踪 |
| **轻量开发者** | SSH 运维、HTML/JS 小工具、脚本生成 | Agent 工具调用、代码高亮、Artifacts 预览 |
| **AI 爱好者** | 日常闲聊、模型对比、提示词工程 | 多模型自由切换、推理过程可视化、模型能力透明化 |

### 1.4 能力边界（What Infinity Deck is NOT）

| 不做 | 原因 |
|------|------|
| **Android 端纯本地编程开发 IDE** | 这不是 IDE，对标的是桌面 AI 客户端，不是 Cursor/Copilot |
| **纯本地推理专用客户端** | 本地推理作为可选增强，但核心体验是云端 API（BYOK） |
| **商业闭源收费产品** | GPL-3.0 开源，社区驱动 |
| **iOS 首发** | CMP 跨端是远期目标，当前聚焦 Android 原生 |

---

## 2. 用户体验设计

### 2.1 视觉语言：Lumina Aesthetic（Material Design 3 原生）

| 维度 | 规范 |
|------|------|
| **设计系统** | Material Design 3（Jetpack Compose Material3） |
| **主题** | 纯色背景（Light: #FFFFFF, Dark: #000000） |
| **卡片** | 浅灰圆角容器（SurfaceVariant），`rounded-3xl`（24px） |
| **图标** | Material Symbols 单色线条 |
| **字体** | 系统默认 Roboto，强调 CJK 排版质量 |
| **动效** | Compose Animation API，60fps 目标 |

### 2.2 交互原则

- **触感反馈**：默认关闭，用户可选开启（`HapticFeedback` 延迟 10ms 执行，遵循原生桥接黄金法则）
- **Snackbar 提示**：操作成功/失败优雅提示，不阻塞操作流
- **流畅动画**：转场动画 300ms tween，无掉帧
- **一致性**：所有页面遵循统一的 TopAppBar + 导航规范

### 2.3 信息架构（IA）

```
Welcome（首次引导）
  └─→ MainTabScaffold（主框架）
        ├─ Tab: Chat（对话中枢）
        │     ├─ AgentHubScreen（置顶 / 最近 / 助手分组 + 搜索；助手行就地展开其会话）
        │     │     └─→ ChatScreen（对话详情）
        │     │           ├─→ SessionSettingsScreen（会话设置）
        │     │           └─→ SpaSettingsScreen（默认助手全局设置）
        │     └─→ AgentEditScreen（助手编辑）
        │           ├─→ AgentRagConfigScreen（RAG 配置）
        │           └─→ AgentAdvancedRetrievalScreen（高级检索）
        │
        ├─ Tab: Library（资料）
        │     ├─→ RagFolderScreen（文件夹详情）
        │     ├─→ DocEditorScreen（文档编辑）
        │     ├─→ KnowledgeGraphScreen（知识图谱可视化）
        │     ├─→ GlobalRagConfigScreen（全局 RAG 设置）
        │     ├─→ RagAdvancedScreen（高级检索配置）
        │     └─→ RagDebugScreen（RAG 调试面板）
        │
        └─ Tab: Settings（设置）
              ├─ SettingsHome（账户、通用、AI 与模型、知识与检索、工具与数据、关于）
              ├─ ProviderList → ProviderForm / ProviderModels
              ├─ DefaultModels / Search / Skills / Token Usage
              └─ BackupSettings / DeveloperPanel / LocalModels
```

---

## 3. 核心功能架构

### 3.1 对话引擎（Chat） 🟢 原生版已实现 95%

**目标**：支持多模型、多会话的流式对话体验。

| 功能 | 优先级 | 说明 |
|------|--------|------|
| 基础对话界面 | P0 | 消息气泡、输入框、发送 |
| **流式实时回复** | P0 | SSE 解析，支持 OpenAI/Anthropic/VertexAI 三协议 |
| **会话内模型切换** | P0 | 实时"换脑"，持久化到 Session |
| **Markdown 实时渲染** | P0 | 代码块高亮、GFM Alert、LaTeX 公式、HTML Artifacts |
| **多模态支持** | P1 | 图片上传与预览、VLM 理解 |
| **会话管理** | P0 | 创建、删除、置顶、导出 |
| **智能标题生成** | P1 | LLM 自动总结标题（仅未手动命名时触发） |
| **自定义 Agent** | P0 | 创建/编辑/删除 Agent，配置系统提示词、模型、参数 |
| **Agent 级 RAG 配置** | P0 | 每个 Agent 可独立配置 RAG 参数或继承全局设置 |
| **Inverted List 渲染** | P0 | 倒序消息列表，O(1) 插入成本，优先加载最新消息 |

### 3.2 RAG 知识引擎 🟢 原生版已实现 90%

**目标**：本地向量化 + 语义检索 + 检索增强生成，为长对话和写作提供记忆外脑。

| 功能 | 优先级 | 说明 |
|------|--------|------|
| **文档管理** | P0 | 文件夹层级、文档导入（TXT/MD/PDF/Word/HTML） |
| **自动分块** | P0 | Recursive Character Splitter，可配置 |
| **本地向量化** | P0 | 可选本地 Embedding 模型或远程 API |
| **向量存储与检索** | P0 | SQLite + 余弦相似度，Top-K 排序 |
| **混合检索** | P1 | 向量 + 关键词（FTS5）混合，提高召回率 |
| **查询重写** | P1 | LLM 改写用户查询以提高检索精度 |
| **重排序** | P1 | 对检索结果进行 Rerank 精排 |
| **会话记忆向量化** | P0 | 对话历史自动向量化归档 |
| **RAG 检索指示器** | P1 | 聊天界面实时展示检索进度与来源 |
| **全文搜索** | P1 | FTS5 全文索引，支持关键字快速定位 |

### 3.3 知识图谱引擎

**目标**：从文档和对话中自动抽取实体关系，构建可视化知识网络。

| 功能 | 优先级 | 说明 |
|------|--------|------|
| **自动实体抽取** | P0 | LLM 驱动的三元组抽取（Subject-Predicate-Object） |
| **成本预算** | P0 | 按文档版本限制调用、输出 token 与总时长；大文档显示估算并确认；超限提交部分结果及已处理分块数，按 Hash 校验候选 |
| **本地图谱存储** | P0 | Room 全局实体、唯一关系与独立来源证据；读取按存活证据和可见来源筛选 |
| **聊天图检索** | P0 | 查询与 RAG 文档种子，默认一跳、可选两跳；按证据、置信度和距离剪枝；与即时抽取按实体与关系去重，并在独立 token 预算内生成上下文和路径快照 |
| **交互式可视化** | P1 | Jetpack Compose Canvas；初始最多 300 个节点，实体搜索与邻居按需展开；后台 Barnes-Hut 近似布局、视口裁剪和适配居中 |
| **多维视图** | P1 | 全局 / 文档 / 规范化概念作用域；切换作用域时清空跨域旧图 |
| **JIT 图缓存** | P1 | 默认持久图优先，覆盖不足时才抽取可见命中片段；可在检索高级设置关闭即时抽取回退 |

### 3.4 Agent 多步骤连续任务 🟢 原生版已实现 82%

**目标**：支持工具调用、审批流程、多步推理的基础 Agent 能力。

| 功能 | 优先级 | 说明 |
|------|--------|------|
| **工具调用** | P0 | Function Calling（OpenAI/Anthropic 协议原生） |
| **MCP 协议** | P1 | Model Context Protocol 支持，接入外部工具 |
| **审批循环** | P0 | Semi-Automatic 模式：高风险操作需用户确认 |
| **执行时间轴** | P1 | 可视化展示 Agent 思考与工具调用过程 |
| **技能/插件系统** | P2 | 用户自定义 Skill、MCP Server 配置 |
| **网络搜索** | P1 | 原生 Google Search Grounding + 自定义搜索工具 |
| **HTML Artifacts** | P2 | Agent 生成的 HTML/JS 代码可在 WebView 中预览与编辑 |

### 3.5 多服务商模型管理 🟢 原生版已实现 90%

**目标**：自由接入第三方模型 API，透明化管理。

| 功能 | 优先级 | 说明 |
|------|--------|------|
| **多协议支持** | P0 | OpenAI / Anthropic / VertexAI 三协议 |
| **服务商预设** | P0 | 10+ 主流提供商（OpenAI, DeepSeek, GLM, KIMI 等） |
| **自定义端点** | P0 | 任意兼容 OpenAI 协议的第三方 API |
| **模型连通性测试** | P0 | 一键探测延迟与可用性 |
| **模型能力标签** | P0 | 自动检测 Chat/Reasoning/Vision/Embedding/Rerank 等能力 |
| **推理参数配置** | P0 | Temperature, Top-P, Max Tokens 等 |
| **本地推理引擎** | P2 | llama.cpp 集成，可选本地运行小模型 |

### 3.6 Token 计费与统计 🟢 原生版已实现 85%

| 功能 | 优先级 | 说明 |
|------|--------|------|
| **混合计费** | P0 | API 返回的真实 Usage + 本地估算降级 |
| **全链路追踪** | P0 | Chat + RAG 操作 Token 消耗 |
| **可视化仪表盘** | P1 | 会话级 + 全局级统计 |
| **成本估算** | P1 | 基于模型定价的金额换算 |

### 3.7 数据安全与可迁移性 🟢 原生版已实现 90%

| 功能 | 优先级 | 说明 |
|------|--------|------|
| **会话导出** | P1 | 导出为 TXT/Markdown |
| **WebDAV 备份** | P2 | 用户显式触发的上传、列表与恢复；不声明后台同步 |
| **完整数据恢复** | P2 | 清单与逐项 SHA-256 验证、事务恢复；核心数据可独立加密，完整密钥备份强制密码 |
| **跨版本数据继承** | P0 | 当前库 v1→v5→v18 前进迁移，以及公开 `infinity.db` v17→`infinity_v2.db` v18 的精确 identity 接管与同签名 `adb install -r` 覆盖升级哨兵；Android FTS 校验使用私有可写副本并保持源库零写入，旧工作区兼容系统私有目录 bind-mount 别名但拒绝符号链接和越界路径；v17+v5 双库歧义在写入前 fail-close，待独立恢复工具闭环；禁止卸载/清数据回退 |
| **设备本地媒体边界** | P1 | 个人与 Agent 自定义头像不跨设备备份；同一安装覆盖升级由 Android 私有目录保留 |
| **开发中面板** | P1 | 日志导出、崩溃诊断 |

---

## 4. 技术路线与关键决策

### 4.1 技术栈

| 层级 | 选型 | 理由 |
|------|------|------|
| **语言** | Kotlin | Android 原生首选，CMP 跨端基础 |
| **UI 框架** | Jetpack Compose + Material3 | 原生 MD3 视觉，声明式 UI |
| **架构模式** | MVVM + Repository | ViewModel 管理状态，Repository 抽象数据源 |
| **数据库** | Room (SQLite) | 类型安全、Flow 响应式查询 |
| **网络** | OkHttp + Kotlin Coroutines | 成熟稳定，SSE 流式支持 |
| **序列化** | kotlinx.serialization | Kotlin 原生，编译期类型安全 |
| **依赖注入** | 手动 DI（Application 级单例） | 避免 Hilt/Koin 引入复杂度，当前规模适用 |
| **导航** | Compose Navigation | 类型安全路由（NavDestinations 密封类模式） |
| **Markdown 渲染** | 自定义 Compose Markdown（mikepenz 库深度定制） | 需要 CJK 排版、流式平滑等特殊优化 |
| **RAG 向量化** | 远程 Embedding API 为主 + 本地降级 | 平衡精度与性能 |
| **本地推理** | 暂不进入稳定版 | `v0.2.1-beta` Release 明确排除 GGUF/llama.cpp 制品，后续独立验收 |

### 4.2 跨端路线图

```
当前 (2026.Q2)               中期 (2026.Q3-Q4)             远期 (2027+)
─────────────────────────────────────────────────────────────────────
Kotlin/Compose 原生 Android    抽取 commonMain 公共层        CMP 全平台
  │                              │                            ├─ Android (原生)
  ├─ UI: Compose Material3       ├─ UI: Compose Multiplatform   ├─ iOS (SwiftUI bridge)
  ├─ Data: Room                   ├─ Data: SQLDelight           ├─ Desktop (JVM)
  └─ Network: OkHttp              └─ Network: Ktor Client       └─ Web (Kotlin/JS)
```

**渐进式策略**：先完成 Android 原生版功能闭环，再提取 `commonMain`。不追求一次到位，以用户体验交付为优先。

### 4.3 RAG + 知识图谱 + 工具回传架构路线

```
文档导入 → 分块 → Embedding → 向量存储 (SQLite)
                              ↘
                               LLM 实体抽取 → 图谱存储 (kg_nodes/edges)
                              ↗
用户查询 → 查询重写 → 混合检索 → Rerank ┐
                                        │
                    工具调用回传 ← Agent Loop  │
                    (搜索/代码/文件)     │     │
                                        ▼     ▼
                                ContextBuilder 组装 → 注入 System Prompt → LLM 回复
                                        ↑
                                  历史对话摘要

数据源层级:
  1. 被动检索（每轮自动）: 向量相似度 + FTS5 全文 + 知识图谱 + 历史摘要
  2. 主动工具回传（Agent 循环中）: 网络搜索结果、代码执行输出、文件读写等
     ↑ 注意：网络搜索是工具调用的一种，不是独立的数据源类别
```

**设计原则**：
- 向量检索提供语义相关性，FTS5 提供精确匹配
- 知识图谱提供结构化关系（角色关系网、概念层级）
- 工具回传是 Agent 循环的核心数据闭环——工具调用的输出作为下一轮 LLM 调用的上下文
- 四者互补，不是替代关系

---

## 5. 性能预算

| 指标 | 目标值 |
|------|--------|
| 冷启动时间 | < 1.5s |
| 交互延迟（UI 响应） | < 16ms（60fps） |
| 会话加载（1000+ 消息） | < 500ms |
| 流式响应延迟 | 首 Token < 500ms |
| RAG 检索召回率 | Top-5 > 80% |
| 崩溃率 | < 0.1% |
| APK 体积 | < 50MB |

---

## 6. 成功指标

### 6.1 功能完整度

- [ ] 多服务商 BYOK 自由接入 ≥ 10 提供商
- [ ] RAG 知识库（文档导入、分块、向量化、检索）全链路打通
- [ ] 知识图谱（自动抽取 + 可视化）可用
- [ ] Agent 工具调用 + 审批循环可用
- [ ] Markdown 渲染对标 LobeChat（GFM Alert / LaTeX / HTML Artifacts）
- [ ] 会话管理（创建/删除/导出/备份）完整
- [ ] Token 统计与成本可视化可用

### 6.2 用户体验

- [ ] MD3 原生视觉，暗黑模式完整适配
- [ ] 流式响应平滑，无明显闪烁
- [ ] CJK 排版质量不低于桌面端对标产品
- [ ] 长会话（1000+ 轮）无性能退化
- [ ] 触感反馈统一、可配置

### 6.3 工程质量

- [ ] Kotlin 代码覆盖率 > 80%
- [ ] Room 数据库迁移零数据丢失
- [ ] 编译警告清零
- [ ] 架构文档与代码同步更新（DIA 机制）

---

## 7. 开发路线图

### Phase 1: 原生架构奠基 ✅ 已完成
- [x] Kotlin/Jetpack Compose 项目初始化
- [x] MVVM + Repository 架构成型
- [x] Room 数据库设计（19 实体）
- [x] Compose Navigation 路由中心
- [x] 三协议 LLM 客户端（OpenAI/Anthropic/VertexAI）
- [x] SSE 流式解析管线
- [x] 模型规格库（50+ 模型，12 能力维度）
- [x] Provider 管理系统
- [x] Agent 创建/编辑/配置
- [x] 会话管理基础
- [x] Markdown 渲染基础

### Phase 2: RAG + 知识图谱 ✅ 当前稳定范围已完成
- [x] 文档管理（文件夹、导入 TXT/MD）
- [x] 向量存储与检索
- [x] Embedding 客户端（远程 API）
- [x] 知识图谱节点/边存储
- [x] LLM 实体抽取
- [x] RAG 检索指示器
- [ ] 本地 Embedding 降级方案
- [ ] PDF/Word/HTML 导入
- [x] RAG 配置阈值与作用域 UI
- [x] 混合检索（向量 + BM25/FTS）
- [x] 知识图谱可视化与全局/文档/概念作用域

### Phase 3: Agent 能力增强 ✅ 当前稳定范围已完成
- [x] Function Calling 基础
- [x] 工具管线（搜索、代码执行）
- [x] 审批循环（Semi-Automatic）
- [x] MCP HTTPS Streamable HTTP 客户端与持久化工具快照
- [x] 工具调用状态与审批 UI
- [x] Skill/MCP 管理 UI
- [ ] HTML Artifacts 预览与编辑

### Phase 4: 打磨发布 🚧 `v0.2.1-beta` 最终验证中
- [x] Markdown 渲染行业对齐（GFM Alert / LaTeX / 流式平滑）
- [x] CJK 排版与全层级 Material 3 设置视觉收敛
- [x] Token 统计仪表盘
- [x] WebDAV 手动备份恢复与加密包
- [x] 性能、截图、Lint、设备与 minified 自动门禁
- [ ] `v0.2.1-beta` 物理真机人工体验
- [ ] 用户另行授权后的 GitHub tag / Release；当前任务不包含公开发布

---

## 8. 超级助手（Super Assistant）决策 ⚡

> 详细分析见 [当前实现分析文档](./IMPLEMENTATION_ANALYSIS.md) §8。

**结论：去繁就简，取消 Super Assistant 概念，统一为可配置 Agent。**

### 背景
- RN 时代：超级助手有独立的 FAB、专属设置页、全局 RAG 权限、5 种动画模式
- 原生版现状：FAB 已移除，用户感知无差异，但底层 `isSuperAssistant` 检查、`spa_settings` 路由、字符串资源等残留

### 决策理由
1. **功能同质化**：所有 Agent 均支持 RAG 配置，超级助手的"全局 RAG"能力已成为 Agent 的基础能力
2. **架构冗余**：`isSuperAssistant` 检查、特殊路由、特殊设置页增加了不必要的分支逻辑
3. **用户心智负担**：普通助手 vs 超级助手的二元划分对用户不够直觉
4. **未来扩展**：统一 Agent 模型更容易支持 Agent 模板市场、社区分享

### 迁移方案
- 将 `spa_settings` 重命名为 `default_agent_settings`，成为全局默认 Agent 配置
- 移除 `PostProcessor.isSuperAssistant` 检查
- 移除硬编码的 `"super"` ID 特殊逻辑
- 保留 FAB 视觉定制能力，但作为 Agent 级别的属性（而非专属某 Agent）
- 保留全局 RAG 配置继承机制

---

**文档维护者**: AI Assistant
**最后更新**: 2026-08-25
**下次审查**: `v0.2.1-beta` 物理真机验收后
