package com.promenar.infinity.data.production

import kotlinx.serialization.json.*

/** 制作层规则，生成层在创建任务前调用同一门槛。 */
object ProductionRules {
    val defaultSlotOrder = listOf("front", "side", "back")
    private val json = Json { encodeDefaults = true }
    val contentFields = listOf("title", "size", "durationSeconds", "camera", "cameraMovement", "visual", "performance", "dialogue", "sound", "transition", "continuity", "appearances", "locationAssetId", "propAssetIds")

    fun defaultSlotIds(look: CharacterLook): List<String> = defaultSlotOrder.filter { id -> look.slots.any { it.id == id } }

    /** 使用场内最大序号加一，并保留既有数字宽度；空场从 01 开始。 */
    fun nextShotCode(scene: Scene): String {
        val suffixes = scene.shots.map { it.code.removePrefix("${scene.code}-") }
        val next = (suffixes.map { it.toBigInteger() }.maxOrNull() ?: java.math.BigInteger.ZERO) + java.math.BigInteger.ONE
        val width = suffixes.maxOfOrNull { it.length } ?: 2
        return "${scene.code}-${next.toString().padStart(width, '0')}"
    }

    fun generationRejection(production: Production, episodeId: String, shotCode: String): String? {
        val episode = production.seasons.flatMap { it.episodes }.firstOrNull { it.id == episodeId }
            ?: return "集 $episodeId 不存在。"
        val scene = episode.scenes.firstOrNull { sc -> sc.shots.any { it.code == shotCode } }
            ?: return "镜头 $shotCode 不存在。"
        return if (!production.experimental && !scene.storyboardLocked) "场 ${scene.code} 尚未锁定，请由用户锁定后生成。" else null
    }

    fun removeSlot(look: CharacterLook, slotId: String): CharacterLook {
        require(slotId !in defaultSlotOrder) { "默认参考位不可删除。" }
        require(look.slots.any { it.id == slotId }) { "参考位不存在。" }
        return look.copy(slots = look.slots.filterNot { it.id == slotId })
    }

    fun changes(old: Shot, updated: Shot): Map<String, ShotFieldChange> {
        val a = json.encodeToJsonElement(Shot.serializer(), old).jsonObject
        val b = json.encodeToJsonElement(Shot.serializer(), updated).jsonObject
        return contentFields.mapNotNull { key -> if (a[key] != b[key]) key to ShotFieldChange(a.getValue(key), b.getValue(key)) else null }.toMap()
    }

    fun applyChanges(shot: Shot, changes: Map<String, ShotFieldChange>): Shot {
        require(changes.keys.all { it in contentFields }) { "变更请求包含无效字段。" }
        val fields = json.encodeToJsonElement(Shot.serializer(), shot).jsonObject.toMutableMap()
        changes.forEach { (key, change) ->
            require(fields[key] == change.oldValue) { "镜头字段 $key 已变化，请重新提交变更。" }
            fields[key] = change.newValue
        }
        return json.decodeFromJsonElement(Shot.serializer(), JsonObject(fields))
    }

    fun referenceWarnings(production: Production, shot: Shot): List<String> = shot.appearances.mapNotNull { appearance ->
        val asset = production.assets.firstOrNull { it.id == appearance.characterAssetId && it.kind == AssetKind.CHARACTER }
        val look = asset?.looks?.firstOrNull { it.id == appearance.lookId }
        when {
            asset == null -> "角色 ${appearance.characterAssetId} 不存在。"
            asset.status != AssetStatus.LOCKED -> "角色 ${asset.name} 的参考包尚未定稿。"
            look == null -> "角色 ${asset.name} 的造型不存在。"
            defaultSlotOrder.any { id -> look.slots.none { it.id == id && it.path != null } } || appearance.referenceSlotIds.any { id -> look.slots.none { it.id == id && it.path != null } } -> "角色 ${asset.name} 的所选参考位尚未备齐。"
            else -> null
        }
    }

    fun planSnapshot(shot: Shot) = TakePlanSnapshot(shot.channelId, shot.mode, shot.inputs, shot.prompt)
}
