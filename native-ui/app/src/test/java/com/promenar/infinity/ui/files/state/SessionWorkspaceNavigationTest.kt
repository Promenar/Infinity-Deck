package com.promenar.infinity.ui.files.state

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.files.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionWorkspaceNavigationTest {
    private val sessions = FileLocation("sessions", FileLocationKind.SESSION_WORKSPACE, "会话工作区",
        FileLocationCapabilities(false, false, false))
    private val session = FileLocation("session:example", FileLocationKind.SESSION_WORKSPACE, "会话甲",
        FileLocationCapabilities(true, true, true, git = true, terminal = true), sessionId = "example")
    private val entry = FileItem(FileRef(session.id, "/"), "会话甲", true, 0, 1)
    private val folder = file("/docs", session.id, directory = true)
    private val document = file("/docs/note.txt", session.id)

    private fun services() = FakeFileServices().apply {
        locations.session.value = listOf(session)
        operations.listings[FileRef("sessions", "/")] = listOf(entry)
        operations.listings[FileRef(session.id, "/")] = listOf(folder)
        operations.listings[FileRef(session.id, "/docs")] = listOf(document)
    }

    private fun TestScope.controller(services: FakeFileServices, initial: FileRef = FileRef("sessions", "/")) =
        DefaultFileManagerController(backgroundScope, services, MutableStateFlow(listOf(location(), sessions)), initial,
            StandardTestDispatcher(testScheduler))

    @Test fun `会话列表进入隐藏位置并读取内容和会话能力`() = runTest {
        val services = services()
        val controller = controller(services)
        runCurrent()

        controller.open(entry)
        runCurrent()

        assertThat(controller.state.value.location).isEqualTo(session)
        assertThat(controller.state.value.items).containsExactly(folder)
        assertThat(controller.state.value.locations.map { it.id }).containsExactly("library", "sessions").inOrder()
        assertThat(controller.state.value.crumbs.map { it.title }).containsExactly("会话工作区", "会话甲").inOrder()
        assertThat(controller.state.value.canGoUp).isTrue()
    }

    @Test fun `子目录面包屑可返回会话根和会话列表`() = runTest {
        val controller = controller(services())
        runCurrent()
        controller.open(entry)
        runCurrent()
        controller.open(folder)
        runCurrent()
        assertThat(controller.state.value.items).containsExactly(document)
        assertThat(controller.state.value.crumbs.map { it.title }).containsExactly("会话工作区", "会话甲", "docs").inOrder()
        controller.goUp()
        runCurrent()
        assertThat(controller.state.value.location?.id).isEqualTo(session.id)
        assertThat(controller.state.value.path).isEqualTo("/")
        controller.open(folder)
        runCurrent()
        controller.goTo(controller.state.value.crumbs[1].path)
        runCurrent()
        assertThat(controller.state.value.location?.id).isEqualTo(session.id)
        assertThat(controller.state.value.path).isEqualTo("/")
        controller.goUp()
        runCurrent()
        assertThat(controller.state.value.location?.id).isEqualTo("sessions")
        controller.open(entry)
        runCurrent()
        controller.open(folder)
        runCurrent()
        controller.goTo(controller.state.value.crumbs.first().path)
        runCurrent()
        assertThat(controller.state.value.location?.id).isEqualTo("sessions")
    }

    @Test fun `会话文件夹置顶并可由初始引用或位置入口重新打开`() = runTest {
        val services = services()
        val first = controller(services)
        runCurrent()
        first.open(entry)
        runCurrent()
        first.open(folder)
        runCurrent()
        first.togglePinCurrentFolder()
        runCurrent()
        assertThat(services.pins.values.value).containsExactly(FileRef(session.id, "/docs"))
        assertThat(first.state.value.pinned).isTrue()
        first.openLocation("library")
        runCurrent()
        first.openLocation(session.id, "/docs")
        runCurrent()
        assertThat(first.state.value.items).containsExactly(document)
        val restored = controller(services, FileRef(session.id, "/docs"))
        runCurrent()
        assertThat(restored.state.value.location?.id).isEqualTo(session.id)
        assertThat(restored.state.value.items).containsExactly(document)
    }

    @Test fun `会话内搜索结果可以定位并预览`() = runTest {
        val services = services()
        services.operations.searchHandler = { locationId, _ ->
            assertThat(locationId).isEqualTo(session.id)
            listOf(FileSearchHit(document, "/docs"))
        }
        val controller = controller(services)
        runCurrent()
        controller.open(entry)
        runCurrent()
        controller.startSearch()
        controller.updateSearch { it.copy(text = "note") }
        advanceTimeBy(250)
        runCurrent()
        val hit = controller.state.value.search!!.hits.single()
        controller.reveal(hit)
        runCurrent()
        assertThat(controller.state.value.path).isEqualTo("/docs")
        assertThat(controller.state.value.preview).isEqualTo(FilePreview.Text("/docs/note.txt", false, false))
    }

    @Test fun `会话汇总搜索结果可以解析隐藏位置并预览`() = runTest {
        val services = services()
        services.operations.searchHandler = { locationId, _ ->
            assertThat(locationId).isEqualTo("sessions")
            listOf(FileSearchHit(document, "/docs"))
        }
        val controller = controller(services)
        runCurrent()
        controller.startSearch()
        controller.updateSearch { it.copy(text = "note") }
        advanceTimeBy(250)
        runCurrent()
        controller.reveal(controller.state.value.search!!.hits.single())
        runCurrent()
        assertThat(controller.state.value.location?.id).isEqualTo(session.id)
        assertThat(controller.state.value.path).isEqualTo("/docs")
        assertThat(controller.state.value.preview).isEqualTo(FilePreview.Text("/docs/note.txt", false, false))
    }

    @Test fun `会话名称及能力变化同步到当前视图和目标选择器`() = runTest {
        val services = services()
        val controller = controller(services)
        runCurrent()
        controller.open(entry)
        runCurrent()
        controller.open(folder)
        runCurrent()
        controller.openTargetPicker(FileTransferMode.COPY, listOf(document))
        runCurrent()
        val updated = session.copy(title = "会话新名", capabilities = session.capabilities.copy(git = false))
        services.locations.session.value = listOf(updated)
        runCurrent()
        assertThat(controller.state.value.location).isEqualTo(updated)
        assertThat(controller.state.value.crumbs.map { it.title }).containsExactly("会话工作区", "会话新名", "docs").inOrder()
        assertThat(controller.state.value.targetPicker!!.crumbs.first().title).isEqualTo("会话新名")
    }

    @Test fun `会话位置内复制和移动目标选择器可进入子目录`() = runTest {
        for (mode in FileTransferMode.entries) {
            val services = services()
            val source = file("/source.txt", session.id)
            services.operations.listings[FileRef(session.id, "/")] = listOf(folder, source)
            var transfer: Pair<List<FileRef>, FileRef>? = null
            val handler: suspend (List<FileRef>, FileRef) -> FileOpOutcome = { refs, target ->
                transfer = refs to target
                FileOpOutcome(listOf(FileRef(target.locationId, "${target.path}/source.txt")), emptyList())
            }
            services.operations.copyHandler = handler
            services.operations.moveHandler = handler
            val controller = controller(services)
            runCurrent()
            controller.open(entry)
            runCurrent()
            controller.openTargetPicker(mode, listOf(source))
            runCurrent()
            assertThat(controller.state.value.targetPicker?.folders).containsExactly(folder)
            controller.pickerGoTo("/docs")
            runCurrent()
            controller.confirmTargetPicker()
            runCurrent()
            assertThat(transfer).isEqualTo(listOf(source.ref) to FileRef(session.id, "/docs"))
        }
    }

    @Test fun `会话删除时回到列表并拒绝失效位置操作`() = runTest {
        val services = services()
        val controller = controller(services)
        runCurrent()
        controller.open(entry)
        runCurrent()
        controller.open(folder)
        runCurrent()
        controller.showPreview(document)
        runCurrent()
        controller.openTargetPicker(FileTransferMode.COPY, listOf(document))
        runCurrent()
        assertThat(controller.state.value.targetPicker).isNotNull()

        services.locations.session.value = emptyList()
        runCurrent()

        assertThat(controller.state.value.location?.id).isEqualTo("sessions")
        assertThat(controller.state.value.previewItem).isNull()
        assertThat(controller.state.value.targetPicker).isNull()
        val calls = services.operations.listCalls.size
        controller.open(folder)
        runCurrent()
        assertThat(services.operations.listCalls).hasSize(calls)
    }

    @Test fun `离开后迟到的会话位置不会覆盖当前导航`() = runTest {
        val services = services()
        val gate = CompletableDeferred<Unit>()
        val locationSource = object : FileLocationsSource by services.locations {
            override fun observeSessionLocations(sessionId: String) = flow {
                gate.await()
                emit(listOf(session))
            }
        }
        val wrapped = object : FileManagerServices by services {
            override val locations = locationSource
        }
        val controller = DefaultFileManagerController(backgroundScope, wrapped,
            MutableStateFlow(listOf(location(), sessions)), FileRef("sessions", "/"), StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.open(entry)
        runCurrent()
        controller.openLocation("library")
        gate.complete(Unit)
        runCurrent()
        assertThat(controller.state.value.location?.id).isEqualTo("library")
    }

    @Test fun `会话自身作用域保持原有根面包屑和返回行为`() = runTest {
        val services = services()
        val controller = DefaultFileManagerController(backgroundScope, services, MutableStateFlow(listOf(session)),
            FileRef(session.id, "/"), StandardTestDispatcher(testScheduler))
        runCurrent()
        assertThat(controller.state.value.crumbs.map { it.title }).containsExactly("会话甲")
        assertThat(controller.state.value.canGoUp).isFalse()
        controller.open(folder)
        runCurrent()
        controller.goUp()
        runCurrent()
        assertThat(controller.state.value.location?.id).isEqualTo(session.id)
        assertThat(controller.state.value.path).isEqualTo("/")
    }
}
