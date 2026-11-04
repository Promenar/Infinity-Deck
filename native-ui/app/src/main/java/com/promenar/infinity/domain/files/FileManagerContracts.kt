package com.promenar.infinity.domain.files

import kotlinx.coroutines.flow.Flow

/*
 * 统一资源管理器的数据契约。资料页、会话资源管理器与 Linux 文件页都通过这里访问文件，
 * 界面只面对“位置 + 位置内路径”，不关心底层是 Room 文件树、真实路径、SAF 还是 Linux 主目录。
 */

/** 位置类别；决定图标、默认能力与在导航中的分组。 */
enum class FileLocationKind {
    /** 全局资料库（系统会话的 Room 文件树），内容默认建索引。 */
    KNOWLEDGE_BASE,

    /** 普通会话的私有工作区（Room 文件树）。 */
    SESSION_WORKSPACE,

    /** 自选项目工作区（真实目录）。 */
    PROJECT,

    /** 本机 Linux 环境主目录。 */
    LINUX_HOME,

    /** 经系统授权或所有文件访问挂载的外部文件夹。 */
    EXTERNAL_FOLDER,

    /** 分支 worktree 工作区。 */
    BRANCH_WORKTREE,
}

/** 位置支持的操作；界面据此隐藏不可用的菜单项。 */
data class FileLocationCapabilities(
    val writable: Boolean,
    val recycle: Boolean,
    val archive: Boolean,
    val git: Boolean = false,
    val terminal: Boolean = false,
)

data class FileLocation(
    /** 稳定 id，用于固定文件夹、会话范围与状态恢复；跨进程重启不变。 */
    val id: String,
    val kind: FileLocationKind,
    val title: String,
    val capabilities: FileLocationCapabilities,
    /** 仅会话工作区：所属会话 id。 */
    val sessionId: String? = null,
    /** 有 Git 仓库时的当前分支与改动数，否则为 null。 */
    val gitBranch: String? = null,
    val gitChangeCount: Int? = null,
    /** 可用空间字节数，无法查询时为 null。 */
    val freeBytes: Long? = null,
    /** 外部文件夹授权已失效，需要重新授权。 */
    val accessLost: Boolean = false,
)

/** 位置内的一个条目引用。[path] 为规范化绝对路径，根目录为 "/"。 */
data class FileRef(val locationId: String, val path: String)

enum class KnowledgeIndexState { NONE, QUEUED, INDEXING, INDEXED, FAILED, STALE, UNSUPPORTED }

/** 条目在资料（知识层）里的状态；不在资料范围内的条目为 null。 */
data class FileKnowledge(
    val state: KnowledgeIndexState,
    /** 资料库文档 id，可打开文档页。 */
    val documentId: String? = null,
    val chunkCount: Int? = null,
    /** 解析或索引失败的可读原因。 */
    val failureReason: String? = null,
)

data class FileItem(
    val ref: FileRef,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val mimeType: String? = null,
    /** 目录的直接子项数，未知为 null。 */
    val childCount: Int? = null,
    val knowledge: FileKnowledge? = null,
)

enum class FileSortKey { NAME, MODIFIED, SIZE, TYPE }

data class FileSort(
    val key: FileSortKey = FileSortKey.MODIFIED,
    val ascending: Boolean = false,
    val foldersFirst: Boolean = true,
)

enum class FileTypeFilter { DOCUMENT, IMAGE, AUDIO, VIDEO, ARCHIVE, CODE, OTHER }

data class FileSearchQuery(
    val text: String,
    /** 为 null 时搜索整个位置，否则只搜该目录及其子目录。 */
    val withinPath: String? = null,
    val types: Set<FileTypeFilter> = emptySet(),
    /** 只保留该时间之后修改的条目，毫秒；null 表示不限。 */
    val modifiedAfter: Long? = null,
)

data class FileSearchHit(val item: FileItem, val parentPath: String)

/** 文件预览来源；图片、音频、PDF 由界面用 [uri] 加载。 */
sealed interface FilePreview {
    data class Text(val content: String, val truncated: Boolean, val markdown: Boolean) : FilePreview
    data class Image(val uri: String) : FilePreview
    data class Audio(val uri: String, val durationMs: Long?) : FilePreview
    data class Video(val uri: String, val durationMs: Long?) : FilePreview
    data class Pdf(val uri: String, val pageCount: Int?) : FilePreview
    data class Archive(val entries: List<String>, val totalEntries: Int) : FilePreview
    data object Unsupported : FilePreview
}

enum class FileOpCode {
    NAME_EXISTS,
    INVALID_NAME,
    NOT_FOUND,
    READ_ONLY,
    PERMISSION_LOST,
    TOO_LARGE,
    NO_SPACE,
    UNSUPPORTED,
    CONFLICT,
    IO,
}

class FileOpException(val code: FileOpCode, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: code.name, cause)

data class FileOpFailure(val ref: FileRef, val code: FileOpCode)

/** 批量操作的结果：[succeeded] 为操作后的新位置（复制、移动）或原位置（删除）。 */
data class FileOpOutcome(
    val succeeded: List<FileRef>,
    val failed: List<FileOpFailure>,
)

data class RecycledItem(
    /** 回收站条目的不透明 id，恢复与彻底删除时原样传回。 */
    val id: String,
    val locationId: String,
    val originalPath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val deletedAt: Long,
    /** 自动清除时间；不自动清除时为 null。 */
    val expiresAt: Long?,
)

/** 可访问的位置集合。 */
interface FileLocationsSource {
    /** 资料页：全部位置（资料库、会话工作区汇总、项目、Linux 主目录、外部文件夹、分支 worktree）。 */
    fun observeLibraryLocations(): Flow<List<FileLocation>>

    /** 会话资源管理器：该会话可访问的工作区与挂载。 */
    fun observeSessionLocations(sessionId: String): Flow<List<FileLocation>>

    /** Linux 文件页：本机 Linux 主目录；环境不可用时为 null。 */
    fun observeLinuxHome(): Flow<FileLocation?>
}

/** 文件操作；所有方法在 IO 线程执行，失败抛出 [FileOpException]，批量操作按项汇总到 [FileOpOutcome]。 */
interface FileOperations {
    suspend fun list(locationId: String, path: String): List<FileItem>
    suspend fun stat(ref: FileRef): FileItem?

    /** 按文件名搜索，最多返回 [limit] 条。 */
    suspend fun search(locationId: String, query: FileSearchQuery, limit: Int = 200): List<FileSearchHit>

    suspend fun createFolder(parent: FileRef, name: String): FileItem
    suspend fun createTextFile(parent: FileRef, name: String, content: String = ""): FileItem
    suspend fun rename(ref: FileRef, newName: String): FileItem

    /** 复制到 [targetDirectory]，可跨位置；同名时自动改为“名称 (2)”。 */
    suspend fun copy(sources: List<FileRef>, targetDirectory: FileRef): FileOpOutcome

    /** 移动到 [targetDirectory]，可跨位置；同名时该项以 [FileOpCode.NAME_EXISTS] 失败。 */
    suspend fun move(sources: List<FileRef>, targetDirectory: FileRef): FileOpOutcome

    /** 移到回收站；位置不支持回收站时以 [FileOpCode.UNSUPPORTED] 失败，不做不可逆删除。 */
    suspend fun delete(sources: List<FileRef>): FileOpOutcome

    /** 在 [targetDirectory] 生成 ZIP，返回新建的压缩包。 */
    suspend fun compress(sources: List<FileRef>, targetDirectory: FileRef, archiveName: String): FileItem

    /** 解压到 [targetDirectory] 下与压缩包同名（自动避让重名）的新文件夹。 */
    suspend fun extract(archive: FileRef, targetDirectory: FileRef): FileItem

    /** 把系统选择器或分享得到的 content URI 导入 [targetDirectory]。 */
    suspend fun importUris(uris: List<String>, targetDirectory: FileRef): FileOpOutcome

    /** 把文本写成新文件（“粘贴剪贴板为文件”）。 */
    suspend fun importText(text: String, targetDirectory: FileRef, name: String): FileItem

    suspend fun preview(ref: FileRef, maxTextBytes: Int = 256 * 1024): FilePreview

    /** 供“打开方式”“分享”使用的 content URI（经 FileProvider 授权）。 */
    suspend fun shareUri(ref: FileRef): String

    fun observeRecycleBin(): Flow<List<RecycledItem>>
    suspend fun restore(ids: List<String>): FileOpOutcome
    suspend fun purge(ids: List<String>)
    suspend fun emptyRecycleBin()
}

/** 固定到资料页侧栏的文件夹，最多 [MAX_PINS] 个。 */
interface PinnedFolderStore {
    fun observePins(): Flow<List<FileRef>>

    /** 已满或已固定时返回 false。 */
    suspend fun pin(ref: FileRef): Boolean
    suspend fun unpin(ref: FileRef)

    companion object {
        const val MAX_PINS = 6
    }
}

/** 三项服务的组合入口，由应用容器提供。 */
interface FileManagerServices {
    val locations: FileLocationsSource
    val operations: FileOperations
    val pins: PinnedFolderStore
}
