package com.promenar.infinity.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.theme.forInput

/** 紧凑输入框的高度。 */
val CompactTextFieldHeight = 46.dp

/**
 * 紧凑描边输入框：高 46dp、圆角 12dp、14sp 文字，其余行为与 Material 描边输入框一致（浮动标签、错误态、辅助文字）。
 * 单行；尾部图标按钮保留 48dp 触控区，出现时输入框随之略高。
 */
@Composable
fun CompactOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp).forInput()
    val textColor = when {
        !enabled -> colors.disabledTextColor
        isError -> colors.errorTextColor
        focused -> colors.focusedTextColor
        else -> colors.unfocusedTextColor
    }
    // 浮动标签的字号取自 bodyLarge 与 bodySmall；把 bodyLarge 换成 14sp，标签在框内时与输入文字同高，整体高度落在 46dp。
    MaterialTheme(typography = MaterialTheme.typography.copy(bodyLarge = textStyle)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.heightIn(min = CompactTextFieldHeight),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle.copy(color = textColor).forInput(),
            keyboardOptions = keyboardOptions,
            singleLine = true,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value,
                    innerTextField = innerTextField,
                    enabled = enabled,
                    singleLine = true,
                    visualTransformation = visualTransformation,
                    interactionSource = interaction,
                    isError = isError,
                    label = if (label != null) { { Text(label) } } else null,
                    placeholder = if (placeholder != null) { { Text(placeholder) } } else null,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    supportingText = if (supportingText != null) { { Text(supportingText) } } else null,
                    colors = colors,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    container = {
                        OutlinedTextFieldDefaults.Container(
                            enabled = enabled,
                            isError = isError,
                            interactionSource = interaction,
                            colors = colors,
                            shape = RoundedCornerShape(12.dp),
                        )
                    },
                )
            },
        )
    }
}
