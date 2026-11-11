package com.promenar.infinity.ui.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.ui.draw.clip
import com.promenar.infinity.data.browser.BrowserDisplayMode
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.browser.BrowserActionKind
import com.promenar.infinity.data.browser.BrowserAgentAction
import com.promenar.infinity.data.browser.BrowserAvailability
import com.promenar.infinity.data.browser.BrowserHandoffReason
import com.promenar.infinity.data.browser.BrowserMode
import com.promenar.infinity.data.browser.BrowserUiState
import com.promenar.infinity.ui.common.LocalInfinityPanePresentation
import com.promenar.infinity.ui.common.InfinityPanePresentation
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forInput

/** 会话的 Agent 浏览器页：窄窗口整页、宽窗口在检视器“浏览器”页签。 */
@Composable
fun BrowserPage(sessionId: String, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val host = (context.applicationContext as InfinityApplication).agentBrowser
    val ids by host.sessionIds.collectAsState()
    val session = if (sessionId in ids) host.session(sessionId) else null
    val state = session?.control?.state?.collectAsState()?.value
    BrowserPageContent(
        state = state,
        availability = host.availability(),
        onBack = onBack,
        onTakeOver = { session?.takeOver() },
        onHandBack = { session?.handBack() },
        onReload = { session?.reload() },
        onPageBack = { session?.goBack() },
        onPageForward = { session?.goForward() },
        onUpdateWebView = { openWebViewStore(context) },
        onNavigate = { target: String -> host.openByUser(sessionId, target); Unit }.takeIf { host.availability() is BrowserAvailability.Supported },
        tabActions = BrowserTabActions(
            onSelect = { host.switchTabByUser(sessionId, it) },
            onClose = { host.closeTabByUser(sessionId, it) },
            onNew = { host.newTabByUser(sessionId) },
            onDisplayMode = { host.setDisplayModeByUser(sessionId, it) },
        ),
        modifier = browserPageModifier(),
    ) {
        session?.let { BrowserWebViewHost(it, state?.currentTabId, Modifier.fillMaxSize()) }
    }
}

/** 浏览器整页的外壳：铺满底色；整页打开时避开系统栏，在检视器里由检视器负责边距。视觉实验室的浏览器版本复用同一外壳。 */
@Composable
internal fun browserPageModifier(): Modifier = Modifier
    .fillMaxSize()
    .background(MaterialTheme.colorScheme.background)
    .then(
        if (LocalInfinityPanePresentation.current == InfinityPanePresentation.Inspector) Modifier
        else Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
    )

/** 标签栏与显示模式的用户操作；为空时不显示标签栏。 */
class BrowserTabActions(
    val onSelect: (String) -> Unit,
    val onClose: (String) -> Unit,
    val onNew: () -> Unit,
    val onDisplayMode: (BrowserDisplayMode) -> Unit,
)

/** 无状态页面：[state] 为空表示本会话还没有打开网页。网页区域由 [web] 提供，截图测试可用占位内容代替。 */
@Composable
fun BrowserPageContent(
    state: BrowserUiState?,
    availability: BrowserAvailability,
    onBack: (() -> Unit)?,
    onTakeOver: () -> Unit,
    onHandBack: () -> Unit,
    onReload: () -> Unit,
    onPageBack: () -> Unit,
    onPageForward: () -> Unit,
    onUpdateWebView: () -> Unit,
    modifier: Modifier = Modifier,
    /** 用户在地址栏前往时收到解析后的地址；为空时地址栏只显示不可编辑。 */
    onNavigate: ((String) -> Unit)? = null,
    tabActions: BrowserTabActions? = null,
    web: @Composable () -> Unit,
) {
    Column(modifier.testTag(BrowserTags.PAGE)) {
        BrowserUrlBar(state, onBack, onReload, onPageBack, onPageForward, onNavigate)
        if (state != null && tabActions != null && availability !is BrowserAvailability.Unsupported) BrowserTabStrip(state, tabActions)
        when {
            availability is BrowserAvailability.Unsupported -> BrowserUnsupported(availability.currentVersion, onUpdateWebView)
            state == null -> BrowserEmpty()
            else -> {
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (state.loading) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                val lastActive = remember { LastActiveState() }
                val barState = browserStatusBarState(state, lastActive.value).also { lastActive.value = it }
                AnimatedVisibility(visible = state.mode != BrowserMode.IDLE) {
                    barState?.let { BrowserStatusBar(it, onTakeOver, onHandBack) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { web() }
            }
        }
    }
}

/** 状态条显示的状态：转为空闲后退场动画期间沿用退场前的状态，空闲态本身没有文案。 */
internal fun browserStatusBarState(state: BrowserUiState, previous: BrowserUiState?): BrowserUiState? =
    if (state.mode != BrowserMode.IDLE) state else previous

private class LastActiveState(var value: BrowserUiState? = null)

@Composable
private fun BrowserUrlBar(
    state: BrowserUiState?,
    onBack: (() -> Unit)?,
    onReload: () -> Unit,
    onPageBack: () -> Unit,
    onPageForward: () -> Unit,
    onNavigate: ((String) -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val url = state?.url.orEmpty().takeUnless { it == "about:blank" }.orEmpty()
    var editing by remember { mutableStateOf(false) }
    BackHandler(enabled = editing) { editing = false }
    val showClose = onBack != null && !editing
    val showNav = !editing && state != null
    // 地址框高 40dp、栏高 56dp，上下各留 8dp；两侧没有按钮时同样留 8dp，与上边距一致。
    // 两侧是图标按钮时只留 4dp，按钮自带的触控留白补足视觉间距。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = if (showClose) 4.dp else BrowserBarInset, end = if (showNav || editing) 4.dp else BrowserBarInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 离开浏览器用关闭图标，和网页内的“后退”箭头区分开。
        if (showClose) {
            IconButton(onClick = onBack!!) { Icon(Icons.Rounded.Close, stringResource(R.string.browser_cd_close)) }
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = scheme.surfaceContainerHigh,
            border = if (editing) BorderStroke(1.5.dp, scheme.primary) else null,
            modifier = Modifier.weight(1f).height(40.dp),
        ) {
            if (editing && onNavigate != null) {
                BrowserAddressField(
                    initial = url,
                    onGo = { input ->
                        editing = false
                        browserAddressTarget(input)?.let(onNavigate)
                    },
                    onDismiss = { editing = false },
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (onNavigate != null) Modifier.clickable(role = Role.Button) { editing = true } else Modifier)
                        .testTag(BrowserTags.ADDRESS)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val placeholder = url.isEmpty() && onNavigate != null
                    Icon(
                        when {
                            placeholder -> Icons.Rounded.Search
                            url.startsWith("https://") -> Icons.Rounded.Lock
                            else -> Icons.Rounded.Public
                        },
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = displayUrl(url).ifEmpty {
                            stringResource(if (placeholder) R.string.browser_address_hint else R.string.browser_title)
                        },
                        style = InfinityTypography.bodyMedium,
                        color = if (url.isEmpty()) scheme.onSurfaceVariant else scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(BrowserTags.URL),
                    )
                }
            }
        }
        when {
            editing -> TextButton(onClick = { editing = false }) { Text(stringResource(R.string.browser_address_cancel)) }
            // 打开过网页后后退、前进、刷新常驻，不可用的按钮置灰；还没打开网页时都不显示。
            showNav -> {
                val navColors = IconButtonDefaults.iconButtonColors(contentColor = scheme.onSurfaceVariant)
                IconButton(onClick = onPageBack, enabled = state.canGoBack, colors = navColors) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.browser_cd_back))
                }
                IconButton(onClick = onPageForward, enabled = state.canGoForward, colors = navColors) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.browser_cd_forward))
                }
                IconButton(onClick = onReload, colors = navColors) {
                    Icon(Icons.Rounded.Refresh, stringResource(R.string.browser_cd_reload))
                }
            }
        }
    }
}

/** 地址栏两侧没有按钮时地址框到边缘的距离，等于地址框到栏顶的距离。 */
private val BrowserBarInset = 8.dp

/** 编辑中的地址框：进入时全选当前网址，键盘“前往”提交，失去焦点即退出编辑。 */
@Composable
private fun BrowserAddressField(initial: String, onGo: (String) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var value by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
        if (value.text.isEmpty()) {
            Text(stringResource(R.string.browser_address_hint), style = InfinityTypography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = InfinityTypography.bodyMedium.copy(color = scheme.onSurface).forInput(),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onGo(value.text) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { if (focused && !it.isFocused) onDismiss() else focused = it.isFocused }
                .testTag(BrowserTags.ADDRESS_INPUT),
        )
    }
}

@Composable
private fun BrowserStatusBar(state: BrowserUiState, onTakeOver: () -> Unit, onHandBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val handoff = state.mode == BrowserMode.HANDOFF
    val container = if (handoff) scheme.tertiaryContainer else scheme.surfaceContainerHigh
    val content = if (handoff) scheme.onTertiaryContainer else scheme.onSurface
    val (title, detail) = when (state.mode) {
        BrowserMode.AGENT -> stringResource(R.string.browser_mode_agent) to state.action?.let { browserActionText(it) }
        BrowserMode.USER -> stringResource(R.string.browser_mode_user) to stringResource(R.string.browser_mode_user_detail)
        else -> stringResource(handoffTitle(state.handoff ?: BrowserHandoffReason.LOGIN)) to stringResource(R.string.browser_handoff_detail)
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.Small, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(BrowserTags.STATUS),
    ) {
        if (handoff) {
            // 交接说明较长，按钮单独一行靠右，标题与说明不被挤压。
            Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = content, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                    StatusText(title, detail, content, Modifier.weight(1f))
                }
                Button(
                    onClick = onHandBack,
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.tertiary, contentColor = scheme.onTertiary),
                    modifier = Modifier.align(Alignment.End).testTag(BrowserTags.HAND_BACK),
                ) { Text(stringResource(R.string.browser_handoff_done)) }
            }
        } else {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(8.dp).background(if (state.mode == BrowserMode.AGENT) scheme.primary else scheme.outline, CircleShape))
                StatusText(title, detail, content, Modifier.weight(1f))
                if (state.mode == BrowserMode.AGENT) {
                    OutlinedButton(onClick = onTakeOver, modifier = Modifier.testTag(BrowserTags.TAKE_OVER)) {
                        Text(stringResource(R.string.browser_take_over))
                    }
                } else {
                    Button(onClick = onHandBack, modifier = Modifier.testTag(BrowserTags.HAND_BACK)) {
                        Text(stringResource(R.string.browser_hand_back))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusText(title: String, detail: String?, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = InfinityTypography.labelLarge, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!detail.isNullOrBlank()) {
            Text(detail, style = InfinityTypography.bodySmall, color = color.copy(alpha = 0.78f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun browserActionText(action: BrowserAgentAction): String = when (action.kind) {
    BrowserActionKind.OPEN -> stringResource(R.string.browser_action_open, action.label)
    BrowserActionKind.SNAPSHOT -> stringResource(R.string.browser_action_snapshot)
    BrowserActionKind.CLICK -> stringResource(R.string.browser_action_click, action.ref.orEmpty(), action.label)
    BrowserActionKind.TYPE -> stringResource(R.string.browser_action_type, action.label)
}

internal fun handoffTitle(reason: BrowserHandoffReason): Int = when (reason) {
    BrowserHandoffReason.LOGIN -> R.string.browser_handoff_login
    BrowserHandoffReason.PAYMENT -> R.string.browser_handoff_payment
    BrowserHandoffReason.VERIFICATION -> R.string.browser_handoff_verification
}

@Composable
private fun BrowserEmpty() = BrowserNotice(
    title = stringResource(R.string.browser_empty_title),
    detail = stringResource(R.string.browser_empty_detail),
)

@Composable
private fun BrowserUnsupported(version: String?, onUpdate: () -> Unit) = BrowserNotice(
    title = stringResource(R.string.browser_unsupported_title),
    detail = stringResource(R.string.browser_unsupported_detail, version ?: stringResource(R.string.browser_unsupported_missing)),
    action = stringResource(R.string.browser_update_webview) to onUpdate,
)

@Composable
private fun BrowserNotice(title: String, detail: String, action: Pair<String, () -> Unit>? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = InfinitySpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Public, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(InfinitySpacing.Medium))
        Text(title, style = InfinityTypography.titleSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(InfinitySpacing.XSmall))
        Text(detail, style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(InfinitySpacing.Medium))
            Button(onClick = onClick) { Text(label) }
        }
    }
}

/**
 * 一行标签栏：当前标签高亮并带关闭按钮，其余标签单行截断；末尾新建标签与手机/桌面切换。
 * 标签多时横向滚动，栏高固定。
 */
@Composable
private fun BrowserTabStrip(state: BrowserUiState, actions: BrowserTabActions) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(start = 8.dp, end = 8.dp).testTag(BrowserTags.TABS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(state.tabs, key = { it.id }) { tab ->
                val current = tab.id == state.currentTabId
                Row(
                    modifier = Modifier
                        .widthIn(max = 160.dp)
                        .height(32.dp)
                        .background(if (current) scheme.surfaceContainerHigh else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable(role = Role.Tab) { if (!current) actions.onSelect(tab.id) }
                        .padding(start = 10.dp, end = if (current && state.tabs.size > 1) 2.dp else 10.dp)
                        .testTag(BrowserTags.TAB),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tab.title.ifBlank { displayUrl(tab.url) }.ifBlank { stringResource(R.string.browser_tab_untitled) },
                        style = InfinityTypography.labelMedium,
                        color = when {
                            current -> scheme.onSurface
                            tab.released -> scheme.outline
                            else -> scheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (current && state.tabs.size > 1) {
                        IconButton(onClick = { actions.onClose(tab.id) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.browser_cd_close_tab), tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            item {
                IconButton(onClick = actions.onNew, modifier = Modifier.size(32.dp).testTag(BrowserTags.NEW_TAB)) {
                    Icon(Icons.Rounded.Add, stringResource(R.string.browser_cd_new_tab), tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
        BrowserDisplayModeToggle(state.displayMode, actions.onDisplayMode)
    }
}

@Composable
private fun BrowserDisplayModeToggle(mode: BrowserDisplayMode, onChange: (BrowserDisplayMode) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.padding(start = 8.dp).height(30.dp).border(1.dp, scheme.outlineVariant, CircleShape).clip(CircleShape).testTag(BrowserTags.DISPLAY_MODE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            Triple(BrowserDisplayMode.MOBILE, Icons.Rounded.PhoneAndroid, R.string.browser_dev_mobile),
            Triple(BrowserDisplayMode.DESKTOP, Icons.Rounded.Computer, R.string.browser_dev_desktop),
        ).forEach { (value, icon, label) ->
            val selected = value == mode
            Box(
                Modifier
                    .fillMaxHeight()
                    .background(if (selected) scheme.secondaryContainer else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton) { if (!selected) onChange(value) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = stringResource(label),
                    tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 把会话当前标签的 WebView 挂进一个容器；切换标签后重新挂载，离开组合时交还给会话离屏保持。
 * 页面配额已满、当前标签拿不到 WebView 时显示配额提示。
 */
@Composable
private fun BrowserWebViewHost(session: AgentBrowserSession, currentTabId: String?, modifier: Modifier) {
    if (LocalInspectionMode.current) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest))
        return
    }
    val context = LocalContext.current
    key(session, currentTabId) {
        if (session.currentWebViewOrNull() == null) {
            BrowserNotice(title = stringResource(R.string.browser_capacity_title), detail = stringResource(R.string.browser_dev_capacity))
        } else AndroidView(
            factory = { FrameLayout(it) },
            modifier = modifier,
            update = { container ->
                val page = session.currentWebViewOrNull()
                if (page != null && page.parent !== container) {
                    container.removeAllViews()
                    container.addView(session.attach(context))
                }
            },
            onRelease = { session.detach() },
        )
    }
}

/** 地址栏只显示主机与路径，不显示协议与查询参数。 */
internal fun displayUrl(url: String): String {
    if (url.isBlank() || url == "about:blank") return ""
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
    val host = uri.host ?: return url
    val path = uri.path?.takeIf { it.isNotEmpty() && it != "/" }.orEmpty()
    return host.removePrefix("www.") + path
}

private fun openWebViewStore(context: Context) {
    val pkg = runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.packageName }.getOrNull() ?: "com.google.android.webview"
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(market)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

object BrowserTags {
    const val PAGE = "browser_page"
    const val URL = "browser_url"
    const val ADDRESS = "browser_address"
    const val ADDRESS_INPUT = "browser_address_input"
    const val STATUS = "browser_status"
    const val TAKE_OVER = "browser_take_over"
    const val HAND_BACK = "browser_hand_back"
    const val TABS = "browser_tabs"
    const val TAB = "browser_tab"
    const val NEW_TAB = "browser_new_tab"
    const val DISPLAY_MODE = "browser_display_mode"
}
