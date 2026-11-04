package com.promenar.infinity.ui.files.preview

import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileOpCode
import com.promenar.infinity.domain.files.FilePreview
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.files.FileSearchHit
import com.promenar.infinity.domain.files.FileSort
import com.promenar.infinity.domain.files.FileSortKey
import com.promenar.infinity.domain.files.FileTypeFilter
import com.promenar.infinity.ui.files.FileKind
import com.promenar.infinity.ui.files.fileExtension
import com.promenar.infinity.ui.files.fileKindOf
import com.promenar.infinity.ui.files.state.FileCrumb
import com.promenar.infinity.ui.files.state.FileManagerController
import com.promenar.infinity.ui.files.state.FileManagerNotice
import com.promenar.infinity.ui.files.state.FileManagerUiState
import com.promenar.infinity.ui.files.state.FileSearchState
import com.promenar.infinity.ui.files.state.FileTargetPickerState
import com.promenar.infinity.ui.files.state.FileTransferMode
import com.promenar.infinity.ui.files.state.FileViewMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * 预览、截图与实验页用的内存控制器：在一棵内存文件树上实现契约的全部动作，不访问任何存储。
 * 只服务界面演示，不追求与真实控制器逐项一致（例如移动文件夹时不搬迁其子项）。
 */
internal class FakeFileManagerController(
    locations: List<FileLocation>,
    /** 目录 → 直接子项。 */
    tree: Map<FileRef, List<FileItem>>,
    private val previews: Map<String, FilePreview> = emptyMap(),
    initialLocationId: String = locations.first().id,
    initialPath: String = "/",
    private val clock: () -> Long = { System.currentTimeMillis() },
) : FileManagerController {
    private val tree: MutableMap<FileRef, MutableList<FileItem>> =
        tree.mapValues { it.value.toMutableList() }.toMutableMap()
    private val _state = MutableStateFlow(FileManagerUiState(locations = locations))
    override val state: StateFlow<FileManagerUiState> = _state.asStateFlow()

    init {
        load(initialLocationId, initialPath)
    }

    private fun update(block: (FileManagerUiState) -> FileManagerUiState) {
        _state.value = block(_state.value)
    }

    private fun location(id: String): FileLocation? = _state.value.locations.firstOrNull { it.id == id }

    private fun children(locationId: String, path: String): List<FileItem> = tree[FileRef(locationId, path)].orEmpty()

    private fun crumbsFor(locationId: String, path: String): List<FileCrumb> {
        val root = FileCrumb(location(locationId)?.title.orEmpty(), "/")
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        return listOf(root) + parts.mapIndexed { index, part -> FileCrumb(part, "/" + parts.take(index + 1).joinToString("/")) }
    }

    private fun arrange(items: List<FileItem>, sort: FileSort, showHidden: Boolean): List<FileItem> {
        val visible = if (showHidden) items else items.filterNot { it.name.startsWith('.') }
        val comparator: Comparator<FileItem> = when (sort.key) {
            FileSortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            FileSortKey.MODIFIED -> compareBy { it.lastModified }
            FileSortKey.SIZE -> compareBy { it.sizeBytes }
            FileSortKey.TYPE -> compareBy<FileItem> { fileExtension(it.name) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val ordered = visible.sortedWith(if (sort.ascending) comparator else comparator.reversed())
        return if (sort.foldersFirst) ordered.filter { it.isDirectory } + ordered.filterNot { it.isDirectory } else ordered
    }

    private fun load(locationId: String, path: String) {
        val target = location(locationId) ?: return
        update { s ->
            s.copy(
                location = target,
                path = path,
                crumbs = crumbsFor(locationId, path),
                items = arrange(children(locationId, path), s.sort, s.showHidden),
                loading = false,
                loadError = null,
                selection = emptySet(),
                previewItem = null,
                preview = null,
                previewLoading = false,
            )
        }
    }

    private fun reload() {
        val s = _state.value
        val locationId = s.location?.id ?: return
        update { it.copy(items = arrange(children(locationId, s.path), s.sort, s.showHidden)) }
    }

    private fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { "/" }

    private fun childPath(parent: String, name: String): String = if (parent == "/") "/$name" else "$parent/$name"

    private fun currentDir(): FileRef? {
        val s = _state.value
        return s.location?.id?.let { FileRef(it, s.path) }
    }

    private fun targets(items: List<FileItem>): List<FileItem> = items.ifEmpty {
        val s = _state.value
        s.items.filter { it.ref.path in s.selection }
    }

    private fun notice(notice: FileManagerNotice) = update { it.copy(notice = notice) }

    private fun nameTaken(dir: FileRef, name: String): Boolean = children(dir.locationId, dir.path).any { it.name == name }

    private fun addItem(dir: FileRef, name: String, isDirectory: Boolean, sizeBytes: Long = 0): FileItem? {
        if (nameTaken(dir, name)) {
            notice(FileManagerNotice.Failed(FileManagerNotice.Kind.CREATE, FileOpCode.NAME_EXISTS, 1, 1))
            return null
        }
        val item = FileItem(
            ref = FileRef(dir.locationId, childPath(dir.path, name)),
            name = name,
            isDirectory = isDirectory,
            sizeBytes = sizeBytes,
            lastModified = clock(),
            childCount = if (isDirectory) 0 else null,
        )
        tree.getOrPut(dir) { mutableListOf() }.add(item)
        if (isDirectory) tree.getOrPut(item.ref) { mutableListOf() }
        return item
    }

    // 浏览
    override fun openLocation(locationId: String, path: String) = load(locationId, path)

    override fun open(item: FileItem) {
        if (item.isDirectory) load(item.ref.locationId, item.ref.path) else showPreview(item)
    }

    override fun goUp() {
        val s = _state.value
        val locationId = s.location?.id ?: return
        if (s.path != "/") load(locationId, parentOf(s.path))
    }

    override fun goTo(path: String) {
        val locationId = _state.value.location?.id ?: return
        load(locationId, path)
    }

    override fun refresh() = reload()

    // 排序与视图
    override fun setSortKey(key: FileSortKey) {
        update { it.copy(sort = it.sort.copy(key = key, ascending = key == FileSortKey.NAME || key == FileSortKey.TYPE)) }
        reload()
    }

    override fun toggleSortDirection() {
        update { it.copy(sort = it.sort.copy(ascending = !it.sort.ascending)) }
        reload()
    }

    override fun toggleFoldersFirst() {
        update { it.copy(sort = it.sort.copy(foldersFirst = !it.sort.foldersFirst)) }
        reload()
    }

    override fun setViewMode(mode: FileViewMode) = update { it.copy(viewMode = mode) }

    override fun toggleShowHidden() {
        update { it.copy(showHidden = !it.showHidden) }
        reload()
    }

    // 选择
    override fun toggleSelection(item: FileItem) = update {
        val path = item.ref.path
        it.copy(selection = if (path in it.selection) it.selection - path else it.selection + path)
    }

    override fun selectAll() = update { s -> s.copy(selection = s.items.map { it.ref.path }.toSet()) }

    override fun clearSelection() = update { it.copy(selection = emptySet()) }

    // 搜索
    override fun startSearch() = update { it.copy(search = FileSearchState(), selection = emptySet()) }

    override fun updateSearch(transform: (FileSearchState) -> FileSearchState) {
        val current = _state.value.search ?: return
        val next = transform(current)
        update { it.copy(search = next.copy(running = false, hits = searchHits(next))) }
    }

    override fun closeSearch() = update { it.copy(search = null) }

    private fun searchHits(search: FileSearchState): List<FileSearchHit> {
        val s = _state.value
        val locationId = s.location?.id ?: return emptyList()
        if (search.text.isBlank() && search.types.isEmpty() && search.modifiedAfter == null) return emptyList()
        return tree.entries
            .filter { (dir, _) -> dir.locationId == locationId }
            .filter { (dir, _) -> !search.withinCurrentFolder || s.path == "/" || dir.path == s.path || dir.path.startsWith(s.path + "/") }
            .flatMap { (dir, items) -> items.map { FileSearchHit(it, dir.path) } }
            .filter { hit -> search.text.isBlank() || hit.item.name.contains(search.text.trim(), ignoreCase = true) }
            .filter { hit -> search.types.isEmpty() || typeFilterOf(hit.item) in search.types }
            .filter { hit -> search.modifiedAfter == null || hit.item.lastModified >= search.modifiedAfter }
            .filter { hit -> s.showHidden || !hit.item.name.startsWith('.') }
            .sortedByDescending { it.item.lastModified }
    }

    private fun typeFilterOf(item: FileItem): FileTypeFilter? = when (fileKindOf(item)) {
        FileKind.FOLDER -> null
        FileKind.PDF, FileKind.DOCUMENT, FileKind.TEXT -> FileTypeFilter.DOCUMENT
        FileKind.IMAGE -> FileTypeFilter.IMAGE
        FileKind.AUDIO -> FileTypeFilter.AUDIO
        FileKind.VIDEO -> FileTypeFilter.VIDEO
        FileKind.ARCHIVE -> FileTypeFilter.ARCHIVE
        FileKind.CODE -> FileTypeFilter.CODE
        FileKind.OTHER -> FileTypeFilter.OTHER
    }

    override fun reveal(hit: FileSearchHit) {
        update { it.copy(search = null) }
        if (hit.item.isDirectory) {
            load(hit.item.ref.locationId, hit.item.ref.path)
        } else {
            load(hit.item.ref.locationId, hit.parentPath)
            showPreview(hit.item)
        }
    }

    // 预览
    override fun showPreview(item: FileItem) = update {
        it.copy(previewItem = item, preview = previews[item.ref.path] ?: FilePreview.Unsupported, previewLoading = false)
    }

    override fun closePreview() = update { it.copy(previewItem = null, preview = null, previewLoading = false) }

    // 写操作
    override fun createFolder(name: String) {
        val dir = currentDir() ?: return
        if (addItem(dir, name, isDirectory = true) != null) notice(FileManagerNotice.Done(FileManagerNotice.Kind.CREATE, 1))
        reload()
    }

    override fun createTextFile(name: String) {
        val dir = currentDir() ?: return
        val item = addItem(dir, name, isDirectory = false)
        reload()
        if (item != null) {
            notice(FileManagerNotice.Done(FileManagerNotice.Kind.CREATE, 1))
            showPreview(item)
        }
    }

    override fun rename(item: FileItem, newName: String) {
        val dir = FileRef(item.ref.locationId, parentOf(item.ref.path))
        if (nameTaken(dir, newName)) {
            notice(FileManagerNotice.Failed(FileManagerNotice.Kind.RENAME, FileOpCode.NAME_EXISTS, 1, 1))
            return
        }
        val list = tree[dir] ?: return
        val index = list.indexOfFirst { it.ref == item.ref }
        if (index < 0) return
        list[index] = item.copy(name = newName, ref = FileRef(dir.locationId, childPath(dir.path, newName)))
        reload()
        update { it.copy(selection = emptySet()) }
        notice(FileManagerNotice.Done(FileManagerNotice.Kind.RENAME, 1))
    }

    override fun delete(items: List<FileItem>) {
        val doomed = targets(items)
        doomed.forEach { item -> tree[FileRef(item.ref.locationId, parentOf(item.ref.path))]?.removeAll { it.ref == item.ref } }
        reload()
        update { s ->
            s.copy(
                selection = emptySet(),
                previewItem = s.previewItem?.takeIf { preview -> doomed.none { it.ref == preview.ref } },
            )
        }
        notice(FileManagerNotice.Done(FileManagerNotice.Kind.DELETE, doomed.size))
    }

    override fun compress(items: List<FileItem>, archiveName: String) {
        val dir = currentDir() ?: return
        val sources = targets(items)
        addItem(dir, archiveName, isDirectory = false, sizeBytes = sources.sumOf { it.sizeBytes } / 2)
        reload()
        update { it.copy(selection = emptySet()) }
        notice(FileManagerNotice.Done(FileManagerNotice.Kind.COMPRESS, sources.size))
    }

    override fun extract(item: FileItem) {
        val dir = currentDir() ?: return
        addItem(dir, item.name.substringBeforeLast('.'), isDirectory = true)
        reload()
        notice(FileManagerNotice.Done(FileManagerNotice.Kind.EXTRACT, 1))
    }

    override fun importUris(uris: List<String>) = notice(FileManagerNotice.Done(FileManagerNotice.Kind.IMPORT, uris.size))

    override fun importText(text: String, name: String) {
        val dir = currentDir() ?: return
        addItem(dir, name, isDirectory = false, sizeBytes = text.toByteArray().size.toLong())
        reload()
        notice(FileManagerNotice.Done(FileManagerNotice.Kind.IMPORT, 1))
    }

    // 复制到 / 移动到
    private fun pickerAt(mode: FileTransferMode, sources: List<FileRef>, locationId: String, path: String) = FileTargetPickerState(
        mode = mode,
        sources = sources,
        locationId = locationId,
        path = path,
        crumbs = crumbsFor(locationId, path),
        folders = children(locationId, path).filter { it.isDirectory }.sortedBy { it.name },
    )

    override fun openTargetPicker(mode: FileTransferMode, items: List<FileItem>) {
        val locationId = _state.value.location?.id ?: return
        val sources = targets(items).map { it.ref }
        if (sources.isEmpty()) return
        update { it.copy(targetPicker = pickerAt(mode, sources, locationId, "/")) }
    }

    override fun pickerOpenLocation(locationId: String) = update { s ->
        val picker = s.targetPicker ?: return@update s
        s.copy(targetPicker = pickerAt(picker.mode, picker.sources, locationId, "/"))
    }

    override fun pickerGoTo(path: String) = update { s ->
        val picker = s.targetPicker ?: return@update s
        s.copy(targetPicker = pickerAt(picker.mode, picker.sources, picker.locationId, path))
    }

    override fun pickerCreateFolder(name: String) {
        val picker = _state.value.targetPicker ?: return
        val item = addItem(FileRef(picker.locationId, picker.path), name, isDirectory = true) ?: return
        pickerGoTo(item.ref.path)
        reload()
    }

    override fun confirmTargetPicker() {
        val picker = _state.value.targetPicker ?: return
        val target = FileRef(picker.locationId, picker.path)
        val moved = picker.sources.mapNotNull { ref ->
            val parent = FileRef(ref.locationId, parentOf(ref.path))
            val item = tree[parent]?.firstOrNull { it.ref == ref } ?: return@mapNotNull null
            if (picker.mode == FileTransferMode.MOVE) tree[parent]?.remove(item)
            val copy = item.copy(ref = FileRef(target.locationId, childPath(target.path, item.name)))
            tree.getOrPut(target) { mutableListOf() }.add(copy)
            copy
        }
        val title = picker.crumbs.joinToString(" › ") { it.title }
        update { it.copy(targetPicker = null, selection = emptySet()) }
        reload()
        val kind = if (picker.mode == FileTransferMode.MOVE) FileManagerNotice.Kind.MOVE else FileManagerNotice.Kind.COPY
        notice(FileManagerNotice.Done(kind, moved.size, title))
    }

    override fun dismissTargetPicker() = update { it.copy(targetPicker = null) }

    override fun togglePinCurrentFolder() {
        val pinned = !_state.value.pinned
        update { it.copy(pinned = pinned) }
        if (pinned) notice(FileManagerNotice.Done(FileManagerNotice.Kind.PIN, 1))
    }

    override suspend fun shareUri(item: FileItem): String = "content://example.invalid/files/${item.ref.locationId}${item.ref.path}"

    override fun consumeNotice() = update { it.copy(notice = null) }
}
