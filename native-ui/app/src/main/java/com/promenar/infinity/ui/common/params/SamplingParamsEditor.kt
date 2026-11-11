package com.promenar.infinity.ui.common.params

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.params.MODEL_DEFAULT_SCHEME_ID
import com.promenar.infinity.data.model.params.ModelParameterProfile
import com.promenar.infinity.data.model.params.ParamAvailability
import com.promenar.infinity.data.model.params.ParameterScheme
import com.promenar.infinity.data.model.params.ReasoningEffort
import com.promenar.infinity.data.model.params.SamplingParam
import com.promenar.infinity.data.model.params.SamplingValues
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinityChip
import com.promenar.infinity.ui.common.InfinityMenuDivider
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityStepperButton
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.promenar.infinity.ui.common.InfinityMenuItem

/** 参数值来自哪一层：本层写入、沿用上一层（会话沿用助手），或不发送、由模型按默认处理。 */
enum class ParamSource { OWN, INHERITED, DEFAULT }

/** 方案行的选中状态：[schemeId] 为方案 id 或 [MODEL_DEFAULT_SCHEME_ID]，null 表示当前取值不对应任何方案。 */
data class SchemeSelection(val schemeId: String?, val modified: Boolean)

/** 当前模型与接口下各参数的显示方式。 */
data class SamplingParamLayout(
    /** 可编辑的参数，按显示顺序；可能带提醒标签。 */
    val visible: List<SamplingParam>,
    /** 官方接口对该模型不接受的参数，收进末尾的“不适用”分组。 */
    val notApplicable: List<SamplingParam> = emptyList(),
    val availability: Map<SamplingParam, ParamAvailability> = emptyMap(),
)

/** 未识别模型或服务商时显示全部参数，不加标签。 */
fun samplingParamLayout(profile: ModelParameterProfile?, stored: ReasoningEffort?): SamplingParamLayout {
    if (profile == null) return SamplingParamLayout(SAMPLING_PARAM_ORDER)
    val availability = SAMPLING_PARAM_ORDER.associateWith { profile.availability(it, stored) }
    return SamplingParamLayout(
        visible = SAMPLING_PARAM_ORDER.filter {
            availability[it] != ParamAvailability.NOT_APPLICABLE && availability[it] != ParamAvailability.NOT_IN_PROTOCOL
        },
        notApplicable = SAMPLING_PARAM_ORDER.filter { availability[it] == ParamAvailability.NOT_APPLICABLE },
        availability = availability,
    )
}

/**
 * 采样参数编辑器的数据。[own] 是本层写入的值，[inherited] 是上一层的值；
 * [followsScheme] 为真时本层完全决定采样参数，不再沿用上一层。
 */
@Immutable
data class SamplingEditorState(
    val own: SamplingValues,
    val inherited: SamplingValues = SamplingValues(),
    val followsScheme: Boolean = false,
    /** 最近一次选用的方案；只用于判断“已修改”，不影响发送的值。 */
    val baseSchemeId: String? = null,
    val layout: SamplingParamLayout = SamplingParamLayout(SAMPLING_PARAM_ORDER),
    val schemes: List<ParameterScheme> = emptyList(),
) {
    val visible: List<SamplingParam> get() = layout.visible

    val effective: SamplingValues get() = if (followsScheme) own else own.orElse(inherited)

    /** 方案只保存当前可编辑的参数。 */
    val savableValues: SamplingValues get() = effective.onlyFor(visible.toSet())

    fun source(param: SamplingParam): ParamSource = when {
        own.valueOf(param) != null -> ParamSource.OWN
        !followsScheme && inherited.valueOf(param) != null -> ParamSource.INHERITED
        else -> ParamSource.DEFAULT
    }

    fun availability(param: SamplingParam): ParamAvailability =
        layout.availability[param] ?: ParamAvailability.SUPPORTED

    val selection: SchemeSelection get() = resolveSchemeSelection(this)

    /** 选中方案被修改时，与方案取值不同的参数。 */
    val changedParams: List<SamplingParam>
        get() {
            val current = selection
            if (!current.modified) return emptyList()
            val base = if (current.schemeId == MODEL_DEFAULT_SCHEME_ID) SamplingValues()
            else schemes.firstOrNull { it.id == current.schemeId }?.values ?: return emptyList()
            return visible.filter { effective.valueOf(it) != base.valueOf(it) }
        }
}

/**
 * 选中刚选用的方案并标出之后是否改过；没有可用的基准方案时，按当前取值匹配“模型默认”或某个已存方案。
 * 只比较当前可编辑的参数，不适用项的残留值不算修改。
 */
fun resolveSchemeSelection(state: SamplingEditorState): SchemeSelection {
    val current = state.savableValues
    val visible = state.visible.toSet()
    when (val base = state.baseSchemeId) {
        MODEL_DEFAULT_SCHEME_ID -> return SchemeSelection(base, !current.isModelDefault)
        null -> Unit
        else -> state.schemes.firstOrNull { it.id == base }?.let { scheme ->
            return SchemeSelection(scheme.id, current != scheme.values.onlyFor(visible))
        }
    }
    if (current.isModelDefault) return SchemeSelection(MODEL_DEFAULT_SCHEME_ID, false)
    state.schemes.firstOrNull { it.values.onlyFor(visible) == current }?.let { return SchemeSelection(it.id, false) }
    return SchemeSelection(null, false)
}

class SamplingEditorActions(
    val onApplyModelDefault: () -> Unit = {},
    val onApplyScheme: (ParameterScheme) -> Unit = {},
    /** null 清空本层设置。 */
    val onParamChange: (SamplingParam, Double?) -> Unit = { _, _ -> },
    val onSaveScheme: (String) -> Unit = {},
    val onOverwriteScheme: (ParameterScheme) -> Unit = {},
    val onRenameScheme: (ParameterScheme, String) -> Unit = { _, _ -> },
    val onDeleteScheme: (ParameterScheme) -> Unit = {},
)

/**
 * 参数方案分组：标题右侧注明方案所属模型；当前方案是一个下拉标签，菜单里切换方案，并对选中的方案还原、重命名与删除；
 * 修改过选中的方案时列出不同的参数并给出覆盖与另存。
 * 传入 [scrollState] 时，差异行因下方逐项调整而出现或消失，会按同样高度滚动，让手指下的编辑区留在原位。
 */
@Composable
fun ParameterSchemeSection(
    state: SamplingEditorState,
    actions: SamplingEditorActions,
    modelName: String?,
    modifier: Modifier = Modifier,
    scrollState: ScrollableState? = null,
    /** 方案卡下方的说明，例如智能体编辑页注明方案全局共享。 */
    footer: String? = null,
) {
    val selection = state.selection
    val selectedScheme = state.schemes.firstOrNull { it.id == selection.schemeId }
    var naming by remember { mutableStateOf<SchemeNaming?>(null) }
    var deleting by remember { mutableStateOf<ParameterScheme?>(null) }
    val scope = rememberCoroutineScope()
    // 方案卡自己的操作引起的高度变化不做补偿，用户此时看的就是这张卡。
    var ownActionAt by remember { mutableLongStateOf(0L) }
    val own: (() -> Unit) -> Unit = { block -> ownActionAt = SystemClock.uptimeMillis(); block() }
    var diffHeight by remember { mutableIntStateOf(-1) }
    Column(modifier.fillMaxWidth().padding(bottom = InfinitySpacing.SectionGap)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal)
                .padding(bottom = InfinitySpacing.SectionHeaderBottom),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.params_scheme_section),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.width(12.dp))
            Text(
                modelName.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("params_scheme_model"),
            )
        }
        BettboxListGroup {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                if (modelName == null) {
                    Text(
                        stringResource(R.string.params_no_model),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    return@Column
                }
                // 方案数量由用户决定：当前方案收成一个下拉标签，切换、还原、重命名与删除都在菜单里。
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SchemePicker(
                        state = state,
                        selectedScheme = selectedScheme,
                        onApplyModelDefault = { own(actions.onApplyModelDefault) },
                        onApplyScheme = { scheme -> own { actions.onApplyScheme(scheme) } },
                        onRename = { naming = SchemeNaming.Rename(it) },
                        onDelete = { deleting = it },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (selection.schemeId == null) {
                        InfinityChip(
                            label = stringResource(R.string.params_scheme_save),
                            onClick = { naming = SchemeNaming.Create },
                            leading = { Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.testTag("params_scheme_save"),
                        )
                    }
                }
                val changed = state.changedParams
                Column(Modifier.onSizeChanged { size ->
                    val delta = size.height - diffHeight
                    val first = diffHeight < 0
                    diffHeight = size.height
                    if (!first && delta != 0 && scrollState != null &&
                        SystemClock.uptimeMillis() - ownActionAt > OWN_ACTION_WINDOW_MS
                    ) {
                        scope.launch { scrollState.scrollBy(delta.toFloat()) }
                    }
                }) {
                    if (changed.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.padding(start = 4.dp, end = 8.dp).size(6.dp)
                                .background(MaterialTheme.infinityDomainColors.warning, CircleShape))
                            Text(
                                stringResource(
                                    R.string.params_scheme_diff,
                                    selectedScheme?.name ?: stringResource(R.string.params_scheme_model_default),
                                    changed.map { samplingParamTitle(it) }.joinToString(stringResource(R.string.params_list_separator)),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).testTag("params_scheme_diff"),
                            )
                        }
                        // 说明独占一行，按钮靠右另起一行，窄屏大字号下也不会把说明挤成碎片。
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            if (selectedScheme != null) {
                                OutlinedButton(
                                    onClick = { own { actions.onOverwriteScheme(selectedScheme) } },
                                    contentPadding = CompactButtonPadding,
                                    modifier = Modifier.heightIn(min = 36.dp).testTag("params_scheme_overwrite"),
                                ) { Text(stringResource(R.string.params_scheme_overwrite)) }
                            }
                            FilledTonalButton(
                                onClick = { naming = SchemeNaming.Create },
                                contentPadding = CompactButtonPadding,
                                modifier = Modifier.heightIn(min = 36.dp).testTag("params_scheme_save_as"),
                            ) {
                                Text(stringResource(if (selectedScheme != null) R.string.params_scheme_save_as else R.string.params_scheme_save))
                            }
                        }
                    }
                }
            }
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal).padding(top = InfinitySpacing.FooterTop)
                    .testTag("params_scheme_footer"),
            )
        }
    }
    deleting?.let { scheme ->
        InfinityConfirmDialog(
            onDismissRequest = { deleting = null },
            title = stringResource(R.string.params_scheme_delete_title, scheme.name),
            message = stringResource(R.string.params_scheme_delete_message),
            confirmText = stringResource(R.string.params_scheme_delete),
            isDestructive = true,
            onConfirm = {
                deleting = null
                own { actions.onDeleteScheme(scheme) }
            },
            onCancel = { deleting = null },
            confirmButtonModifier = Modifier.testTag("params_scheme_delete_confirm"),
        )
    }
    naming?.let { current ->
        SchemeNameDialog(
            initial = (current as? SchemeNaming.Rename)?.scheme?.name.orEmpty(),
            onDismiss = { naming = null },
            onConfirm = { name ->
                naming = null
                when (current) {
                    SchemeNaming.Create -> own { actions.onSaveScheme(name) }
                    is SchemeNaming.Rename -> actions.onRenameScheme(current.scheme, name)
                }
            },
        )
    }
}

private const val OWN_ACTION_WINDOW_MS = 1_000L

private val CompactButtonPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)

private sealed interface SchemeNaming {
    data object Create : SchemeNaming
    data class Rename(val scheme: ParameterScheme) : SchemeNaming
}

/** 已存方案选中后带下拉标记，再点打开管理菜单；未选中时点按即选用。 */
@Composable
private fun SchemePicker(
    state: SamplingEditorState,
    selectedScheme: ParameterScheme?,
    onApplyModelDefault: () -> Unit,
    onApplyScheme: (ParameterScheme) -> Unit,
    onRename: (ParameterScheme) -> Unit,
    onDelete: (ParameterScheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selection = state.selection
    var open by remember { mutableStateOf(false) }
    val label = when {
        selection.schemeId == MODEL_DEFAULT_SCHEME_ID -> stringResource(R.string.params_scheme_model_default)
        selectedScheme != null -> selectedScheme.name
        else -> stringResource(R.string.params_scheme_custom)
    }
    Box(modifier) {
        InfinityChip(
            label = label,
            onClick = { open = true },
            trailing = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.testTag("params_scheme_picker"),
        )
        InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            InfinityMenuItem(
                text = { Text(stringResource(R.string.params_scheme_model_default)) },
                selected = selection.schemeId == MODEL_DEFAULT_SCHEME_ID,
                onClick = { open = false; onApplyModelDefault() },
                modifier = Modifier.testTag("params_scheme_model_default"),
            )
            state.schemes.forEach { scheme ->
                InfinityMenuItem(
                    text = { Text(scheme.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = selection.schemeId == scheme.id,
                    onClick = { open = false; onApplyScheme(scheme) },
                    modifier = Modifier.testTag("params_scheme_${scheme.id}"),
                )
            }
            if (selectedScheme != null) {
                InfinityMenuDivider()
                if (selection.modified) {
                    InfinityMenuItem(text = { Text(stringResource(R.string.params_scheme_revert)) }, onClick = {
                        open = false; onApplyScheme(selectedScheme)
                    })
                }
                InfinityMenuItem(text = { Text(stringResource(R.string.params_scheme_rename)) }, onClick = {
                    open = false; onRename(selectedScheme)
                })
                InfinityMenuItem(
                    text = { Text(stringResource(R.string.params_scheme_delete)) },
                    destructive = true,
                    onClick = { open = false; onDelete(selectedScheme) },
                )
            }
        }
    }
}

@Composable
private fun SchemeNameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    InfinityConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.params_scheme_name_title),
        message = "",
        confirmText = stringResource(R.string.common_btn_confirm),
        cancelText = stringResource(R.string.common_btn_cancel),
        onConfirm = { onConfirm(text.trim()) },
        onCancel = onDismiss,
        confirmEnabled = text.isNotBlank(),
        confirmButtonModifier = Modifier.testTag("params_scheme_name_confirm"),
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(SCHEME_NAME_MAX_LENGTH) },
                textStyle = LocalTextStyle.current.forInput(),
                placeholder = { Text(stringResource(R.string.params_scheme_name_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("params_scheme_name_input"),
            )
        },
    )
}

private const val SCHEME_NAME_MAX_LENGTH = 24

/**
 * 逐项参数行，点按在原位展开编辑区，同一时间只展开一项。标题旁的标签注明取值来源（[ownLabel] 为本层，
 * 如“本会话”；沿用上一层为“助手”）与可用性提醒；末尾收起官方接口不接受的参数。
 */
@Composable
fun SamplingParamRows(
    state: SamplingEditorState,
    actions: SamplingEditorActions,
    ownLabel: String?,
    initialExpanded: SamplingParam? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(initialExpanded) }
    state.visible.forEach { param ->
        SamplingParamItem(
            param = param,
            state = state,
            ownLabel = ownLabel,
            expanded = expanded == param,
            onToggle = { expanded = if (expanded == param) null else param },
            onCommit = { actions.onParamChange(param, it) },
        )
    }
    if (state.layout.notApplicable.isNotEmpty()) {
        NotApplicableParams(state.layout.notApplicable)
    }
}

private enum class TagTone { SOURCE, INHERITED, WARNING, MUTED }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SamplingParamItem(
    param: SamplingParam,
    state: SamplingEditorState,
    ownLabel: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCommit: (Double?) -> Unit,
) {
    val committed = state.effective.valueOf(param)?.toDouble()
    val source = state.source(param)
    // 加减与输入先改本地草稿，停顿后再写入，长按连续调整时不必每一步都落库。
    var draft by remember(param) { mutableStateOf(committed) }
    var pending by remember(param) { mutableStateOf(false) }
    val commit by rememberUpdatedState(onCommit)
    LaunchedEffect(committed) { if (!pending) draft = committed }
    LaunchedEffect(draft, pending) {
        if (pending) {
            delay(COMMIT_DELAY_MS)
            pending = false
            commit(draft)
        }
    }
    DisposableEffect(param) { onDispose { if (pending) commit(draft) } }
    val change: (Double?, Boolean) -> Unit = { value, immediate ->
        draft = value
        if (immediate) {
            pending = false
            commit(value)
        } else {
            pending = true
        }
    }

    val title = samplingParamTitle(param)
    val defaultLabel = stringResource(R.string.params_value_default)
    val valueText = draft?.let { formatSamplingValue(param, it) } ?: defaultLabel
    val tags = buildList {
        when (source) {
            ParamSource.OWN -> ownLabel?.let { add(it to TagTone.SOURCE) }
            ParamSource.INHERITED -> add(stringResource(R.string.params_source_agent) to TagTone.INHERITED)
            ParamSource.DEFAULT -> Unit
        }
        when (state.availability(param)) {
            ParamAvailability.MAY_BE_IGNORED -> add(stringResource(R.string.params_tag_may_be_ignored) to TagTone.WARNING)
            ParamAvailability.NOT_SENT_WHILE_THINKING -> add(stringResource(R.string.params_tag_off_while_thinking) to TagTone.MUTED)
            else -> Unit
        }
    }
    val stateText = (listOf(valueText) + tags.map { it.first }).joinToString(stringResource(R.string.params_list_separator))
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .heightIn(min = 52.dp)
                .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 8.dp)
                .semantics { stateDescription = stateText }
                .testTag("params_row_${param.name.lowercase()}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(Alignment.CenterVertically))
                    tags.forEach { (text, tone) -> ParamTag(text, tone, Modifier.align(Alignment.CenterVertically)) }
                }
                // 中文主名下方给出接口参数名，便于对照服务商文档（S8）。
                Text(
                    param.wireName,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = com.promenar.infinity.ui.theme.InfinityMonospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("params_row_${param.name.lowercase()}_wire"),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = if (draft == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SamplingParamEditor(
                param = param,
                title = title,
                draft = draft,
                resetLabel = when {
                    source == ParamSource.DEFAULT -> null
                    source == ParamSource.OWN && !state.followsScheme && state.inherited.valueOf(param) != null ->
                        stringResource(R.string.params_restore_agent)
                    else -> defaultLabel
                },
                resetLabels = listOfNotNull(
                    defaultLabel,
                    state.inherited.valueOf(param)?.let { stringResource(R.string.params_restore_agent) },
                ),
                onChange = change,
            )
        }
    }
}

private const val COMMIT_DELAY_MS = 400L

/** 展开后的编辑区：减、可直接输入的数值、加与恢复在同一行，下面是快捷值。 */
@Composable
private fun SamplingParamEditor(
    param: SamplingParam,
    title: String,
    draft: Double?,
    /** null 表示没有可恢复的值，按钮置灰但仍占位。 */
    resetLabel: String?,
    /** 恢复按钮可能出现的全部文案，按最长的占宽。 */
    resetLabels: List<String>,
    onChange: (Double?, Boolean) -> Unit,
) {
    val haptics = LocalInfinityHaptics.current
    val focusManager = LocalFocusManager.current
    val range = samplingParamRange(param)
    var focused by remember { mutableStateOf(false) }
    var text by remember(param) { mutableStateOf(draft?.let { formatSamplingInput(param, it) }.orEmpty()) }
    LaunchedEffect(draft) { if (!focused) text = draft?.let { formatSamplingInput(param, it) }.orEmpty() }
    val invalid = text.isNotBlank() && parseSamplingInput(param, text) == null
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = InfinitySpacing.RowHorizontal, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InfinityStepperButton(
                Icons.Rounded.Remove, stringResource(R.string.p0_decrease_setting, title),
                canStepSamplingValue(param, draft, -1), { onChange(stepSamplingValue(param, draft, -1), false) }, outlined = true,
            )
            SamplingNumberField(
                text = text,
                onTextChange = { input ->
                    text = input
                    parseSamplingInput(param, input)?.let { onChange(it, false) }
                },
                title = title,
                placeholder = stringResource(R.string.params_value_default),
                isError = invalid,
                decimal = range.decimals > 0,
                onFocusChange = { now ->
                    focused = now
                    if (!now) text = draft?.let { formatSamplingInput(param, it) }.orEmpty()
                },
                onDone = { focusManager.clearFocus() },
                modifier = Modifier.weight(1f).testTag("params_input_${param.name.lowercase()}"),
            )
            InfinityStepperButton(
                Icons.Rounded.Add, stringResource(R.string.p0_increase_setting, title),
                canStepSamplingValue(param, draft, 1), { onChange(stepSamplingValue(param, draft, 1), false) }, outlined = true,
            )
            // 恢复按钮始终占位且宽度固定：值写入后才出现会把加号挤走，连点时第二下就会落到恢复上。
            val shownLabel = resetLabel ?: resetLabels.first()
            Spacer(Modifier.width(4.dp))
            FilledTonalButton(
                onClick = {
                    haptics.select(false)
                    focusManager.clearFocus()
                    onChange(null, true)
                },
                enabled = resetLabel != null || draft != null,
                contentPadding = PaddingValues(start = 10.dp, end = 12.dp),
                modifier = Modifier.heightIn(min = 44.dp).testTag("params_reset_${param.name.lowercase()}"),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Box {
                    resetLabels.forEach { label ->
                        if (label == shownLabel) {
                            Text(label, maxLines = 1)
                        } else {
                            Text(label, maxLines = 1, modifier = Modifier.alpha(0f).clearAndSetSemantics { })
                        }
                    }
                }
            }
        }
        if (invalid) {
            Text(
                stringResource(R.string.numeric_range, formatSamplingInput(param, range.min), formatSamplingInput(param, range.max)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 52.dp, top = 4.dp).testTag("params_input_error"),
            )
        }
        // 快捷值等宽排成一行，与上方加减区左右对齐。
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            range.presets.forEach { preset ->
                val selected = draft == preset
                PresetOption(
                    label = formatSamplingValue(param, preset),
                    selected = selected,
                    onClick = {
                        haptics.select(selected)
                        focusManager.clearFocus()
                        onChange(preset, true)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 快捷值按钮：等宽、内边距小，窄屏下五个值也能排在一行。 */
@Composable
private fun PresetOption(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(colors.secondaryContainer, shape) else Modifier.border(1.dp, colors.outlineVariant, shape))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SamplingNumberField(
    text: String,
    onTextChange: (String) -> Unit,
    title: String,
    placeholder: String,
    isError: Boolean,
    decimal: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val borderColor = when {
        isError -> colors.error
        focused -> colors.primary
        else -> colors.outlineVariant
    }
    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(
            fontFeatureSettings = "tnum", textAlign = TextAlign.Center, color = colors.onSurface,
        ).forInput(),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.height(44.dp)
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .semantics { contentDescription = title },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxSize()
                    .border(if (focused || isError) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (text.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                }
                inner()
            }
        },
    )
}

@Composable
private fun ParamTag(text: String, tone: TagTone, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val (container, content) = when (tone) {
        TagTone.SOURCE -> colors.primaryContainer to colors.onPrimaryContainer
        TagTone.INHERITED -> colors.secondaryContainer to colors.onSecondaryContainer
        TagTone.WARNING -> domain.warningContainer to domain.onWarningContainer
        TagTone.MUTED -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        modifier = modifier.background(container, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 官方接口对该模型不接受的参数：默认收起，展开后说明原因。 */
@Composable
private fun NotApplicableParams(params: List<SamplingParam>) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button) { open = !open }
                .heightIn(min = 48.dp)
                .padding(horizontal = InfinitySpacing.RowHorizontal)
                .testTag("params_not_applicable"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                pluralStringResource(R.plurals.params_not_applicable_count, params.size, params.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null,
                modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = InfinitySpacing.RowHorizontal + 30.dp, end = InfinitySpacing.RowHorizontal, bottom = 12.dp)) {
                Text(
                    params.map { samplingParamTitle(it) }.joinToString(stringResource(R.string.params_list_separator)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.params_not_applicable_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
fun samplingParamTitle(param: SamplingParam): String = stringResource(
    when (param) {
        SamplingParam.TEMPERATURE -> R.string.params_temperature
        SamplingParam.TOP_P -> R.string.sheet_settings_top_p
        SamplingParam.TOP_K -> R.string.sheet_settings_top_k
        SamplingParam.MAX_TOKENS -> R.string.sheet_settings_max_tokens
        SamplingParam.FREQUENCY_PENALTY -> R.string.settings_ia_param_frequency_penalty
        SamplingParam.PRESENCE_PENALTY -> R.string.settings_ia_param_presence_penalty
        SamplingParam.REPETITION_PENALTY -> R.string.sheet_settings_repetition_penalty
    },
)

/** 参数行副标题中的接口参数名。 */
internal val SamplingParam.wireName: String
    get() = when (this) {
        SamplingParam.TEMPERATURE -> "temperature"
        SamplingParam.TOP_P -> "top_p"
        SamplingParam.TOP_K -> "top_k"
        SamplingParam.MAX_TOKENS -> "max_tokens"
        SamplingParam.FREQUENCY_PENALTY -> "frequency_penalty"
        SamplingParam.PRESENCE_PENALTY -> "presence_penalty"
        SamplingParam.REPETITION_PENALTY -> "repetition_penalty"
    }

/** 显示顺序：常用的温度、Top P 在前，惩罚项在后。 */
val SAMPLING_PARAM_ORDER: List<SamplingParam> = listOf(
    SamplingParam.TEMPERATURE,
    SamplingParam.TOP_P,
    SamplingParam.TOP_K,
    SamplingParam.MAX_TOKENS,
    SamplingParam.REPETITION_PENALTY,
    SamplingParam.PRESENCE_PENALTY,
    SamplingParam.FREQUENCY_PENALTY,
)
