package com.promenar.infinity.ui.production

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.promenar.infinity.R
import com.promenar.infinity.data.production.AssetKind
import com.promenar.infinity.data.production.AssetStatus
import com.promenar.infinity.data.production.InputRole
import com.promenar.infinity.data.production.PreviewKind
import com.promenar.infinity.data.production.ProductionStage
import com.promenar.infinity.data.production.ShotMode
import com.promenar.infinity.data.production.ShotStatus
import com.promenar.infinity.data.production.StageStatus
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.media.LocalWorkspaceMedia
import com.promenar.infinity.ui.media.WorkspaceMediaEnvironment
import com.promenar.infinity.ui.media.WorkspaceMediaModel
import com.promenar.infinity.ui.media.rememberWorkspaceMediaResolution
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.launch

/** 制作面板的测试标签。 */
object ProductionUiTags {
    const val PANEL = "production_panel"
    const val EMPTY = "production_panel_empty"
    const val TAB_OVERVIEW = "production_tab_overview"
    const val TAB_SCRIPT = "production_tab_script"
    const val TAB_ASSETS = "production_tab_assets"
    const val TAB_STORYBOARD = "production_tab_storyboard"
    const val MANIFEST_BANNER = "production_manifest_banner"
    const val MANIFEST_ADOPT = "production_manifest_adopt"
    const val MANIFEST_OVERWRITE = "production_manifest_overwrite"
    const val ERROR = "production_panel_error"
    const val SHOT_DETAIL = "production_shot_detail"
    const val SHOT_ROW = "production_shot_row"
    const val SHOT_SELECT = "production_shot_select"
    const val SHOT_REDO = "production_shot_redo"
    const val SHOT_UNLOCK = "production_shot_unlock"
    const val SHOT_PROMPT_EDIT = "production_shot_prompt_edit"
    const val TAKE = "production_take"
    const val ASSET_CARD = "production_asset_card"
    const val ASSET_SHEET = "production_asset_sheet"
    const val ASSET_APPROVE = "production_asset_approve"
    const val ASSET_REJECT = "production_asset_reject"
    const val ASSET_UNLOCK = "production_asset_unlock"
    const val REASON_FIELD = "production_reason_field"
    const val PROMPT_FIELD = "production_prompt_field"
    const val DIALOG_CONFIRM = "production_dialog_confirm"
}

internal val ProductionThumbShape = RoundedCornerShape(8.dp)

/** 状态色调，映射到主题的状态色。 */
internal enum class ProductionTone { NEUTRAL, INFO, WARNING, SUCCESS, ERROR }

internal fun ShotStatus.tone(): ProductionTone = when (this) {
    ShotStatus.TODO -> ProductionTone.NEUTRAL
    ShotStatus.GENERATING -> ProductionTone.INFO
    ShotStatus.REVIEW -> ProductionTone.WARNING
    ShotStatus.LOCKED -> ProductionTone.SUCCESS
    ShotStatus.REDO -> ProductionTone.ERROR
}

internal fun AssetStatus.tone(): ProductionTone = when (this) {
    AssetStatus.DRAFT -> ProductionTone.NEUTRAL
    AssetStatus.REVIEW -> ProductionTone.WARNING
    AssetStatus.LOCKED -> ProductionTone.SUCCESS
    AssetStatus.REDO -> ProductionTone.ERROR
}

internal fun StageStatus.tone(): ProductionTone = when (this) {
    StageStatus.NOT_STARTED, StageStatus.SKIPPED -> ProductionTone.NEUTRAL
    StageStatus.IN_PROGRESS -> ProductionTone.INFO
    StageStatus.LOCKED -> ProductionTone.SUCCESS
}

@Composable
internal fun ProductionStatusChip(text: String, tone: ProductionTone, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val (container, content) = when (tone) {
        ProductionTone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        ProductionTone.INFO -> domain.infoContainer to domain.onInfoContainer
        ProductionTone.WARNING -> domain.warningContainer to domain.onWarningContainer
        ProductionTone.SUCCESS -> domain.successContainer to domain.onSuccessContainer
        ProductionTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
    Surface(shape = RoundedCornerShape(50), color = container, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun ShotStatus.label(): String = stringResource(
    when (this) {
        ShotStatus.TODO -> R.string.production_panel_shot_todo
        ShotStatus.GENERATING -> R.string.production_panel_shot_generating
        ShotStatus.REVIEW -> R.string.production_panel_shot_review
        ShotStatus.LOCKED -> R.string.production_panel_shot_locked
        ShotStatus.REDO -> R.string.production_panel_shot_redo
    },
)

@Composable
internal fun AssetStatus.label(): String = stringResource(
    when (this) {
        AssetStatus.DRAFT -> R.string.production_panel_asset_draft
        AssetStatus.REVIEW -> R.string.production_panel_asset_review
        AssetStatus.LOCKED -> R.string.production_panel_asset_locked
        AssetStatus.REDO -> R.string.production_panel_asset_redo
    },
)

@Composable
internal fun StageStatus.label(): String = stringResource(
    when (this) {
        StageStatus.NOT_STARTED -> R.string.production_panel_stage_not_started
        StageStatus.IN_PROGRESS -> R.string.production_panel_stage_in_progress
        StageStatus.LOCKED -> R.string.production_panel_stage_locked
        StageStatus.SKIPPED -> R.string.production_panel_stage_skipped
    },
)

@Composable
internal fun ProductionStage.label(): String = stringResource(
    when (this) {
        ProductionStage.SCRIPT -> R.string.production_panel_stage_script
        ProductionStage.VISUAL_DEV -> R.string.production_panel_stage_visual_dev
        ProductionStage.STORYBOARD -> R.string.production_panel_stage_storyboard
        ProductionStage.ANIMATIC -> R.string.production_panel_stage_animatic
        ProductionStage.GENERATION -> R.string.production_panel_stage_generation
        ProductionStage.SOUND -> R.string.production_panel_stage_sound
        ProductionStage.EDIT -> R.string.production_panel_stage_edit
    },
)

@Composable
internal fun AssetKind.label(): String = stringResource(
    when (this) {
        AssetKind.CHARACTER -> R.string.production_panel_asset_character
        AssetKind.LOCATION -> R.string.production_panel_asset_location
        AssetKind.PROP -> R.string.production_panel_asset_prop
        AssetKind.STYLE -> R.string.production_panel_asset_style
    },
)

@Composable
internal fun InputRole.label(): String = stringResource(
    when (this) {
        InputRole.FIRST_FRAME -> R.string.production_panel_role_first_frame
        InputRole.LAST_FRAME -> R.string.production_panel_role_last_frame
        InputRole.ANCHOR -> R.string.production_panel_role_anchor
        InputRole.REFERENCE -> R.string.production_panel_role_reference
    },
)

@Composable
internal fun ShotMode.label(): String = stringResource(
    when (this) {
        ShotMode.TEXT -> R.string.production_panel_mode_text
        ShotMode.FIRST_FRAME -> R.string.production_panel_mode_first_frame
        ShotMode.FIRST_LAST -> R.string.production_panel_mode_first_last
        ShotMode.REFERENCE -> R.string.production_panel_mode_reference
    },
)

@Composable
internal fun PreviewKind.label(): String = stringResource(
    when (this) {
        PreviewKind.KEYFRAME -> R.string.production_panel_preview_keyframe
        PreviewKind.DRAFT_CLIP -> R.string.production_panel_preview_draft_clip
        PreviewKind.TEXT_CARD -> R.string.production_panel_preview_text_card
    },
)

@Composable
internal fun secondsLabel(seconds: Double): String = stringResource(R.string.production_panel_seconds, formatShotSeconds(seconds))

/**
 * 工作区媒体缩略图：图片原图、视频取帧，裁切填满。路径为空、媒体环境缺失或文件不可用时显示占位图标。
 * 只展示，不响应点按；点按行为由外层决定。
 */
@Composable
internal fun ProductionThumbnail(
    path: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val placeholderTint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier.clip(ProductionThumbShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val environment = LocalWorkspaceMedia.current
        if (path == null || environment == null) {
            Icon(Icons.Rounded.Movie, contentDescription = contentDescription, tint = placeholderTint, modifier = Modifier.size(20.dp))
        } else {
            ResolvedThumbnail(environment, path, contentDescription)
        }
    }
}

@Composable
private fun BoxScope.ResolvedThumbnail(
    environment: WorkspaceMediaEnvironment,
    path: String,
    contentDescription: String?,
) {
    val placeholderTint = MaterialTheme.colorScheme.onSurfaceVariant
    val virtualPath = workspacePath(path)
    val resolved = rememberWorkspaceMediaResolution(environment, virtualPath)
    when (val resolution = resolved?.resolution) {
        is MediaResolution.Available -> {
            AsyncImage(
                model = WorkspaceMediaModel(environment.sessionId, virtualPath, resolved.identity),
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
            if (resolution.ref.kind == MediaKind.VIDEO) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.infinityDomainColors.overlayContent,
                        modifier = Modifier.padding(2.dp).size(16.dp),
                    )
                }
            }
        }
        is MediaResolution.Unavailable -> Icon(Icons.Rounded.BrokenImage, contentDescription = contentDescription, tint = placeholderTint, modifier = Modifier.size(20.dp))
        null -> Unit
    }
}

/** 小节标题。 */
@Composable
internal fun ProductionSectionTitle(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier,
    )
}

/**
 * 单字段文本输入对话框：打回原因、编辑提示词共用。[onSubmit] 返回错误文本，成功时返回 null 并关闭。
 */
@Composable
internal fun ProductionTextDialog(
    title: String,
    label: String,
    initial: String,
    confirmText: String,
    maxLength: Int,
    minLines: Int,
    fieldTag: String,
    onDismiss: () -> Unit,
    onSubmit: suspend (String) -> String?,
    allowEmpty: Boolean = true,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    InfinityConfirmDialog(
        title = title,
        message = "",
        confirmText = confirmText,
        onConfirm = {
            saving = true
            scope.launch {
                try {
                    error = onSubmit(text.trim())
                    if (error == null) onDismiss()
                } finally {
                    saving = false
                }
            }
        },
        onCancel = onDismiss,
        onDismissRequest = { if (!saving) onDismiss() },
        confirmEnabled = !saving && (allowEmpty || text.isNotBlank()),
        cancelEnabled = !saving,
        confirmButtonModifier = Modifier.heightIn(min = 48.dp).testTag(ProductionUiTags.DIALOG_CONFIRM),
        cancelButtonModifier = Modifier.heightIn(min = 48.dp),
        content = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(maxLength) },
                    label = { Text(label) },
                    enabled = !saving,
                    minLines = minLines,
                    textStyle = LocalTextStyle.current.forInput(),
                    modifier = Modifier.fillMaxWidth().testTag(fieldTag),
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = InfinitySpacing.Small),
                    )
                }
            }
        },
    )
}

/** 单选标签：一行排列，放不下时整行横向滚动。 */
@Composable
internal fun <T> ProductionChoiceRow(
    choices: List<com.promenar.infinity.ui.common.InfinityChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    com.promenar.infinity.ui.common.InfinityTagScrollRow(modifier = modifier, spacing = com.promenar.infinity.ui.theme.InfinitySpacing.Small) {
        choices.forEach { choice ->
            androidx.compose.material3.FilterChip(
                selected = choice.value == selected,
                onClick = { onSelect(choice.value) },
                label = { Text(choice.label, maxLines = 1) },
            )
        }
    }
}
