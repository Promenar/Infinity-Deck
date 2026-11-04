package com.promenar.infinity.ui.files

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.promenar.infinity.domain.files.FileItem

/*
 * 资源管理器宿主的声明：页头左侧按钮、标题覆盖、宿主专属动作与可选能力。
 * 资料页、会话资源管理器与 Linux 文件页各自构造一份，组件本身不认识具体宿主。
 */

/** 页头最左侧的按钮。内屏宿主只放展开 / 还原；外屏与独立整页放标准返回键。 */
@Immutable
sealed interface FileManagerLeading {
    /** 内屏两列：“←|”展开，内容占满内屏。 */
    data class Expand(val onExpand: () -> Unit) : FileManagerLeading

    /** 内屏展开态：“|→”还原为两列。 */
    data class Restore(val onRestore: () -> Unit) : FileManagerLeading

    /** 侧栏或面板：关闭。 */
    data class Close(val onClose: () -> Unit) : FileManagerLeading

    /** 外屏与独立整页：离开这一页。 */
    data class Leave(val onLeave: () -> Unit) : FileManagerLeading

    data object None : FileManagerLeading
}

/** 内屏宿主（展开 / 还原）：单列预览页用标题下方的“‹ 文件夹名”返回列表，不放返回图标。 */
internal val FileManagerLeading.isInnerScreen: Boolean
    get() = this is FileManagerLeading.Expand || this is FileManagerLeading.Restore

/** 宿主动作适用的对象。 */
enum class FileHostActionScope { FILE, FOLDER, MULTI }

/**
 * 宿主专属的条目动作，例如资料页“加入资料 / 在对话中使用”、会话“附加到消息”、Linux“在终端运行”。
 * - [label] 由宿主给出已本地化的文字；
 * - [appliesTo] 决定出现在文件、文件夹或多选的菜单里；
 * - [prominent] 为 true 时同时作为文件预览上方的按钮，排在“打开方式”之前，第一个为主色。
 */
@Immutable
data class FileHostAction(
    val id: String,
    val icon: ImageVector,
    val label: String,
    val appliesTo: Set<FileHostActionScope>,
    val prominent: Boolean = false,
    val onInvoke: (List<FileItem>) -> Unit,
)

/** 作用于当前文件夹的宿主动作，出现在页头“更多”菜单末尾，例如 Linux“在终端中打开”。 */
@Immutable
data class FileFolderAction(
    val id: String,
    val icon: ImageVector,
    val label: String,
    /** 参数为当前位置 id 与位置内路径。 */
    val onInvoke: (locationId: String, path: String) -> Unit,
)

/**
 * 资源管理器的宿主声明。
 * - [title] 非 null 时替换页头标题（默认是当前文件夹名，根目录为位置名）；
 * - [showPin] 为 true 时“更多”菜单显示“固定到侧栏”（资料页）；
 * - [allowLocationSwitch] 为 true 时标题下方显示“位置 ▾”下拉，可在 [com.promenar.infinity.ui.files.state.FileManagerUiState.locations] 间切换（会话）；
 * - [backNavigatesUp] 为 true 时系统返回在子文件夹里先回上一级，到根目录再交给宿主；
 * - [onOpenGit] 非 null 时根目录的 Git 状态条可点，打开宿主的 Git 面板。
 */
@Immutable
data class FileManagerHostSpec(
    val leading: FileManagerLeading = FileManagerLeading.None,
    val title: String? = null,
    val hostActions: List<FileHostAction> = emptyList(),
    val folderActions: List<FileFolderAction> = emptyList(),
    val showPin: Boolean = false,
    val allowLocationSwitch: Boolean = false,
    val backNavigatesUp: Boolean = true,
    val onOpenGit: (() -> Unit)? = null,
)

/** 对 [items] 适用的宿主动作：单个文件、单个文件夹或多选。 */
internal fun FileManagerHostSpec.actionsFor(items: List<FileItem>): List<FileHostAction> {
    if (items.isEmpty()) return emptyList()
    val scope = when {
        items.size > 1 -> FileHostActionScope.MULTI
        items.first().isDirectory -> FileHostActionScope.FOLDER
        else -> FileHostActionScope.FILE
    }
    return hostActions.filter { scope in it.appliesTo }
}
