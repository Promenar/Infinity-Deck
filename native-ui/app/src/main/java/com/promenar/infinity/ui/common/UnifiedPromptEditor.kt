package com.promenar.infinity.ui.common

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class EditorMode { DIALOG }

internal data class PromptTextStatistics(
    val words: Int,
    val lines: Int,
    val characters: Int,
)

/**
 * Prompt 统计采用编辑器可解释规则：中日韩统一表意文字逐字计数，其他字母/数字连续段计为一词。
 * 连续段内部的点、连字符、下划线和撇号在两侧均为字母或数字时保留，便于正确统计版本号和复合词。
 */
internal fun promptTextStatistics(text: String): PromptTextStatistics {
    var words = 0
    var inWord = false
    var offset = 0

    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        val nextOffset = offset + Character.charCount(codePoint)
        val nextCodePoint = if (nextOffset < text.length) text.codePointAt(nextOffset) else null
        when {
            codePoint.isCjkIdeograph() -> {
                if (inWord) words++
                words++
                inWord = false
            }

            Character.isLetterOrDigit(codePoint) -> inWord = true

            codePoint.isInternalWordPunctuation() &&
                inWord &&
                nextCodePoint?.let(Character::isLetterOrDigit) == true -> Unit

            else -> {
                if (inWord) words++
                inWord = false
            }
        }
        offset = nextOffset
    }
    if (inWord) words++

    return PromptTextStatistics(
        words = words,
        lines = text.count { it == '\n' } + 1,
        characters = text.length,
    )
}

internal fun hasUnsavedPromptChanges(initialText: String, currentText: String): Boolean =
    initialText != currentText

/** 同步门禁用于拦截同一帧内的快速重复点击；Compose 状态重组本身不足以消除该竞态。 */
internal class PromptSaveGate {
    private var saving = false

    fun tryStart(): Boolean {
        if (saving) return false
        saving = true
        return true
    }

    fun finish() {
        saving = false
    }
}

/**
 * 将普通异常收敛为可展示的保存失败，同时保持协程取消的结构化并发语义。
 */
/** 保存时发现内容已被他人修改；编辑器不显示通用失败，由调用方的提示条说明。 */
open class PromptSaveConflictException(message: String) : IllegalStateException(message)

/** 提示条操作编辑器内容的入口：读取当前草稿、替换内容（同时作为未修改基线）与关闭编辑器。 */
class PromptEditorHandle internal constructor(
    private val state: TextFieldState,
    private val onReplace: (String) -> Unit,
    private val onClose: () -> Unit,
) {
    val text: String get() = state.text.toString()
    fun replace(text: String) = onReplace(text)
    fun close() = onClose()
}

internal suspend fun runPromptSave(
    text: String,
    persist: suspend (String) -> Result<Unit>,
): Result<Unit> = try {
    persist(text).also { result ->
        (result.exceptionOrNull() as? CancellationException)?.let { throw it }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

private fun Int.isCjkIdeograph(): Boolean =
    this in 0x1100..0x11FF ||
        this in 0x3040..0x30FF ||
        this in 0x3130..0x318F ||
        this in 0x31F0..0x31FF ||
        this in 0x3400..0x4DBF ||
        this in 0x4E00..0x9FFF ||
        this in 0xAC00..0xD7AF ||
        this in 0xF900..0xFAFF ||
        this in 0x20000..0x2FA1F

private fun Int.isInternalWordPunctuation(): Boolean =
    this == '.'.code || this == '-'.code || this == '_'.code || this == '\''.code


@get:StringRes
private val PromptEditorView.labelRes: Int
    get() = when (this) {
        PromptEditorView.EDIT -> R.string.prompt_editor_tab_edit
        PromptEditorView.SPLIT -> R.string.prompt_editor_tab_split
        PromptEditorView.PREVIEW -> R.string.prompt_editor_tab_preview
    }

/** 宽于此值（折叠屏内屏、平板）时默认左写右看，保存放进顶栏。 */
private val PromptEditorWideWidth = 600.dp

/** 单栏编辑或预览时正文的最大宽度，宽屏上不至于一行过长。 */
private val PromptEditorReadableWidth = 760.dp

/**
 * 提示词与文本文件的整页编辑器：全屏窗口承载，顶栏为返回、标题与视图切换。
 * 窄窗口在编辑与预览之间切换，底部是格式工具条与整宽保存按钮；宽窗口默认分栏，保存在顶栏。
 * [showPreviewTab] 为 false 时按代码文本处理：等宽字体、不做 Markdown 高亮、没有格式按钮。
 */
@Composable
fun UnifiedPromptEditor(
    show: Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> Result<Unit>,
    initialText: String = "",
    title: String = "",
    placeholder: String = "",
    mode: EditorMode = EditorMode.DIALOG,
    fontSize: Int = 15,
    /** 为 false 时只保留编辑页，不显示 Markdown 预览（例如编辑脚本与配置文件）。 */
    showPreviewTab: Boolean = true,
    /** 顶栏下方的提示条（如“智能体刚修改了提示词”）；可读取与替换草稿。 */
    banner: (@Composable (PromptEditorHandle) -> Unit)? = null,
    /** 顶栏下方、提示条上方的次要入口；为空时不占位。 */
    secondaryAction: (@Composable () -> Unit)? = null,
) {
    if (!show) return

    val textFieldState = rememberTextFieldState(initialText)
    val text = textFieldState.text.toString()
    // 未修改基线：提示条用新内容替换草稿时一并更新，避免把已保存的内容当成未保存改动。
    var baseline by remember(initialText) { mutableStateOf(initialText) }
    var chosenView by rememberSaveable { mutableStateOf<PromptEditorView?>(null) }
    var showDiscardConfirmation by remember(initialText) { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    val saveGate = remember { PromptSaveGate() }
    val coroutineScope = rememberCoroutineScope()

    val statistics = remember(text) { promptTextStatistics(text) }
    val dirty = hasUnsavedPromptChanges(baseline, text)
    val resolvedTitle = title.ifBlank { stringResource(R.string.prompt_editor_default_title) }
    val resolvedPlaceholder = placeholder.ifBlank { stringResource(R.string.prompt_editor_default_placeholder) }
    val requestDismiss = {
        if (isSaving) {
            saveJob?.cancel()
            Unit
        } else if (dirty) {
            showDiscardConfirmation = true
        } else {
            onDismiss()
        }
    }
    val save = {
        if (saveGate.tryStart()) {
            isSaving = true
            saveFailed = false
            saveJob = coroutineScope.launch {
                try {
                    val result = runPromptSave(text, onSave)
                    if (result.isSuccess) onDismiss()
                    else if (result.exceptionOrNull() !is PromptSaveConflictException) saveFailed = true
                } finally {
                    saveGate.finish()
                    isSaving = false
                    saveJob = null
                }
            }
        }
    }

    when (mode) {
        EditorMode.DIALOG -> InfinityFullscreenDialog(
            onDismissRequest = requestDismiss,
            // 窗口铺到系统栏下方，背景覆盖整屏；状态栏、导航栏与键盘的留白由内容自己处理。
            systemBarColor = MaterialTheme.colorScheme.background,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                val wide = maxWidth >= PromptEditorWideWidth
                val views = promptEditorViews(wide, showPreviewTab)
                val view = (chosenView ?: defaultPromptEditorView(wide, showPreviewTab)).coerceTo(views)
                val status = buildList {
                    if (dirty) add(stringResource(R.string.prompt_editor_modified))
                    add(pluralStringResource(R.plurals.prompt_editor_word_count, statistics.words, statistics.words))
                    if (wide) add(pluralStringResource(R.plurals.prompt_editor_line_count, statistics.lines, statistics.lines))
                }.joinToString(" · ")  // wrap-check: allow 状态栏单行省略

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding(),
                ) {
                    PromptEditorTopBar(
                        title = resolvedTitle,
                        views = views,
                        view = view,
                        onViewChange = { chosenView = it },
                        onBack = requestDismiss,
                        saveInBar = wide,
                        isSaving = isSaving,
                        onSave = save,
                    )
                    if (saveFailed) {
                        Text(
                            text = stringResource(R.string.shared_error_generic),
                            style = InfinityTypography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Assertive }
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    secondaryAction?.invoke()
                    banner?.let { content ->
                        val dismiss by rememberUpdatedState(onDismiss)
                        val handle = remember(textFieldState) {
                            PromptEditorHandle(
                                state = textFieldState,
                                onReplace = { replacement ->
                                    textFieldState.setTextAndPlaceCursorAtEnd(replacement)
                                    baseline = replacement
                                },
                                onClose = { dismiss() },
                            )
                        }
                        content(handle)
                    }
                    // 视图按页签左右顺序做共享 X 轴切换；文本状态在外层，切换不丢内容与撤销记录。
                    AnimatedContent(
                        targetState = view,
                        transitionSpec = { InfinityMotion.sharedAxisX(forward = targetState.ordinal > initialState.ordinal) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        label = "prompt_editor_view",
                    ) { current ->
                        when (current) {
                            PromptEditorView.SPLIT -> Row(Modifier.fillMaxSize()) {
                                PromptEditorField(textFieldState, resolvedPlaceholder, codeMode = false, Modifier.weight(1f))
                                VerticalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                PromptPreviewPane(
                                    text = text,
                                    placeholder = resolvedPlaceholder,
                                    fontSize = fontSize,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                                )
                            }
                            PromptEditorView.EDIT -> ReadableColumn {
                                PromptEditorField(textFieldState, resolvedPlaceholder, codeMode = !showPreviewTab, it)
                            }
                            PromptEditorView.PREVIEW -> ReadableColumn {
                                PromptPreviewPane(text, resolvedPlaceholder, fontSize, it)
                            }
                        }
                    }
                    PromptEditorToolbar(
                        state = textFieldState,
                        showFormatting = showPreviewTab && view != PromptEditorView.PREVIEW,
                        showHistory = view != PromptEditorView.PREVIEW,
                        showRedo = wide,
                        status = status,
                    )
                    if (!wide) {
                        PromptSaveButton(
                            isSaving = isSaving,
                            onSave = save,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                }
            }
        }
    }

    if (showDiscardConfirmation) {
        InfinityConfirmDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = stringResource(R.string.prompt_editor_discard_title),
            message = stringResource(R.string.prompt_editor_discard_message),
            confirmText = stringResource(R.string.prompt_editor_discard_confirm),
            cancelText = stringResource(R.string.prompt_editor_discard_keep),
            onConfirm = onDismiss,
            onCancel = { showDiscardConfirmation = false },
            isDestructive = true,
        )
    }
}

@Composable
private fun PromptEditorTopBar(
    title: String,
    views: List<PromptEditorView>,
    view: PromptEditorView,
    onViewChange: (PromptEditorView) -> Unit,
    onBack: () -> Unit,
    saveInBar: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.prompt_editor_cd_close),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (views.size > 1) {
            InfinityCapsuleTabRow(
                tabs = views.map { InfinityTabItem(title = stringResource(it.labelRes)) },
                selectedIndex = views.indexOf(view),
                onTabSelected = { onViewChange(views[it]) },
                modifier = Modifier.width(if (views.size > 2) 216.dp else 148.dp),
            )
        }
        if (saveInBar) PromptSaveButton(isSaving = isSaving, onSave = onSave)
    }
}

/** 保存中保留按钮宽度，文字隐去换成进度圈，避免按钮跳动。 */
@Composable
private fun PromptSaveButton(isSaving: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier) {
    val savingDescription = stringResource(R.string.shared_loading)
    Button(
        onClick = onSave,
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag(UiTags.PROMPT_EDITOR_SAVE)
            .semantics { if (isSaving) stateDescription = savingDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.prompt_editor_cd_save),
                modifier = Modifier.alpha(if (isSaving) 0f else 1f),
            )
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun ReadableColumn(content: @Composable (Modifier) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        content(
            Modifier
                .widthIn(max = PromptEditorReadableWidth)
                .fillMaxWidth()
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun PromptEditorField(
    state: TextFieldState,
    placeholder: String,
    codeMode: Boolean,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val highlight = remember(codeMode, colors) {
        if (codeMode) null else promptSyntaxHighlight(colors.primary, colors.onSurfaceVariant, colors.surfaceContainerHigh)
    }
    val baseStyle = if (codeMode) {
        InfinityTypography.bodySmall.copy(fontSize = 14.sp, fontFamily = InfinityMonospace, lineHeight = 20.sp)
    } else {
        InfinityTypography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 24.sp)
    }
    val isEmpty = state.text.isEmpty()
    BasicTextField(
        state = state,
        modifier = modifier
            .fillMaxHeight()
            .testTag(UiTags.PROMPT_EDITOR_INPUT)
            .semantics { contentDescription = placeholder }
            .padding(start = 20.dp, end = 20.dp, top = 16.dp),
        textStyle = baseStyle.copy(color = colors.onSurface).forInput(),
        cursorBrush = SolidColor(colors.primary),
        outputTransformation = highlight,
        scrollState = rememberScrollState(),
        decorator = { innerTextField ->
            Box {
                if (isEmpty) {
                    Text(text = placeholder, style = baseStyle, color = colors.onSurfaceVariant.copy(alpha = 0.6f))
                }
                innerTextField()
            }
        },
    )
}

/** 标题用主色加粗，标记符号弱化，加粗与代码按 Markdown 语义显示；不改动文本本身。 */
private fun promptSyntaxHighlight(accent: Color, muted: Color, codeBackground: Color) = OutputTransformation {
    promptSyntaxSpans(asCharSequence().toString()).forEach { span ->
        val style = when (span.kind) {
            PromptSyntaxKind.HEADING -> SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)
            PromptSyntaxKind.MARKER -> SpanStyle(color = muted.copy(alpha = 0.7f))
            PromptSyntaxKind.STRONG -> SpanStyle(fontWeight = FontWeight.SemiBold)
            PromptSyntaxKind.CODE -> SpanStyle(fontFamily = InfinityMonospace, background = codeBackground)
        }
        addStyle(style, span.start, span.end)
    }
}

@Composable
private fun PromptPreviewPane(text: String, placeholder: String, fontSize: Int, modifier: Modifier) {
    Box(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (text.isBlank()) {
            Text(
                text = placeholder,
                style = InfinityTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        } else {
            MarkdownText(markdown = text, isStreaming = false, fontSize = fontSize, showCursor = false)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PromptEditorToolbar(
    state: TextFieldState,
    showFormatting: Boolean,
    showHistory: Boolean,
    showRedo: Boolean,
    status: String,
) {
    Column {
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showFormatting) {
                ToolbarButton(Icons.Rounded.Title, R.string.prompt_editor_cd_heading) { state.applyPromptEdit(::cycleHeading) }
                ToolbarButton(Icons.AutoMirrored.Rounded.FormatListBulleted, R.string.prompt_editor_cd_list) {
                    state.applyPromptEdit(::toggleListItems)
                }
                ToolbarButton(Icons.Rounded.FormatBold, R.string.prompt_editor_cd_bold) {
                    state.applyPromptEdit { text, start, end -> toggleInlineWrap(text, start, end, "**") }
                }
                ToolbarButton(Icons.Rounded.Code, R.string.prompt_editor_cd_code) {
                    state.applyPromptEdit { text, start, end -> toggleInlineWrap(text, start, end, "`") }
                }
            }
            if (showHistory) {
                ToolbarButton(Icons.AutoMirrored.Rounded.Undo, R.string.prompt_editor_cd_undo, state.undoState.canUndo) {
                    state.undoState.undo()
                }
                if (showRedo) {
                    ToolbarButton(Icons.AutoMirrored.Rounded.Redo, R.string.prompt_editor_cd_redo, state.undoState.canRedo) {
                        state.undoState.redo()
                    }
                }
            }
            Text(
                text = status,
                style = InfinityTypography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 12.dp),
            )
        }
    }
}

@Composable
private fun ToolbarButton(icon: ImageVector, @StringRes description: Int, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(imageVector = icon, contentDescription = stringResource(description), modifier = Modifier.size(22.dp))
    }
}

private fun TextFieldState.applyPromptEdit(build: (String, Int, Int) -> PromptTextEdit) {
    val change = build(text.toString(), selection.min, selection.max)
    edit {
        replace(change.start, change.end, change.replacement)
        selection = TextRange(change.selectionStart, change.selectionEnd)
    }
}
