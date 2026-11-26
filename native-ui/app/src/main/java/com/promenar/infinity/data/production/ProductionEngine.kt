package com.promenar.infinity.data.production

import java.util.UUID
import kotlinx.serialization.json.*

/** 引擎批量更新结果：除新快照外携带非致命提醒（如受保护的用户改写提示词未覆盖）。 */
internal data class ProductionUpdateResult(
    val snapshot: ProductionSnapshot,
    val warnings: List<String> = emptyList(),
)

/** 批量操作只创建新快照；媒体存在性由调用方在进入引擎前提供。 */
object ProductionEngine {
    internal fun promptProtectedWarning(shotCode: String): String =
        "镜头 $shotCode 的提示词已由用户手动编辑锁定，本次未覆盖 prompt。"

    /** 汇总本批操作中因用户手动编辑锁定提示词而未覆盖 prompt 的提醒。 */
    internal fun collectPromptProtectionWarnings(production: Production, operations: List<JsonElement>): List<String> =
        operations.mapNotNull { element ->
            val op = element as? JsonObject ?: return@mapNotNull null
            if ((op["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "upsert_shot") return@mapNotNull null
            val incomingPrompt = (op["prompt"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val episodeId = (op["episode_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val shotCode = (op["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val shot = production.seasons.flatMap { it.episodes }.firstOrNull { it.id == episodeId }
                ?.scenes?.flatMap { it.shots }?.firstOrNull { it.code == shotCode } ?: return@mapNotNull null
            if (shot.promptUserEdited && incomingPrompt != shot.prompt) promptProtectedWarning(shot.code) else null
        }.distinct()

    fun update(snapshot: ProductionSnapshot, operations: List<JsonElement>, seenRevision: Long?, now: Long, isMedia: (String) -> Boolean): ProductionSnapshot =
        updateWithWarnings(snapshot, operations, seenRevision, now, isMedia).snapshot

    internal fun updateWithWarnings(
        snapshot: ProductionSnapshot,
        operations: List<JsonElement>,
        seenRevision: Long?,
        now: Long,
        isMedia: (String) -> Boolean,
    ): ProductionUpdateResult =
        updateWithWarnings(snapshot, prepareProductionOperations(operations, snapshot.production) { it }, seenRevision, now, isMedia)

    internal fun update(
        snapshot: ProductionSnapshot,
        prepared: PreparedProductionOperations,
        seenRevision: Long?,
        now: Long,
        isMedia: (String) -> Boolean,
        lockAuthorization: ProductionLockPreflight? = null,
        scriptHashes: Map<String, String> = emptyMap(),
        scriptMtimes: Map<String, Long> = emptyMap(),
    ): ProductionSnapshot = updateWithWarnings(
        snapshot = snapshot,
        prepared = prepared,
        seenRevision = seenRevision,
        now = now,
        isMedia = isMedia,
        lockAuthorization = lockAuthorization,
        scriptHashes = scriptHashes,
        scriptMtimes = scriptMtimes,
    ).snapshot

    internal fun updateWithWarnings(
        snapshot: ProductionSnapshot,
        prepared: PreparedProductionOperations,
        seenRevision: Long?,
        now: Long,
        isMedia: (String) -> Boolean,
        lockAuthorization: ProductionLockPreflight? = null,
        scriptHashes: Map<String, String> = emptyMap(),
        scriptMtimes: Map<String, Long> = emptyMap(),
    ): ProductionUpdateResult {
        require(snapshot.revision < Long.MAX_VALUE) { "项目修订号已达到上限。" }
        val authorized = lockAuthorization != null
        val rev = snapshot.revision + 1
        var p = snapshot.production
        val warnings = mutableListOf<String>()
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
        fun checkTree(e: Episode) {
            checkEpisode(e)
            if (!authorized) require(!e.scriptLocked) { "集 ${e.id} 的剧本已锁定，请先由用户解锁。" }
            e.scenes.forEach { sc ->
                checkScene(sc)
                if (!authorized) require(!sc.storyboardLocked) { "场 ${sc.code} 已锁定，请先由用户解锁。" }
                sc.shots.forEach {
                    checkShot(it)
                    if (!authorized) require(it.status != ShotStatus.LOCKED) { "镜头 ${it.code} 已锁定，请先由用户解锁。" }
                }
            }
        }
        fun text(o: JsonObject, key: String): String = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$key 必须是字符串，请检查参数。")
        fun optional(o: JsonObject, key: String, old: String?): String? = if (key !in o) old else if (o[key] == JsonNull) null else text(o, key)
        fun int(o: JsonObject, key: String): Int = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw IllegalArgumentException("$key 必须是整数，请检查参数。")
        fun number(o: JsonObject, key: String): Double = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("$key 必须是有限数值，请检查参数。")
        fun bool(o: JsonObject, key: String): Boolean = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw IllegalArgumentException("$key 必须是布尔值，请检查参数。")
        fun media(path: String) { ProductionManifestCodec.validatePath(path); require(isMedia(path)) { "媒体 $path 不存在或不是图片、视频或音频，请检查工作区路径。" } }
        fun enumText(value: String, allowed: List<String>, field: String): Int = allowed.indexOf(value).also { require(it >= 0) { "$field 仅支持 ${allowed.joinToString()}，请修正参数。" } }
        fun mode(o: JsonObject, key: String): ShotMode = ShotMode.entries[enumText(text(o, key), productionModes, key)]
        prepared.operations.forEachIndexed { index, op ->
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
                        val scriptPath = optional(op, "script_path", existing?.scriptPath)
                        val previsPath = optional(op, "previs_path", existing?.previsPath)
                        val scriptChanged = existing != null && "script_path" in op && scriptPath != existing.scriptPath
                        existing?.let {
                            checkEpisode(it)
                            if (!authorized) require(!it.scriptLocked || !scriptChanged) { "剧本已锁定，请先由用户解锁。" }
                        }
                        val unlockScript = existing != null && existing.scriptLocked && scriptChanged
                        val nextScriptSha256 = scriptPath?.let(scriptHashes::get) ?: if (scriptChanged) null else existing?.scriptSha256
                        val e = (existing ?: Episode(id, int(op, "number"), text(op, "title"))).copy(
                            number = int(op, "number"),
                            title = text(op, "title"),
                            scriptPath = scriptPath,
                            previsPath = previsPath,
                            scriptSha256 = nextScriptSha256,
                            scriptLocked = if (unlockScript) false else existing?.scriptLocked ?: false,
                            changedBy = existing?.changedBy ?: ChangeSource.AGENT,
                            changedAt = if ("script_path" in op || "previs_path" in op) rev else if (existing?.changedBy == ChangeSource.USER) existing?.changedAt ?: rev else rev,
                            contentChangedAt = if ("script_path" in op) rev else existing?.contentChangedAt,
                        )
                        p = p.copy(seasons = p.seasons.map { if (it.id != sid) it else it.copy(episodes = if (existing == null) it.episodes + e else it.episodes.map { item -> if (item.id == id) e else item }) })
                        if (unlockScript) {
                            p = p.reopenNonQueuedReviewGroup(ReviewTarget.script(existing.id))
                        }
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
                        val structuralLockedChange = sc.storyboardLocked && (existing == null || position != null && position != sc.shots.indexOf(existing))
                        if (!authorized) require(!structuralLockedChange) { "场 ${sc.code} 的分镜已锁定，请由用户解锁后增删或重排。" }
                        val old = existing ?: Shot(code, text(op, "title"), durationSeconds = number(op, "duration_seconds"))
                        val inputs = if ("inputs" in op) decodeProductionField(op, "inputs") else old.inputs
                        if ("inputs" in op) inputs.forEach { media(it.path) }
                        val preview = if ("preview" !in op) old.preview else if (op["preview"] == JsonNull) null else PreviewKind.entries[enumText(text(op, "preview"), productionPreviews, "preview")]
                        val appearances = if ("appearances" !in op) old.appearances else {
                            val rawAppearances = op["appearances"] as? JsonArray
                            decodeProductionField<List<ShotAppearance>>(op, "appearances").mapIndexed { idx, app ->
                                val rawObj = rawAppearances?.getOrNull(idx) as? JsonObject
                                if (rawObj != null && "referenceSlotIds" !in rawObj) {
                                    val look = p.assets.firstOrNull { it.id == app.characterAssetId && it.kind == AssetKind.CHARACTER }?.looks?.firstOrNull { it.id == app.lookId }
                                    if (look != null) app.copy(referenceSlotIds = ProductionRules.defaultSlotsForAppearance(look)) else app
                                } else app
                            }
                        }
                        val incomingPrompt = if ("prompt" in op) text(op, "prompt") else null
                        if (incomingPrompt != null && old.promptUserEdited && incomingPrompt != old.prompt) {
                            warnings += promptProtectedWarning(old.code)
                        }
                        val updated = old.copy(title = if ("title" in op) text(op, "title") else old.title, size = optional(op, "size", old.size), durationSeconds = if ("duration_seconds" in op) number(op, "duration_seconds") else old.durationSeconds, mode = if ("mode" in op) mode(op, "mode") else old.mode, prompt = if (incomingPrompt != null && !old.promptUserEdited) incomingPrompt else old.prompt, inputs = inputs, preview = preview, previewPath = optional(op, "preview_path", old.previewPath), agentNote = optional(op, "agent_note", old.agentNote),
                            camera = optional(op, "camera", old.camera), cameraMovement = optional(op, "camera_movement", old.cameraMovement),
                            visual = optional(op, "visual", old.visual), performance = optional(op, "performance", old.performance),
                            dialogue = if ("dialogue" in op) decodeProductionField(op, "dialogue") else old.dialogue,
                            sound = optional(op, "sound", old.sound), transition = optional(op, "transition", old.transition), continuity = optional(op, "continuity", old.continuity),
                            appearances = appearances,
                            locationAssetId = optional(op, "location_asset_id", old.locationAssetId),
                            propAssetIds = if ("prop_asset_ids" in op) decodeProductionField(op, "prop_asset_ids") else old.propAssetIds,
                            channelId = optional(op, "channel_id", old.channelId),
                            specResolution = optional(op, "spec_resolution", old.specResolution),
                            specWidth = if ("spec_width" in op) op["spec_width"]?.takeUnless { it is JsonNull }?.let { (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull?.takeIf { n -> n >= 1 } ?: throw IllegalArgumentException("spec_width 须为正整数。") } else old.specWidth,
                            specHeight = if ("spec_height" in op) op["spec_height"]?.takeUnless { it is JsonNull }?.let { (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull?.takeIf { n -> n >= 1 } ?: throw IllegalArgumentException("spec_height 须为正整数。") } else old.specHeight,
                            changedBy = old.changedBy, changedAt = if (old.changedBy == ChangeSource.USER) old.changedAt else rev)
                        val changes = ProductionRules.changes(old, updated)
                        val stored = if (sc.storyboardLocked && !structuralLockedChange && changes.isNotEmpty()) {
                            val id = UUID.nameUUIDFromBytes("${p.id}:$rev:$index:change".toByteArray(Charsets.UTF_8)).toString()
                            p = p.copy(changeRequests = p.changeRequests + ShotChangeRequest(id, ep.id, sc.code, code, changes, ChangeSource.AGENT, optional(op, "change_note", null), changedAt = rev))
                            ProductionRules.applyChanges(updated, changes.mapValues { (_, change) -> ShotFieldChange(change.newValue, change.oldValue) })
                        } else updated
                        val remaining = sc.shots.filterNot { it.code == code }.toMutableList()
                        val target = position ?: if (existing == null) remaining.size else sc.shots.indexOf(existing)
                        require(target in 0..remaining.size) { "position 超出场内范围，请使用 0～${remaining.size}。" }
                        remaining.add(target, stored)
                        val nextScene = if (structuralLockedChange) sc.copy(storyboardLocked = false, shots = remaining) else sc.copy(shots = remaining)
                        setScene(ep, nextScene)
                        if (structuralLockedChange) p = p.reopenNonQueuedReviewGroup(ReviewTarget.scene(ep.id, sc.code))
                    }
                    "set_shot_status" -> {
                        val ep = episode(text(op, "episode_id")); val old = shot(ep, text(op, "shot_code")); checkShot(old)
                        if (!authorized) require(old.status != ShotStatus.LOCKED) { "镜头 ${old.code} 已锁定，请先由用户解锁。" }
                        val status = listOf(ShotStatus.TODO, ShotStatus.GENERATING, ShotStatus.REVIEW)[enumText(text(op, "status"), productionShotStatuses, "status")]
                        val wasLocked = old.status == ShotStatus.LOCKED
                        setShot(ep, old.copy(
                            status = status,
                            selectedTakeId = if (wasLocked) null else old.selectedTakeId,
                            changedBy = old.changedBy,
                            changedAt = if (old.changedBy == ChangeSource.USER) old.changedAt else rev,
                        ))
                        if (wasLocked) p = p.reopenNonQueuedReviewGroup(ReviewTarget.shot(ep.id, old.code))
                    }
                    "add_take" -> {
                        val ep = episode(text(op, "episode_id")); val old = shot(ep, text(op, "shot_code")); checkShot(old); ProductionRules.generationRejection(p, ep.id, old.code)?.let { throw IllegalArgumentException(it) }; val path = text(op, "path"); media(path)
                        require("task_id" !in op || op["task_id"] == JsonNull || "plan_snapshot" in op) { "生成任务登记候选时必须提供实际规划快照。" }
                        val id = UUID.nameUUIDFromBytes("${p.id}:$rev:$index".toByteArray(Charsets.UTF_8)).toString()
                        val take = Take(id, path, TakeSource(text(op, "service"), optional(op, "model", null), if ("mode" in op && op["mode"] != JsonNull) mode(op, "mode") else null, optional(op, "usage_ref", null)), now, optional(op, "note", null), taskId = optional(op, "task_id", null), planSnapshot = if ("plan_snapshot" in op) decodeProductionField(op, "plan_snapshot") else ProductionRules.planSnapshot(old))
                        setShot(ep, old.copy(takes = old.takes + take, status = if (old.status == ShotStatus.LOCKED) old.status else ShotStatus.REVIEW, changedBy = old.changedBy, changedAt = if (old.changedBy == ChangeSource.USER) old.changedAt else rev))
                    }
                    "upsert_asset" -> {
                        val id = text(op, "id"); val old = p.assets.firstOrNull { it.id == id }; old?.let(::checkAsset)
                        val kind = AssetKind.entries[enumText(text(op, "kind"), productionAssetKinds, "kind")]
                        val nextName = text(op, "name")
                        if (!authorized) {
                            require(old == null || old.status != AssetStatus.LOCKED || old.kind == kind && old.name == nextName) { "资产 $id 已锁定，请先由用户解锁后修改名称或种类。" }
                        }
                        val unlockedLookIds = mutableSetOf<String>()
                        val looks = if ("looks" in op) {
                            decodeProductionField<List<CharacterLook>>(op, "looks").map { newLook ->
                                val prev = old?.looks?.firstOrNull { it.id == newLook.id }
                                val visualChanged = prev != null && prev.locked &&
                                    (prev.sheetPath != newLook.sheetPath || prev.slots.map { it.id to it.path } != newLook.slots.map { it.id to it.path })
                                val textChanged = prev != null && prev.locked && !visualChanged &&
                                    (prev.name != newLook.name || prev.note != newLook.note || prev.sheetNote != newLook.sheetNote || prev.slots != newLook.slots)
                                if (!authorized) {
                                    require(prev == null || !prev.locked || (!visualChanged && !textChanged)) {
                                        "造型 ${prev?.name} 已锁定，请先由用户解锁。"
                                    }
                                }
                                if (visualChanged) unlockedLookIds += newLook.id
                                newLook.copy(locked = (prev?.locked ?: false) && !visualChanged, contentChangedAt = prev?.contentChangedAt)
                            }
                        } else old?.looks ?: emptyList()
                        var viewsVisualChanged = false
                        val referenceViews = if ("reference_views" in op) {
                            require(kind in setOf(AssetKind.LOCATION, AssetKind.PROP, AssetKind.STYLE)) { "reference_views 仅用于场景、道具与画风资产。" }
                            val incoming = decodeProductionField<List<ReferenceSlot>>(op, "reference_views")
                            if (old != null && old.viewsLocked && old.referenceViews != incoming) {
                                if (!authorized) throw IllegalArgumentException("资产参考视图组已锁定，请先由用户解锁。")
                                viewsVisualChanged = old.referenceViews.map { it.id to it.path } != incoming.map { it.id to it.path }
                            }
                            incoming
                        } else old?.referenceViews ?: emptyList()
                        if ("looks" in op) looks.forEach { look -> look.sheetPath?.let(::media); look.slots.mapNotNull { it.path }.forEach(::media) }
                        if ("reference_views" in op) referenceViews.mapNotNull { it.path }.forEach(::media)
                        if ("reference_voice" in op) decodeProductionField<ReferenceVoice?>(op, "reference_voice")?.path?.let(::media)
                        unlockedLookIds.forEach { lookId ->
                            p = p.reopenNonQueuedReviewGroup(ReviewTarget.assetLook(id, lookId))
                        }
                        if (viewsVisualChanged) {
                            p = p.reopenNonQueuedReviewGroup(ReviewTarget.assetViews(id))
                        }
                        val nextViewsLocked = (old?.viewsLocked ?: false) && !viewsVisualChanged
                        val hasNewVisualMedia = ("looks" in op && looks.any { it.sheetPath != null || it.slots.any { slot -> !slot.path.isNullOrBlank() } }) ||
                            ("reference_views" in op && referenceViews.any { !it.path.isNullOrBlank() })
                        val baseNextStatus = if (hasNewVisualMedia && (old?.status ?: AssetStatus.DRAFT) in setOf(AssetStatus.DRAFT, AssetStatus.REDO)) AssetStatus.REVIEW else old?.status ?: AssetStatus.DRAFT
                        val draftAsset = (old ?: Asset(id, kind, nextName)).copy(
                            kind = kind,
                            name = nextName,
                            description = optional(op, "description", old?.description),
                            looks = looks,
                            referenceVoice = if ("reference_voice" in op) decodeProductionField(op, "reference_voice") else old?.referenceVoice,
                            referenceViews = referenceViews,
                            viewsLocked = nextViewsLocked,
                            status = baseNextStatus,
                            changedBy = old?.changedBy ?: ChangeSource.AGENT,
                            changedAt = if (old?.changedBy == ChangeSource.USER) old?.changedAt ?: rev else rev,
                        )
                        val a = if (unlockedLookIds.isNotEmpty() || viewsVisualChanged) {
                            draftAsset.copy(status = p.deriveAssetStatusAfterLockChange(draftAsset))
                        } else draftAsset
                        p = p.copy(assets = if (old == null) p.assets + a else p.assets.map { if (it.id == id) a else it })
                    }
                    "add_asset_version" -> {
                        val old = asset(text(op, "asset_id")); checkAsset(old); val path = text(op, "path"); media(path)
                        val number = (old.versions.maxOfOrNull { it.number } ?: 0) + 1
                        val durationSeconds = (op["duration_seconds"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull
                        setAsset(old.copy(versions = old.versions + AssetVersion(number, path, now, optional(op, "note", null), taskId = optional(op, "task_id", null), durationSeconds = durationSeconds), currentVersion = if (old.status == AssetStatus.LOCKED) old.currentVersion else number, status = if (old.status == AssetStatus.LOCKED) old.status else AssetStatus.REVIEW, changedBy = old.changedBy, changedAt = if (old.changedBy == ChangeSource.USER) old.changedAt else rev))
                    }
                    "upsert_look" -> {
                        val old = asset(text(op, "asset_id")); checkAsset(old)
                        require(old.kind == AssetKind.CHARACTER) { "仅角色资产支持造型。" }
                        val lookId = text(op, "look_id")
                        val prev = old.looks.firstOrNull { it.id == lookId }
                        val sheetPath = optional(op, "sheet_path", prev?.sheetPath)
                        sheetPath?.let(::media)
                        val baseSlots = prev?.slots ?: defaultReferenceSlots()
                        val mergedSlots = if ("slots" !in op) baseSlots else {
                            val incoming = decodeProductionField<List<ReferenceSlot>>(op, "slots")
                            val byId = baseSlots.associateBy { it.id }.toMutableMap()
                            incoming.forEach { slot -> byId[slot.id] = slot }
                            val orderedDefaults = ProductionRules.defaultSlotOrder.map { id -> byId.getValue(id) }
                            val customIds = (baseSlots.map { it.id } + incoming.map { it.id }).distinct().filter { it !in ProductionRules.defaultSlotOrder }
                            orderedDefaults + customIds.map { id -> byId.getValue(id) }
                        }
                        mergedSlots.mapNotNull { it.path }.forEach(::media)
                        val nextName = text(op, "name")
                        val nextNote = optional(op, "note", prev?.note)
                        val nextSheetNote = optional(op, "sheet_note", prev?.sheetNote)
                        val visualChanged = prev != null && prev.locked &&
                            (prev.sheetPath != sheetPath || prev.slots.map { it.id to it.path } != mergedSlots.map { it.id to it.path })
                        val anyChanged = prev != null && (prev.name != nextName || prev.note != nextNote || prev.sheetNote != nextSheetNote || prev.sheetPath != sheetPath || prev.slots != mergedSlots)
                        if (!authorized && prev != null && prev.locked) {
                            require(!anyChanged) { "造型 ${prev.name} 已锁定，请先由用户解锁。" }
                        }
                        val updatedLook = (prev ?: CharacterLook(lookId, nextName)).copy(
                            name = nextName,
                            note = nextNote,
                            sheetPath = sheetPath,
                            sheetNote = nextSheetNote,
                            slots = mergedSlots,
                            locked = prev?.locked == true && !visualChanged,
                        )
                        val hasMedia = updatedLook.sheetPath != null || updatedLook.slots.any { !it.path.isNullOrBlank() }
                        val nextLooks = if (prev == null) old.looks + updatedLook else old.looks.map { if (it.id == lookId) updatedLook else it }
                        if (visualChanged) {
                            p = p.reopenNonQueuedReviewGroup(ReviewTarget.assetLook(old.id, lookId))
                        }
                        val candidateAsset = old.copy(looks = nextLooks)
                        val nextStatus = if (visualChanged) p.deriveAssetStatusAfterLockChange(candidateAsset)
                        else if (hasMedia && old.status in setOf(AssetStatus.DRAFT, AssetStatus.REDO)) AssetStatus.REVIEW else old.status
                        setAsset(candidateAsset.copy(status = nextStatus, changedBy = old.changedBy, changedAt = if (old.changedBy == ChangeSource.USER) old.changedAt else rev))
                    }
                    "set_look_slot" -> {
                        val old = asset(text(op, "asset_id")); checkAsset(old)
                        require(old.kind == AssetKind.CHARACTER) { "仅角色资产支持造型槽位。" }
                        val lookId = text(op, "look_id")
                        val prev = old.looks.firstOrNull { it.id == lookId } ?: throw IllegalArgumentException("造型 $lookId 不存在，请先用 upsert_look 创建。")
                        val slotId = text(op, "slot_id")
                        val existing = prev.slots.firstOrNull { it.id == slotId }
                        if (existing == null) require("name" in op && op["name"] != JsonNull) { "新增参考位 $slotId 须提供 name。" }
                        val defaultName = defaultReferenceSlots().firstOrNull { it.id == slotId }?.name ?: slotId
                        val nextName = if ("name" in op && op["name"] != JsonNull) text(op, "name") else existing?.name ?: defaultName
                        val nextPath = optional(op, "path", existing?.path)
                        nextPath?.let(::media)
                        val nextNote = optional(op, "note", existing?.note)
                        val visualChanged = prev.locked && (existing == null || existing.path != nextPath)
                        val anyChanged = existing == null || existing.name != nextName || existing.path != nextPath || existing.note != nextNote
                        if (!authorized && prev.locked) {
                            require(!anyChanged) { "造型 ${prev.name} 已锁定，请先由用户解锁。" }
                        }
                        val updatedSlot = (existing ?: ReferenceSlot(slotId, nextName)).copy(name = nextName, path = nextPath, note = nextNote)
                        val nextSlots = if (existing == null) prev.slots + updatedSlot else prev.slots.map { if (it.id == slotId) updatedSlot else it }
                        val updatedLook = prev.copy(slots = nextSlots, locked = prev.locked && !visualChanged)
                        val nextLooks = old.looks.map { if (it.id == lookId) updatedLook else it }
                        if (visualChanged) {
                            p = p.reopenNonQueuedReviewGroup(ReviewTarget.assetLook(old.id, lookId))
                        }
                        val candidateAsset = old.copy(looks = nextLooks)
                        val nextStatus = if (visualChanged) p.deriveAssetStatusAfterLockChange(candidateAsset)
                        else if (nextPath != null && old.status in setOf(AssetStatus.DRAFT, AssetStatus.REDO)) AssetStatus.REVIEW else old.status
                        setAsset(candidateAsset.copy(status = nextStatus, changedBy = old.changedBy, changedAt = if (old.changedBy == ChangeSource.USER) old.changedAt else rev))
                    }
                    "set_asset_view" -> {
                        val old = asset(text(op, "asset_id")); checkAsset(old)
                        require(old.kind in setOf(AssetKind.LOCATION, AssetKind.PROP, AssetKind.STYLE)) { "set_asset_view 仅用于场景、道具与画风资产。" }
                        val viewId = text(op, "view_id")
                        val removeView = "remove" in op && bool(op, "remove")
                        val existing = old.referenceViews.firstOrNull { it.id == viewId }
                        val visualChanged: Boolean
                        val anyChanged: Boolean
                        val nextViews = if (removeView) {
                            require(existing != null) { "参考视图 $viewId 不存在。" }
                            visualChanged = old.viewsLocked
                            anyChanged = true
                            old.referenceViews.filterNot { it.id == viewId }
                        } else {
                            if (existing == null) require("name" in op && op["name"] != JsonNull) { "新建参考视图 $viewId 须提供 name。" }
                            val nextName = if ("name" in op && op["name"] != JsonNull) text(op, "name") else existing!!.name
                            val nextPath = optional(op, "path", existing?.path)
                            nextPath?.let(::media)
                            val nextNote = optional(op, "note", existing?.note)
                            visualChanged = old.viewsLocked && (existing == null || existing.path != nextPath)
                            anyChanged = existing == null || existing.name != nextName || existing.path != nextPath || existing.note != nextNote
                            val updatedSlot = (existing ?: ReferenceSlot(viewId, nextName)).copy(name = nextName, path = nextPath, note = nextNote)
                            require(existing != null || old.referenceViews.size < 30) { "每个资产最多 30 个参考视图。" }
                            if (existing == null) old.referenceViews + updatedSlot else old.referenceViews.map { if (it.id == viewId) updatedSlot else it }
                        }
                        if (!authorized && old.viewsLocked) {
                            require(!anyChanged) { "资产参考视图组已锁定，请先由用户解锁。" }
                        }
                        val nextViewsLocked = old.viewsLocked && !visualChanged
                        if (visualChanged) {
                            p = p.reopenNonQueuedReviewGroup(ReviewTarget.assetViews(old.id))
                        }
                        val candidateAsset = old.copy(referenceViews = nextViews, viewsLocked = nextViewsLocked)
                        val addedImage = !removeView && nextViews.firstOrNull { it.id == viewId }?.path != null
                        val nextStatus = if (visualChanged) p.deriveAssetStatusAfterLockChange(candidateAsset)
                        else if (addedImage && old.status in setOf(AssetStatus.DRAFT, AssetStatus.REDO)) AssetStatus.REVIEW else old.status
                        setAsset(candidateAsset.copy(status = nextStatus, changedBy = old.changedBy, changedAt = if (old.changedBy == ChangeSource.USER) old.changedAt else rev))
                    }
                    "set_video_settings" -> {
                        check(p.policyChangedBy, p.policyChangedAt, "项目设置")
                        val nextChannel = optional(op, "default_video_channel_id", p.defaultVideoChannelId)
                        val nextMode = if ("reference_input_mode" !in op) p.referenceInputMode
                            else if (op["reference_input_mode"] == JsonNull) null
                            else ReferenceInputMode.entries[enumText(text(op, "reference_input_mode"), productionReferenceInputModes, "reference_input_mode")]
                        p = p.copy(defaultVideoChannelId = nextChannel, referenceInputMode = nextMode, policyChangedAt = if (p.policyChangedBy == ChangeSource.USER) p.policyChangedAt else rev)
                    }
                    "set_asset_status" -> {
                        val old = asset(text(op, "asset_id")); checkAsset(old)
                        if (!authorized) require(old.status != AssetStatus.LOCKED) { "资产 ${old.id} 已锁定，请先由用户解锁。" }
                        val status = listOf(AssetStatus.DRAFT, AssetStatus.REVIEW)[enumText(text(op, "status"), productionAssetStatuses, "status")]
                        val wasLocked = old.status == AssetStatus.LOCKED
                        if (wasLocked) p = p.reopenAssetToReview(old.id)
                        setAsset(old.copy(
                            status = status,
                            looks = if (wasLocked) old.looks.map { it.copy(locked = false) } else old.looks,
                            viewsLocked = if (wasLocked) false else old.viewsLocked,
                            changedBy = old.changedBy,
                            changedAt = if (old.changedBy == ChangeSource.USER) old.changedAt else rev,
                        ))
                    }
                    "set_stage" -> {
                        val stage = ProductionStage.entries[enumText(text(op, "stage"), productionStages, "stage")]
                        val old = p.stages.first { it.stage == stage }; check(old.changedBy, old.changedAt, "阶段 $stage")
                        if (!authorized) require(old.status != StageStatus.LOCKED) { "阶段已锁定，请先由用户解锁。" }
                        val status = listOf(StageStatus.NOT_STARTED, StageStatus.IN_PROGRESS, StageStatus.SKIPPED)[enumText(text(op, "status"), productionStageStatuses, "status")]
                        p = p.copy(stages = p.stages.map { if (it.stage == stage) it.copy(status = status, note = optional(op, "note", it.note), changedBy = it.changedBy, changedAt = if (it.changedBy == ChangeSource.USER) it.changedAt else rev) else it })
                    }
                    "set_bible" -> {
                        val docs = decodeProductionField<List<DocumentRef>>(op, "documents")
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
                            "scene" -> {
                                val ep = episode(text(op, "episode_id"))
                                ids.forEach { id ->
                                    val sc = scene(ep, id); checkScene(sc)
                                    if (!authorized) require(!sc.storyboardLocked) { "场 $id 已锁定，请先由用户解锁。" }
                                    sc.shots.forEach {
                                        checkShot(it)
                                        if (!authorized) require(it.status != ShotStatus.LOCKED) { "镜头 ${it.code} 已锁定，请先由用户解锁。" }
                                    }
                                }
                                setEpisode(ep.copy(scenes = ep.scenes.filterNot { it.code in ids }))
                            }
                            "shot" -> {
                                val ep = episode(text(op, "episode_id"))
                                ids.forEach { id ->
                                    val item = shot(ep, id); checkShot(item)
                                    val sc = ep.scenes.first { it.shots.any { s -> s.code == id } }; checkScene(sc)
                                    if (!authorized) require(!sc.storyboardLocked && item.status != ShotStatus.LOCKED) { "场或镜头已锁定，请先由用户解锁。" }
                                }
                                setEpisode(ep.copy(scenes = ep.scenes.map { it.copy(shots = it.shots.filterNot { s -> s.code in ids }) }))
                            }
                            "asset" -> {
                                ids.forEach { id ->
                                    val a = asset(id); checkAsset(a)
                                    if (!authorized) require(a.status != AssetStatus.LOCKED) { "资产 $id 已锁定，请先由用户解锁。" }
                                }
                                p = p.copy(assets = p.assets.filterNot { it.id in ids })
                            }
                            else -> throw IllegalArgumentException("target 仅支持 season、episode、scene、shot、asset。")
                        }
                    }
                    else -> throw IllegalArgumentException("未知 op，请使用制作面板支持的操作。")
                }
            } catch (e: IllegalArgumentException) { throw IllegalArgumentException("第 ${index + 1} 项（${text(op, "op")}）：${e.message}", e) }
        }
        if (scriptHashes.isNotEmpty()) {
            p = p.copy(seasons = p.seasons.map { season ->
                season.copy(episodes = season.episodes.map { episode ->
                    val path = episode.scriptPath ?: return@map episode
                    val diskHash = scriptHashes[path] ?: return@map episode
                    if (episode.scriptLocked) return@map episode
                    val oldHash = snapshot.production.reviewEpisode(episode.id)?.scriptSha256
                    val alreadyMarked = episode.contentChangedAt == rev
                    val hashChanged = oldHash != null && oldHash != diskHash
                    val mtimeUpdated = if (oldHash == null && !alreadyMarked) {
                        val sentRejectAt = latestSentScriptRejectTimestamp(snapshot.production, episode.id)
                        val diskMtime = scriptMtimes[path]
                        sentRejectAt != null && diskMtime != null && diskMtime > sentRejectAt
                    } else false
                    when {
                        !alreadyMarked && (hashChanged || mtimeUpdated) ->
                            episode.copy(scriptSha256 = diskHash, changedAt = rev, contentChangedAt = rev)
                        episode.scriptSha256 == null -> episode.copy(scriptSha256 = diskHash)
                        else -> episode
                    }
                })
            })
        }
        p = stampProductionContent(snapshot.production, p, rev)
            .reopenResolvedSentRejections()
            .pruneDeletedReviewTargets()
            .copy(updatedAt = now)
        ProductionManifestCodec.encode(p, rev)
        return ProductionUpdateResult(ProductionSnapshot(p, rev, ManifestSync.Pending), warnings.distinct())
    }
}

private fun latestSentScriptRejectTimestamp(production: Production, episodeId: String): Long? {
    val target = ReviewTarget.script(episodeId)
    val entry = production.reviews.lastOrNull {
        it.target == target && it.kind == ReviewKind.REJECT && it.round != null && it.queuedRound == null
    } ?: return null
    val round = production.reviewRounds.firstOrNull { it.number == entry.round }
    return entry.sentAt ?: round?.sentAt ?: entry.at
}
