package com.promenar.infinity.data.knowledge

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.agent.KnowledgeFileAccess
import com.promenar.infinity.data.model.DocumentScope
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionOptions
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KnowledgeAccessPlannerTest {
    private var session = Session("s", "a", ragOptions = RagOptions())
    private var config = AgentRetrievalConfig()
    private var roots = listOf(KnowledgeBaseSummary("kb", "默认资料", 3, true))
    private val mounts = mutableListOf<ExternalMount>()
    private var adds = 0
    private val access = mockk<ExternalWorkspaceAccess>().also { access ->
        coEvery { access.visible("s") } answers { mounts.toList() }
        coEvery { access.addKnowledgeBase(any(), "s", any()) } answers {
            adds++
            ExternalMount("m-$adds", firstArg(), ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, thirdArg(), "s", 1, readOnly = true)
                .also(mounts::add)
        }
    }
    private val source = object : KnowledgeCatalogSource {
        override suspend fun knowledgeBases(session: Session, options: RagOptions) = roots
        override suspend fun snapshot(session: Session, options: RagOptions) = KnowledgeCatalogSnapshot()
        override fun hasIndexedMaterial(session: Session, options: RagOptions) = false
        override suspend fun documentLocation(sessionId: String, reference: RagReference): KnowledgeDocumentLocation? = null
    }
    private val planner = KnowledgeAccessPlanner({ session }, { config }, access, source)

    @Test
    fun `预览不挂载且序列化批准目标执行为只读`() = runTest {
        val preview = planner.preview("s")
        assertThat(preview.allowed).isTrue()
        assertThat(preview.knowledgeBases.single()).isEqualTo(KnowledgeAccessTarget("kb", "默认资料", 3, "knowledge", "/mnt/knowledge"))
        assertThat(adds).isEqualTo(0)
        val approved = Json.decodeFromString<KnowledgeAccessPreview>(Json.encodeToString(KnowledgeAccessPreview.serializer(), preview))
        val mounted = planner.mount(approved).single()
        assertThat(mounted.readOnly).isTrue()
        assertThat(mounted.sourceId).isEqualTo("kb")
        assertThat(mounted.commandAccessible).isFalse()
        assertThat(adds).isEqualTo(1)
    }

    @Test
    fun `工作区范围和文档关闭时不能挂载`() = runTest {
        session = session.copy(ragOptions = RagOptions(documentScope = DocumentScope.WORKSPACE))
        assertThat(planner.preview("s").allowed).isFalse()
        session = session.copy(ragOptions = RagOptions(enableDocs = false))
        assertThat(planner.preview("s").allowed).isFalse()
        assertThat(adds).isEqualTo(0)
    }

    @Test
    fun `批准后范围收紧或资料目标变化要求重新申请`() = runTest {
        val approved = planner.preview("s")
        config = config.copy(knowledgeFileAccess = KnowledgeFileAccess.OFF)
        assertThat(assertThrows<ExternalFolderException> { planner.mount(approved) }.code).isEqualTo("APPROVAL_TARGET_CHANGED")
        config = AgentRetrievalConfig()
        roots = listOf(KnowledgeBaseSummary("other", "默认资料", 3, true))
        assertThat(assertThrows<ExternalFolderException> { planner.mount(approved) }.code).isEqualTo("APPROVAL_TARGET_CHANGED")
        assertThat(adds).isEqualTo(0)
    }

    @Test
    fun `别名清理保留名称并对现有挂载和同批目标消歧`() = runTest {
        mounts += ExternalMount("existing", "knowledge", "real_path", "/existing", "s", 0)
        roots = listOf(
            KnowledgeBaseSummary("kb", "默认资料", 1, true),
            KnowledgeBaseSummary("kb-2", "mnt", 1),
            KnowledgeBaseSummary("kb-3", "a/b\\c", 1),
            KnowledgeBaseSummary("kb-4", "a-b-c", 1),
        )
        val aliases = planner.preview("s").knowledgeBases.map { it.alias }
        assertThat(aliases).containsExactly("knowledge-2", "a-b-c", "a-b-c-2", "knowledge-3").inOrder()
        aliases.forEach { assertThat(ExternalMountPaths.validateAlias(it)).isEqualTo(it) }
    }

    @Test
    fun `指定知识库只批准唯一可见名称`() = runTest {
        roots = roots + KnowledgeBaseSummary("kb-2", "项目资料", 7)
        assertThat(planner.preview("s", "项目资料").knowledgeBases.map { it.rootUuid }).containsExactly("kb-2")
        assertThat(planner.preview("s", "不可见资料").allowed).isFalse()
        roots = roots + KnowledgeBaseSummary("kb-3", "项目资料", 9)
        assertThat(planner.preview("s", "项目资料").allowed).isFalse()
        assertThat(adds).isEqualTo(0)
    }

    @Test
    fun `已挂载知识库复用原别名且不重复新增`() = runTest {
        val existing = ExternalMount("m", "custom", ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, "kb", "s", 0, readOnly = true)
        mounts += existing
        val preview = planner.preview("s")
        assertThat(preview.knowledgeBases.single().alreadyMounted).isTrue()
        assertThat(preview.knowledgeBases.single().path).isEqualTo("/mnt/custom")
        assertThat(planner.mount(preview)).containsExactly(existing)
        assertThat(adds).isEqualTo(0)
    }

    @Test
    fun `并发执行相同批准不重复新增知识库`() = runTest {
        val preview = planner.preview("s")
        val first = async { runCatching { planner.mount(preview) } }
        val second = async { runCatching { planner.mount(preview) } }
        val results = listOf(first.await(), second.await())
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(adds).isEqualTo(1)
        assertThat(mounts.map { it.sourceId }).containsExactly("kb")
    }

    @Test
    fun `ALWAYS仅新建入口挂载预览不会补回用户移除的挂载`() = runTest {
        assertThat(planner.mountIfAlways(session)).isEmpty()
        config = config.copy(knowledgeFileAccess = KnowledgeFileAccess.ALWAYS)
        assertThat(planner.mountIfAlways(session)).hasSize(1)
        mounts.clear()
        planner.preview("s")
        assertThat(mounts).isEmpty()
        assertThat(adds).isEqualTo(1)
        session = session.copy(options = SessionOptions(toolsEnabled = false))
        assertThat(planner.mountIfAlways(session)).isEmpty()
        assertThat(planner.preview("s").allowed).isFalse()
    }

    @Test
    fun `OFF和非只读已有知识库挂载不能通过访问预览`() = runTest {
        config = config.copy(knowledgeFileAccess = KnowledgeFileAccess.OFF)
        assertThat(planner.preview("s").allowed).isFalse()
        config = AgentRetrievalConfig()
        mounts += ExternalMount("unsafe", "knowledge", ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, "kb", "s", 0)
        assertThat(planner.preview("s").allowed).isFalse()
        assertThat(adds).isEqualTo(0)
    }
}
