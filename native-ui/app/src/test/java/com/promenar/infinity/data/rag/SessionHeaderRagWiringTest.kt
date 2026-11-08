package com.promenar.infinity.data.rag

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint
import com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint.Companion.MODEL
import io.ktor.http.HttpStatusCode
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SessionHeaderRagWiringTest {
    @Test fun `会话图谱从队列任务传入身份而文档图谱没有身份`() = runTest {
        SessionHeaderWiringEndpoint().use { endpoint ->
            endpoint.reply = { HttpStatusCode.ServiceUnavailable to "{}" }
            val client = KgRequestClient(endpoint.router, { endpoint.protocol }, Dispatchers.IO)
            val extractor = GraphExtractor(client, mockk(relaxed = true), MODEL, dispatcher = Dispatchers.IO)
            val stats = SessionGraphStats(mockk<SharedPreferences>(relaxed = true))
            val database = graphDatabase()
            val processor = RoomSessionGraphProcessor({ database }, { extractor }, stats)
            val task = VectorizationTask(id = "graph-task", type = VectorizationQueue.TYPE_SESSION_GRAPH,
                sessionId = "rag-session", assistantMessageId = "a", userContent = "足够长的用户输入内容用于图谱抽取", aiContent = "足够长的助手回复内容用于图谱抽取")
            assertThat(runCatching { processor.process(task) }.exceptionOrNull()).isNotNull()
            assertThat(endpoint.requests).hasSize(2)
            assertThat(endpoint.requests.first().headers["x-opencode-session"]).isEqualTo(endpoint.identifier("rag-session"))
            assertThat(endpoint.requests[1].headers["x-opencode-session"]).isEqualTo(endpoint.identifier("rag-session"))
            endpoint.enabled = false
            assertThat(runCatching { processor.process(task) }.exceptionOrNull()).isNotNull()
            assertThat(endpoint.requests.last().headers["x-opencode-session"]).isNull()
            assertThat(endpoint.requests.last().headers["User-Agent"]?.startsWith("Infinity-Deck/") == true).isFalse()
            assertThat(SessionHeaderWiringEndpoint.body(endpoint.requests.last())).isEqualTo(SessionHeaderWiringEndpoint.body(endpoint.requests.first()))
            endpoint.enabled = true
            extractor.extractCandidate("文档文本")
            assertThat(endpoint.requests.last().headers["x-opencode-session"]).isNull()
            assertThat(endpoint.requests.last().headers["User-Agent"]?.startsWith("Infinity-Deck/") == true).isFalse()
        }
    }

    @Test fun `JIT图谱带当前会话且关闭设置后不加头不改请求体`() = runTest {
        SessionHeaderWiringEndpoint().use { endpoint ->
            endpoint.reply = { HttpStatusCode.OK to SessionHeaderWiringEndpoint.sync("{\"nodes\":[],\"edges\":[]}") }
            val store = mockk<GraphStore>(relaxed = true)
            every { store.database } returns graphDatabase()
            coEvery { store.captureJitSources(any()) } returns emptyList()
            val cache = mockk<com.promenar.infinity.data.local.db.dao.KgJitCacheDao>(relaxed = true)
            coEvery { cache.getByKey(any()) } returns null
            val client = KgRequestClient(endpoint.router, { endpoint.protocol }, Dispatchers.IO)
            MicroGraphExtractor(client, store, cache, MODEL, dispatcher = Dispatchers.IO).use { micro ->
                val sources = listOf(SearchResult(id = "chunk", content = "文档片段", createdAt = 0, similarity = 1f))
                assertThat(micro.extract(sources, "问题", "rag-session").isSuccess).isTrue()
                assertThat(endpoint.requests.single().headers["x-opencode-session"]).isEqualTo(endpoint.identifier("rag-session"))
                endpoint.enabled = false
                assertThat(micro.extract(sources, "问题", "rag-session").isSuccess).isTrue()
                assertThat(endpoint.requests.last().headers["x-opencode-session"]).isNull()
                assertThat(endpoint.requests.last().headers["User-Agent"]?.startsWith("Infinity-Deck/") == true).isFalse()
                assertThat(SessionHeaderWiringEndpoint.body(endpoint.requests.last())).isEqualTo(SessionHeaderWiringEndpoint.body(endpoint.requests.first()))
            }
        }
    }

    private fun graphDatabase(): InfinityDatabase {
        val database = mockk<InfinityDatabase>()
        val sessions = mockk<SessionDao>()
        every { database.sessionDao() } returns sessions
        coEvery { sessions.getById(any()) } returns null
        return database
    }
}
