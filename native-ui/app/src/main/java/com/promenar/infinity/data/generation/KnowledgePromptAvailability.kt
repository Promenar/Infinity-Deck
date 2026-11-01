package com.promenar.infinity.data.generation

import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.agent.AgentRetrievalMode
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.rag.effectiveRetrievalMode
import com.promenar.infinity.data.rag.resolveEffectiveRagOptions

/** 工具目录只随权限和检索配置变化；索引为空时由工具返回空结果。 */
fun knowledgeSearchAvailableForPrompt(session: Session, config: AgentRetrievalConfig?): Boolean {
    if (effectiveRetrievalMode(config, session.options.toolsEnabled) == AgentRetrievalMode.AUTO) return false
    val options = resolveEffectiveRagOptions(session.ragOptions, memoryPolicy = config?.memoryPolicy ?: AgentMemoryPolicy())
    return options.enableDocs || options.enableMemory
}
