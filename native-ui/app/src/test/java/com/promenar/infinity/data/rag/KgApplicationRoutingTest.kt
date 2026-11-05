package com.promenar.infinity.data.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.model.ProviderConfig
import com.promenar.infinity.data.remote.*
import com.promenar.infinity.data.remote.protocol.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KgApplicationRoutingTest {
    private lateinit var app: InfinityApplication
    private val defaultProtocol = mockk<LlmProtocol>()
    private val ownerProtocol = mockk<LlmProtocol>()
    private val router = mockk<ProviderRequestRouter>()
    private val resolved = ResolvedProviderModel(
        "owner::remote", "remote", "owner", "Owner",
        UnifiedProviderConfig(ProtocolType.OpenAI_ChatCompletions, "https://owner.invalid", "", "remote"),
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("rag_settings", 0).edit().clear()
            .putString("kg_model", "owner::remote").putString("kg_prompt", "提取当前片段的关系").commit()
        val realApp = InfinityApplication()
        android.content.ContextWrapper::class.java.getDeclaredField("mBase").apply {
            isAccessible = true
            set(realApp, context)
        }
        app = spyk(realApp)
        every { app.getSharedPreferences(any(), any()) } answers { context.getSharedPreferences(firstArg(), secondArg()) }
        every { app.cacheDir } returns context.cacheDir
        every { app.filesDir } returns context.filesDir
        every { app.graphStore } returns mockk(relaxed = true)
        val database = mockk<InfinityDatabase>(relaxed = true)
        every { app.database } returns database
        coEvery { database.kgJitCacheDao().getByKey(any()) } returns null
        every { app.getSavedProviderConfig() } returns ProviderConfig(model = "main")
        every { app.llmProvider } returns mockk { every { protocol } returns defaultProtocol }
        every { app.providerRequestRouter } returns router
        every { router.resolve("owner::remote") } returns ProviderResolution.Success(resolved)
        coEvery { router.sessionHeaders(any(), any()) } returns null
        coEvery { defaultProtocol.sendPromptSync(any()) } returns PromptResponse(EMPTY)
        coEvery { ownerProtocol.sendPromptSync(any()) } returns PromptResponse(EMPTY)
        mockkObject(ProtocolFactory)
        every { ProtocolFactory.create(any(), any(), any(), any(), any(), any(), any(), any()) } returns ownerProtocol
    }

    @After
    fun cleanup() = unmockkObject(ProtocolFactory)

    @Test
    fun `KG和摘要均未设置时主模型远端ID转为稳定ID路由`() = runTest {
        app.getSharedPreferences("rag_settings", 0).edit().remove("kg_model").remove("kg_prompt").commit()
        app.getSharedPreferences("infinity_settings", 0).edit().remove("preset_summary_model").commit()
        every { router.resolve("default::main") } returns ProviderResolution.Success(resolved.copy(
            modelId = "default::main", providerId = "default", remoteModelId = "main",
            config = resolved.config.copy(defaultModel = "main"),
        ))
        assertThat(app.graphExtractor.extractCandidate("text").isSuccess).isTrue()
        assertThat(app.microGraphExtractor.extract(listOf(SearchResult(id = "r", content = "text", similarity = 1f, createdAt = 0)), "q", "s").isSuccess).isTrue()
        coVerify(exactly = 3) { ownerProtocol.sendPromptSync(match { it.model == "main" }) }
    }

    @Test
    fun `全量抽取仅向模型所属Provider发送远端ID`() = runTest {
        val result = app.graphExtractor.extractCandidate("document")
        assertThat(result.error).isNull()
        coVerify(exactly = 2) { ownerProtocol.sendPromptSync(match { it.model == "remote" }) }
        coVerify(exactly = 0) { defaultProtocol.sendPromptSync(any()) }
    }

    @Test
    fun `JIT复用KG模型与提示词`() = runTest {
        app.microGraphExtractor.extract(listOf(SearchResult(id = "r", content = "text", similarity = 1f, createdAt = 0)), "q", "s")
        coVerify(exactly = 1) {
            ownerProtocol.sendPromptSync(match { it.model == "remote" && it.messages.first().content == "提取当前片段的关系" })
        }
        coVerify(exactly = 0) { defaultProtocol.sendPromptSync(any()) }
    }

    @Test
    fun `解析失败不向默认Provider泄漏文档`() = runTest {
        every { router.resolve(any()) } returns ProviderResolution.Failure(ProviderResolutionError.MODEL_NOT_FOUND, "missing")
        val result = app.graphExtractor.extractCandidate("private document")
        assertThat(result.error).contains("MODEL_NOT_FOUND")
        coVerify(exactly = 0) { defaultProtocol.sendPromptSync(any()) }
        coVerify(exactly = 0) { ownerProtocol.sendPromptSync(any()) }
    }

    @Test
    fun `保留的聊天ContextBuilder在配置变化后使用新JIT模型与提示词`() = runBlocking<Unit> {
        val database = Room.inMemoryDatabaseBuilder(app, InfinityDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        every { app.graphStore } returns GraphStore(database)
        every { app.database } returns database
        database.sessionDao().insert(com.promenar.infinity.data.local.db.entity.SessionEntity("s", "a", createdAt = 1, updatedAt = 1))
        database.fileEntryDao().insert(com.promenar.infinity.data.local.db.entity.FileEntry(
            uuid = "doc", workspaceRootUuid = "root", parentUuid = "root", name = "doc.txt", hash = "doc-hash",
            physicalRootPath = "/tmp/kg-routing-fixture", materializedPath = "/doc.txt",
            createdAt = 1, updatedAt = 1,
        ))
        try {
            val rag = object : com.promenar.infinity.ui.chat.manager.RagProvider {
                override suspend fun retrieveContext(query: String, sessionId: String, options: com.promenar.infinity.data.model.RagOptions,
                    onProgress: ((String, Int, String?) -> Unit)?) = com.promenar.infinity.ui.chat.manager.RagRetrievalResult(
                    "RAG", listOf(com.promenar.infinity.data.model.RagReference(
                        "r", "text", "doc", documentId = "doc", documentContentHash = "doc-hash")), null,
                )
            }
            val builder = com.promenar.infinity.ui.chat.manager.ContextBuilder(ragProvider = rag, kgProvider = app.kgProvider)
            val params = com.promenar.infinity.ui.chat.manager.ContextBuilderParams(
                sessionId = "s", content = "q", assistantMsgId = "m",
                session = com.promenar.infinity.data.model.Session("s", "a",
                    ragOptions = com.promenar.infinity.data.model.RagOptions(enableMemory = true,
                        activeDocIds = listOf("doc"), enableKnowledgeGraph = true)),
            )
            builder.buildContext(params)
            app.getSharedPreferences("rag_settings", 0).edit().putString("kg_model", "next::new")
                .putString("kg_prompt", "新的关系提示词").commit()
            every { router.resolve("next::new") } returns ProviderResolution.Success(resolved.copy(
                modelId = "next::new", providerId = "next", remoteModelId = "new",
                config = resolved.config.copy(baseUrl = "https://next.invalid", defaultModel = "new"),
            ))
            app.rebuildGraphExtractor()

            builder.buildContext(params)

            coVerify(exactly = 1) {
                ownerProtocol.sendPromptSync(match { it.model == "new" && it.messages.first().content == "新的关系提示词" })
            }
        } finally {
            app.microGraphExtractor.close()
            database.close()
        }
    }

    @Test
    fun `重建KG使JIT和适配器引用一起失效`() {
        val oldJit = app.microGraphExtractor
        val oldAdapter = app.kgProvider
        app.rebuildGraphExtractor()
        assertThat(app.microGraphExtractor).isNotSameInstanceAs(oldJit)
        assertThat(app.kgProvider).isNotSameInstanceAs(oldAdapter)
    }

    private companion object {
        const val EMPTY = """{"nodes":[],"edges":[]}"""
    }
}
