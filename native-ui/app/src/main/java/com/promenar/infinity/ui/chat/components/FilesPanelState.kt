package com.promenar.infinity.ui.chat.components

import com.promenar.infinity.data.local.db.entity.FileEntry

internal enum class FilesPanelUiState {
    Loading,
    Error,
    Empty,
    SearchEmpty,
    Content,
}

/** 单层文件树中的可见节点；稳定身份始终来自 [file] 的 UUID。 */
internal data class VisibleFileNode(
    val file: FileEntry,
    val depth: Int,
)

/**
 * 把树投影为单层、稳定顺序的可见节点列表。
 *
 * 默认展开前两层目录；用户覆盖和搜索强制展开都按 UUID 绑定，不受重排影响。
 * visited 同时防止异常仓库数据中的环或重复 UUID 让 UI 无限递归。
 */
internal fun projectVisibleFileNodes(
    roots: List<FileEntry>,
    childrenByParent: Map<String, List<FileEntry>>,
    expansionOverrides: Map<String, Boolean> = emptyMap(),
    forceExpandedIds: Set<String> = emptySet(),
    allowTreeExpansion: Boolean = true,
): List<VisibleFileNode> = buildList {
    if (!allowTreeExpansion) {
        roots.forEach { add(VisibleFileNode(file = it, depth = 0)) }
        return@buildList
    }
    val visited = mutableSetOf<String>()

    fun append(entries: List<FileEntry>, depth: Int) {
        entries.forEach { file ->
            if (!visited.add(file.uuid)) return@forEach
            add(VisibleFileNode(file = file, depth = depth))
            if (!file.isDirectory) return@forEach

            val expanded = isFileNodeExpanded(
                uuid = file.uuid,
                depth = depth,
                expansionOverrides = expansionOverrides,
                forceExpandedIds = forceExpandedIds,
            )
            if (expanded) append(childrenByParent[file.uuid].orEmpty(), depth + 1)
        }
    }

    append(roots, depth = 0)
}

internal fun isFileNodeExpanded(
    uuid: String,
    depth: Int,
    expansionOverrides: Map<String, Boolean>,
    forceExpandedIds: Set<String> = emptySet(),
): Boolean = uuid in forceExpandedIds || (expansionOverrides[uuid] ?: (depth < DEFAULT_EXPANDED_DEPTH))

internal fun boundedFileTreeIndentLevel(depth: Int): Int =
    depth.coerceIn(0, MAX_FILE_TREE_INDENT_LEVEL)

/**
 * 解析当前组合节点需要维持的目录订阅。
 *
 * 当前组合中的展开目录需要订阅自身；普通后代则要求其全部展开祖先持续订阅。
 * 因此 Lazy 列表回收祖先行时，只要仍有后代处于组合中，祖先订阅就不会中断；
 * 与当前组合分支无关的离屏目录不会进入结果，避免大目录列表无限持有 collector。
 */
internal fun resolveRequiredDirectorySubscriptionIds(
    visibleNodes: List<VisibleFileNode>,
    visibleNodeIds: Set<String>,
    expandedDirectoryIds: Set<String>,
): Set<String> {
    if (visibleNodeIds.isEmpty() || expandedDirectoryIds.isEmpty()) return emptySet()

    val nodesById = visibleNodes.associateBy { it.file.uuid }
    return buildSet {
        visibleNodeIds.forEach { visibleNodeId ->
            var current = nodesById[visibleNodeId] ?: return@forEach
            val visited = mutableSetOf<String>()
            while (visited.add(current.file.uuid)) {
                if (current.file.isDirectory && current.file.uuid in expandedDirectoryIds) {
                    add(current.file.uuid)
                }
                val parentUuid = current.file.parentUuid
                if (parentUuid.isNullOrBlank()) break
                current = nodesById[parentUuid] ?: break
            }
        }
    }
}

private const val DEFAULT_EXPANDED_DEPTH = 2
private const val MAX_FILE_TREE_INDENT_LEVEL = 4

internal fun resolveFilesPanelUiState(
    workspaceReady: Boolean,
    isLoading: Boolean,
    hasLoadError: Boolean,
    itemCount: Int,
    filteredItemCount: Int,
    searchQuery: String,
): FilesPanelUiState = when {
    isLoading -> FilesPanelUiState.Loading
    hasLoadError -> FilesPanelUiState.Error
    !workspaceReady -> FilesPanelUiState.Loading
    filteredItemCount > 0 -> FilesPanelUiState.Content
    searchQuery.isNotBlank() && itemCount > 0 -> FilesPanelUiState.SearchEmpty
    else -> FilesPanelUiState.Empty
}

/**
 * Android MediaStore 回收站与写入暂存在共享存储上留下的改名条目：`.trashed-<数字>-<原名>`、`.pending-<数字>-<原名>`。
 * 只用于文件面板显示过滤，不影响索引、文件工具与 Linux 文件页。
 */
private val SystemTrashNamePattern = Regex("""^\.(trashed|pending)-\d+-.+""")

internal fun isSystemTrashName(name: String): Boolean = SystemTrashNamePattern.matches(name)

/** 过滤系统回收区条目后的可见列表与被隐藏的数量。 */
internal data class SystemTrashPartition<T>(val visible: List<T>, val hiddenCount: Int)

internal fun <T> partitionSystemTrash(entries: List<T>, nameOf: (T) -> String): SystemTrashPartition<T> {
    val visible = entries.filterNot { isSystemTrashName(nameOf(it)) }
    return SystemTrashPartition(visible, entries.size - visible.size)
}

/**
 * 树形面板中当前可见层级里被隐藏的系统回收区条目总数：根层级加上每个已展开且可见目录的直接子项。
 * [hiddenByParent] 只统计已加载的目录，不触发额外读取。
 */
internal fun countHiddenSystemTrash(
    rootHidden: Int,
    hiddenByParent: Map<String, Int>,
    expandedVisibleDirectoryIds: Set<String>,
): Int = rootHidden + expandedVisibleDirectoryIds.sumOf { hiddenByParent[it] ?: 0 }

/** 已加载子项的目录才显示子项数；未加载（null）时不显示，避免为此新增读取。 */
internal fun loadedChildCount(directoryUuid: String, childrenByParent: Map<String, List<*>>): Int? =
    childrenByParent[directoryUuid]?.size

/** 文件批量操作的稳定结果；底层异常不得进入 UI 文案。 */
data class FileBatchOperationResult(
    val attemptedIds: List<String>,
    val failedIds: List<String> = emptyList(),
) {
    val succeededIds: Set<String> = attemptedIds.toSet() - failedIds.toSet()
    val allSucceeded: Boolean = attemptedIds.isNotEmpty() && failedIds.isEmpty()
}

/** 成功项退出选择，失败项继续保留，原本未参与操作的选择不受影响。 */
internal fun reduceSelectionAfterFileOperation(
    selectedIds: MutableList<String>,
    result: FileBatchOperationResult,
) {
    selectedIds.removeAll(result.succeededIds)
    result.failedIds.forEach { failedId ->
        if (failedId !in selectedIds) selectedIds.add(failedId)
    }
}

/**
 * 从搜索命中项向上追溯到工作区根，得到需要渲染的精确祖先链。
 * lookup 是 suspend 契约，便于直接复用仓库且保持取消传播。
 */
internal suspend fun resolveSearchVisibleIds(
    matches: List<FileEntry>,
    workspaceRootUuid: String,
    lookup: suspend (String) -> FileEntry?,
): Set<String> = buildSet {
    matches.forEach { match ->
        var current: FileEntry? = match
        val visited = mutableSetOf<String>()
        while (current != null && current.uuid != workspaceRootUuid && visited.add(current.uuid)) {
            add(current.uuid)
            val parentUuid = current.parentUuid
            current = if (parentUuid.isNullOrBlank() || parentUuid == workspaceRootUuid) {
                null
            } else {
                lookup(parentUuid)
            }
        }
    }
}
