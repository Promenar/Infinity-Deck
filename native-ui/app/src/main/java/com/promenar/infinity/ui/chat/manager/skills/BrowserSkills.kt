package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.browser.BrowserActionException
import com.promenar.infinity.data.browser.BrowserArgumentException
import com.promenar.infinity.data.browser.BrowserArguments
import com.promenar.infinity.data.browser.BrowserAvailability
import com.promenar.infinity.data.browser.BrowserController
import com.promenar.infinity.data.browser.BrowserFieldPolicy
import com.promenar.infinity.data.browser.BrowserSnapshotFormatter
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.network.AgentNetworkPolicy
import com.promenar.infinity.domain.network.AgentNetworkTool
import com.promenar.infinity.domain.network.NetworkAccessDenied
import com.promenar.infinity.domain.network.NetworkTargetClass
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

const val BROWSER_OPEN = "browser_open"
const val BROWSER_SNAPSHOT = "browser_snapshot"
const val BROWSER_CLICK = "browser_click"
const val BROWSER_TYPE = "browser_type"
val BROWSER_TOOL_NAMES = setOf(BROWSER_OPEN, BROWSER_SNAPSHOT, BROWSER_CLICK, BROWSER_TYPE) + BROWSER_DEVELOPER_TOOL_NAMES

/** 结果首行的固定前缀，执行步骤行据此显示元素名称；改动时同步 [browserStepLabel] 的解析。 */
internal const val BROWSER_CLICKED_PREFIX = "Clicked "
internal const val BROWSER_ELEMENTS_PREFIX = "Interactive elements ("

fun browserTools(
    controller: BrowserController,
    networkPolicy: AgentNetworkPolicy,
    screenshots: com.promenar.infinity.ui.browser.BrowserScreenshotPublisher? = null,
): List<SkillDefinition> = listOf(
    BrowserSkill(controller, networkPolicy, BROWSER_OPEN,
        "Open a web page in the user's in-app Agent browser and return a snapshot of its interactive elements. Use it for pages that need clicking, typing, scripts or a login; use web_fetch to just read a static page. The browser keeps its own cookies, separate from other apps.",
        """{"type":"object","properties":{"url":{"type":"string","description":"An http or https address. A bare host name is opened with https."}},"required":["url"],"additionalProperties":false}"""),
    BrowserSkill(controller, networkPolicy, BROWSER_SNAPSHOT,
        "Read the current page of the Agent browser: interactive elements with refs such as e12, headings, and optionally the readable main text in chunks.",
        """{"type":"object","properties":{"include_text":{"type":"boolean","description":"Also return the page's readable main text. Defaults to false."},"text_offset":{"type":"integer","description":"Non-negative character offset for the next text chunk, from the previous snapshot."}},"additionalProperties":false}"""),
    BrowserSkill(controller, networkPolicy, BROWSER_CLICK,
        "Click an element in the Agent browser by its ref from the latest snapshot, wait for any navigation, and return a new snapshot.",
        """{"type":"object","properties":{"ref":{"type":"string","description":"Element ref from the latest snapshot, such as e12."}},"required":["ref"],"additionalProperties":false}"""),
    BrowserSkill(controller, networkPolicy, BROWSER_TYPE,
        "Replace the text of an input field in the Agent browser and optionally press Enter, then return a new snapshot. Password, payment and verification-code fields are never filled by the agent: call this tool on such a field with empty text and the page is handed to the user, who fills it and hands control back; the call returns after that.",
        """{"type":"object","properties":{"ref":{"type":"string","description":"Field ref from the latest snapshot, such as e3."},"text":{"type":"string","description":"Text that replaces the field's current value, at most ${BrowserArguments.MAX_TEXT} characters."},"submit":{"type":"boolean","description":"Press Enter after typing to submit. Defaults to false."}},"required":["ref","text"],"additionalProperties":false}"""),
) + browserDeveloperTools(controller, networkPolicy, screenshots)

private class BrowserSkill(
    private val controller: BrowserController,
    private val networkPolicy: AgentNetworkPolicy,
    override val name: String,
    override val description: String,
    override val parametersSchema: String,
) : SkillDefinition {
    override val id = name
    override val mcpServerId: String? = null
    /** 操作绑定当前聊天的浏览器会话，子代理不可调用。 */
    override val risk = ToolRisk.SESSION_STATE

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val availability = controller.availability()
        if (availability is BrowserAvailability.Unsupported) {
            return error("BROWSER_UNSUPPORTED", "The system WebView ${availability.currentVersion ?: "is missing and"} does not support isolated browser profiles.",
                "Ask the user to update Android System WebView, or use web_fetch to read pages.")
        }
        return try {
            when (name) {
                BROWSER_OPEN -> open(args, context.sessionId)
                BROWSER_SNAPSHOT -> success(BrowserSnapshotFormatter.format(
                    controller.snapshot(context.sessionId, BrowserArguments.includeText(args), BrowserArguments.textOffset(args))))
                BROWSER_CLICK -> click(args, context.sessionId)
                else -> type(args, context.sessionId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: BrowserArgumentException) {
            error("INVALID_ARGUMENTS", invalid.message, "Fix the arguments and retry.")
        } catch (denied: NetworkAccessDenied) {
            error(denied.code, denied.message ?: "Network access denied.", "Open a public web address instead.")
        } catch (failed: BrowserActionException) {
            error(failed.code, failed.message, suggestionFor(failed.code))
        }
    }

    private suspend fun open(args: JsonObject, sessionId: String): ToolResult {
        val url = BrowserArguments.url(args)
        val endpoint = networkPolicy.validate(url, AgentNetworkTool.WEB_FETCH)
        // WebView 不经过应用内 Tailscale 代理，tailnet 地址在浏览器里不可达。
        if (endpoint.target == NetworkTargetClass.TAILNET) {
            return error("TAILNET_UNSUPPORTED", "The Agent browser cannot reach tailnet addresses.", "Use web_fetch for tailnet pages.")
        }
        return success(BrowserSnapshotFormatter.format(controller.open(sessionId, url)))
    }

    private suspend fun click(args: JsonObject, sessionId: String): ToolResult {
        val element = requireElement(sessionId, BrowserArguments.ref(args))
        val snapshot = controller.click(sessionId, element)
        return success("$BROWSER_CLICKED_PREFIX${element.ref} \"${element.name}\".\n\n${BrowserSnapshotFormatter.format(snapshot)}")
    }

    private suspend fun type(args: JsonObject, sessionId: String): ToolResult {
        val ref = BrowserArguments.ref(args)
        val text = BrowserArguments.text(args)
        val submit = BrowserArguments.submit(args)
        val element = requireElement(sessionId, ref)
        BrowserFieldPolicy.classify(element)?.let { kind ->
            // 敏感字段的文本一律丢弃；页面交给用户，用户交回后再继续。
            val snapshot = controller.handOff(sessionId, element, kind.handoff)
            return success("The ${kind.modelLabel} field ${element.ref} can only be filled by the user. " +
                "The user handled the page and handed control back. Check the new snapshot before continuing; do not ask for the value.\n\n" +
                BrowserSnapshotFormatter.format(snapshot))
        }
        if (!element.editable) return error("NOT_EDITABLE", "Element ${element.ref} is not a text field.", "Pick a textbox or searchbox ref from the latest snapshot.")
        val snapshot = controller.type(sessionId, element, text, submit)
        return success("Typed into ${element.ref} \"${element.name}\"${if (submit) " and pressed Enter" else ""}.\n\n${BrowserSnapshotFormatter.format(snapshot)}")
    }

    private suspend fun requireElement(sessionId: String, ref: String) = controller.describe(sessionId, ref)
        ?: throw BrowserActionException("STALE_REF", "No element $ref exists on the current page.")

    private fun suggestionFor(code: String) = when (code) {
        "STALE_REF" -> "Call browser_snapshot to get current refs."
        "NO_PAGE" -> "Call browser_open first."
        "USER_TIMEOUT" -> "Ask the user whether to continue in the browser."
        else -> "Call browser_snapshot to check the page before retrying."
    }

    private fun success(text: String) = ToolResult(id = name, content = text)
    private fun error(code: String, message: String, suggestion: String) = ToolResult(
        id = name, status = "error", content = "$code: $message $suggestion",
    )
}
