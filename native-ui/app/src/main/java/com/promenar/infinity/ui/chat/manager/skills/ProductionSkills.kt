package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.production.*
import com.promenar.infinity.data.repository.ProductionRepository
import com.promenar.infinity.data.cut.CutExportController
import com.promenar.infinity.data.cut.CutExportState
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import com.promenar.infinity.domain.mediagen.MediaTask

class ProductionDefineSkill(repo: ProductionRepository) : ProductionSkill(repo, "production_define", "定义或更新当前会话的视频与影视制作项目（适用于长短片、动作试验片、短剧或带分镜/视觉资产/审片需求的视频创作）。title 最多120字；kind 为 film 或 series；root_path 为项目目录，默认 productions/标题 slug；支持当前工作区的文件工具路径，保存为相对路径。logline 最多1000字，aspect_ratio 最多120字。更新时省略可选项可保留现值。", """{"type":"object","properties":{"title":{"type":"string"},"kind":{"type":"string","enum":["film","series"]},"root_path":{"type":"string"},"logline":{"type":"string"},"aspect_ratio":{"type":"string"}},"required":["title","kind"],"additionalProperties":false}""")
class ProductionAttachSkill(repo: ProductionRepository) : ProductionSkill(repo, "production_attach", "manifest_path 指向当前工作区的 production.json，例如 productions/demo/production.json。路径须与清单内 rootPath 对应。同 id 项目保留数据库内容并报告文件冲突。", """{"type":"object","properties":{"manifest_path":{"type":"string"}},"required":["manifest_path"],"additionalProperties":false}""")
class ProductionGetSkill(repo: ProductionRepository, tasks: suspend () -> List<MediaTask> = { emptyList() }, auditFiles: (suspend (String) -> Unit)? = null) : ProductionSkill(repo, "production_get", "读取制作项目修订号和清单同步状态。scope 为 overview、episode、shot、assets 或 changes；overview 包含阶段生成次数与达到6次的镜头、资产；shot 与 assets 包含各自生成次数；changes 返回实时重扫后的 change_requests、最近3轮 review_rounds、open_items、runs、chains、unregistered_files；只有 changes 的 runs、chains、unregistered_files、open_items 使用 limit（1～50，默认10）与 offset（非负，默认0）分页。episode 需要 episode_id，shot 还需要 shot_code。内容过长会截断。", """{"type":"object","properties":{"scope":{"type":"string","enum":["overview","episode","shot","assets","changes"]},"episode_id":{"type":"string"},"shot_code":{"type":"string"},"limit":{"type":"integer"},"offset":{"type":"integer"}},"required":["scope"],"additionalProperties":false}""", tasks, auditFiles)
class ProductionUpdateSkill(repo: ProductionRepository) : ProductionSkill(
    repo, "production_update", productionUpdateDescription, productionUpdateSchema,
)
class ProductionRemoveSkill(repo: ProductionRepository) : ProductionSkill(repo, "production_remove", "解除当前会话与制作项目的绑定，保留项目和文件。", """{"type":"object","properties":{},"additionalProperties":false}""")

abstract class ProductionSkill(private val repo: ProductionRepository, override val name: String, override val description: String, override val parametersSchema: String, private val tasks: suspend () -> List<MediaTask> = { emptyList() }, private val auditFiles: (suspend (String) -> Unit)? = null) : SkillDefinition {
    override val id get() = name
    override val mcpServerId: String? = null
    override val risk get() = if (name == "production_get") ToolRisk.SAFE_READ else ToolRisk.SESSION_STATE
    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = try {
        fun text(key: String): String = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$key 必须是字符串。")
        fun optional(key: String): String? = if (key !in args || args[key] == JsonNull) null else text(key)
        fun keys(required: Set<String>, allowed: Set<String>) = validateProductionToolKeys(args, required, allowed)
        var updateOps: List<JsonElement> = emptyList()
        val result = when (name) {
            "production_define" -> {
                keys(setOf("title", "kind"), setOf("title", "kind", "root_path", "logline", "aspect_ratio"))
                val kind = when (text("kind")) { "film" -> ProductionKind.FILM; "series" -> ProductionKind.SERIES; else -> throw IllegalArgumentException("kind 仅支持 film、series。") }
                val old = repo.get(context.sessionId)?.production
                repo.define(context.sessionId, text("title"), kind, if ("root_path" in args) optional("root_path") else old?.rootPath, if ("logline" in args) optional("logline") else old?.logline, if ("aspect_ratio" in args) optional("aspect_ratio") else old?.aspectRatio)
            }
            "production_attach" -> { keys(setOf("manifest_path"), setOf("manifest_path")); repo.attach(context.sessionId, text("manifest_path")) }
            "production_update" -> {
                keys(setOf("operations"), setOf("operations", "reason"))
                com.promenar.infinity.data.production.ProductionLockApprovalPlanner.extractReason(args)
                val ops = args["operations"] as? JsonArray ?: throw IllegalArgumentException("operations 必须是包含 1～100 个操作对象的数组。")
                updateOps = ops
                val owner = requireNotNull(context.assistantMessageId) { "缺少所属助手消息。" }
                val lockTarget = context.approvedExecutionTarget?.takeIf {
                    context.isApprovalBoundExecution && com.promenar.infinity.data.production.ProductionLockApprovalPlanner.isLockTarget(it)
                }
                if (lockTarget != null) repo.update(context.sessionId, owner, ops, lockTarget)
                else repo.update(context.sessionId, owner, ops)
            }
            "production_remove" -> { keys(emptySet(), emptySet()); repo.unbind(context.sessionId); null }
            else -> {
                keys(setOf("scope"), setOf("scope", "episode_id", "shot_code", "limit", "offset"))
                if (text("scope") == "changes") {
                    try { auditFiles?.invoke(context.sessionId) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                }
                repo.get(context.sessionId)
            }
        }
        val body = if (name == "production_get") productionGetResult(result, text("scope"), optional("episode_id"), optional("shot_code"), tasks(),
            limit = if (text("scope") != "changes" || "limit" !in args) 10 else (args["limit"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw IllegalArgumentException("limit 需要整数。"),
            offset = if (text("scope") != "changes" || "offset" !in args) 0 else (args["offset"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw IllegalArgumentException("offset 需要整数。")) else buildJsonObject {
            put("action", name); put("revision", result?.revision ?: 0); put("manifest", manifestJson(result?.manifest)); put("production_id", result?.production?.id?.let(::JsonPrimitive) ?: JsonNull)
            if (name == "production_update") {
                val pending = result?.production?.changeRequests.orEmpty().filter { !it.approved && !it.rejected }
                put("change_requests", buildJsonArray { pending.forEach { add(buildJsonObject { put("id", it.id); put("status", "待用户审批") }) } })
                val warnings = result?.production?.let { ProductionEngine.collectPromptProtectionWarnings(it, updateOps) }.orEmpty()
                put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
            }
        }.toString()
        if (name == "production_get" && context.assistantMessageId != null) repo.recordContextRead(context.sessionId, context.assistantMessageId!!, result!!.revision, result.production.id)
        ToolResult(id = name, content = body)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (invalid: Exception) {
        ToolResult(id = name, status = "error", content = buildJsonObject {
            val reason = invalid.message.orEmpty().take(PRODUCTION_BATCH_ERROR_LIMIT)
            put("error", if (reason.contains("当前会话没有制作项目")) "no_project" else "INVALID_PRODUCTION_ARGUMENTS")
            put("message", reason)
            put("suggestion", productionErrorSuggestion(reason))
        }.toString())
    }
}

internal fun validateProductionToolKeys(args: JsonObject, required: Set<String>, allowed: Set<String>) {
    val missing = required - args.keys
    val unknown = args.keys - allowed
    require(missing.isEmpty() && unknown.isEmpty()) {
        buildList {
            if (missing.isNotEmpty()) add("缺少必填字段 ${missing.joinToString("、")}。")
            if (unknown.isNotEmpty()) add("含未知或不适用字段 ${unknown.joinToString("、")}，允许字段为 ${allowed.joinToString("、").ifEmpty { "无" }}。")
        }.joinToString(" ")
    }
}

internal fun productionErrorSuggestion(reason: String): String = when {
    "当前会话没有制作项目" in reason -> "请先调用 production_define 创建项目，或用 production_attach 导入已有 production.json。"
    listOf("在读取后由用户修改", "修订冲突", "修订号已变", "绑定已改变", "会话绑定或项目根目录已改变", "清单已变化").any { it in reason } ->
        "请先调用 production_get 获取最新修订与用户决定，再修改。"
    listOf("已锁定", "解锁", "待审核", "待用户审核", "待审批", "待用户审批").any { it in reason } ->
        "请说明需要修改的原因，并在 production_update 中发起已锁定项修改（系统将弹出审批卡交由用户决定），或在制作面板解锁或审核。"
    else -> "请根据错误信息中的操作序号与字段规范修正参数后重试。"
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

/** 版本写入和导出与面板共享控制器；状态读取必须先核对会话绑定。 */
class ProductionCutSkill(
    private val load: suspend (String) -> ProductionSnapshot?,
    private val controller: () -> CutExportController,
) : SkillDefinition {
    override val id = "production_cut"
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SESSION_STATE
    override val description = "管理当前项目的剪辑版本。list 可传 episodeId 筛选；create 必填 episodeId，每集最多20个版本；export、status 必填 versionId，可附 episodeId 核对所属集。burnSubtitles 为布尔值，仅用于 create 或 export。export 异步启动，status 查询进度与产物。"
    override val parametersSchema = """{"type":"object","properties":{"action":{"type":"string","enum":["list","create","export","status"]},"episodeId":{"type":"string"},"versionId":{"type":"string"},"burnSubtitles":{"type":"boolean"}},"required":["action"],"additionalProperties":false}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = try {
        val parameters = parseCutArguments(args)
        val snapshot = requireNotNull(load(context.sessionId)) { "当前会话没有制作项目。" }
        val p = snapshot.production
        parameters.episodeId?.let { episode -> require(p.seasons.flatMap { it.episodes }.any { it.id == episode }) { "集不存在。" } }
        val version = parameters.versionId?.let { id ->
            requireNotNull(p.cuts.firstOrNull { it.id == id && (parameters.episodeId == null || it.episodeId == parameters.episodeId) }) { "剪辑版本不存在。" }
        }
        val cuts = controller()
        val body = when (parameters.action) {
            "list" -> buildJsonObject {
                put("versions", buildJsonArray { p.cuts.filter { parameters.episodeId == null || it.episodeId == parameters.episodeId }.forEach { cut ->
                    add(cutSummary(cut))
                } })
            }
            "create" -> {
                val cut = cuts.createVersion(context.sessionId, requireNotNull(parameters.episodeId) { "create 必填 episodeId。" })
                if (parameters.burnSubtitles == true) cuts.setBurnSubtitles(context.sessionId, cut.id, true)
                cutSummary(cut.copy(burnSubtitles = parameters.burnSubtitles ?: false))
            }
            "export" -> {
                val cut = requireNotNull(version) { "export 需要有效的 versionId。" }
                cuts.export(context.sessionId, cut.id, parameters.burnSubtitles)
                buildJsonObject { put("versionId", cut.id); put("status", "accepted") }
            }
            else -> cutState(requireNotNull(version) { "status 需要有效的 versionId。" }, cuts.observe().value[version.id])
        }
        ToolResult(id = id, content = body.toString())
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (error: Exception) {
        // 领域与控制器的参数错误消息已是简短中文，直接交给智能体；其余异常不暴露底层细节。
        val reason = (error as? IllegalArgumentException ?: error as? IllegalStateException)?.message
            ?.takeIf { it.isNotBlank() && it.length <= 200 && it.any { c -> c.code > 0x2E80 } }
            ?: "请核对剪辑参数、当前项目与导出状态。"
        ToolResult(id = id, status = "error", content = buildJsonObject { put("error", if (reason.contains("当前会话没有制作项目")) "no_project" else "INVALID_PRODUCTION_CUT"); put("message", reason); put("suggestion", productionErrorSuggestion(reason)) }.toString())
    }
}

internal data class CutArguments(val action: String, val episodeId: String?, val versionId: String?, val burnSubtitles: Boolean?)

internal fun parseCutArguments(args: JsonObject): CutArguments {
    validateProductionToolKeys(args, setOf("action"), setOf("action", "episodeId", "versionId", "burnSubtitles"))
    fun text(key: String): String? = if (key !in args) null else
        (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= 300 }
            ?: throw IllegalArgumentException("$key 必须是 1～300 字的非空字符串。")
    val action = requireNotNull(text("action")) { "缺少 action，可用 list、create、export、status。" }
    require(action in setOf("list", "create", "export", "status")) { "action 仅支持 list、create、export、status。" }
    val episode = text("episodeId")
    val version = text("versionId")
    val burn = if ("burnSubtitles" !in args) null else
        (args["burnSubtitles"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw IllegalArgumentException("burnSubtitles 必须是布尔值 true 或 false。")
    when (action) {
        "create" -> require(episode != null && version == null) { "create 必填 episodeId，请省略 versionId。" }
        "export" -> require(version != null) { "export 必填 versionId。" }
        "status" -> require(version != null && burn == null) { "status 必填 versionId，请省略 burnSubtitles。" }
        "list" -> require(version == null && burn == null) { "list 可使用 episodeId，请省略 versionId 和 burnSubtitles。" }
    }
    return CutArguments(action, episode, version, burn)
}

private fun cutSummary(cut: CutVersion) = buildJsonObject {
    put("versionId", cut.id); put("episodeId", cut.episodeId); put("number", cut.number)
    put("clipCount", cut.clips.size); put("gapCount", cut.clips.count { it.path == null })
    put("durationSeconds", cut.clips.sumOf { it.seconds }); put("burnSubtitles", cut.burnSubtitles)
    put("export", cut.export?.let { ProductionToolJson.encodeToJsonElement(it) } ?: JsonNull)
}

private fun cutState(cut: CutVersion, state: CutExportState?) = buildJsonObject {
    put("versionId", cut.id)
    when (val effective = state ?: cut.export?.let(CutExportState::Succeeded) ?: CutExportState.Idle) {
        CutExportState.Idle -> put("status", "idle")
        is CutExportState.Running -> { put("status", "running"); put("progress", effective.progress?.let(::JsonPrimitive) ?: JsonNull) }
        is CutExportState.Succeeded -> { put("status", "succeeded"); put("record", ProductionToolJson.encodeToJsonElement(effective.record)) }
        is CutExportState.Failed -> { put("status", "failed"); put("reason", effective.reason)
            effective.recoveryReference?.let { put("recoveryReference", it) } }
    }
}

internal fun productionGetResult(snapshot: ProductionSnapshot?, scope: String, episodeId: String?, shotCode: String?, tasks: List<MediaTask> = emptyList(), limit: Int = 10, offset: Int = 0): String {
    require(scope != "changes" || limit in 1..50 && offset >= 0) { "limit 需要 1～50，offset 不能为负数。" }
    require(scope in setOf("overview", "episode", "shot", "assets", "changes")) { "scope 仅支持 overview、episode、shot、assets、changes。" }
    val p = snapshot?.production ?: throw IllegalArgumentException("当前会话没有制作项目，请先定义或附加。")
    val stats = productionCostStats(p, tasks)
    fun count(value: GenerationCount) = buildJsonObject { put("completed", value.completed); put("failed", value.failed); put("total", value.total) }
    fun snake(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapKeys { (key, _) -> key.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase() }.mapValues { (_, item) -> snake(item) })
        is JsonArray -> JsonArray(value.map(::snake))
        else -> value
    }
    fun target(value: ReviewTarget) = snake(ProductionToolJson.encodeToJsonElement(value))
    val selected: JsonElement = when (scope) {
        "overview" -> buildJsonObject {
            val overview = p.copy(
                seasons = p.seasons.map { it.copy(episodes = it.episodes.map { ep -> ep.copy(scenes = emptyList()) }) },
                assets = emptyList(),
                bible = p.bible.take(10),
                reviews = emptyList(),
                reviewRounds = emptyList(),
                reviewDrafts = emptyList(),
            )
            val episodeCounts = p.seasons.flatMap { it.episodes }.associate { ep ->
                ep.id to (ep.scenes.size to ep.scenes.sumOf { it.shots.size })
            }
            ProductionToolJson.encodeToJsonElement(overview).jsonObject
                .filterKeys { it !in setOf("reviews", "reviewRounds", "reviewDrafts", "assets") }
                .forEach { (key, value) ->
                    if (key == "seasons") {
                        put(key, buildJsonArray {
                            value.jsonArray.forEach { seasonEl ->
                                val seasonObj = seasonEl.jsonObject
                                add(buildJsonObject {
                                    seasonObj.forEach { (sk, sv) ->
                                        if (sk == "episodes") {
                                            put(sk, buildJsonArray {
                                                sv.jsonArray.forEach { epEl ->
                                                    val epObj = epEl.jsonObject
                                                    val epId = epObj["id"]?.jsonPrimitive?.content.orEmpty()
                                                    val (sceneCount, shotCount) = episodeCounts[epId] ?: (0 to 0)
                                                    add(buildJsonObject {
                                                        epObj.filterKeys { it != "scenes" }.forEach { (ek, ev) -> put(ek, ev) }
                                                        put("scene_count", sceneCount)
                                                        put("shot_count", shotCount)
                                                    })
                                                }
                                            })
                                        } else put(sk, sv)
                                    }
                                })
                            }
                        })
                    } else put(key, value)
                }
            put("asset_count", p.assets.size)
            put("omitted_in_overview", buildJsonArray { add("scenes"); add("assets") })
            put("hint", "概览已省略场次与资产明细，查看请传 scope=episode 或 scope=assets")
            put("generation_counts", buildJsonObject { stats.byStage.forEach { (stage, value) -> put(stage.name.lowercase(), count(value)) } })
            put("alert_shots", buildJsonArray { stats.alertShots.forEach { (key, value) -> add(buildJsonObject { put("episode_id", key.episodeId); put("shot_code", key.shotCode); put("generation_count", count(value)) }) } })
            put("alert_assets", buildJsonArray { stats.alertAssets.forEach { (id, value) -> add(buildJsonObject { put("asset_id", id); put("generation_count", count(value)) }) } })
        }
        "assets" -> buildJsonArray { p.assets.forEach { asset -> add(buildJsonObject {
            ProductionToolJson.encodeToJsonElement(asset).jsonObject.forEach { (key, value) -> put(key, value) }
            put("generation_count", count(stats.asset(asset.id)))
        }) } }
        "changes" -> buildJsonObject {
            val chains = p.seasons.flatMap { it.episodes }.flatMap { ep -> ep.scenes.flatMap { it.shots }.flatMap { shot -> shot.chains.map { chain -> buildJsonObject {
                put("episode_id", ep.id); put("shot_code", shot.code)
                put("chain", snake(ProductionToolJson.encodeToJsonElement(chain.copy(planSnapshot = null, segments = chain.segments.map { it.copy(prompt = null) }))))
            } } } }
            put("runs", snake(ProductionToolJson.encodeToJsonElement(p.runs.drop(offset).take(limit))))
            put("chains", JsonArray(chains.drop(offset).take(limit)))
            put("unregistered_files", JsonArray(p.unregisteredFiles.drop(offset).take(limit).map(::JsonPrimitive)))
            put("totals", buildJsonObject { put("runs", p.runs.size); put("chains", chains.size); put("unregistered_files", p.unregisteredFiles.size); put("open_items", productionOpenItems(p, tasks, stats).size) })
            put("offset", offset); put("limit", limit)
            put("change_requests", snake(ProductionToolJson.encodeToJsonElement(p.changeRequests)))
            put("review_rounds", snake(ProductionToolJson.encodeToJsonElement(p.reviewRounds.take(3))))
            put("open_items", buildJsonArray { productionOpenItems(p, tasks, stats).drop(offset).take(limit).forEach { item -> add(buildJsonObject {
                when (item) {
                    is OpenItem.Run -> { put("kind", "run"); put("run_id", item.run.id); put("unreported", item.unreported); put("message", productionProgressItemText(item)) }
                    is OpenItem.Chain -> { put("kind", "chain"); put("chain_id", item.chain.id); put("episode_id", item.episodeId); put("shot_code", item.shotCode); put("message", productionProgressItemText(item)) }
                    is OpenItem.UnregisteredFile -> { put("kind", "unregistered_file"); put("path", item.path) }
                    is OpenItem.FailedTask -> { put("kind", "failed_task"); put("task_id", item.taskId); put("target", item.target?.let(::target) ?: JsonNull); put("error", item.error?.let(::JsonPrimitive) ?: JsonNull) }
                    is OpenItem.CanceledTask -> { put("kind", "canceled_task"); put("task_id", item.taskId); put("target", item.target?.let(::target) ?: JsonNull) }
                    is OpenItem.Rejected -> { put("kind", "rejected"); put("target", target(item.target)) }
                    is OpenItem.CostAlert -> { put("kind", "cost_alert"); put("target", target(item.target)); put("generation_count", count(item.count)) }
                }
            }) } })
        }
        else -> {
            val ep = p.seasons.flatMap { it.episodes }.firstOrNull { it.id == episodeId } ?: throw IllegalArgumentException("需要有效的 episode_id。")
            if (scope == "episode") ProductionToolJson.encodeToJsonElement(ep) else buildJsonObject {
                val shot = ep.scenes.flatMap { it.shots }.firstOrNull { it.code == shotCode } ?: throw IllegalArgumentException("需要有效的 shot_code。")
                ProductionToolJson.encodeToJsonElement(shot).jsonObject.forEach { (key, value) -> put(key, value) }
                put("generation_count", count(stats.shot(ep.id, shot.code)))
            }
        }
    }
    fun wrap(value: JsonElement, truncated: Boolean) = buildJsonObject { put("revision", snapshot.revision); put("production_id", snapshot.production.id); put("manifest", manifestJson(snapshot.manifest)); put("scope", scope); put("content", value); put("truncated", truncated)
        if (scope == "shot") p.seasons.flatMap { it.episodes }.firstOrNull { it.id == episodeId }?.scenes?.flatMap { it.shots }?.firstOrNull { it.code == shotCode }?.let { shot ->
            put("reference_warnings", JsonArray(ProductionRules.referenceWarnings(p, shot).map(::JsonPrimitive)))
        } }.toString()
    val full = wrap(selected, false)
    if (full.toByteArray(Charsets.UTF_8).size <= 24 * 1024) return full
    val raw = selected.toString()
    var charLimit = minOf(raw.length, 12000)
    while (charLimit > 0) {
        val output = wrap(JsonPrimitive(raw.take(charLimit)), true)
        if (output.toByteArray(Charsets.UTF_8).size <= 24 * 1024) return output
        charLimit /= 2
    }
    return wrap(JsonPrimitive(""), true)
}

private val productionUpdateDescription = """
    原子批量执行 1～100 项 operations，按依赖顺序排列，每项必填 op。季 id 为 S[0-9]+（S1），集 id 为 S[0-9]+E[0-9]{2,}（S1E01）且属于该季，场 code 为 SC[0-9]+（SC01），镜头 code 为 SC[0-9]+-[0-9]+（SC01-01）且属于该场。资产 id 为 [a-z][a-z0-9-]*，最多48字符。number 为正整数，position 为场内从 0 起算的位置，duration_seconds 为有限正数。
    upsert_season 必填 id、number，可选 title。
    upsert_episode 必填 season_id、id、number、title，可选 script_path、previs_path。登记同路径剧本正文更新时也须提供 script_path。
    upsert_scene 必填 episode_id、code、number、heading。
    upsert_shot 必填 episode_id、scene_code、code，新建还须 title、duration_seconds。可选 title、duration_seconds、size、camera、camera_movement、visual、performance、dialogue、sound、transition、continuity、appearances、location_asset_id、prop_asset_ids、channel_id、mode、prompt、inputs、preview、preview_path、agent_note、change_note、position。
    set_shot_status 必填 episode_id、shot_code、status（todo、generating、review）。
    add_take 必填 episode_id、shot_code、path、service，可选 model、mode、task_id、usage_ref、plan_snapshot、note。提供 task_id 时须提供实际 plan_snapshot。
    upsert_asset 必填 id、kind（character、location、prop、style、sound）、name，可选 description、looks、reference_voice、reference_views。looks 与 reference_voice 仅用于 character；reference_views 仅用于 location、prop、style。
    add_asset_version 必填 asset_id、path，可选 note、task_id。仅用于定妆概念迭代版本（v1→v2 互斥替换）或单图多视图整卡主图；严禁用 v1–v3 版本代替多视角三视图。
    upsert_look 必填 asset_id、look_id、name，可选 note、sheet_path、sheet_note、slots。用于为角色增量新增或更新造型，支持单图多视图整卡（sheet_path）与分张单视图组（slots）二选一或同时登记；省略 slots 时保留既有或填默认三项（front/side/back）。
    set_look_slot 必填 asset_id、look_id、slot_id，可选 name、path、note（path 为 null 可清空）。用于单视角槽位增量更新，新增自定义槽位须提供 name。
    set_asset_view 必填 asset_id、view_id，可选 name、path、note、remove（布尔）。用于为 location、prop、style 资产增量增删或更新自由命名单视图组。
    set_asset_status 必填 asset_id、status（draft、review）。
    set_stage 必填 stage（script、visual_dev、storyboard、animatic、generation、sound、edit）、status（not_started、in_progress、skipped），可选 note。
    set_bible 必填 documents。
    set_video_settings 可选 default_video_channel_id、reference_input_mode（sheet 或 separate，至少提供一项，传 null 跟随通道默认）。
    set_experimental 与 set_preview_enabled 各必填布尔值 enabled。
    remove 必填 target（season、episode、scene、shot、asset）、非空且无重复的 ids 字符串数组。target 为 scene 或 shot 时还必填 episode_id。
    mode 为 text、first_frame、last_frame、first_last、reference。preview 为 keyframe、draft_clip、text_card 或 null。路径保存为工作区相对路径，可使用同一会话文件工具给出的工作区虚拟路径。
    ${productionNestedFields.filterKeys { it !in setOf("ids", "prop_asset_ids") }.entries.joinToString("\n    ") { (key, format) -> "$key 用 $format。" }}
    prop_asset_ids 为资产 ID 字符串数组。可选顶层字段 reason（最多 300 字）用于在本次批量操作涉及已锁定剧本、视觉资产、镜头或制作阶段时向用户说明修改原因，系统将弹出审批卡交由用户决定是否放行（涉及视觉参考图、剧本文件或增删重排镜头在批准后自动退回待审；锁定场修改镜头分镜内容仍产生待审变更请求，此时务必填写简明中文 change_note 说明原因；若角色与造型未变、仅调整 appearances.referenceSlotIds 则直接生效）。收到审核回合后逐条处理，用 agent_note、change_note 或资产 description 写明结果。用户改写的提示词受保护。
""".trimIndent()

private val productionUpdateSchema by lazy { buildJsonObject {
    put("type", "object")
    put("required", schemaStrings(listOf("operations")))
    put("additionalProperties", false)
    putJsonObject("properties") {
        putJsonObject("reason") {
            put("type", schemaStrings(listOf("string", "null")))
            put("description", "可选。当本次操作需要修改或移除已锁定制作项时向用户展示的原因说明（最多 300 字）。")
        }
        putJsonObject("operations") {
            put("type", "array")
            put("description", "按依赖顺序排列的 1～100 项原子操作。各操作的必填字段见工具说明。")
            putJsonObject("items") {
                put("type", "object")
                put("required", schemaStrings(listOf("op")))
                putJsonObject("properties") {
                    putJsonObject("op") {
                        put("type", "string")
                        put("enum", schemaStrings(productionOperationFields.keys))
                        put("description", "精确的制作操作名称。")
                    }
                    val keys = productionOperationFields.values.flatMap { it.required + it.optional }.distinct()
                    keys.forEach { key -> put(key, productionFieldSchema(key)) }
                }
            }
        }
    }
}.toString() }

private fun schemaStrings(values: Collection<String>) = JsonArray(values.map(::JsonPrimitive))

private fun productionFieldSchema(key: String): JsonObject = buildJsonObject {
    val type = when (key) {
        "number", "position", "spec_width", "spec_height" -> "integer"
        "duration_seconds" -> "number"
        "enabled", "remove" -> "boolean"
        "plan_snapshot", "reference_voice" -> "object"
        in productionNestedFields -> "array"
        else -> "string"
    }
    val nullable = key in nullableProductionOperationFields
    if (nullable) put("type", schemaStrings(listOf(type, "null"))) else put("type", type)
    if (type == "array") putJsonObject("items") {
        put("type", if (key in setOf("ids", "prop_asset_ids")) "string" else "object")
    }
    val choices = when (key) {
        "mode" -> productionModes
        "preview" -> productionPreviews
        "kind" -> productionAssetKinds
        "stage" -> productionStages
        "status" -> (productionShotStatuses + productionAssetStatuses + productionStageStatuses).distinct()
        "target" -> productionRemoveTargets
        "reference_input_mode" -> productionReferenceInputModes
        else -> emptyList()
    }
    if (choices.isNotEmpty()) put("enum", JsonArray(choices.map(::JsonPrimitive) + if (nullable) listOf(JsonNull) else emptyList()))
    put("description", productionNestedFields[key] ?: productionFieldDescriptions.getValue(key))
}

private val productionFieldDescriptions = mapOf(
    "id" to "当前操作要新增或更新的季、集或资产标识。", "season_id" to "所属季，如 S1。",
    "episode_id" to "所属集，如 S1E01。", "scene_code" to "所属场，如 SC01。",
    "shot_code" to "既有镜头编号，如 SC01-01。", "asset_id" to "既有资产的小写标识。",
    "look_id" to "角色造型的标识，如 coat。", "slot_id" to "造型参考位标识（默认 front、side、back 或自定义标识）。",
    "view_id" to "场景/道具/画风资产的自由参考视角标识。",
    "code" to "场或镜头的编号。", "number" to "从 1 起算的序号。", "title" to "季、集或镜头标题。",
    "heading" to "场标题，通常包含内外景、地点与时间。", "script_path" to "剧本文档的工作区路径。",
    "previs_path" to "3D 白模预演 DSL 文件的工作区路径；null 可清空。",
    "duration_seconds" to "镜头时长，单位秒。", "size" to "镜头景别。", "camera" to "机位或拍摄角度。",
    "camera_movement" to "摄影机运动。", "visual" to "画面内容。", "performance" to "表演说明。",
    "sound" to "声音设计。", "transition" to "镜头之间的转场。", "continuity" to "连续性要求。",
    "location_asset_id" to "地点资产的标识。", "channel_id" to "生成通道标识。",
    "spec_resolution" to "单镜头输出分辨率规格（如 1080p、720p）；null 跟随通道默认。",
    "spec_width" to "单镜头自定义像素宽度（ComfyUI 工作流生效）；null 跟随工作流默认。",
    "spec_height" to "单镜头自定义像素高度（ComfyUI 工作流生效）；null 跟随工作流默认。",
    "default_video_channel_id" to "项目默认视频通道标识；null 表示跟随全局默认。",
    "reference_input_mode" to "参考资产入参模式：sheet（多视图资产卡整图）或 separate（分张单视图组）；null 跟随通道推荐。",
    "mode" to "镜头生成模式。", "prompt" to "镜头生成提示词，最多 7000 字。",
    "preview" to "预览类别。", "preview_path" to "预览文件的工作区路径。",
    "sheet_path" to "造型单图多视图资产卡的工作区路径；null 可清空。",
    "sheet_note" to "造型多视图整卡说明；null 可清空。",
    "agent_note" to "智能体处理结果。", "change_note" to "锁定场修改镜头内容时的简明中文修改原因（将前置展示在用户审批卡上，发起锁定场内容改动时务必填写）。", "position" to "场内位置，从 0 起算。",
    "status" to "状态，具体允许取值由 op 决定。", "kind" to "资产类别。", "name" to "资产、造型或视角显示名称。",
    "description" to "资产说明。", "path" to "候选、资产版本或参考视图的工作区媒体路径。",
    "service" to "候选的生成服务名称。", "model" to "候选使用的模型。", "task_id" to "对应生成任务标识。",
    "usage_ref" to "生成计费记录的引用。", "note" to "候选、资产版本、造型、视角或阶段的说明。",
    "stage" to "制作阶段。", "enabled" to "启用或关闭对应开关。", "target" to "要移除的实体类别。",
    "remove" to "为 true 时移除对应参考视图。",
)
