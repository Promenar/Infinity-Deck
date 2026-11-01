package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.repository.StateBoardRepository
import com.promenar.infinity.data.state.*
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private const val STATE_VALUE_SCHEMA = """{"description":"text 为字符串；number 为数值；meter 为 {current:数值,max:正数}；list 为字符串数组，也接受非空字符串并作为单项列表，每项最多200字、总计最多50项"}"""

class StateDefineSkill(repo: StateBoardRepository) : StateBoardSkill(repo, "state_define",
    "定义状态面板，跟踪游戏状态或写作进度。title 最多80字，fields 1～20项，key 以字母开头、最多40字且唯一；label 最多40字。重定义保留同 key 现值，可增删、改名、排序；同 key 不可改类型。新字段省略 value 时使用空值，meter 默认0/100。text 最多2000字，list 最多50项且每项200字；数值有限且绝对值不超过10^12。",
    """{"type":"object","properties":{"title":{"type":"string"},"fields":{"type":"array","items":{"type":"object","properties":{"key":{"type":"string"},"label":{"type":"string"},"type":{"type":"string","enum":["text","number","meter","list"]},"value":$STATE_VALUE_SCHEMA,"unit":{"type":"string","description":"number/meter 可选单位，最多16字"}},"required":["key","label","type"],"additionalProperties":false}}},"required":["title","fields"],"additionalProperties":false}""")

class StateUpdateSkill(repo: StateBoardRepository) : StateBoardSkill(repo, "state_update",
    "原子批量更新状态面板，一次1～50项。set 设置完整 value；increment 对 number 或 meter.current 加上 value（负数为减少）；list 的 set/append/remove 接受字符串数组或作为单项列表的非空字符串，每项最多200字、总计最多50项；append/remove 追加/移除列表项；clear 清空（meter 保留 max）。所有操作合法才写入，返回前后值。剧情或进度改变字段时先更新再回复。",
    """{"type":"object","properties":{"operations":{"type":"array","items":{"type":"object","properties":{"key":{"type":"string"},"op":{"type":"string","enum":["set","increment","append","remove","clear"]},"value":$STATE_VALUE_SCHEMA},"required":["key","op"],"additionalProperties":false}}},"required":["operations"],"additionalProperties":false}""")

class StateGetSkill(repo: StateBoardRepository) : StateBoardSkill(repo, "state_get", "读取当前会话完整状态面板及字段 key、类型、现值、单位和更新时间。", """{"type":"object","properties":{},"additionalProperties":false}""")
class StateRemoveSkill(repo: StateBoardRepository) : StateBoardSkill(repo, "state_remove", "移除当前会话的状态面板；撤销所属回复时面板可随消息回滚。", """{"type":"object","properties":{},"additionalProperties":false}""")

abstract class StateBoardSkill(
    private val repo: StateBoardRepository,
    override val name: String,
    override val description: String,
    override val parametersSchema: String,
) : SkillDefinition {
    override val id get() = name
    override val mcpServerId: String? = null
    override val risk get() = if (name == "state_get") ToolRisk.SAFE_READ else ToolRisk.SESSION_STATE
    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = try {
        val previous = repo.get(context.sessionId)
        val owner = context.assistantMessageId
        fun owner() = requireNotNull(owner) { "缺少所属助手消息，无法保存状态版本。" }
        var changes = emptyList<StateChange>()
        val snapshot = when (name) {
            "state_define" -> {
                require(args.keys == setOf("title", "fields")) { "需要 title 和 fields 参数。" }
                val title = args["title"] as? JsonPrimitive
                require(title?.isString == true) { "title 必须是字符串。" }
                val raw = args["fields"] as? JsonArray ?: throw IllegalArgumentException("fields 必须是数组。")
                repo.define(context.sessionId, owner(), title.content, raw.map { Json.decodeFromJsonElement<StateField>(it) })
            }
            "state_update" -> {
                require(args.keys == setOf("operations")) { "需要 operations 数组参数。" }
                val raw = args["operations"] as? JsonArray ?: throw IllegalArgumentException("operations 必须是数组。")
                val result = repo.update(context.sessionId, owner(), raw.map { Json.decodeFromJsonElement<StateOperation>(it) })
                changes = result.second
                result.first
            }
            "state_remove" -> {
                require(args.isEmpty()) { "state_remove 不接受参数。" }
                repo.remove(context.sessionId, owner())
            }
            else -> { require(args.isEmpty()) { "state_get 不接受参数。" }; previous }
        }
        if (name == "state_get" && owner != null) repo.recordContextRead(context.sessionId, owner, snapshot?.revision ?: 0)
        ToolResult(id = name, content = buildJsonObject {
            put("action", name)
            put("created", name == "state_define" && previous?.board == null)
            put("revision", snapshot?.revision ?: 0)
            put("board", snapshot?.board?.let { Json.encodeToJsonElement(it) } ?: JsonNull)
            put("changes", Json.encodeToJsonElement(changes))
        }.toString())
    } catch (invalid: IllegalArgumentException) {
        ToolResult(id = name, status = "error", content = buildJsonObject {
            put("error", "INVALID_STATE_ARGUMENTS")
            put("message", invalid.message.orEmpty().take(600))
            put("suggestion", "用 state_get 核对字段 key、类型和现值，修正参数后调用；没有面板时先 state_define。")
        }.toString())
    }
}
