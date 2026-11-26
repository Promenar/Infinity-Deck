package com.promenar.infinity.data.production

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** 批准已锁定制作项修改后的处理结果。 */
enum class ProductionLockOutcome {
    /** 仅改名称/种类/纯文字描述，批准写入后保持原锁定状态。 */
    KEEP_LOCKED,
    /** 涉及视觉参考图、剧本文件路径、增删重排镜头或解锁态切换，批准后自动退回待审。 */
    RETURN_TO_REVIEW,
    /** 从项目永久移除已锁定实体。 */
    PERMANENT_REMOVE,
}

/** 单个字段的旧值 → 新值差异；[oldValue] 为空表示新增，[newValue] 为空表示清空或删除。 */
data class ProductionLockFieldDiff(
    val label: String,
    val oldValue: String?,
    val newValue: String?,
)

/** 一项受锁定影响的操作摘要与差异明细。 */
data class ProductionLockedItemChange(
    val kindAndId: String,
    val title: String,
    val outcome: ProductionLockOutcome,
    val fields: List<ProductionLockFieldDiff> = emptyList(),
    val summary: String? = null,
    val referencedByLockedShots: Int = 0,
    val revertTarget: ReviewTarget? = null,
)

/** `production_update` 预检结果：当且仅当批内存在合法但受锁定保护的改动时生成。 */
data class ProductionLockPreflight(
    val productionId: String,
    val productionTitle: String,
    val revision: Long,
    val reason: String,
    val lockedItems: List<ProductionLockedItemChange>,
    val unlockedSummaries: List<String>,
    val authorizationTarget: String,
) {
    val showsVisualOrScriptRevertWarning: Boolean
        get() = lockedItems.any { it.outcome == ProductionLockOutcome.RETURN_TO_REVIEW && it.revertTarget != null }
}

/**
 * 为 `production_update` 计算结构化锁定项变更预检：
 * - 先遵循与 [ProductionEngine] 完全一致的非锁定校验与 [seenRevision] 冲突门禁；任何参数/读取错误返回 null，交由常规执行报错；
 * - 批内没有任何受锁定项受影响时返回 null，直接执行，不弹出审批卡；
 * - 受锁定项受影响时返回确定性 [ProductionLockPreflight] 及其 `production-lock:` 授权目标供审批卡展示与事务复核。
 */
object ProductionLockApprovalPlanner {
    const val TARGET_PREFIX = "production-lock:"
    const val REASON_MAX_LENGTH = 300
    private val LOCK_TARGET_REGEX = Regex("^production-lock:[^:]+:r([0-9]+):[0-9a-f]{24}$")

    fun isLockTarget(target: String?): Boolean = target?.startsWith(TARGET_PREFIX) == true

    /** 仅当 [target] 为合法 `production-lock:<id>:r<rev>:<hex>` 格式时解析其中修订号。 */
    fun parseRevision(target: String?): Long? =
        target?.let { LOCK_TARGET_REGEX.matchEntire(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }

    /** 从 `production_update` 工具参数解析可选 `reason` 字段；非法类型直接报错。 */
    fun extractReason(args: JsonObject): String {
        val raw = args["reason"] ?: return ""
        if (raw == JsonNull) return ""
        val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?: throw IllegalArgumentException("reason 必须是字符串。")
        require(text.length <= REASON_MAX_LENGTH) { "reason 最多 $REASON_MAX_LENGTH 字。" }
        return text
    }

    internal fun analyze(
        snapshot: ProductionSnapshot,
        prepared: PreparedProductionOperations,
        seenRevision: Long?,
        isMedia: (String) -> Boolean,
        reason: String = "",
    ): ProductionLockPreflight? {
        if (snapshot.revision >= Long.MAX_VALUE) return null
        val baseline = seenRevision ?: 0L
        val lockedItems = mutableListOf<ProductionLockedItemChange>()
        val unlockedSummaries = mutableListOf<String>()

        var p = snapshot.production
        val rev = snapshot.revision + 1

        fun stale(source: ChangeSource, changed: Long): Boolean = source == ChangeSource.USER && changed > baseline
        fun epOf(id: String): Episode? = p.seasons.flatMap { it.episodes }.firstOrNull { it.id == id }
        fun scOf(ep: Episode, code: String): Scene? = ep.scenes.firstOrNull { it.code == code }
        fun shOf(ep: Episode, code: String): Shot? = ep.scenes.flatMap { it.shots }.firstOrNull { it.code == code }
        fun asOf(id: String): Asset? = p.assets.firstOrNull { it.id == id }
        fun str(o: JsonObject, key: String): String = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$key invalid")
        fun opt(o: JsonObject, key: String, old: String?): String? = if (key !in o) old else if (o[key] == JsonNull) null else str(o, key)
        fun numInt(o: JsonObject, key: String): Int = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw IllegalArgumentException("$key invalid")
        fun numDouble(o: JsonObject, key: String): Double = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("$key invalid")
        fun flag(o: JsonObject, key: String): Boolean = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw IllegalArgumentException("$key invalid")
        fun requireMedia(path: String) {
            ProductionManifestCodec.validatePath(path)
            if (!isMedia(path)) throw IllegalArgumentException("missing media: $path")
        }
        fun setEp(value: Episode) {
            p = p.copy(seasons = p.seasons.map { s -> s.copy(episodes = s.episodes.map { if (it.id == value.id) value else it }) })
        }
        fun setSc(ep: Episode, value: Scene) = setEp(ep.copy(scenes = ep.scenes.map { if (it.code == value.code) value else it }))
        fun setSh(ep: Episode, value: Shot) {
            val sc = ep.scenes.first { it.shots.any { s -> s.code == value.code } }
            setSc(ep, sc.copy(shots = sc.shots.map { if (it.code == value.code) value else it }))
        }
        fun setAs(value: Asset) {
            p = p.copy(assets = p.assets.map { if (it.id == value.id) value else it })
        }

        val outcome = runCatching {
            prepared.operations.forEachIndexed { index, op ->
                when (val name = str(op, "op")) {
                    "upsert_season" -> {
                        val id = str(op, "id")
                        val old = p.seasons.firstOrNull { it.id == id }
                        val updated = (old ?: Season(id, numInt(op, "number"))).copy(number = numInt(op, "number"), title = opt(op, "title", old?.title))
                        p = p.copy(seasons = if (old == null) p.seasons + updated else p.seasons.map { if (it.id == id) updated else it })
                        unlockedSummaries += if (old == null) "新建季 $id" else "更新季 $id"
                    }
                    "upsert_episode" -> {
                        val sid = str(op, "season_id")
                        val id = str(op, "id")
                        val season = p.seasons.firstOrNull { it.id == sid } ?: throw IllegalArgumentException("missing season")
                        val existing = p.seasons.flatMap { it.episodes }.firstOrNull { it.id == id }
                        if (existing != null && season.episodes.none { it.id == id }) throw IllegalArgumentException("wrong season")
                        if (existing != null && stale(existing.changedBy, existing.changedAt)) throw IllegalArgumentException("stale")
                        val nextScriptPath = opt(op, "script_path", existing?.scriptPath)
                        val nextPrevisPath = opt(op, "previs_path", existing?.previsPath)
                        val nextNumber = numInt(op, "number")
                        val nextTitle = str(op, "title")
                        if (existing != null && existing.scriptLocked && "script_path" in op && nextScriptPath != existing.scriptPath) {
                            lockedItems += ProductionLockedItemChange(
                                kindAndId = "剧本 · ${existing.id}",
                                title = existing.title,
                                outcome = ProductionLockOutcome.RETURN_TO_REVIEW,
                                fields = listOf(
                                    ProductionLockFieldDiff("剧本文件", existing.scriptPath, nextScriptPath),
                                ),
                                revertTarget = ReviewTarget.script(existing.id),
                            )
                            val updated = existing.copy(
                                number = nextNumber,
                                title = nextTitle,
                                scriptPath = nextScriptPath,
                                previsPath = nextPrevisPath,
                                scriptLocked = false,
                                changedAt = rev,
                                contentChangedAt = rev,
                            )
                            p = p.copy(seasons = p.seasons.map { if (it.id != sid) it else it.copy(episodes = it.episodes.map { item -> if (item.id == id) updated else item }) })
                        } else {
                            val updated = (existing ?: Episode(id, nextNumber, nextTitle)).copy(
                                number = nextNumber,
                                title = nextTitle,
                                scriptPath = nextScriptPath,
                                previsPath = nextPrevisPath,
                                changedBy = existing?.changedBy ?: ChangeSource.AGENT,
                                changedAt = rev,
                                contentChangedAt = if ("script_path" in op) rev else existing?.contentChangedAt,
                            )
                            p = p.copy(seasons = p.seasons.map { if (it.id != sid) it else it.copy(episodes = if (existing == null) it.episodes + updated else it.episodes.map { item -> if (item.id == id) updated else item }) })
                            unlockedSummaries += if (existing == null) "新建集 $id" else "更新集 $id"
                        }
                    }
                    "upsert_scene" -> {
                        val ep = epOf(str(op, "episode_id")) ?: throw IllegalArgumentException("missing ep")
                        val code = str(op, "code")
                        val old = ep.scenes.firstOrNull { it.code == code }
                        if (old != null && stale(old.changedBy, old.changedAt)) throw IllegalArgumentException("stale")
                        val sc = (old ?: Scene(code, numInt(op, "number"), str(op, "heading"))).copy(
                            number = numInt(op, "number"),
                            heading = str(op, "heading"),
                            changedBy = old?.changedBy ?: ChangeSource.AGENT,
                            changedAt = rev,
                        )
                        setEp(ep.copy(scenes = if (old == null) ep.scenes + sc else ep.scenes.map { if (it.code == code) sc else it }))
                        unlockedSummaries += if (old == null) "新建场 $code" else "更新场 $code 场头"
                    }
                    "upsert_shot" -> {
                        val ep = epOf(str(op, "episode_id")) ?: throw IllegalArgumentException("missing ep")
                        val sc = scOf(ep, str(op, "scene_code")) ?: throw IllegalArgumentException("missing scene")
                        val code = str(op, "code")
                        val existing = ep.scenes.flatMap { it.shots }.firstOrNull { it.code == code }
                        if (existing != null && sc.shots.none { it.code == code }) throw IllegalArgumentException("wrong scene")
                        if (existing != null && stale(existing.changedBy, existing.changedAt)) throw IllegalArgumentException("stale")
                        val position = if ("position" in op) numInt(op, "position") else null
                        if ((existing == null || position != null && position != sc.shots.indexOf(existing)) && stale(sc.changedBy, sc.changedAt)) {
                            throw IllegalArgumentException("stale")
                        }
                        val old = existing ?: Shot(code, str(op, "title"), durationSeconds = numDouble(op, "duration_seconds"))
                        val inputs = if ("inputs" in op) decodeProductionField(op, "inputs") else old.inputs
                        if ("inputs" in op) inputs.forEach { requireMedia(it.path) }
                        val preview = if ("preview" !in op) old.preview else if (op["preview"] == JsonNull) null else PreviewKind.entries[enumIndex(str(op, "preview"), productionPreviews)]
                        val updated = old.copy(
                            title = if ("title" in op) str(op, "title") else old.title,
                            size = opt(op, "size", old.size),
                            durationSeconds = if ("duration_seconds" in op) numDouble(op, "duration_seconds") else old.durationSeconds,
                            mode = if ("mode" in op) ShotMode.entries[enumIndex(str(op, "mode"), productionModes)] else old.mode,
                            prompt = if ("prompt" in op && !old.promptUserEdited) str(op, "prompt") else old.prompt,
                            inputs = inputs,
                            preview = preview,
                            previewPath = opt(op, "preview_path", old.previewPath),
                            agentNote = opt(op, "agent_note", old.agentNote),
                            camera = opt(op, "camera", old.camera),
                            cameraMovement = opt(op, "camera_movement", old.cameraMovement),
                            visual = opt(op, "visual", old.visual),
                            performance = opt(op, "performance", old.performance),
                            dialogue = if ("dialogue" in op) decodeProductionField(op, "dialogue") else old.dialogue,
                            sound = opt(op, "sound", old.sound),
                            transition = opt(op, "transition", old.transition),
                            continuity = opt(op, "continuity", old.continuity),
                            appearances = if ("appearances" in op) decodeProductionField(op, "appearances") else old.appearances,
                            locationAssetId = opt(op, "location_asset_id", old.locationAssetId),
                            propAssetIds = if ("prop_asset_ids" in op) decodeProductionField(op, "prop_asset_ids") else old.propAssetIds,
                            channelId = opt(op, "channel_id", old.channelId),
                        )
                        val remaining = sc.shots.filterNot { it.code == code }.toMutableList()
                        val targetPos = position ?: if (existing == null) remaining.size else sc.shots.indexOf(existing)
                        if (targetPos !in 0..remaining.size) throw IllegalArgumentException("position out of bounds")
                        val needsSceneUnlock = sc.storyboardLocked && (existing == null || (position != null && position != sc.shots.indexOf(existing)))
                        if (needsSceneUnlock) {
                            val fields = buildList {
                                if (existing == null) {
                                    add(ProductionLockFieldDiff("新增镜头", null, "${updated.code} ${updated.title}"))
                                } else {
                                    add(ProductionLockFieldDiff("场内顺序", "第 ${sc.shots.indexOf(existing) + 1} 位", "第 ${targetPos + 1} 位"))
                                }
                            }
                            lockedItems += ProductionLockedItemChange(
                                kindAndId = "分镜 · ${sc.code}",
                                title = sc.heading,
                                outcome = ProductionLockOutcome.RETURN_TO_REVIEW,
                                fields = fields,
                                revertTarget = ReviewTarget.scene(ep.id, sc.code),
                            )
                            remaining.add(targetPos, updated)
                            setEp(ep.copy(scenes = ep.scenes.map { if (it.code == sc.code) sc.copy(storyboardLocked = false, shots = remaining) else it }))
                        } else {
                            remaining.add(targetPos, updated)
                            setSc(ep, sc.copy(shots = remaining))
                            unlockedSummaries += if (existing == null) "新建镜头 ${updated.code}" else "更新 ${updated.code} 镜头内容"
                        }
                    }
                    "set_shot_status" -> {
                        val ep = epOf(str(op, "episode_id")) ?: throw IllegalArgumentException("missing ep")
                        val old = shOf(ep, str(op, "shot_code")) ?: throw IllegalArgumentException("missing shot")
                        if (stale(old.changedBy, old.changedAt)) throw IllegalArgumentException("stale")
                        val status = listOf(ShotStatus.TODO, ShotStatus.GENERATING, ShotStatus.REVIEW)[enumIndex(str(op, "status"), productionShotStatuses)]
                        if (old.status == ShotStatus.LOCKED) {
                            lockedItems += ProductionLockedItemChange(
                                kindAndId = "镜头 · ${old.code}",
                                title = old.title,
                                outcome = ProductionLockOutcome.RETURN_TO_REVIEW,
                                fields = listOf(ProductionLockFieldDiff("状态", shotStatusText(old.status), shotStatusText(status))),
                                revertTarget = ReviewTarget.shot(ep.id, old.code),
                            )
                        } else {
                            unlockedSummaries += "调整镜头 ${old.code} 状态"
                        }
                        setSh(ep, old.copy(status = status))
                    }
                    "add_take" -> {
                        val ep = epOf(str(op, "episode_id")) ?: throw IllegalArgumentException("missing ep")
                        val old = shOf(ep, str(op, "shot_code")) ?: throw IllegalArgumentException("missing shot")
                        if (stale(old.changedBy, old.changedAt)) throw IllegalArgumentException("stale")
                        ProductionRules.generationRejection(p, ep.id, old.code)?.let { throw IllegalArgumentException(it) }
                        val path = str(op, "path")
                        requireMedia(path)
                        if ("task_id" in op && op["task_id"] != JsonNull && "plan_snapshot" !in op) throw IllegalArgumentException("missing plan_snapshot")
                        if ("mode" in op && op["mode"] != JsonNull) enumIndex(str(op, "mode"), productionModes)
                        unlockedSummaries += "登记镜头 ${old.code} 新候选"
                    }
                    "upsert_asset" -> {
                        val id = str(op, "id")
                        val old = asOf(id)
                        if (old != null && stale(old.changedBy, old.changedAt)) throw IllegalArgumentException("stale")
                        val kind = AssetKind.entries[enumIndex(str(op, "kind"), productionAssetKinds)]
                        val name = str(op, "name")
                        val incomingDesc = opt(op, "description", old?.description)
                        val looks = if ("looks" in op) decodeProductionField<List<CharacterLook>>(op, "looks") else old?.looks.orEmpty()
                        val referenceViews = if ("reference_views" in op) {
                            if (kind !in setOf(AssetKind.LOCATION, AssetKind.PROP, AssetKind.STYLE)) throw IllegalArgumentException("invalid reference_views kind")
                            decodeProductionField<List<ReferenceSlot>>(op, "reference_views")
                        } else old?.referenceViews.orEmpty()
                        if ("looks" in op) looks.forEach { l -> l.sheetPath?.let(::requireMedia); l.slots.mapNotNull { it.path }.forEach(::requireMedia) }
                        if ("reference_views" in op) referenceViews.mapNotNull { it.path }.forEach(::requireMedia)
                        if ("reference_voice" in op) decodeProductionField<ReferenceVoice?>(op, "reference_voice")?.path?.let(::requireMedia)

                        var touchedLock = false
                        if (old != null && old.status == AssetStatus.LOCKED && (old.kind != kind || old.name != name)) {
                            touchedLock = true
                            val diffs = buildList {
                                if (old.kind != kind) add(ProductionLockFieldDiff("种类", assetKindText(old.kind), assetKindText(kind)))
                                if (old.name != name) add(ProductionLockFieldDiff("名称", old.name, name))
                                if (old.description != incomingDesc) add(ProductionLockFieldDiff("Canon 描述", old.description, incomingDesc))
                            }
                            lockedItems += ProductionLockedItemChange(
                                kindAndId = "${assetKindText(old.kind)} · ${old.id}",
                                title = old.name,
                                outcome = ProductionLockOutcome.KEEP_LOCKED,
                                fields = diffs,
                            )
                        }
                        if (old != null && "looks" in op) {
                            looks.forEach { newLook ->
                                val prev = old.looks.firstOrNull { it.id == newLook.id }
                                if (prev != null && prev.locked &&
                                    (prev.name != newLook.name || prev.note != newLook.note || prev.sheetPath != newLook.sheetPath || prev.sheetNote != newLook.sheetNote || prev.slots != newLook.slots)
                                ) {
                                    touchedLock = true
                                    val visualChanged = prev.sheetPath != newLook.sheetPath ||
                                        prev.slots.map { it.id to it.path } != newLook.slots.map { it.id to it.path }
                                    val diffs = buildList {
                                        if (prev.name != newLook.name) add(ProductionLockFieldDiff("造型名称", prev.name, newLook.name))
                                        if (prev.note != newLook.note) add(ProductionLockFieldDiff("造型说明", prev.note, newLook.note))
                                        if (prev.sheetPath != newLook.sheetPath) add(ProductionLockFieldDiff("多视图资产卡", prev.sheetPath, newLook.sheetPath))
                                        if (prev.sheetNote != newLook.sheetNote) add(ProductionLockFieldDiff("整卡说明", prev.sheetNote, newLook.sheetNote))
                                        addAll(slotDiffs(prev.slots, newLook.slots))
                                    }
                                    lockedItems += ProductionLockedItemChange(
                                        kindAndId = "角色造型 · ${old.name} / ${prev.name}",
                                        title = if (visualChanged) "多视角参考组" else "造型文字说明",
                                        outcome = if (visualChanged) ProductionLockOutcome.RETURN_TO_REVIEW else ProductionLockOutcome.KEEP_LOCKED,
                                        fields = diffs,
                                        revertTarget = if (visualChanged) ReviewTarget.assetLook(old.id, prev.id) else null,
                                    )
                                }
                            }
                        }
                        if (old != null && "reference_views" in op && old.viewsLocked && old.referenceViews != referenceViews) {
                            touchedLock = true
                            val visualChanged = old.referenceViews.map { it.id to it.path } != referenceViews.map { it.id to it.path }
                            lockedItems += ProductionLockedItemChange(
                                kindAndId = "${assetKindText(old.kind)}参考 · ${old.id}",
                                title = old.name,
                                outcome = if (visualChanged) ProductionLockOutcome.RETURN_TO_REVIEW else ProductionLockOutcome.KEEP_LOCKED,
                                fields = slotDiffs(old.referenceViews, referenceViews),
                                revertTarget = if (visualChanged) ReviewTarget.assetViews(old.id) else null,
                            )
                        }
                        val nextLooks = looks.map { newLook ->
                            val prev = old?.looks?.firstOrNull { it.id == newLook.id }
                            val visualChanged = prev != null && prev.locked &&
                                (prev.sheetPath != newLook.sheetPath || prev.slots.map { it.id to it.path } != newLook.slots.map { it.id to it.path })
                            newLook.copy(locked = (prev?.locked ?: false) && !visualChanged, contentChangedAt = prev?.contentChangedAt)
                        }
                        val viewsVisualChanged = old != null && old.viewsLocked && "reference_views" in op &&
                            old.referenceViews.map { it.id to it.path } != referenceViews.map { it.id to it.path }
                        val updatedAsset = (old ?: Asset(id, kind, name)).copy(
                            kind = kind,
                            name = name,
                            description = incomingDesc,
                            looks = nextLooks,
                            referenceVoice = if ("reference_voice" in op) decodeProductionField(op, "reference_voice") else old?.referenceVoice,
                            referenceViews = referenceViews,
                            viewsLocked = (old?.viewsLocked ?: false) && !viewsVisualChanged,
                            status = old?.status ?: AssetStatus.DRAFT,
                        )
                        p = p.copy(assets = if (old == null) p.assets + updatedAsset else p.assets.map { if (it.id == id) updatedAsset else it })
                        if (!touchedLock) unlockedSummaries += if (old == null) "新建资产 $id" else "更新资产 $id"
                    }
                    "add_asset_version" -> {
                        val old = asOf(str(op, "asset_id")) ?: throw IllegalArgumentException("missing asset")
                        if (stale(old.changedBy, old.changedAt)) throw IllegalArgumentException("stale")
                        requireMedia(str(op, "path"))
                        unlockedSummaries += "新增资产 ${old.id} 概念版本"
                    }
                    "upsert_look" -> {
                        val old = asOf(str(op, "asset_id")) ?: throw IllegalArgumentException("missing asset")
                        if (stale(old.changedBy, old.changedAt)) throw IllegalArgumentException("stale")
                        if (old.kind != AssetKind.CHARACTER) throw IllegalArgumentException("only character")
                        val lookId = str(op, "look_id")
                        val prev = old.looks.firstOrNull { it.id == lookId }
                        val sheetPath = opt(op, "sheet_path", prev?.sheetPath)
                        sheetPath?.let(::requireMedia)
                        val baseSlots = prev?.slots ?: defaultReferenceSlots()
                        val mergedSlots = if ("slots" !in op) baseSlots else mergeLookSlots(baseSlots, decodeProductionField(op, "slots"))
                        mergedSlots.mapNotNull { it.path }.forEach(::requireMedia)
                        val nextName = str(op, "name")
                        val nextNote = opt(op, "note", prev?.note)
                        val nextSheetNote = opt(op, "sheet_note", prev?.sheetNote)
                        val visualChanged = prev != null && (prev.sheetPath != sheetPath || prev.slots.map { it.id to it.path } != mergedSlots.map { it.id to it.path })
                        val anyChanged = prev != null && (prev.name != nextName || prev.note != nextNote || prev.sheetNote != nextSheetNote || prev.slots != mergedSlots || visualChanged)
                        if (prev != null && prev.locked && anyChanged) {
                            val diffs = buildList {
                                if (prev.name != nextName) add(ProductionLockFieldDiff("造型名称", prev.name, nextName))
                                if (prev.note != nextNote) add(ProductionLockFieldDiff("造型说明", prev.note, nextNote))
                                if (prev.sheetPath != sheetPath) add(ProductionLockFieldDiff("多视图资产卡", prev.sheetPath, sheetPath))
                                if (prev.sheetNote != nextSheetNote) add(ProductionLockFieldDiff("整卡说明", prev.sheetNote, nextSheetNote))
                                addAll(slotDiffs(prev.slots, mergedSlots))
                            }
                            lockedItems += ProductionLockedItemChange(
                                kindAndId = "角色造型 · ${old.name} / ${prev.name}",
                                title = if (visualChanged) "多视角或资产卡参考" else "造型文字说明",
                                outcome = if (visualChanged) ProductionLockOutcome.RETURN_TO_REVIEW else ProductionLockOutcome.KEEP_LOCKED,
                                fields = diffs,
                                revertTarget = if (visualChanged) ReviewTarget.assetLook(old.id, lookId) else null,
                            )
                        } else if (prev == null || !prev.locked) {
                            unlockedSummaries += if (prev == null) "新增造型 $lookId" else "更新造型 $lookId"
                        }
                        val updatedLook = (prev ?: CharacterLook(lookId, nextName)).copy(
                            name = nextName,
                            note = nextNote,
                            sheetPath = sheetPath,
                            sheetNote = nextSheetNote,
                            slots = mergedSlots,
                            locked = prev?.locked == true && !visualChanged,
                        )
                        val nextLooks = if (prev == null) old.looks + updatedLook else old.looks.map { if (it.id == lookId) updatedLook else it }
                        setAs(old.copy(looks = nextLooks))
                    }
                    "set_look_slot" -> {
                        val old = asOf(str(op, "asset_id")) ?: throw IllegalArgumentException("missing asset")
                        if (stale(old.changedBy, old.changedAt)) throw IllegalArgumentException("stale")
                        if (old.kind != AssetKind.CHARACTER) throw IllegalArgumentException("only character")
                        val lookId = str(op, "look_id")
                        val prev = old.looks.firstOrNull { it.id == lookId } ?: throw IllegalArgumentException("missing look")
                        val slotId = str(op, "slot_id")
                        val existing = prev.slots.firstOrNull { it.id == slotId }
                        if (existing == null && ("name" !in op || op["name"] == JsonNull)) throw IllegalArgumentException("missing slot name")
                        val defaultName = defaultReferenceSlots().firstOrNull { it.id == slotId }?.name ?: slotId
                        val nextName = if ("name" in op && op["name"] != JsonNull) str(op, "name") else existing?.name ?: defaultName
                        val nextPath = opt(op, "path", existing?.path)
                        nextPath?.let(::requireMedia)
                        val nextNote = opt(op, "note", existing?.note)
                        val visualChanged = existing == null || existing.path != nextPath
                        val anyChanged = existing == null || existing.name != nextName || existing.path != nextPath || existing.note != nextNote
                        if (prev.locked && anyChanged) {
                            val diffs = buildList {
                                if (existing == null || existing.path != nextPath) {
                                    add(ProductionLockFieldDiff("${nextName}参考", existing?.path, nextPath))
                                }
                                if (existing != null && existing.name != nextName) {
                                    add(ProductionLockFieldDiff("槽位名称", existing.name, nextName))
                                }
                                if (existing?.note != nextNote) {
                                    add(ProductionLockFieldDiff("${nextName}说明", existing?.note, nextNote))
                                }
                            }
                            lockedItems += ProductionLockedItemChange(
                                kindAndId = "角色造型 · ${old.name} / ${prev.name}",
                                title = "$nextName 参考位",
                                outcome = if (visualChanged) ProductionLockOutcome.RETURN_TO_REVIEW else ProductionLockOutcome.KEEP_LOCKED,
                                fields = diffs,
                                revertTarget = if (visualChanged) ReviewTarget.assetLook(old.id, lookId) else null,
                            )
                        } else if (!prev.locked) {
                            unlockedSummaries += "更新造型位 $lookId/$slotId"
                        }
                        val updatedSlot = (existing ?: ReferenceSlot(slotId, nextName)).copy(name = nextName, path = nextPath, note = nextNote)
                        val nextSlots = if (existing == null) prev.slots + updatedSlot else prev.slots.map { if (it.id == slotId) updatedSlot else it }
                        val updatedLook = prev.copy(slots = nextSlots, locked = prev.locked && !visualChanged)
                        setAs(old.copy(looks = old.looks.map { if (it.id == lookId) updatedLook else it }))
                    }
                    "set_asset_view" -> {
                        val old = asOf(str(op, "asset_id")) ?: throw IllegalArgumentException("missing asset")
                        if (stale(old.changedBy, old.changedAt)) throw IllegalArgumentException("stale")
                        if (old.kind !in setOf(AssetKind.LOCATION, AssetKind.PROP, AssetKind.STYLE)) throw IllegalArgumentException("invalid kind")
                        val viewId = str(op, "view_id")
                        val removeView = "remove" in op && flag(op, "remove")
                        val existing = old.referenceViews.firstOrNull { it.id == viewId }
                        val nextViews: List<ReferenceSlot>
                        val visualChanged: Boolean
                        val diffs = mutableListOf<ProductionLockFieldDiff>()
                        val subtitle: String
                        if (removeView) {
                            if (existing == null) throw IllegalArgumentException("missing view")
                            nextViews = old.referenceViews.filterNot { it.id == viewId }
                            visualChanged = true
                            subtitle = "移除视角 ${existing.name}"
                            diffs += ProductionLockFieldDiff(existing.name, existing.path ?: existing.name, null)
                        } else {
                            if (existing == null && ("name" !in op || op["name"] == JsonNull)) throw IllegalArgumentException("missing view name")
                            if (existing == null && old.referenceViews.size >= 30) throw IllegalArgumentException("too many views")
                            val nextName = if ("name" in op && op["name"] != JsonNull) str(op, "name") else existing!!.name
                            val nextPath = opt(op, "path", existing?.path)
                            nextPath?.let(::requireMedia)
                            val nextNote = opt(op, "note", existing?.note)
                            visualChanged = existing == null || existing.path != nextPath
                            val updatedSlot = (existing ?: ReferenceSlot(viewId, nextName)).copy(name = nextName, path = nextPath, note = nextNote)
                            nextViews = if (existing == null) old.referenceViews + updatedSlot else old.referenceViews.map { if (it.id == viewId) updatedSlot else it }
                            subtitle = "$nextName 参考视角"
                            if (existing == null || existing.path != nextPath) diffs += ProductionLockFieldDiff("${nextName}图片", existing?.path, nextPath)
                            if (existing != null && existing.name != nextName) diffs += ProductionLockFieldDiff("视角名称", existing.name, nextName)
                            if (existing?.note != nextNote) diffs += ProductionLockFieldDiff("${nextName}说明", existing?.note, nextNote)
                        }
                        if (old.viewsLocked && diffs.isNotEmpty()) {
                            lockedItems += ProductionLockedItemChange(
                                kindAndId = "${assetKindText(old.kind)} · ${old.name}",
                                title = subtitle,
                                outcome = if (visualChanged) ProductionLockOutcome.RETURN_TO_REVIEW else ProductionLockOutcome.KEEP_LOCKED,
                                fields = diffs,
                                revertTarget = if (visualChanged) ReviewTarget.assetViews(old.id) else null,
                            )
                        } else if (!old.viewsLocked) {
                            unlockedSummaries += "更新参考视角 ${old.id}/$viewId"
                        }
                        setAs(old.copy(referenceViews = nextViews, viewsLocked = old.viewsLocked && !visualChanged))
                    }
                    "set_video_settings" -> {
                        if (stale(p.policyChangedBy, p.policyChangedAt)) throw IllegalArgumentException("stale")
                        val nextChannel = opt(op, "default_video_channel_id", p.defaultVideoChannelId)
                        val nextMode = if ("reference_input_mode" !in op) p.referenceInputMode
                        else if (op["reference_input_mode"] == JsonNull) null
                        else ReferenceInputMode.entries[enumIndex(str(op, "reference_input_mode"), productionReferenceInputModes)]
                        p = p.copy(defaultVideoChannelId = nextChannel, referenceInputMode = nextMode)
                        unlockedSummaries += "调整视频默认设置"
                    }
                    "set_asset_status" -> {
                        val old = asOf(str(op, "asset_id")) ?: throw IllegalArgumentException("missing asset")
                        if (stale(old.changedBy, old.changedAt)) throw IllegalArgumentException("stale")
                        val status = listOf(AssetStatus.DRAFT, AssetStatus.REVIEW)[enumIndex(str(op, "status"), productionAssetStatuses)]
                        if (old.status == AssetStatus.LOCKED) {
                            lockedItems += ProductionLockedItemChange(
                                kindAndId = "${assetKindText(old.kind)} · ${old.id}",
                                title = old.name,
                                outcome = ProductionLockOutcome.RETURN_TO_REVIEW,
                                fields = listOf(ProductionLockFieldDiff("状态", assetStatusText(old.status), assetStatusText(status))),
                                revertTarget = ReviewTarget(ReviewTargetKind.ASSET_VERSION, assetId = old.id),
                            )
                        } else {
                            unlockedSummaries += "调整资产 ${old.id} 状态"
                        }
                        setAs(old.copy(status = status, looks = old.looks.map { it.copy(locked = false) }, viewsLocked = false))
                    }
                    "set_stage" -> {
                        val stage = ProductionStage.entries[enumIndex(str(op, "stage"), productionStages)]
                        val old = p.stages.first { it.stage == stage }
                        if (stale(old.changedBy, old.changedAt)) throw IllegalArgumentException("stale")
                        val status = listOf(StageStatus.NOT_STARTED, StageStatus.IN_PROGRESS, StageStatus.SKIPPED)[enumIndex(str(op, "status"), productionStageStatuses)]
                        if (old.status == StageStatus.LOCKED) {
                            lockedItems += ProductionLockedItemChange(
                                kindAndId = "制作阶段 · ${stageText(stage)}",
                                title = stageText(stage),
                                outcome = ProductionLockOutcome.RETURN_TO_REVIEW,
                                fields = listOf(ProductionLockFieldDiff("状态", stageStatusText(old.status), stageStatusText(status))),
                            )
                        } else {
                            unlockedSummaries += "更新阶段 ${stageText(stage)}"
                        }
                        p = p.copy(stages = p.stages.map { if (it.stage == stage) it.copy(status = status, note = opt(op, "note", it.note)) else it })
                    }
                    "set_bible" -> {
                        p = p.copy(bible = decodeProductionField(op, "documents"))
                        unlockedSummaries += "更新世界观文档"
                    }
                    "set_experimental" -> {
                        if (stale(p.policyChangedBy, p.policyChangedAt)) throw IllegalArgumentException("stale")
                        p = p.copy(experimental = flag(op, "enabled"))
                        unlockedSummaries += "调整试验模式"
                    }
                    "set_preview_enabled" -> {
                        p = p.copy(previewEnabled = flag(op, "enabled"))
                        unlockedSummaries += "调整预演开关"
                    }
                    "remove" -> {
                        val target = str(op, "target")
                        val ids = (op["ids"] as? JsonArray)?.map {
                            (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content ?: throw IllegalArgumentException("invalid ids")
                        } ?: throw IllegalArgumentException("invalid ids")
                        if (ids.isEmpty() || ids.distinct().size != ids.size) throw IllegalArgumentException("invalid ids")
                        when (target) {
                            "season" -> {
                                ids.forEach { id ->
                                    val s = p.seasons.firstOrNull { it.id == id } ?: throw IllegalArgumentException("missing season")
                                    var lockedCount = 0
                                    s.episodes.forEach { e ->
                                        if (stale(e.changedBy, e.changedAt)) throw IllegalArgumentException("stale")
                                        if (e.scriptLocked) lockedCount++
                                        e.scenes.forEach { sc ->
                                            if (stale(sc.changedBy, sc.changedAt)) throw IllegalArgumentException("stale")
                                            if (sc.storyboardLocked) lockedCount++
                                            sc.shots.forEach { sh ->
                                                if (stale(sh.changedBy, sh.changedAt)) throw IllegalArgumentException("stale")
                                                if (sh.status == ShotStatus.LOCKED) lockedCount++
                                            }
                                        }
                                    }
                                    if (lockedCount > 0) {
                                        lockedItems += ProductionLockedItemChange(
                                            kindAndId = "季 · ${s.id}",
                                            title = s.title ?: "第 ${s.number} 季",
                                            outcome = ProductionLockOutcome.PERMANENT_REMOVE,
                                            summary = "永久删除已锁定季（含 $lockedCount 处已锁定剧本、分镜或镜头）。",
                                        )
                                    } else unlockedSummaries += "移除季 $id"
                                }
                                p = p.copy(seasons = p.seasons.filterNot { it.id in ids })
                            }
                            "episode" -> {
                                ids.forEach { id ->
                                    val e = epOf(id) ?: throw IllegalArgumentException("missing ep")
                                    if (stale(e.changedBy, e.changedAt)) throw IllegalArgumentException("stale")
                                    var lockedCount = if (e.scriptLocked) 1 else 0
                                    e.scenes.forEach { sc ->
                                        if (stale(sc.changedBy, sc.changedAt)) throw IllegalArgumentException("stale")
                                        if (sc.storyboardLocked) lockedCount++
                                        sc.shots.forEach { sh ->
                                            if (stale(sh.changedBy, sh.changedAt)) throw IllegalArgumentException("stale")
                                            if (sh.status == ShotStatus.LOCKED) lockedCount++
                                        }
                                    }
                                    if (lockedCount > 0) {
                                        lockedItems += ProductionLockedItemChange(
                                            kindAndId = "集 · ${e.id}",
                                            title = e.title,
                                            outcome = ProductionLockOutcome.PERMANENT_REMOVE,
                                            summary = "永久删除已锁定集（含 $lockedCount 处已锁定剧本、分镜或镜头）。",
                                        )
                                    } else unlockedSummaries += "移除集 $id"
                                }
                                p = p.copy(seasons = p.seasons.map { it.copy(episodes = it.episodes.filterNot { e -> e.id in ids }) })
                            }
                            "scene" -> {
                                val ep = epOf(str(op, "episode_id")) ?: throw IllegalArgumentException("missing ep")
                                ids.forEach { id ->
                                    val sc = scOf(ep, id) ?: throw IllegalArgumentException("missing scene")
                                    if (stale(sc.changedBy, sc.changedAt)) throw IllegalArgumentException("stale")
                                    var lockedShots = 0
                                    sc.shots.forEach { sh ->
                                        if (stale(sh.changedBy, sh.changedAt)) throw IllegalArgumentException("stale")
                                        if (sh.status == ShotStatus.LOCKED) lockedShots++
                                    }
                                    if (sc.storyboardLocked || lockedShots > 0) {
                                        lockedItems += ProductionLockedItemChange(
                                            kindAndId = "场 · ${sc.code}",
                                            title = sc.heading,
                                            outcome = ProductionLockOutcome.PERMANENT_REMOVE,
                                            summary = "永久删除已锁定场（含 ${sc.shots.size} 个镜头）。",
                                        )
                                    } else unlockedSummaries += "移除场 $id"
                                }
                                setEp(ep.copy(scenes = ep.scenes.filterNot { it.code in ids }))
                            }
                            "shot" -> {
                                val ep = epOf(str(op, "episode_id")) ?: throw IllegalArgumentException("missing ep")
                                ids.forEach { id ->
                                    val sh = shOf(ep, id) ?: throw IllegalArgumentException("missing shot")
                                    if (stale(sh.changedBy, sh.changedAt)) throw IllegalArgumentException("stale")
                                    val sc = ep.scenes.first { it.shots.any { s -> s.code == id } }
                                    if (stale(sc.changedBy, sc.changedAt)) throw IllegalArgumentException("stale")
                                    if (sc.storyboardLocked || sh.status == ShotStatus.LOCKED) {
                                        lockedItems += ProductionLockedItemChange(
                                            kindAndId = "镜头 · ${sh.code}",
                                            title = sh.title,
                                            outcome = ProductionLockOutcome.PERMANENT_REMOVE,
                                            summary = "永久删除已锁定分镜中的镜头 ${sh.code}。",
                                        )
                                    } else unlockedSummaries += "移除镜头 $id"
                                }
                                setEp(ep.copy(scenes = ep.scenes.map { it.copy(shots = it.shots.filterNot { s -> s.code in ids }) }))
                            }
                            "asset" -> {
                                ids.forEach { id ->
                                    val a = asOf(id) ?: throw IllegalArgumentException("missing asset")
                                    if (stale(a.changedBy, a.changedAt)) throw IllegalArgumentException("stale")
                                    if (a.status == AssetStatus.LOCKED) {
                                        val lockedShotRefs = countLockedShotsReferencingAsset(p, id)
                                        val summary = if (lockedShotRefs == 0) {
                                            "永久删除已锁定${assetKindText(a.kind)}（已确认无锁定镜头引用）。"
                                        } else {
                                            "永久删除已锁定${assetKindText(a.kind)}（当前仍有 $lockedShotRefs 个已锁定镜头或分镜场引用）。"
                                        }
                                        lockedItems += ProductionLockedItemChange(
                                            kindAndId = "${assetKindText(a.kind)} · ${a.id}",
                                            title = a.name,
                                            outcome = ProductionLockOutcome.PERMANENT_REMOVE,
                                            referencedByLockedShots = lockedShotRefs,
                                            summary = summary,
                                        )
                                    } else unlockedSummaries += "移除资产 $id"
                                }
                                p = p.copy(assets = p.assets.filterNot { it.id in ids })
                            }
                            else -> throw IllegalArgumentException("invalid remove target")
                        }
                    }
                    else -> throw IllegalArgumentException("unknown op $name @$index")
                }
            }
        }
        if (outcome.isFailure || lockedItems.isEmpty()) return null
        val digest = computeDigest(snapshot.production.id, snapshot.revision, prepared.operations)
        return ProductionLockPreflight(
            productionId = snapshot.production.id,
            productionTitle = snapshot.production.title,
            revision = snapshot.revision,
            reason = reason.trim(),
            lockedItems = lockedItems,
            unlockedSummaries = unlockedSummaries.distinct(),
            authorizationTarget = "$TARGET_PREFIX${snapshot.production.id}:r${snapshot.revision}:$digest",
        )
    }

    private fun enumIndex(value: String, allowed: List<String>): Int =
        allowed.indexOf(value).also { if (it < 0) throw IllegalArgumentException("invalid enum $value") }

    private fun mergeLookSlots(baseSlots: List<ReferenceSlot>, incoming: List<ReferenceSlot>): List<ReferenceSlot> {
        val byId = baseSlots.associateBy { it.id }.toMutableMap()
        incoming.forEach { slot -> byId[slot.id] = slot }
        val orderedDefaults = ProductionRules.defaultSlotOrder.map { id -> byId.getValue(id) }
        val customIds = (baseSlots.map { it.id } + incoming.map { it.id }).distinct().filter { it !in ProductionRules.defaultSlotOrder }
        return orderedDefaults + customIds.map { id -> byId.getValue(id) }
    }

    private fun slotDiffs(oldSlots: List<ReferenceSlot>, newSlots: List<ReferenceSlot>): List<ProductionLockFieldDiff> {
        val oldById = oldSlots.associateBy { it.id }
        val newById = newSlots.associateBy { it.id }
        val orderedIds = (oldSlots.map { it.id } + newSlots.map { it.id }).distinct()
        return buildList {
            orderedIds.forEach { id ->
                val oldSlot = oldById[id]
                val newSlot = newById[id]
                val label = newSlot?.name ?: oldSlot?.name ?: id
                when {
                    oldSlot == null && newSlot != null ->
                        add(ProductionLockFieldDiff("新增 $label", null, newSlot.path ?: newSlot.name))
                    oldSlot != null && newSlot == null ->
                        add(ProductionLockFieldDiff("移除 $label", oldSlot.path ?: oldSlot.name, null))
                    oldSlot != null && newSlot != null -> {
                        if (oldSlot.path != newSlot.path) add(ProductionLockFieldDiff("${label}参考", oldSlot.path, newSlot.path))
                        if (oldSlot.name != newSlot.name) add(ProductionLockFieldDiff("${label}名称", oldSlot.name, newSlot.name))
                        if (oldSlot.note != newSlot.note) add(ProductionLockFieldDiff("${label}说明", oldSlot.note, newSlot.note))
                    }
                }
            }
        }
    }

    private fun countLockedShotsReferencingAsset(production: Production, assetId: String): Int =
        production.seasons.flatMap { it.episodes }.flatMap { it.scenes }.sumOf { scene ->
            scene.shots.count { shot ->
                val locked = scene.storyboardLocked || shot.status == ShotStatus.LOCKED
                val references = shot.locationAssetId == assetId ||
                    assetId in shot.propAssetIds ||
                    shot.appearances.any { it.characterAssetId == assetId }
                locked && references
            }
        }

    private fun computeDigest(productionId: String, revision: Long, operations: List<JsonObject>): String {
        val payload = buildString {
            append(productionId).append('\u0000').append(revision).append('\u0000')
            operations.forEach { append(canonicalJson(it)).append('\u0000') }
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"$k\":${canonicalJson(v)}"
        }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",") { canonicalJson(it) }
        else -> element.toString()
    }

    private fun assetKindText(kind: AssetKind): String = when (kind) {
        AssetKind.CHARACTER -> "角色"
        AssetKind.LOCATION -> "场景"
        AssetKind.PROP -> "道具"
        AssetKind.STYLE -> "画风"
        AssetKind.SOUND -> "声音"
    }

    private fun assetStatusText(status: AssetStatus): String = when (status) {
        AssetStatus.DRAFT -> "草稿"
        AssetStatus.REVIEW -> "待审"
        AssetStatus.LOCKED -> "已锁定"
        AssetStatus.REDO -> "重做"
    }

    private fun shotStatusText(status: ShotStatus): String = when (status) {
        ShotStatus.TODO -> "待制作"
        ShotStatus.GENERATING -> "生成中"
        ShotStatus.REVIEW -> "待审"
        ShotStatus.LOCKED -> "已锁定"
        ShotStatus.REDO -> "重做"
    }

    private fun stageText(stage: ProductionStage): String = when (stage) {
        ProductionStage.SCRIPT -> "剧本"
        ProductionStage.VISUAL_DEV -> "视觉资产"
        ProductionStage.STORYBOARD -> "分镜"
        ProductionStage.ANIMATIC -> "分镜预演"
        ProductionStage.GENERATION -> "出片"
        ProductionStage.POST_PROCESS -> "后处理"
        ProductionStage.SOUND -> "声音"
        ProductionStage.EDIT -> "剪辑"
    }

    private fun stageStatusText(status: StageStatus): String = when (status) {
        StageStatus.NOT_STARTED -> "未开始"
        StageStatus.IN_PROGRESS -> "进行中"
        StageStatus.LOCKED -> "已锁定"
        StageStatus.SKIPPED -> "已跳过"
    }
}

/**
 * 清除 [target] 所属审批组下未排队的审核结论与草稿基线，保留 [Production.reviewRounds] 与排队中轮次，
 * 并在 [Asset] 上按剩余锁定造型/参考视图自动重算状态，使批准修改的视觉/剧本退回「待审」时审核状态一致且通过校验。
 */
internal fun Production.reopenNonQueuedReviewGroup(target: ReviewTarget): Production {
    val group = target.reviewGroup()
    val filteredReviews = reviews.filterNot {
        it.queuedRound == null && it.kind != ReviewKind.SUGGEST && it.target.reviewGroup() == group
    }
    val filteredDrafts = reviewDrafts.filterNot { !it.queued && it.group == group }
    return copy(reviews = filteredReviews, reviewDrafts = filteredDrafts)
}

internal fun Production.reopenAssetToReview(assetId: String): Production {
    val filteredReviews = reviews.filterNot {
        it.queuedRound == null && it.kind != ReviewKind.SUGGEST && it.target.assetId == assetId
    }
    val filteredDrafts = reviewDrafts.filterNot { !it.queued && it.group.assetId == assetId }
    return copy(reviews = filteredReviews, reviewDrafts = filteredDrafts)
}

internal fun Production.deriveAssetStatusAfterLockChange(asset: Asset): AssetStatus {
    val verdicts = currentReviewVerdicts().filter { it.target.assetId == asset.id }
    val approvedVersion = verdicts.any { it.target.kind == ReviewTargetKind.ASSET_VERSION && it.kind == ReviewKind.APPROVE }
    val anyLocked = approvedVersion || asset.looks.any { it.locked } || asset.viewsLocked
    return when {
        anyLocked -> AssetStatus.LOCKED
        asset.versions.isNotEmpty() || asset.looks.any { it.sheetPath != null || it.populatedSlots().isNotEmpty() } || asset.populatedReferenceViews().isNotEmpty() ->
            AssetStatus.REVIEW
        else -> AssetStatus.DRAFT
    }
}
