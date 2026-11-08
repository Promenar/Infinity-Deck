# Infinity Deck Kotlin 原生规范

> 本文件是 OpenCode 的项目级指令，定义了从 TypeScript/React Native 迁移到 Kotlin/Compose 的所有规范。

## 项目概述

Infinity Deck（原名 Nexara）是一个 AI 助手 Android 应用，正在从 React Native 前端迁移到纯 Kotlin/Jetpack Compose 原生前端。
本模块 (`native-ui/`) 是完全独立的 Android 项目，零 RN 依赖。

## 架构约束

- 所有代码位于 `app/src/main/java/com/promenar/infinity/`
- 包名根：`com.promenar.infinity`（应用 ID 同名）
- UI 层：Jetpack Compose + Material 3
- 网络：Ktor Client（Multiplatform-Ready）
- 图片：Coil 3
- 存储：DataStore Preferences + Room
- 序列化：kotlinx.serialization
- 测试：JUnit 5 + kotlinx-coroutines-test + Ktor Mock + Google Truth

## 目录结构

```
app/src/main/java/com/promenar/infinity/
├── MainActivity.kt
├── navigation/
├── ui/theme|common|chat|hub|rag|welcome|renderer
├── data/
│   ├── model/            # 数据类 + 模型规格库
│   ├── remote/
│   │   ├── parser/       # SSE 流解析管线（StreamParser, ThinkingDetector 等）
│   │   ├── protocol/     # 协议实现（OpenAI, Anthropic, VertexAI）
│   │   └── provider/     # Provider 聚合层（路由到对应协议）
│   ├── local/            # 本地存储（StateStore, DataStore, Room DB）
│   └── repository/       # 仓库层
└── bridge/               # (仅集成阶段) RN ↔ Native 桥接
```

## Provider 架构：三协议 + 可扩展

### 背景

原 RN 端因框架 Markdown 渲染能力限制，被迫为每个模型厂商维护独立 Provider 以适配输出差异。
Kotlin 原生 Markdown 渲染引擎可统一兼容各家模型输出，因此**简化为三大协议实现**：

### 协议层

| 协议 | 目录 | 测试端点 | 覆盖范围 |
|------|------|---------|---------|
| **OpenAI 兼容协议** | `protocol/OpenAIProtocol.kt` | MiniMax M2.7 OpenAI 端点 | OpenAI、DeepSeek、Moonshot、GLM、MiniMax、所有兼容端点 |
| **Anthropic 兼容协议** | `protocol/AnthropicProtocol.kt` | MiniMax M2.7 Anthropic 端点 | Anthropic Claude、Anthropic 兼容端点（含 thinking 模式） |
| **VertexAI 协议** | `protocol/VertexAIProtocol.kt` | Google Vertex AI | Gemini 系列（含 RSA-JWT OAuth2 认证） |

### 扩展机制

```kotlin
// 协议接口（所有协议实现此接口）
interface LlmProtocol {
    val id: String
    suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk>
    suspend fun sendPromptSync(request: PromptRequest): PromptResponse
}

// Provider 聚合层（路由到对应协议）
class LlmProvider(private val protocol: LlmProtocol) {
    suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> =
        protocol.sendPrompt(request)
}

// 后续扩展：只需新增 LlmProtocol 实现即可
// class CustomProtocol(...) : LlmProtocol { ... }
```

### 原多 Provider 的简化映射

| 原 TS Provider | 迁移后协议 | 备注 |
|----------------|-----------|------|
| moonshot.ts | OpenAIProtocol | OpenAI 兼容 |
| openai.ts | OpenAIProtocol | 原生 OpenAI |
| openai-compatible.ts | OpenAIProtocol | 通用兼容层 |
| deepseek.ts | OpenAIProtocol | OpenAI 兼容 + thinking 标签处理 |
| gemini.ts | VertexAIProtocol | Vertex AI 直连 |
| vertexai.ts | VertexAIProtocol | 含 RSA-JWT 签名认证 |
| claude（原未实现） | AnthropicProtocol | Anthropic 兼容 |

**原 Provider 中的模型输出适配逻辑**（如 DeepSeek 的思考模式特殊处理）移至 `parser/` 层的 StreamParser 中统一处理。

## 向量数据库与知识图谱技术选型

### 向量存储

| 组件 | 选型 | 说明 |
|------|------|------|
| **向量存储** | **Room + FloatArray BLOB** | 复用 TS 端已有方案，SQLite 存储 Float32Array |
| **相似度计算** | **纯 Kotlin 余弦相似度** | 无需 C++ TurboModule，Kotlin 直接操作 FloatArray |
| **全文检索** | **Room FTS4** | SQLite 内建 FTS5/FTS4 虚拟表 |
| **混合检索** | **向量 + FTS4 加权融合** | 复用 TS 端的 RRF (Reciprocal Rank Fusion) 策略 |
| **Embedding 客户端** | **Ktor + OpenAI 兼容协议** | 调用 embedding API |

### 知识图谱

| 组件 | 选型 | 说明 |
|------|------|------|
| **图存储** | **Room 三表（nodes/edges/jit_cache）** | 复用 TS 端 Schema |
| **实体抽取** | **LLM 驱动（JSON Mode）** | 通过 LlmProtocol 调用，解析结构化 JSON 输出 |
| **查询重写** | **Kotlin 实现 HyDE/Multi-Query** | 纯算法，无外部依赖 |
| **Reranker** | **LLM 驱动 或 API 调用** | 通过 LlmProtocol 的 rerank 端点（如支持） |
| **JIT 动态建图** | **Room + 内存 LRU 缓存** | 复用 TS 端的 TTL 缓存策略 |

### 数据库 Schema 迁移

```
TS 端 (OP-SQLite, 13 表) → Kotlin 端 (Room, 相同 Schema)
├── sessions          → @Entity Session
├── messages          → @Entity Message
├── attachments       → @Entity Attachment
├── folders           → @Entity Folder
├── documents         → @Entity Document
├── vectors           → @Entity Vector (BLOB FloatArray)
├── vectors_fts       → @Fts4 (FTS 虚拟表)
├── context_summaries → @Entity ContextSummary
├── tags              → @Entity Tag
├── document_tags     → @Entity DocumentTag (交叉表)
├── kg_nodes          → @Entity KgNode
├── kg_edges          → @Entity KgEdge
├── kg_jit_cache      → @Entity KgJitCache
├── vectorization_tasks → @Entity VectorizationTask
├── audit_logs        → @Entity AuditLog
└── artifacts         → @Entity Artifact
```

## 测试配置

### 配置文件位置

- **LLM 测试配置**: `test-resources/llm-test-config.json`（已在 .gitignore 中排除）
- **VertexAI 私钥**: `test-resources/vertexai/test.json`（将你的私钥文件改名为 test.json 放入此目录）

### 配置文件格式

```json
{
  "providers": {
    "openai_compatible": {
      "baseUrl": "...", "apiKey": "...", "modelId": "..."
    },
    "anthropic_compatible": {
      "baseUrl": "...", "apiKey": "...", "modelId": "..."
    },
    "vertexai": {
      "serviceAccountKeyPath": "test-resources/vertexai/test.json",
      "projectId": "...", "location": "us-central1",
      "modelId": "gemini-3-flash-preview"
    }
  }
}
```

### 测试读取方式

```kotlin
// 测试基类中读取配置
val config = Json.decodeFromString<TestConfig>(
    File("test-resources/llm-test-config.json").readText()
)
```

## TS → Kotlin 映射

| TS | Kotlin |
|----|--------|
| `interface` | `interface` 或 `data class` |
| `type union` | `sealed class` |
| `Promise<T>` | `suspend fun(): T` |
| `EventEmitter` | `SharedFlow / Channel` |
| `useState` | `mutableStateOf / MutableStateFlow` |
| `Array<T>` | `List<T>` |
| `Record<K,V>` | `Map<K,V>` |

## 迁移顺序

### B：确定性模块（不需要 API Key）
1. stream-parser.ts → parser/StreamParser.kt
2. response-normalizer.ts → parser/ResponseNormalizer.kt
3. error-normalizer.ts → parser/ErrorNormalizer.kt
4. message-formatter.ts → parser/MessageFormatter.kt
5. stream-buffer.ts → parser/StreamBufferManager.kt
6. thinking-detector.ts → parser/ThinkingDetector.kt
7. model-specs.ts → model/ModelSpecs.kt

### C：协议层（需读取 test-resources/llm-test-config.json）
8. LlmProtocol 接口 + PromptRequest/StreamChunk 数据类
9. OpenAIProtocol（用 MiniMax M2.7 OpenAI 端点测试）
10. AnthropicProtocol（用 MiniMax M2.7 Anthropic 端点测试）
11. VertexAIProtocol（用 test.json 私钥测试 gemini-3-flash-preview）
12. LlmProvider 聚合层 + 路由逻辑

### D：集成层
13. Chat Store 拆分为 6 个 ViewModel
14. RAG 流水线（向量存储 + FTS4 + Embedding + Reranker）
15. Knowledge Graph（实体抽取 + 图存储 + JIT 缓存）
16. Agent Loop 状态机

### E：数据层
17. Room Schema + 15 张表 + 迁移脚本
18. Repository 层
19. 数据迁移脚本（OP-SQLite → Room）

## 排除范围
- Workbench 服务器（远期）
- web-client/（独立项目）

## 本地模型推理（待开发）

**当前状态**：`LocalModelsScreen` 为纯占位符，零实际实现。详见 `.agent/plans/20260510-local-model-implementation.md`。

**技术选型**：llama.cpp + JNI 绑定 + GGUF 格式，Vulkan GPU 加速。

**核心模块**（待实现）：
- `data/local/inference/` — 推理引擎、模型管理、GGUF 解析
- `data/remote/protocol/LocalProtocol.kt` — 实现 `LlmProtocol` 接口
- `ui/settings/LocalModelsViewModel.kt` — 模型管理 UI 状态
- `cpp/` — JNI 桥接层

## 编译命令
```bash
.\gradlew.bat :app:compileSideloadDebugKotlin
.\gradlew.bat :app:testSideloadDebugUnitTest :app:testPlayDebugUnitTest
.\gradlew.bat :app:assembleSideloadDebug :app:assemblePlayDebug
.\gradlew.bat :app:assembleSideloadRelease :app:assemblePlayRelease
```

签名构建需要已授权的安全签名环境。风味、合并清单权限检查、设备测试与产物路径以根级 [发行渠道构建说明](../docs/distribution-builds.md) 为准。

## TS 源码参考位置（只读）
`../src/`
