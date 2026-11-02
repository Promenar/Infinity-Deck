package com.promenar.infinity.ui.chat.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import com.promenar.infinity.ui.theme.infinityDomainColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.data.state.*
import com.promenar.infinity.ui.chat.progress
import com.promenar.infinity.ui.chat.stateValueText
import com.promenar.infinity.ui.common.rememberStreamAnimationsEnabled
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityMetaLine
import androidx.compose.material3.LocalTextStyle
import com.promenar.infinity.ui.theme.forInput

/**
 * 运行详情“状态”页：面板标题与更新时间、按字段类型排版的卡片（数值与进度两两并排，文本与列表占整行），
 * 点按卡片编辑；移除收进右上角菜单。刚被 Agent 更新的项高亮，用户改过的项单独标注。
 */
@Composable
fun StateBoardContent(
    snapshot: StateBoardSnapshot,
    isGenerating: Boolean,
    onEdit: suspend (Long, StateOperation) -> String?,
    onRemove: suspend (Long) -> String?,
    modifier: Modifier = Modifier,
    onRemoved: () -> Unit = {},
) {
    val board = snapshot.board ?: return
    var editing by remember { mutableStateOf<Pair<StateBoardSnapshot, StateField>?>(null) }
    var confirmingRemove by remember { mutableStateOf(false) }
    var removalError by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    StateBoardDetails(
        board = board,
        onEdit = { editing = snapshot to it },
        onRemove = { confirmingRemove = true },
        isGenerating = isGenerating,
        modifier = modifier,
        error = removalError,
    )
    editing?.let { (original, field) ->
        StateFieldEditor(original, field, onDismiss = { editing = null }, onSave = onEdit)
    }
    if (confirmingRemove) {
        val revision = remember { snapshot.revision }
        InfinityConfirmDialog(
            onDismissRequest = { if (!removing) confirmingRemove = false },
            title = stringResource(R.string.state_remove_title),
            message = stringResource(R.string.state_remove_body),
            confirmText = stringResource(R.string.state_remove),
            cancelText = stringResource(R.string.state_cancel),
            onConfirm = {
                removing = true
                scope.launch {
                    try {
                        removalError = onRemove(revision)
                        confirmingRemove = false
                        if (removalError == null) onRemoved()
                    } finally { removing = false }
                }
            },
            // 删除中取消按钮原为禁用；统一对话框无禁用态，改为删除期间忽略点击。
            onCancel = { confirmingRemove = false },
            cancelEnabled = !removing,
            isDestructive = true,
            confirmEnabled = !removing,
            confirmButtonModifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.CHAT_STATE_REMOVE_CONFIRM),
            cancelButtonModifier = Modifier.heightIn(min = 48.dp),
        )
    }
}

/** 刚更新的高亮窗口：Agent 写入后这段时间内卡片带淡色底与“刚更新”标签。 */
internal const val STATE_FIELD_RECENT_MS = 90_000L

/** 数值与进度类字段较窄，两两并排；文本与列表占整行。 */
internal fun stateFieldRows(fields: List<StateField>): List<List<StateField>> {
    val rows = mutableListOf<List<StateField>>()
    var pending: StateField? = null
    fields.forEach { field ->
        val compact = field.type == "number" || field.type == "meter"
        when {
            compact && pending == null -> pending = field
            compact -> { rows += listOf(pending!!, field); pending = null }
            else -> {
                pending?.let { rows += listOf(it) }
                pending = null
                rows += listOf(field)
            }
        }
    }
    pending?.let { rows += listOf(it) }
    return rows
}

@Composable
fun StateBoardDetails(
    board: StateBoard,
    onEdit: (StateField) -> Unit,
    onRemove: () -> Unit,
    isGenerating: Boolean = false,
    modifier: Modifier = Modifier,
    error: String? = null,
    nowMillis: Long? = null,
) {
    var now by remember { mutableLongStateOf(nowMillis ?: System.currentTimeMillis()) }
    if (nowMillis == null) {
        LaunchedEffect(board) {
            while (true) { now = System.currentTimeMillis(); delay(15_000) }
        }
    }
    val lastUpdated = board.fields.maxOfOrNull { it.updatedAt }?.takeIf { it > 0 }
    val time = lastUpdated?.let { remember(it) { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) } }
    val manual = board.manualKeys.toSet()
    Column(modifier.fillMaxWidth().testTag(UiTags.CHAT_STATE_SHEET)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.TableChart, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(board.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                InfinityMetaLine(
                    segments = listOfNotNull(
                        stringResource(R.string.state_board_maintained_by_agent),
                        time?.let { stringResource(R.string.state_board_updated_at, it) },
                        pluralStringResource(R.plurals.state_board_field_count, board.fields.size, board.fields.size),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag(UiTags.CHAT_STATE_MENU)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.state_board_more))
                }
                InfinityDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    InfinityMenuItem(
                        text = { Text(stringResource(R.string.state_remove), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onRemove() },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.CHAT_STATE_REMOVE),
                    )
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            stateFieldRows(board.fields).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { field ->
                        key(field.key) {
                            StateFieldCard(
                                field = field,
                                manual = field.key in manual,
                                recent = field.key !in manual && field.updatedAt > 0 && now - field.updatedAt in 0..STATE_FIELD_RECENT_MS,
                                isGenerating = isGenerating,
                                onEdit = { onEdit(field) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                    if (row.size == 1 && (row[0].type == "number" || row[0].type == "meter")) Spacer(Modifier.weight(1f))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Text(
            text = stringResource(if (manual.isNotEmpty()) R.string.state_board_manual_note else R.string.state_board_edit_note),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun StateFieldCard(
    field: StateField,
    manual: Boolean,
    recent: Boolean,
    isGenerating: Boolean,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val animations = rememberStreamAnimationsEnabled() == true
    var previous by remember(field.key) { mutableStateOf(field.value) }
    var flash by remember(field.key) { mutableStateOf(false) }
    LaunchedEffect(field.value, animations) {
        val changed = previous != field.value
        previous = field.value
        flash = changed && isGenerating && animations
        if (flash) { delay(700); flash = false }
    }
    val container by animateColorAsState(
        when {
            flash -> scheme.primaryContainer
            recent -> scheme.primaryContainer.copy(alpha = 0.45f).compositeOver(scheme.surfaceContainerHigh)
            else -> scheme.surfaceContainerHigh
        },
        animationSpec = if (animations) tween(180) else snap(), label = "state_field_card",
    )
    val editLabel = stringResource(R.string.state_edit, field.label)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        border = if (recent) BorderStroke(1.dp, scheme.primary.copy(alpha = 0.28f)) else null,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = editLabel, onClick = onEdit)
            .testTag(UiTags.stateField(field.key)),
    ) {
        Box {
            Column(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 36.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(field.label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (field.type == "list") {
                        Text("· ${field.value.jsonArray.size}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)  // wrap-check: allow 紧跟字段名的单行计数
                    }
                    when {
                        manual -> StateFieldTag(stringResource(R.string.state_board_tag_manual), MaterialTheme.infinityDomainColors.warningContainer, MaterialTheme.infinityDomainColors.onWarningContainer)
                        recent -> StateFieldTag(stringResource(R.string.state_board_tag_recent), scheme.primaryContainer, scheme.onPrimaryContainer)
                    }
                }
                StateFieldValue(field)
            }
            Icon(
                Icons.Rounded.Edit,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp).size(16.dp),
            )
        }
    }
}

@Composable
private fun StateFieldTag(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp))
    }
}

@Composable
private fun StateFieldValue(field: StateField) {
    val scheme = MaterialTheme.colorScheme
    when (field.type) {
        "number", "meter" -> {
            val big = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
            val (main, suffix) = if (field.type == "meter") {
                val value = field.value.jsonObject
                value["current"]?.jsonPrimitive?.content.orEmpty() to listOfNotNull("/ ${value["max"]?.jsonPrimitive?.content.orEmpty()}", field.unit).joinToString(" ")
            } else (field.value as? JsonPrimitive)?.content.orEmpty() to field.unit.orEmpty()
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(main.ifEmpty { "—" }, style = big, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (suffix.isNotBlank()) Text(suffix, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
            }
            if (field.type == "meter") {
                LinearProgressIndicator(
                    progress = { field.progress() },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    trackColor = scheme.outlineVariant.copy(alpha = 0.45f),
                )
            }
        }
        "list" -> {
            val items = field.value.jsonArray
            if (items.isEmpty()) Text(stringResource(R.string.state_board_empty_value), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.padding(top = 7.dp).size(6.dp).background(scheme.tertiary, CircleShape))
                    Text(item.jsonPrimitive.content, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                }
            }
        }
        else -> {
            val text = stateValueText(field.value, field.unit)
            Text(
                text.ifBlank { stringResource(R.string.state_board_empty_value) },
                style = MaterialTheme.typography.bodyMedium,
                color = if (text.isBlank()) scheme.onSurfaceVariant else scheme.onSurface,
            )
        }
    }
}

@Composable
private fun StateFieldEditor(snapshot: StateBoardSnapshot, field: StateField, onDismiss: () -> Unit, onSave: suspend (Long, StateOperation) -> String?) {
    var text by remember(field.key) { mutableStateOf(if (field.type == "meter") field.value.jsonObject.getValue("current").jsonPrimitive.content else stateValueText(field.value)) }
    var max by remember(field.key) { mutableStateOf(if (field.type == "meter") field.value.jsonObject.getValue("max").jsonPrimitive.content else "") }
    var items by remember(field.key) { mutableStateOf<List<String>>(if (field.type == "list") field.value.jsonArray.map { it.jsonPrimitive.content } else emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val invalidValue = stringResource(R.string.state_invalid_value)
    InfinityConfirmDialog(
        modifier = Modifier.testTag(UiTags.CHAT_STATE_EDITOR),
        onDismissRequest = { if (!saving) onDismiss() },
        title = stringResource(R.string.state_edit, field.label),
        message = "",
        confirmText = stringResource(R.string.state_save),
        cancelText = stringResource(R.string.state_cancel),
        onConfirm = confirm@{
            val operation = try { com.promenar.infinity.ui.chat.stateFieldEdit(field, text, max, items) }
                catch (_: IllegalArgumentException) { error = invalidValue; return@confirm }
            saving = true
            scope.launch {
                try {
                    error = onSave(snapshot.revision, operation)
                    if (error == null) onDismiss()
                } finally { saving = false }
            }
        },
        // 保存中取消按钮原为禁用；统一对话框无禁用态，改为保存期间忽略点击。
        onCancel = onDismiss,
        cancelEnabled = !saving,
        confirmEnabled = !saving,
        confirmButtonModifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.CHAT_STATE_SAVE),
        cancelButtonModifier = Modifier.heightIn(min = 48.dp),
        content = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                if (field.type == "list") {
                    items.forEachIndexed { index, value ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value, { new -> items = items.toMutableList().also { it[index] = new.take(StateBoardEngine.MAX_ITEM_TEXT) } },
                                modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.state_item, index + 1)) }, enabled = !saving,
                                textStyle = LocalTextStyle.current.forInput())
                            IconButton(enabled = !saving, onClick = { items = items.filterIndexed { i, _ -> i != index } }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.state_remove_item, index + 1))
                            }
                        }
                    }
                    InfinityPillButton(
                        text = stringResource(R.string.state_add_item),
                        onClick = { items = items + "" },
                        enabled = !saving && items.size < StateBoardEngine.MAX_ITEMS,
                        icon = Icons.Rounded.Add,
                    )
                } else {
                    OutlinedTextField(text, { text = it.take(StateBoardEngine.MAX_TEXT) }, modifier = Modifier.testTag(UiTags.CHAT_STATE_VALUE), enabled = !saving,
                        label = { Text(stringResource(if (field.type == "meter") R.string.state_current else R.string.state_value)) },
                        keyboardOptions = KeyboardOptions(keyboardType = if (field.type == "text") KeyboardType.Text else KeyboardType.Decimal),
                        singleLine = field.type != "text", minLines = if (field.type == "text") 3 else 1,
                        textStyle = LocalTextStyle.current.forInput())
                    if (field.type == "meter") OutlinedTextField(max, { max = it.take(40) }, enabled = !saving,
                        modifier = Modifier.testTag(UiTags.CHAT_STATE_MAX), label = { Text(stringResource(R.string.state_max)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                        textStyle = LocalTextStyle.current.forInput())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}
