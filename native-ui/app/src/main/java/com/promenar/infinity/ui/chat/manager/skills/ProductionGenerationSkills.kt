package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

private const val ShotArguments = """{"type":"object","properties":{"episode_id":{"type":"string"},"shot_code":{"type":"string"}},"required":["episode_id","shot_code"],"additionalProperties":false}"""

class ProductionPlanSkill(private val planner: () -> ShotGenerationPlanner) : ProductionGenerationTool("production_plan", "预览镜头的通道、实际输入顺序、提示词与阻断原因；提交前调用。", ShotArguments, ToolRisk.SAFE_READ) {
    override suspend fun run(args: JsonObject, context: SkillExecutionContext): JsonObject {
        val (episode, shot) = shotArguments(args)
        return shotPlanJson(planner().preview(context.sessionId, episode, shot))
    }
}
class ProductionGenerateSkill(private val planner: () -> ShotGenerationPlanner) : ProductionGenerationTool("production_generate", "按已预览的当前规划生成镜头及配音，返回任务 id 或拒绝原因；任务直接入队。", """{"type":"object","properties":{"episode_id":{"type":"string"},"shot_code":{"type":"string"},"params":{"type":"object"}},"required":["episode_id","shot_code"],"additionalProperties":false}""", ToolRisk.SESSION_STATE) {
    override suspend fun run(args: JsonObject, context: SkillExecutionContext): JsonObject {
        arguments(args, setOf("episode_id", "shot_code"), setOf("episode_id", "shot_code", "params"))
        val episode = stringArgument(args, "episode_id"); val shot = stringArgument(args, "shot_code")
        val params = productionParams(args)
        return when (val result = if (params.isEmpty()) planner().generate(context.sessionId, episode, shot) else planner().generate(context.sessionId, episode, shot, params)) {
            is ShotGenerateResult.Rejected -> buildJsonObject { put("status", "rejected"); put("reason", result.reason) }
            is ShotGenerateResult.Submitted -> buildJsonObject {
                put("status", "submitted"); put("task_id", result.taskId)
                put("audio_task_ids", JsonArray(result.audioTaskIds.map(::JsonPrimitive))); put("warnings", JsonArray(result.warnings.map(::JsonPrimitive)))
            }
        }
    }
}
class ProductionGenerationTasksSkill(private val store: () -> ShotGenerationStore, private val queue: () -> MediaGenerationQueue) : ProductionGenerationTool(
    "production_generation_tasks", "查看当前制作项目的生成任务；可用 episode_id、shot_code 筛选。limit 为1～100，offset 为非负整数。",
    """{"type":"object","properties":{"episode_id":{"type":"string"},"shot_code":{"type":"string"},"limit":{"type":"integer"},"offset":{"type":"integer"}},"additionalProperties":false}""", ToolRisk.SAFE_READ) {
    override suspend fun run(args: JsonObject, context: SkillExecutionContext): JsonObject {
        arguments(args, emptySet(), setOf("episode_id", "shot_code", "limit", "offset"))
        val episode = if ("episode_id" in args) stringArgument(args, "episode_id") else null
        val shot = if ("shot_code" in args) stringArgument(args, "shot_code") else null
        require(shot == null || episode != null)
        fun integer(key: String, fallback: Int) = if (key !in args) fallback else (args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw IllegalArgumentException("$key")
        val limit = integer("limit", 50).also { require(it in 1..100) }
        val offset = integer("offset", 0).also { require(it >= 0) }
        val project = store().load(context.sessionId)?.production ?: throw IllegalArgumentException("project")
        val tasks = queue().observeTasks().first().filter { task ->
            val source = ProductionSourceRef.parse(task.request.sourceRef.removeSuffix("/preview"))
            project.ownsTask(task) && (episode == null || source?.episodeId == episode) && (shot == null || source?.shotCode == shot)
        }
        return buildJsonObject {
            put("production_id", project.id); put("total", tasks.size); put("offset", offset)
            put("tasks", buildJsonArray { tasks.drop(offset).take(limit).forEach { task -> add(buildJsonObject {
                put("id", task.id); put("source_ref", task.request.sourceRef); put("channel_id", task.request.channelId); put("state", task.state.name.lowercase())
                task.progress?.let { put("progress", it) }; put("actual_cost", moneyJson(task.actualCost))
                put("failure", task.failure?.name?.let(::JsonPrimitive) ?: JsonNull)
            }) } })
        }
    }
}
abstract class ProductionGenerationTool(override val name: String, override val description: String, override val parametersSchema: String, override val risk: ToolRisk) : SkillDefinition {
    override val id get() = name
    override val mcpServerId: String? = null
    protected abstract suspend fun run(args: JsonObject, context: SkillExecutionContext): JsonObject
    final override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        try { ToolResult(id = name, content = run(args, context).toString()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ToolResult(id = name, status = "error", content = buildJsonObject { put("error", "INVALID_PRODUCTION_GENERATION"); put("message", (failure as? IllegalArgumentException)?.message?.takeIf { it.any { c -> c.code > 0x2E80 } }?.take(1200) ?: "请核对工具参数、当前项目和生成通道。") }.toString()) }
    }
}
private fun arguments(args: JsonObject, required: Set<String>, allowed: Set<String>) { require(args.keys.containsAll(required) && args.keys.all { it in allowed }) }
private fun stringArgument(args: JsonObject, name: String) = (args[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= 300 } ?: throw IllegalArgumentException(name)
private fun shotArguments(args: JsonObject): Pair<String, String> { arguments(args, setOf("episode_id", "shot_code"), setOf("episode_id", "shot_code")); return stringArgument(args, "episode_id") to stringArgument(args, "shot_code") }
private fun moneyJson(money: Money?): JsonElement = money?.let { buildJsonObject { put("amount", it.amount.toPlainString()); put("currency", it.currency) } } ?: JsonNull
internal fun shotPlanJson(plan: ShotPlanPreview): JsonObject = buildJsonObject {
    put("channel_id", plan.channel?.id?.let(::JsonPrimitive) ?: JsonNull); put("channel_label", plan.channelLabel); put("channel_is_default", plan.channelIsDefault)
    put("specs", Json.encodeToJsonElement(GenerationSpecs.serializer(), plan.specs))
    put("params", JsonObject(plan.params))
    put("mode", plan.mode.name.lowercase()); put("prompt", plan.prompt); put("prompt_user_edited", plan.promptEdited); put("prompt_format", plan.promptFormat.name.lowercase())
    fun inputs(values: List<PlannedInput>) = buildJsonArray { values.forEach { input -> add(buildJsonObject {
        put("number", input.number?.let(::JsonPrimitive) ?: JsonNull); put("title", input.title); put("detail", input.detail); put("kind", input.kind.name.lowercase()); put("role", input.role.name.lowercase())
        put("path", input.path?.let(::JsonPrimitive) ?: JsonNull); put("composite_of", JsonArray(input.compositeOf.map(::JsonPrimitive)))
    }) } }
    put("inputs", inputs(plan.inputs)); put("overflow", inputs(plan.overflow))
    put("blocked_reason", plan.blockedReason?.let(::JsonPrimitive) ?: JsonNull); put("warnings", JsonArray(plan.warnings.map(::JsonPrimitive)))
    put("dubbing", buildJsonArray { plan.audioPlans.forEach { audio -> add(buildJsonObject { put("dialogue_index", audio.dialogueIndex); put("channel_id", audio.request.channelId); put("text", audio.request.prompt) }) } })
}
