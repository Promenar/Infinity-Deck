package com.promenar.infinity.data.library.ask

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.memory.LongTermMemoryKind
import com.promenar.infinity.data.memory.LongTermMemorySource
import com.promenar.infinity.data.memory.RoomLongTermMemoryRepository
import com.promenar.infinity.data.rag.EmbeddingClient
import com.promenar.infinity.data.rag.EmbeddingResult
import com.promenar.infinity.data.rag.GraphStore
import com.promenar.infinity.data.rag.KeywordSearcher
import com.promenar.infinity.data.rag.MemoryManager
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.data.rag.VectorStore
import com.promenar.infinity.domain.library.AskScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RoomLibraryAskMemoryIsolationTest {
    @Test fun `关键词和语义问资料只读取共享池且不嵌入隔离正文`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = RoomLongTermMemoryRepository(db)
            repository.save(LongTermMemoryKind.USER, "共享记忆", "needle shared")
            repository.save(LongTermMemoryKind.USER, "隔离记忆", "needle isolated", source = LongTermMemorySource("a", "s", null, "s"))
            val embedded = mutableListOf<String>()
            val embedding = mockk<EmbeddingClient>()
            every { embedding.vectorSpaceKey() } returns "fixture-model"
            every { embedding.localModelGeneration() } returns null
            every { embedding.frozen() } returns embedding
            coEvery { embedding.embedQuery(any()) } returns (floatArrayOf(1f, 0f) to null)
            coEvery { embedding.embedDocuments(any()) } answers {
                val texts = firstArg<List<String>>()
                embedded += texts
                EmbeddingResult(texts.map { floatArrayOf(1f, 0f) })
            }
            val vectors = VectorStore(db.vectorDao(), db.kgNodeDao(), db.kgEdgeDao(), db.kgEvidenceDao())
            val memoryManager = MemoryManager(vectors, KeywordSearcher(db.vectorDao()), GraphStore(db), embedding,
                ragConfig = RagConfiguration(enableQueryRewrite = false, docThreshold = 0.1f))
            val backend = RoomLibraryAskBackend(db, mockk<ProviderManager>(relaxed = true), mockk<SharedPreferences>(relaxed = true),
                { embedding }, memoryManager = { memoryManager }, memories = repository, citations = RoomKnowledgeCitationRepository(db),
                answerStream = { _, _ -> emptyFlow() }, libraryLocation = { "资料库" }, workspaceLocation = { it },
                memoryLocation = { "主动记忆" }, entityLocation = { "图谱" })
            val keyword = backend.keywords("needle", AskScope.AllIndexed, 10)
            assertThat(keyword.map { it.title }).contains("共享记忆")
            assertThat(keyword.map { it.title }).doesNotContain("隔离记忆")
            val semantic = backend.retrieve("needle", AskScope.AllIndexed, keyword)
            assertThat(semantic.map { it.title }).doesNotContain("隔离记忆")
            assertThat(embedded.joinToString()).doesNotContain("isolated")
            assertThat(embedded.joinToString()).contains("shared")
        } finally { db.close() }
    }
}
