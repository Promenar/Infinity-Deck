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
internal fun ProductionBlock(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        title?.let { ProductionSectionTitle(it) }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(InfinitySpacing.Medium), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small), content = content)
        }
    }
}

@Composable
internal fun ProductionValueRow(title: String, value: String? = null, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        value?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)) }
    }
}

@Composable
internal fun ProductionBackHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = InfinitySpacing.Large), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.production_panel_back))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ProductionCommand(text: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    InfinityPillButton(text = text, onClick = onClick, enabled = enabled, tone = if (primary) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL, modifier = Modifier.heightIn(min = 48.dp))
}

@Composable
internal fun <T> ProductionOptionSheet(title: String, options: List<Pair<T, String>>, selected: T?, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    InfinityBottomSheet(show = true, onDismiss = onDismiss, title = title, scrollContent = true) {
        InfinitySegmentedControl(
            segments = options.map { (value, label) -> InfinitySegment(value, label) },
            selected = selected,
            onSelect = { onSelect(it); onDismiss() },
            modifier = Modifier.fillMaxWidth(),
        )
        if (options.isEmpty()) Text(stringResource(R.string.p2_no_channels), style = MaterialTheme.typography.bodyMedium)
    }
}

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
    Text(planSummary(channelLabel, shot.mode.label(), packing, shot.promptUserEdited, stringResource(R.string.p2_edited)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun PromptFormat.label(): String = stringResource(when (this) {
    PromptFormat.H3 -> R.string.p2_format_h3
    PromptFormat.SEEDANCE -> R.string.p2_format_seedance
    PromptFormat.GENERIC -> R.string.p2_format_generic
})

@Composable
internal fun MediaTask.stateLabel(): String = stringResource(when (state.name) {
    "AWAITING_APPROVAL" -> R.string.p2_task_approval
    "QUEUED" -> R.string.p2_task_queued
    "SUBMITTING" -> R.string.p2_task_submitting
    "RUNNING" -> R.string.p2_task_running
    "DOWNLOADING" -> R.string.p2_task_downloading
    "COMPLETED" -> R.string.p2_task_completed
    "RESULT_UNKNOWN" -> R.string.p2_task_unknown
    else -> R.string.p2_task_failed
})

@Composable
internal fun ProductionShotText(shot: Shot, production: Production) {
    InfinityMetaLine(listOfNotNull(shot.size, shot.camera, shot.cameraMovement, secondsLabel(shot.durationSeconds)), style = MaterialTheme.typography.labelMedium)
    listOfNotNull(shot.visual, shot.performance).filter(String::isNotBlank).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    shot.dialogue.forEach { line ->
        Text(listOfNotNull(production.findAsset(line.characterAssetId)?.name ?: line.characterAssetId, line.text, line.tone).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
    }
    listOfNotNull(shot.sound, shot.transition, shot.continuity).filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let {
        InfinityMetaLine(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
