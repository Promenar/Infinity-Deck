package com.promenar.infinity.data.production

import java.util.UUID
import kotlinx.serialization.json.*

/** 批量操作只创建新快照；媒体存在性由调用方在进入引擎前提供。 */
object ProductionEngine {
    fun update(snapshot: ProductionSnapshot, operations: List<JsonObject>, seenRevision: Long?, now: Long, isMedia: (String) -> Boolean): ProductionSnapshot {
        require(operations.size in 1..100) { "operations 需为 1～100 项。" }
        require(snapshot.revision < Long.MAX_VALUE) { "项目修订号已达到上限。" }
        val rev = snapshot.revision + 1
        var p = snapshot.production
        val baseline = seenRevision ?: 0L
        fun check(source: ChangeSource, changed: Long, name: String) {
            require(source != ChangeSource.USER || changed <= baseline) { "$name 在读取后由用户修改，请先调用 production_get，再依据用户决定修改。" }
        }
        fun episode(id: String): Episode = p.seasons.flatMap { it.episodes }.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("集 $id 不存在，请先创建该集。")
        fun scene(ep: Episode, code: String): Scene = ep.scenes.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("场 $code 不存在，请先创建该场。")
        fun shot(ep: Episode, code: String): Shot = ep.scenes.flatMap { it.shots }.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("镜头 $code 不存在，请先创建该镜头。")
        fun asset(id: String): Asset = p.assets.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("资产 $id 不存在，请先创建该资产。")
        fun setEpisode(value: Episode) { p = p.copy(seasons = p.seasons.map { s -> s.copy(episodes = s.episodes.map { if (it.id == value.id) value else it }) }) }
        fun setScene(ep: Episode, value: Scene) = setEpisode(ep.copy(scenes = ep.scenes.map { if (it.code == value.code) value else it }))
        fun setShot(ep: Episode, value: Shot) { val sc = ep.scenes.first { it.shots.any { s -> s.code == value.code } }; setScene(ep, sc.copy(shots = sc.shots.map { if (it.code == value.code) value else it })) }
        fun setAsset(value: Asset) { p = p.copy(assets = p.assets.map { if (it.id == value.id) value else it }) }
        fun checkShot(s: Shot) = check(s.changedBy, s.changedAt, "镜头 ${s.code}")
        fun checkScene(s: Scene) = check(s.changedBy, s.changedAt, "场 ${s.code}")
        fun checkEpisode(e: Episode) = check(e.changedBy, e.changedAt, "集 ${e.id}")
        fun checkAsset(a: Asset) = check(a.changedBy, a.changedAt, "资产 ${a.id}")
        fun checkTree(e: Episode) { checkEpisode(e); require(!e.scriptLocked) { "集 ${e.id} 的剧本已锁定，请先由用户解锁。" }; e.scenes.forEach { sc -> checkScene(sc); require(!sc.storyboardLocked) { "场 ${sc.code} 已锁定，请先由用户解锁。" }; sc.shots.forEach { checkShot(it); require(it.status != ShotStatus.LOCKED) { "镜头 ${it.code} 已锁定，请先由用户解锁。" } } } }
        fun text(o: JsonObject, key: String): String = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$key 必须是字符串，请检查参数。")
        fun optional(o: JsonObject, key: String, old: String?): String? = if (key !in o) old else if (o[key] == JsonNull) null else text(o, key)
        fun int(o: JsonObject, key: String): Int = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw IllegalArgumentException("$key 必须是整数，请检查参数。")
        fun number(o: JsonObject, key: String): Double = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("$key 必须是有限数值，请检查参数。")
        fun bool(o: JsonObject, key: String): Boolean = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw IllegalArgumentException("$key 必须是布尔值，请检查参数。")
        fun media(path: String) { ProductionManifestCodec.validatePath(path); require(isMedia(path)) { "媒体 $path 不存在或不是图片、视频或音频，请检查工作区路径。" } }
        fun enumText(value: String, allowed: List<String>, field: String): Int = allowed.indexOf(value).also { require(it >= 0) { "$field 仅支持 ${allowed.joinToString()}，请修正参数。" } }
        fun mode(o: JsonObject, key: String): ShotMode = ShotMode.entries[enumText(text(o, key), listOf("text", "first_frame", "first_last", "reference"), key)]
        operations.forEachIndexed { index, op ->
            try {
                when (text(op, "op")) {
                    "upsert_season" -> {
                        val id = text(op, "id"); val old = p.seasons.firstOrNull { it.id == id }
                        val updated = (old ?: Season(id, int(op, "number"))).copy(number = int(op, "number"), title = optional(op, "title", old?.title))
                        p = p.copy(seasons = if (old == null) p.seasons + updated else p.seasons.map { if (it.id == id) updated else it })
                    }
                    "upsert_episode" -> {
                        val sid = text(op, "season_id"); val id = text(op, "id"); val season = p.seasons.firstOrNull { it.id == sid } ?: throw IllegalArgumentException("季 $sid 不存在，请先创建该季。")
                        val existing = p.seasons.flatMap { it.episodes }.firstOrNull { it.id == id }
                        require(existing == null || season.episodes.any { it.id == id }) { "集 $id 已属于其他季。" }
                        existing?.let { checkEpisode(it); require(!it.scriptLocked || "script_path" !in op || optional(op, "script_path", it.scriptPath) == it.scriptPath) { "剧本已锁定，请先由用户解锁。" } }
                        val e = (existing ?: Episode(id, int(op, "number"), text(op, "title"))).copy(number = int(op, "number"), title = text(op, "title"), scriptPath = optional(op, "script_path", existing?.scriptPath), changedBy = existing?.changedBy ?: ChangeSource.AGENT, changedAt = if ("script_path" in op) rev else if (existing?.changedBy == ChangeSource.USER) existing?.changedAt ?: rev else rev, contentChangedAt = if ("script_path" in op) rev else existing?.contentChangedAt)
                        p = p.copy(seasons = p.seasons.map { if (it.id != sid) it else it.copy(episodes = if (existing == null) it.episodes + e else it.episodes.map { item -> if (item.id == id) e else item }) })
                    }
                    "upsert_scene" -> {
                        val ep = episode(text(op, "episode_id")); val code = text(op, "code"); val old = ep.scenes.firstOrNull { it.code == code }
                        old?.let { checkScene(it) }
                        val sc = (old ?: Scene(code, int(op, "number"), text(op, "heading"))).copy(number = int(op, "number"), heading = text(op, "heading"), changedBy = old?.changedBy ?: ChangeSource.AGENT, changedAt = if (old?.changedBy == ChangeSource.USER) old?.changedAt ?: rev else rev)
                        setEpisode(ep.copy(scenes = if (old == null) ep.scenes + sc else ep.scenes.map { if (it.code == code) sc else it }))
                    }
                    "upsert_shot" -> {
                        val ep = episode(text(op, "episode_id")); val sc = scene(ep, text(op, "scene_code")); val code = text(op, "code"); val existing = ep.scenes.flatMap { it.shots }.firstOrNull { it.code == code }
                        require(existing == null || sc.shots.any { it.code == code }) { "镜头 $code 已属于其他场。" }
                        existing?.let(::checkShot)
                        val position = if ("position" in op) int(op, "position") else null
                        if (existing == null || position != null && position != sc.shots.indexOf(existing)) checkScene(sc)
                        require(!sc.storyboardLocked || existing != null && (position == null || position == sc.shots.indexOf(existing))) { "场 ${sc.code} 的分镜已锁定，请由用户解锁后增删或重排。" }
                        val old = existing ?: Shot(code, text(op, "title"), durationSeconds = number(op, "duration_seconds"))
                        val inputs = if ("inputs" in op) try { Json.decodeFromJsonElement<List<ShotInput>>(op.getValue("inputs")) } catch (e: Exception) { throw IllegalArgumentException("inputs 格式无效，请检查输入角色和路径。", e) } else old.inputs
                        if ("inputs" in op) inputs.forEach { media(it.path) }
                        val preview = if ("preview" !in op) old.preview else if (op["preview"] == JsonNull) null else PreviewKind.entries[enumText(text(op, "preview"), listOf("keyframe", "draft_clip", "text_card"), "preview")]
                        val updated = old.copy(title = if ("title" in op) text(op, "title") else old.title, size = optional(op, "size", old.size), durationSeconds = if ("duration_seconds" in op) number(op, "duration_seconds") else old.durationSeconds, mode = if ("mode" in op) mode(op, "mode") else old.mode, prompt = if ("prompt" in op && !old.promptUserEdited) text(op, "prompt") else old.prompt, inputs = inputs, preview = preview, previewPath = optional(op, "preview_path", old.previewPath), agentNote = optional(op, "agent_note", old.agentNote),
                            camera = optional(op, "camera", old.camera), cameraMovement = optional(op, "camera_movement", old.cameraMovement),
                            visual = optional(op, "visual", old.visual), performance = optional(op, "performance", old.performance),
                            dialogue = if ("dialogue" in op) decodeField(op, "dialogue") else old.dialogue,
                            sound = optional(op, "sound", old.sound), transition = optional(op, "transition", old.transition), continuity = optional(op, "continuity", old.continuity),
                            appearances = if ("appearances" in op) decodeField(op, "appearances") else old.appearances,
                            locationAssetId = optional(op, "location_asset_id", old.locationAssetId),
                            propAssetIds = if ("prop_asset_ids" in op) decodeField(op, "prop_asset_ids") else old.propAssetIds,
                            channelId = optional(op, "channel_id", old.channelId), changedBy = old.changedBy, changedAt = if (old.changedBy == ChangeSource.USER) old.changedAt else rev)
                        val changes = ProductionRules.changes(old, updated)
                        val stored = if (sc.storyboardLocked && changes.isNotEmpty()) {
                            val id = UUID.nameUUIDFromBytes("${p.id}:$rev:$index:change".toByteArray(Charsets.UTF_8)).toString()
                            p = p.copy(changeRequests = p.changeRequests + ShotChangeRequest(id, ep.id, sc.code, code, changes, ChangeSource.AGENT, optional(op, "change_note", null), changedAt = rev))
                            ProductionRules.applyChanges(updated, changes.mapValues { (_, change) -> ShotFieldChange(change.newValue, change.oldValue) })
                        } else updated
                        val remaining = sc.shots.filterNot { it.code == code }.toMutableList()
                        val target = position ?: if (existing == null) remaining.size else sc.shots.indexOf(existing)
                        require(target in 0..remaining.size) { "position 超出场内范围，请使用 0～${remaining.size}。" }
                        remaining.add(target, stored); setScene(ep, sc.copy(shots = remaining))
                    }
                    "set_shot_status" -> {
                        val ep = episode(text(op, "episode_id")); val old = shot(ep, text(op, "shot_code")); checkShot(old)
                        require(old.status != ShotStatus.LOCKED) { "镜头 ${old.code} 已锁定，请先由用户解锁。" }
                        val status = listOf(ShotStatus.TODO, ShotStatus.GENERATING, ShotStatus.REVIEW)[enumText(text(op, "status"), listOf("todo", "generating", "review"), "status")]
                        setShot(ep, old.copy(status = status, changedBy = old.changedBy, changedAt = if (old.changedBy == ChangeSource.USER) old.changedAt else rev))
                    }
                    "add_take" -> {
                        val ep = episode(text(op, "episode_id")); val old = shot(ep, text(op, "shot_code")); checkShot(old); ProductionRules.generationRejection(p, ep.id, old.code)?.let { throw IllegalArgumentException(it) }; val path = text(op, "path"); media(path)
                        require("task_id" !in op || op["task_id"] == JsonNull || "plan_snapshot" in op) { "生成任务登记候选时必须提供实际规划快照。" }
                        val id = UUID.nameUUIDFromBytes("${p.id}:$rev:$index".toByteArray(Charsets.UTF_8)).toString()
                        val take = Take(id, path, TakeSource(text(op, "service"), optional(op, "model", null), if ("mode" in op && op["mode"] != JsonNull) mode(op, "mode") else null, optional(op, "usage_ref", null)), now, optional(op, "note", null), taskId = optional(op, "task_id", null), planSnapshot = if ("plan_snapshot" in op) decodeField(op, "plan_snapshot") else ProductionRules.planSnapshot(old))
                        setShot(ep, old.copy(takes = old.takes + take, status = if (old.status == ShotStatus.LOCKED) old.status else ShotStatus.REVIEW, changedBy = old.changedBy, changedAt = if (old.changedBy == ChangeSource.USER) old.changedAt else rev))
                    }
                    "upsert_asset" -> {
                        val id = text(op, "id"); val old = p.assets.firstOrNull { it.id == id }; old?.let(::checkAsset)
                        val kind = AssetKind.entries[enumText(text(op, "kind"), listOf("character", "location", "prop", "style", "sound"), "kind")]
                        require(old == null || old.status != AssetStatus.LOCKED || old.kind == kind && old.name == text(op, "name")) { "资产 $id 已锁定，请先由用户解锁后修改名称或种类。" }
                        val a = (old ?: Asset(id, kind, text(op, "name"))).copy(kind = kind, name = text(op, "name"), description = optional(op, "description", old?.description), looks = if ("looks" in op) decodeField(op, "looks") else old?.looks ?: emptyList(), referenceVoice = if ("reference_voice" in op) decodeField(op, "reference_voice") else old?.referenceVoice, changedBy = old?.changedBy ?: ChangeSource.AGENT, changedAt = if (old?.changedBy == ChangeSource.USER) old?.changedAt ?: rev else rev)
                        if ("looks" in op) a.looks.flatMap { it.slots }.mapNotNull { it.path }.forEach(::media)
                        if ("reference_voice" in op) a.referenceVoice?.path?.let(::media)
                        p = p.copy(assets = if (old == null) p.assets + a else p.assets.map { if (it.id == id) a else it })
                    }
                    "add_asset_version" -> {
                        val old = asset(text(op, "asset_id")); checkAsset(old); val path = text(op, "path"); media(path)
                        val number = (old.versions.maxOfOrNull { it.number } ?: 0) + 1
                        setAsset(old.copy(versions = old.versions + AssetVersion(number, path, now, optional(op, "note", null), taskId = optional(op, "task_id", null)), currentVersion = if (old.status == AssetStatus.LOCKED) old.currentVersion else number, status = if (old.status == AssetStatus.LOCKED) old.status else AssetStatus.REVIEW, changedBy = old.changedBy, changedAt = if (old.changedBy == ChangeSource.USER) old.changedAt else rev))
                    }
                    "set_asset_status" -> {
                        val old = asset(text(op, "asset_id")); checkAsset(old); require(old.status != AssetStatus.LOCKED) { "资产 ${old.id} 已锁定，请先由用户解锁。" }
                        val status = listOf(AssetStatus.DRAFT, AssetStatus.REVIEW)[enumText(text(op, "status"), listOf("draft", "review"), "status")]
                        setAsset(old.copy(status = status, changedBy = old.changedBy, changedAt = if (old.changedBy == ChangeSource.USER) old.changedAt else rev))
                    }
                    "set_stage" -> {
                        val stage = ProductionStage.entries[enumText(text(op, "stage"), listOf("script", "visual_dev", "storyboard", "animatic", "generation", "sound", "edit"), "stage")]
                        val old = p.stages.first { it.stage == stage }; check(old.changedBy, old.changedAt, "阶段 $stage"); require(old.status != StageStatus.LOCKED) { "阶段已锁定，请先由用户解锁。" }
                        val status = listOf(StageStatus.NOT_STARTED, StageStatus.IN_PROGRESS, StageStatus.SKIPPED)[enumText(text(op, "status"), listOf("not_started", "in_progress", "skipped"), "status")]
                        p = p.copy(stages = p.stages.map { if (it.stage == stage) it.copy(status = status, note = optional(op, "note", it.note), changedBy = it.changedBy, changedAt = if (it.changedBy == ChangeSource.USER) it.changedAt else rev) else it })
                    }
                    "set_bible" -> {
                        val docs = try { Json.decodeFromJsonElement<List<DocumentRef>>(op.getValue("documents")) } catch (e: Exception) { throw IllegalArgumentException("documents 必须是 title 和 path 组成的数组。", e) }
                        p = p.copy(bible = docs)
                    }
                    "set_experimental" -> {
                        check(p.policyChangedBy, p.policyChangedAt, "项目设置")
                        p = p.copy(experimental = bool(op, "enabled"), policyChangedAt = if (p.policyChangedBy == ChangeSource.USER) p.policyChangedAt else rev)
                    }
                    "set_preview_enabled" -> p = p.copy(previewEnabled = bool(op, "enabled"))
                    "remove" -> {
                        val target = text(op, "target"); val ids = (op["ids"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content ?: throw IllegalArgumentException("ids 必须是字符串数组。") } ?: throw IllegalArgumentException("ids 必须是字符串数组。")
                        require(ids.isNotEmpty() && ids.distinct().size == ids.size) { "ids 不能为空或重复。" }
                        when (target) {
                            "season" -> { ids.forEach { id -> val s = p.seasons.firstOrNull { it.id == id } ?: throw IllegalArgumentException("季 $id 不存在。") ; s.episodes.forEach(::checkTree) }; p = p.copy(seasons = p.seasons.filterNot { it.id in ids }) }
                            "episode" -> { ids.forEach { checkTree(episode(it)) }; p = p.copy(seasons = p.seasons.map { it.copy(episodes = it.episodes.filterNot { e -> e.id in ids }) }) }
                            "scene" -> { val ep = episode(text(op, "episode_id")); ids.forEach { id -> val sc = scene(ep, id); checkScene(sc); require(!sc.storyboardLocked) { "场 $id 已锁定，请先由用户解锁。" }; sc.shots.forEach { checkShot(it); require(it.status != ShotStatus.LOCKED) { "镜头 ${it.code} 已锁定，请先由用户解锁。" } } }; setEpisode(ep.copy(scenes = ep.scenes.filterNot { it.code in ids })) }
                            "shot" -> { val ep = episode(text(op, "episode_id")); ids.forEach { id -> val item = shot(ep, id); checkShot(item); val sc = ep.scenes.first { it.shots.any { s -> s.code == id } }; checkScene(sc); require(!sc.storyboardLocked && item.status != ShotStatus.LOCKED) { "场或镜头已锁定，请先由用户解锁。" } }; setEpisode(ep.copy(scenes = ep.scenes.map { it.copy(shots = it.shots.filterNot { s -> s.code in ids }) })) }
                            "asset" -> { ids.forEach { id -> val a = asset(id); checkAsset(a); require(a.status != AssetStatus.LOCKED) { "资产 $id 已锁定，请先由用户解锁。" } }; p = p.copy(assets = p.assets.filterNot { it.id in ids }) }
                            else -> throw IllegalArgumentException("target 仅支持 season、episode、scene、shot、asset。")
                        }
                    }
                    else -> throw IllegalArgumentException("未知 op，请使用制作面板支持的操作。")
                }
            } catch (e: IllegalArgumentException) { throw IllegalArgumentException("第 ${index + 1} 项：${e.message}", e) }
        }
        p = stampProductionContent(snapshot.production, p, rev).pruneDeletedReviewTargets().copy(updatedAt = now)
        ProductionManifestCodec.encode(p, rev)
        return ProductionSnapshot(p, rev, ManifestSync.Pending)
    }
}

private inline fun <reified T> decodeField(op: JsonObject, key: String): T = try {
    Json.decodeFromJsonElement<T>(op.getValue(key))
} catch (e: Exception) { throw IllegalArgumentException("$key 格式无效，请检查字段。", e) }
