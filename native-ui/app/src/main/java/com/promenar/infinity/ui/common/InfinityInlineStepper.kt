package com.promenar.infinity.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun InfinityInlineStepper(
    title: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
    enabled: Boolean = true,
) {
    val decreaseLabel = stringResource(R.string.p0_decrease_setting, title)
    val increaseLabel = stringResource(R.string.p0_increase_setting, title)
    Row(
        modifier.height(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp))
            .semantics {
                stateDescription = value
                customActions = buildList {
                    if (enabled && canDecrease) add(CustomAccessibilityAction(decreaseLabel) { onDecrease(); true })
                    if (enabled && canIncrease) add(CustomAccessibilityAction(increaseLabel) { onIncrease(); true })
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfinityStepperButton(Icons.Rounded.Remove, decreaseLabel, enabled && canDecrease, onDecrease)
        Text(value, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.widthIn(min = 32.dp))
        InfinityStepperButton(Icons.Rounded.Add, increaseLabel, enabled && canIncrease, onIncrease)
    }
}

/** 点按单步、长按连续调整的加减按钮；[outlined] 为真时画成带描边的方形按钮，用于独立的数值编辑区。 */
@Composable
internal fun InfinityStepperButton(icon: ImageVector, label: String, enabled: Boolean, onStep: () -> Unit, outlined: Boolean = false) {
    val haptics = LocalInfinityHaptics.current
    val tickingStep: () -> Unit = {
        haptics.perform(InfinityHapticEvent.STEP)
        onStep()
    }
    val currentStep by rememberUpdatedState(tickingStep)
    val silentStep by rememberUpdatedState(onStep)
    val currentEnabled by rememberUpdatedState(enabled)
    val interaction = remember { MutableInteractionSource() }
    val visualSize = if (outlined) 44.dp else 32.dp
    Box(
        Modifier.width(48.dp).requiredHeight(48.dp).minimumInteractiveComponentSize()
            .semantics {
                contentDescription = label
                role = Role.Button
                if (enabled) onClick { currentStep(); true } else disabled()
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var repeated = false
                detectTapGestures(
                    onTap = { if (!repeated && currentEnabled) currentStep() },
                    onPress = { offset ->
                        repeated = false
                        // 手势自行处理，按压反馈画在居中的可见按钮上：坐标换到按钮内，水波从手指处展开。
                        val inset = (size.width - visualSize.toPx()) / 2f to (size.height - visualSize.toPx()) / 2f
                        val press = PressInteraction.Press(Offset(offset.x - inset.first, offset.y - inset.second))
                        interaction.emit(press)
                        coroutineScope {
                            val repeat = launch {
                                delay(400)
                                var count = 0
                                while (currentEnabled) {
                                    repeated = true
                                    // 长按连续调整时每 4 步振动一次，避免持续嗡鸣。
                                    if (count % 4 == 0) currentStep() else silentStep()
                                    count++
                                    delay(80)
                                }
                            }
                            try {
                                val released = tryAwaitRelease()
                                interaction.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                            } finally { repeat.cancel() }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val visual = if (outlined) {
            Modifier.size(visualSize).pressFeedback(interaction, RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
        } else {
            Modifier.size(visualSize).pressFeedback(interaction, CircleShape)
        }
        Box(visual, contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp).alpha(if (enabled) 1f else 0.38f), MaterialTheme.colorScheme.primary)
        }
    }
}
