package com.promenar.infinity.ui.production

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.theme.InfinitySpacing

internal val ProductionPagePadding = PaddingValues(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small)

@Composable
internal fun ProductionBlock(title: String? = null, content: @Composable ColumnScope.() -> Unit) = ProductionSection(title, content = content)

@Composable
internal fun ProductionValueRow(title: String, value: String? = null, enabled: Boolean = true, onClick: (() -> Unit)? = null) =
    ProductionRow(title, value = value, enabled = enabled, onClick = onClick)

@Composable
internal fun ProductionBackHeader(title: String, subtitle: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) =
    ProductionTopBar(title, listOf(subtitle).filter(String::isNotBlank), onBack, actions = actions)

@Composable
internal fun ProductionCommand(text: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    InfinityPillButton(text = text, onClick = onClick, enabled = enabled, tone = if (primary) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL, modifier = Modifier.heightIn(min = 48.dp))
}

@Composable
internal fun <T> ProductionOptionSheet(title: String, options: List<Pair<T, String>>, selected: T?, onDismiss: () -> Unit, onSelect: (T) -> Unit) =
    ProductionChoiceSheet(title, options.map { (value, label) -> ProductionChoice(value, label) }, selected, onDismiss, emptyText = stringResource(R.string.p2_no_channels), onSelect = onSelect)

@Composable
internal fun ReferenceSlot.displayName(): String = when (id) {
    "front" -> stringResource(R.string.p2_slot_front)
    "side" -> stringResource(R.string.p2_slot_side)
    "back" -> stringResource(R.string.p2_slot_back)
    else -> name
}

@Composable
internal fun ShotPlanSummary(shot: Shot, channel: MediaChannel?, channelLabel: String) {
    val packing = if (shot.mode == ShotMode.REFERENCE && channel != null) stringResource(
        if (channel.capabilities.characterReferenceMode == CharacterReferenceMode.COMPOSITE) R.string.p2_composite else R.string.p2_separate,
    ) else null
    InfinityMetaLine(
        planSummarySegments(listOf(channelLabel), shot.mode.label(), packing, shot.promptUserEdited, stringResource(R.string.p2_edited)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun PromptFormat.label(): String = stringResource(when (this) {
    PromptFormat.H3 -> R.string.p2_format_h3
    PromptFormat.SEEDANCE -> R.string.p2_format_seedance
    PromptFormat.GENERIC -> R.string.p2_format_generic
})

@Composable
internal fun MediaTask.stateLabel(pendingRegistrations: Set<String> = emptySet()): String = stringResource(taskStatusRes(pendingRegistrations))

internal fun MediaTask.taskStatusRes(pendingRegistrations: Set<String>): Int = if (awaitingRegistration(pendingRegistrations)) {
    R.string.production_wire_pending_registration
} else when (state.name) {
    // 旧数据里的待批准任务升级后转为已取消。
    "CANCELED", "AWAITING_APPROVAL" -> R.string.p2_task_canceled
    "QUEUED" -> R.string.p2_task_queued
    "SUBMITTING" -> R.string.p2_task_submitting
    "RUNNING" -> R.string.p2_task_running
    "DOWNLOADING" -> R.string.p2_task_downloading
    "COMPLETED" -> R.string.p2_task_completed
    "RESULT_UNKNOWN" -> R.string.p2_task_unknown
    else -> R.string.p2_task_failed
}

@Composable
internal fun ProductionShotText(shot: Shot, production: Production) {
    InfinityMetaLine(listOfNotNull(shot.size, shot.camera, shot.cameraMovement, secondsLabel(shot.durationSeconds)), style = MaterialTheme.typography.labelMedium)
    listOfNotNull(shot.visual, shot.performance).filter(String::isNotBlank).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    shot.dialogue.forEach { line ->
        InfinityMetaLine(
            listOfNotNull(production.findAsset(line.characterAssetId)?.name ?: line.characterAssetId, line.text, line.tone),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    listOfNotNull(shot.sound, shot.transition, shot.continuity).filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let {
        InfinityMetaLine(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
