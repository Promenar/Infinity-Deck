package com.promenar.infinity.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 选择面板或分段控件中的一项。 */
@Immutable
class InfinityChoice<T>(
    val value: T,
    val label: String,
    val icon: ImageVector? = null,
    val tag: String? = null,
)

/** 已选项的名称，按 [choices] 的顺序用 [separator] 连接；没有选中项时为 null。 */
fun <T> selectedChoiceSummary(choices: List<InfinityChoice<T>>, selected: Set<T>, separator: String): String? =
    choices.filter { it.value in selected }.joinToString(separator) { it.label }.ifEmpty { null }

/**
 * 多选面板：每项一行，点按切换，选中项行尾显示勾；改动即时生效，关闭面板即完成。
 * 页面上用一行摘要（已选项名称）作为入口，数量再多也不会把页面撑高。
 */
@Composable
fun <T> InfinityMultiChoiceSheet(
    show: Boolean,
    title: String,
    choices: List<InfinityChoice<T>>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    InfinityBottomSheet(show = show, onDismiss = onDismiss, title = title, scrollContent = true) {
        val haptics = LocalInfinityHaptics.current
        Column(Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Large)) {
            choices.forEach { choice ->
                val isSelected = choice.value in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = InfinitySpacing.MinimumTouchTarget + InfinitySpacing.XSmall)
                        .toggleable(value = isSelected, role = Role.Checkbox) {
                            haptics.toggle(it)
                            onToggle(choice.value)
                        }
                        .then(choice.tag?.let { Modifier.testTag(it) } ?: Modifier)
                        .padding(horizontal = InfinitySpacing.RowHorizontal),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                ) {
                    choice.icon?.let {
                        Icon(it, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        choice.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Spacer(Modifier.width(20.dp))
                    }
                }
            }
        }
    }
}
