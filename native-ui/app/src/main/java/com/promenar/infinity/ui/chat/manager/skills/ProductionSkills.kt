package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.production.*
import com.promenar.infinity.data.repository.ProductionRepository
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class ProductionDefineSkill(repo: ProductionRepository) : ProductionSkill(repo, "production_define", "定义或更新当前会话的制作项目。title 最多120字；kind 为 film 或 series；root_path 为工作区相对路径，默认 productions/标题 slug；logline 最多1000字。", """{"type":"object","properties":{"title":{"type":"string"},"kind":{"type":"string","enum":["film","series"]},"root_path":{"type":"string"},"logline":{"type":"string"},"aspect_ratio":{"type":"string"}},"required":["title","kind"],"additionalProperties":false}""")
class ProductionAttachSkill(repo: ProductionRepository) : ProductionSkill(repo, "production_attach", "从工作区 production.json 导入并绑定项目；已有同 id 项目时保留数据库内容并报告文件冲突。", """{"type":"object","properties":{"manifest_path":{"type":"string"}},"required":["manifest_path"],"additionalProperties":false}""")
class ProductionGetSkill(repo: ProductionRepository) : ProductionSkill(repo, "production_get", "读取制作项目修订号和清单同步状态。scope 为 overview、episode、shot、assets 或 changes；episode 需要 episode_id，shot 还需要 shot_code。内容过长会截断。", """{"type":"object","properties":{"scope":{"type":"string","enum":["overview","episode","shot","assets","changes"]},"episode_id":{"type":"string"},"shot_code":{"type":"string"}},"required":["scope"],"additionalProperties":false}""")
class ProductionUpdateSkill(repo: ProductionRepository) : ProductionSkill(repo, "production_update", "原子执行制作操作。upsert_shot 支持 size、camera、camera_movement、duration_seconds、visual、performance、dialogue、sound、transition、continuity、appearances、location_asset_id、prop_asset_ids、channel_id、mode、prompt、inputs、preview；upsert_asset 支持 sound 种类、looks、reference_voice。dialogue 用 characterAssetId、text、tone、audioSource（native、dubbed）；appearances 用 characterAssetId、lookId、referenceSlotIds；looks 用 id、name、slots（id、name、path、version）；reference_voice 用 path、transcript、voiceSetting、tone。add_take 使用 episode_id、shot_code、path、service、model、mode、task_id、usage_ref、plan_snapshot（channelId、mode、inputs、prompt）；add_asset_version 使用 asset_id、path、note；set_budget（cents，整数或 null）、set_experimental（enabled）、set_stage、set_bible、set_preview_enabled、upsert_season、upsert_episode、upsert_scene、set_shot_status、set_asset_status、remove 沿用原参数。锁定场内容修改需审批，用户改写的提示词受保护。", """{"type":"object","properties":{"operations":{"type":"array","description":"1～100 项","items":{"type":"object"}}},"required":["operations"],"additionalProperties":false}""")
class ProductionRemoveSkill(repo: ProductionRepository) : ProductionSkill(repo, "production_remove", "解除当前会话与制作项目的绑定，保留项目和文件。", """{"type":"object","properties":{},"additionalProperties":false}""")

abstract class ProductionSkill(private val repo: ProductionRepository, override val name: String, override val description: String, override val parametersSchema: String) : SkillDefinition {
    override val id get() = name
    override val mcpServerId: String? = null
    override val risk get() = if (name == "production_get") ToolRisk.SAFE_READ else ToolRisk.SESSION_STATE
    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = try {
        fun text(key: String): String = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$key 必须是字符串。")
        fun optional(key: String): String? = if (key !in args || args[key] == JsonNull) null else text(key)
        fun keys(required: Set<String>, allowed: Set<String>) { require(args.keys.containsAll(required) && args.keys.all { it in allowed }) { "参数不完整或包含未知字段，请检查工具参数。" } }
        val result = when (name) {
            "production_define" -> {
                keys(setOf("title", "kind"), setOf("title", "kind", "root_path", "logline", "aspect_ratio"))
                val kind = when (text("kind")) { "film" -> ProductionKind.FILM; "series" -> ProductionKind.SERIES; else -> throw IllegalArgumentException("kind 仅支持 film、series。") }
                val old = repo.get(context.sessionId)?.production
                repo.define(context.sessionId, text("title"), kind, if ("root_path" in args) optional("root_path") else old?.rootPath, if ("logline" in args) optional("logline") else old?.logline, if ("aspect_ratio" in args) optional("aspect_ratio") else old?.aspectRatio)
            }
            "production_attach" -> { keys(setOf("manifest_path"), setOf("manifest_path")); repo.attach(context.sessionId, text("manifest_path")) }
            "production_update" -> {
                keys(setOf("operations"), setOf("operations"))
                val ops = (args["operations"] as? JsonArray)?.map { it as? JsonObject ?: throw IllegalArgumentException("每项 operation 必须是对象。") } ?: throw IllegalArgumentException("operations 必须是数组。")
                repo.update(context.sessionId, requireNotNull(context.assistantMessageId) { "缺少所属助手消息。" }, ops)
            }
            "production_remove" -> { keys(emptySet(), emptySet()); repo.unbind(context.sessionId); null }
            else -> { keys(setOf("scope"), setOf("scope", "episode_id", "shot_code")); repo.get(context.sessionId) }
        }
        val body = if (name == "production_get") productionGetResult(result, text("scope"), optional("episode_id"), optional("shot_code")) else buildJsonObject {
            put("action", name); put("revision", result?.revision ?: 0); put("manifest", manifestJson(result?.manifest)); put("production_id", result?.production?.id?.let(::JsonPrimitive) ?: JsonNull)
            if (name == "production_update") {
                val pending = result?.production?.changeRequests.orEmpty().filter { !it.approved }
                put("change_requests", buildJsonArray { pending.forEach { add(buildJsonObject { put("id", it.id); put("status", "待用户审批") }) } })
            }
        }.toString()
        if (name == "production_get" && context.assistantMessageId != null) repo.recordContextRead(context.sessionId, context.assistantMessageId!!, result!!.revision, result.production.id)
        ToolResult(id = name, content = body)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (invalid: Exception) {
        ToolResult(id = name, status = "error", content = buildJsonObject {
            put("error", "INVALID_PRODUCTION_ARGUMENTS"); put("message", invalid.message.orEmpty().take(600)); put("suggestion", "请用 production_get 核对项目与用户决定，修正参数后重试。")
        }.toString())
    }
}

internal fun manifestJson(sync: ManifestSync?): JsonObject = buildJsonObject {
    when (sync) {
        null -> put("status", "none")
        ManifestSync.InSync -> put("status", "in_sync")
        ManifestSync.Pending -> put("status", "pending")
        is ManifestSync.WriteFailed -> { put("status", "write_failed"); put("reason", sync.reason) }
        is ManifestSync.Invalid -> { put("status", "invalid"); put("reason", sync.reason) }
        is ManifestSync.ExternalChange -> { put("status", "external_change"); sync.fileRevision?.let { put("file_revision", it) } }
    }
}

/** 读取结果保留默认值字段（如 todo、not_started），智能体不必猜测缺省含义。 */
private val ProductionToolJson = Json { encodeDefaults = true }

internal fun productionGetResult(snapshot: ProductionSnapshot?, scope: String, episodeId: String?, shotCode: String?): String {
    require(scope in setOf("overview", "episode", "shot", "assets", "changes")) { "scope 仅支持 overview、episode、shot、assets、changes。" }
    val p = snapshot?.production ?: throw IllegalArgumentException("当前会话没有制作项目，请先定义或附加。")
    val selected: JsonElement = when (scope) {
        "overview" -> ProductionToolJson.encodeToJsonElement(p.copy(seasons = p.seasons.map { it.copy(episodes = it.episodes.map { ep -> ep.copy(scenes = emptyList()) }) }, assets = emptyList(), bible = p.bible.take(10)))
        "assets" -> ProductionToolJson.encodeToJsonElement(p.assets)
        "changes" -> ProductionToolJson.encodeToJsonElement(p.changeRequests)
        else -> {
            val ep = p.seasons.flatMap { it.episodes }.firstOrNull { it.id == episodeId } ?: throw IllegalArgumentException("需要有效的 episode_id。")
            if (scope == "episode") ProductionToolJson.encodeToJsonElement(ep) else ProductionToolJson.encodeToJsonElement(ep.scenes.flatMap { it.shots }.firstOrNull { it.code == shotCode } ?: throw IllegalArgumentException("需要有效的 shot_code。"))
        }
    }
    fun wrap(value: JsonElement, truncated: Boolean) = buildJsonObject { put("revision", snapshot.revision); put("production_id", snapshot.production.id); put("manifest", manifestJson(snapshot.manifest)); put("scope", scope); put("content", value); put("truncated", truncated)
        if (scope == "shot") p.seasons.flatMap { it.episodes }.firstOrNull { it.id == episodeId }?.scenes?.flatMap { it.shots }?.firstOrNull { it.code == shotCode }?.let { shot ->
            put("reference_warnings", JsonArray(ProductionRules.referenceWarnings(p, shot).map(::JsonPrimitive)))
        } }.toString()
    val full = wrap(selected, false)
    if (full.toByteArray(Charsets.UTF_8).size <= 24 * 1024) return full
    val raw = selected.toString()
    var limit = minOf(raw.length, 12000)
    while (limit > 0) {
        val output = wrap(JsonPrimitive(raw.take(limit)), true)
        if (output.toByteArray(Charsets.UTF_8).size <= 24 * 1024) return output
        limit /= 2
    }
    return wrap(JsonPrimitive(""), true)
}
