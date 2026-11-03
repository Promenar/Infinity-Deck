package com.promenar.infinity.data.generation

import com.promenar.infinity.data.remote.protocol.PromptRequest

/** 对同一文本累计分配 ASCII 余数；拆分类别不改变原估算器的四字符取整。 */
object ContextUsageBreakdown {
    private val systemIds = listOf("assistant_prompt", "session_instructions", "project_rules", "runtime", "permissions",
        "tool_guidance", "skill_catalog", "knowledge_catalog", "history_summary")
    private val conversationIds = listOf("user_body", "assistant_body", "reasoning", "tool_calls", "tool_results", "media",
        "memory", "retrieval", "knowledge_graph", "system_time", "snapshot", "web_search", "task_plan", "status_board", "skill_body")
    private val knowledgeIds = setOf("snapshot", "retrieval", "knowledge_graph", "web_search")
    private data class Fragment(val id: String, val content: String, val knowledge: Boolean = false)

    fun estimate(
        prompt: PromptRequest,
        systemSections: List<ContextPromptSection>,
        foldedToolResults: Int,
        modelContextTokens: Int,
    ): ContextUsageEstimate {
        val system = systemIds.associateWith { 0L }.toMutableMap()
        val tools = linkedMapOf("builtin" to 0L)
        val conversation = conversationIds.associateWith { 0L }.toMutableMap()
        val knowledge = mutableMapOf<String, Long>()
        fun MutableMap<String, Long>.add(id: String, count: Long) { this[id] = getOrDefault(id, 0L) + count }
        prompt.messages.forEach { message ->
            if (message.role == "system") {
                val sections = systemSections.takeIf { it.joinToString("") { section -> section.content } == message.content }
                    ?: listOf(ContextPromptSection("assistant_prompt", message.content))
                val parts = sections.map { Fragment(it.id.takeIf(system::containsKey) ?: "assistant_prompt", it.content) }
                system.add(parts.firstOrNull { it.content.isNotEmpty() }?.id ?: "assistant_prompt", ApproximateTokenEstimator.MESSAGE_OVERHEAD_TOKENS.toLong())
                partition(parts) { part, count -> system.add(part.id, count.toLong()) }
            } else {
                val body = when (message.role) { "user" -> "user_body"; "tool" -> "tool_results"; else -> "assistant_body" }
                conversation.add(body, ApproximateTokenEstimator.MESSAGE_OVERHEAD_TOKENS.toLong() + ApproximateTokenEstimator.estimate(message.content))
                if (message.role == "user" && !message.turnContext.isNullOrEmpty()) {
                    val parts = turnSections(message.turnContext)
                    conversation.add(parts.first().id, 1)
                    partition(parts) { part, count ->
                        conversation.add(part.id, count.toLong())
                        if (part.knowledge) knowledge.add(part.id, count.toLong())
                    }
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
                ?: tool.runtimeToolId.takeIf { it.startsWith("mcp:") }?.removePrefix("mcp:")
                    ?.takeIf { ':' in it }?.substringBefore(':')?.takeIf(String::isNotBlank)
            tools.add(if (mcp && server != null) "mcp:$server" else "builtin", ApproximateTokenEstimator.estimate(tool.function.name + tool.function.description + tool.function.parameters).toLong())
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
        var rag = 0
        val conversationCategory = group("conversation", conversation.map { (id, count) ->
            val item = leaf(id, count)
            // 同一叶子的结构开销先占预算，知识资料只计入实际保留下来的部分。
            val nonKnowledge = count - knowledge.getOrDefault(id, 0L)
            rag += (item.tokens.toLong() - nonKnowledge).coerceAtLeast(0).toInt()
            item
        })
        val summary = systemCategory.children.single { it.id == "history_summary" }.tokens
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

    private fun partition(parts: List<Fragment>, add: (Fragment, Int) -> Unit) {
        var ascii = 0L
        parts.forEach { part ->
            var other = 0
            val before = (ascii + 3) / 4
            part.content.forEach { if (it.code < 0x80) ascii++ else other++ }
            add(part, ((ascii + 3) / 4 - before + other).toInt())
        }
    }

    /** 按构建器的块顺序恢复持久化文本；结构前缀跟随下一块，尾标签跟随当前块。 */
    private fun turnSections(text: String): List<Fragment> {
        var inReferences = false
        var category = ""
        // 构建顺序：时间、资料、计划、状态板、点名技能；后段正文不重新解释前段标题。
        var phase = 0
        var inTaskTree = false
        val parts = text.splitToSequence('\n').mapIndexed { index, line ->
            val content = if (index == 0) line else "\n$line"
            val marker = line.removeSuffix("\r")
            // 任务树包含原样步骤正文；固定计划尾部决定边界，内部代码围栏不改变扫描状态。
            if (phase == 2 && marker == "### Task Tree") inTaskTree = true
            if (phase == 2 && marker == "[Use get_plan to re-read the full task tree when context is truncated.]") inTaskTree = false
            var structural = false
            if (!inTaskTree) when {
                phase == 0 && marker.startsWith("[System Time: ") -> category = "system_time"
                phase == 0 && marker == "## Reference Material" -> { phase = 1; category = "" }
                phase <= 1 && marker == "<reference_material>" -> { phase = 1; inReferences = true; category = "" }
                inReferences && marker == "</reference_material>" -> { inReferences = false; structural = true }
                inReferences -> when (marker) {
                    "### Available Material Snapshot" -> category = "snapshot"
                    "### Long-term memory" -> category = "memory"
                    "### Retrieved Context" -> category = "retrieval"
                    "### Knowledge Graph Relations" -> category = "knowledge_graph"
                    "### Web Search Results" -> category = "web_search"
                }
                phase <= 2 && marker == "## Active Task Plan" -> { phase = 2; category = "task_plan" }
                phase <= 3 && marker.startsWith("<state_board revision=") -> { phase = 3; category = "status_board" }
                marker == "## Requested Skill" -> { phase = 4; category = "skill_body" }
                marker == "</turn_context>" -> structural = true
            }
            Fragment(category, content, !structural && inReferences && category in knowledgeIds)
        }.toMutableList()
        var nextId = parts.lastOrNull { it.id.isNotEmpty() }?.id ?: "snapshot"
        for (index in parts.indices.reversed()) {
            if (parts[index].id.isEmpty()) parts[index] = parts[index].copy(id = nextId) else nextId = parts[index].id
        }
        return parts
    }
}
