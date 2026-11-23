package com.promenar.infinity.ui.chat.command

import com.promenar.infinity.data.skills.DiscoveredSkill
import com.promenar.infinity.data.skills.SkillSlashInvocation
import com.promenar.infinity.data.skills.SkillStatus
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.export.SessionExportFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 执行内置命令时需要的会话现状。 */
data class SlashCommandContext(
    val isGenerating: Boolean = false,
    val isCompressing: Boolean = false,
    val hasMessages: Boolean = false,
    val toolsEnabled: Boolean = true,
    val executionMode: ExecutionMode = ExecutionMode.WORKSPACE,
)

/** 内置命令解析后的动作；界面层把它映射到现有的 ViewModel 方法、对话框与轻提示。 */
sealed interface SlashCommandAction {
    data object CreateSession : SlashCommandAction
    data class Rename(val title: String) : SlashCommandAction
    data object OpenRenameDialog : SlashCommandAction
    data class SetSessionPrompt(val prompt: String) : SlashCommandAction
    data object OpenPromptEditor : SlashCommandAction
    data object Compact : SlashCommandAction

    /** 清空历史不可逆，只打开现有的确认对话框。 */
    data object ConfirmClearHistory : SlashCommandAction
    data class SetExecutionMode(val mode: ExecutionMode) : SlashCommandAction

    /** 打开会话设置的“工具”页（执行模式所在位置）。 */
    data object OpenToolSettings : SlashCommandAction
    data class SetToolsEnabled(val enabled: Boolean) : SlashCommandAction
    /** 不带参数的 /export：打开导出面板。 */
    data object OpenExportSheet : SlashCommandAction
    data class Export(val format: SessionExportFormat) : SlashCommandAction
    data class Reject(val reason: SlashCommandRejection, val argument: String = "") : SlashCommandAction
}

/** 内置命令无法执行的原因；草稿保持不变，便于用户修改参数。 */
enum class SlashCommandRejection {
    COMPACT_GENERATING,
    COMPACT_RUNNING,
    NOTHING_TO_CLEAR,
    NOTHING_TO_EXPORT,
    UNKNOWN_MODE,
    UNKNOWN_TOGGLE,
    UNKNOWN_EXPORT_FORMAT,
}

/** 内置命令的参数解析与可用性判断，纯函数。 */
object BuiltinSlashCommandResolver {
    private val MODE_ALIASES = mapOf(
        ExecutionMode.WORKSPACE to setOf("workspace", "工作区", "默认", "default", "按风险审批", "风险审批", "命令需批准", "需批准", "ask by risk", "approve by risk", "ask for commands", "ask"),
        ExecutionMode.FULL_ACCESS to setOf("full_access", "full access", "完全访问", "免批准", "no approvals"),
    )
    private val ON = setOf("on", "开", "开启", "打开", "启用", "enable", "true", "1")
    private val OFF = setOf("off", "关", "关闭", "停用", "禁用", "disable", "false", "0")
    private val EXPORT_FORMAT_ALIASES = mapOf(
        SessionExportFormat.MARKDOWN to setOf("md", "markdown"),
        SessionExportFormat.TXT to setOf("txt", "text", "文本", "纯文本"),
        SessionExportFormat.HTML to setOf("html", "htm", "web", "网页"),
        SessionExportFormat.PDF to setOf("pdf"),
        SessionExportFormat.PNG to setOf("png", "image", "img", "图片", "长图", "图像"),
    )

    fun resolve(command: BuiltinSlashCommand, argument: String, context: SlashCommandContext): SlashCommandAction {
        val arg = argument.trim()
        val key = arg.lowercase()
        return when (command) {
            BuiltinSlashCommand.NEW -> SlashCommandAction.CreateSession
            BuiltinSlashCommand.RENAME -> {
                val title = arg.lines().joinToString(" ") { it.trim() }.trim()
                if (title.isEmpty()) SlashCommandAction.OpenRenameDialog else SlashCommandAction.Rename(title)
            }
            BuiltinSlashCommand.PROMPT ->
                if (arg.isEmpty()) SlashCommandAction.OpenPromptEditor else SlashCommandAction.SetSessionPrompt(arg)
            BuiltinSlashCommand.COMPACT -> when {
                context.isCompressing -> SlashCommandAction.Reject(SlashCommandRejection.COMPACT_RUNNING)
                context.isGenerating -> SlashCommandAction.Reject(SlashCommandRejection.COMPACT_GENERATING)
                else -> SlashCommandAction.Compact
            }
            BuiltinSlashCommand.CLEAR ->
                if (context.hasMessages) SlashCommandAction.ConfirmClearHistory
                else SlashCommandAction.Reject(SlashCommandRejection.NOTHING_TO_CLEAR)
            BuiltinSlashCommand.MODE -> {
                if (arg.isEmpty()) return SlashCommandAction.OpenToolSettings
                MODE_ALIASES.entries.firstOrNull { key in it.value }?.let { SlashCommandAction.SetExecutionMode(it.key) }
                    ?: SlashCommandAction.Reject(SlashCommandRejection.UNKNOWN_MODE, arg)
            }
            BuiltinSlashCommand.TOOLS -> when (key) {
                "" -> SlashCommandAction.SetToolsEnabled(!context.toolsEnabled)
                in ON -> SlashCommandAction.SetToolsEnabled(true)
                in OFF -> SlashCommandAction.SetToolsEnabled(false)
                else -> SlashCommandAction.Reject(SlashCommandRejection.UNKNOWN_TOGGLE, arg)
            }
            BuiltinSlashCommand.EXPORT -> {
                val format = if (key.isEmpty()) null else {
                    EXPORT_FORMAT_ALIASES.entries.firstOrNull { key.removePrefix(".") in it.value }?.key
                        ?: return SlashCommandAction.Reject(SlashCommandRejection.UNKNOWN_EXPORT_FORMAT, arg)
                }
                when {
                    !context.hasMessages -> SlashCommandAction.Reject(SlashCommandRejection.NOTHING_TO_EXPORT)
                    format == null -> SlashCommandAction.OpenExportSheet
                    else -> SlashCommandAction.Export(format)
                }
            }
        }
    }
}

/** 斜杠技能命令无法发送的原因。不认识的命令词按普通消息发送，不在此列。 */
enum class SlashBlockReason { DISABLED, PENDING_TRUST, BROKEN, SCAN_FAILED }

/** 发送时的判定：按普通消息发送、执行内置命令、发送技能命令或拦下并提示。 */
sealed interface SlashSendDecision {
    data object SendAsMessage : SlashSendDecision
    data class RunBuiltin(val command: BuiltinSlashCommand, val argument: String) : SlashSendDecision
    data class SendSkill(val skill: DiscoveredSkill) : SlashSendDecision
    data class Block(val reason: SlashBlockReason, val name: String) : SlashSendDecision

    /** 需要先扫描技能才能判定（命令词符合技能名格式且不是内置命令名）。 */
    data class NeedsSkills(val name: String, val argument: String) : SlashSendDecision
}

/**
 * 发送前的命令判定。优先级：内置命令名 > 同名技能 > 内置命令别名 > 普通消息。
 * 第一个词不是命令形态（如 `/etc/hosts`、`/ 你好`、`/嗯`），或是没有对应技能与命令的词（如 `/tmp 目录`）时按普通消息发送。
 */
object SlashSendPlanner {
    fun classify(text: String): SlashSendDecision {
        val parsed = SkillSlashInvocation.parse(text) ?: return SlashSendDecision.SendAsMessage
        BuiltinSlashCommand.byName(parsed.token)?.let { return SlashSendDecision.RunBuiltin(it, parsed.argument) }
        parsed.skillName?.let { return SlashSendDecision.NeedsSkills(it, parsed.argument) }
        BuiltinSlashCommand.byAlias(parsed.token)?.let { return SlashSendDecision.RunBuiltin(it, parsed.argument) }
        return SlashSendDecision.SendAsMessage
    }

    /** 用最新扫描结果判定技能命令；[skills] 为 null 表示扫描失败，不发送。 */
    fun withSkills(pending: SlashSendDecision.NeedsSkills, skills: List<DiscoveredSkill>?): SlashSendDecision {
        if (skills == null) return SlashSendDecision.Block(SlashBlockReason.SCAN_FAILED, pending.name)
        return when (val selection = SkillSlashInvocation.select(skills, pending.name)) {
            is SkillSlashInvocation.Selection.Ready -> SlashSendDecision.SendSkill(selection.skill)
            is SkillSlashInvocation.Selection.Unavailable -> SlashSendDecision.Block(
                when (selection.skill.status) {
                    SkillStatus.PENDING_TRUST -> SlashBlockReason.PENDING_TRUST
                    SkillStatus.BROKEN -> SlashBlockReason.BROKEN
                    else -> SlashBlockReason.DISABLED
                },
                pending.name,
            )
            SkillSlashInvocation.Selection.NotFound -> BuiltinSlashCommand.byAlias(pending.name)
                ?.let { SlashSendDecision.RunBuiltin(it, pending.argument) }
                ?: SlashSendDecision.SendAsMessage
        }
    }
}

/**
 * 输入框命令的交互控制：点选候选与发送拦截。界面层注入副作用，便于在 JVM 中测试。
 *
 * - 点选内置命令：以输入框第一个词之后的内容作为参数立即执行，并清空输入框；
 * - 点选技能命令：把第一个词替换为 `/技能名 `，保留其后已写的补充说明；
 * - 发送：普通消息直接发送；内置命令执行成功后清空输入框，被拒绝时保留草稿；
 *   技能命令先重新扫描确认可用再发送，不可用时提示并保留草稿。
 */
class SlashCommandController(
    private val scope: CoroutineScope,
    private val scanSkills: suspend () -> List<DiscoveredSkill>?,
    private val context: () -> SlashCommandContext,
    private val perform: (SlashCommandAction) -> Unit,
    private val block: (SlashBlockReason, String) -> Unit,
    private val setInput: (String) -> Unit,
) {
    private var checkingSkill = false

    fun pick(command: SlashCommand, currentText: String) {
        val rest = currentText.indexOfFirst(Char::isWhitespace).let { if (it < 0) "" else currentText.substring(it) }.trim()
        val builtin = command.builtin
        if (builtin == null) {
            setInput("/${command.name} " + rest)
            return
        }
        setInput("")
        perform(BuiltinSlashCommandResolver.resolve(builtin, rest, context()))
    }

    /** [send] 按普通消息发送当前草稿（生成中会进入排队），只在判定为普通消息或可用技能命令时调用。 */
    fun submit(text: String, send: () -> Unit) {
        when (val decision = SlashSendPlanner.classify(text)) {
            SlashSendDecision.SendAsMessage -> send()
            is SlashSendDecision.NeedsSkills -> {
                if (checkingSkill) return
                checkingSkill = true
                scope.launch {
                    val skills = try {
                        scanSkills()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    } finally {
                        checkingSkill = false
                    }
                    handle(SlashSendPlanner.withSkills(decision, skills), send)
                }
            }
            else -> handle(decision, send)
        }
    }

    private fun handle(decision: SlashSendDecision, send: () -> Unit) {
        when (decision) {
            is SlashSendDecision.SendSkill, SlashSendDecision.SendAsMessage -> send()
            is SlashSendDecision.Block -> block(decision.reason, decision.name)
            is SlashSendDecision.RunBuiltin -> {
                val action = BuiltinSlashCommandResolver.resolve(decision.command, decision.argument, context())
                if (action !is SlashCommandAction.Reject) setInput("")
                perform(action)
            }
            is SlashSendDecision.NeedsSkills -> Unit
        }
    }
}
