package com.promenar.infinity.ui.lab.browser

import android.content.res.AssetManager
import android.util.Base64
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.promenar.infinity.R
import com.promenar.infinity.data.browser.BrowserActionKind
import com.promenar.infinity.data.browser.BrowserAgentAction
import com.promenar.infinity.data.browser.BrowserAvailability
import com.promenar.infinity.data.browser.BrowserHandoffReason
import com.promenar.infinity.data.browser.BrowserMode
import com.promenar.infinity.data.browser.BrowserTabState
import com.promenar.infinity.data.browser.BrowserUiState
import com.promenar.infinity.ui.browser.BrowserPageContent
import com.promenar.infinity.ui.browser.BrowserTabActions
import com.promenar.infinity.ui.browser.browserPageModifier
import com.promenar.infinity.ui.lab.stage.LabScene
import com.promenar.infinity.ui.lab.stage.LabSceneScope
import com.promenar.infinity.ui.lab.stage.LabStateScene
import com.promenar.infinity.ui.theme.InfinityBrowserAgentHighlight
import com.promenar.infinity.ui.theme.InfinityBrowserHandoffHighlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 浏览器目录下的版本：Agent 在 starlog 的 CI 页面上重跑失败的作业、用户接手，以及登录页交给用户输入密码。
 * 网页是内置的虚构页面（保留域名 .example），由实验室自建的 WebView 载入，不访问网络；
 * 目标元素的轮廓与角标沿用真实浏览器注入脚本的样式与颜色。
 */
internal val labBrowserScenes: Map<String, () -> LabScene> = mapOf(
    "browser.state.agent" to {
        LabStateScene { LabBrowserPage(runsState(BrowserMode.AGENT), RUNS_URL) { runsHtml(it, highlight = AGENT_REF) } }
    },
    "browser.state.takeover" to {
        LabStateScene { LabBrowserPage(runsState(BrowserMode.USER), RUNS_URL) { runsHtml(it, highlight = null) } }
    },
    // starlog 周刊第 12 期发布后的页面：满宽封面与正文，可下滑一屏。
    "browser.state.issue-12" to {
        LabStateScene {
            val context = LocalContext.current
            val zh = LocalConfiguration.current.locales[0].language == "zh"
            val assets = context.assets
            val issue = remember(zh) { IssuePage(zh) }
            LabBrowserPage(issueState(issue), ISSUE_URL, styleAsset = SITE_STYLE_ASSET) { style ->
                issueHtml(style, issue, coverDataUri(assets))
            }
        }
    },
    "browser.state.login" to {
        LabStateScene {
            val badge = stringResource(R.string.browser_handoff_field_badge)
            LabBrowserPage(loginState(), LOGIN_URL) { loginHtml(it, badge) }
        }
    },
)

private const val RUNS_URL = "https://ci.starlog.example/starlog/runs/482"
private const val RUNS_TITLE = "Run #482 · starlog CI"
private const val LOGIN_URL = "https://ci.starlog.example/login?next=%2Fstarlog%2Fruns%2F482"
private const val AGENT_REF = "e12"
private const val RERUN_LABEL = "Re-run failed jobs"

private val runsTabs = listOf(
    BrowserTabState("lab-tab-runs", RUNS_TITLE, RUNS_URL, released = false),
    BrowserTabState("lab-tab-release", "Release v0.4.0 · starlog", "https://code.starlog.example/starlog/releases/tag/v0.4.0"),
    BrowserTabState("lab-tab-docs", "", "http://localhost:8000/"),
)

private fun runsState(mode: BrowserMode) = BrowserUiState(
    url = RUNS_URL,
    title = RUNS_TITLE,
    canGoBack = true,
    mode = mode,
    action = BrowserAgentAction(BrowserActionKind.CLICK, AGENT_REF, RERUN_LABEL).takeIf { mode == BrowserMode.AGENT },
    tabs = runsTabs,
    currentTabId = runsTabs.first().id,
)

private fun loginState() = BrowserUiState(
    url = LOGIN_URL,
    title = "Sign in · starlog CI",
    canGoBack = true,
    mode = BrowserMode.HANDOFF,
    action = BrowserAgentAction(BrowserActionKind.TYPE, "e4", "Password"),
    handoff = BrowserHandoffReason.LOGIN,
    tabs = listOf(BrowserTabState("lab-tab-runs", "Sign in · starlog CI", LOGIN_URL, released = false)) + runsTabs.drop(1),
    currentTabId = "lab-tab-runs",
)

/** 套用真实浏览器页的外壳 [browserPageModifier]；标签栏与地址栏的操作都是空操作，返回键接到版本页出口。 */
@Composable
private fun LabSceneScope.LabBrowserPage(
    state: BrowserUiState,
    baseUrl: String,
    styleAsset: String = PAGE_STYLE_ASSET,
    html: (style: String) -> String,
) {
    var current by remember { mutableStateOf(state) }
    BrowserPageContent(
        state = current,
        availability = BrowserAvailability.Supported,
        onBack = onExit,
        onTakeOver = { current = current.copy(mode = BrowserMode.USER, action = null) },
        onHandBack = { current = current.copy(mode = BrowserMode.IDLE, action = null, handoff = null) },
        onReload = {},
        onPageBack = {},
        onPageForward = {},
        onUpdateWebView = {},
        modifier = browserPageModifier(),
        onNavigate = { _: String -> },
        tabActions = BrowserTabActions(onSelect = {}, onClose = {}, onNew = {}, onDisplayMode = {}),
    ) {
        val assets = LocalContext.current.assets
        val page by produceState<String?>(null) {
            value = withContext(Dispatchers.IO) { html(assets.open(styleAsset).bufferedReader().use { it.readText() }) }
        }
        page?.let { LabWebView(baseUrl, it) }
    }
}

/** 只载入内置 HTML 的 WebView：不开 JavaScript，内容不变时不重复载入。 */
@Composable
private fun LabWebView(baseUrl: String, html: String) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                setBackgroundColor(android.graphics.Color.WHITE)
            }
        },
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun Color.css(): String = "#%06X".format(0xFFFFFF and toArgb())

/** 演示网页的样式表放在 assets：模拟第三方站点的固定配色，不跟随应用主题。 */
private const val PAGE_STYLE_ASSET = "lab/starlog-ci.css"

private fun page(style: String, title: String, body: String) = """
    <!doctype html><html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title>$title</title><style>$style</style></head>
    <body><header><span class="logo"></span><span class="brand">starlog CI</span></header>$body</body></html>
""".trimIndent()

private fun runsHtml(style: String, highlight: String?): String {
    val color = InfinityBrowserAgentHighlight.css()
    val rerun = if (highlight != null) {
        """<span class="hl" style="--hl:$color"><span class="btn primary">$RERUN_LABEL</span><span class="badge">$highlight</span></span>"""
    } else {
        """<span class="btn primary">$RERUN_LABEL</span>"""
    }
    return page(
        style,
        RUNS_TITLE,
        """
        <main>
          <h1>Run #482</h1>
          <div class="meta">main · a3f9c1e Add --json output to the starlog CLI · 3 jobs</div>
          <div class="job"><span class="dot ok"></span><span class="name">lint</span><span class="time">0:18</span></div>
          <div class="job"><span class="dot ok"></span><span class="name">test (3.12)</span><span class="time">1:02</span></div>
          <div class="job"><span class="dot fail"></span><span class="name">test (3.13)</span><span class="time">0:41</span></div>
          <div class="log">FAIL: test_json_large_file (tests.test_main.JsonOutputTest)
TimeoutError: runner lost connection after 40s</div>
          <div class="row">$rerun<span class="btn">Download logs</span></div>
        </main>
        """.trimIndent(),
    )
}

private fun loginHtml(style: String, badge: String): String {
    val color = InfinityBrowserHandoffHighlight.css()
    return page(
        style,
        "Sign in · starlog CI",
        """
        <div class="card">
          <h1>Sign in</h1>
          <div class="meta">Continue to starlog / runs / 482</div>
          <label>Username</label>
          <div class="field">demo-maintainer</div>
          <label>Password</label>
          <div class="hl" style="--hl:$color"><div class="field"></div><span class="badge">$badge</span></div>
          <div style="margin-top:20px"><span class="btn primary" style="display:block;text-align:center">Sign in</span></div>
        </div>
        """.trimIndent(),
    )
}

// ── starlog 周刊第 12 期 ──

private const val ISSUE_URL = "http://web-01:8080/issues/12/"
private const val SITE_STYLE_ASSET = "lab/starlog-site.css"
private const val COVER_ASSET = "lab/issue-12-cover.jpg"

/** 周刊页面的文案；星等与距离取公认数据，与会话里核对引用的结论一致。 */
private class IssuePage(zh: Boolean) {
    val lang = if (zh) "zh-CN" else "en"
    val title = if (zh) "第 12 期：四颗亮星" else "Issue 12: Four bright stars"
    val tabTitle = "$title · starlog" // wrap-check: allow 模拟网页的 HTML 标题，沿用站点“标题 · 站名”写法
    val nav = if (zh) listOf("周刊", "关于") else listOf("Weekly", "About")
    val kicker = if (zh) "starlog 周刊" else "starlog weekly"
    val meta = if (zh) "2026 年 11 月 9 日 · 阅读约 4 分钟" else "November 9, 2026 · 4 min read"
    val coverAlt = if (zh) "星空插画：银河与四颗亮星" else "Illustration of the night sky with the Milky Way and four bright stars"
    val intro = if (zh) {
        "十一月夜里十点前后，织女星还低悬在西北天空，天狼星已经从东南方升起。这一期挑出全天最亮的四颗恒星：天狼星、老人星、大角星和织女星，看看它们为什么这么亮，以及这个冬天去哪里找它们。"
    } else {
        "Around ten on a November night, Vega still hangs low in the northwest while Sirius is already up in the southeast. This issue picks the four brightest stars in the sky, Sirius, Canopus, Arcturus and Vega, and looks at why they shine so brightly and where to find them this winter."
    }
    val second = if (zh) {
        "视星等越小越亮。天狼星以 −1.46 等居首，它本身并不比太阳大多少，只是离我们很近，约 8.6 光年。老人星以 −0.74 等排第二，却远在约 310 光年之外，真实光度是太阳的一万倍以上。"
    } else {
        "The smaller the apparent magnitude, the brighter the star. Sirius leads at −1.46. It is not much bigger than the Sun, just close, about 8.6 light-years away. Canopus comes second at −0.74 from about 310 light-years, which makes it more than ten thousand times as luminous as the Sun."
    }
    val tableTitle = if (zh) "四颗亮星" else "The four stars"
    val columns = if (zh) listOf("星名", "视星等", "距离") else listOf("Star", "Magnitude", "Distance")
    val rows = if (zh) {
        listOf(
            Triple("天狼星", "−1.46", "8.6 光年"),
            Triple("老人星", "−0.74", "约 310 光年"),
            Triple("大角星", "−0.05", "36.7 光年"),
            Triple("织女星", "+0.03", "25 光年"),
        )
    } else {
        listOf(
            Triple("Sirius", "−1.46", "8.6 ly"),
            Triple("Canopus", "−0.74", "about 310 ly"),
            Triple("Arcturus", "−0.05", "36.7 ly"),
            Triple("Vega", "+0.03", "25 ly"),
        )
    }
    val third = if (zh) {
        "大角星是一颗橙色巨星，也是北天最亮的恒星；织女星曾是测光的零点参照，视星等接近 0。"
    } else {
        "Arcturus is an orange giant and the brightest star in the northern celestial hemisphere. Vega once served as the zero point of the magnitude scale, so its magnitude sits close to 0."
    }
    val findTitle = if (zh) "这个冬天怎么找" else "Finding them this winter"
    val find = if (zh) {
        "在北纬 30° 一带，一月晚上九点前后，天狼星在东南方的半空；老人星只在南方贴着地平线露面，纬度越低越容易看到。织女星黄昏时在西北方低空，很快落下；大角星要等到后半夜才从东方升起。"
    } else {
        "Around 30° north at nine on a January evening, Sirius stands halfway up the southeastern sky. Canopus only skims the southern horizon and is easier to see the farther south you are. Vega sits low in the northwest at dusk and sets soon after, and Arcturus rises in the east after midnight."
    }
    val refsTitle = if (zh) "引用" else "References"
    val footer = if (zh) "starlog · 由 web-01 提供" else "starlog · served from web-01"
}

private fun issueState(page: IssuePage): BrowserUiState {
    val tabs = listOf(BrowserTabState("lab-tab-issue", page.tabTitle, ISSUE_URL, released = false))
    return BrowserUiState(
        url = ISSUE_URL,
        title = page.tabTitle,
        canGoBack = true,
        mode = BrowserMode.IDLE,
        tabs = tabs,
        currentTabId = tabs.first().id,
    )
}

/** 封面以 data URI 嵌入：页面基址是演示站点，WebView 不能跨源读取 assets。 */
private fun coverDataUri(assets: AssetManager): String =
    "data:image/jpeg;base64," + Base64.encodeToString(assets.open(COVER_ASSET).use { it.readBytes() }, Base64.NO_WRAP)

private fun issueHtml(style: String, page: IssuePage, cover: String): String {
    val rows = page.rows.joinToString("") { (name, magnitude, distance) ->
        "<tr><td>$name</td><td class=\"num\">$magnitude</td><td class=\"num\">$distance</td></tr>"
    }
    return """
        <!doctype html><html lang="${page.lang}"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>${page.tabTitle}</title><style>$style</style></head>
        <body>
        <header><span class="logo"></span><span class="brand">starlog</span><nav>${page.nav.joinToString("") { "<span>$it</span>" }}</nav></header>
        <article>
          <div class="kicker">${page.kicker}</div>
          <h1>${page.title}</h1>
          <div class="meta">${page.meta}</div>
          <img class="cover" src="$cover" alt="${page.coverAlt}">
          <p>${page.intro}</p>
          <p>${page.second}<sup>[1]</sup></p>
          <h2>${page.tableTitle}</h2>
          <table><tr>${page.columns.joinToString("") { "<th>$it</th>" }}</tr>$rows</table>
          <p>${page.third}<sup>[2]</sup></p>
          <h2>${page.findTitle}</h2>
          <p>${page.find}</p>
          <div class="refs">${page.refsTitle}<br>[1] List of brightest stars · en.wikipedia.org<br>[2] SIMBAD Astronomical Database · simbad.cds.unistra.fr</div>
        </article>
        <footer>${page.footer}</footer>
        </body></html>
    """.trimIndent()
}
