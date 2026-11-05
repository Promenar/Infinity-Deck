package com.promenar.infinity.domain.usecase

import android.content.SharedPreferences
import com.promenar.infinity.data.agent.AgentRagConfig
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class RagConfigPersistenceTest {

    @Test
    fun `未保存查询改写时关闭且已保存值保持原样`() {
        val prefs = emptyPrefs()
        val persistence = RagConfigPersistence(prefs)
        assertThat(persistence.loadRetrievalConfig().enableQueryRewrite).isFalse()
        assertThat(persistence.loadFullConfig().enableQueryRewrite).isFalse()
        for (saved in listOf(true, false)) {
            every { prefs.getBoolean(RagConfigPersistence.KEY_ENABLE_QUERY_REWRITE, any()) } returns saved
            assertThat(persistence.loadRetrievalConfig().enableQueryRewrite).isEqualTo(saved)
            assertThat(persistence.loadFullConfig().enableQueryRewrite).isEqualTo(saved)
        }
    }

    private fun emptyPrefs(): SharedPreferences {
        val p = mockk<SharedPreferences>(relaxed = true)
        every { p.getInt(any(), any()) } answers { secondArg() }
        every { p.getString(any(), any()) } answers { secondArg() }
        every { p.getFloat(any(), any()) } answers { secondArg() }
        every { p.getBoolean(any(), any()) } answers { secondArg() }
        return p
    }

    @Test
    fun `深度档位只保存四个原始键且默认读取为均衡`() {
        val prefs = emptyPrefs()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.putInt(any(), any()) } returns editor
        // 预算默认值迁移已完成，读取不产生写入。
        every { prefs.getBoolean("kg_budget_defaults_v3", false) } returns true
        val persistence = RagConfigPersistence(prefs)
        assertThat(persistence.loadRetrievalDepth()).isEqualTo(com.promenar.infinity.data.rag.RetrievalDepth.BALANCED)
        persistence.saveRetrievalDepth(com.promenar.infinity.data.rag.RetrievalDepth.LEAN)
        verify(exactly = 1) { editor.putInt(RagConfigPersistence.KEY_MEMORY_LIMIT, 3) }
        verify(exactly = 1) { editor.putInt(RagConfigPersistence.KEY_DOC_LIMIT, 4) }
        verify(exactly = 1) { editor.putInt(RagConfigPersistence.KEY_RERANK_TOP_K, 20) }
        verify(exactly = 1) { editor.putInt(RagConfigPersistence.KEY_RERANK_FINAL_K, 3) }
        verify(exactly = 4) { editor.putInt(any(), any()) }
        verify(exactly = 0) { editor.remove(any()) }
        verify(exactly = 0) { editor.putString(any(), any()) }
        verify(exactly = 0) { editor.putBoolean(any(), any()) }
        verify(exactly = 0) { editor.putFloat(any(), any()) }
        verify(exactly = 1) { editor.apply() }
    }

    @Test
    fun `loadRagConfig returns defaults when prefs empty`() {
        val config = RagConfigPersistence(emptyPrefs()).loadRagConfig()
        assertThat(config.docChunkSize).isEqualTo(800)
        assertThat(config.chunkOverlap).isEqualTo(100)
        assertThat(config.memoryChunkSize).isEqualTo(1000)
    }

    @Test
    fun `loadRetrievalConfig returns defaults when prefs empty`() {
        val config = RagConfigPersistence(emptyPrefs()).loadRetrievalConfig()
        assertThat(config.memoryLimit).isEqualTo(5)
        assertThat(config.memoryThreshold).isWithin(0.001f).of(0.7f)
        assertThat(config.docLimit).isEqualTo(8)
        assertThat(config.enableMemory).isTrue()
        assertThat(config.enableDocs).isTrue()
        assertThat(config.enableKnowledgeGraph).isFalse()
    }

    @Test
    fun `loadRagConfig reads from prefs`() {
        val p = mockk<SharedPreferences>(relaxed = true)
        every { p.getInt(RagConfigPersistence.KEY_CHUNK_SIZE, 800) } returns 600
        every { p.getInt(RagConfigPersistence.KEY_CHUNK_OVERLAP, 100) } returns 50
        every { p.getInt(RagConfigPersistence.KEY_MEMORY_CHUNK_SIZE, 1000) } returns 800
        every { p.getString(any(), any()) } answers { secondArg() }
        every { p.getFloat(any(), any()) } answers { secondArg() }
        every { p.getBoolean(any(), any()) } answers { secondArg() }

        val config = RagConfigPersistence(p).loadRagConfig()
        assertThat(config.docChunkSize).isEqualTo(600)
        assertThat(config.chunkOverlap).isEqualTo(50)
        assertThat(config.memoryChunkSize).isEqualTo(800)
    }

    @Test
    fun `loadRetrievalConfig reads from prefs`() {
        // Mock all keys explicitly — avoid generic any() which conflicts with specific rules in MockK
        val p = mockk<SharedPreferences>(relaxed = true)
        // Custom values for keys we want to verify
        every { p.getInt(RagConfigPersistence.KEY_MEMORY_LIMIT, 5) } returns 15
        every { p.getFloat(RagConfigPersistence.KEY_MEMORY_THRESHOLD, 0.7f) } returns 0.7f
        every { p.getInt(RagConfigPersistence.KEY_DOC_LIMIT, 8) } returns 20
        every { p.getFloat(RagConfigPersistence.KEY_DOC_THRESHOLD, 0.45f) } returns 0.45f
        every { p.getBoolean(RagConfigPersistence.KEY_ENABLE_RERANK, true) } returns true
        every { p.getInt(RagConfigPersistence.KEY_RERANK_TOP_K, 30) } returns 30
        every { p.getInt(RagConfigPersistence.KEY_RERANK_FINAL_K, 5) } returns 5
        every { p.getBoolean(RagConfigPersistence.KEY_ENABLE_QUERY_REWRITE, false) } returns false
        every { p.getString(RagConfigPersistence.KEY_QUERY_REWRITE_STRATEGY, "multi-query") } returns "multi-query"
        every { p.getInt(RagConfigPersistence.KEY_QUERY_REWRITE_COUNT, 3) } returns 3
        every { p.getBoolean(RagConfigPersistence.KEY_ENABLE_HYBRID_SEARCH, true) } returns true
        every { p.getFloat(RagConfigPersistence.KEY_HYBRID_ALPHA, 0.6f) } returns 0.8f
        every { p.getFloat(RagConfigPersistence.KEY_HYBRID_BM25_BOOST, 1.0f) } returns 1.0f
        every { p.getBoolean(RagConfigPersistence.KEY_ENABLE_MEMORY, true) } returns false
        every { p.getBoolean(RagConfigPersistence.KEY_ENABLE_DOCS, true) } returns true
        every { p.getBoolean(RagConfigPersistence.KEY_ENABLE_KG, false) } returns false
        every { p.getString(RagConfigPersistence.KEY_QUERY_REWRITE_MODEL, null as String?) } returns null
        every { p.getString(RagConfigPersistence.KEY_KG_MODEL, null as String?) } returns null
        every { p.getString(RagConfigPersistence.KEY_KG_PROMPT, null as String?) } returns null
        every { p.getBoolean(RagConfigPersistence.KEY_KG_FREE_MODE, false) } returns false
        every { p.getBoolean(RagConfigPersistence.KEY_KG_DOMAIN_AUTO, false) } returns false
        every { p.getInt(RagConfigPersistence.KEY_JIT_MAX_CHUNKS, 0) } returns 0

        val config = RagConfigPersistence(p).loadRetrievalConfig()
        assertThat(config.memoryLimit).isEqualTo(15)
        assertThat(config.docLimit).isEqualTo(20)
        assertThat(config.enableRerank).isTrue()
        assertThat(config.enableMemory).isFalse()
        assertThat(config.enableHybridSearch).isTrue()
        assertThat(config.hybridAlpha).isWithin(0.001f).of(0.8f)
    }

    @Test
    fun `key constants are unique`() {
        val allKeys = setOf(
            RagConfigPersistence.KEY_CHUNK_SIZE, RagConfigPersistence.KEY_CHUNK_OVERLAP,
            RagConfigPersistence.KEY_MEMORY_CHUNK_SIZE, RagConfigPersistence.KEY_SUMMARY_TEMPLATE,
            RagConfigPersistence.KEY_MEMORY_LIMIT, RagConfigPersistence.KEY_MEMORY_THRESHOLD,
            RagConfigPersistence.KEY_DOC_LIMIT, RagConfigPersistence.KEY_DOC_THRESHOLD,
            RagConfigPersistence.KEY_ENABLE_RERANK, RagConfigPersistence.KEY_RERANK_TOP_K,
            RagConfigPersistence.KEY_RERANK_FINAL_K, RagConfigPersistence.KEY_ENABLE_QUERY_REWRITE,
            RagConfigPersistence.KEY_QUERY_REWRITE_STRATEGY, RagConfigPersistence.KEY_QUERY_REWRITE_COUNT,
            RagConfigPersistence.KEY_QUERY_REWRITE_MODEL, RagConfigPersistence.KEY_ENABLE_HYBRID_SEARCH,
            RagConfigPersistence.KEY_HYBRID_ALPHA, RagConfigPersistence.KEY_HYBRID_BM25_BOOST,
            RagConfigPersistence.KEY_ENABLE_MEMORY, RagConfigPersistence.KEY_ENABLE_DOCS,
            RagConfigPersistence.KEY_ENABLE_KG, RagConfigPersistence.KEY_KG_MODEL,
            RagConfigPersistence.KEY_KG_PROMPT, RagConfigPersistence.KEY_KG_FREE_MODE,
            RagConfigPersistence.KEY_KG_DOMAIN_AUTO, RagConfigPersistence.KEY_JIT_MAX_CHUNKS,
            RagConfigPersistence.KEY_ENABLE_INCREMENTAL_HASH, RagConfigPersistence.KEY_ENABLE_LOCAL_PREPROCESS,
            RagConfigPersistence.KEY_COST_STRATEGY, RagConfigPersistence.KEY_SHOW_RETRIEVAL_PROGRESS,
            RagConfigPersistence.KEY_SHOW_RETRIEVAL_DETAILS, RagConfigPersistence.KEY_TRACK_RETRIEVAL_METRICS
        )
        assertThat(allKeys).hasSize(32)
    }

    @Test
    fun `saving current config removes retired and unused embedding keys instead of rewriting them`() {
        val prefs = emptyPrefs()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putFloat(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putString(any(), any()) } returns editor

        val persistence = RagConfigPersistence(prefs)
        persistence.saveRagConfig(AgentRagConfig())
        persistence.saveRetrievalConfig(AgentRetrievalConfig())

        verify {
            editor.remove(RagConfigPersistence.KEY_ENABLE_INCREMENTAL_HASH)
            editor.remove(RagConfigPersistence.KEY_ENABLE_LOCAL_PREPROCESS)
            editor.remove(RagConfigPersistence.KEY_KG_DOMAIN_AUTO)
            editor.remove(RagConfigPersistence.KEY_JIT_MAX_CHUNKS)
            editor.remove(RagConfigPersistence.KEY_EMBED_DIMENSION)
            editor.remove(RagConfigPersistence.KEY_MAX_EMBED_TOKENS_PER_CALL)
        }
        verify(exactly = 0) {
            editor.putBoolean(RagConfigPersistence.KEY_ENABLE_INCREMENTAL_HASH, any())
            editor.putBoolean(RagConfigPersistence.KEY_ENABLE_LOCAL_PREPROCESS, any())
            editor.putBoolean(RagConfigPersistence.KEY_KG_DOMAIN_AUTO, any())
            editor.putInt(RagConfigPersistence.KEY_JIT_MAX_CHUNKS, any())
            editor.putInt(RagConfigPersistence.KEY_EMBED_DIMENSION, any())
            editor.putInt(RagConfigPersistence.KEY_MAX_EMBED_TOKENS_PER_CALL, any())
        }
    }

    @Test
    fun `RagConfiguration schema no longer exposes unused embedding knobs`() {
        assertThat(com.promenar.infinity.data.rag.RagConfiguration::class.java.declaredFields.map { it.name })
            .containsNoneOf("embedDimension", "maxEmbedTokensPerCall")
    }
}
