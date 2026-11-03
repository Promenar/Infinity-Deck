package com.promenar.infinity.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.browser.BrowserActionKind
import com.promenar.infinity.data.browser.BrowserAgentAction
import com.promenar.infinity.data.browser.BrowserAvailability
import com.promenar.infinity.data.browser.BrowserDisplayMode
import com.promenar.infinity.data.browser.BrowserTabState
import com.promenar.infinity.data.browser.BrowserHandoffReason
import com.promenar.infinity.data.browser.BrowserMode
import com.promenar.infinity.data.browser.BrowserUiState
import com.promenar.infinity.ui.chat.BrowserStep
import com.promenar.infinity.ui.chat.BrowserStepKind
import com.promenar.infinity.ui.chat.BrowserStepRows
import com.promenar.infinity.ui.chat.ChatBrowserAccess
import com.promenar.infinity.ui.chat.LocalChatBrowser
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

// Agent 浏览器：地址栏下是标签栏与手机/桌面切换，再下是状态条，区分 Agent 操作、用户接手与交给用户登录；WebView 不可用时给出版本与更新入口。
@PreviewTest
@Preview(name = "浏览器 Agent操作中 深中411", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Composable fun browserAgentDarkChinese() = PagePreview(english = false, state = agentState(english = false))

@PreviewTest
@Preview(name = "浏览器 Agent操作中 浅英360 大字号", widthDp = 360, heightDp = 560, locale = "en", fontScale = 1.3f)
@Composable fun browserAgentLightEnglish() = PagePreview(english = true, state = agentState(english = true))

@PreviewTest
@Preview(name = "浏览器 用户接手 深中411", widthDp = 411, heightDp = 420, locale = "zh-rCN")
@Composable fun browserUserDarkChinese() = PagePreview(english = false, state = agentState(english = false).copy(mode = BrowserMode.USER))

@PreviewTest
@Preview(name = "浏览器 需要你登录 深中411", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Composable fun browserHandoffDarkChinese() = PagePreview(english = false, state = handoffState(), login = true)

@PreviewTest
@Preview(name = "浏览器 需要你登录 浅英360 大字号", widthDp = 360, heightDp = 560, locale = "en", fontScale = 1.3f)
@Composable fun browserHandoffLightEnglish() = PagePreview(english = true, state = handoffState(), login = true)

@PreviewTest
@Preview(name = "浏览器 空闲 浅中411", widthDp = 411, heightDp = 360, locale = "zh-rCN")
@Composable fun browserIdleLightChinese() = PagePreview(english = true, state = agentState(english = false).copy(mode = BrowserMode.IDLE, action = null))

@PreviewTest
@Preview(name = "浏览器 未打开网页 深中411", widthDp = 411, heightDp = 420, locale = "zh-rCN")
@Composable fun browserEmptyDarkChinese() = PagePreview(english = false, state = null)

@PreviewTest
@Preview(name = "浏览器 WebView过低 深中411", widthDp = 411, heightDp = 480, locale = "zh-rCN")
@Composable fun browserUnsupportedDarkChinese() = PagePreview(english = false, state = null, availability = BrowserAvailability.Unsupported("99.0.4844.88"))

@PreviewTest
@Preview(name = "浏览器 WebView过低 浅英360 大字号", widthDp = 360, heightDp = 520, locale = "en", fontScale = 1.3f)
@Composable fun browserUnsupportedLightEnglish() = PagePreview(english = true, state = null, availability = BrowserAvailability.Unsupported("99.0.4844.88"))

@PreviewTest
@Preview(name = "会话 浏览器步骤行 深中411", widthDp = 411, heightDp = 260, locale = "zh-rCN")
@Composable fun browserStepRowsDarkChinese() = StepRowsPreview(english = false)

@PreviewTest
@Preview(name = "会话 浏览器步骤行 浅英360 大字号", widthDp = 360, heightDp = 320, locale = "en", fontScale = 1.3f)
@Composable fun browserStepRowsLightEnglish() = StepRowsPreview(english = true)

private fun agentState(english: Boolean) = BrowserUiState(
    url = "https://www.bing.com/search?q=kotlin",
    title = "kotlin - Search",
    canGoBack = true,
    mode = BrowserMode.AGENT,
    action = BrowserAgentAction(BrowserActionKind.CLICK, "e12", if (english) "Kotlin coroutines guide" else "Kotlin 协程指南"),
    tabs = listOf(
        BrowserTabState("t1", "kotlin - Search", "https://www.bing.com/search?q=kotlin", released = false),
        BrowserTabState("t2", "Coroutines guide | Kotlin Documentation", "https://kotlinlang.org/docs/coroutines-guide.html"),
        BrowserTabState("t3", "", "http://localhost:5173/"),
    ),
    currentTabId = "t1",
    displayMode = if (english) BrowserDisplayMode.DESKTOP else BrowserDisplayMode.MOBILE,
)

private fun handoffState() = BrowserUiState(
    url = "https://github.com/login",
    title = "Sign in to GitHub",
    mode = BrowserMode.HANDOFF,
    handoff = BrowserHandoffReason.LOGIN,
)

@Composable private fun BrowserTheme(english: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (english) InfinityThemeMode.LIGHT else InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) { content() }
    }
}

@Composable private fun PagePreview(
    english: Boolean,
    state: BrowserUiState?,
    availability: BrowserAvailability = BrowserAvailability.Supported,
    login: Boolean = false,
) = BrowserTheme(english) {
    BrowserPageContent(
        state = state,
        availability = availability,
        onBack = {},
        onTakeOver = {},
        onHandBack = {},
        onReload = {},
        onPageBack = {},
        onPageForward = {},
        onUpdateWebView = {},
        modifier = Modifier.fillMaxSize(),
        onNavigate = { _: String -> }.takeIf { availability is BrowserAvailability.Supported },
        tabActions = BrowserTabActions({}, {}, {}, {}),
    ) { FakePage(login) }
}

/** 截图里的网页占位：浅色页面上的若干行与一个被标出的元素。 */
@Composable private fun FakePage(login: Boolean) {
    val accent = if (login) Color(0xFFC25E00) else Color(0xFF4548C7)
    Column(Modifier.fillMaxSize().background(Color.White).padding(20.dp)) {
        Box(Modifier.fillMaxWidth(0.55f).height(18.dp).background(Color(0xFFE3E3E8), RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(16.dp))
        repeat(2) {
            Box(Modifier.fillMaxWidth().height(40.dp).background(Color(0xFFF2F2F5), RoundedCornerShape(8.dp)))
            Spacer(Modifier.height(12.dp))
        }
        Box(Modifier.fillMaxWidth().height(40.dp).border(2.dp, accent, RoundedCornerShape(8.dp))) {
            Text(
                if (login) "仅你可输入" else "e12",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.background(accent, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        repeat(3) {
            Box(Modifier.fillMaxWidth().height(10.dp).background(Color(0xFFE9E9EE), RoundedCornerShape(3.dp)))
            Spacer(Modifier.height(8.dp))
        }
    }
}

// 打开、读取、点击、输入各一行，最后一行是挂起等待用户登录的输入调用。
@Composable private fun StepRowsPreview(english: Boolean) = BrowserTheme(english) {
    val access = ChatBrowserAccess(BrowserUiState(url = "https://github.com/login", mode = BrowserMode.HANDOFF, handoff = BrowserHandoffReason.LOGIN)) {}
    CompositionLocalProvider(LocalChatBrowser provides access) {
        BrowserStepRows(
            steps = listOf(
                BrowserStep("1", BrowserStepKind.OPEN, detail = "bing.com"),
                BrowserStep("2", BrowserStepKind.SNAPSHOT, count = 42),
                BrowserStep("3", BrowserStepKind.CLICK, detail = if (english) "Kotlin coroutines guide" else "Kotlin 协程指南"),
                BrowserStep("4", BrowserStepKind.TYPE, detail = "kotlin flow"),
                BrowserStep("5", BrowserStepKind.CLICK, detail = "e7", failed = true),
                BrowserStep("6", BrowserStepKind.TYPE, detail = "", running = true),
            ),
            fontSize = 13,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
