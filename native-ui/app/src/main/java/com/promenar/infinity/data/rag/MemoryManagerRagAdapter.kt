package com.promenar.infinity.data.rag

import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.RagUsage
import com.promenar.infinity.ui.chat.manager.RagProvider
import com.promenar.infinity.ui.chat.manager.RagRetrievalResult

class MemoryManagerRagAdapter(
    private val memoryManagerProvider: () -> MemoryManager
) : RagProvider {
    constructor(memoryManager: MemoryManager) : this({ memoryManager })

    override suspend fun retrieveContext(
        query: String,
        sessionId: String,
        options: RagOptions,
        onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)?
    ): RagRetrievalResult = retrieve(query, sessionId, options, onProgress, allowQueryRewrite = true)

    override suspend fun retrieveForTool(
        query: String,
        sessionId: String,
        options: RagOptions,
        onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)?
    ): RagRetrievalResult = retrieve(query, sessionId, options, onProgress, allowQueryRewrite = false)

    private suspend fun retrieve(
        query: String,
        sessionId: String,
        options: RagOptions,
        onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)?,
        allowQueryRewrite: Boolean,
    ): RagRetrievalResult {
        val memoryManager = memoryManagerProvider()
        // 检索参数一律取全局；会话只提供开关与范围，旧会话调优字段不再读取。
        val global = memoryManager.ragConfig
        val retrieveOptions = MemoryManager.RetrieveOptions(
            enableMemory = options.enableMemory,
            enableDocs = options.enableDocs,
            activeDocIds = options.activeDocIds,
            activeFolderIds = options.activeFolderIds,
            memoryScope = options.effectiveMemoryScope,
            documentScope = options.effectiveDocumentScope,
            sessionId = sessionId,
            enableRerank = options.enableRerank,
            allowQueryRewrite = allowQueryRewrite,
            ragOptions = options,
            configOverride = global.copy(
                enableMemory = options.enableMemory,
                enableDocs = options.enableDocs,
                enableKnowledgeGraph = options.enableKnowledgeGraph ?: global.enableKnowledgeGraph,
                enableRerank = options.enableRerank,
            )
        )

        val result = memoryManager.retrieveContext(query, sessionId, retrieveOptions, onProgress)

        val estimatedTokens = if (result.context.isNotEmpty()) {
            (result.context.length / 4).coerceAtLeast(1)
        } else {
            0
        }

        val usage = if (estimatedTokens > 0) {
            RagUsage(ragSystem = estimatedTokens, isEstimated = true)
        } else {
            null
        }

        return RagRetrievalResult(result.context, result.references, usage, result.trace, result.resolvedScope)
    }
}
