package com.promenar.infinity.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.forInput

@Composable
fun InfinitySearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.common_search_placeholder),
    onSearch: (() -> Unit)? = null,
    onEscape: (() -> Unit)? = null,
    // 作用于输入框本身，供调用方挂接焦点请求等。
    fieldModifier: Modifier = Modifier,
    /** 为 true 时采用无硬描边浅底胶囊风格，聚焦时才轻显主色轮廓。 */
    borderless: Boolean = false,
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // 聚焦时亮边渐亮（强调减速），失焦时按标准曲线渐暗，避免亮边瞬间亮灭。
    val unfocusedBorder = if (borderless) Color.Transparent else MaterialTheme.colorScheme.outlineVariant
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) MaterialTheme.colorScheme.primary else unfocusedBorder,
        animationSpec = tween(
            durationMillis = InfinityMotion.DurationMedium,
            easing = if (isFocused) InfinityMotion.EmphasizedDecelerate else InfinityMotion.Standard,
        ),
        label = "searchBarBorder",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(if (borderless) 44.dp else InfinitySpacing.MinimumTouchTarget),
        shape = if (borderless) CircleShape else MaterialTheme.shapes.extraLarge,
        color = if (borderless) {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                val overlayDismiss = LocalInfinityOverlayDismiss.current
                var fieldValueState by remember { mutableStateOf(TextFieldValue(text = value)) }
                val fieldValue = if (fieldValueState.text == value) {
                    fieldValueState
                } else {
                    TextFieldValue(text = value, selection = TextRange(value.length))
                }
                SideEffect {
                    if (fieldValueState.text != value) fieldValueState = fieldValue
                }
                // 输入区占满整条搜索框高度，点框内任意位置都能聚焦。
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { next ->
                        fieldValueState = next
                        if (next.text != value) onValueChange(next.text)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ).forInput(),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { onSearch?.invoke() ?: focusManager.clearFocus() }
                    ),
                    decorationBox = { inner -> Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) { inner() } },
                    modifier = fieldModifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        // 中文输入法组词态放行 Esc 取消候选；非组词态在 Pre-IME 阶段优先退出搜索/清空查询/关闭外层弹层。
                        .infinityImeSafeKeyEvent { event ->
                            if (fieldValue.composition != null) return@infinityImeSafeKeyEvent false
                            when {
                                event.isPlainEscapeDown() -> {
                                    when {
                                        onEscape != null -> onEscape()
                                        value.isNotEmpty() -> onValueChange("")
                                        overlayDismiss != null -> overlayDismiss()
                                        else -> focusManager.clearFocus()
                                    }
                                    true
                                }
                                event.isPlainEscapeUp() -> true
                                else -> false
                            }
                        }
                        .onFocusChanged { isFocused = it.isFocused }
                        .semantics { contentDescription = placeholder }
                )

                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget).infinityPointerHand(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Clear,
                        contentDescription = stringResource(R.string.common_cd_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(InfinitySpacing.XLarge),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun InfinitySearchBarEmptyPreview() {
    InfinityTheme {
        InfinitySearchBar(
            value = "",
            onValueChange = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun InfinitySearchBarWithTextPreview() {
    InfinityTheme {
        InfinitySearchBar(
            value = "DeepSeek",
            onValueChange = {}
        )
    }
}
