package com.promenar.infinity.ui.welcome

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.onboarding.OnboardingStep
import com.promenar.infinity.ui.chat.LedMorphWordmark
import com.promenar.infinity.ui.chat.WordmarkPointer
import com.promenar.infinity.ui.chat.trackWordmarkPointer
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinityPureDarkTheme
import com.promenar.infinity.ui.theme.hierarchicalTransition
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.delay
import com.promenar.infinity.ui.common.InfinityMetaLine

/** 宽于此值（折叠屏内屏、平板）时左栏常驻字标与竖排步骤，右栏是内容。 */
private val WideLayoutMinWidth = 600.dp
/** 步骤内元素依次上浮的错峰间隔。 */
private const val RiseStaggerMillis = 45L

/**
 * 首次引导：语言 → Linux（仅侧载 arm64）→ 连接提供商 → 选择模型 → 创建 Agent → 准备就绪。
 * 外观固定为深色加纯黑背景、稀疏星空，主色跟随当前主题色；字标是会话页同一套灯珠变形。
 * 页面切换沿用共享 X 轴；系统“移除动画”时灯珠停在字标、星点不闪、元素不上浮。
 */
@Composable
internal fun OnboardingScreen(
    state: OnboardingUiState,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    var linuxSheetOpen by rememberSaveable { mutableStateOf(false) }
    // 静态预览没有返回分发器；运行时系统返回与左上角返回按钮一致。
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        when (state.step) {
            OnboardingStep.MODEL -> BackHandler(enabled = !state.busy, onBack = actions.onBackToProvider)
            OnboardingStep.AGENT -> BackHandler(enabled = !state.busy, onBack = actions.onBackToModel)
            else -> Unit
        }
    }
    InfinityPureDarkTheme {
        OnboardingBackdrop(modifier) {
            AnimatedContent(
                targetState = state.step == OnboardingStep.LANGUAGE,
                transitionSpec = { InfinityMotion.sharedAxisX(forward = !targetState) },
                label = "onboarding_intro",
                modifier = Modifier.fillMaxSize(),
            ) { intro ->
                if (intro) {
                    OnboardingIntro(onLanguageSelected = actions.onLanguageSelected, busy = state.busy)
                } else {
                    OnboardingFrame(state, actions, onOpenLinux = { linuxSheetOpen = true })
                }
            }
        }
        val linux = state.linux
        if (linuxSheetOpen && linux != null) {
            OnboardingLinuxSheet(
                status = linux,
                actions = actions,
                onDismiss = { linuxSheetOpen = false },
            )
        }
        state.restore?.let { OnboardingRestoreSheet(it, actions.restore) }
    }
}

@Composable
private fun OnboardingFrame(state: OnboardingUiState, actions: OnboardingActions, onOpenLinux: () -> Unit) {
    val railSteps = onboardingRailSteps(state.linux != null)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val wide = maxWidth >= WideLayoutMinWidth
        val ready = state.step == OnboardingStep.FIRST_CHAT
        Row(Modifier.fillMaxSize()) {
            if (wide && !ready) OnboardingSidePanel(railSteps, state.step)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                OnboardingHeader(
                    state = state,
                    railSteps = railSteps,
                    compact = !wide,
                    actions = actions,
                    onOpenLinux = onOpenLinux,
                )
                AnimatedContent(
                    targetState = state.step,
                    transitionSpec = { hierarchicalTransition { it.ordinal } },
                    label = "onboarding_step",
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) { step ->
                    // 宽屏配置页贴着左栏、限宽 520dp；就绪页居中单栏。
                    val centered = step == OnboardingStep.FIRST_CHAT
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag(OnboardingTags.step(step)),
                        contentAlignment = if (centered || !wide) Alignment.TopCenter else Alignment.TopStart,
                    ) {
                        val contentWidth = if (centered) OnboardingDimens.CenteredMaxWidth else OnboardingDimens.ContentMaxWidth
                        Box(Modifier.widthIn(max = contentWidth).fillMaxSize()) {
                            when (step) {
                                OnboardingStep.LINUX -> state.linux?.let { OnboardingLinuxStep(it, actions) }
                                OnboardingStep.PROVIDER -> OnboardingProviderStep(state, actions, wide)
                                OnboardingStep.MODEL -> OnboardingModelStep(state, actions)
                                OnboardingStep.AGENT -> OnboardingAgentStep(state, actions)
                                OnboardingStep.FIRST_CHAT -> OnboardingReadyStep(state, actions)
                                OnboardingStep.LANGUAGE, OnboardingStep.RECORDING_SETUP, OnboardingStep.COMPLETED -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 灯珠变形字标；点按跳到下一段变形。 */
@Composable
internal fun OnboardingWordmark(modifier: Modifier = Modifier, dispersed: Boolean = false) {
    val pointer = remember { WordmarkPointer() }
    LedMorphWordmark(
        modifier = modifier.trackWordmarkPointer(pointer),
        dispersed = dispersed,
        pointer = pointer,
        centerFraction = 0.5f,
    )
}

/** 宽屏左栏：字标与竖排步骤，固定不随内容滚动。 */
@Composable
private fun OnboardingSidePanel(steps: List<OnboardingStep>, current: OnboardingStep) {
    Column(
        Modifier
            .width(260.dp)
            .fillMaxHeight()
            .padding(start = 40.dp, top = 48.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        OnboardingWordmark(Modifier.offset(x = (-10).dp).size(220.dp, 140.dp))
        OnboardingVerticalRail(steps, current)
    }
}

/**
 * 页头：返回按钮与 Linux 胶囊一行；下面是手机上的小字标、“第 n 步，共 N 步 · 标题”与分段进度条。
 * 宽屏的字标与步骤在左栏，这里只留步骤文字。
 */
@Composable
private fun OnboardingHeader(
    state: OnboardingUiState,
    railSteps: List<OnboardingStep>,
    compact: Boolean,
    actions: OnboardingActions,
    onOpenLinux: () -> Unit,
) {
    val back = when (state.step) {
        OnboardingStep.MODEL -> actions.onBackToProvider
        OnboardingStep.AGENT -> actions.onBackToModel
        else -> null
    }
    val index = railSteps.indexOf(state.step)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(visible = back != null, enter = fadeIn(), exit = fadeOut()) {
                    IconButton(
                        onClick = { back?.invoke() },
                        enabled = !state.busy,
                        modifier = Modifier.testTag(OnboardingTags.BACK),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.onboarding_back))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            OnboardingLinuxCapsule(
                status = state.linux?.takeIf { state.step != OnboardingStep.LINUX },
                onClick = onOpenLinux,
            )
        }
        if (index >= 0) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = OnboardingDimens.PageMargin),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (compact) OnboardingWordmark(Modifier.offset(x = (-12).dp).size(176.dp, 64.dp))
                AnimatedContent(
                    targetState = index,
                    transitionSpec = { InfinityMotion.sharedAxisY(forward = targetState > initialState) },
                    label = "onboarding_step_label",
                ) { shown ->
                    InfinityMetaLine(
                        segments = listOf(
                            stringResource(R.string.onboarding_step_of, shown + 1, railSteps.size),
                            stringResource(railSteps[shown].railLabel()),
                        ),
                        style = OnboardingType.eyebrow(),
                        color = onboardingFaintText,
                    )
                }
                if (compact) OnboardingProgressBar(count = railSteps.size, current = index)
            }
        }
    }
}

private fun OnboardingStep.railLabel(): Int = when (this) {
    OnboardingStep.LINUX -> R.string.onboarding_rail_linux
    OnboardingStep.PROVIDER -> R.string.onboarding_rail_provider
    OnboardingStep.MODEL -> R.string.onboarding_rail_model
    else -> R.string.onboarding_rail_agent
}

/** 宽屏竖排步骤：已完成打勾，当前步骤主色描边，未到的步骤淡显；不画连线。 */
@Composable
private fun OnboardingVerticalRail(steps: List<OnboardingStep>, current: OnboardingStep) {
    val currentIndex = steps.indexOf(current)
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEachIndexed { index, step ->
            val done = index < currentIndex
            val active = index == currentIndex
            val primary = MaterialTheme.colorScheme.primary
            val border by animateColorAsState(
                if (done || active) primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                tween(InfinityMotion.DurationMedium),
                label = "rail_node_border",
            )
            val fill by animateColorAsState(if (done) primary else Color.Transparent, tween(InfinityMotion.DurationMedium), label = "rail_node_fill")
            Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(22.dp).background(fill, CircleShape).border(1.5.dp, border, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
                    } else {
                        Text(
                            "${index + 1}",
                            style = OnboardingType.caption(),
                            color = if (active) primary else onboardingFaintText,
                        )
                    }
                }
                Text(
                    stringResource(step.railLabel()),
                    style = OnboardingType.body(),
                    color = when {
                        active -> MaterialTheme.colorScheme.onSurface
                        done -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> onboardingFaintText
                    },
                )
            }
        }
    }
}

/** 顶部 Linux 胶囊：安装中显示进度环与百分比，中断显示警示，就绪显示对勾；点按打开 Linux 面板。样式与卡片一致。 */
@Composable
private fun OnboardingLinuxCapsule(status: OnboardingLinuxStatus?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val runtime = status?.runtime
    val visible = status != null && !status.hidden && (
        runtime is LinuxRuntimeState.Installing || runtime is LinuxRuntimeState.InstallInterrupted ||
            runtime is LinuxRuntimeState.Ready
        )
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(InfinityMotion.DurationMedium)) + scaleIn(initialScale = 0.85f),
        exit = fadeOut(tween(InfinityMotion.DurationShort)) + scaleOut(targetScale = 0.85f),
        modifier = modifier,
    ) {
        val shown = remember { arrayOfNulls<LinuxRuntimeState>(1) }
        if (runtime != null && visible) shown[0] = runtime
        val current = shown[0] ?: return@AnimatedVisibility
        val label = linuxCapsuleLabel(current)
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = onboardingCardColor,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, onboardingLineColor),
            modifier = Modifier
                .testTag(OnboardingTags.LINUX_CAPSULE)
                .semantics { contentDescription = label },
        ) {
            Row(
                Modifier.heightIn(min = 30.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                LinuxCapsuleIcon(current)
                Text(label, style = OnboardingType.caption(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun linuxCapsuleLabel(runtime: LinuxRuntimeState): String = when (runtime) {
    is LinuxRuntimeState.Installing -> runtime.progress.fraction?.let {
        stringResource(R.string.onboarding_linux_capsule_running, (it * 100).toInt())
    } ?: stringResource(R.string.onboarding_linux_capsule_preparing)
    is LinuxRuntimeState.InstallInterrupted -> stringResource(R.string.onboarding_linux_capsule_stopped)
    else -> stringResource(R.string.onboarding_linux_capsule_ready)
}

@Composable
private fun LinuxCapsuleIcon(runtime: LinuxRuntimeState) {
    val success = MaterialTheme.infinityDomainColors.success
    when (runtime) {
        is LinuxRuntimeState.Installing -> OnboardingProgressRing(runtime.progress.fraction, Modifier.size(12.dp))
        is LinuxRuntimeState.InstallInterrupted -> Box(
            Modifier.size(14.dp).background(MaterialTheme.colorScheme.error, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PriorityHigh, null, tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(10.dp))
        }
        else -> Box(Modifier.size(14.dp).background(success, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(10.dp))
        }
    }
}

/** 进度环；[fraction] 为 null 时画一段静止的弧表示进行中。 */
@Composable
internal fun OnboardingProgressRing(fraction: Float?, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(fraction ?: 0.25f, tween(InfinityMotion.DurationMedium), label = "linux_ring")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val value = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2f
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(value, -90f, 360f * animated.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** 步骤页骨架：可滚动的正文加固定在底部的操作区。 */
@Composable
internal fun OnboardingStepPage(
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = OnboardingDimens.PageMargin, end = OnboardingDimens.PageMargin, top = 32.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(OnboardingDimens.SectionGap),
            content = content,
        )
        OnboardingFooter(footer)
    }
}

/** 长列表单选步骤页：正文惰性排布，数百个候选也只组合可见项。 */
@Composable
internal fun OnboardingLazyStepPage(
    listTag: String,
    footer: @Composable ColumnScope.() -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().selectableGroup().testTag(listTag),
            contentPadding = PaddingValues(start = OnboardingDimens.PageMargin, end = OnboardingDimens.PageMargin, top = 32.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
        OnboardingFooter(footer)
    }
}

@Composable
private fun OnboardingFooter(footer: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = OnboardingDimens.PageMargin, end = OnboardingDimens.PageMargin, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = footer,
    )
}

@Composable
internal fun OnboardingHeading(title: String, description: String, centered: Boolean = false, index: Int = 0) {
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RiseIn(index) {
            Text(
                title,
                style = OnboardingType.title(),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = align,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
        }
        RiseIn(index + 1) {
            Text(
                description,
                style = OnboardingType.body(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = align,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 进入步骤时内容按 [index] 错峰上浮淡入；关闭动画或静态预览时直接显示。 */
@Composable
internal fun RiseIn(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    if (!animate) {
        Box(modifier) { content() }
        return
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * RiseStaggerMillis)
        progress.animateTo(1f, tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate))
    }
    val distance = with(LocalDensity.current) { 16.dp.toPx() }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * distance
        },
    ) { content() }
}
