package com.promenar.infinity.domain.model

enum class MessageRole { USER, ASSISTANT, SYSTEM, TOOL }
enum class ExecutionMode { WORKSPACE, FULL_ACCESS }
enum class ProtocolType { OPENAI, ANTHROPIC, VERTEX_AI }
enum class ModelType { CHAT, REASONING, IMAGE, EMBEDDING, RERANK, VIDEO, AUDIO }
enum class ModelCapability { CHAT, REASONING, VISION, WEB_SEARCH, EMBEDDING, RERANK, IMAGE_GEN, CODE, FUNCTION_CALLING, TOOL_USE, JSON_MODE, STREAMING }
enum class ToolCallStatus { PENDING, RUNNING, COMPLETED, FAILED }
