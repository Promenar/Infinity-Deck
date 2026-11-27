package com.promenar.infinity.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.theme.InfinityMonospace

/** 能力图标组里的一项。 */
@Immutable
data class ModelCapabilityBadge(val icon: ImageVector, val label: String)

private const val MaxVisibleCapabilityIcons = 5

/**
 * 模型列表行元数据副行：类型微标 → 上下文长度紧随其后（杜绝被多能力图标挤出裁切）→ 无框单色能力图标串（超限折叠为 +N）。
 * 能力只画图标，读屏把整组朗读为“能力：视觉、工具调用…”（[capabilitiesDescription]）。
 */
@Composable
fun ModelCapabilityBadges(
    typeLabel: String,
    capabilities: List<ModelCapabilityBadge>,
    contextLabel: String?,
    capabilitiesDescription: String,
    modifier: Modifier = Modifier,
    contextDescription: String? = null,
    typeContainerColor: Color? = null,
    typeContentColor: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val badgeBg = typeContainerColor ?: scheme.secondaryContainer.copy(alpha = 0.72f)
    val badgeFg = typeContentColor ?: scheme.onSecondaryContainer
    val visibleIcons = capabilities.take(MaxVisibleCapabilityIcons)
    val overflowCount = (capabilities.size - visibleIcons.size).coerceAtLeast(0)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = typeLabel,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = badgeFg,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .background(badgeBg, BadgeShape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (!contextLabel.isNullOrBlank()) {
            Text(
                text = contextLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = InfinityMonospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.5.sp,
                ),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = if (contextDescription != null) {
                    Modifier.clearAndSetSemantics { this.contentDescription = contextDescription }
                } else {
                    Modifier
                },
            )
        }
        if (visibleIcons.isNotEmpty()) {
            Row(
                modifier = Modifier.clearAndSetSemantics { contentDescription = capabilitiesDescription },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                visibleIcons.forEach { badge ->
                    Icon(
                        imageVector = badge.icon,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant.copy(alpha = 0.74f),
                        modifier = Modifier.size(13.5.dp),
                    )
                }
                if (overflowCount > 0) {
                    Text(
                        text = "+$overflowCount",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                        color = scheme.onSurfaceVariant.copy(alpha = 0.74f),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

private val BadgeShape = RoundedCornerShape(6.dp)
