package com.promenar.infinity.ui.production

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.data.production.Asset
import com.promenar.infinity.data.production.AssetKind
import com.promenar.infinity.data.production.AssetStatus
import com.promenar.infinity.data.production.ChangeSource
import com.promenar.infinity.data.production.CharacterLook
import com.promenar.infinity.data.production.DialogueAudioSource
import com.promenar.infinity.data.production.Episode
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionKind
import com.promenar.infinity.data.production.ReferenceSlot
import com.promenar.infinity.data.production.ReviewEntry
import com.promenar.infinity.data.production.ReviewKind
import com.promenar.infinity.data.production.ReviewTarget
import com.promenar.infinity.data.production.Scene
import com.promenar.infinity.data.production.Season
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.production.ShotAppearance
import com.promenar.infinity.data.production.ShotChangeRequest
import com.promenar.infinity.data.production.ShotDialogue
import com.promenar.infinity.data.production.ShotFieldChange
import com.promenar.infinity.data.production.ShotMode
import com.promenar.infinity.data.production.verdictOf
import com.promenar.infinity.ui.common.FrostedChromeRole
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPanePresentation
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.LocalFrostedChromeMaterial
import com.promenar.infinity.ui.common.LocalInfinityPanePresentation
import com.promenar.infinity.ui.common.rememberFrostedChromeMaterial
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement

/*
 * 制作面板「内容变更请求」人类可读差异卡片高保真原型（v1，隔离于 debug 源码）：
 * 1. 结构化字段翻译：将 appearances / dialogue / locationAssetId / propAssetIds / durationSeconds 及文本字段翻译为中文资产名、造型名与槽位变化，彻底消除原始 JSON 与英文键名；
 * 2. 变更理由前置：有 change_note 时以信息提示条展示在差异块上方，无说明的旧记录在元信息行标注「未附说明」，不留空白占位；
 * 3. 长短自适应排布：短字段使用「左标签 + 右删除线旧值 → 加粗新值」双列行（与聊天锁项审批卡保持一致），长文本/对白自动切换为「原 / 改」纵向对照，保障窄屏和大字号零挤压；
 * 4. 批量审批入口：待审 ≥ 2 条时在分组标题右侧提供紧凑「全部批准」胶囊按钮，单条仍复用全站统一「审批 / 修改审批」胶囊入口。
 */

private val protoJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private data class ProtoFieldDiffLine(
    val label: String,
    val oldText: String?,
    val newText: String?,
    val stacked: Boolean = false,
    val headerAbove: Boolean = false,
)

private fun protoSlotLabel(look: CharacterLook?, slotId: String): String =
    look?.slots?.firstOrNull { it.id == slotId }?.name?.takeIf(String::isNotBlank)
        ?: when (slotId) {
            "front" -> "正面全身"
            "side" -> "侧面全身"
            "back" -> "背面全身"
            else -> slotId
        }

private fun protoFormatAppearancesDiff(
    production: Production,
    change: ShotFieldChange,
): List<ProtoFieldDiffLine> {
    val oldList = runCatching { protoJson.decodeFromJsonElement<List<ShotAppearance>>(change.oldValue) }.getOrNull()
    val newList = runCatching { protoJson.decodeFromJsonElement<List<ShotAppearance>>(change.newValue) }.getOrNull()
    if (oldList == null || newList == null) {
        return listOf(ProtoFieldDiffLine("出场", change.oldValue.toString(), change.newValue.toString(), stacked = true))
    }

    fun charName(id: String) = production.findAsset(id)?.name ?: id
    fun lookOf(charId: String, lookId: String) = production.findAsset(charId)?.looks?.firstOrNull { it.id == lookId }
    fun formatItem(app: ShotAppearance): String {
        val name = charName(app.characterAssetId)
        val look = lookOf(app.characterAssetId, app.lookId)
        val lookName = look?.name ?: app.lookId
        return "$name（$lookName）"
    }

    val lines = mutableListOf<ProtoFieldDiffLine>()
    val oldByChar = oldList.associateBy { it.characterAssetId }
    val newByChar = newList.associateBy { it.characterAssetId }
    val orderedIds = (oldList.map { it.characterAssetId } + newList.map { it.characterAssetId }).distinct()

    orderedIds.forEach { charId ->
        val prev = oldByChar[charId]
        val next = newByChar[charId]
        when {
            prev == null && next != null -> {
                lines += ProtoFieldDiffLine("新增角色", null, formatItem(next))
            }
            prev != null && next == null -> {
                lines += ProtoFieldDiffLine("移除角色", formatItem(prev), null)
            }
            prev != null && next != null -> {
                val name = charName(charId)
                if (prev.lookId != next.lookId) {
                    val oldLook = lookOf(charId, prev.lookId)?.name ?: prev.lookId
                    val newLook = lookOf(charId, next.lookId)?.name ?: next.lookId
                    lines += ProtoFieldDiffLine(
                        label = "$name · 造型",
                        oldText = oldLook,
                        newText = newLook,
                        stacked = false,
                        headerAbove = true,
                    )
                }
                if (prev.referenceSlotIds != next.referenceSlotIds) {
                    val oldLook = lookOf(charId, prev.lookId)
                    val newLook = lookOf(charId, next.lookId)
                    val oldSlots = prev.referenceSlotIds.map { protoSlotLabel(oldLook, it) }
                    val newSlots = next.referenceSlotIds.map { protoSlotLabel(newLook, it) }
                    val oldStr = oldSlots.joinToString("、").ifBlank { "无" }
                    val newStr = newSlots.joinToString("、").ifBlank { "已清空" }
                    lines += ProtoFieldDiffLine(
                        label = "$name · 参考位",
                        oldText = oldStr,
                        newText = newStr,
                        stacked = oldStr.length > 16 || newStr.length > 16,
                    )
                }
            }
        }
    }

    if (lines.isEmpty() && oldList != newList) {
        lines += ProtoFieldDiffLine(
            label = "出场顺序",
            oldText = oldList.joinToString("、", transform = ::formatItem),
            newText = newList.joinToString("、", transform = ::formatItem),
            stacked = true,
        )
    }
    return lines
}

private fun protoFormatDialogue(production: Production, element: JsonElement): String? {
    val lines = runCatching { protoJson.decodeFromJsonElement<List<ShotDialogue>>(element) }.getOrNull() ?: return element.toString()
    if (lines.isEmpty()) return null
    return lines.joinToString("\n") { line ->
        val speaker = production.findAsset(line.characterAssetId)?.name ?: line.characterAssetId
        val tone = line.tone?.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty()
        "$speaker$tone：「${line.text}」"
    }
}

private fun protoFormatFieldChanges(
    production: Production,
    changes: Map<String, ShotFieldChange>,
): List<ProtoFieldDiffLine> {
    fun unwrapString(el: JsonElement): String? = when (el) {
        JsonNull -> null
        is JsonPrimitive -> el.content.takeIf(String::isNotBlank)
        else -> el.toString()
    }
    fun resolveAsset(el: JsonElement): String? = unwrapString(el)?.let { id -> production.findAsset(id)?.name ?: id }
    fun resolveAssetList(el: JsonElement): String? {
        val arr = el as? JsonArray ?: return unwrapString(el)
        if (arr.isEmpty()) return null
        return arr.mapNotNull { item -> unwrapString(item)?.let { id -> production.findAsset(id)?.name ?: id } }.joinToString("、")
    }
    fun formatDuration(el: JsonElement): String? =
        (el as? JsonPrimitive)?.doubleOrNull?.let { "${formatShotSeconds(it)} 秒" } ?: unwrapString(el)

    fun fieldLabel(key: String): String = when (key) {
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
        else -> key
    }

    return buildList {
        changes.forEach { (field, change) ->
            when (field) {
                "appearances" -> addAll(protoFormatAppearancesDiff(production, change))
                "dialogue" -> {
                    add(
                        ProtoFieldDiffLine(
                            label = fieldLabel(field),
                            oldText = protoFormatDialogue(production, change.oldValue),
                            newText = protoFormatDialogue(production, change.newValue),
                            stacked = true,
                        ),
                    )
                }
                "locationAssetId" -> {
                    add(ProtoFieldDiffLine(fieldLabel(field), resolveAsset(change.oldValue), resolveAsset(change.newValue)))
                }
                "propAssetIds" -> {
                    val o = resolveAssetList(change.oldValue)
                    val n = resolveAssetList(change.newValue)
                    add(ProtoFieldDiffLine(fieldLabel(field), o, n, stacked = (o?.length ?: 0) > 18 || (n?.length ?: 0) > 18))
                }
                "durationSeconds" -> {
                    add(ProtoFieldDiffLine(fieldLabel(field), formatDuration(change.oldValue), formatDuration(change.newValue)))
                }
                else -> {
                    val o = unwrapString(change.oldValue)
                    val n = unwrapString(change.newValue)
                    val isLong = (o?.length ?: 0) > 20 || (n?.length ?: 0) > 20 || o?.contains('\n') == true || n?.contains('\n') == true
                    add(ProtoFieldDiffLine(fieldLabel(field), o, n, stacked = isLong))
                }
            }
        }
    }
}

@Composable
private fun ProtoChangeRequestCard(
    request: ShotChangeRequest,
    production: Production,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val target = ReviewTarget.change(request.id)
    val shot = production.findShot(request.episodeId, request.shotCode)?.shot
    val shotTitle = shot?.title?.takeIf(String::isNotBlank)
    val codeLabel = listOfNotNull(
        request.episodeId.takeIf { production.showEpisodeNumbers() },
        request.shotCode,
    ).joinToString(" · ")
    val sourceText = if (request.source == ChangeSource.AGENT) "智能体提议" else "你的修改"
    val diffLines = protoFormatFieldChanges(production, request.changes)
    val metaSegments = buildList {
        add(sourceText)
        add("${request.changes.size} 项变更")
        if (request.note.isNullOrBlank()) add("未附变更说明")
    }
    val chip = production.reviewChipSpec(target)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 顶行：编号 + 右侧状态标签不争抢镜头标题空间；下方全宽展示完整镜头名与来源元信息
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.EditNote,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = codeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                chip?.let { ProductionStatusChip(it.text, it.tone) }
            }
            if (shotTitle != null) {
                Text(
                    text = shotTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            InfinityMetaLine(
                segments = metaSegments,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
            )
        }

        // 变更原因条（有 change_note 时前置展示）
        request.note?.takeIf(String::isNotBlank)?.let { reason ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = domain.infoContainer.copy(alpha = 0.42f),
                border = BorderStroke(0.5.dp, domain.onInfoContainer.copy(alpha = 0.18f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = domain.onInfoContainer,
                        modifier = Modifier.padding(top = 2.dp).size(15.dp),
                    )
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall.forReading(),
                        color = scheme.onSurface,
                    )
                }
            }
        }

        // 结构化人类可读差异块
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                diffLines.forEachIndexed { index, line ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = scheme.outlineVariant.copy(alpha = 0.45f),
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    ProtoDiffFieldRow(line)
                }
            }
        }

        // 复用全站统一单入口审批操作栏
        ProductionReviewActions(
            production = production,
            target = target,
            label = listOf(request.shotCode, "内容变更请求"),
            busy = false,
            onRun = {},
            onSubmit = { null },
        )
    }
}

@Composable
private fun ProtoDiffFieldRow(line: ProtoFieldDiffLine) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    if (line.stacked) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = line.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = scheme.surfaceContainerHighest,
                ) {
                    Text(
                        text = "原",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                Text(
                    text = line.oldText ?: "未设置",
                    style = MaterialTheme.typography.bodySmall.forReading(),
                    color = scheme.onSurfaceVariant,
                    textDecoration = if (line.oldText != null) TextDecoration.LineThrough else TextDecoration.None,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = domain.infoContainer.copy(alpha = 0.72f),
                ) {
                    Text(
                        text = "改",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = domain.onInfoContainer,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                Text(
                    text = line.newText ?: "已清空",
                    style = MaterialTheme.typography.bodySmall.forReading(),
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else if (line.headerAbove) {
        val annotated: AnnotatedString = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = scheme.onSurfaceVariant,
                    textDecoration = if (line.oldText != null) TextDecoration.LineThrough else TextDecoration.None,
                ),
            ) {
                append(line.oldText ?: "未设置")
            }
            append("  →  ")
            withStyle(SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.Medium)) {
                append(line.newText ?: "已清空")
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = line.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodySmall.forReading(),
            )
        }
    } else {
        val annotated: AnnotatedString = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = scheme.onSurfaceVariant,
                    textDecoration = if (line.oldText != null) TextDecoration.LineThrough else TextDecoration.None,
                ),
            ) {
                append(line.oldText ?: "未设置")
            }
            append("  →  ")
            withStyle(SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.Medium)) {
                append(line.newText ?: "已清空")
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = line.label,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(0.24f),
            )
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodySmall.forReading(),
                modifier = Modifier.weight(0.76f),
            )
        }
    }
}

private fun protoChangeRequestSample(): Production {
    val shen = Asset(
        id = "shen-zhilan",
        kind = AssetKind.CHARACTER,
        name = "沈知澜",
        status = AssetStatus.LOCKED,
        looks = listOf(
            CharacterLook(
                id = "daoist",
                name = "宗门法衣",
                slots = listOf(
                    ReferenceSlot("front", "正面全身", "refs/p1.png"),
                    ReferenceSlot("side", "侧面全身", "refs/p2.png"),
                    ReferenceSlot("face", "面部近景", "refs/p3.png"),
                    ReferenceSlot("back", "背面全身", "refs/p4.png"),
                ),
                locked = true,
            ),
            CharacterLook(
                id = "rain-cloak",
                name = "雨夜战袍",
                slots = listOf(
                    ReferenceSlot("front", "正面全身", "refs/r1.png"),
                    ReferenceSlot("face", "面部近景", "refs/r3.png"),
                ),
                locked = true,
            ),
        ),
    )
    val sword = Asset("jinglan-sword", AssetKind.PROP, "惊澜剑", status = AssetStatus.LOCKED)
    val stream = Asset("qixia-stream-platform", AssetKind.LOCATION, "听雨仙阁后山临溪石坪", status = AssetStatus.LOCKED)
    val oldPier = Asset("old-pier", AssetKind.LOCATION, "雾港老码头", status = AssetStatus.LOCKED)

    val shots = listOf(
        Shot("SC01-01", "雨中静立", durationSeconds = 5.0, mode = ShotMode.REFERENCE),
        Shot("SC01-03", "低身三百六十度旋斩", durationSeconds = 5.0, mode = ShotMode.REFERENCE),
        Shot("SC01-04", "腾空反手下劈", durationSeconds = 5.0, mode = ShotMode.REFERENCE),
    )

    val req1 = ShotChangeRequest(
        id = "cr-1",
        episodeId = "S0E01",
        sceneCode = "SC01",
        shotCode = "SC01-03",
        changes = mapOf(
            "appearances" to ShotFieldChange(
                oldValue = protoJson.encodeToJsonElement(
                    listOf(ShotAppearance("shen-zhilan", "daoist", listOf("front", "side", "face", "back"))),
                ),
                newValue = protoJson.encodeToJsonElement(
                    listOf(ShotAppearance("shen-zhilan", "rain-cloak", listOf("front", "face"))),
                ),
            ),
        ),
        source = ChangeSource.AGENT,
        note = "切换为「雨夜战袍」造型以匹配暴雨石坪暗部湿衣质感，避免白衣过曝。",
        changedAt = 18,
    )

    val req2 = ShotChangeRequest(
        id = "cr-2",
        episodeId = "S0E01",
        sceneCode = "SC01",
        shotCode = "SC01-04",
        changes = mapOf(
            "durationSeconds" to ShotFieldChange(JsonPrimitive(5.0), JsonPrimitive(6.0)),
            "locationAssetId" to ShotFieldChange(JsonPrimitive("old-pier"), JsonPrimitive("qixia-stream-platform")),
            "propAssetIds" to ShotFieldChange(
                protoJson.encodeToJsonElement(emptyList<String>()),
                protoJson.encodeToJsonElement(listOf("jinglan-sword")),
            ),
            "visual" to ShotFieldChange(
                JsonPrimitive("沈知澜原地起跳反手下劈，镜头仰拍跟进。"),
                JsonPrimitive("沈知澜蹬石腾空，反手举剑顺雨幕弧线劈落，落地溅起冷青色水环。"),
            ),
            "dialogue" to ShotFieldChange(
                protoJson.encodeToJsonElement(emptyList<ShotDialogue>()),
                protoJson.encodeToJsonElement(listOf(ShotDialogue("shen-zhilan", "破！", "冷冽沉声", DialogueAudioSource.NATIVE))),
            ),
        ),
        source = ChangeSource.AGENT,
        note = "补足腾空发力与水环落点节拍，延长 1 秒避免收招动作被截断。",
        changedAt = 19,
    )

    // 历史兼容样例：无 change_note 且为旧版仅清空参考位的记录，也需清晰可读
    val req3 = ShotChangeRequest(
        id = "cr-3",
        episodeId = "S0E01",
        sceneCode = "SC01",
        shotCode = "SC01-01",
        changes = mapOf(
            "appearances" to ShotFieldChange(
                oldValue = protoJson.encodeToJsonElement(
                    listOf(ShotAppearance("shen-zhilan", "daoist", listOf("front", "side", "face", "back"))),
                ),
                newValue = protoJson.encodeToJsonElement(
                    listOf(ShotAppearance("shen-zhilan", "daoist", emptyList())),
                ),
            ),
        ),
        source = ChangeSource.AGENT,
        note = null,
        changedAt = 17,
    )

    return Production(
        id = "proto-jinglan",
        title = "惊澜三十秒",
        kind = ProductionKind.FILM,
        rootPath = "productions/jinglan",
        seasons = listOf(
            Season(
                id = "S0",
                number = 0,
                episodes = listOf(
                    Episode(
                        id = "S0E01",
                        number = 1,
                        title = "惊澜三十秒",
                        scenes = listOf(Scene("SC01", 1, "外 临溪石坪 雨夜", storyboardLocked = true, shots = shots)),
                    ),
                ),
            ),
        ),
        assets = listOf(shen, sword, stream, oldPier),
        changeRequests = listOf(req1, req2, req3),
        reviews = listOf(
            ReviewEntry(ReviewTarget.change("cr-1"), ReviewKind.APPROVE, at = 1_000),
        ),
        createdAt = 1,
        updatedAt = 2,
    )
}

@Composable
private fun ProtoChangeRequestSectionCanvas(
    mode: InfinityThemeMode,
    inspectorChrome: Boolean = false,
) {
    val production = protoChangeRequestSample()
    val pending = production.changeRequests
    val unapprovedCount = pending.count { production.verdictOf(ReviewTarget.change(it.id))?.kind != ReviewKind.APPROVE }

    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        val chrome = if (inspectorChrome) rememberFrostedChromeMaterial(FrostedChromeRole.DOCKED_INSPECTOR) else null
        val surfaceColor = chrome?.frostedTint ?: chrome?.container ?: MaterialTheme.colorScheme.background
        CompositionLocalProvider(
            LocalFrostedChromeMaterial provides chrome,
            LocalInfinityPanePresentation provides if (inspectorChrome) InfinityPanePresentation.Inspector else InfinityPanePresentation.Page,
            LocalProductionSend provides ProductionSendState(count = 1, sending = false, queued = false, onSend = {}),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (inspectorChrome && mode == InfinityThemeMode.DARK) Color(0xFF0A0B0E) else MaterialTheme.colorScheme.background),
            ) {
                Surface(color = surfaceColor, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        contentPadding = ProductionPagePadding,
                        verticalArrangement = Arrangement.spacedBy(ProductionSectionGap),
                    ) {
                        item("changes") {
                            ProductionSection(
                                title = "内容变更请求",
                                titleSegments = listOf("内容变更请求", "待审 ${pending.size} 条"),
                                titleAction = if (unapprovedCount >= 2) {
                                    {
                                        InfinityPillButton(
                                            text = "全部批准",
                                            onClick = {},
                                            tone = InfinityPillTone.PRIMARY,
                                            icon = Icons.Rounded.DoneAll,
                                            compact = true,
                                            modifier = Modifier.padding(end = 12.dp),
                                        )
                                    }
                                } else null,
                            ) {
                                pending.forEachIndexed { idx, req ->
                                    if (idx > 0) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                            modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal),
                                        )
                                    }
                                    ProtoChangeRequestCard(req, production)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Light · 412dp", widthDp = 412, heightDp = 1220, locale = "zh-rCN", showBackground = true)
@Composable
fun ChangeRequestOverviewLightPreview() {
    ProtoChangeRequestSectionCanvas(mode = InfinityThemeMode.LIGHT)
}

@Preview(name = "Dark Inspector · 412dp", widthDp = 412, heightDp = 1220, locale = "zh-rCN", showBackground = true)
@Composable
fun ChangeRequestOverviewDarkPreview() {
    ProtoChangeRequestSectionCanvas(mode = InfinityThemeMode.DARK, inspectorChrome = true)
}

@Preview(name = "Narrow Large Font · 360dp 1.3x", widthDp = 360, heightDp = 1480, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Composable
fun ChangeRequestNarrowLargeFontPreview() {
    ProtoChangeRequestSectionCanvas(mode = InfinityThemeMode.LIGHT)
}

@Preview(name = "Real Impl Light · 412dp", widthDp = 412, heightDp = 1320, locale = "zh-rCN", showBackground = true)
@Composable
fun RealChangeRequestManagementLightPreview() {
    val production = protoChangeRequestSample()
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        CompositionLocalProvider(
            LocalProductionSend provides ProductionSendState(count = 1, sending = false, queued = false, onSend = {}),
        ) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                ProductionManagementTab(
                    production = production,
                    tasks = emptyList(),
                    busy = false,
                    services = ProductionPanelServices(),
                    onScene = {},
                    onAsset = {},
                    onRun = {},
                    onSubmit = { null },
                    onCancelTask = {},
                )
            }
        }
    }
}

@Preview(name = "Real Impl Dark Inspector · 412dp", widthDp = 412, heightDp = 1320, locale = "zh-rCN", showBackground = true)
@Composable
fun RealChangeRequestManagementDarkPreview() {
    val production = protoChangeRequestSample()
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        val chrome = rememberFrostedChromeMaterial(FrostedChromeRole.DOCKED_INSPECTOR)
        val surfaceColor = chrome.frostedTint ?: chrome.container
        CompositionLocalProvider(
            LocalFrostedChromeMaterial provides chrome,
            LocalInfinityPanePresentation provides InfinityPanePresentation.Inspector,
            LocalProductionSend provides ProductionSendState(count = 1, sending = false, queued = false, onSend = {}),
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0A0B0E))) {
                Surface(color = surfaceColor, modifier = Modifier.fillMaxSize()) {
                    ProductionManagementTab(
                        production = production,
                        tasks = emptyList(),
                        busy = false,
                        services = ProductionPanelServices(),
                        onScene = {},
                        onAsset = {},
                        onRun = {},
                        onSubmit = { null },
                        onCancelTask = {},
                    )
                }
            }
        }
    }
}
