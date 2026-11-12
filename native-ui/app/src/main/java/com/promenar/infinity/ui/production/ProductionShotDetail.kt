package com.promenar.infinity.ui.production

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.Asset
import com.promenar.infinity.data.production.AssetKind
import com.promenar.infinity.data.production.AssetStatus
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ReviewTarget
import com.promenar.infinity.data.production.PreviewKind
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.production.ShotInput
import com.promenar.infinity.data.production.Take
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityChoice
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.common.InfinityStatusStrip
import com.promenar.infinity.ui.media.MediaCardStyle
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 提示词与批注的长度上限，与后端校验一致。 */
internal const val PRODUCTION_PROMPT_MAX = 7000
internal const val PRODUCTION_NOTE_MAX = 1000

@Composable
internal fun TakeThumb(take: Take, index: Int, viewing: Boolean, selected: Boolean, serviceLabel: String = take.source.service, onClick: () -> Unit) {
    val label = stringResource(R.string.production_panel_take_number, index)
    Column(
        Modifier
            .width(112.dp)
            .semantics { this.selected = viewing }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .testTag(ProductionUiTags.TAKE),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
    ) {
        Box {
            ProductionThumbnail(
                take.path,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .then(if (viewing) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, ProductionThumbShape) else Modifier),
                contentDescription = label,
            )
            if (selected) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp),
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.production_review_approved),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(2.dp),
                    )
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (viewing) FontWeight.SemiBold else FontWeight.Normal,
            color = if (viewing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(serviceLabel, style = MaterialTheme.typography.labelSmall, maxLines = 2)
    }
}

@Composable
private fun NoteBlock(title: String, text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(InfinitySpacing.Medium), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
            ProductionSectionTitle(title)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 资产详情：查看各版本，对选中的版本批准、否决或建议。 */
@Composable
internal fun ProductionAssetSheet(
    asset: Asset,
    production: Production,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRun: (ProductionUserAction) -> Unit,
    onSubmit: suspend (ProductionUserAction) -> String?,
) {
    var versionNumber by rememberSaveable(asset.id) { mutableStateOf(asset.currentVersion ?: asset.versions.maxOfOrNull { it.number }) }
    val version = asset.versions.firstOrNull { it.number == versionNumber } ?: asset.versions.maxByOrNull { it.number }
    val usage = production.shotsUsingAsset(asset.id)
    InfinityBottomSheet(show = true, onDismiss = onDismiss, title = asset.name, scrollContent = true) {
        Column(Modifier.fillMaxWidth().testTag(ProductionUiTags.ASSET_SHEET), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                InfinityMetaLine(
                    segments = listOf(asset.kind.label(), pluralStringResource(R.plurals.production_panel_asset_usage, usage, usage)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip(asset.status.label(), asset.status.tone())
            }
            version?.let { current ->
                key(current.path) {
                    ProductionMediaReference(path = current.path, alt = asset.name, style = MediaCardStyle.TOOL, modifier = Modifier.fillMaxWidth())
                }
                current.note?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (asset.kind == AssetKind.SOUND && asset.versions.isNotEmpty()) {
                ScoreVersionList(asset, production, version?.number) { versionNumber = it }
            } else if (asset.versions.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                    ProductionSectionTitle(stringResource(R.string.production_panel_section_versions))
                    ProductionChoiceRow(
                        choices = asset.versions.sortedBy { it.number }.map {
                            InfinityChoice(it.number, stringResource(R.string.production_panel_version, it.number))
                        },
                        selected = version?.number ?: -1,
                        onSelect = { versionNumber = it },
                    )
                }
            }
            asset.description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            asset.userNote?.takeIf(String::isNotBlank)?.let { NoteBlock(stringResource(R.string.production_panel_section_user_note), it) }
            version?.let { selected ->
                ProductionReviewBlock(
                    production = production,
                    target = ReviewTarget.assetVersion(asset.id, selected.number),
                    label = listOf(asset.name, stringResource(R.string.production_panel_version, selected.number)),
                    busy = busy,
                    onRun = onRun,
                    onSubmit = onSubmit,
                    // 审核记录之前已定稿的当前版本视为已批准。
                    settled = asset.status == AssetStatus.LOCKED && selected.number == asset.currentVersion,
                )
            }
        }
    }
}
