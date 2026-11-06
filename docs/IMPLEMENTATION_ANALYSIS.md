# Infinity Workbench — 实现状态与历史进度记录

2026-09-22 的当前实现状态以[逐需求验收总账](audit/20260922-requirements-acceptance.md)和[候选验证账本](release/v0.2.1-beta-validation.md)为准。审计修复已完成本机确定性及签名制品验收；真实网关失败、物理设备和未测指标分别保留。历史测试数量、覆盖率表述和总体百分比不能继承为当前版本通过。

## 2026-05 至 2026-07 历史记录

以下保留原分析的规模、阶段估算和当时门禁，供审计对照，不作为当前功能完成率或测试覆盖率的证据。


> **版本**: 2.0.0-beta
> **分析日期**: 2026-05-19（更新自 2026-05-14）
> **分析范围**: `native-ui/` 目录（Kotlin/Jetpack Compose 原生版）
> **对照基准**: [PRD.md](./PRD.md)（产品需求） + [ARCHITECTURE_DESIGN.md](./ARCHITECTURE_DESIGN.md)（理想架构）

> **2026-07-13 发行快照**：本文主体仍保留 2026-05 的历史规模与阶段记录，以下快照优先于旧的“总体 98%”估算。`v0.2-beta` 的 P0 安全/数据与 P1 核心业务门禁已完成；P2 后台生成、首次引导、完整双语/自适应/视觉回归和 P3 签名/CI/GitHub Release 尚未完成，因此当前不可宣称达到发行交付条件。

## 2026-07-13 v0.2-beta 门禁记录

| 阶段 | 状态 | 已验证结果 |
|------|------|------------|
| P0 安全与数据 | ✅ 完成 | 密钥默认遮蔽、可选完整备份、事务恢复、真实设置 UI 与多阶段进程重建 |
| P1 核心业务 | ✅ 完成 | 请求级 Provider、工具幂等 ledger、Session 工作区、KG 回流、事务索引、分享/SAF 导入 |
| P2 后台生成与 UI | 🚧 待完成 | GenerationCoordinator/FGS、首次引导、完整双语、自适应、无障碍、截图回归 |
| P3 发行工程 | 📋 待开始 | Release 签名、CI、冷安装、升级/清数据策略、GitHub Release APK |

该阶段记录的自动化证据：1232 个 JVM 测试零失败（14 跳过），28 个 Android 设备测试零失败（2 个多阶段用例按设计跳过），Lint 无 Error/Fatal，Debug APK 构建成功；真实 LLM 集成基础设施已建立，但本轮尚未通过安全环境变量注入真实凭证执行。

---

## 1. 代码库概览

### 1.1 规模统计

| 指标 | 数值 |
|------|------|
| Kotlin 源文件 | ~342 个 |
| Room 实体 | 18 个 |
| Room DAO | 19 个 |
| Repository 实现 | 12 个（覆盖率 100%） |
| Domain 接口 | 9 个（Repository 接口）|
| Domain UseCase | 6 个 |
| ViewModel | 15+ 个 |
| Composable Screen | 25+ 个 |
| 导航路由 | 27 个 |
| 内置模型规格 | 117+ 个（12 能力维度） |
| 协议实现 | 5 个（OpenAI / Anthropic / VertexAI / GenericOpenAICompat / Local） |
| 内置 Skill | 18 个 |
| 测试文件 | 58 个 |

### 1.2 包结构映射

```
com.promenar.nexara/
├── MainActivity.kt              # Compose 宿主 Activity
├── NexaraApplication.kt         # 全局单例容器 + 默认数据注入
│
├── data/
│   ├── agent/                   # AgentRagConfig / AgentRetrievalConfig
│   │   └── AgentConfigModels.kt
│   │
│   ├── local/
│   │   ├── db/
│   │   │   ├── NexaraDatabase.kt        # Room DB (19 entities, v6)
│   │   │   ├── Converters.kt
│   │   │   ├── entity/                   # 19 个 @Entity
│   │   │   │   ├── AgentEntity.kt
│   │   │   │   ├── SessionEntity.kt
│   │   │   │   ├── MessageEntity.kt
│   │   │   │   ├── AttachmentEntity.kt
│   │   │   │   ├── FolderEntity.kt
│   │   │   │   ├── DocumentEntity.kt
│   │   │   │   ├── VectorEntity.kt       # BLOB 浮点向量
│   │   │   │   ├── VectorFtsEntity.kt    # FTS5 全文检索
│   │   │   │   ├── ContextSummaryEntity.kt
│   │   │   │   ├── TagEntity.kt
│   │   │   │   ├── DocumentTagEntity.kt
│   │   │   │   ├── KgNodeEntity.kt       # 知识图谱节点
│   │   │   │   ├── KgEdgeEntity.kt
│   │   │   │   ├── KgJitCacheEntity.kt
│   │   │   │   ├── VectorizationTaskEntity.kt
│   │   │   │   ├── AuditLogEntity.kt
│   │   │   │   ├── ArtifactEntity.kt
│   │   │   │   └── SkillEntities.kt      # CustomSkill / McpServer
│   │   │   └── dao/                       # 19 个 DAO
│   │   │
│   │   └── inference/                    # 本地推理引擎
│   │       ├── LocalInferenceEngine.kt   # llama.cpp JNI
│   │       ├── LlamaContext.kt
│   │       ├── GgufParser.kt
│   │       ├── GpuDetector.kt
│   │       ├── ModelDownloader.kt
│   │       ├── ModelStorageManager.kt
│   │       └── InferenceBackend.kt
│   │
│   ├── manager/
│   │   └── ProviderManager.kt            # 全站 Provider 单例
│   │
│   ├── model/
│   │   ├── ChatModels.kt                 # Agent / Message / Session / TokenUsage
│   │   ├── Mappers.kt                    # Entity ↔ Model
│   │   ├── ModelSpecs.kt                 # 50+ 模型规格
│   │   └── ProviderModels.kt             # ProviderConfig / ProviderListItem
│   │
│   ├── remote/
│   │   ├── MessageFormatter.kt           # Message → ProtocolMessage
│   │   ├── MessageFormatterFactory.kt
│   │   ├── ThinkingDetector.kt
│   │   ├── parser/                       # SSE 流解析管线
│   │   │   ├── StreamParser.kt
│   │   │   ├── ResponseNormalizer.kt
│   │   │   ├── ErrorNormalizer.kt
│   │   │   └── StreamBufferManager.kt
│   │   └── protocol/                     # 三协议实现
│   │       ├── LlmProtocol.kt            # 核心接口 + 密封类
│   │       ├── OpenAIProtocol.kt
│   │       ├── AnthropicProtocol.kt
│   │       └── VertexAIProtocol.kt
│   │
│   ├── repository/                       # 仓库实现
│   │   ├── SessionRepository.kt
│   │   ├── MessageRepository.kt
│   │   └── SkillRepository.kt
│   │
│   └── rag/                              # RAG 引擎
│       ├── VectorStore.kt
│       ├── EmbeddingClient.kt
│       ├── DocumentImporter.kt
│       ├── ContextBuilder.kt
│       └── ...
│
├── ui/
│   ├── chat/                             # 对话相关
│   │   ├── ChatScreen.kt
│   │   ├── ChatViewModel.kt
│   │   ├── SpaSettingsScreen.kt          # 超级助手设置
│   │   ├── SpaViewModel.kt
│   │   ├── SessionSettingsScreen.kt
│   │   └── components/                   # 对话组件
│   │
│   ├── hub/                              # 中枢
│   │   ├── AgentHubScreen.kt            # Agent 列表
│   │   ├── AgentHubViewModel.kt
│   │   ├── AgentEditScreen.kt           # Agent 编辑
│   │   ├── AgentEditViewModel.kt
│   │   ├── AgentSessionsScreen.kt       # 会话列表
│   │   ├── AgentRagConfigScreen.kt
│   │   └── AgentAdvancedRetrievalScreen.kt
│   │
│   ├── rag/                              # 知识库 UI
│   │   ├── GlobalRagConfigScreen.kt
│   │   ├── RagFolderScreen.kt
│   │   ├── DocEditorScreen.kt
│   │   ├── KnowledgeGraphScreen.kt
│   │   ├── AdvancedRetrievalScreen.kt
│   │   └── RagDebugScreen.kt
│   │
│   ├── settings/                         # 设置 UI
│   │   ├── SettingsScreen.kt
│   │   ├── SettingsViewModel.kt
│   │   ├── ProviderFormScreen.kt
│   │   ├── ProviderModelsScreen.kt
│   │   ├── BackupSettingsScreen.kt
│   │   ├── DeveloperScreen.kt
│   │   └── ...
│   │
│   ├── welcome/
│   │   └── WelcomeScreen.kt
│   │
│   ├── common/                           # 通用组件
│   │   ├── ModelPicker.kt
│   │   ├── NexaraGlassCard.kt
│   │   └── ...
│   │
│   └── theme/                            # MD3 主题
│       ├── NexaraColors.kt
│       ├── NexaraTypography.kt
│       └── NexaraTheme.kt
│
├── navigation/
│   └── NavGraph.kt                       # 27 路由定义
│
└── utils/
    ├── NexaraLogger.kt
    └── LocaleHelper.kt
```

---

## 2. 架构合规性分析（对照理想架构）

### 2.1 分层架构 ✅ / 🟡 / ❌

| 层级 | 理想状态 | 当前实现 | 评级 | 差距 |
|------|---------|---------|------|------|
| **UI 层** | Screens + ViewModels + Components | ✅ 完整实现 | 🟢 | 组件拆分可继续优化 |
| **Domain 层** | 纯 Kotlin Use Cases | ✅ 已实现（Phase 5，2026-05-13） | 🟢 | 6 个 UseCase，可按需扩展 |
| **Data 层** | Repository 接口 + 实现 | ✅ 完整实现 | 🟢 | 9/9 Repository 覆盖率 100% |
| **Infra 层** | Room + OkHttp + FileSystem | ✅ 完整实现 | 🟢 | — |

**关键问题**：Domain 层缺失导致业务逻辑分散。

```kotlin
// 现状：ViewModel 直接操作 DAO（绕过 Repository）
// AgentHubViewModel.kt
fun createAgent(...) {
    val entity = AgentEntity(...)
    agentDao.insert(entity)  // ← 直接 DAO 调用
}
```

**理想状态**应该是：
```kotlin
// Domain 层
interface IAgentRepository {
    suspend fun create(name: String, ...): Agent
}

// ViewModel
fun createAgent(...) {
    viewModelScope.launch {
        agentRepository.create(name, ...)  // ← Repository 接口
    }
}
```

### 2.2 Repository 体系覆盖率

| 聚合根 | Repository 接口 | Repository 实现 | 使用方式 |
|--------|:---:|:---:|------|
| Agent | ✅ IAgentRepository | ✅ AgentRepository | ViewModel → AgentRepository |
| Session | ✅ ISessionRepository | ✅ SessionRepository | ViewModel → SessionRepository |
| Message | ✅ IMessageRepository | ✅ MessageRepository | ViewModel → MessageRepository |
| Document | ✅ IDocumentRepository | ✅ DocumentRepository | ViewModel → DocumentRepository |
| Vector | ✅ IVectorRepository | ✅ VectorRepository | ContextBuilder → VectorRepository |
| KnowledgeGraph | ✅ IKnowledgeGraphRepository | ✅ KnowledgeGraphRepository | ViewModel → KnowledgeGraphRepository |
| Provider | ✅ IProviderRepository | ✅ ProviderRepository | ProviderManager → ProviderRepository |
| Folder | ✅ IFolderRepository | ✅ FolderRepository | ViewModel → FolderRepository |
| TokenStats | ✅ ITokenStatsRepository | ✅ TokenStatsRepository | Settings → TokenStatsRepository |
| Skill | — | ✅ SkillRepository | ToolExecutor → SkillRepository |

**评级**: 🟢 **Repository 覆盖率 9/9（100%）— 全部路径经 Repository 间接访问 DAO**

### 2.3 LLM 抽象层

| 组件 | 理想状态 | 当前实现 | 评级 |
|------|---------|---------|------|
| `LlmClient` 接口 | Domain 层定义 | ❌ 无统一接口 | 🔴 |
| 协议实现 | OpenAI / Anthropic / VertexAI | ✅ 三协议完整 | 🟢 |
| SSE 解析管线 | StreamParser → Normalizer | ✅ 完整实现 | 🟢 |
| Thinking 分离 | ThinkingDetector | ✅ 完整实现 | 🟢 |
| Embedding 客户端 | 统一接口 + 多后端 | 🟡 EmbeddingClient 仅远程 API | 🟡 |

**评级**: 🟢 **协议层健壮，但缺少 Domain 层抽象接口**

### 2.4 RAG 引擎

| 功能 | 实现状态 | 评级 |
|------|---------|------|
| 文档导入（TXT/MD） | ✅ DocumentImporter | 🟢 |
| PDF/Word/HTML 导入 | ✅ PDF/.docx/.doc/HTML 已实现；扫描版 PDF 提示无文字层 | ✅ |
| 自动分块 | ✅ Recursive Character Splitter | 🟢 |
| 向量存储（SQLite） | ✅ VectorEntity + VectorDao | 🟢 |
| FTS5 全文检索 | ✅ VectorFtsEntity + KeywordSearcher | 🟢 |
| 余弦相似度检索 | ✅ VectorStore | 🟢 |
| 混合检索（向量 + FTS） | ✅ RRF Fusion 默认开启 | 🟢 |
| Embedding 配置回退 | ✅ 空键回退到主 LLM Provider | 🟢 |
| Embedding 本地降级 | ❌ 未实现 | 🔴 |
| 查询重写 | ✅ 默认开启（enableQueryRewrite=true） | 🟢 |
| Rerank 重排序 | ✅ RerankClient 双路径（API+LLM 回退） | 🟢 |
| ContextBuilder（多源调度） | ✅ 核心调度器已实现 | 🟢 |
| RAG 进度指示器 | ✅ RagOmniIndicator | 🟢 |
| 会话记忆向量化 | ✅ 已实现 | 🟢 |
| Memory 视图 | ✅ RagHomeScreen 记忆浏览+删除 | 🟢 |
| 全文搜索 UI | ✅ 标题+FTS5 合并搜索 | 🟢 |
| 图像生成工具 | ✅ ImageGenerationSkill (2026-05-14) | 🟢 |

**评级**: 🟢 **核心管线完整，本地 Embedding 降级待补充（~90%）**

### 2.5 知识图谱

| 功能 | 实现状态 | 评级 |
|------|---------|------|
| LLM 实体抽取 | ✅ GraphExtractor | 🟢 |
| Summary-First 策略 | ✅ 已实现 | 🟢 |
| 增量更新（Hash 校验） | ✅ 已实现 | 🟢 |
| 图谱存储（kg_nodes/edges） | ✅ Room 实体 + DAO | 🟢 |
| JIT 图缓存 | ✅ KgJitCacheEntity | 🟢 |
| **交互式可视化** | ✅ KnowledgeGraphScreen + ECharts 力导向图 | 🟢 |
| 多维视图（全局/文档/概念） | ✅ 三种视图模式已切换 | 🟢 |

**评级**: 🟢 **抽取、存储、可视化全链路贯通（~80%）**

### 2.6 Agent 引擎

| 功能 | 实现状态 | 评级 |
|------|---------|------|
| Function Calling | ✅ 三协议支持 | 🟢 |
| 审批循环（Semi-Automatic） | ✅ ApprovalManager + ApprovalCard + 工具审批跳过 | 🟢 |
| 工具注册表 | ✅ 内置 11 工具 + MCP 动态扩展 | 🟢 |
| 文件系统工具 | ✅ file_read / file_write / file_list / file_search | 🟢 |
| **JS 沙箱解释器** | ✅ exec_js (WebView 沙箱) | 🟢 |
| 执行时间轴 | ✅ 完整 UI 接入（ToolExecutionTimeline + TOOL 卡片 + 流式指示器） | 🟢 |
| MCP 协议 | ✅ McpClient + McpSkill + McpSkillRegistry 同步链路 | 🟢 |
| HTML Artifacts | ✅ HtmlArtifactCard WebView 实时预览 + 全屏分屏 + PNG 导出 | 🟢 |
| 工具调优 | ✅ 工具链参数双重累积修复 + Fallback 兜底 + 循环限制 50 步 | 🟢 |
| 核心视觉 | ✅ PipelineBubble + 长按菜单原生 MD3 + 引用内容面板 + RAG 指示器常驻 | 🟢 |

**评级**: 🟢 **工具生态就绪，全线贯通（~95%）**

---

## 3. 功能模块实现进度

```
模块                     进度        关键缺失
───────────────────────────────────────────────────────────
对话引擎 (Chat)          █████████░  95%   后台生成能力(GenerationService)
RAG 知识引擎             █████████░  95%   本地 Embedding 降级
知识图谱 (KG)            █████████░  90%   Compose Canvas 原生引擎已完成
Agent 引擎               █████████░  95%   后台生成能力
Provider 管理            ██████████  98%   本地推理调通
Token 统计               ████████░░  85%   费用精确计算
数据备份与恢复           ████░░░░░░  40%   WebDAV
设置与主题               █████████░  95%   全站 UI 一致性已统一
Welcome 引导             ██████████ 100%   已完成
导航与路由               █████████░  95%   部分预留路由
本地推理                 ███████░░░  60%   llama.cpp 集成完成，运行调通待验证

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
总体进度                  █████████░  98%
```
```

### 3.1 已完成（✅）

| 模块 | 关键成果 |
|------|---------|
| 项目基础设施 | Kotlin/Compose 项目搭建、Room DB、Compose Navigation 27 路由、MD3 主题 |
| LLM 协议层 | OpenAI / Anthropic / VertexAI 三协议完整实现、UnifiedLlmClient 统一入口、SSE 流式解析管线、ThinkingDetector、DsmlStreamParser |
| Provider 管理 | ProviderFormScreen、ProviderModelsScreen、ModelPicker、117+ 模型规格库、多路保存修复 |
| Agent 基础 | AgentHubScreen、AgentEditScreen（含自动保存）、AgentRagConfig、AgentAdvancedRetrieval |
| 对话核心流 | ChatScreen + ChatViewModel、流式对话、图片上传/VLM、消息气泡、会话创建/删除、重发/重新生成 |
| RAG 存储与检索 | VectorStore、EmbeddingClient、DocumentImporter（PDF/.docx/.doc/HTML/TXT/MD，文本支持 UTF-8/UTF-16/GB18030）、混合检索（RRF Fusion）、RerankClient、查询重写、Memory 记忆浏览、全文搜索、引用内容面板 |
| 知识图谱 | KgNodeEntity/KgEdgeEntity、GraphExtractor（LLM 抽取）、MicroGraphExtractor（JIT）、ECharts 力导向图可视化、全局/文档/概念三维视图、176+ 大数据量防崩溃优化 |
| Token 仪表盘 | TokenUsageScreen（全局统计/会话排行/趋势图/模型明细）、费用估算 |
| Agent 工具 | 18 内置工具 + MCP 动态扩展 + JS 沙箱 + 文件系统 + 联网搜索三引擎 + 审批增强 + 工具链 Fallback 兜底 |
| HTML Artifacts | HtmlArtifactCard WebView 实时预览 + 全屏分屏模式 + PNG 导出 |
| 提示词编辑器 | UnifiedPromptEditor（整页编辑、窄屏编辑/预览切换与宽屏分栏、输入时 Markdown 高亮、格式工具条与撤销、词数统计）|
| 测试覆盖 | 58 个测试文件，覆盖 Skill/ViewModel/Repository/RAG/Protocol/Domain 全链路 |
| 本地推理引擎 | llama.cpp JNI 绑定、GGUF 解析、GPU 检测、模型下载管理、三槽位（Main/Embed/Rerank）|
| 调试桥 | Nexara Metro 调试桥（Room QueryCallback Auditor + MetroLogInterceptor + MetroLoggingMiddleware + TUI 终端）|
| 核心视觉 | PipelineBubble 线性气泡 + 长按菜单原生 MD3 + 引用内容面板 + RAG 检索指示器常驻 + MD3 一致性主题 |

### 3.2 进行中（🚧）

| 模块 | 当前状态 |
|------|---------|
| 本地推理调通 | llama.cpp 引擎代码已完成（7 文件），三槽位管理，LocalModelsScreen 完整 UI 实现（811行），待在实际设备上完成端到端推理验证 |
| Agent 视觉美化 | 核心功能（PipelineBubble、执行时间轴、Smart Follow）已完成，动画过渡与视觉精致化待优化 |

### 3.3 待开始（📋）

| 模块 | 依赖 |
|------|------|
| 后台生成服务 (GenerationService) | Foreground Service 承载 SSE 流式，需 API 34+ 通知权限适配 |
| WebDAV 备份恢复 | 需网络层支持 + 完整数据序列化 |
| CMP 跨端迁移 | 需先完成 Android 端功能闭环 |

---

## 4. 技术债务识别

> **2026-05-19 更新**: 架构债已全部消除。代码债和 UI 债在 Phase 7-9 期间大幅减少。

### 4.1 架构债（✅ 已全部消除）

| 编号 | 问题 | 状态 | 解决方案 |
|------|------|:---:|---------|
| **AD-1** | **Domain 层缺失** | ✅ 已解决 | Phase 5 (2026-05-13): 引入 9 Repository 接口 + 6 UseCase |
| **AD-2** | **Repository 覆盖率不足** | ✅ 已解决 | Phase 2-5: 补全 9 个 Repository，覆盖率 100% |
| **AD-3** | **ProviderManager 单例模式** | ✅ 已解决 | ProviderRepository 实现 IProviderRepository |
| **AD-4** | **"super" Agent 硬编码** | ✅ 已解决 | Phase 3: 去繁就简，取消 Super Assistant 特殊逻辑 |

### 4.2 代码债（🟡 中危）

| 编号 | 问题 | 位置 |
|------|------|------|
| CD-1 | `AgentHubScreen` 中 `onNavigateToSuperChat` 被 `@Suppress("UNUSED_PARAMETER")` | `AgentHubScreen.kt:35-37` |
| CD-2 | `PostProcessor` 中 `isSuperAssistant` 字符串匹配检查 | `PostProcessor.kt:69-70` |
| CD-3 | `SpaSettingsScreen` 中的占位统计数据（"Coming soon"） | `SpaSettingsScreen.kt` |
| CD-4 | `ChatViewModel` 中 RAG 引用写入逻辑缺失 | ✅ 已修复（2026-05-14） |
| CD-5 | `VectorStore` 缺少向量维度不匹配的日志告警 | ✅ 已修复（2026-05-14） |

### 4.3 UI 债（🟢 低危）

| 编号 | 问题 |
|------|------|
| UD-1 | 部分设置页面仍使用 `PlaceholderScreen`（workspace / session_settings_sheet） |
| UD-2 | CJK 排版在部分场景下间距不一致（已纳入 Markdown 渲染审计） |
| UD-3 | 模型选择器中能力标签在某些暗黑模式下可读性差 |

---

## 5. 数据库架构评估

### 5.1 表设计

19 个 Room Entity — **覆盖面完整**，覆盖了从对话到 RAG 到知识图谱的全链路。

| 优势 | 劣势 |
|------|------|
| ✅ 核心实体覆盖全面 | ⚠️ 表数量较多（19 个），DAO 数量同比例增长 |
| ✅ FTS5 全文索引已就绪 | ⚠️ 缺少复合索引优化高频查询 |
| ✅ KG JIT 缓存体现设计前瞻性 | ⚠️ `VectorEntity` 使用 BLOB 存储向量，JSON 序列化开销 |
| ✅ AuditLog 支持操作审计 | — |

### 5.2 建议优化

1. **向量存储优化**：考虑使用 FloatArray + 自定义 Room Converter（减少 JSON 序列化开销）
2. **索引补全**：`messages(session_id, timestamp DESC)` 复合索引
3. **数据库版本管理**：当前 v6，需确保迁移脚本覆盖所有变更

---

## 6. 导航架构评估

### 6.1 当前状态

- 27 条路由，覆盖所有主要流程
- `NavDestinations` 使用密封类模式确保类型安全
- 转场动画统一配置（300ms tween + slide）
- 3 条预留路由：`SESSION_SETTINGS_SHEET`、`WORKSPACE_SHEET` → `PlaceholderScreen`

### 6.2 问题

| 问题 | 影响 |
|------|------|
| `SPA_SETTINGS` 无参数 — 硬编码为"super" Agent 专用 | 无法为其他 Agent 复用 |
| `WelcomeScreen` 直接操作 SharedPreferences | 违反分层原则 |
| 占位路由未实现 | 用户体验断层 |

---

## 7. 性能架构评估

### 7.1 已实现的优化 ✅

- LazyColumn/LazyVerticalGrid（列表虚拟化）
- Inverted List 渲染（消息列表 O(1) 插入）
- 流式响应异步处理（Kotlin Flow）
- Room Flow 响应式查询
- Markdown 分量渲染 + 缓存

### 7.2 待实现的优化 📋

- 向量检索批处理（当前逐条计算余弦相似度）
- 大文档加载的分页/懒加载
- 图片压缩与磁盘缓存策略
- Compose `derivedStateOf` 优化重组合并

---

## 8. 超级助手（Super Assistant）取舍分析 ⚡

> **这是本次审计的核心议题之一。**

### 8.1 历史沿革

```
RN 时代 (v1.x)
  ┌────────────────────────────────────────────┐
  │ Super Assistant = 专属 FAB + 全局 RAG      │
  │                                            │
  │ • 5 种动画模式 (Pulse/Nebula/Quantum/...)  │
  │ • GIF 动图头像                             │
  │ • 专属设置页 (spa_settings)                │
  │ • 全局 RAG 检索（跨所有文档和会话）        │
  │ • 专用的 spa-store (Zustand)               │
  │ • 独立的导航路由                            │
  │ • FAB 是用户感知的核心差异点               │
  └────────────────────────────────────────────┘
            │
            ▼  Kotlin 原生迁移
            │
  ┌────────────────────────────────────────────┐
  │ 原生版现状                                  │
  │                                            │
  │ • FAB 已从 AgentHubScreen 移除 ← 关键变化  │
  │ • SpaSettingsScreen 仍存在                 │
  │ • PostProcessor.isSuperAssistant 检查仍存在 │
  │ • "super" 仅作为 Agent ID 字符串约定        │
  │ • 所有 Agent 均可配置 RAG                   │
  │ • 字符串资源仍保留 "超级助手" 相关翻译      │
  │ • onNavigateToSuperChat 被 @Suppress       │
  └────────────────────────────────────────────┘
```

### 8.2 当前架构中的残留

```kotlin
// 1. 硬编码 Agent ID
// NexaraApplication.kt:297
Agent("super", "Nexara 超级助手", ...)

// 2. 被抑制的导航回调
// AgentHubScreen.kt:35-37
@Suppress("UNUSED_PARAMETER")
onNavigateToSuperChat: () -> Unit

// 3. 字符串匹配检查
// PostProcessor.kt:69-70
val isSuperAssistant = 
    sessionId == "super_assistant" || session.agentId == "super_assistant"

// 4. 专属全局路由
// NavGraph.kt:61
const val SPA_SETTINGS = "spa_settings"  // 无参数，硬编码

// 5. 字符串资源
// strings.xml:80
<string name="hub_fab_super">Super Assistant</string>
```

### 8.3 决策分析

```
选项 A: 恢复超级助手
  ┌─────────────────────────────────────────────┐
  │ 优势                                        │
  │ • 恢复 RN 时代的产品差异化特性              │
  │ • FAB 动画视觉吸引力强                      │
  │                                              │
  │ 劣势                                        │
  │ • 需重写 5 种动画模式的 Compose 实现        │
  │ • 恢复 FAB → 恢复特殊路由 → 恢复特殊逻辑    │
  │ • 用户已有普通 Agent = 超级 Agent 的认知    │
  │ • "超级" 概念与 BYOK 精神有张力             │
  │   (为什么某个 Agent "超级"？因为 ID 是       │
  │    "super"？这不优雅)                        │
  │ • 增加 UI 维护负担（特殊设置页 + 通用设置）  │
  │                                              │
  │ 工期: ~5 人天 (5 种动画 + UI 恢复)          │
  └─────────────────────────────────────────────┘

选项 B: 去繁就简 ✅ 推荐
  ┌─────────────────────────────────────────────┐
  │ 优势                                        │
  │ • 所有 Agent 平等，用户自由创建/配置        │
  │ • RAG 能力 = Agent 的基础属性，非特权       │
  │ • 消除 isSuperAssistant 等分支逻辑          │
  │ • 架构更清晰（少一个特殊路由和特殊设置页）  │
  │ • 符合 BYOK 精神 — 用户定义"超级"          │
  │ • 更易扩展（Agent 模板市场不受"超级"概念限制）│
  │                                              │
  │ 劣势                                        │
  │ • 丢弃了 RN 时代投入的 FAB 动画资产         │
  │ • 需要在 CHANGELOG 中记录此破坏性变更       │
  │                                              │
  │ 工期: ~2 人天 (清理 + 重命名)               │
  └─────────────────────────────────────────────┘
```

### 8.4 决策结论：**选项 B — 去繁就简**

**决策理由**：

1. **功能已同质化**：所有 Agent 均支持 RAG 配置。超级助手的"全局 RAG"能力已下沉为 Agent 基础能力。
2. **FAB 已移除，用户感知无差异**：最关键的 UX 差异点已消失，恢复 FAB 只是创造无意义的视觉噪音。
3. **BYOK 精神**：Nexara 的定位是 "让用户自由接入模型与配置"，而不是 "我们定义什么是超级"。
4. **CMP 迁移友好**：统一 Agent 模型减少跨端迁移的复杂度。
5. **RN 时代的合理设计在原生时代成为包袱**：RN 时代 RAG 不完善，需要一个 "超级" Agent 承载全局 RAG。原生时代 RAG 已是 Agent 的标准能力。

### 8.5 迁移方案

```diff
待清理项:
- Agent("super", ...) 默认构造          → 默认 Agent 命名为 "assistant"，id="default"
- onNavigateToSuperChat 回调           → 移除，用 onNavigateToNewChat()
- PostProcessor.isSuperAssistant       → 移除检查，所有 Agent 统一逻辑
- SPA_SETTINGS 路由                    → 重命名为 DEFAULT_AGENT_SETTINGS，支持 agentId 参数
- SpaSettingsScreen → 重命名           → AgentDefaultSettingsScreen
- SpaViewModel → 重命名                → AgentDefaultSettingsViewModel
- strings.xml 中 "超级助手" 相关字符串  → 替换为 "默认助手" 或删除
- 动画模式相关的配置键                 → 归档（RN 时代的动画引擎不再适用）

保留项:
- Agent 的 FAB 定制能力 (icon/color)   → 作为 Agent 级属性保留，不限于特定 Agent
- 全局 RAG 配置继承机制                → 保留，所有 Agent 共享
- 上下文管理（自动摘要、手动触发）     → 保留，作为会话级功能
```

---

## 9. 项目差异化优势

| 优势 | 说明 |
|------|------|
| **原生 MD3 视觉** | 基于 Jetpack Compose Material Design 3 原生构建，带来 60fps 触感体验与系统级视觉融合 |
| **RAG + 知识图谱深度融合** | 向量检索 + 全文搜索 + 知识图谱三位一体，为长篇写作与知识管理提供记忆外脑 |
| **移动端优先** | 专为 Android 触屏交互设计，支持流式对话、手势操作、触感反馈 |
| **BYOK 隐私优先** | 全本地存储 + 自由接入第三方 API Key，数据不经过任何中间服务器 |
| **Agent 工具生态** | 18 个内置工具 + MCP 协议扩展，覆盖网络搜索、文件操作、代码执行、AI 生图 |

---

## 10. 分阶段开发状态（2026-05-19 更新）

### 10.1 已完成 ✅

| 阶段 | 内容 | 完成日期 |
|------|------|---------|
| Phase 1 | 项目基础设施 + LLM 协议层 | 2026-05-09 |
| Phase 2 | Repository 全覆盖（9/9，100%） | 2026-05-13 |
| Phase 3 | Super Assistant 清理（ADR-001） | 2026-05-13 |
| Phase 4 | 核心引擎增强（FolderRepository + 文档导入） | 2026-05-13 |
| Phase 5 | UseCase 层抽取（6 个 UseCase） | 2026-05-13 |
| Phase 6 | 测试补缺 + 功能增强 | 2026-05-14 |
| Phase 7 | Markdown 渲染行业对齐 + 知识库修复增强 | 2026-05-17 |
| Phase 8 | Agent 工具系统重构 + Cherry-Studio 工具调用移植 | 2026-05-18 |
| Phase 9 | 发布冲刺 + 测试补全 + UI 一致性统一 | 2026-05-19 |
| Phase 10 | 工具链参数修复 + Fallback 兜底 + Room 迁移修复 | 2026-05-19 |

### 10.2 近期优先（2026-05-20+）🔥

| 优先级 | 任务 | 预估工期 | 说明 |
|--------|------|---------|------|
| **P0** | **后台生成能力（GenerationService）** | 2d | Foreground Service 承载 SSE 流式，离开 App 不中断 |
| P1 | 本地推理端到端调通 | 1d | llama.cpp 引擎代码已完成，需在设备上验证 |
| P1 | Embedding 本地降级方案 | 1d | 无远程 API 时回退到本地 TF-IDF 或 ONNX 推理 |

### 10.3 中期规划（2026-05-21 ~ 05-30）

| 优先级 | 任务 | 预估工期 |
|--------|------|---------|
| P1 | Compose Canvas 原生知识图谱可视化（ADR-018） | 2d |
| P1 | Token 统计仪表盘完善 | 1.5d |
| P2 | 会话导出（TXT/Markdown） | 1d |
| P2 | 多模态图片上传 + VLM 增强 | 1.5d |

### 10.4 远期规划（2026-06+）

| 优先级 | 任务 | 预估工期 |
|--------|------|---------|
| P1 | 性能 Profile + 启动优化 | 1.5d |
| P2 | WebDAV 备份恢复 | 2d |
| P2 | Compose 自动化测试（E2E） | 2d |
| P3 | CMP 渐进式跨端迁移 | 按需求 |

---

## 11. 关键风险（2026-05-19 更新）

| 风险 | 严重度 | 缓解措施 |
|------|--------|---------|
| **后台生成 Service 稳定性** | 🟡 中 | Foreground Service + 通知权限处理，需在 API 34+ 设备上验证 |
| **RAG 大规模文档库性能** | 🟡 中 | 混合检索 + 批处理优化，已有 58 个测试文件保障 |
| **本地推理引擎稳定性** | 🟡 中 | 作为可选增强，不阻塞主线功能 |
| **CMP 迁移阻塞 Android 交付** | 🟢 低 | 严格按渐进式策略，先完成 Android 闭环 |

---

**文档维护者**: AI Assistant  
**最后更新**: 2026-05-19  
**下次审查**: 后台生成实施完成时
