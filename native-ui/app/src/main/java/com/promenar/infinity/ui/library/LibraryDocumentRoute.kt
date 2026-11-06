package com.promenar.infinity.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.ui.AppTab
import com.promenar.infinity.ui.MainTabsState
import com.promenar.infinity.ui.rag.RagViewModel
import java.util.concurrent.ConcurrentHashMap

/*
 * 从对话“本轮用到的资料”打开资料文档：整页显示文档页，并定位到被引用的段落。
 * 分块原文可能很长，不放进路由参数，经进程内的交接表传给文档页；进程重建后只显示全文。
 */

/** 打开资料文档；为 null 时（例如预览）引用行不可点。 */
val LocalOpenLibraryDocument = staticCompositionLocalOf<((documentId: String, chunkContent: String?) -> Unit)?> { null }

internal object LibraryLocateHandoff {
    private val pending = ConcurrentHashMap<String, DocumentLocateRequest>()

    fun put(documentId: String, chunkContent: String?) {
        if (chunkContent.isNullOrBlank()) {
            pending.remove(documentId)
        } else {
            pending[documentId] = DocumentLocateRequest(documentId, listOf(DocumentLocateRequest.LocateChunk(chunkContent, emptyList())), fromAsk = false)
        }
    }

    fun peek(documentId: String): DocumentLocateRequest? = pending[documentId]
}

/** 整页文档：返回回到对话；页内编辑与打开会话沿用资料页的去向。 */
@Composable
fun LibraryDocumentRoute(
    documentId: String,
    onBack: () -> Unit,
    onNavigateToSecondary: (String) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(app))
    val ragViewModel: RagViewModel = viewModel(factory = RagViewModel.factory(app))
    val askViewModel: LibraryAskViewModel = viewModel(factory = LibraryAskViewModel.factory(app))
    // 独立的页面层级：只含这篇文档，不影响资料页签的层级。
    val state = remember(documentId) {
        MainTabsState(AppTab.LIBRARY, emptyList(), "", libraryStack = listOf(LibraryPageKeys.document(documentId)))
    }
    val navigator = remember(state) { LibraryNavigator(state, twoPane = false) }
    val request = remember(documentId) { LibraryLocateHandoff.peek(documentId) }
    val host = LibraryPageHost(
        viewModel = viewModel,
        workspaceRootUuid = ragViewModel.workspaceRootUuid,
        askViewModel = askViewModel,
        navigator = navigator,
        askQuery = "",
        ask = remember { LibraryAsk(onQueryChange = {}, onSearch = {}) },
        onNavigateToSecondary = onNavigateToSecondary,
        onOpenSession = onOpenSession,
        chunkLocator = com.promenar.infinity.data.library.ask.DefaultChunkLocator,
        locateRequest = request,
    )
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LibraryPageContent(
            page = LibraryPage.Document(documentId),
            host = host,
            leading = LibraryLeading.Back(onBack = onBack),
            trail = null,
        )
    }
}
