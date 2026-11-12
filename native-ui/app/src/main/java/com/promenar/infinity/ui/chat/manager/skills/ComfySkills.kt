package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.mediagen.comfy.*
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.mediagen.TemplateParam
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.tool.ToolSchemaValidation
import com.promenar.infinity.domain.tool.ToolSchemaValidator
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.util.UUID

/** 主机由用户界面配置；模板创建与更新逐次经过会话审批。 */
fun comfyTools(repository: ComfyRepository): List<SkillDefinition> = listOf(
    ComfyProbeSkill(repository), ComfyDefineTemplateSkill(repository), ComfyListTemplatesSkill(repository),
)
class ComfyProbeSkill(repository: ComfyRepository) : ComfySkill(repository, "comfy_probe",
    "探测已配置的 ComfyUI 主机，返回节点与模型候选摘要。可按 node_type 过滤，长结果截断。",
    """{"type":"object","properties":{"host_id":{"type":"string"},"node_type":{"type":"string"}},"required":["host_id"],"additionalProperties":false}""")
class ComfyListTemplatesSkill(repository: ComfyRepository) : ComfySkill(repository, "comfy_templates",
    "列出已配置主机和 ComfyUI 模板、通道 id、锁定状态、预检结果。recheck=true 重新预检指定模板。",
    """{"type":"object","properties":{"template_id":{"type":"string"},"recheck":{"type":"boolean"}},"additionalProperties":false}""")
class ComfyDefineTemplateSkill(repository: ComfyRepository) : ComfySkill(repository, "comfy_define_template",
    """创建或按同一 id 更新 API Format 工作流模板。每次变更须经用户审批，保存后保持锁定并重新预检。模型文件由 models 显式声明。
        生成模板使用 role=GENERATE，图像或视频按 group 与 capabilities 声明；配乐使用 role=GENERATE、group=AUDIO、MUSIC 模式、DURATION 秒数槽位与 AUDIO 产出。
        派生处理使用 role=DERIVE、operation.label 与一个 INPUT_VIDEO 来源槽位，可不声明 PROMPT。媒体连线必须给出 uploadTargets，节点类型和输入名先用 comfy_probe 核验。
        任意模板可用 params 声明最多 6 个 ENUM/NUMBER/BOOLEAN 参数，每项以唯一 key 对应一个 PARAM 槽位的 param。default 必须满足选项、范围与整数约束，提交请求时可按 key 覆盖 params。
        派生处理最小字段示例（workflow 使用主机探测所得的 API Format 节点字典）：{"id":"upscale","hostId":"host","group":"VIDEO","role":"DERIVE","operation":{"label":"超分"},"slots":[{"nodeId":"1","input":"video","kind":"INPUT_VIDEO"},{"nodeId":"2","input":"videos","kind":"OUTPUT","outputKind":"VIDEO"}],"capabilities":{"modes":["REFERENCE"]}}。
        配乐最小字段示例（workflow 使用主机探测所得的 API Format 节点字典）：{"id":"music","hostId":"host","group":"AUDIO","role":"GENERATE","slots":[{"nodeId":"1","input":"prompt","kind":"PROMPT"},{"nodeId":"1","input":"seconds","kind":"DURATION"},{"nodeId":"2","input":"audio","kind":"OUTPUT","outputKind":"AUDIO"}],"capabilities":{"modes":["MUSIC"]}}。""".trimIndent(),
    COMFY_TEMPLATE_SCHEMA)

abstract class ComfySkill(private val repository: ComfyRepository, override val name: String,
    override val description: String, override val parametersSchema: String) : SkillDefinition {
    override val id get() = name
    override val mcpServerId: String? = null
    override val risk get() = if (name == "comfy_define_template") ToolRisk.AGENT_CONFIG else ToolRisk.SAFE_READ
    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = try {
        val content = when (name) {
            "comfy_probe" -> {
                keys(args, setOf("host_id"), setOf("host_id", "node_type"))
                repository.probe(text(args, "host_id"), optional(args, "node_type"))
            }
            "comfy_define_template" -> {
                keys(args, setOf("template"), setOf("template"))
                val raw = args["template"] as? JsonObject ?: throw ComfyFailure("template 必须是对象")
                require(raw.toString().toByteArray().size <= 1024 * 1024) { "模板参数超过 1 MiB" }
                val validation = ToolSchemaValidator().validate(parametersSchema, args)
                if (validation is ToolSchemaValidation.Invalid) throw ComfyFailure("${validation.error.path.removePrefix("$.template.")}: ${validation.error.message}")
                val template = try { ComfyConfigurationCodec.decodeTemplate(raw) }
                catch (_: SerializationException) { throw ComfyFailure("template 字段、枚举或类型无效，请按参数说明填写") }
                renderState(repository.defineTemplateByAgent(template))
            }
            "comfy_templates" -> {
                keys(args, emptySet(), setOf("template_id", "recheck"))
                val templateId = optional(args, "template_id")
                val recheck = if ("recheck" in args) (args["recheck"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                    ?: throw ComfyFailure("recheck 必须是布尔值") else false
                require(!recheck || templateId != null) { "重新预检需要 template_id" }
                if (recheck) repository.preflight(requireNotNull(templateId))
                val all = repository.templates().filter { templateId == null || it.template.id == templateId }
                require(templateId == null || all.isNotEmpty()) { "模板不存在" }
                buildJsonObject {
                    put("count", all.size)
                    put("hosts", JsonArray(repository.hosts().map { host -> buildJsonObject { put("id", host.id); put("name", host.name) } }))
                    val rendered = boundedComfyStates(all)
                    put("templates", JsonArray(rendered))
                    put("truncated", all.size > rendered.size)
                }
            }
            else -> throw ComfyFailure("未知 ComfyUI 工具")
        }
        ToolResult(id = "comfy_${UUID.randomUUID()}", content = content.toString())
    } catch (e: CancellationException) { throw e }
    catch (e: ComfyFailure) { errorResult(e.message ?: "ComfyUI 操作失败") }
    catch (_: IllegalStateException) { errorResult("配置已变化，请重新读取后操作") }
    catch (_: SerializationException) { errorResult("ComfyUI 配置字段无法解析，请检查已保存的配置") }
    catch (e: IllegalArgumentException) { errorResult(safeParameterError(e)) }
    catch (_: Exception) { errorResult("ComfyUI 操作失败，请检查参数、主机连接和配置") }

    private fun errorResult(message: String) = ToolResult(id = "comfy_${UUID.randomUUID()}", status = "error", content =
        buildJsonObject { put("error", message) }.toString())
    private fun keys(args: JsonObject, required: Set<String>, allowed: Set<String>) {
        if (!args.keys.containsAll(required) || args.keys.any { it !in allowed }) throw ComfyFailure("参数缺失或包含未知字段，请检查工具参数")
    }
    private fun text(args: JsonObject, key: String): String = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        ?: throw ComfyFailure("$key 必须是非空字符串")
    private fun optional(args: JsonObject, key: String): String? = if (key !in args) null else text(args, key)
    private fun safeParameterError(e: IllegalArgumentException): String =
        e.message?.take(600) ?: "template 字段: 参数值无效"

}
private fun boundedComfyStates(states: List<ComfyTemplateState>): List<JsonElement> {
    val result = mutableListOf<JsonElement>()
    var bytes = 0
    for (state in states.take(100)) {
        val rendered = renderState(state)
        bytes += rendered.toString().toByteArray().size
        if (bytes > 24 * 1024) break
        result += rendered
    }
    return result
}
private fun renderState(state: ComfyTemplateState): JsonObject = buildJsonObject {
    put("id", state.template.id); put("name", state.template.name); put("host_id", state.template.hostId)
    put("channel_id", state.template.channelId); put("locked", state.template.locked)
    put("preflight", buildJsonObject { put("status", state.preflight.status.name); put("missing", JsonArray(state.preflight.missing.take(12).map { JsonPrimitive(it.take(160)) }));
        put("uncertain", JsonArray(state.preflight.uncertain.take(12).map { JsonPrimitive(it.take(160)) })); put("truncated", state.preflight.missing.size > 12 || state.preflight.uncertain.size > 12) })
    val channel = state.template.channel()
    put("group", channel.group.name); put("max_images", channel.capabilities.maxImages); put("max_audio", channel.capabilities.maxAudio)
    put("max_videos", channel.capabilities.maxVideos); put("role", state.template.role.name)
    state.template.operation?.let { put("operation", buildJsonObject { put("label", it.label) }) }
    put("params", JsonArray(state.template.params.map { Json.encodeToJsonElement(TemplateParam.serializer(), it) }))
    put("modes", JsonArray(channel.capabilities.modes.map { JsonPrimitive(it.name) }))
}
private const val COMFY_TEMPLATE_SCHEMA = """{
 "type":"object","required":["template"],"additionalProperties":false,"properties":{"template":{
  "type":"object","additionalProperties":false,"required":["id","hostId","group","workflow","slots","capabilities"],"properties":{
   "id":{"type":"string","description":"稳定 id，字母数字下划线或连字符"},"name":{"type":"string"},"hostId":{"type":"string"},
   "locked":{"type":"boolean","description":"审批保存后保持锁定"},
   "group":{"type":"string","enum":["IMAGE","VIDEO","AUDIO"]},"workflow":{"type":"object","description":"ComfyUI API Format 节点字典"},
   "role":{"type":"string","enum":["GENERATE","DERIVE"]},
   "operation":{"type":["object","null"],"additionalProperties":false,"required":["label"],"properties":{"label":{"type":"string","description":"DERIVE 操作显示名称，1～20 字符"}}},
   "params":{"type":"array","description":"最多 6 项；提交时可按 key 覆盖，每项需要一个 PARAM 槽位","items":{"type":"object","additionalProperties":false,"required":["key","label","type","default"],"properties":{
    "key":{"type":"string","description":"[a-z][a-z0-9_]{0,31}，模板内唯一"},"label":{"type":"string","description":"1～40 字符"},
    "type":{"type":"string","enum":["ENUM","NUMBER","BOOLEAN"]},"default":{"type":["string","number","boolean"],"description":"ENUM 为选项字符串，NUMBER 为范围内数值，BOOLEAN 为布尔值"},
    "options":{"type":"array","items":{"type":"string"},"description":"ENUM 专用，2～20 个不重复选项"},
    "min":{"type":["number","null"]},"max":{"type":["number","null"]},"integer":{"type":"boolean","description":"NUMBER 专用，是否要求整数"}
   }}},
   "frameBase":{"type":"integer","description":"正整数"},"frameStep":{"type":"integer","description":"正整数"},"maxFrames":{"type":["integer","null"],"description":"正整数"},
   "slots":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["nodeId","input","kind"],"properties":{
    "nodeId":{"type":"string"},"input":{"type":"string","description":"精确输入键；动态键使用组名.成员数字后缀；OUTPUT 填历史输出字段 images/gifs/videos/audio"},
    "kind":{"type":"string","enum":["PROMPT","REFERENCE_IMAGES","FIRST_FRAME","LAST_FRAME","REFERENCE_AUDIO","INPUT_VIDEO","PARAM","DURATION","WIDTH","HEIGHT","FRAMES","SEED","OUTPUT"]},
    "param":{"type":["string","null"],"description":"PARAM 槽位对应的 params.key；其它种类不填写"},
    "capacity":{"type":"integer","description":"1～100"},"outputKind":{"type":["string","null"],"enum":["IMAGE","VIDEO","AUDIO",null]},
    "uploadTargets":{"type":"array","description":"媒体槽位是节点连线时必填，按容量顺序填写上传文件输入，不修改图片连线","items":{"type":"object","additionalProperties":false,"required":["nodeId","input"],"properties":{"nodeId":{"type":"string"},"input":{"type":"string"}}}}
   }}},
   "models":{"type":"array","description":"显式声明需检查文件可选值的输入","items":{"type":"object","additionalProperties":false,"required":["nodeId","input"],"properties":{"nodeId":{"type":"string"},"input":{"type":"string"}}}},
   "capabilities":{"type":"object","additionalProperties":false,"required":["modes"],"properties":{
    "modes":{"type":"array","items":{"type":"string","enum":["TEXT","REFERENCE","FIRST_FRAME","FIRST_LAST_FRAME","SPEECH","MUSIC"]}},
    "nativeAudio":{"type":"boolean"},"resolutions":{"type":"array","items":{"type":"string"}},
    "minDurationSeconds":{"type":["integer","null"]},"maxDurationSeconds":{"type":["integer","null"]},"promptFormat":{"type":"string","enum":["GENERIC","H3","SEEDANCE"]},
    "referenceOrder":{"type":"array","items":{"type":"string"},"description":"分张参考位顺序；参考容量由槽位计算"}
   }}
  }
 }}}
"""
