package com.promenar.infinity.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.domain.linux.LinuxOptionalPackage
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.linux.LinuxStepStatus
import com.promenar.infinity.onboarding.OnboardingStep
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.linux.settings.LINUX_BUNDLED_ALPINE_VERSION
import com.promenar.infinity.ui.linux.settings.LinuxFailureCard
import com.promenar.infinity.ui.linux.settings.LinuxInstallStepRow
import com.promenar.infinity.ui.linux.settings.LinuxOutputBox
import com.promenar.infinity.ui.linux.settings.linuxBytes
import com.promenar.infinity.ui.linux.settings.linuxOptionalPackageSubtitle
import com.promenar.infinity.ui.linux.settings.linuxOptionalPackageTitle
import com.promenar.infinity.ui.linux.settings.linuxRequiredBytes
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 选中语言后等字标压暗再推进；语言切换可能重建 Activity。 */
private const val LanguageDisperseMillis = 420L
private const val LinuxOutputLines = 6

@Composable
internal fun OnboardingIntro(onLanguageSelected: (String) -> Unit, busy: Boolean) {
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    var selecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val select: (String) -> Unit = { code ->
        if (!selecting && !busy) {
            selecting = true
            scope.launch {
                if (animate) delay(LanguageDisperseMillis)
                onLanguageSelected(code)
                // 语言未能保存时引导停在本页，字标恢复后可再次选择。
                delay(1_000)
                selecting = false
            }
        }
    }
    val wordmark: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            OnboardingWordmark(Modifier.fillMaxWidth().heightIn(max = 200.dp).weight(1f, fill = false).fillMaxHeight(), dispersed = selecting)
            Text(
                stringResource(R.string.welcome_slogan),
                style = OnboardingType.body().copy(fontSize = 12.5.sp, letterSpacing = 0.32.em),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val choices: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier
                .widthIn(max = OnboardingDimens.CenteredMaxWidth)
                .fillMaxWidth()
                .padding(start = OnboardingDimens.PageMargin, end = OnboardingDimens.PageMargin, top = 24.dp, bottom = 44.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RiseIn(0) {
                Text(
                    stringResource(R.string.onboarding_choose_language),
                    style = OnboardingType.caption(),
                    color = onboardingFaintText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RiseIn(1) {
                OnboardingCard {
                    LanguageRow("文", stringResource(R.string.welcome_lang_chinese), "zh", enabled = !selecting && !busy, onClick = select)
                    HorizontalDivider(color = onboardingLineColor)
                    LanguageRow("A", stringResource(R.string.welcome_lang_english), "en", enabled = !selecting && !busy, onClick = select)
                }
            }
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag(OnboardingTags.step(OnboardingStep.LANGUAGE)),
    ) {
        if (maxWidth > maxHeight && maxHeight < 480.dp) {
            // 横屏矮窗口：字标与语言按钮左右并排，按钮列可滚动，放大字号也能点到。
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                wordmark(Modifier.weight(1f).fillMaxHeight().padding(vertical = InfinitySpacing.Large))
                Box(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) { choices(Modifier) }
            }
        } else {
            // 竖屏与折叠屏内屏：居中单栏。
            Column(
                Modifier.fillMaxSize().padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                wordmark(Modifier.weight(1f).widthIn(max = OnboardingDimens.CenteredMaxWidth).fillMaxWidth())
                choices(Modifier)
            }
        }
    }
}

/** 语言卡片里的一行：字形小图块、语言名与右侧箭头。 */
@Composable
private fun LanguageRow(glyph: String, label: String, code: String, enabled: Boolean, onClick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button) { onClick(code) }
            .heightIn(min = OnboardingDimens.RowMinHeight)
            .padding(horizontal = 16.dp)
            .testTag(OnboardingTags.LANGUAGE_PREFIX + code),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OnboardingIconTile(Modifier.clearAndSetSemantics {}) {
            Text(glyph, style = OnboardingType.caption().copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(label, style = OnboardingType.rowTitle(), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForwardIos,
            contentDescription = null,
            tint = onboardingFaintText,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
internal fun OnboardingLinuxStep(status: OnboardingLinuxStatus, actions: OnboardingActions) {
    val runtime = status.runtime
    val freeBytes = (runtime as? LinuxRuntimeState.NotInstalled)?.freeBytes
    val required = linuxRequiredBytes(status.packages)
    val enoughSpace = freeBytes == null || freeBytes >= required
    OnboardingStepPage(
        footer = {
            RiseIn(6) {
                OnboardingPrimaryButton(
                    onClick = actions.onInstallLinux,
                    enabled = enoughSpace,
                    modifier = Modifier.testTag(OnboardingTags.LINUX_INSTALL),
                ) { Text(stringResource(R.string.onboarding_linux_install), style = OnboardingType.button()) }
            }
            RiseIn(7) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    InfinityPillButton(
                        text = stringResource(R.string.onboarding_linux_skip),
                        onClick = actions.onSkipLinux,
                        modifier = Modifier.testTag(OnboardingTags.LINUX_SKIP),
                    )
                }
            }
            RiseIn(8) {
                Text(
                    stringResource(R.string.onboarding_linux_background_note),
                    style = OnboardingType.caption(),
                    color = onboardingFaintText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        RiseIn(0) { LinuxTerminalPreview() }
        OnboardingHeading(
            stringResource(R.string.linux_install_hero_title),
            stringResource(R.string.linux_install_hero_body),
            index = 1,
        )
        RiseIn(3) { OnboardingRestoreEntry(onClick = actions.onOpenRestore) }
        RiseIn(3) {
            OnboardingCard {
                LinuxPackageRow(
                    title = stringResource(R.string.linux_install_base_title, LINUX_BUNDLED_ALPINE_VERSION),
                    subtitle = stringResource(R.string.linux_install_base_subtitle),
                    checked = true,
                    onCheckedChange = null,
                )
                LinuxOptionalPackage.entries.forEach { item ->
                    HorizontalDivider(color = onboardingLineColor)
                    LinuxPackageRow(
                        title = stringResource(linuxOptionalPackageTitle(item)),
                        subtitle = linuxOptionalPackageSubtitle(item),
                        checked = item in status.packages,
                        onCheckedChange = { actions.onSetLinuxPackage(item, it) },
                    )
                }
            }
        }
        if (freeBytes != null) {
            RiseIn(4) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                    Icon(
                        if (enoughSpace) Icons.Rounded.SdStorage else Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = if (enoughSpace) MaterialTheme.infinityDomainColors.success else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        if (enoughSpace) stringResource(R.string.onboarding_linux_space, linuxBytes(freeBytes), linuxBytes(required))
                        else stringResource(R.string.linux_install_space_low, linuxBytes(freeBytes), linuxBytes(required)),
                        style = OnboardingType.caption(),
                        color = if (enoughSpace) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        RiseIn(5) { OnboardingNotice(stringResource(R.string.linux_install_warning)) }
    }
}

/** 引导页的提示条：卡片样式，左侧警示图标用警示色。 */
@Composable
private fun OnboardingNotice(text: String) {
    OnboardingCard {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.infinityDomainColors.warning,
                modifier = Modifier.size(18.dp),
            )
            Text(text, style = OnboardingType.caption(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LinuxPackageRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
    } else {
        Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(toggle)
            .heightIn(min = OnboardingDimens.RowMinHeight)
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = OnboardingType.rowTitle(), color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = OnboardingType.rowSubtitle(), color = onboardingFaintText)
        }
        Checkbox(checked = checked, onCheckedChange = null, enabled = onCheckedChange != null)
    }
}

private val TerminalLines = listOf("~ $ uname -sm", "Linux aarch64", "~ $ apk add git python3", "OK")

/** 终端预览：逐字打出几行命令，光标用主色；关闭动画时直接显示全文。纯装饰。 */
@Composable
private fun LinuxTerminalPreview() {
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val full = remember { TerminalLines.joinToString("\n") }
    var shown by remember { mutableIntStateOf(if (animate) 0 else full.length) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        delay(300)
        while (shown < full.length) {
            delay(if (full[shown] == '\n') 260 else 38)
            shown++
        }
    }
    val cursor = MaterialTheme.colorScheme.primary
    OnboardingCard(Modifier.clearAndSetSemantics {}) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { Box(Modifier.size(9.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                // 预留全文高度，逐字出现时卡片不跳动。
                text = buildAnnotatedString {
                    append(full.substring(0, shown))
                    withStyle(SpanStyle(color = cursor)) { append("▍") }
                    append("\n".repeat(full.count { it == '\n' } - full.substring(0, shown).count { it == '\n' }))
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace, fontSize = 11.5.sp, lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 30dp 的小图块：服务、语言与智能体列表行的图标底。 */
@Composable
internal fun OnboardingIconTile(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(OnboardingDimens.TileIcon)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Linux 面板：分步进度、实时输出尾部与取消、重试、跳过。安装在应用级作用域进行，关闭面板不影响。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnboardingLinuxSheet(status: OnboardingLinuxStatus, actions: OnboardingActions, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        OnboardingLinuxSheetContent(
            runtime = status.runtime,
            onCancel = actions.onCancelLinux,
            onRetry = actions.onRetryLinux,
            onSkip = {
                actions.onHideLinux()
                close()
            },
            onClose = close,
        )
    }
}

@Composable
internal fun OnboardingLinuxSheetContent(
    runtime: LinuxRuntimeState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onClose: () -> Unit,
) {
    val progress = when (runtime) {
        is LinuxRuntimeState.Installing -> runtime.progress
        is LinuxRuntimeState.InstallInterrupted -> runtime.progress
        else -> null
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = InfinitySpacing.XLarge)
            .padding(bottom = InfinitySpacing.XLarge),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Text(
            stringResource(R.string.onboarding_linux_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        when (runtime) {
            is LinuxRuntimeState.Installing -> {
                val steps = runtime.progress.steps
                val index = steps.indexOfFirst { it.status == LinuxStepStatus.RUNNING }.coerceAtLeast(0)
                val head = stringResource(R.string.linux_status_installing, index + 1, steps.size)
                val fraction = runtime.progress.fraction
                Text(
                    if (fraction != null) stringResource(R.string.onboarding_linux_sheet_progress, head, (fraction * 100).toInt()) else head,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val label = stringResource(R.string.linux_progress_label)
                val bar = Modifier.fillMaxWidth().semantics { contentDescription = label }
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = bar) else LinearProgressIndicator(modifier = bar)
            }
            is LinuxRuntimeState.InstallInterrupted -> {
                Text(
                    stringResource(if (runtime.failure != null) R.string.linux_status_failed else R.string.linux_status_interrupted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                runtime.failure?.let { LinuxFailureCard(it) }
            }
            is LinuxRuntimeState.Ready -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.infinityDomainColors.success)
                Text(
                    stringResource(R.string.linux_status_ready, runtime.info.version),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> Unit
        }
        if (progress != null) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 6.dp)) { progress.steps.forEach { LinuxInstallStepRow(it) } }
            }
            if (progress.outputTail.isNotBlank()) {
                LinuxOutputBox(progress.outputTail.trimEnd().lines().takeLast(LinuxOutputLines).joinToString("\n"))
            }
            Text(
                stringResource(
                    if (runtime is LinuxRuntimeState.Installing) R.string.onboarding_linux_sheet_note_running
                    else R.string.onboarding_linux_sheet_note_stopped,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InfinityPillRow(Modifier.fillMaxWidth(), spacing = InfinitySpacing.Medium) {
            when (runtime) {
                is LinuxRuntimeState.Installing -> {
                    InfinityPillButton(
                        text = stringResource(R.string.linux_install_cancel),
                        onClick = onCancel,
                        tone = InfinityPillTone.DANGER,
                    )
                    InfinityPillButton(
                        text = stringResource(R.string.onboarding_linux_hide),
                        onClick = onClose,
                        tone = InfinityPillTone.PRIMARY,
                    )
                }
                is LinuxRuntimeState.InstallInterrupted -> {
                    InfinityPillButton(
                        text = stringResource(R.string.onboarding_linux_skip_install),
                        onClick = onSkip,
                    )
                    InfinityPillButton(
                        text = stringResource(R.string.onboarding_linux_retry),
                        onClick = onRetry,
                        tone = InfinityPillTone.PRIMARY,
                    )
                }
                else -> InfinityPillButton(
                    text = stringResource(R.string.onboarding_linux_done),
                    onClick = onClose,
                    tone = InfinityPillTone.PRIMARY,
                )
            }
        }
    }
}
