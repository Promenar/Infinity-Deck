package com.promenar.infinity.data.generation

import com.promenar.infinity.data.remote.protocol.PromptRequest

/** 对同一文本累计分配 ASCII 余数；拆分类别不改变原估算器的四字符取整。 */
object ContextUsageBreakdown {
    private val systemIds = listOf("instructions", "project_rules", "runtime_permissions", "tool_guidance", "skill_catalog", "knowledge_catalog", "history_summary")
    private val conversationIds = listOf("user_body", "assistant_body", "reasoning", "tool_calls", "tool_results", "media")

    fun estimate(
        prompt: PromptRequest,
        systemSections: List<ContextPromptSection>,
        foldedToolResults: Int,
        modelContextTokens: Int,
    ): ContextUsageEstimate {
        val system = systemIds.associateWith { 0L }.toMutableMap()
        val tools = linkedMapOf("builtin" to 0L)
        val conversation = conversationIds.associateWith { 0L }.toMutableMap()
        val turn = linkedMapOf("memory" to 0L, "knowledge" to 0L, "other" to 0L)
        fun MutableMap<String, Long>.add(id: String, count: Long) { this[id] = getOrDefault(id, 0L) + count }
        prompt.messages.forEach { message ->
            if (message.role == "system") {
                system.add("instructions", ApproximateTokenEstimator.MESSAGE_OVERHEAD_TOKENS.toLong())
                val parts = systemSections.takeIf { it.joinToString("") { section -> section.content } == message.content }
                    ?: listOf(ContextPromptSection("instructions", message.content))
                partition(parts.map { (it.id.takeIf(system::containsKey) ?: "instructions") to it.content }) { id, count -> system.add(id, count.toLong()) }
            } else {
                val body = when (message.role) { "user" -> "user_body"; "tool" -> "tool_results"; else -> "assistant_body" }
                conversation.add(body, ApproximateTokenEstimator.MESSAGE_OVERHEAD_TOKENS.toLong() + ApproximateTokenEstimator.estimate(message.content))
                if (message.role == "user" && !message.turnContext.isNullOrEmpty()) {
                    turn.add("other", 1)
                    partition(turnSections(message.turnContext)) { id, count -> turn.add(id, count.toLong()) }
                }
            }
            conversation.add("reasoning", ApproximateTokenEstimator.estimate(message.reasoning.orEmpty()).toLong())
            message.toolCalls.orEmpty().forEach {
                conversation.add("tool_calls", ApproximateTokenEstimator.estimate(it.name).toLong() + ApproximateTokenEstimator.estimate(it.arguments))
            }
            conversation.add("media", ApproximateTokenEstimator.mediaTokens(message))
        }
        prompt.tools.orEmpty().forEach { tool ->
            val mcp = tool.sourceId == "mcp" || tool.mcpServerId != null || tool.runtimeToolId.startsWith("mcp:")
            val server = tool.mcpServerId?.takeIf(String::isNotBlank)
                ?: tool.runtimeToolId.takeIf { it.startsWith("mcp:") }?.removePrefix("mcp:")?.substringBefore(':')?.takeIf(String::isNotBlank)
                ?: "unknown"
            tools.add(if (mcp) "mcp:$server" else "builtin", ApproximateTokenEstimator.estimate(tool.function.name + tool.function.description + tool.function.parameters).toLong())
        }
        // 超大输入统一按剩余 Int 预算分配，父子与旧字段在饱和时也保持一致。
        var remaining = Int.MAX_VALUE.toLong()
        fun leaf(id: String, count: Long): ContextUsageCategory {
            val tokens = minOf(count, remaining).toInt()
            remaining -= tokens
            return ContextUsageCategory(id, tokens)
        }
        fun group(id: String, children: List<ContextUsageCategory>) = ContextUsageCategory(id, children.sumOf { it.tokens }, children)
        val systemCategory = group("system_prompt", system.map { leaf(it.key, it.value) })
        val toolsCategory = group("tool_definitions", tools.map { leaf(it.key, it.value) })
        val conversationCategory = group("conversation", conversation.map { leaf(it.key, it.value) } +
            group("turn_context", turn.map { leaf(it.key, it.value) }))
        val summary = systemCategory.children.single { it.id == "history_summary" }.tokens
        val rag = conversationCategory.children.last().children.single { it.id == "knowledge" }.tokens
        return ContextUsageEstimate(
            systemTokens = systemCategory.tokens - summary + toolsCategory.tokens,
            summaryTokens = summary,
            conversationTokens = conversationCategory.tokens - rag,
            ragTokens = rag,
            foldedToolResults = foldedToolResults,
            modelContextTokens = modelContextTokens,
            categories = listOf(systemCategory, toolsCategory, conversationCategory),
        )
    }

    private fun partition(parts: List<Pair<String, String>>, add: (String, Int) -> Unit) {
        var ascii = 0L
        parts.forEach { (id, content) ->
            var other = 0
            val before = (ascii + 3) / 4
            content.forEach { if (it.code < 0x80) ascii++ else other++ }
            add(id, ((ascii + 3) / 4 - before + other).toInt())
        }
    }

    /** 历史 turn_context 只有文本；按客户端生成的资料区与二级标题恢复类别。 */
    private fun turnSections(text: String): List<Pair<String, String>> {
        var inReferences = false
        var category = "other"
        return text.splitToSequence('\n').mapIndexed { index, line ->
            val content = if (index == 0) line else "\n$line"
            when (line) {
                "<reference_material>" -> { inReferences = true; category = "other" }
                "</reference_material>" -> { inReferences = false; category = "other" }
                "### Long-term memory" -> if (inReferences) category = "memory"
                "### Available Material Snapshot", "### Retrieved Context", "### Knowledge Graph Relations", "### Web Search Results" -> if (inReferences) category = "knowledge"
            }
            category to content
        }.toList()
    }
}
