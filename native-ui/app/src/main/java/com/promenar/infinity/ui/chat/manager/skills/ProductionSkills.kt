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
class ProductionGetSkill(repo: ProductionRepository) : ProductionSkill(repo, "production_get", "读取制作项目修订号和清单同步状态。scope 为 overview、episode、shot 或 assets；episode 需要 episode_id，shot 还需要 shot_code。内容过长会截断。", """{"type":"object","properties":{"scope":{"type":"string","enum":["overview","episode","shot","assets"]},"episode_id":{"type":"string"},"shot_code":{"type":"string"}},"required":["scope"],"additionalProperties":false}""")
class ProductionUpdateSkill(repo: ProductionRepository) : ProductionSkill(repo, "production_update", "原子执行 1～100 项 operations。upsert_season: id,number,title?；upsert_episode: season_id,id,number,title,script_path?；upsert_scene: episode_id,code,number,heading；upsert_shot: episode_id,scene_code,code,title,duration_seconds,size?,mode?,prompt?,inputs?,preview?,preview_path?,agent_note?,position?，已有镜头只需给修改字段；remove: target,ids，场或镜头另给 episode_id；set_shot_status: episode_id,shot_code,status（todo、generating、review）；add_take: episode_id,shot_code,path,service,model?,mode?,note?；upsert_asset: id,kind,name,description?；add_asset_version: asset_id,path,note?；set_asset_status: asset_id,status（draft、review）；set_stage: stage,status, note?（status 为 not_started、in_progress、skipped）；set_bible: documents（title、path 数组）；set_preview_enabled: enabled。路径为工作区相对路径；锁定和候选选定由用户完成，修改用户决定前先读取。最多20季、每季100集、每集200场、每场100镜头、每镜头30候选和12输入、500资产且每个30版本、50文档，标题120字、提示词7000字、说明1000字。", """{"type":"object","properties":{"operations":{"type":"array","minItems":1,"maxItems":100,"items":{"type":"object","description":"填写 op 及对应操作参数"}}},"required":["operations"],"additionalProperties":false}""")
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
    require(scope in setOf("overview", "episode", "shot", "assets")) { "scope 仅支持 overview、episode、shot、assets。" }
    val p = snapshot?.production ?: throw IllegalArgumentException("当前会话没有制作项目，请先定义或附加。")
    val selected: JsonElement = when (scope) {
        "overview" -> ProductionToolJson.encodeToJsonElement(p.copy(seasons = p.seasons.map { it.copy(episodes = it.episodes.map { ep -> ep.copy(scenes = emptyList()) }) }, assets = emptyList(), bible = p.bible.take(10)))
        "assets" -> ProductionToolJson.encodeToJsonElement(p.assets)
        else -> {
            val ep = p.seasons.flatMap { it.episodes }.firstOrNull { it.id == episodeId } ?: throw IllegalArgumentException("需要有效的 episode_id。")
            if (scope == "episode") ProductionToolJson.encodeToJsonElement(ep) else ProductionToolJson.encodeToJsonElement(ep.scenes.flatMap { it.shots }.firstOrNull { it.code == shotCode } ?: throw IllegalArgumentException("需要有效的 shot_code。"))
        }
    }
    fun wrap(value: JsonElement, truncated: Boolean) = buildJsonObject { put("revision", snapshot.revision); put("production_id", snapshot.production.id); put("manifest", manifestJson(snapshot.manifest)); put("scope", scope); put("content", value); put("truncated", truncated) }.toString()
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
