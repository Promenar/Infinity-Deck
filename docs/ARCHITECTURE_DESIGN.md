# Infinity Deck — 全局架构设计文档

> **版本**: 2.3.0
> **更新时间**: 2026-08-25
> **状态**: 理想架构与 `v0.2.1-beta` 本地发行候选剖面；历史差距分析见 [IMPLEMENTATION_ANALYSIS.md](./IMPLEMENTATION_ANALYSIS.md)
> **关联**: [PRD.md](./PRD.md) — 产品需求文档

---

## 1. 架构全景

### 1.1 分层架构图

```mermaid
graph TD
    subgraph "UI 层 (Compose)"
        SCREENS[Screens<br/>Welcome/Hub/Chat/RAG/Settings]
        VM[ViewModels<br/>状态管理 + 业务调度]
        COMP[Components<br/>可复用 Compose 组件]
    end

    subgraph "Domain 层"
        UC[Use Cases / Interactors<br/>纯业务逻辑，无框架依赖]
        MODEL[Domain Models<br/>Agent/Session/Message/Document/KgNode]
    end

    subgraph "Data 层"
        REPO[Repository 接口 + 实现<br/>单一数据源抽象]
        DAO[Room DAOs<br/>类型安全数据库访问]
        ENTITY[Room Entities<br/>持久化数据模型]
        REMOTE[Remote Data Sources<br/>LLM Client / Embedding Client / MCP Client]
    end

    subgraph "Infra 层"
        DB[(Room/SQLite<br/>主数据库)]
        PREF[SharedPreferences<br/>轻量 KV 配置]
        FS[FileSystem<br/>文档/日志/缓存]
        NET[OkHttp<br/>HTTP + SSE 流式]
    end

    SCREENS --> VM
    VM --> UC
    VM --> REPO
    COMP --> VM
    UC --> MODEL
    UC --> REPO
    REPO --> DAO
    REPO --> REMOTE
    DAO --> ENTITY
    DAO --> DB
    REMOTE --> NET
```

### 1.2 模块依赖规则

```
依赖方向: UI → Domain → Data → Infra
          └─── 绝不反向 ────┘

规则:
  1. UI 层只依赖 Domain 层（通过 ViewModel）
  2. Domain 层无 Android 框架依赖（纯 Kotlin）
  3. Data 层实现 Domain 层定义的 Repository 接口
  4. Infra 层只被 Data 层引用
  5. 所有跨越层边界的通信使用 Flow / suspend 函数
```

---

## 2. 模块详细设计

### 2.1 数据模型 (Domain Models)

#### 核心聚合根

```
Agent（助手）
  ├── id: String                    # 唯一标识（uuid）
  ├── name: String                  # 显示名称
  ├── description: String           # 描述（Markdown）
  ├── systemPrompt: String          # 系统提示词
  ├── modelId: String               # 关联模型
  ├── icon: String                  # 图标（emoji 或 Material icon key）
  ├── color: String                 # 主题色（HEX）
  ├── avatarPath: String?           # 自定义头像路径
  ├── isPinned: Boolean             # 是否置顶
  ├── temperature: Double?          # 推理参数
  ├── topP: Double?
  ├── maxTokens: Int?
  ├── ragConfig: AgentRagConfig?    # RAG 配置（null = 继承全局）
  ├── retrievalConfig: AgentRetrievalConfig?  # 检索配置
  ├── useInheritedConfig: Boolean   # 是否继承全局 RAG 配置
  ├── executionMode: ExecutionMode  # auto / semi / manual
  ├── skills: List<String>          # 启用的技能 ID 列表
  └── createdAt: Long               # 创建时间戳

Session（会话）
  ├── id: String
  ├── agentId: String
  ├── title: String
  ├── modelId: String               # 当前使用的模型（可会话内切换）
  ├── isPinned: Boolean
  ├── createdAt / updatedAt: Long
  └── messageCount: Int

Message（消息）
  ├── id: String
  ├── sessionId: String
  ├── role: MessageRole             # USER / ASSISTANT / SYSTEM / TOOL
  ├── content: String               # Markdown 格式
  ├── thinking: String?             # 推理过程（如 DeepSeek R1）
  ├── toolCalls: List<ToolCall>?    # 工具调用记录
  ├── ragReferences: List<RagReference>?  # RAG 引用来源
  ├── tokenUsage: TokenUsage?       # Token 消耗
  └── timestamp: Long

Document（文档）
  ├── id: String
  ├── folderId: String
  ├── title: String
  ├── content: String               # 原始文本
  ├── summary: String?              # LLM 摘要
  ├── hash: String                  # SHA-256 内容指纹
  ├── chunkSize / chunkOverlap: Int
  ├── vectorizedAt: Long?
  └── createdAt / updatedAt: Long
```

#### 枚举与值对象

```kotlin
enum class MessageRole { USER, ASSISTANT, SYSTEM, TOOL }
enum class ExecutionMode { AUTO, SEMI, MANUAL }
enum class ProtocolType { OPENAI, ANTHROPIC, VERTEX_AI }
enum class ModelType { CHAT, REASONING, IMAGE, EMBEDDING, RERANK }
enum class ModelCapability { CHAT, REASONING, VISION, WEB_SEARCH, EMBEDDING, RERANK, IMAGE_GEN, CODE, FUNCTION_CALLING, TOOL_USE, JSON_MODE, STREAMING }

data class TokenUsage(val input: Int, val output: Int, val estimated: Boolean = false)
data class RagReference(val chunkId: String, val documentTitle: String, val snippet: String, val score: Double)
data class ToolCall(val id: String, val name: String, val arguments: String, val result: String?, val status: ToolCallStatus)
```

### 2.2 Repository 体系

```
每个聚合根一个 Repository 接口（定义在 Domain 层） + 实现（定义在 Data 层）

IAgentRepository
  fun observeAll(): Flow<List<Agent>>
  fun observeById(id: String): Flow<Agent?>
  suspend fun create(agent: Agent): String
  suspend fun update(agent: Agent)
  suspend fun delete(id: String)

ISessionRepository
  fun observeByAgent(agentId: String): Flow<List<Session>>
  fun observeById(id: String): Flow<Session?>
  suspend fun create(agentId: String, modelId: String): Session
  suspend fun updateTitle(id: String, title: String)
  suspend fun delete(id: String)

IMessageRepository
  fun observeBySession(sessionId: String): Flow<List<Message>>
  suspend fun send(sessionId: String, content: String, attachments: List<Attachment>): Message
  suspend fun appendStreamChunk(messageId: String, chunk: StreamChunk)
  suspend fun delete(id: String)

IDocumentRepository
  fun observeByFolder(folderId: String): Flow<List<Document>>
  suspend fun import(path: String, folderId: String): Document
  suspend fun update(id: String, content: String)
  suspend fun delete(id: String)
  suspend fun vectorize(id: String)

IVectorRepository
  suspend fun search(query: String, topK: Int, filters: SearchFilters): List<SearchResult>
  suspend fun index(documentId: String, chunks: List<VectorChunk>)
  suspend fun deleteByDocument(documentId: String)

IKnowledgeGraphRepository
  suspend fun extractFromDocument(documentId: String): ExtractionResult
  suspend fun queryNodes(documentIds: List<String>): List<KgNode>
  suspend fun queryEdges(nodeIds: List<String>): List<KgEdge>

IProviderRepository
  fun observeAll(): Flow<List<ProviderConfig>>
  suspend fun testConnection(providerId: String): ConnectionResult
  suspend fun fetchModels(providerId: String): List<ModelSpec>
```

### 2.3 LLM 抽象层

```
                    ┌──────────────────────────┐
                    │      LlmClient            │
                    │   (Domain 层接口)          │
                    │  + streamChat(request)    │
                    │  + chat(request)          │
                    │  + embed(texts)           │
                    └──────────┬───────────────┘
                               │ 实现
               ┌───────────────┼───────────────┐
               │               │               │
        ┌──────▼──────┐ ┌─────▼──────┐ ┌──────▼──────┐
        │ OpenAIClient │ │AnthropicCl │ │VertexAICl   │
        │  (Data 层)   │ │  (Data 层) │ │  (Data 层)  │
        └──────┬───────┘ └─────┬──────┘ └──────┬──────┘
               │               │               │
        ┌──────▼───────────────▼───────────────▼──────┐
        │          StreamParser 管线                    │
        │  ResponseNormalizer → StreamBuffer → Parser  │
        └──────────────────────────────────────────────┘
                               │
                    ┌──────────▼──────────┐
                    │   OkHttp (Infra)    │
                    │   SSE Stream Read   │
                    └─────────────────────┘
```

**关键设计**：
- `LlmClient` 接口定义在 Domain 层，返回 `Flow<StreamChunk>`（标准化片段）
- 每个 Provider 在 Data 层独立实现，通过 `MessageFormatter` 处理协议差异
- `StreamParser` 管线负责将原始 SSE 事件转为 `StreamChunk` 流
- `ThinkingDetector` 检测并分离推理内容（`<thinking>` 标签或 `reasoning_content` 字段）

### 2.4 上下文构建引擎（ContextBuilder）

上下文构建是 Infinity Deck 的核心调度器，负责从多个数据源收集、打分、组装 System Prompt。
数据源分为 **被动检索** 和 **主动工具回传** 两类：

- **被动检索**（每次对话自动触发）：向量检索、FTS5 全文、知识图谱查询、历史摘要
- **主动工具回传**（Agent 循环中产生）：网络搜索、代码执行、文件读写等工具调用结果，作为下一轮 LLM 调用的上下文注入

```
┌──────────────────────────────────────────────────────────────────┐
│                    ContextBuilder Pipeline                        │
│                                                                   │
│  被动检索源                         主动工具回传源                │
│  ┌──────────┐  ┌──────────┐       ┌──────────────────┐          │
│  │ Vectors  │  │ FTS Main │       │ ToolCallResult   │          │
│  │(语义检索)│  │(关键词)  │       │ (上一轮工具回传) │          │
│  └────┬─────┘  └────┬─────┘       └────────┬─────────┘          │
│       │              │                      │                    │
│  ┌────▼──────────────▼──────┐              │                    │
│  │      Hybrid Search       │              │                    │
│  │     (混合检索 + Rerank)   │              │                    │
│  └────────────┬──────────────┘              │                    │
│               │                             │                    │
│  ┌────────────▼──────┐  ┌──────────┐       │                    │
│  │ Knowledge Graph   │  │ History  │       │                    │
│  │ (kg_nodes/edges)  │  │ Summary  │       │                    │
│  └────────┬──────────┘  └────┬─────┘       │                    │
│           │                  │              │                    │
│           └──────────┬───────┘              │                    │
│                      │                      │                    │
│                      ▼                      ▼                    │
│              ┌───────────────────────────────────────┐           │
│              │         ContextBuilder                 │           │
│              │    (Domain 层 — 多源调度与组装)        │           │
│              │                                       │           │
│              │  1. 向量 + FTS5 混合检索 → RAG 片段   │           │
│              │  2. KG 关系查询 → 结构化上下文        │           │
│              │  3. 历史摘要加载                     │           │
│              │  4. 工具回传结果注入                  │           │
│              │  5. Token 预算分配与裁剪              │           │
│              │  6. 拼接 System Prompt               │           │
│              └──────────────────┬────────────────────┘           │
│                                 │                                │
│                                 ▼                                │
│              ┌───────────────────────────────────────┐           │
│              │         ContextPayload                 │           │
│              │  systemPrompt + ragRefs + kgContext   │           │
│              │  + toolResults + tokenEstimate        │           │
│              └───────────────────────────────────────┘           │
│                                 │                                │
│                                 ▼                                │
│                        ┌──────────────┐                          │
│                        │  LLM Call    │                          │
│                        └──────────────┘                          │
└──────────────────────────────────────────────────────────────────┘
```

**ContextBuilder 设计**（核心调度器）：

```kotlin
// Domain 层接口
interface ContextBuilder {
    suspend fun build(
        sessionId: String,
        userMessage: String,
        config: RagConfig,
        previousToolResults: List<ToolCallResult> = emptyList(),  // ← 上一轮工具回传
        onProgress: (ContextBuildProgress) -> Unit
    ): ContextPayload
}

data class ContextPayload(
    val systemPrompt: String,                     // 拼接好的 System Prompt
    val ragReferences: List<RagReference>,        // 向量/FTS5 检索引用（展示用）
    val kgContext: List<KgTriple>,                // 知识图谱上下文
    val historySummary: String? = null,           // 历史对话摘要
    val toolResults: List<ToolCallResult>,        // 工具调用回传数据
    val tokenEstimate: Int                        // 上下文 Token 估算
)

// 工具调用回传 — 统一的抽象，网络搜索/代码执行/文件读写都是其子类型
data class ToolCallResult(
    val toolName: String,                         // "web_search", "execute_code", "read_file", ...
    val summary: String,                          // 摘要文本，注入 System Prompt
    val rawData: String? = null,                  // 原始数据（如搜索结果 JSON）
    val references: List<ExternalReference>? = null  // 外部引用（如搜索结果 URL）
)

data class ExternalReference(
    val title: String,
    val url: String,
    val snippet: String
)

// 进度观测（覆盖所有上下文构建阶段，不限于 RAG）
sealed class ContextBuildProgress {
    data class Retrieving(val source: String, val progress: Float) : ContextBuildProgress()
    data class Reranking(val candidateCount: Int) : ContextBuildProgress()
    data class LoadingHistory(val messageCount: Int) : ContextBuildProgress()
    data class InjectingTools(val toolName: String) : ContextBuildProgress()
    data class Building(val stage: String) : ContextBuildProgress()
    object Complete : ContextBuildProgress()
}
```

### 2.4.1 KG 抽取与图检索

知识图谱抽取分为文档候选图与会话 JIT 微图，两者共用模型解析、结果状态与图校验：

| 模式 | 触发者 | 输入 | 存储标记 | 生命周期 | 用途 |
|------|--------|------|---------|---------|------|
| **会话 JIT 微图** | 持久图种子不足或无扩展关系，且高级设置允许 | 可见的 RAG 命中片段 + 用户查询 | `source_type="jit"` | 容量 8 的后台队列串行缓存与归并，缓存 1h | 补充当前对话关系 |
| **文档全量抽取** | 手动菜单或启用 KG 的索引配置；大文档先确认 | PDF / DOCX / 文本等文件引用，经统一解析与分块 | `source_type="document"` | DocumentIndexService 候选事务提交 | 构建文档知识模型 |

**设计原则**：
- 模型选择依次使用 KG 设置、摘要预设和主模型；所选稳定 ID 必须通过 `ProviderRequestRouter.resolve()`，解析失败返回明确错误，不发送文档到其它提供商。请求只携带所属提供商的远端模型 ID。
- 抽取状态区分成功、部分完成、解析失败、超时、取消和提供商错误；协程取消向上传播。成功空图可清除旧关系，未经处理的待确认大文档保留旧图。
- 手动抽取仅替换 KG，保留向量与向量完成时间；索引事务同时检查文件 hash、epoch 和活动任务标识，原子切换节点、边与 `kgExtractedAt`。失败保留旧数据，删除屏障阻止迟到候选写回。
- `ValidatedGraph` 统一名称身份与端点，过滤空关系和不在 `[0,1]` 内的非有限或越界权重，最多保留 256 个节点与 512 条边；入库、提示词与路径均由校验结果派生。
- 文档解析使用统一 16 MiB UTF-8 文本预算并保留截断状态；源文件、格式与路径边界由 `DocumentReferenceExtractor` 检查。断点身份包含源文件 hash、提取文本 hash、模型稳定 ID、提示词摘要和切块参数。
- JIT 默认超时 5 秒；失败时 RAG 上下文正常返回，进度不发布 KG 成功。抽取器关闭或设置重建会取消其后台作用域。
- 文档版本默认限制 32 次调用、32,768 输出 token 和 180 秒活跃时长；每个请求输出最多 2,048 token，预计超过 16 次调用需确认。预算中断提交已处理分块的候选，并持久显示“已处理 x / y 分块”。提供商返回的实际用量进入用量页 KG 分类；缺失用量标为估算，并以请求输出预留保守计预算。
- `PersistentGraphKgProvider` 按有效会话/Agent KG 开关独立执行，不依赖文档检索是否开启。`GraphRetrievalStore` 从当前会话、已选择的文档/文件夹、会话工作区及明确全局选择的系统知识库定位存活证据；其它会话私有证据不可见，共享节点的跨来源 metadata 不注入。
- 查询匹配经过 NFKC、空白与大小写规范化；从来源索引和实体主键读取匹配种子，包含匹配与分词在可见候选内执行，不扫描实体全表。RAG 命中文档补充种子。默认最多 12 个种子、64 个节点、24 条关系；关系分数为 `(0.35 × 证据数/(证据数+1) + 0.65 × 置信度) / 距离`，默认一跳、最多两跳。
- 持久图与 JIT 按端点规范名、类型和有向关系身份融合。KG 文本默认限制 2,048 个保守估算 token，先移除低分完整关系；最终路径快照标注裁剪，注入文字仅由该快照生成。`PreparedPromptBudgetGate` 对已知模型窗口计量历史、工具、检索、图谱与附件的总请求；未知窗口的完整文档请求失败关闭，其它请求不虚构容量。
- 图谱页通过 `GraphSubgraphStore` 加载有界子图并支持实体搜索/邻居展开；画布与无障碍列表共用子图。布局在 `Dispatchers.Default` 执行 Barnes-Hut 近似斥力，画布按 ID 索引读取端点并用线段与矩形相交裁剪。首次/重置适配居中，页面不可见或收敛后暂停布局与装饰动画。设备帧率、功耗与内存需另行实测，不由复杂度或 JVM 计时推定。

```kotlin
// 文档菜单 → RagViewModel.extractKG(fileUuid)
//   → VectorizationQueue.enqueueDocumentReference(..., kgStrategy="full", skipVectorization=true)
//     → DocumentReferenceExtractor → GraphExtractor.extractCandidate → ValidatedGraph
//       → RoomDocumentIndexService 候选事务 → 图谱与 kgExtractedAt
```

### 2.5 Agent 引擎架构

```
┌──────────────────────────────────────────────────────────┐
│                    AgentLoopManager                        │
│                                                           │
│  User Input                                               │
│     │                                                     │
│     ▼                                                     │
│  ┌─────────────┐                                         │
│  │ Context     │ ← RAG检索 + KG查询 + 工具回传 + 历史摘要 │
│  │ Builder     │                                         │
│  └──────┬──────┘                                         │
│         │                                                 │
│         ▼                                                 │
│  ┌─────────────┐     ┌──────────────┐                    │
│  │ LLM Call    │────▶│ Tool Call    │                    │
│  │ (streamChat)│     │ Detected?    │                    │
│  └─────────────┘     └──────┬───────┘                    │
│                             │ Yes                         │
│                             ▼                             │
│                    ┌──────────────┐                       │
│                    │ Execute Tool │                       │
│                    │ (ToolRegistry)                      │
│                    └──────┬───────┘                       │
│                           │                               │
│                           ▼                               │
│                    ┌──────────────┐                       │
│                    │ Need         │── Yes ──→ LLM Call   │
│                    │ Approval?    │          (loop)       │
│                    └──────┬───────┘                       │
│                           │ No                            │
│                           ▼                               │
│                    ┌──────────────┐                       │
│                    │ Return       │                       │
│                    │ Final Result │                       │
│                    └──────────────┘                       │
└──────────────────────────────────────────────────────────┘
```

**ToolRegistry 设计**：

```kotlin
interface Tool {
    val name: String
    val description: String
    val parameters: JsonSchema
    val riskLevel: RiskLevel         // LOW / MEDIUM / HIGH / CRITICAL
    suspend fun execute(args: String): ToolResult
}

enum class RiskLevel {
    LOW,        // 只读操作：搜索、检索
    MEDIUM,     // 本地写入：创建文件、保存文档
    HIGH,       // 网络操作：API调用、发送请求
    CRITICAL    // 系统操作：执行命令、修改配置
}

// 根据 ExecutionMode + RiskLevel 决定是否需要审批
fun needsApproval(executionMode: ExecutionMode, riskLevel: RiskLevel): Boolean {
    return when (executionMode) {
        ExecutionMode.AUTO -> riskLevel >= RiskLevel.HIGH
        ExecutionMode.SEMI -> riskLevel >= RiskLevel.MEDIUM
        ExecutionMode.MANUAL -> true
    }
}
```

---

## 3. 数据库设计

### 3.1 核心表（19 实体）

```
┌─────────────┐     ┌──────────────┐     ┌────────────────┐
│   agents    │     │   sessions   │     │   messages     │
├─────────────┤     ├──────────────┤     ├────────────────┤
│ id (PK)     │────▶│ agent_id(FK) │────▶│ session_id(FK) │
│ name        │     │ id (PK)      │     │ id (PK)        │
│ description │     │ title        │     │ role           │
│ system_prmpt│     │ model_id     │     │ content        │
│ model_id    │     │ is_pinned    │     │ thinking       │
│ icon        │     │ created_at   │     │ tool_calls     │
│ color       │     │ updated_at   │     │ rag_refs       │
│ avatar_path │     │ msg_count    │     │ token_usage    │
│ is_pinned   │     └──────────────┘     │ timestamp      │
│ temperature │                          └────────────────┘
│ top_p       │
│ max_tokens  │     ┌──────────────┐     ┌────────────────┐
│ rag_config  │     │  documents   │     │   folders      │
│ retr_config │     ├──────────────┤     ├────────────────┤
│ use_inherit │     │ id (PK)      │────▶│ id (PK)        │
│ exec_mode   │     │ folder_id(FK)│     │ name           │
│ skills      │     │ title        │     │ parent_id      │
│ created_at  │     │ content      │     │ created_at     │
└─────────────┘     │ summary      │     └────────────────┘
                    │ hash         │
┌─────────────┐     │ chunk_size   │     ┌────────────────┐
│  vectors    │     │ chunk_olap   │     │ kg_nodes       │
├─────────────┤     │ vectorized_at│     ├────────────────┤
│ id (PK)     │     │ created_at   │     │ id (PK)        │
│ doc_id (FK) │     └──────────────┘     │ label          │
│ session_id  │                          │ type           │
│ chunk_text  │     ┌──────────────┐     │ properties     │
│ embedding   │     │  kg_edges    │     │ created_at     │
│ chunk_index │     ├──────────────┤     └────────────────┘
│ created_at  │     │ id (PK)      │
└─────────────┘     │ source_id(FK)│     ┌────────────────┐
                    │ target_id(FK)│     │ context_summar │
┌─────────────┐     │ relation     │     ├────────────────┤
│  tags       │     │ weight       │     │ id (PK)        │
├─────────────┤     │ created_at   │     │ session_id(FK) │
│ id (PK)     │     └──────────────┘     │ summary_text   │
│ name        │                          │ range_start    │
│ color       │     ┌──────────────┐     │ range_end      │
└─────────────┘     │ doc_tags     │     │ created_at     │
                    ├──────────────┤     └────────────────┘
                    │ doc_id (FK)  │
                    │ tag_id (FK)  │     ┌────────────────┐
                    └──────────────┘     │ audit_logs     │
                                         ├────────────────┤
                                         │ id (PK)        │
                                         │ action         │
                                         │ entity_type    │
                                         │ entity_id      │
                                         │ details        │
                                         │ timestamp      │
                                         └────────────────┘
```

### 3.2 关键索引策略

```sql
-- 消息列表查询（最高频）
CREATE INDEX idx_messages_session_time ON messages(session_id, timestamp DESC);

-- Agent 会话列表
CREATE INDEX idx_sessions_agent_pinned ON sessions(agent_id, is_pinned DESC, updated_at DESC);

-- 向量检索（余弦相似度计算需要在应用层完成）
CREATE INDEX idx_vectors_doc ON vectors(doc_id);
CREATE INDEX idx_vectors_session ON vectors(session_id);

-- 全文搜索
CREATE VIRTUAL TABLE vectors_fts USING fts5(chunk_text, content=vectors, content_rowid=id);

-- 知识图谱关系查询
CREATE INDEX idx_edges_source ON kg_edges(source_id);
CREATE INDEX idx_edges_target ON kg_edges(target_id);
```

---

## 4. 导航架构

### 4.1 路由表

```
Welcome（一次性引导）
  └─→ MainTabScaffold
        ├─ Tab 0: CHAT（对话中枢）
        │     ├─ agent_hub              会话与助手分组 + 搜索（助手就地展开会话）
        │     ├─ chat_hero/{sId}        对话详情
        │     ├─ agent_edit/{aId}       Agent 编辑
        │     ├─ agent_rag/{aId}        Agent RAG 配置
        │     ├─ agent_retrieval/{aId}  Agent 高级检索
        │     ├─ session_settings/{sId} 会话设置
        │     └─ workspace/{sId}        工作区（未来 Artifacts）
        │
        ├─ Tab 1: LIBRARY（知识库）
        │     ├─ rag_library            文库主页
        │     ├─ rag_folder/{fId}/{name} 文件夹详情
        │     ├─ doc_editor/{dId}       文档编辑器
        │     ├─ knowledge_graph        知识图谱可视化
        │     ├─ rag_global_config      全局 RAG 配置
        │     ├─ rag_advanced           高级检索配置
        │     └─ rag_debug              RAG 调试面板
        │
        └─ Tab 2: SETTINGS（设置）
              ├─ settings_hub           设置主页
              ├─ provider_form/{pId}?   服务商配置
              ├─ provider_models/{pId}  模型列表
              ├─ local_models           本地模型管理
              ├─ theme_config           主题配置
              ├─ search_config          搜索配置
              ├─ skills_config          技能配置
              ├─ token_usage            Token 统计
              ├─ backup_settings        备份管理
              └─ developer_panel        开发者工具
```

### 4.2 导航类型安全

```kotlin
// 使用密封类模式确保编译期类型安全
sealed class NavRoute(val route: String) {
    object AgentHub : NavRoute("agent_hub")
    data class ChatHero(val sessionId: String) : NavRoute("chat_hero/$sessionId")
    // ... 所有路由
}
```

---

## 5. 跨端架构路线（CMP 渐进式迁移）

### 5.1 当前状态

```
native-ui/
├── app/src/main/java/com/promenar/infinity/
│   ├── data/          ← 100% Android 依赖（Room / OkHttp / Context）
│   ├── ui/            ← 100% Compose Material3（Android-only API）
│   └── navigation/    ← Compose Navigation（Android-only）
```

### 5.2 渐进式提取路径

```
Step 1: 提取纯 Kotlin 模块
  └─ :core-model        Domain Models（无 Android 依赖）
  └─ :core-protocol     LLM 协议定义 + 序列化

Step 2: 抽取 commonMain
  └─ :shared
       ├─ commonMain/   ← Domain Models + Use Cases + Repository 接口
       ├─ androidMain/  ← Room DAO + OkHttp 实现（expect/actual）
       └─ iosMain/      ← 未来 iOS 实现

Step 3: UI 层迁移
  └─ Compose Multiplatform → 共享 UI 代码
  └─ 平台特定 UI（Android MD3 / iOS SwiftUI bridge）

Step 4: 数据层迁移
  └─ Room → SQLDelight（跨平台数据库）
  └─ OkHttp → Ktor Client（跨平台网络）
```

**原则**：每步迁移保持功能可用，不阻塞 Android 端交付。

---

## 6. 安全与隐私设计

### 6.1 API Key 管理

```
┌─────────────┐     ┌─────────────────────┐     ┌─────────────┐
│  User Input │────▶│ SecretStore          │────▶│ LLM Client  │
│  (明文 Key) │     │ AES-256-GCM ciphertext│     │ (按请求读取) │
│             │     │ + random IV/version │     │             │
│             │     │ Android Keystore     │     │ Key 只在    │
│             │     │ non-exportable key   │     │ 内存短暂停留 │
└─────────────┘     └─────────────────────┘     └─────────────┘
```

- 普通 SharedPreferences 只保存格式版本、随机 IV 和密文，不保存明文凭据。
- UI 持久状态只保存“是否已配置”。输入框默认显示 `****`；完整值仅在当前页面经用户显式操作短暂进入可清零缓冲区，失焦或离开页面后重新隐藏。
- `SecretCatalog` 为 Provider API Key、Vertex service account、Tavily、Embedding、WebDAV 等凭据分配稳定标识；路由层只在请求开始时解析当前 Provider 的凭据。
- 备份按层选择，密钥层默认不选，不含密钥的备份也可单独加密；选中密钥层时强制设置密码。加密使用 PBKDF2-HMAC-SHA256（600,000 次）派生 AES-256 密钥，按 1 MiB 分块 AES-GCM 加密并认证块序号与末块标记。个人与 Agent 头像以随机 ID 引用随核心层打包，不携带源设备路径；WebDAV 只提供用户触发的流式上传、列表与恢复，不声明后台同步。

### 6.2 数据所有权

- 所有数据存储在设备本地（SQLite + FileSystem）
- 用户可随时导出完整数据
- 用户可随时删除任意数据（含向量、图谱）并验证清理
- 网络传输仅发送必要上下文（用户消息 + RAG 片段 + System Prompt）
- 不上报任何遥测数据（除非用户主动启用崩溃报告）

### 6.3 Release 网络与日志边界

- Release 与 Debug 的网络安全配置 base-config 均允许明文 HTTP（Manifest `usesCleartextTraffic=true`），提供商、MCP、WebDAV 与 Linux 自定义镜像可使用 http 地址；地址输入框对离开本机且不经 tailnet 的 http 地址显示“未加密”提示。
- Debug 日志统一经 `InfinityLogger` 与 `SensitiveDataRedactor`；Release 输出入口受编译期门禁并由 R8 剥离。
- Release APK 验证器对包身份、单一签名者、证书指纹、ZIP 边界、GGUF/llama/ggml 制品、常见密钥/Authorization/私钥/本机路径和 SHA-256 执行 fail-closed 检查。

---

## 7. 测试策略

| 层级 | 测试类型 | 工具 | 覆盖率目标 |
|------|---------|------|-----------|
| Domain | Unit Test | JUnit5 + MockK | > 90% |
| Data (Repository) | Integration Test | Room in-memory + OkHttp MockWebServer | > 70% |
| UI (ViewModel) | Unit Test | Turbine (Flow test) + MockK | > 70% |
| UI (Compose) | Screenshot Test | Paparazzi / Roborazzi | 关键页面 100% |
| E2E | Instrumentation Test | Espresso / Compose Testing | 核心流程 100% |

---

## 8. v0.2.1-beta 生成与发行剖面

### 8.1 单一生成任务源

```mermaid
flowchart LR
    UI[ChatRoute / ChatViewModel] -->|submit/observe/cancel| GC[GenerationCoordinator]
    GC --> RUNNER[ChatGenerationRunner]
    RUNNER --> ROUTER[ProviderRequestRouter]
    ROUTER --> LLM[UnifiedLlmClient / Protocol]
    RUNNER --> DATA[(Room / Message / Tool Ledger)]
    FGS[GenerationForegroundService] -->|observe same snapshot| GC
    FGS --> NOTIFY[Notification: return / stop]
```

- `GenerationCoordinator` 以 `sessionId` 管理任务、快照、取消和终态；重复提交同一任务不会创建第二条网络请求。
- `v0.2.1-beta` 全局只允许一个活动生成任务，避免 Provider 重复计费、通知竞争和持久化竞态。
- `ChatViewModel` 负责提交与呈现，不再以自身生命周期独占网络协程；Foreground Service 与 UI 观察同一快照，不复制请求。
- 取消在协议、背压发送、工具审批、持久化和清理边界显式传播；普通失败映射为稳定错误码，用户界面不消费原始异常消息。

### 8.2 后台能力边界

Foreground Service 只承接用户在可见界面发起的当前任务，支持切后台、锁屏、旋转与 Activity 重建。通知可返回准确会话或停止生成；权限不可用时退化为仅前台生成。设备重启续传、多会话并行、无人值守队列和定时任务均为非目标。

### 8.3 事务索引与恢复

- 文档索引先在事务外构建隔离的向量/KG 候选，事务内复核源哈希后原子切换；失败保留旧索引。
- 永久删除统一清理向量、FTS、KG、标签和任务记录；稳定 tombstone 覆盖数据库提交后、物理删除前的进程死亡窗口。
- SAF 与系统分享通过共享 importer 和持久 inbox 逐项处理，支持去重、部分失败、容量重试及进程恢复。

### 8.4 发行构建与辅助工具

- `minSdk=31`、`targetSdk=36`、`versionCode=3`、`versionName=0.2.1-beta`；Release 使用环境变量签名并开启 R8/资源收缩。
- GGUF/llama.cpp 不进入本版稳定能力，Release 关闭本地推理并由 APK 扫描器拒绝相关制品。
- `scripts/infinity-metro-tui.js` 是 Debug Metro 事件的开发者 TUI，不是最终用户 CLI，也不承担 Android UI 的业务功能对等。
- 最终放行证据以 [v0.2.1-beta validation](release/v0.2.1-beta-validation.md) 为准；当前签名 APK 必须同时通过冷安装和从冻结 `v0.1-beta` 同签名 APK 的 `adb install -r` 覆盖升级数据继承。未执行项必须保持 `PENDING`，编译或专项单测不能代替签名 APK、minified 业务验真和视觉矩阵。

---

**文档维护者**: AI Assistant
**最后更新**: 2026-08-25
**下次审查**: `v0.2.1-beta` 物理真机验收后
