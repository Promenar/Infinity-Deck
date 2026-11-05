package com.promenar.infinity.data.rag

import com.promenar.infinity.data.local.db.dao.KgJitCacheDao
import com.promenar.infinity.data.remote.*
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.ProtocolType
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

internal fun TestScope.testKgClient(protocol: LlmProtocol): KgRequestClient {
    return KgRequestClient(testKgRouter(), { protocol }, StandardTestDispatcher(testScheduler))
}

internal fun testKgRouter(): ProviderRequestRouter = object : ProviderRequestRouter {
        override fun resolve(modelId: String): ProviderResolution {
            val owner = modelId.substringBefore("::")
            val remote = modelId.substringAfter("::")
            return ProviderResolution.Success(ResolvedProviderModel(
                modelId, remote, owner, owner,
                UnifiedProviderConfig(ProtocolType.OpenAI_ChatCompletions, "https://test.invalid", "", remote),
            ))
        }
        override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("不使用流式客户端")
}

internal class TestKgUsageStore : KgUsageStore {
    val records = mutableListOf<KgUsageRecord>()
    override suspend fun record(record: KgUsageRecord) { records += record }
    override suspend fun snapshot() = KgUsageSnapshot(records)
    override suspend fun clear() { records.clear() }
}

internal fun TestScope.testGraphExtractor(
    protocol: LlmProtocol,
    graphStore: GraphStore,
    modelId: String? = "owner::model",
    systemPrompt: String = GraphExtractor.DEFAULT_KG_PROMPT,
    chunkSize: Int = 1200,
    chunkOverlap: Int = 200,
    timeoutMs: Long = 120_000,
    checkpointRoot: java.io.File? = null,
): GraphExtractor = GraphExtractor(testKgClient(protocol), graphStore, modelId, systemPrompt,
    chunkSize, chunkOverlap, timeoutMs, checkpointRoot, dispatcher = StandardTestDispatcher(testScheduler),
    concurrency = 1, gleaningPasses = 0)

internal fun TestScope.testMicroExtractor(
    protocol: LlmProtocol,
    graphStore: GraphStore,
    cacheDao: KgJitCacheDao,
    timeoutMs: Long = 5000,
): MicroGraphExtractor = MicroGraphExtractor(testKgClient(protocol), graphStore, cacheDao,
    modelId = "owner::model", timeoutMs = timeoutMs, dispatcher = StandardTestDispatcher(testScheduler))
