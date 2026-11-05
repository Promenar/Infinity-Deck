package com.promenar.infinity.data.rag

import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.agent.AgentRetrievalMode
import com.promenar.infinity.data.agent.KnowledgeFileAccess
import com.promenar.infinity.data.model.RagOptions

/** 自动检索与工具共用开关、范围和智能体上限；全局开关只用于创建会话。 */
fun resolveEffectiveRagOptions(
    sessionOptions: RagOptions?,
    temporaryOptions: RagOptions? = null,
    memoryPolicy: AgentMemoryPolicy = AgentMemoryPolicy(),
): RagOptions {
    val session = sessionOptions ?: RagOptions()
    val temporary = temporaryOptions ?: RagOptions()
    return memoryPolicy.clamp(RagOptions(
        enableMemory = temporary.enableMemory && session.enableMemory,
        enableDocs = temporary.enableDocs && session.enableDocs,
        activeDocIds = if (temporaryOptions == null || temporary.activeDocIds.isEmpty()) session.activeDocIds
            else session.activeDocIds.filter { it in temporary.activeDocIds },
        activeFolderIds = if (temporaryOptions == null || temporary.activeFolderIds.isEmpty()) session.activeFolderIds
            else session.activeFolderIds.filter { it in temporary.activeFolderIds },
        memoryScope = (temporaryOptions ?: session).effectiveMemoryScope,
        graphScope = (temporaryOptions ?: session).graphScope,
        graphBinding = session.graphBinding,
        globalMemoryEnabled = session.globalMemoryEnabled && (temporaryOptions?.globalMemoryEnabled ?: true),
        globalMemoryScope = if (temporaryOptions == null) session.globalMemoryScope else minOf(session.globalMemoryScope, temporary.globalMemoryScope),
        activeMemoryMode = session.activeMemoryMode,
        selectedCollectionIds = if (temporaryOptions?.documentScope == com.promenar.infinity.data.model.DocumentScope.WORKSPACE) emptyList() else session.selectedCollectionIds?.let { selected ->
            temporaryOptions?.selectedCollectionIds?.let { selected.intersect(it.toSet()).toList() } ?: selected
        } ?: temporaryOptions?.selectedCollectionIds,
        documentScope = if (temporaryOptions == null) session.effectiveDocumentScope else minOf(session.effectiveDocumentScope, temporary.effectiveDocumentScope),
        enableRerank = if (sessionOptions == null) temporary.enableRerank else session.enableRerank,
        enableKnowledgeGraph = if (sessionOptions == null) temporary.enableKnowledgeGraph else session.enableKnowledgeGraph,
    ))
}

fun effectiveRetrievalMode(config: AgentRetrievalConfig?, toolsEnabled: Boolean): AgentRetrievalMode =
    if (toolsEnabled) config?.retrievalMode ?: AgentRetrievalMode.AUTO else AgentRetrievalMode.AUTO

fun effectiveKnowledgeFileAccess(config: AgentRetrievalConfig?, toolsEnabled: Boolean): KnowledgeFileAccess =
    if (toolsEnabled) config?.knowledgeFileAccess ?: KnowledgeFileAccess.ON_REQUEST else KnowledgeFileAccess.OFF
