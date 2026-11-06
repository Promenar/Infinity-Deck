package com.promenar.infinity.ui.library

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.domain.library.ChunkLocator
import com.promenar.infinity.navigation.InfinityPageStack
import com.promenar.infinity.ui.common.FocusDismissArea
import com.promenar.infinity.ui.MainTabsState
import com.promenar.infinity.ui.hub.HubListPaneWidth
import com.promenar.infinity.ui.hub.HubStripWidth
import com.promenar.infinity.ui.rag.RagViewModel

/** 资料页的测试标签（与 UiTags 分开，避免与资源管理器组件的标签段冲突）。 */
object LibraryTags {
    const val SCREEN = "library_screen"
    const val NAV_COLUMN = "library_nav_column"
    const val STRIP = "library_strip"
    const val ASK_FIELD = "library_ask_field"
    const val UPLINK = "library_uplink"
    const val EXPAND = "library_expand"
    const val COLLECTION_STRIP = "library_collection_strip"
    const val COLLECTION_STRIP_SETTINGS = "library_collection_strip_settings"
    const val COLLECTION_STRIP_EXTRACT = "library_collection_strip_extract"
    fun navItem(key: String) = "library_nav_$key"
    fun page(key: String) = "library_page_$key"
}

/** 资料页的布局：外屏与窄窗口单页栈；内屏两列，可展开。 */
sealed interface LibraryLayout {
    data object Single : LibraryLayout

    /** [railWidth] 为导航轨（含安全区）宽度；[expansion] 0 为两列，1 为展开，由主标签页与导航轨共用。 */
    class TwoPane(val railWidth: Dp, val expansion: Animatable<Float, AnimationVector1D>) : LibraryLayout
}

/** 资料页的页面层级操作：内屏两列时根页面由左列决定，页内推入；外屏单列时空栈即资料首页。 */
@Stable
internal class LibraryNavigator(private val state: MainTabsState, val twoPane: Boolean) {
    val stack: List<String> get() = if (twoPane) twoPaneStack(state.libraryStack) else state.libraryStack
    val expanded: Boolean get() = twoPane && state.libraryExpanded

    fun openRoot(key: String) = state.openLibraryRoot(key)

    fun push(key: String) {
        if (twoPane && state.libraryStack.isEmpty()) state.openLibraryRoot(LibraryPageKeys.OVERVIEW)
        state.pushLibrary(key)
    }

    /** 打开左列级别的分区：内屏替换根页面（左列高亮随之变化），外屏在首页上推入。 */
    fun openSection(key: String) = if (twoPane) openRoot(key) else push(key)

    fun back(): Boolean = state.popLibrary(keepRoot = twoPane)

    fun dropAsk() = state.dropLibraryFrom(LibraryPageKeys.ASK)

    fun toggleExpanded() = state.toggleLibraryExpanded()

    /** 系统返回：先弹出页内层级，再还原两列；都不需要时交给页签级处理。 */
    val handlesBack: Boolean get() = (if (twoPane) state.libraryStack.size > 1 else state.libraryStack.isNotEmpty()) || expanded

    fun handleBack() {
        if (!back()) state.collapseLibrary()
    }
}

@Composable
fun LibraryScreen(
    state: MainTabsState,
    layout: LibraryLayout,
    onNavigateToSecondary: (String) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(app))
    val ragViewModel: RagViewModel = viewModel(factory = RagViewModel.factory(app))
    val twoPane = layout is LibraryLayout.TwoPane
    val navigator = remember(state, twoPane) { LibraryNavigator(state, twoPane) }
    val askViewModel: LibraryAskViewModel = viewModel(factory = LibraryAskViewModel.factory(app))
    val askState by askViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // 外屏的“文件”中转页在两列里没有对应导航项；展开折叠屏时去掉它，左列才能高亮当前位置。
    LaunchedEffect(twoPane) { if (twoPane) state.normalizeLibraryForTwoPane() }

    // 问资料：有查询时压上结果页；结果页被弹出（返回或“‹ 上一页”）即清空查询与结果。
    val askInStack = LibraryPageKeys.ASK in navigator.stack
    val ask = remember(askViewModel, navigator) {
        val focusOnEnter = mutableStateOf(false)
        LibraryAsk(
            onQueryChange = { text ->
                askViewModel.onQueryChange(text)
                when (libraryAskNavigation(navigator.twoPane, text, LibraryPageKeys.ASK in navigator.stack)) {
                    LibraryAskNavigation.OPEN -> navigator.push(LibraryPageKeys.ASK)
                    LibraryAskNavigation.CLOSE -> navigator.dropAsk()
                    LibraryAskNavigation.STAY -> Unit
                }
            },
            onSearch = {
                if (askViewModel.state.value.query.isNotBlank()) {
                    if (LibraryPageKeys.ASK !in navigator.stack) navigator.push(LibraryPageKeys.ASK)
                    askViewModel.submit()
                }
            },
            onOpen = {
                if (!navigator.twoPane && LibraryPageKeys.ASK !in navigator.stack) {
                    focusOnEnter.value = true
                    navigator.push(LibraryPageKeys.ASK)
                }
            },
            focusOnEnter = focusOnEnter,
        )
    }
    LaunchedEffect(askInStack) {
        if (!askInStack && askViewModel.state.value.query.isNotEmpty()) askViewModel.clear()
    }

    BackHandler(enabled = navigator.handlesBack) { navigator.handleBack() }
    LibraryNoticeEffect(viewModel, snackbar)
    LibraryGraphConfirmDialog(viewModel)

    var mountsOpen by rememberSaveable { mutableStateOf(false) }
    val host = LibraryPageHost(
        viewModel = viewModel,
        ragViewModel = ragViewModel,
        navigator = navigator,
        askViewModel = askViewModel,
        askQuery = askState.query,
        ask = ask,
        onNavigateToSecondary = onNavigateToSecondary,
        onOpenSession = onOpenSession,
        chunkLocator = com.promenar.infinity.data.library.ask.DefaultChunkLocator,
        onManageMounts = { mountsOpen = true },
    )
    if (mountsOpen) LibraryMountsSheet(onDismiss = { mountsOpen = false })

    // Surface 提供内容色：点按反馈取内容色绘制，裸 Box 下默认黑色，在深色主题里看不见。
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag(LibraryTags.SCREEN)) {
        FocusDismissArea(Modifier.fillMaxSize()) { area ->
            Box(area) {
                when (layout) {
                    LibraryLayout.Single -> LibrarySingleLayout(host)
                    is LibraryLayout.TwoPane -> LibraryTwoPaneLayout(host, layout)
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
        }
    }
}

/**
 * 问资料输入的回调集合，左列、外屏首页与结果页共用。
 * [onOpen] 在外屏首页输入框获得焦点时进入结果页；[focusOnEnter] 让结果页的输入框接过焦点，键盘不收起。
 */
@Stable
internal class LibraryAsk(
    val onQueryChange: (String) -> Unit,
    val onSearch: () -> Unit,
    val onOpen: () -> Unit = {},
    val focusOnEnter: MutableState<Boolean> = mutableStateOf(false),
)

internal enum class LibraryAskNavigation { OPEN, CLOSE, STAY }

/**
 * 输入变化时结果页是否开合。两列时输入框常驻左列，结果页随输入在右列开合；
 * 外屏的输入框就在结果页里，输入期间换页会让输入框离开组合、键盘收起并打断输入法组字，
 * 所以外屏只在输入框获得焦点时进入结果页，清空文字也留在结果页，返回才离开。
 */
internal fun libraryAskNavigation(twoPane: Boolean, text: String, askInStack: Boolean): LibraryAskNavigation = when {
    !twoPane -> LibraryAskNavigation.STAY
    text.isNotBlank() && !askInStack -> LibraryAskNavigation.OPEN
    text.isBlank() && askInStack -> LibraryAskNavigation.CLOSE
    else -> LibraryAskNavigation.STAY
}

/** 各页面需要的依赖与回调。 */
@Stable
internal class LibraryPageHost(
    val viewModel: LibraryViewModel,
    val ragViewModel: RagViewModel,
    val askViewModel: LibraryAskViewModel,
    val navigator: LibraryNavigator,
    val askQuery: String,
    val ask: LibraryAsk,
    val onNavigateToSecondary: (String) -> Unit,
    val onOpenSession: (String) -> Unit,
    /** 在正文中定位分块；来自数据层的默认实现。 */
    val chunkLocator: ChunkLocator,
    /** 从对话引用打开的文档页要定位的段落；资料页内导航为 null。 */
    val locateRequest: DocumentLocateRequest? = null,
    /** 打开资料页级外部文件夹的管理面板。 */
    val onManageMounts: () -> Unit = {},
)

@Composable
private fun LibrarySingleLayout(host: LibraryPageHost) {
    val stack = host.navigator.stack
    // 与设置页、首页的二级页面相同的视差推入；空栈即资料首页。
    InfinityPageStack(
        pages = stack,
        popped = stack.takeIf { it.isNotEmpty() }?.dropLast(1),
        onPop = { host.navigator.back() },
        modifier = Modifier.fillMaxSize(),
    ) { pages ->
        val key = pages.lastOrNull()
        if (key == null) {
            LibraryHome(host)
        } else {
            LibraryPageContent(
                page = LibraryPageKeys.parse(key),
                host = host,
                leading = LibraryLeading.Back(onBack = { host.navigator.back() }),
                uplink = null,
            )
        }
    }
}

@Composable
private fun LibraryTwoPaneLayout(host: LibraryPageHost, layout: LibraryLayout.TwoPane) {
    val navigator = host.navigator
    val expansion = layout.expansion
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowWidth = maxWidth
        val listStart = layout.railWidth
        val twoPaneContentStart = layout.railWidth + HubListPaneWidth
        val shift = twoPaneContentStart - HubStripWidth

        // 左列：随导航轨一起左移推出，展开后不再参与布局与无障碍。
        if (expansion.value < 1f) {
            Box(
                Modifier
                    .width(HubListPaneWidth)
                    .fillMaxHeight()
                    .offset { IntOffset((listStart - shift * expansion.value).roundToPx(), 0) }
                    .graphicsLayer { alpha = 1f - expansion.value },
            ) {
                LibraryNavColumn(host)
            }
        }
        // 展开后的图标条：直接切换分区并保持展开。
        if (expansion.value > 0f) {
            Box(
                Modifier
                    .width(HubStripWidth)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = expansion.value },
            ) {
                LibraryStrip(host)
            }
        }
        val contentStart = lerp(twoPaneContentStart.value, HubStripWidth.value, expansion.value).dp
        Box(
            Modifier
                .offset { IntOffset(contentStart.roundToPx(), 0) }
                .width((windowWidth - contentStart).coerceAtLeast(0.dp))
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background),
        ) {
            val stack = navigator.stack
            // 右列内前进、后退用视差推入，左列切换分区时同级叠化，与设置页两列一致。
            InfinityPageStack(
                pages = stack,
                popped = stack.takeIf { it.size > 1 }?.dropLast(1),
                onPop = { navigator.back() },
                modifier = Modifier.fillMaxSize(),
            ) { pages ->
                if (pages.isEmpty()) return@InfinityPageStack
                val previous = pages.getOrNull(pages.lastIndex - 1)
                LibraryPageContent(
                    page = LibraryPageKeys.parse(pages.last()),
                    host = host,
                    leading = LibraryLeading.Expand(expanded = navigator.expanded, onToggle = navigator::toggleExpanded),
                    uplink = previous?.let { key -> LibraryUplink(key, onClick = { navigator.back() }) },
                )
            }
        }
    }
}

/** 页头最左侧：内屏为展开 / 还原，外屏为离开本页。 */
internal sealed interface LibraryLeading {
    data class Expand(val expanded: Boolean, val onToggle: () -> Unit) : LibraryLeading
    data class Back(val onBack: () -> Unit) : LibraryLeading
}

/** 标题下方“‹ 上一页名称”：[key] 为上一页的键。 */
internal data class LibraryUplink(val key: String, val onClick: () -> Unit)

/** 抽取知识图谱前的用量确认：模型、窗口数、调用次数与输出上限。 */
@Composable
private fun LibraryGraphConfirmDialog(viewModel: LibraryViewModel) {
    val request by viewModel.graphRequest.collectAsStateWithLifecycle()
    val current = request ?: return
    val estimate = current.estimate
    val model = estimate.modelDisplayName ?: stringResource(R.string.library_graph_confirm_model_unknown)
    InfinityConfirmDialog(
        title = stringResource(R.string.library_graph_confirm_title),
        message = stringResource(
            R.string.library_graph_confirm_message,
            model,
            estimate.chunks,
            estimate.calls,
            java.text.NumberFormat.getIntegerInstance().format(estimate.maxOutputTokens),
        ),
        confirmText = stringResource(R.string.library_graph_confirm_start),
        onConfirm = viewModel::confirmGraphExtraction,
        onCancel = viewModel::dismissGraphExtraction,
    )
}

@Composable
private fun LibraryNoticeEffect(viewModel: LibraryViewModel, snackbar: SnackbarHostState) {
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val doneTexts = mapOf(
        LibraryNotice.Kind.RETRY to stringResource(R.string.library_notice_retry_done),
        LibraryNotice.Kind.REBUILD to stringResource(R.string.library_notice_rebuild_done),
        LibraryNotice.Kind.GRAPH to stringResource(R.string.library_notice_graph_done),
        LibraryNotice.Kind.IMPORT to stringResource(R.string.library_notice_import_done),
        LibraryNotice.Kind.NOTE to stringResource(R.string.library_notice_note_done),
        LibraryNotice.Kind.CLEAR_FRAGMENTS to stringResource(R.string.library_notice_clear_done),
    )
    val failedTexts = mapOf(
        LibraryNotice.Kind.RETRY to stringResource(R.string.library_notice_retry_failed),
        LibraryNotice.Kind.REBUILD to stringResource(R.string.library_notice_rebuild_failed),
        LibraryNotice.Kind.GRAPH to stringResource(R.string.library_notice_graph_failed),
        LibraryNotice.Kind.IMPORT to stringResource(R.string.library_notice_import_failed),
        LibraryNotice.Kind.NOTE to stringResource(R.string.library_notice_note_failed),
        LibraryNotice.Kind.CLEAR_FRAGMENTS to stringResource(R.string.library_notice_clear_failed),
        LibraryNotice.Kind.DELETE_FRAGMENT to stringResource(R.string.library_notice_delete_failed),
    )
    val withReason = stringResource(R.string.library_notice_with_reason)
    LaunchedEffect(notice) {
        val current = notice ?: return@LaunchedEffect
        viewModel.consumeNotice()
        val text = when (current) {
            is LibraryNotice.ActionDone -> doneTexts[current.kind]
            is LibraryNotice.ActionFailed -> {
                val base = failedTexts[current.kind]
                val reason = current.reason?.takeIf { it.isNotBlank() }
                if (base != null && reason != null) withReason.format(base, reason) else base
            }
        } ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
    }
}
