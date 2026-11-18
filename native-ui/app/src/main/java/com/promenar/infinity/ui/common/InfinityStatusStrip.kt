package com.promenar.infinity.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.keepCjkTailTogether

/** 状态条的四种语义色。 */
enum class InfinityStatusKind { Success, Error, Info, Warning }

/**
 * 成功、错误、提示、警示四色状态条，用于同步反馈与连接测试结果。
 * - [busy] 为 true 时图标换成转圈（如“正在测试连接”）；
 * - 读屏：错误立即播报，其余礼貌播报；
 * - [onDismiss] 给出时在尾部显示 48dp 关闭按钮。
 */
@Composable
fun InfinityStatusStrip(
    kind: InfinityStatusKind,
    message: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    dismissEnabled: Boolean = true,
    dismissModifier: Modifier = Modifier,
) {
    val (containerTarget, contentTarget) = statusStripColors(kind)
    val container by animateColorAsState(containerTarget, tween(InfinityMotion.DurationShort), label = "status_strip_container")
    val content by animateColorAsState(contentTarget, tween(InfinityMotion.DurationShort), label = "status_strip_content")
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = if (kind == InfinityStatusKind.Error) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
        shape = StripShape,
        color = container,
        contentColor = content,
        border = BorderStroke(0.8.dp, content.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(start = 14.dp, end = if (onDismiss != null) 0.dp else 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), color = content, strokeWidth = 2.dp)
            } else {
                Icon(statusStripIcon(kind), contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                text = message.keepCjkTailTogether(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, enabled = dismissEnabled, modifier = dismissModifier.size(48.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.status_strip_dismiss),
                        modifier = Modifier.size(18.dp),
                        tint = content.copy(alpha = if (dismissEnabled) 0.85f else 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun statusStripColors(kind: InfinityStatusKind): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    return when (kind) {
        InfinityStatusKind.Success -> domain.success.copy(alpha = 0.14f) to domain.success
        InfinityStatusKind.Error -> scheme.errorContainer.copy(alpha = 0.55f) to scheme.onErrorContainer
        InfinityStatusKind.Info -> domain.info.copy(alpha = 0.12f) to domain.info
        InfinityStatusKind.Warning -> domain.warning.copy(alpha = 0.14f) to domain.warning
    }
}

private fun statusStripIcon(kind: InfinityStatusKind): ImageVector = when (kind) {
    InfinityStatusKind.Success -> Icons.Rounded.CheckCircle
    InfinityStatusKind.Error -> Icons.Rounded.ErrorOutline
    InfinityStatusKind.Info -> Icons.Rounded.Info
    InfinityStatusKind.Warning -> Icons.Rounded.WarningAmber
}

private val StripShape = RoundedCornerShape(12.dp)
