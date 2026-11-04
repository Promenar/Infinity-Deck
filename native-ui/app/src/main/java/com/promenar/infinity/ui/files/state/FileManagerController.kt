package com.promenar.infinity.ui.files.state

import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileOpCode
import com.promenar.infinity.domain.files.FilePreview
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.files.FileSearchHit
import com.promenar.infinity.domain.files.FileSort
import com.promenar.infinity.domain.files.FileSortKey
import com.promenar.infinity.domain.files.FileTypeFilter
import com.promenar.infinity.domain.files.RecycledItem
import kotlinx.coroutines.flow.StateFlow

/*
 * 资源管理器组件的状态与动作契约。Compose 组件只读 [FileManagerController.state] 并调用动作，
 * 不直接访问 FileOperations；同一套控制器供资料页、会话资源管理器与 Linux 文件页使用。
 */

enum class FileViewMode { LIST, GRID }

data class FileCrumb(val title: String, val path: String)

data class FileSearchState(
    val text: String = "",
    /** true：只搜当前文件夹及其子目录；false：整个位置。 */
    val withinCurrentFolder: Boolean = false,
    val types: Set<FileTypeFilter> = emptySet(),
    val modifiedAfter: Long? = null,
    val running: Boolean = false,
    val hits: List<FileSearchHit> = emptyList(),
)

enum class FileTransferMode { COPY, MOVE }

/** “复制到 / 移动到”目标选择器的状态。 */
data class FileTargetPickerState(
    val mode: FileTransferMode,
    val sources: List<FileRef>,
    val locationId: String,
    val path: String,
    val crumbs: List<FileCrumb>,
    val folders: List<FileItem>,
    val loading: Boolean = false,
)

/** 操作结束后给用户的一次性提示。 */
sealed interface FileManagerNotice {
    data class Done(val kind: Kind, val count: Int, val target: String? = null) : FileManagerNotice
    data class Failed(val kind: Kind, val code: FileOpCode, val failedCount: Int, val total: Int) : FileManagerNotice

    enum class Kind { CREATE, RENAME, COPY, MOVE, DELETE, RESTORE, PURGE, COMPRESS, EXTRACT, IMPORT, PIN }
}

data class FileManagerUiState(
    /** 宿主允许访问的位置，按导航顺序。 */
    val locations: List<FileLocation> = emptyList(),
    val location: FileLocation? = null,
    val path: String = "/",
    val crumbs: List<FileCrumb> = emptyList(),
    /** 已排序、已按隐藏文件设置过滤的当前目录内容。 */
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    /** 当前目录加载失败的原因；成功后清除。 */
    val loadError: FileOpCode? = null,
    val sort: FileSort = FileSort(),
    val viewMode: FileViewMode = FileViewMode.LIST,
    val showHidden: Boolean = false,
    /** 选择模式下已选条目的路径（当前目录内）；为空表示不在选择模式。 */
    val selection: Set<String> = emptySet(),
    /** 非 null 表示处于搜索模式。 */
    val search: FileSearchState? = null,
    /** 当前预览的文件；双栏布局显示在右栏，单列布局推入预览页。 */
    val previewItem: FileItem? = null,
    val preview: FilePreview? = null,
    val previewLoading: Boolean = false,
    val targetPicker: FileTargetPickerState? = null,
    /** 当前目录已固定到侧栏（仅资料页宿主有意义）。 */
    val pinned: Boolean = false,
    val notice: FileManagerNotice? = null,
) {
    val canGoUp: Boolean get() = path != "/" && search == null
    val selecting: Boolean get() = selection.isNotEmpty()
}

/** 回收站页的状态，与目录浏览分开，跨所有位置汇总。 */
data class RecycleBinUiState(
    val items: List<RecycledItem> = emptyList(),
    val running: Boolean = false,
    val notice: FileManagerNotice? = null,
)

interface FileManagerController {
    val state: StateFlow<FileManagerUiState>

    // 浏览
    fun openLocation(locationId: String, path: String = "/")
    fun open(item: FileItem)
    fun goUp()
    fun goTo(path: String)
    fun refresh()

    // 排序与视图：切到名称或类型时默认升序，其它默认降序。
    fun setSortKey(key: FileSortKey)
    fun toggleSortDirection()
    fun toggleFoldersFirst()
    fun setViewMode(mode: FileViewMode)
    fun toggleShowHidden()

    // 选择
    fun toggleSelection(item: FileItem)
    fun selectAll()
    fun clearSelection()

    // 搜索
    fun startSearch()
    fun updateSearch(transform: (FileSearchState) -> FileSearchState)
    fun closeSearch()

    /** 打开搜索结果：退出搜索，进入其所在文件夹；文件同时打开预览。 */
    fun reveal(hit: FileSearchHit)

    // 预览
    fun showPreview(item: FileItem)
    fun closePreview()

    // 写操作；[items] 为空时作用于当前选择。
    fun createFolder(name: String)
    fun createTextFile(name: String)
    fun rename(item: FileItem, newName: String)
    fun delete(items: List<FileItem> = emptyList())
    fun compress(items: List<FileItem> = emptyList(), archiveName: String)
    fun extract(item: FileItem)
    fun importUris(uris: List<String>)
    fun importText(text: String, name: String)

    // 复制到 / 移动到
    fun openTargetPicker(mode: FileTransferMode, items: List<FileItem> = emptyList())
    fun pickerOpenLocation(locationId: String)
    fun pickerGoTo(path: String)
    fun pickerCreateFolder(name: String)
    fun confirmTargetPicker()
    fun dismissTargetPicker()

    // 资料页侧栏固定
    fun togglePinCurrentFolder()

    suspend fun shareUri(item: FileItem): String

    fun consumeNotice()
}

interface RecycleBinController {
    val state: StateFlow<RecycleBinUiState>
    fun restore(items: List<RecycledItem>)
    fun purge(items: List<RecycledItem>)
    fun emptyAll()
    fun consumeNotice()
}
