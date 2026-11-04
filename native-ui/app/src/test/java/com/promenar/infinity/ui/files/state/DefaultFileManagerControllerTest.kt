package com.promenar.infinity.ui.files.state

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.files.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultFileManagerControllerTest {
    private fun TestScope.controller(
        services: FakeFileServices,
        locations: MutableStateFlow<List<FileLocation>> = MutableStateFlow(listOf(location())),
        initial: FileRef? = null,
        preferences: FileManagerPreferences? = null,
    ) = DefaultFileManagerController(backgroundScope, services, locations, initial, StandardTestDispatcher(testScheduler), preferences)

    @Test fun `目录切换取消旧加载且过期结果不能覆盖`() = runTest {
        val services = FakeFileServices()
        val blocked = CompletableDeferred<Unit>()
        val old = file("/old.txt")
        val fresh = file("/new/new.txt")
        services.operations.listHandler = { ref ->
            if (ref.path == "/") {
                withContext(NonCancellable) { blocked.await() }
                listOf(old)
            } else listOf(fresh)
        }
        val controller = controller(services)
        runCurrent()
        controller.goTo("/new")
        runCurrent()
        assertThat(controller.state.value.items).containsExactly(fresh)
        blocked.complete(Unit)
        runCurrent()
        assertThat(controller.state.value.path).isEqualTo("/new")
        assertThat(controller.state.value.items).containsExactly(fresh)
    }

    @Test fun `选择限定在当前目录并随切换和刷新清理`() = runTest {
        val services = FakeFileServices()
        val first = file("/a.txt")
        val second = file("/b.txt")
        services.operations.listings[FileRef("library", "/")] = listOf(first, second)
        val controller = controller(services)
        runCurrent()
        controller.selectAll()
        assertThat(controller.state.value.selection).containsExactly("/a.txt", "/b.txt")
        services.operations.listings[FileRef("library", "/")] = listOf(second)
        controller.refresh()
        runCurrent()
        assertThat(controller.state.value.selection).containsExactly("/b.txt")
        controller.goTo("/folder")
        runCurrent()
        assertThat(controller.state.value.selection).isEmpty()
        controller.toggleSelection(first)
        assertThat(controller.state.value.selection).isEmpty()
    }

    @Test fun `隐藏切换更新选择并保留排序和视图`() = runTest {
        val services = FakeFileServices()
        services.operations.listings[FileRef("library", "/")] = listOf(file("/.hidden"), file("/shown"))
        val controller = controller(services)
        runCurrent()
        controller.toggleShowHidden()
        controller.selectAll()
        controller.toggleShowHidden()
        controller.setSortKey(FileSortKey.NAME)
        controller.setViewMode(FileViewMode.GRID)
        assertThat(controller.state.value.selection).containsExactly("/shown")
        assertThat(controller.state.value.sort.ascending).isTrue()
        controller.setSortKey(FileSortKey.SIZE)
        assertThat(controller.state.value.sort.ascending).isFalse()
        controller.goTo("/other")
        runCurrent()
        assertThat(controller.state.value.viewMode).isEqualTo(FileViewMode.GRID)
        assertThat(controller.state.value.sort.key).isEqualTo(FileSortKey.SIZE)
    }

    @Test fun `搜索防抖取消过期请求并清空空文本`() = runTest {
        val services = FakeFileServices()
        val blocked = CompletableDeferred<Unit>()
        val fresh = FileSearchHit(file("/fresh.txt"), "/")
        services.operations.searchHandler = { _, query ->
            if (query.text == "old") {
                withContext(NonCancellable) { blocked.await() }
                listOf(FileSearchHit(file("/old.txt"), "/"))
            } else listOf(fresh)
        }
        val controller = controller(services)
        runCurrent()
        controller.startSearch()
        controller.updateSearch { it.copy(text = "old") }
        assertThat(controller.state.value.search?.running).isTrue()
        advanceTimeBy(249)
        runCurrent()
        assertThat(services.operations.searchCalls).isEmpty()
        advanceTimeBy(1)
        runCurrent()
        controller.updateSearch { it.copy(text = "new", withinCurrentFolder = true) }
        advanceTimeBy(250)
        runCurrent()
        assertThat(services.operations.searchCalls.map { it.text }).containsExactly("old", "new").inOrder()
        assertThat(services.operations.searchCalls.last().withinPath).isEqualTo("/")
        assertThat(controller.state.value.search?.hits).containsExactly(fresh)
        blocked.complete(Unit)
        runCurrent()
        assertThat(controller.state.value.search?.hits).containsExactly(fresh)
        controller.updateSearch { it.copy(text = " ") }
        assertThat(controller.state.value.search?.running).isFalse()
        assertThat(controller.state.value.search?.hits).isEmpty()
    }

    @Test fun `搜索结果定位父目录并预览文件`() = runTest {
        val services = FakeFileServices()
        val target = file("/docs/report.md")
        val controller = controller(services)
        runCurrent()
        controller.startSearch()
        controller.reveal(FileSearchHit(target, "/docs"))
        runCurrent()
        assertThat(controller.state.value.search).isNull()
        assertThat(controller.state.value.path).isEqualTo("/docs")
        assertThat(controller.state.value.previewItem).isEqualTo(target)
        assertThat(controller.state.value.preview).isEqualTo(FilePreview.Text("/docs/report.md", false, false))
    }

    @Test fun `预览过期请求不能覆盖当前文件`() = runTest {
        val services = FakeFileServices()
        val blocked = CompletableDeferred<Unit>()
        services.operations.previewHandler = { ref ->
            if (ref.path == "/a") withContext(NonCancellable) { blocked.await() }
            FilePreview.Text(ref.path, false, false)
        }
        val controller = controller(services)
        runCurrent()
        controller.showPreview(file("/a"))
        runCurrent()
        controller.showPreview(file("/b"))
        runCurrent()
        blocked.complete(Unit)
        runCurrent()
        assertThat(controller.state.value.previewItem?.ref?.path).isEqualTo("/b")
        assertThat(controller.state.value.preview).isEqualTo(FilePreview.Text("/b", false, false))
    }

    @Test fun `写操作刷新目录清空选择并映射异常`() = runTest {
        val services = FakeFileServices()
        val item = file("/a")
        services.operations.listings[FileRef("library", "/")] = listOf(item)
        val controller = controller(services)
        runCurrent()
        controller.toggleSelection(item)
        val before = services.operations.listCalls.size
        controller.delete()
        runCurrent()
        assertThat(controller.state.value.selection).isEmpty()
        assertThat(services.operations.listCalls.size).isGreaterThan(before)
        assertThat(controller.state.value.notice).isEqualTo(FileManagerNotice.Done(FileManagerNotice.Kind.DELETE, 1))
        services.operations.deleteHandler = { throw FileOpException(FileOpCode.READ_ONLY) }
        controller.delete(listOf(item))
        runCurrent()
        assertThat(controller.state.value.notice).isEqualTo(FileManagerNotice.Failed(FileManagerNotice.Kind.DELETE, FileOpCode.READ_ONLY, 1, 1))
    }

    @Test fun `部分失败按最高频错误汇总并关闭已删除预览`() = runTest {
        val services = FakeFileServices()
        val items = (1..4).map { file("/$it.txt") }
        services.operations.listings[FileRef("library", "/")] = items
        services.operations.deleteHandler = { refs -> FileOpOutcome(
            listOf(refs[0]), listOf(FileOpFailure(refs[1], FileOpCode.IO), FileOpFailure(refs[2], FileOpCode.READ_ONLY), FileOpFailure(refs[3], FileOpCode.READ_ONLY)),
        ) }
        val controller = controller(services)
        runCurrent()
        controller.showPreview(items[0])
        controller.delete(items)
        runCurrent()
        assertThat(controller.state.value.previewItem).isNull()
        assertThat(controller.state.value.notice).isEqualTo(FileManagerNotice.Failed(FileManagerNotice.Kind.DELETE, FileOpCode.READ_ONLY, 3, 4))
    }

    @Test fun `目标选择器只显示文件夹并拒绝移动到自身子目录`() = runTest {
        val services = FakeFileServices()
        val folder = file("/folder", directory = true)
        services.operations.listings[FileRef("library", "/")] = listOf(folder, file("/a.txt"))
        services.operations.listings[FileRef("library", "/folder")] = listOf(file("/folder/sub", directory = true))
        val controller = controller(services)
        runCurrent()
        controller.openTargetPicker(FileTransferMode.MOVE, listOf(folder))
        runCurrent()
        assertThat(controller.state.value.targetPicker?.folders).containsExactly(folder)
        controller.pickerGoTo("/folder/sub")
        runCurrent()
        controller.confirmTargetPicker()
        runCurrent()
        assertThat(services.operations.moveCalls).isEqualTo(0)
        assertThat(controller.state.value.notice).isEqualTo(FileManagerNotice.Failed(FileManagerNotice.Kind.MOVE, FileOpCode.CONFLICT, 1, 1))
    }

    @Test fun `目标选择器新建文件夹并进入且兄弟路径可移动`() = runTest {
        val services = FakeFileServices()
        val folder = file("/a", directory = true)
        services.operations.listings[FileRef("library", "/")] = listOf(folder)
        val controller = controller(services)
        runCurrent()
        controller.openTargetPicker(FileTransferMode.MOVE, listOf(folder))
        controller.pickerCreateFolder("ab")
        runCurrent()
        assertThat(controller.state.value.targetPicker?.path).isEqualTo("/ab")
        controller.confirmTargetPicker()
        runCurrent()
        assertThat(services.operations.moveCalls).isEqualTo(1)
        assertThat(controller.state.value.notice).isEqualTo(FileManagerNotice.Done(FileManagerNotice.Kind.MOVE, 1, "/ab"))
    }

    @Test fun `固定状态跟随流且达到上限提示不支持`() = runTest {
        val services = FakeFileServices()
        val controller = controller(services)
        runCurrent()
        controller.togglePinCurrentFolder()
        runCurrent()
        assertThat(controller.state.value.pinned).isTrue()
        controller.togglePinCurrentFolder()
        runCurrent()
        assertThat(controller.state.value.pinned).isFalse()
        services.pins.values.value = (1..6).map { FileRef("library", "/pin$it") }
        runCurrent()
        controller.togglePinCurrentFolder()
        runCurrent()
        assertThat(controller.state.value.notice).isEqualTo(FileManagerNotice.Failed(FileManagerNotice.Kind.PIN, FileOpCode.UNSUPPORTED, 1, 1))
    }

    @Test fun `当前位置消失回退第一个位置根目录`() = runTest {
        val services = FakeFileServices()
        val locations = MutableStateFlow(listOf(location(), location("session")))
        val controller = controller(services, locations, FileRef("session", "/docs"))
        runCurrent()
        assertThat(controller.state.value.location?.id).isEqualTo("session")
        assertThat(controller.state.value.path).isEqualTo("/docs")
        locations.value = listOf(location())
        runCurrent()
        assertThat(controller.state.value.location?.id).isEqualTo("library")
        assertThat(controller.state.value.path).isEqualTo("/")
        locations.value = emptyList()
        runCurrent()
        assertThat(controller.state.value.location).isNull()
        assertThat(controller.state.value.items).isEmpty()
    }

    @Test fun `偏好读取写入并避免迟到读取覆盖用户选择`() = runTest {
        val services = FakeFileServices()
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<FileManagerPreferenceValues>()
        val preferences = object : FileManagerPreferences {
            override suspend fun read(): FileManagerPreferenceValues {
                gate.await()
                return FileManagerPreferenceValues(viewMode = FileViewMode.LIST)
            }
            override suspend fun write(values: FileManagerPreferenceValues) { writes += values }
        }
        val controller = controller(services, preferences = preferences)
        runCurrent()
        controller.setViewMode(FileViewMode.GRID)
        gate.complete(Unit)
        runCurrent()
        assertThat(controller.state.value.viewMode).isEqualTo(FileViewMode.GRID)
        assertThat(writes.last().viewMode).isEqualTo(FileViewMode.GRID)
    }

    @Test fun `无效名称不进入后端且分享 URI 由后端生成`() = runTest {
        val services = FakeFileServices()
        val controller = controller(services)
        runCurrent()
        controller.createFolder("../wrong")
        runCurrent()
        assertThat(services.operations.createCalls).isEqualTo(0)
        assertThat(controller.state.value.notice).isEqualTo(FileManagerNotice.Failed(FileManagerNotice.Kind.CREATE, FileOpCode.INVALID_NAME, 1, 1))
        assertThat(controller.shareUri(file("/a"))).isEqualTo("content://example.invalid/a")
    }

    @Test fun `加载失败可刷新恢复且非文件异常映射 IO`() = runTest {
        val services = FakeFileServices()
        services.operations.listHandler = { throw IllegalStateException("synthetic") }
        val controller = controller(services)
        runCurrent()
        assertThat(controller.state.value.loading).isFalse()
        assertThat(controller.state.value.loadError).isEqualTo(FileOpCode.IO)
        val item = file("/ok")
        services.operations.listHandler = { listOf(item) }
        controller.refresh()
        runCurrent()
        assertThat(controller.state.value.loadError).isNull()
        assertThat(controller.state.value.items).containsExactly(item)
    }

    @Test fun `搜索过滤器与时间约束传入后端`() = runTest {
        val services = FakeFileServices()
        val controller = controller(services, initial = FileRef("library", "/docs"))
        runCurrent()
        controller.startSearch()
        controller.updateSearch { it.copy(text = "report", withinCurrentFolder = true, types = setOf(FileTypeFilter.DOCUMENT), modifiedAfter = 123L) }
        advanceTimeBy(250)
        runCurrent()
        assertThat(services.operations.searchCalls).containsExactly(FileSearchQuery("report", "/docs", setOf(FileTypeFilter.DOCUMENT), 123L))
        assertThat(controller.state.value.search?.running).isFalse()
    }

    @Test fun `复制目标支持切换位置并保留源引用`() = runTest {
        val services = FakeFileServices()
        val entry = file("/source")
        val otherFolder = file("/target", locationId = "other", directory = true)
        services.operations.listings[FileRef("library", "/")] = listOf(entry)
        services.operations.listings[FileRef("other", "/")] = listOf(otherFolder)
        var copied: Pair<List<FileRef>, FileRef>? = null
        services.operations.copyHandler = { refs, target -> copied = refs to target; FileOpOutcome(refs.map { FileRef(target.locationId, "${target.path}/${it.path.substringAfterLast('/')}") }, emptyList()) }
        val controller = controller(services, MutableStateFlow(listOf(location(), location("other"))))
        runCurrent()
        controller.openTargetPicker(FileTransferMode.COPY, listOf(entry))
        controller.pickerOpenLocation("other")
        runCurrent()
        assertThat(controller.state.value.targetPicker?.folders).containsExactly(otherFolder)
        controller.pickerGoTo("/target")
        runCurrent()
        controller.confirmTargetPicker()
        runCurrent()
        assertThat(copied).isEqualTo(listOf(entry.ref) to FileRef("other", "/target"))
        assertThat(controller.state.value.notice).isEqualTo(FileManagerNotice.Done(FileManagerNotice.Kind.COPY, 1, "/target"))
    }

    @Test fun `写入完成时不清掉用户在新目录里的选择`() = runTest {
        val services = FakeFileServices()
        val gate = CompletableDeferred<Unit>()
        val first = file("/first")
        val second = file("/new/second")
        services.operations.listings[FileRef("library", "/")] = listOf(first)
        services.operations.listings[FileRef("library", "/new")] = listOf(second)
        services.operations.deleteHandler = { refs -> gate.await(); FileOpOutcome(refs, emptyList()) }
        val controller = controller(services)
        runCurrent()
        controller.delete(listOf(first))
        runCurrent()
        controller.goTo("/new")
        runCurrent()
        controller.toggleSelection(second)
        gate.complete(Unit)
        runCurrent()
        assertThat(controller.state.value.selection).containsExactly(second.ref.path)
        assertThat(controller.state.value.notice).isEqualTo(FileManagerNotice.Done(FileManagerNotice.Kind.DELETE, 1))
    }

    @Test fun `恢复已保存偏好并在缺省情况下只保存在会话中`() = runTest {
        val services = FakeFileServices()
        val saved = FileManagerPreferenceValues(FileSort(FileSortKey.TYPE, true, false), FileViewMode.GRID, true)
        val preferences = object : FileManagerPreferences {
            override suspend fun read() = saved
            override suspend fun write(values: FileManagerPreferenceValues) = Unit
        }
        val controller = controller(services, preferences = preferences)
        runCurrent()
        assertThat(controller.state.value.sort).isEqualTo(saved.sort)
        assertThat(controller.state.value.viewMode).isEqualTo(FileViewMode.GRID)
        assertThat(controller.state.value.showHidden).isTrue()
    }


    @Test fun `往返同一目录时第一次加载不能覆盖新结果`() = runTest {
        val services = FakeFileServices()
        val gate = CompletableDeferred<Unit>()
        var rootCalls = 0
        val recent = file("/recent")
        services.operations.listHandler = { ref ->
            if (ref.path == "/" && ++rootCalls == 1) {
                withContext(NonCancellable) { gate.await() }
                listOf(file("/stale"))
            } else if (ref.path == "/") listOf(recent) else emptyList()
        }
        val controller = controller(services)
        runCurrent()
        controller.goTo("/other")
        runCurrent()
        controller.goTo("/")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertThat(controller.state.value.items).containsExactly(recent)
    }


    @Test fun `目标文件夹创建期间拒绝确认传输`() = runTest {
        val services = FakeFileServices()
        val gate = CompletableDeferred<Unit>()
        val entry = file("/source")
        var destination: FileRef? = null
        services.operations.listings[FileRef("library", "/")] = listOf(entry)
        services.operations.createFolderHandler = { parent, name ->
            gate.await()
            file("/${name}", parent.locationId, directory = true)
        }
        services.operations.moveHandler = { refs, target ->
            destination = target
            FileOpOutcome(refs.map { FileRef(target.locationId, "${target.path}/${it.path.substringAfterLast('/')}") }, emptyList())
        }
        val controller = controller(services)
        runCurrent()
        controller.openTargetPicker(FileTransferMode.MOVE, listOf(entry))
        runCurrent()
        controller.pickerCreateFolder("destination")
        runCurrent()
        assertThat(controller.state.value.targetPicker?.loading).isTrue()
        controller.toggleShowHidden()
        controller.pickerGoTo("/unintended")
        controller.pickerOpenLocation("library")
        assertThat(controller.state.value.targetPicker?.path).isEqualTo("/")
        controller.confirmTargetPicker()
        runCurrent()
        assertThat(services.operations.moveCalls).isEqualTo(0)
        gate.complete(Unit)
        runCurrent()
        assertThat(controller.state.value.targetPicker?.path).isEqualTo("/destination")
        controller.confirmTargetPicker()
        runCurrent()
        assertThat(destination).isEqualTo(FileRef("library", "/destination"))
    }

    @Test fun `位置失权清理旧状态与迟到结果恢复授权后重载`() = runTest {
        val services = FakeFileServices()
        val locations = MutableStateFlow(listOf(location()))
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val current = file("/current")
        services.operations.listHandler = {
            calls++
            if (calls == 1) {
                withContext(NonCancellable) { gate.await() }
                listOf(file("/stale"))
            } else listOf(current)
        }
        val controller = controller(services, locations)
        runCurrent()
        locations.value = listOf(location().copy(accessLost = true))
        runCurrent()
        assertThat(controller.state.value.loadError).isEqualTo(FileOpCode.PERMISSION_LOST)
        assertThat(controller.state.value.items).isEmpty()
        gate.complete(Unit)
        runCurrent()
        assertThat(controller.state.value.items).isEmpty()
        locations.value = listOf(location())
        runCurrent()
        assertThat(controller.state.value.loadError).isNull()
        assertThat(controller.state.value.items).containsExactly(current)
    }

    @Test fun `宿主移除位置后旧回调不能访问后端`() = runTest {
        val services = FakeFileServices()
        val locations = MutableStateFlow(listOf(location(), location("other")))
        val controller = controller(services, locations)
        runCurrent()
        val stale = file("/outside", locationId = "other")
        locations.value = listOf(location())
        runCurrent()
        var deleteCalls = 0
        services.operations.deleteHandler = { deleteCalls++; FileOpOutcome(it, emptyList()) }
        controller.delete(listOf(stale))
        runCurrent()
        assertThat(deleteCalls).isEqualTo(0)
        assertThat(controller.state.value.notice).isEqualTo(FileManagerNotice.Failed(FileManagerNotice.Kind.DELETE, FileOpCode.PERMISSION_LOST, 1, 1))
        controller.rename(stale, "renamed")
        controller.extract(stale)
        controller.openTargetPicker(FileTransferMode.COPY, listOf(stale))
        runCurrent()
        assertThat(services.operations.renameCalls).isEqualTo(0)
        assertThat(services.operations.extractCalls).isEqualTo(0)
        assertThat(controller.state.value.targetPicker).isNull()
        val error = try { controller.shareUri(stale); null } catch (e: FileOpException) { e.code }
        assertThat(error).isEqualTo(FileOpCode.PERMISSION_LOST)
        assertThat(services.operations.shareCalls).isEqualTo(0)
    }

    @Test fun `部分移动存在成功时保守关闭相关预览`() = runTest {
        val services = FakeFileServices()
        val first = file("/first")
        val second = file("/second")
        services.operations.listings[FileRef("library", "/")] = listOf(first, second)
        services.operations.moveHandler = { _, _ ->
            FileOpOutcome(listOf(FileRef("library", "/target/second")), listOf(FileOpFailure(first.ref, FileOpCode.NAME_EXISTS)))
        }
        val controller = controller(services)
        runCurrent()
        controller.showPreview(first)
        controller.openTargetPicker(FileTransferMode.MOVE, listOf(first, second))
        runCurrent()
        controller.pickerGoTo("/target")
        runCurrent()
        controller.confirmTargetPicker()
        runCurrent()
        assertThat(controller.state.value.previewItem).isNull()
        controller.showPreview(first)
        runCurrent()
        services.operations.moveHandler = { _, _ ->
            FileOpOutcome(listOf(FileRef("library", "/target/first")), listOf(FileOpFailure(second.ref, FileOpCode.NAME_EXISTS)))
        }
        controller.openTargetPicker(FileTransferMode.MOVE, listOf(first, second))
        runCurrent()
        controller.pickerGoTo("/target")
        runCurrent()
        controller.confirmTargetPicker()
        runCurrent()
        assertThat(controller.state.value.previewItem).isNull()
    }


    @Test fun `后端报告权限失效时清除已有目录与选择`() = runTest {
        val services = FakeFileServices()
        val first = file("/first")
        services.operations.listings[FileRef("library", "/")] = listOf(first)
        val controller = controller(services)
        runCurrent()
        controller.toggleSelection(first)
        controller.showPreview(first)
        runCurrent()
        services.operations.listHandler = { throw FileOpException(FileOpCode.PERMISSION_LOST) }
        controller.refresh()
        runCurrent()
        assertThat(controller.state.value.items).isEmpty()
        assertThat(controller.state.value.selection).isEmpty()
        assertThat(controller.state.value.previewItem).isNull()
        assertThat(controller.state.value.loadError).isEqualTo(FileOpCode.PERMISSION_LOST)
        var deleted = false
        services.operations.deleteHandler = { deleted = true; FileOpOutcome(it, emptyList()) }
        controller.delete(listOf(first))
        runCurrent()
        assertThat(deleted).isFalse()
        val error = try { controller.shareUri(first); null } catch (e: FileOpException) { e.code }
        assertThat(error).isEqualTo(FileOpCode.PERMISSION_LOST)
        services.operations.listHandler = { listOf(first) }
        controller.refresh()
        runCurrent()
        assertThat(controller.state.value.loadError).isNull()
        controller.delete(listOf(first))
        runCurrent()
        assertThat(deleted).isTrue()
    }


    @Test fun `在途写入完成不能自动解除位置失权封锁`() = runTest {
        val services = FakeFileServices()
        val gate = CompletableDeferred<Unit>()
        val entry = file("/entry")
        services.operations.listings[FileRef("library", "/")] = listOf(entry)
        services.operations.deleteHandler = { refs -> gate.await(); FileOpOutcome(refs, emptyList()) }
        val controller = controller(services)
        runCurrent()
        controller.delete(listOf(entry))
        runCurrent()
        services.operations.listHandler = { throw FileOpException(FileOpCode.PERMISSION_LOST) }
        controller.refresh()
        runCurrent()
        assertThat(controller.state.value.loadError).isEqualTo(FileOpCode.PERMISSION_LOST)
        val calls = services.operations.listCalls.size
        services.operations.listHandler = { listOf(entry) }
        gate.complete(Unit)
        runCurrent()
        assertThat(services.operations.listCalls.size).isEqualTo(calls)
        assertThat(controller.state.value.loadError).isEqualTo(FileOpCode.PERMISSION_LOST)
        assertThat(controller.state.value.items).isEmpty()
    }

}
