package com.promenar.infinity.data.production

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal data class ProductionOperationFields(val required: Set<String>, val optional: Set<String>)

private fun fields(required: String, optional: String = "") = ProductionOperationFields(
    required.split(' ').filter(String::isNotEmpty).toSet(),
    optional.split(' ').filter(String::isNotEmpty).toSet(),
)

internal val productionOperationFields = linkedMapOf(
    "upsert_season" to fields("id number", "title"),
    "upsert_episode" to fields("season_id id number title", "script_path previs_path"),
    "upsert_scene" to fields("episode_id code number heading"),
    "upsert_shot" to fields(
        "episode_id scene_code code",
        "title duration_seconds size camera camera_movement visual performance dialogue sound transition continuity " +
            "appearances location_asset_id prop_asset_ids channel_id mode prompt inputs preview preview_path agent_note change_note position",
    ),
    "set_shot_status" to fields("episode_id shot_code status"),
    "add_take" to fields("episode_id shot_code path service", "model mode task_id usage_ref plan_snapshot note"),
    "upsert_asset" to fields("id kind name", "description looks reference_voice reference_views"),
    "add_asset_version" to fields("asset_id path", "note task_id"),
    "upsert_look" to fields("asset_id look_id name", "note sheet_path sheet_note slots"),
    "set_look_slot" to fields("asset_id look_id slot_id", "name path note"),
    "set_asset_view" to fields("asset_id view_id", "name path note remove"),
    "set_asset_status" to fields("asset_id status"),
    "set_stage" to fields("stage status", "note"),
    "set_bible" to fields("documents"),
    "set_video_settings" to fields("", "default_video_channel_id reference_input_mode"),
    "set_experimental" to fields("enabled"),
    "set_preview_enabled" to fields("enabled"),
    "remove" to fields("target ids", "episode_id"),
)

private val seasonId = Regex("S[0-9]+")
private val episodeId = Regex("S[0-9]+E[0-9]{2,}")
private val sceneCode = Regex("SC[0-9]+")
private val shotCode = Regex("SC[0-9]+-[0-9]+")
private val assetId = Regex("[a-z][a-z0-9-]{0,47}")
internal val productionModes = listOf("text", "first_frame", "last_frame", "first_last", "reference")
internal val productionPreviews = listOf("keyframe", "draft_clip", "text_card")
internal val productionAssetKinds = listOf("character", "location", "prop", "style", "sound")
internal val productionStages = listOf("script", "visual_dev", "storyboard", "animatic", "generation", "sound", "edit")
internal val productionRemoveTargets = listOf("season", "episode", "scene", "shot", "asset")
internal val productionShotStatuses = listOf("todo", "generating", "review")
internal val productionAssetStatuses = listOf("draft", "review")
internal val productionStageStatuses = listOf("not_started", "in_progress", "skipped")
internal val productionReferenceInputModes = listOf("sheet", "separate")
internal val nullableProductionOperationFields = setOf(
    "title", "script_path", "previs_path", "size", "camera", "camera_movement", "visual", "performance", "sound", "transition",
    "continuity", "location_asset_id", "channel_id", "mode", "preview", "preview_path", "agent_note", "change_note",
    "model", "task_id", "usage_ref", "note", "description", "reference_voice",
    "sheet_path", "sheet_note", "path", "default_video_channel_id", "reference_input_mode",
)
private val foldedKeyNames = productionOperationFields.values
    .flatMap { it.required + it.optional }.distinct().associateBy { it.replace("_", "") }

internal val productionNestedFields = mapOf(
    "inputs" to "[{role: first_frame|last_frame|anchor|reference, path, label?, assetId?, atSeconds?}]",
    "dialogue" to "[{characterAssetId, text, tone?, audioSource?: native|dubbed}]",
    "appearances" to "[{characterAssetId, lookId, referenceSlotIds?: 字符串数组}]",
    "looks" to "[{id, name, note?, sheetPath?, sheetNote?, slots?: [{id, name, path?, version?, note?}]}]，slots 须含 front、side、back，省略时自动填入这三项",
    "slots" to "[{id, name, path?, version?, note?}]",
    "reference_views" to "[{id, name, path?, version?, note?}]（用于 location、prop、style 的自由多视角）",
    "reference_voice" to "{path?, transcript?, voiceSetting?, tone?} 或 null",
    "plan_snapshot" to "{mode, inputs, prompt, channelId?, mediaInputs?: [{location, kind, role}], specs?: {resolution?, durationSeconds?, width?, height?, frames?, seed?, durationSecondsExact?}, generationMode?, templateFingerprint?, params?}",
    "documents" to "[{title, path}]",
    "prop_asset_ids" to "资产 ID 字符串数组",
    "ids" to "目标 ID 字符串数组",
)

internal inline fun <reified T> decodeProductionField(op: JsonObject, key: String): T =
    decodeProductionValue(op.getValue(key), key, key)

internal inline fun <reified T> decodeProductionList(op: JsonObject, key: String): List<T> {
    val array = op[key] as? JsonArray ?: throw IllegalArgumentException("$key 必须是数组，格式为 ${productionNestedFields.getValue(key)}。")
    return array.mapIndexed { index, item -> decodeProductionValue(item, key, "$key[$index]") }
}

internal inline fun <reified T> decodeProductionValue(value: JsonElement, key: String, location: String): T = try {
    Json.decodeFromJsonElement<T>(value)
} catch (e: SerializationException) {
    throw IllegalArgumentException("$location 格式无效（${productionDecodeError(e)}）。请使用 ${productionNestedFields.getValue(key)}，嵌套键使用上述拼写。", e)
}

private val unknownJsonField = Regex("""(?i)unknown (?:key|field) ['"]([^'"]+)['"]""")
private val invalidJsonEnum = Regex("""(?i)does not contain element with name ['"]([^'"]+)['"]""")
private val jsonErrorPath = Regex("""(?i)at path: (\$[^\s:]*)(?:[\s:]|$)""")

@OptIn(ExperimentalSerializationApi::class)
internal fun productionDecodeError(failure: SerializationException): String {
    val message = failure.message.orEmpty().substringBefore("JSON input:")
    val unknown = unknownJsonField.find(message)?.groupValues?.get(1)?.take(80)
    val invalidEnum = invalidJsonEnum.find(message)?.groupValues?.get(1)?.take(80)
    val path = jsonErrorPath.find(message)?.groupValues?.get(1)?.takeUnless { it == "$" }
    val reason = when {
        failure is MissingFieldException -> "缺少字段 ${failure.missingFields.joinToString("、")}"
        unknown != null -> "未知字段 $unknown"
        invalidEnum != null -> "枚举值 $invalidEnum 无效"
        else -> "字段类型或值与契约不符"
    }
    return reason + if (path == null) "" else "，位置 $path"
}

internal const val PRODUCTION_BATCH_ERROR_LIMIT = 6000

internal data class PreparedProductionOperations(val operations: List<JsonObject>, val media: Set<String>)

/** 扫描全部条目并归一明确的路径字段。此阶段不查找媒体，也不修改制作快照。 */
internal inline fun prepareProductionOperations(
    operations: List<JsonElement>,
    current: Production? = null,
    normalize: (String) -> String,
): PreparedProductionOperations {
    require(operations.size in 1..100) { "operations 需为 1～100 项。" }
    val errors = mutableListOf<String>()
    val output = mutableListOf<JsonObject>()
    val media = mutableSetOf<String>()
    val existingShots = current?.seasons?.flatMap { it.episodes }?.flatMap { episode ->
        episode.scenes.flatMap { scene -> scene.shots.map { Triple(episode.id, scene.code, it.code) } }
    }?.toMutableSet()
    operations.forEachIndexed { index, element ->
        val op = element as? JsonObject
        val name = (op?.get("op") as? JsonPrimitive)?.takeIf { it.isString }?.content
        try {
            require(op != null) { "操作必须是对象，例如 {\"op\":\"set_experimental\",\"enabled\":true}。" }
            validateProductionOperation(op, name)
            val shotKey = if (name == "upsert_shot" && existingShots != null) {
                Triple(op.getValue("episode_id").jsonPrimitive.content, op.getValue("scene_code").jsonPrimitive.content, op.getValue("code").jsonPrimitive.content).also {
                    if (it !in existingShots) {
                        val missing = setOf("title", "duration_seconds") - op.keys
                        require(missing.isEmpty()) { "新建镜头缺少必填字段 ${missing.joinToString("、")}。" }
                    }
                }
            } else null
            output += normalizeProductionOperation(op) { key, path, checkMedia ->
                val relative = try {
                    normalize(path).also(ProductionManifestCodec::validatePath)
                } catch (failure: IllegalArgumentException) {
                    throw IllegalArgumentException("$key：${failure.message}", failure)
                }
                if (checkMedia) media += relative
                relative
            }
            shotKey?.let { existingShots?.add(it) }
            if (name == "remove" && existingShots != null) forgetRemovedShots(existingShots, op)
        } catch (failure: IllegalArgumentException) {
            val label = name?.takeIf(productionOperationFields::containsKey)?.let { "（$it）" }.orEmpty()
            errors += "第 ${index + 1} 项$label：${failure.message.orEmpty().take(550)}"
        }
    }
    require(errors.isEmpty()) {
        errors.take(10).joinToString("\n") + if (errors.size > 10) "\n另有 ${errors.size - 10} 项格式错误。" else ""
    }
    return PreparedProductionOperations(output, media)
}

internal fun forgetRemovedShots(shots: MutableSet<Triple<String, String, String>>, op: JsonObject) {
    val target = op.getValue("target").jsonPrimitive.content
    val ids = op.getValue("ids").jsonArray.map { it.jsonPrimitive.content }.toSet()
    val episodeId = op["episode_id"]?.jsonPrimitive?.content
    shots.removeAll { (episode, scene, shot) ->
        when (target) {
            "season" -> episode.substringBeforeLast('E') in ids
            "episode" -> episode in ids
            "scene" -> episode == episodeId && scene in ids
            "shot" -> episode == episodeId && shot in ids
            else -> false
        }
    }
}

internal fun validateProductionOperation(op: JsonObject, name: String?) {
    require(name != null) { "op 是必填字符串，用 op 指定操作，例如 \"op\":\"upsert_season\"。" }
    val contract = productionOperationFields[name] ?: throw IllegalArgumentException(
        "未知 op，可用操作为 ${productionOperationFields.keys.joinToString("、")}。",
    )
    val allowed = contract.required + contract.optional + "op"
    val unknown = op.keys - allowed
    require(unknown.isEmpty()) {
        unknown.joinToString("；") { key ->
            val corrected = when {
                key == "action" || key == "type" -> "op"
                name == "upsert_scene" && key == "title" -> "heading"
                name == "upsert_shot" && key in setOf("shot_code", "shotCode") -> "code"
                name == "upsert_shot" && key == "duration" -> "duration_seconds"
                else -> foldedKeyNames[key.lowercase().replace("_", "")]
            }?.takeIf { it in allowed }
            if (corrected != null) "未知字段 $key，请使用 $corrected" else "未知字段 $key，可用字段为 ${allowed.joinToString("、")}"
        }
    }
    val missing = contract.required - op.keys
    require(missing.isEmpty()) { "缺少必填字段 ${missing.joinToString("、")}。" }

    fun text(key: String): String? = (op[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun matches(key: String, value: String, format: Regex) {
        require(format.matches(value)) { "$key 格式无效，须符合 ${format.pattern}。" }
    }
    fun choice(key: String, value: String, choices: List<String>) {
        require(value in choices) { "$key 仅支持 ${choices.joinToString("、")}。" }
    }
    for ((key, value) in op) {
        if (value == JsonNull && key in nullableProductionOperationFields && key !in contract.required &&
            !(key == "title" && name == "upsert_shot") && !(key == "mode" && name == "upsert_shot")) continue
        val primitive = value as? JsonPrimitive
        when (key) {
            "number", "position" -> {
                val minimum = if (key == "number") 1 else 0
                val number = primitive?.takeUnless { it.isString }?.intOrNull
                require(number != null && number >= minimum) { "$key 必须是大于或等于 $minimum 的整数。" }
            }
            "duration_seconds" -> {
                val duration = primitive?.takeUnless { it.isString }?.doubleOrNull
                require(duration != null && duration.isFinite() && duration > 0) { "duration_seconds 必须是有限正数，单位为秒。" }
            }
            "enabled", "remove" -> require(primitive?.takeUnless { it.isString }?.booleanOrNull != null) { "$key 必须是布尔值 true 或 false。" }
            "ids", "prop_asset_ids", "dialogue", "appearances", "looks", "slots", "reference_views", "inputs", "reference_voice", "plan_snapshot", "documents" -> Unit
            else -> {
                val string = requireNotNull(text(key)) { "$key 必须是字符串。" }
                when (key) {
                    "season_id" -> matches(key, string, seasonId)
                    "episode_id" -> matches(key, string, episodeId)
                    "scene_code" -> matches(key, string, sceneCode)
                    "shot_code" -> matches(key, string, shotCode)
                    "asset_id", "location_asset_id" -> matches(key, string, assetId)
                    "id" -> matches(key, string, when (name) {
                        "upsert_season" -> seasonId
                        "upsert_episode" -> episodeId
                        else -> assetId
                    })
                    "code" -> matches(key, string, if (name == "upsert_scene") sceneCode else shotCode)
                    "look_id", "slot_id", "view_id", "default_video_channel_id" -> require(string.isNotBlank() && string.length <= 120) { "$key 需为 1～120 字。" }
                    "reference_input_mode" -> choice(key, string, productionReferenceInputModes)
                    "mode" -> choice(key, string, productionModes)
                    "preview" -> choice(key, string, productionPreviews)
                    "stage" -> choice(key, string, productionStages)
                    "kind" -> choice(key, string, productionAssetKinds)
                    "target" -> choice(key, string, productionRemoveTargets)
                    "status" -> choice(key, string, when (name) {
                        "set_shot_status" -> productionShotStatuses
                        "set_asset_status" -> productionAssetStatuses
                        else -> productionStageStatuses
                    })
                    "name", "title", "heading", "service" -> require(string.isNotBlank() && string.length <= 120) { "$key 需为 1～120 字。" }
                    "prompt" -> require(string.length <= 7000) { "prompt 最多 7000 字。" }
                    "note", "agent_note", "description", "sheet_note", "camera", "camera_movement", "visual", "performance",
                    "sound", "transition", "continuity" -> require(string.length <= 1000) { "$key 最多 1000 字。" }
                }
            }
        }
    }
    when (name) {
        "upsert_episode" -> require(text("id")!!.startsWith("${text("season_id")}E")) { "id 必须属于 season_id，例如 S1 对应 S1E01。" }
        "upsert_shot" -> require(text("code")!!.startsWith("${text("scene_code")}-")) { "code 必须属于 scene_code，例如 SC01 对应 SC01-01。" }
        "add_take" -> require(text("task_id") == null || "plan_snapshot" in op) { "提供 task_id 时必须提供实际规划 plan_snapshot。" }
        "set_video_settings" -> require("default_video_channel_id" in op || "reference_input_mode" in op) {
            "set_video_settings 至少需提供 default_video_channel_id 或 reference_input_mode。"
        }
        "remove" -> {
            val target = text("target")
            val needsEpisode = target in setOf("scene", "shot")
            require(needsEpisode == ("episode_id" in op)) { "target 为 scene 或 shot 时必填 episode_id，其余目标省略该字段。" }
            val ids = decodeProductionList<String>(op, "ids")
            require(ids.isNotEmpty() && ids.distinct().size == ids.size) { "ids 必须是非空且无重复的字符串数组。" }
            val format = when (target) {
                "season" -> seasonId
                "episode" -> episodeId
                "scene" -> sceneCode
                "shot" -> shotCode
                else -> assetId
            }
            ids.forEach { matches("ids", it, format) }
        }
    }
}

internal inline fun normalizeProductionOperation(
    op: JsonObject,
    normalize: (key: String, path: String, checkMedia: Boolean) -> String,
): JsonObject = buildJsonObject {
    for ((key, value) in op) {
        val result = when (key) {
            "path", "script_path", "previs_path", "preview_path", "sheet_path" -> if (value == JsonNull) value else {
                JsonPrimitive(normalize(key, value.jsonPrimitive.content, key in setOf("path", "sheet_path")))
            }
            "inputs" -> {
                val inputs = decodeProductionList<ShotInput>(op, key)
                validateProductionInputs(key, inputs)
                Json.encodeToJsonElement(inputs.mapIndexed { i, input -> input.copy(path = normalize("inputs[$i].path", input.path, true)) })
            }
            "dialogue" -> {
                decodeProductionList<ShotDialogue>(op, key).forEach {
                    require(assetId.matches(it.characterAssetId) && it.text.length <= 7000 && (it.tone?.length ?: 0) <= 1000) {
                        "dialogue 的 characterAssetId 须为资产 ID，text 最多 7000 字，tone 最多 1000 字。"
                    }
                }
                value
            }
            "appearances" -> {
                decodeProductionList<ShotAppearance>(op, key).forEach {
                    require(assetId.matches(it.characterAssetId) && it.lookId.isNotBlank() && it.lookId.length <= 120 &&
                        it.referenceSlotIds.distinct().size == it.referenceSlotIds.size) {
                        "appearances 需要有效的 characterAssetId、1～120 字的 lookId 与不重复的 referenceSlotIds。"
                    }
                }
                value
            }
            "prop_asset_ids" -> {
                require(decodeProductionList<String>(op, key).all(assetId::matches)) {
                    "prop_asset_ids 的每项须为资产 ID（${assetId.pattern}）。"
                }
                value
            }
            "looks" -> {
                val looks = decodeProductionList<CharacterLook>(op, key)
                require(looks.isEmpty() || op["kind"]?.jsonPrimitive?.content == "character") { "looks 仅用于 kind=character 的角色资产。" }
                require(looks.map { it.id }.distinct().size == looks.size) { "looks 的 id 不能重复。" }
                Json.encodeToJsonElement(looks.mapIndexed { lookIndex, look ->
                    require(look.id.isNotBlank() && look.id.length <= 120 && look.name.isNotBlank() && look.name.length <= 120) {
                        "looks[$lookIndex] 的 id 和 name 需为 1～120 字。"
                    }
                    require((look.note?.length ?: 0) <= 1000 && (look.sheetNote?.length ?: 0) <= 1000) {
                        "looks[$lookIndex] 说明最多 1000 字。"
                    }
                    val ids = look.slots.map { it.id }
                    val missing = ProductionRules.defaultSlotOrder - ids.toSet()
                    require(missing.isEmpty()) { "looks 造型 ${look.id} 的 slots 缺少 ${missing.joinToString("、")}。可省略 slots 使用默认三项。" }
                    require(ids.distinct().size == ids.size) { "looks 造型 ${look.id} 的 slots.id 不能重复。" }
                    look.copy(
                        sheetPath = look.sheetPath?.let { normalize("looks[$lookIndex].sheetPath", it, true) },
                        slots = look.slots.mapIndexed { slotIndex, slot ->
                            require(slot.version == null || slot.version > 0) { "looks.slots.version 必须是正整数。" }
                            require((slot.note?.length ?: 0) <= 1000) { "looks.slots.note 最多 1000 字。" }
                            slot.copy(path = slot.path?.let { normalize("looks[$lookIndex].slots[$slotIndex].path", it, true) })
                        },
                    )
                })
            }
            "slots" -> {
                val slots = decodeProductionList<ReferenceSlot>(op, key)
                require(slots.map { it.id }.distinct().size == slots.size) { "slots 的 id 不能重复。" }
                Json.encodeToJsonElement(slots.mapIndexed { slotIndex, slot ->
                    require(slot.id.isNotBlank() && slot.id.length <= 120 && slot.name.isNotBlank() && slot.name.length <= 120) {
                        "slots[$slotIndex] 的 id 和 name 需为 1～120 字。"
                    }
                    require(slot.version == null || slot.version > 0) { "slots.version 必须是正整数。" }
                    require((slot.note?.length ?: 0) <= 1000) { "slots.note 最多 1000 字。" }
                    slot.copy(path = slot.path?.let { normalize("slots[$slotIndex].path", it, true) })
                })
            }
            "reference_views" -> {
                val views = decodeProductionList<ReferenceSlot>(op, key)
                val kind = op["kind"]?.jsonPrimitive?.content
                require(views.isEmpty() || kind in setOf("location", "prop", "style")) {
                    "reference_views 仅用于 location、prop、style 资产。"
                }
                require(views.size <= 30) { "reference_views 最多 30 项。" }
                require(views.map { it.id }.distinct().size == views.size) { "reference_views 的 id 不能重复。" }
                Json.encodeToJsonElement(views.mapIndexed { i, slot ->
                    require(slot.id.isNotBlank() && slot.id.length <= 120 && slot.name.isNotBlank() && slot.name.length <= 120) {
                        "reference_views[$i] 的 id 和 name 需为 1～120 字。"
                    }
                    require(slot.version == null || slot.version > 0) { "reference_views.version 必须是正整数。" }
                    require((slot.note?.length ?: 0) <= 1000) { "reference_views.note 最多 1000 字。" }
                    slot.copy(path = slot.path?.let { normalize("reference_views[$i].path", it, true) })
                })
            }
            "reference_voice" -> {
                val voice = decodeProductionField<ReferenceVoice?>(op, key)
                require(voice == null || op["kind"]?.jsonPrimitive?.content == "character") { "reference_voice 仅用于 kind=character 的角色资产。" }
                Json.encodeToJsonElement(voice?.copy(path = voice.path?.let { normalize("reference_voice.path", it, true) }))
            }
            "plan_snapshot" -> {
                val plan = decodeProductionField<TakePlanSnapshot>(op, key)
                validateProductionInputs("plan_snapshot.inputs", plan.inputs)
                Json.encodeToJsonElement(plan.copy(
                    inputs = plan.inputs.mapIndexed { i, input -> input.copy(path = normalize("plan_snapshot.inputs[$i].path", input.path, false)) },
                    mediaInputs = plan.mediaInputs.mapIndexed { i, input -> input.copy(location = normalize("plan_snapshot.mediaInputs[$i].location", input.location, false)) },
                ))
            }
            "documents" -> {
                val documents = decodeProductionList<DocumentRef>(op, key)
                require(documents.size <= 50 && documents.all { it.title.isNotBlank() && it.title.length <= 120 }) {
                    "documents 最多 50 项，每项 title 需为 1～120 字。"
                }
                Json.encodeToJsonElement(documents.mapIndexed { i, document -> document.copy(path = normalize("documents[$i].path", document.path, false)) })
            }
            else -> value
        }
        put(key, result)
    }
}

internal fun validateProductionInputs(key: String, inputs: List<ShotInput>) {
    require(inputs.size <= 12) { "$key 最多 12 项。" }
    inputs.forEach { input ->
        require(input.atSeconds == null || input.role == InputRole.ANCHOR && input.atSeconds.isFinite() && input.atSeconds >= 0) {
            "$key.atSeconds 仅用于 role=anchor，须为非负有限数值。"
        }
        require(input.label == null || input.label.isNotBlank() && input.label.length <= 120) { "$key.label 需为 1～120 字。" }
        require(input.assetId == null || assetId.matches(input.assetId)) { "$key.assetId 须为资产 ID（${assetId.pattern}）。" }
    }
}
