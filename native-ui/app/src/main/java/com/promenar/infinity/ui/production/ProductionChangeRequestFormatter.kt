package com.promenar.infinity.ui.production

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.production.CharacterLook
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ShotAppearance
import com.promenar.infinity.data.production.ShotDialogue
import com.promenar.infinity.data.production.ShotFieldChange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull

private val changeDiffJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** 镜头内容变更请求中单条人类可读差异行。 */
internal data class ChangeRequestDiffLine(
    val label: String,
    val oldText: String?,
    val newText: String?,
    val stacked: Boolean = false,
    val headerAbove: Boolean = false,
    val labelSegments: List<String>? = null,
)

/** 本地化文案集合，便于纯单测和 Compose 界面共用同一格式化逻辑。 */
internal data class ChangeRequestFormatStrings(
    val fieldLabel: (String) -> String = ::defaultFieldLabel,
    val addedCharacter: String = "新增角色",
    val removedCharacter: String = "移除角色",
    val lookLabel: String = "造型",
    val slotsLabel: String = "参考位",
    val castOrder: String = "出场顺序",
    val slotFront: String = "正面全身",
    val slotSide: String = "侧面全身",
    val slotBack: String = "背面全身",
    val emptyValue: String = "无",
    val clearedValue: String = "已清空",
    val itemSeparator: String = "、",
    val durationText: (String) -> String = { "$it 秒" },
    val characterWithLook: (String, String) -> String = { name, look -> "$name（$look）" },
    val dialogueLine: (speaker: String, tone: String?, text: String) -> String = { speaker, tone, text ->
        val tonePart = tone?.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty()
        "$speaker$tonePart：「$text」"
    },
) {
    companion object {
        private fun defaultFieldLabel(field: String): String = when (field) {
            "title" -> "标题"
            "size" -> "景别"
            "camera" -> "机位"
            "cameraMovement" -> "运镜"
            "durationSeconds" -> "时长"
            "visual" -> "画面"
            "performance" -> "表演"
            "dialogue" -> "对白"
            "sound" -> "声音"
            "transition" -> "转场"
            "continuity" -> "衔接"
            "appearances" -> "出场"
            "locationAssetId" -> "场景"
            "propAssetIds" -> "道具"
            else -> "镜头内容"
        }
    }
}

@Composable
internal fun rememberChangeRequestFormatStrings(): ChangeRequestFormatStrings {
    val fieldLabels = mapOf(
        "title" to stringResource(R.string.p2_field_title),
        "size" to stringResource(R.string.p2_field_size),
        "camera" to stringResource(R.string.p2_field_camera),
        "cameraMovement" to stringResource(R.string.p2_field_movement),
        "durationSeconds" to stringResource(R.string.p2_field_duration_short),
        "visual" to stringResource(R.string.p2_field_visual),
        "performance" to stringResource(R.string.p2_field_performance),
        "dialogue" to stringResource(R.string.p2_field_dialogue),
        "sound" to stringResource(R.string.p2_field_sound),
        "transition" to stringResource(R.string.p2_field_transition),
        "continuity" to stringResource(R.string.p2_field_continuity),
        "appearances" to stringResource(R.string.p2_cast),
        "locationAssetId" to stringResource(R.string.production_panel_asset_location),
        "propAssetIds" to stringResource(R.string.production_panel_asset_prop),
    )
    val fallbackField = stringResource(R.string.p2_shot_content)
    val durationPattern = stringResource(R.string.production_panel_seconds)
    val charLookPattern = stringResource(R.string.p2_change_character_with_look)
    val dialoguePattern = stringResource(R.string.p2_change_dialogue_line)
    val dialogueTonePattern = stringResource(R.string.p2_change_dialogue_line_tone)

    return ChangeRequestFormatStrings(
        fieldLabel = { fieldLabels[it] ?: fallbackField },
        addedCharacter = stringResource(R.string.p2_change_add_character),
        removedCharacter = stringResource(R.string.p2_change_remove_character),
        lookLabel = stringResource(R.string.p2_look),
        slotsLabel = stringResource(R.string.p2_reference_slots),
        castOrder = stringResource(R.string.p2_change_cast_order),
        slotFront = stringResource(R.string.p2_slot_front),
        slotSide = stringResource(R.string.p2_slot_side),
        slotBack = stringResource(R.string.p2_slot_back),
        emptyValue = stringResource(R.string.p2_none),
        clearedValue = stringResource(R.string.production_lock_diff_cleared),
        itemSeparator = stringResource(R.string.p2_change_list_sep),
        durationText = { sec -> durationPattern.format(sec) },
        characterWithLook = { name, look -> charLookPattern.format(name, look) },
        dialogueLine = { speaker, tone, text ->
            val cleanTone = tone?.takeIf(String::isNotBlank)
            if (cleanTone != null) dialogueTonePattern.format(speaker, cleanTone, text)
            else dialoguePattern.format(speaker, text)
        },
    )
}

/** 将 [ShotFieldChange] 映射表转换为人类可读差异行，彻底屏蔽原始 JSON、英文键名与多余引号。 */
internal fun formatShotChangeRequestDiffs(
    production: Production,
    changes: Map<String, ShotFieldChange>,
    strings: ChangeRequestFormatStrings = ChangeRequestFormatStrings(),
): List<ChangeRequestDiffLine> {
    fun unwrapString(el: JsonElement): String? = when (el) {
        JsonNull -> null
        is JsonPrimitive -> el.content.takeIf(String::isNotBlank)
        else -> el.toString()
    }

    fun resolveAssetName(el: JsonElement): String? =
        unwrapString(el)?.let { id -> production.findAsset(id)?.name ?: id }

    fun resolveAssetList(el: JsonElement): String? {
        val arr = el as? JsonArray ?: return unwrapString(el)
        if (arr.isEmpty()) return null
        return arr.mapNotNull { item -> unwrapString(item)?.let { id -> production.findAsset(id)?.name ?: id } }
            .joinToString(strings.itemSeparator)
            .takeIf(String::isNotBlank)
    }

    fun formatDuration(el: JsonElement): String? =
        (el as? JsonPrimitive)?.doubleOrNull?.let { strings.durationText(formatShotSeconds(it)) } ?: unwrapString(el)

    return buildList {
        changes.forEach { (field, change) ->
            when (field) {
                "appearances" -> addAll(formatAppearancesDiff(production, change, strings))
                "dialogue" -> add(
                    ChangeRequestDiffLine(
                        label = strings.fieldLabel(field),
                        oldText = formatDialogue(production, change.oldValue, strings),
                        newText = formatDialogue(production, change.newValue, strings),
                        stacked = true,
                    ),
                )
                "locationAssetId" -> add(
                    ChangeRequestDiffLine(
                        label = strings.fieldLabel(field),
                        oldText = resolveAssetName(change.oldValue),
                        newText = resolveAssetName(change.newValue),
                    ),
                )
                "propAssetIds" -> {
                    val oldVal = resolveAssetList(change.oldValue)
                    val newVal = resolveAssetList(change.newValue)
                    add(
                        ChangeRequestDiffLine(
                            label = strings.fieldLabel(field),
                            oldText = oldVal,
                            newText = newVal,
                            stacked = (oldVal?.length ?: 0) > 18 || (newVal?.length ?: 0) > 18,
                        ),
                    )
                }
                "durationSeconds" -> add(
                    ChangeRequestDiffLine(
                        label = strings.fieldLabel(field),
                        oldText = formatDuration(change.oldValue),
                        newText = formatDuration(change.newValue),
                    ),
                )
                else -> {
                    val oldVal = unwrapString(change.oldValue)
                    val newVal = unwrapString(change.newValue)
                    val isLong = (oldVal?.length ?: 0) > 20 ||
                        (newVal?.length ?: 0) > 20 ||
                        oldVal?.contains('\n') == true ||
                        newVal?.contains('\n') == true
                    add(
                        ChangeRequestDiffLine(
                            label = strings.fieldLabel(field),
                            oldText = oldVal,
                            newText = newVal,
                            stacked = isLong,
                        ),
                    )
                }
            }
        }
    }
}

private fun formatAppearancesDiff(
    production: Production,
    change: ShotFieldChange,
    strings: ChangeRequestFormatStrings,
): List<ChangeRequestDiffLine> {
    val oldList = runCatching { changeDiffJson.decodeFromJsonElement<List<ShotAppearance>>(change.oldValue) }.getOrNull()
    val newList = runCatching { changeDiffJson.decodeFromJsonElement<List<ShotAppearance>>(change.newValue) }.getOrNull()
    if (oldList == null || newList == null) {
        return listOf(ChangeRequestDiffLine(strings.fieldLabel("appearances"), change.oldValue.toString(), change.newValue.toString(), stacked = true))
    }

    fun charName(id: String) = production.findAsset(id)?.name ?: id
    fun lookOf(charId: String, lookId: String) = production.findAsset(charId)?.looks?.firstOrNull { it.id == lookId }
    fun slotName(look: CharacterLook?, slotId: String): String =
        look?.slots?.firstOrNull { it.id == slotId }?.name?.takeIf(String::isNotBlank)
            ?: when (slotId) {
                "front" -> strings.slotFront
                "side" -> strings.slotSide
                "back" -> strings.slotBack
                else -> slotId
            }

    fun describeAppearance(app: ShotAppearance): String {
        val name = charName(app.characterAssetId)
        val lookName = lookOf(app.characterAssetId, app.lookId)?.name ?: app.lookId
        return strings.characterWithLook(name, lookName)
    }

    val lines = mutableListOf<ChangeRequestDiffLine>()
    val oldByChar = oldList.associateBy { it.characterAssetId }
    val newByChar = newList.associateBy { it.characterAssetId }
    val orderedIds = (oldList.map { it.characterAssetId } + newList.map { it.characterAssetId }).distinct()

    orderedIds.forEach { charId ->
        val prev = oldByChar[charId]
        val next = newByChar[charId]
        when {
            prev == null && next != null -> {
                lines += ChangeRequestDiffLine(strings.addedCharacter, null, describeAppearance(next))
            }
            prev != null && next == null -> {
                lines += ChangeRequestDiffLine(strings.removedCharacter, describeAppearance(prev), null)
            }
            prev != null && next != null -> {
                val name = charName(charId)
                if (prev.lookId != next.lookId) {
                    val oldLook = lookOf(charId, prev.lookId)?.name ?: prev.lookId
                    val newLook = lookOf(charId, next.lookId)?.name ?: next.lookId
                    lines += ChangeRequestDiffLine(
                        label = "$name ${strings.lookLabel}",
                        oldText = oldLook,
                        newText = newLook,
                        headerAbove = true,
                        labelSegments = listOf(name, strings.lookLabel),
                    )
                }
                if (prev.referenceSlotIds != next.referenceSlotIds) {
                    val oldLook = lookOf(charId, prev.lookId)
                    val newLook = lookOf(charId, next.lookId)
                    val oldStr = prev.referenceSlotIds.map { slotName(oldLook, it) }.joinToString(strings.itemSeparator).ifBlank { strings.emptyValue }
                    val newStr = next.referenceSlotIds.map { slotName(newLook, it) }.joinToString(strings.itemSeparator).ifBlank { strings.clearedValue }
                    lines += ChangeRequestDiffLine(
                        label = "$name ${strings.slotsLabel}",
                        oldText = oldStr,
                        newText = newStr,
                        stacked = oldStr.length > 16 || newStr.length > 16,
                        headerAbove = true,
                        labelSegments = listOf(name, strings.slotsLabel),
                    )
                }
            }
        }
    }

    if (lines.isEmpty() && oldList != newList) {
        lines += ChangeRequestDiffLine(
            label = strings.castOrder,
            oldText = oldList.joinToString(strings.itemSeparator, transform = ::describeAppearance),
            newText = newList.joinToString(strings.itemSeparator, transform = ::describeAppearance),
            stacked = true,
        )
    }
    return lines
}

private fun formatDialogue(
    production: Production,
    element: JsonElement,
    strings: ChangeRequestFormatStrings,
): String? {
    val lines = runCatching { changeDiffJson.decodeFromJsonElement<List<ShotDialogue>>(element) }.getOrNull() ?: return element.toString()
    if (lines.isEmpty()) return null
    return lines.joinToString("\n") { line ->
        val speaker = production.findAsset(line.characterAssetId)?.name ?: line.characterAssetId
        strings.dialogueLine(speaker, line.tone, line.text)
    }
}
