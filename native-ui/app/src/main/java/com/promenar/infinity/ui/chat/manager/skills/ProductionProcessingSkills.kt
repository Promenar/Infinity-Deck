package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.mediagen.TemplateParam
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.*

internal fun productionParams(args: JsonObject): Map<String, JsonPrimitive> {
    if ("params" !in args) return emptyMap()
    val objectValue = args["params"] as? JsonObject ?: throw IllegalArgumentException("params 必须是对象。")
    require(objectValue.size <= 6) { "模板参数最多 6 项。" }
    return objectValue.mapValues { (key, value) ->
        require(key.matches(Regex("[a-z][a-z0-9_]{0,31}"))) { "params 的 key 无效。" }
        (value as? JsonPrimitive)?.takeUnless { it == JsonNull } ?: throw IllegalArgumentException("params.$key 必须是字符串、数值或布尔值。")
    }
}
private fun JsonObject.keys(required: Set<String>, allowed: Set<String> = required) {
    require(keys.containsAll(required) && keys.all { it in allowed }) { "缺少必填参数或包含不适用的字段。" }
}
private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?.takeIf { it.isNotBlank() && it.length <= 7000 } ?: throw IllegalArgumentException("$key 需要非空字符串。")
private fun JsonObject.optionalText(key: String): String? = if (key in this) text(key) else null
private fun JsonObject.integer(key: String, fallback: Int? = null): Int? = if (key !in this) fallback else
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw IllegalArgumentException("$key 需要整数。")
private fun JsonObject.number(key: String): Double? = if (key !in this) null else (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    ?.takeIf { it.isFinite() && it > 0 } ?: throw IllegalArgumentException("$key 需要有限正数。")
private fun JsonObject.strings(key: String): List<String> = if (key !in this) emptyList() else
    (get(key) as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotBlank() } ?: throw IllegalArgumentException("$key 需要字符串数组。") }
        ?: throw IllegalArgumentException("$key 需要字符串数组。")
private val progressJson = Json { encodeDefaults = true }

class ProductionOperationsSkill(private val processing: () -> ProductionProcessing) : ProductionGenerationTool("production_operations",
    "读取镜头可由应用执行的尾帧衔接与派生处理及参数表；其余多步编排走脚本并用 production_run 登记。",
    """{"type":"object","properties":{"episode_id":{"type":"string"},"shot_code":{"type":"string"}},"required":["episode_id","shot_code"],"additionalProperties":false}""", ToolRisk.SAFE_READ) {
    override suspend fun run(args: JsonObject, context: SkillExecutionContext): JsonObject {
        args.keys(setOf("episode_id", "shot_code"))
        val operations = processing().operations(context.sessionId, args.text("episode_id"), args.text("shot_code"))
        return buildJsonObject {
            put("extension", operations.extension?.let { buildJsonObject {
                put("channel_id", it.channelId); put("method", "previous_tail_as_first_frame"); put("max_segments", it.maxSegments)
                put("min_duration_seconds", it.minDurationSeconds?.let(::JsonPrimitive) ?: JsonNull); put("max_duration_seconds", it.maxDurationSeconds?.let(::JsonPrimitive) ?: JsonNull)
                put("subsequent_reference_images", false)
            } } ?: JsonNull)
            put("derivations", buildJsonArray { operations.derivations.forEach { operation -> add(buildJsonObject {
                put("operation_id", operation.templateId); put("label", operation.label); put("params", JsonArray(operation.params.map { progressJson.encodeToJsonElement(TemplateParam.serializer(), it) }))
            }) } })
            put("script_route", "一次提交和逐段尾帧衔接由应用执行；依赖工作流内部状态的多步编排经脚本执行，并用 production_run 回报进度和登记产物。")
        }
    }
}
class ProductionDeriveSkill(private val processing: () -> ProductionProcessing) : ProductionGenerationTool("production_derive",
    "把候选视频交给 DERIVE 模板，产出新候选并保存直接来源、实际参数与指纹。operation_id 为模板 id，任务直接入队。",
    """{"type":"object","properties":{"episode_id":{"type":"string"},"shot_code":{"type":"string"},"take_id":{"type":"string"},"operation_id":{"type":"string"},"params":{"type":"object"}},"required":["episode_id","shot_code","take_id","operation_id"],"additionalProperties":false}""", ToolRisk.SESSION_STATE) {
    override suspend fun run(args: JsonObject, context: SkillExecutionContext): JsonObject {
        args.keys(setOf("episode_id", "shot_code", "take_id", "operation_id"), setOf("episode_id", "shot_code", "take_id", "operation_id", "params"))
        val task = processing().derive(context.sessionId, args.text("episode_id"), args.text("shot_code"), args.text("take_id"), args.text("operation_id"), productionParams(args))
        return buildJsonObject { put("status", "submitted"); put("task_id", task.id) }
    }
}
class ProductionScoreSkill(private val processing: () -> ProductionProcessing) : ProductionGenerationTool("production_score",
    "用已通过预检的 AUDIO/GENERATE 配乐模板生成声音资产版本。默认4版、种子互异；默认时长为最新剪辑版本成片时长；直接入队。",
    """{"type":"object","properties":{"asset_id":{"type":"string"},"template_id":{"type":"string"},"prompt":{"type":"string"},"duration_seconds":{"type":"number"},"versions":{"type":"integer","minimum":1,"maximum":30}},"required":["asset_id"],"additionalProperties":false}""", ToolRisk.SESSION_STATE) {
    override suspend fun run(args: JsonObject, context: SkillExecutionContext): JsonObject {
        args.keys(setOf("asset_id"), setOf("asset_id", "template_id", "prompt", "duration_seconds", "versions"))
        val versions = requireNotNull(args.integer("versions", 4)).also { require(it in 1..30) { "versions 需要 1～30。" } }
        val tasks = processing().score(context.sessionId, args.text("asset_id"), args.optionalText("template_id"), args.optionalText("prompt"), args.number("duration_seconds"), versions)
        return buildJsonObject { put("status", if (tasks.size == versions) "submitted" else "partially_submitted"); put("task_ids", JsonArray(tasks.map { JsonPrimitive(it.id) })); if (tasks.size != versions) put("warning", "部分配乐任务已入队，请先查询已有任务，再补交剩余版本。") }
    }
}
class ProductionExtendSkill(private val coordinator: () -> ShotChainCoordinator) : ProductionGenerationTool("production_extend",
    "管理逐段尾帧衔接。start 的 segments 默认3（含起点段），最多8；segment_prompts 为空或与段数相同。status/retry/cancel 需要 chain_id。重试保留已成功段。",
    """{"type":"object","properties":{"action":{"type":"string","enum":["start","status","retry","cancel"]},"episode_id":{"type":"string"},"shot_code":{"type":"string"},"chain_id":{"type":"string"},"start_take_id":{"type":"string"},"segments":{"type":"integer","minimum":1,"maximum":8},"segment_seconds":{"type":"number"},"segment_prompts":{"type":"array","items":{"type":"string"}}},"required":["action","episode_id","shot_code"],"additionalProperties":false}""", ToolRisk.SESSION_STATE) {
    override suspend fun run(args: JsonObject, context: SkillExecutionContext): JsonObject {
        val action = args.text("action")
        val base = setOf("action", "episode_id", "shot_code")
        val episode = args.text("episode_id"); val shot = args.text("shot_code")
        val result = if (action == "start") {
            args.keys(base, base + setOf("start_take_id", "segments", "segment_seconds", "segment_prompts"))
            val segments = requireNotNull(args.integer("segments", 3)).also { require(it in 1..8) { "segments 需要 1～8。" } }
            val prompts = args.strings("segment_prompts")
            require(prompts.isEmpty() || prompts.size == segments) { "segment_prompts 数量必须与 segments 相同。" }
            coordinator().start(context.sessionId, episode, shot, args.optionalText("start_take_id"), segments, args.number("segment_seconds"), prompts)
        } else {
            require(action in setOf("status", "retry", "cancel")) { "action 仅支持 start、status、retry、cancel。" }
            args.keys(base + "chain_id")
            when (action) {
                "retry" -> coordinator().retry(context.sessionId, episode, shot, args.text("chain_id"))
                "cancel" -> coordinator().cancel(context.sessionId, episode, shot, args.text("chain_id"))
                else -> coordinator().status(context.sessionId, episode, shot, args.text("chain_id"))
            }
        }
        return progressJson.encodeToJsonElement(ShotChain.serializer(), result).jsonObject
    }
}
class ProductionRunSkill(private val service: () -> ProductionRunService) : ProductionGenerationTool("production_run",
    "登记外部脚本编排。start 指定镜头或资产、label、可选 steps；progress 指定 completed_steps 与 message；finish 指定 path，可带 source_take_id、operation、params、segments（按成片顺序的分段路径）；fail 指定 reason。后三者需要 run_id。已结束登记项不能再回报。",
    """{"type":"object","properties":{"action":{"type":"string","enum":["start","progress","finish","fail"]},"episode_id":{"type":"string"},"shot_code":{"type":"string"},"asset_id":{"type":"string"},"label":{"type":"string"},"steps":{"type":"integer"},"run_id":{"type":"string"},"completed_steps":{"type":"integer"},"message":{"type":"string"},"path":{"type":"string"},"source_take_id":{"type":"string"},"operation":{"type":"string"},"params":{"type":"object"},"segments":{"type":"array","items":{"type":"string"}},"reason":{"type":"string"}},"required":["action"],"additionalProperties":false}""", ToolRisk.SESSION_STATE) {
    override suspend fun run(args: JsonObject, context: SkillExecutionContext): JsonObject {
        val result = when (args.text("action")) {
            "start" -> {
                args.keys(setOf("action", "label"), setOf("action", "label", "episode_id", "shot_code", "asset_id", "steps"))
                val episode = args.optionalText("episode_id"); val shot = args.optionalText("shot_code"); val asset = args.optionalText("asset_id")
                require(if (asset != null) episode == null && shot == null else episode != null && shot != null) { "对象需要 episode_id 与 shot_code，或单独 asset_id。" }
                val steps = args.integer("steps"); require(steps == null || steps > 0) { "steps 需要正整数。" }
                service().start(context.sessionId, RunTarget(episode, shot, asset), args.text("label"), steps)
            }
            "progress" -> { args.keys(setOf("action", "run_id", "completed_steps", "message")); val done = requireNotNull(args.integer("completed_steps")); require(done >= 0) { "completed_steps 不能为负数。" }; service().progress(context.sessionId, args.text("run_id"), done, args.text("message")) }
            "finish" -> {
                args.keys(setOf("action", "run_id", "path"), setOf("action", "run_id", "path", "source_take_id", "operation", "params", "segments"))
                service().finish(context.sessionId, args.text("run_id"), args.text("path"), args.optionalText("source_take_id"), args.optionalText("operation"), productionParams(args), args.strings("segments"))
            }
            "fail" -> { args.keys(setOf("action", "run_id", "reason")); service().fail(context.sessionId, args.text("run_id"), args.text("reason")) }
            else -> throw IllegalArgumentException("action 仅支持 start、progress、finish、fail。")
        }
        return buildJsonObject { put("run_id", result.id); put("run", progressJson.encodeToJsonElement(ProductionRun.serializer(), result)) }
    }
}
