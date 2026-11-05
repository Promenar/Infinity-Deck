package com.promenar.infinity.ui.library

import com.promenar.infinity.domain.files.FileRef

/*
 * 资料页的页面模型。页面层级以字符串键保存在 MainTabsState.libraryStack，便于跨开合屏与进程重建保存；
 * 内屏右列与外屏单页共用同一组键。
 */

object LibraryPageKeys {
    const val OVERVIEW = "overview"
    const val RECENT = "recent"
    const val PENDING = "pending"

    /** 智能体常用：按被回答引用的次数排列的文档。 */
    const val CITED = "cited"
    const val MEMORY = "memory"
    const val FRAGMENTS = "frag"
    const val GRAPH = "graph"

    /** 外屏“文件”页：列出位置与固定的文件夹。 */
    const val FILES = "files"

    /** 问资料结果，压在当前页上；弹出即清空查询。 */
    const val ASK = "ask"

    private const val FRAGMENT_SESSION_PREFIX = "frag:"
    private const val DOCUMENT_PREFIX = "doc:"
    private const val BROWSE_PREFIX = "browse:"

    fun fragmentSession(sessionId: String): String = FRAGMENT_SESSION_PREFIX + sessionId
    fun document(documentId: String): String = DOCUMENT_PREFIX + documentId

    /** 位置 id 不含“|”（见 files-core 的位置 id 规则），路径放在“|”之后。 */
    fun browse(ref: FileRef): String = BROWSE_PREFIX + ref.locationId + "|" + ref.path

    fun parse(key: String): LibraryPage = when {
        key == OVERVIEW -> LibraryPage.Overview
        key == RECENT -> LibraryPage.Recent
        key == PENDING -> LibraryPage.Pending
        key == CITED -> LibraryPage.Cited
        key == MEMORY -> LibraryPage.Memory
        key == FRAGMENTS -> LibraryPage.Fragments
        key == GRAPH -> LibraryPage.Graph
        key == FILES -> LibraryPage.Files
        key == ASK -> LibraryPage.Ask
        key.startsWith(FRAGMENT_SESSION_PREFIX) -> LibraryPage.FragmentSession(key.removePrefix(FRAGMENT_SESSION_PREFIX))
        key.startsWith(DOCUMENT_PREFIX) -> LibraryPage.Document(key.removePrefix(DOCUMENT_PREFIX))
        key.startsWith(BROWSE_PREFIX) -> {
            val body = key.removePrefix(BROWSE_PREFIX)
            val split = body.indexOf('|')
            if (split <= 0) {
                LibraryPage.Overview
            } else {
                LibraryPage.Browse(FileRef(body.substring(0, split), body.substring(split + 1).ifEmpty { "/" }))
            }
        }
        else -> LibraryPage.Overview
    }
}

sealed interface LibraryPage {
    data object Overview : LibraryPage
    data object Recent : LibraryPage
    data object Pending : LibraryPage
    data object Cited : LibraryPage
    data object Memory : LibraryPage
    data object Fragments : LibraryPage
    data class FragmentSession(val sessionId: String) : LibraryPage
    data object Graph : LibraryPage
    data object Files : LibraryPage
    data object Ask : LibraryPage
    data class Document(val documentId: String) : LibraryPage
    data class Browse(val ref: FileRef) : LibraryPage
}

/** 内屏右列实际显示的层级：空栈显示概览。 */
internal fun twoPaneStack(stack: List<String>): List<String> = stack.ifEmpty { listOf(LibraryPageKeys.OVERVIEW) }

/** 左列高亮的根页面键；浏览页按位置高亮（固定的文件夹按完整路径）。 */
internal fun libraryRootKey(stack: List<String>): String = twoPaneStack(stack).first()

/** 页面是否自带页头。 */
internal fun LibraryPage.ownsHeader(): Boolean = this is LibraryPage.Browse
