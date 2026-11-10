package com.promenar.infinity.ui.document.external

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.document.ComposerDocumentInbox
import com.promenar.infinity.data.document.DocumentWorkspaceBytesSaveResult
import com.promenar.infinity.data.document.DocumentWorkspaceSaver
import com.promenar.infinity.data.document.external.ExternalDocument
import com.promenar.infinity.data.document.external.ExternalDocumentFailure
import com.promenar.infinity.data.document.external.ExternalDocumentInput
import com.promenar.infinity.data.document.external.ExternalDocumentSender
import com.promenar.infinity.data.document.external.ExternalDocumentStore
import com.promenar.infinity.data.document.external.ExternalLibrarySaveResult
import com.promenar.infinity.data.document.external.ExternalSendMode
import com.promenar.infinity.data.model.WorkspaceDocumentReference
import com.promenar.infinity.share.core.ShareKnowledgeFolder
import com.promenar.infinity.share.core.ShareSessionChoice
import io.mockk.coEvery
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalDocumentViewModelTest {
    @get:Rule val temporary = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var root: File
    private lateinit var store: ExternalDocumentStore
    private val inbox = ComposerDocumentInbox()
    private val workspaceSaver = mockk<DocumentWorkspaceSaver>()
    private val targets = FakeTargets()
    private val memory = FakeMemory()
    private val librarySaves = mutableListOf<Triple<ExternalDocument, String, String>>()
    private var libraryResult: ExternalLibrarySaveResult = ExternalLibrarySaveResult.Saved("doc-1", "周报.md", "/工作/周报.md")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        root = temporary.newFolder("staging")
        store = ExternalDocumentStore(root, io = dispatcher)
        coEvery { workspaceSaver.saveBytes(any(), any(), any(), any(), any(), any(), any()) } returns
            DocumentWorkspaceBytesSaveResult.Success(WorkspaceDocumentReference("/attachments/周报.md", 1, 1))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(savedState: SavedStateHandle = SavedStateHandle()) = ExternalDocumentViewModel(
        store = store,
        librarySave = { document, rootUuid, folderUuid ->
            librarySaves += Triple(document, rootUuid, folderUuid)
            libraryResult
        },
        sender = ExternalDocumentSender(inbox, workspaceSaver, dispatcher),
        targets = targets,
        folderMemory = memory,
        savedState = savedState,
        io = dispatcher,
        compute = dispatcher,
    )

    private fun markdown(text: String, name: String = "周报.md") = { ExternalDocumentInput(name, null) { ByteArrayInputStream(text.toByteArray()) } }

    private fun TestScope.events(vm: ExternalDocumentViewModel): List<ExternalDocumentEvent> {
        val received = mutableListOf<ExternalDocumentEvent>()
        backgroundScope.launch(dispatcher) { vm.events.collect { received += it } }
        return received
    }

    @Test
    fun `打开后显示正文并记下暂存 id`() = runTest {
        val savedState = SavedStateHandle()
        val vm = viewModel(savedState)

        vm.open(markdown("# 周报\n正文"))

        val state = vm.state.value
        assertThat(state.visible).isTrue()
        assertThat(state.loading).isFalse()
        assertThat(state.document!!.name).isEqualTo("周报.md")
        assertThat(state.document!!.chunks).containsExactly("# 周报\n正文")
        assertThat(state.document!!.attachmentAllowed).isTrue()
        val id = savedState.get<String>(ExternalDocumentViewModel.KEY_DOCUMENT_ID)
        assertThat(id).isNotNull()
        assertThat(File(root, id!!).isDirectory).isTrue()
    }

    @Test
    fun `读取失败时显示原因且不留暂存 id`() = runTest {
        val savedState = SavedStateHandle()
        val vm = viewModel(savedState)

        vm.open { ExternalDocumentInput("a.md", 0) { ByteArrayInputStream(ByteArray(0)) } }

        assertThat(vm.state.value.failure).isEqualTo(ExternalDocumentFailure.Empty)
        assertThat(vm.state.value.document).isNull()
        assertThat(savedState.contains(ExternalDocumentViewModel.KEY_DOCUMENT_ID)).isFalse()
    }

    @Test
    fun `进程重建后从暂存区恢复 关闭后删除暂存`() = runTest {
        val first = SavedStateHandle()
        viewModel(first).open(markdown("恢复我"))
        val id = first.get<String>(ExternalDocumentViewModel.KEY_DOCUMENT_ID)!!

        val restored = viewModel(SavedStateHandle(mapOf(ExternalDocumentViewModel.KEY_DOCUMENT_ID to id)))
        assertThat(restored.state.value.document!!.chunks).containsExactly("恢复我")

        restored.close()
        assertThat(restored.state.value.visible).isFalse()
        assertThat(File(root, id).exists()).isFalse()

        val missing = viewModel(SavedStateHandle(mapOf(ExternalDocumentViewModel.KEY_DOCUMENT_ID to id)))
        assertThat(missing.state.value.visible).isFalse()
    }

    @Test
    fun `存入存储空间默认上次的文件夹 存入后可在资料中打开`() = runTest {
        val work = ShareKnowledgeFolder("f-work", "工作", "/工作")
        memory.trail = listOf(work)
        targets.existing = setOf("f-work")
        targets.folders = mapOf("f-work" to listOf(ShareKnowledgeFolder("f-weekly", "周报", "/工作/周报")))
        val vm = viewModel()
        val events = events(vm)
        vm.open(markdown("# 周报"))

        vm.openFolderPanel()
        val panel = vm.state.value.panel as ExternalDocumentPanel.Folder
        assertThat(panel.trail).containsExactly(work)
        assertThat(panel.folders.map { it.uuid }).containsExactly("f-weekly")

        vm.confirmFolder()

        assertThat(librarySaves.single().second).isEqualTo("kb")
        assertThat(librarySaves.single().third).isEqualTo("f-work")
        assertThat(vm.state.value.panel).isNull()
        assertThat(vm.state.value.saved).isEqualTo(ExternalSavedLocation("doc-1", "/工作"))
        assertThat(memory.saved).containsExactly(work)
        assertThat(events).contains(ExternalDocumentEvent.Notice(ExternalDocumentNotice.Saved("/工作")))

        vm.openInLibrary()
        assertThat(events.last()).isEqualTo(ExternalDocumentEvent.OpenLibraryDocument("doc-1"))
        assertThat(vm.state.value.visible).isFalse()
    }

    @Test
    fun `记住的文件夹已删除时从根目录开始 可逐级进入与返回`() = runTest {
        memory.trail = listOf(ShareKnowledgeFolder("gone", "旧", "/旧"))
        val work = ShareKnowledgeFolder("f-work", "工作", "/工作")
        targets.folders = mapOf("kb" to listOf(work), "f-work" to emptyList())
        val vm = viewModel()
        vm.open(markdown("# a"))

        vm.openFolderPanel()
        assertThat((vm.state.value.panel as ExternalDocumentPanel.Folder).trail).isEmpty()

        vm.enterFolder(work)
        assertThat((vm.state.value.panel as ExternalDocumentPanel.Folder).trail).containsExactly(work)

        vm.panelBack()
        assertThat((vm.state.value.panel as ExternalDocumentPanel.Folder).trail).isEmpty()
        vm.panelBack()
        assertThat(vm.state.value.panel).isNull()

        vm.openFolderPanel()
        vm.confirmFolder()
        assertThat(librarySaves.single().third).isEqualTo("kb")
        assertThat(vm.state.value.saved!!.folderPath).isEqualTo("/")
    }

    @Test
    fun `存入失败时保留面板并提示`() = runTest {
        libraryResult = ExternalLibrarySaveResult.Failed(null)
        val vm = viewModel()
        val events = events(vm)
        vm.open(markdown("# a"))
        vm.openFolderPanel()

        vm.confirmFolder()

        assertThat(vm.state.value.saved).isNull()
        assertThat(vm.state.value.panel).isInstanceOf(ExternalDocumentPanel.Folder::class.java)
        assertThat(vm.state.value.busy).isFalse()
        assertThat(events).containsExactly(ExternalDocumentEvent.Notice(ExternalDocumentNotice.SaveFailed))
    }

    @Test
    fun `发送到已有会话 默认附件 发送后打开会话并关闭查看器`() = runTest {
        targets.sessions = listOf(ShareSessionChoice("s1", "同步引擎", 1L))
        val vm = viewModel()
        val events = events(vm)
        vm.open(markdown("# 周报"))

        vm.openSendPanel()
        val panel = vm.state.value.panel as ExternalDocumentPanel.Send
        assertThat(panel.mode).isEqualTo(ExternalSendMode.Attachment)
        assertThat(panel.sessions.map { it.sessionId }).containsExactly("s1")

        vm.sendTo("s1")

        assertThat(inbox.take("s1").single().content).isEqualTo("# 周报")
        assertThat(events).containsExactly(ExternalDocumentEvent.OpenSession("s1"))
        assertThat(vm.state.value.visible).isFalse()
    }

    @Test
    fun `选择引用时草稿只带路径`() = runTest {
        val vm = viewModel()
        vm.open(markdown("# 周报"))
        vm.openSendPanel()

        vm.setSendMode(ExternalSendMode.Reference)
        vm.sendTo("s1")

        val attachment = inbox.take("s1").single()
        assertThat(attachment.content).isEmpty()
        assertThat(attachment.workspaceReference).isNotNull()
    }

    @Test
    fun `超过 1 MiB 时只能引用`() = runTest {
        val vm = viewModel()
        vm.open(markdown("a".repeat(1024 * 1024 + 1)))
        assertThat(vm.state.value.document!!.attachmentAllowed).isFalse()

        vm.openSendPanel()
        vm.setSendMode(ExternalSendMode.Attachment)

        val panel = vm.state.value.panel as ExternalDocumentPanel.Send
        assertThat(panel.attachmentAllowed).isFalse()
        assertThat(panel.mode).isEqualTo(ExternalSendMode.Reference)
    }

    @Test
    fun `新建会话 一个智能体直接新建 多个先选择`() = runTest {
        targets.agents = listOf(ExternalAgentChoice("a1", "写作"))
        val vm = viewModel()
        val events = events(vm)
        vm.open(markdown("# a"))
        vm.openSendPanel()

        vm.startNewSession()

        assertThat(targets.createdFor).containsExactly("a1")
        assertThat(events).containsExactly(ExternalDocumentEvent.OpenSession("new-session"))
        assertThat(inbox.take("new-session")).hasSize(1)

        targets.agents = listOf(ExternalAgentChoice("a1", "写作"), ExternalAgentChoice("a2", "编程"))
        vm.open(markdown("# b"))
        vm.openSendPanel()
        vm.startNewSession()
        assertThat((vm.state.value.panel as ExternalDocumentPanel.Send).pickingAgent).isTrue()
        vm.panelBack()
        assertThat((vm.state.value.panel as ExternalDocumentPanel.Send).pickingAgent).isFalse()
        vm.startNewSession()
        vm.createSessionAndSend("a2")
        assertThat(targets.createdFor).containsExactly("a1", "a2").inOrder()
    }

    @Test
    fun `新建会话失败时提示并保留查看器`() = runTest {
        targets.agents = listOf(ExternalAgentChoice("a1", "写作"))
        targets.created = null
        val vm = viewModel()
        val events = events(vm)
        vm.open(markdown("# a"))
        vm.openSendPanel()

        vm.startNewSession()

        assertThat(events).containsExactly(ExternalDocumentEvent.Notice(ExternalDocumentNotice.CreateSessionFailed))
        assertThat(vm.state.value.visible).isTrue()
        assertThat(vm.state.value.busy).isFalse()
        assertThat(inbox.pending.value).isEmpty()
    }

    @Test
    fun `另存为写出暂存副本`() = runTest {
        val vm = viewModel()
        val events = events(vm)
        vm.open(markdown("# 另存"))
        val output = ByteArrayOutputStream()

        vm.saveAs { output }
        vm.saveAs { null }

        assertThat(output.toString(Charsets.UTF_8.name())).isEqualTo("# 另存")
        assertThat(events).containsExactly(
            ExternalDocumentEvent.Notice(ExternalDocumentNotice.SavedAs),
            ExternalDocumentEvent.Notice(ExternalDocumentNotice.SaveAsFailed),
        ).inOrder()
        assertThat(vm.suggestedName()).isEqualTo("周报.md")
    }

    private class FakeTargets : ExternalDocumentTargets {
        var folders: Map<String, List<ShareKnowledgeFolder>> = emptyMap()
        var existing: Set<String> = emptySet()
        var sessions: List<ShareSessionChoice> = emptyList()
        var agents: List<ExternalAgentChoice> = emptyList()
        var created: String? = "new-session"
        val createdFor = mutableListOf<String>()

        override suspend fun knowledgeRootUuid() = "kb"
        override suspend fun folders(parentUuid: String) = folders[parentUuid].orEmpty()
        override suspend fun folderExists(uuid: String) = uuid in existing
        override suspend fun sessions() = sessions
        override suspend fun agents() = agents
        override suspend fun createSession(agentId: String): String? {
            createdFor += agentId
            return created
        }
    }

    private class FakeMemory : ExternalFolderMemory {
        var trail: List<ShareKnowledgeFolder> = emptyList()
        var saved: List<ShareKnowledgeFolder>? = null
        override fun load() = trail
        override fun save(trail: List<ShareKnowledgeFolder>) {
            saved = trail
        }
    }
}
