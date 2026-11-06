package com.promenar.infinity.ui.lab.browser

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import com.promenar.infinity.ui.lab.stage.LabScene
import com.promenar.infinity.ui.lab.stage.LabSceneScope
import com.promenar.infinity.ui.lab.stage.LabStateScene
import com.promenar.infinity.ui.theme.InfinityBrowserAgentHighlight
import com.promenar.infinity.ui.theme.InfinityBrowserHandoffHighlight

/**
 * 浏览器目录下的版本：Agent 在 starlog 的 CI 页面上重跑失败的作业、用户接手，以及登录页交给用户输入密码。
 * 网页是内置的虚构页面（保留域名 .example），由实验室自建的 WebView 载入，不访问网络；
 * 目标元素的轮廓与角标沿用真实浏览器注入脚本的样式与颜色。
 */
internal val labBrowserScenes: Map<String, () -> LabScene> = mapOf(
    "browser.state.agent" to {
        LabStateScene { LabBrowserPage(runsState(BrowserMode.AGENT), RUNS_URL) { runsHtml(highlight = AGENT_REF) } }
    },
    "browser.state.takeover" to {
        LabStateScene { LabBrowserPage(runsState(BrowserMode.USER), RUNS_URL) { runsHtml(highlight = null) } }
    },
    "browser.state.login" to {
        LabStateScene {
            val badge = stringResource(R.string.browser_handoff_field_badge)
            LabBrowserPage(loginState(), LOGIN_URL) { loginHtml(badge) }
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

/** 与真实浏览器页相同的外壳：整页铺满并避开系统栏；标签栏与地址栏的操作都是空操作，返回键接到版本页出口。 */
@Composable
private fun LabSceneScope.LabBrowserPage(state: BrowserUiState, baseUrl: String, html: () -> String) {
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
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        onNavigate = { _: String -> },
        tabActions = BrowserTabActions(onSelect = {}, onClose = {}, onNew = {}, onDisplayMode = {}),
    ) {
        val page = remember { html() }
        LabWebView(baseUrl, page)
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

private val PAGE_STYLE = """
    *{box-sizing:border-box}
    body{margin:0;font:15px/1.45 system-ui,-apple-system,sans-serif;color:#1f2328;background:#fff}
    header{display:flex;align-items:center;gap:10px;padding:14px 18px;border-bottom:1px solid #e4e6eb}
    .logo{width:22px;height:22px;border-radius:6px;background:#2f3e9e}
    .brand{font-weight:600}
    main{padding:18px}
    h1{font-size:20px;margin:0 0 4px}
    .meta{color:#59636e;font-size:13px;margin-bottom:16px}
    .job{display:flex;align-items:center;gap:10px;padding:12px 14px;border:1px solid #e4e6eb;border-radius:10px;margin-bottom:10px}
    .dot{width:10px;height:10px;border-radius:50%}
    .ok{background:#1a7f37}.fail{background:#cf222e}
    .job .name{flex:1;font-weight:500}
    .job .time{color:#59636e;font-size:13px;font-variant-numeric:tabular-nums}
    .log{font:12px/1.5 ui-monospace,monospace;background:#f6f8fa;border-radius:8px;padding:10px 12px;color:#59636e;margin:4px 0 16px;white-space:pre-wrap}
    .btn{display:inline-block;padding:10px 16px;border-radius:8px;font-weight:600;border:1px solid #d0d7de;background:#f6f8fa;color:#1f2328}
    .primary{background:#2f3e9e;border-color:#2f3e9e;color:#fff}
    .row{display:flex;gap:10px;flex-wrap:wrap}
    label{display:block;font-size:14px;font-weight:600;margin:14px 0 6px}
    .field{width:100%;padding:11px 12px;border:1px solid #d0d7de;border-radius:8px;font-size:15px;min-height:44px;color:#1f2328}
    .card{max-width:380px;margin:28px auto;padding:22px;border:1px solid #e4e6eb;border-radius:14px}
    .hl{position:relative}
    .hl::after{content:'';position:absolute;left:-3px;top:-3px;right:-3px;bottom:-3px;border:2px solid var(--hl);border-radius:6px;pointer-events:none}
    .badge{position:absolute;left:-5px;top:-23px;padding:1px 6px;border-radius:6px;white-space:nowrap;font:600 11px/16px system-ui,sans-serif;color:#fff;background:var(--hl)}
""".trimIndent()

private fun page(title: String, body: String) = """
    <!doctype html><html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title>$title</title><style>$PAGE_STYLE</style></head>
    <body><header><span class="logo"></span><span class="brand">starlog CI</span></header>$body</body></html>
""".trimIndent()

private fun runsHtml(highlight: String?): String {
    val color = InfinityBrowserAgentHighlight.css()
    val rerun = if (highlight != null) {
        """<span class="hl" style="--hl:$color"><span class="btn primary">$RERUN_LABEL</span><span class="badge">$highlight</span></span>"""
    } else {
        """<span class="btn primary">$RERUN_LABEL</span>"""
    }
    return page(
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

private fun loginHtml(badge: String): String {
    val color = InfinityBrowserHandoffHighlight.css()
    return page(
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
