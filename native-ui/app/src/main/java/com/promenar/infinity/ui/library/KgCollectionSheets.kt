package com.promenar.infinity.ui.library

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.rag.KgBatchEstimate
import com.promenar.infinity.data.rag.KgBatchPlanner
import com.promenar.infinity.data.rag.KgCollectionArticle
import com.promenar.infinity.data.rag.KgCollectionRepository
import com.promenar.infinity.data.rag.KgEntityResolutionStore
import com.promenar.infinity.data.rag.KgFolderSetting
import com.promenar.infinity.data.rag.KgResolutionLink
import com.promenar.infinity.data.rag.KgResolutionPair
import com.promenar.infinity.data.rag.KgResolutionRun
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.utils.InfinityLogger
import java.text.NumberFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 文件夹的图谱归属：独立作品（默认）、通用知识、与上级文件夹合并。 */
internal enum class FolderGraphMode(@StringRes val label: Int, @StringRes val description: Int) {
    WORK(R.string.ks_folder_mode_work, R.string.ks_folder_mode_work_desc),
    SHARED(R.string.ks_folder_mode_shared, R.string.ks_folder_mode_shared_desc),
    MERGE_PARENT(R.string.ks_folder_mode_merge, R.string.ks_folder_mode_merge_desc),
}

internal fun KgFolderSetting.toMode() = when (this) {
    KgFolderSetting.SHARED -> FolderGraphMode.SHARED
    KgFolderSetting.MERGE_PARENT -> FolderGraphMode.MERGE_PARENT
    KgFolderSetting.AUTO, KgFolderSetting.WORK -> FolderGraphMode.WORK
}

internal fun FolderGraphMode.toSetting() = when (this) {
    FolderGraphMode.WORK -> KgFolderSetting.AUTO
    FolderGraphMode.SHARED -> KgFolderSetting.SHARED
    FolderGraphMode.MERGE_PARENT -> KgFolderSetting.MERGE_PARENT
}

// ---------------------------------------------------------------------------------------------
// 批量抽取确认

/** 批量抽取的对象：多选的文档，或整个文件夹（含子文件夹）。 */
internal sealed interface KgBatchTarget {
    data class Documents(val ids: List<String>) : KgBatchTarget
    data class Folder(val folderId: String) : KgBatchTarget
}

/** 一次估算得到两种选择：只抽未抽取的，以及全部重新抽取。 */
internal data class KgBatchChoices(val pending: KgBatchEstimate, val all: KgBatchEstimate) {
    /** 两种选择结果不同时才让用户选。 */
    val offersChoice get() = pending.documentCount > 0 && pending.skippedCount > 0
    fun defaultOnlyPending() = pending.documentCount > 0
}

/** 全部文档估算一次，再从中筛出未抽取的部分，避免重复读取正文。 */
internal fun kgBatchChoices(all: KgBatchEstimate): KgBatchChoices =
    KgBatchChoices(KgBatchPlanner.aggregate(all.documents, onlyUnextracted = true), all)

private sealed interface BatchLoad {
    data object Loading : BatchLoad
    data class Failed(val reason: String) : BatchLoad
    data class Ready(val choices: KgBatchChoices) : BatchLoad
}

/**
 * 批量抽取确认面板：估算后由用户选择范围并确认，确认后经文档索引队列逐篇抽取。
 * [onQueued] 参数为失败原因，成功时为 null。
 */
@Composable
internal fun KgBatchExtractionHost(
    app: InfinityApplication,
    target: KgBatchTarget,
    onQueued: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val batch = remember(app) { app.kgBatchExtraction() }
    val modelLabel = remember(app) { app.kgModelLabel() }
    var load by remember(target) { mutableStateOf<BatchLoad>(BatchLoad.Loading) }
    var onlyPending by remember(target) { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(target) {
        load = try {
            val ids = when (target) {
                is KgBatchTarget.Documents -> target.ids
                is KgBatchTarget.Folder -> KgCollectionRepository(app.database).folderDocumentIds(target.folderId)
            }
            val choices = kgBatchChoices(batch.estimateDocuments(ids, onlyUnextracted = false))
            onlyPending = choices.defaultOnlyPending()
            BatchLoad.Ready(choices)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            InfinityLogger.logError("[Library] 批量图谱估算失败", failure)
            BatchLoad.Failed(failure.message.orEmpty())
        }
    }
    InfinityBottomSheet(show = true, onDismiss = onDismiss, title = stringResource(R.string.kgc_batch_title),
        dismissEnabled = !submitting, scrollContent = true) {
        Column(Modifier.fillMaxWidth().testTag("kg_batch_sheet")) {
            AnimatedContent(targetState = load, transitionSpec = { InfinityMotion.fadeThrough() }, label = "kg_batch_load") { state ->
                when (state) {
                    BatchLoad.Loading -> Row(
                        Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.Large),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.kgc_batch_estimating), style = MaterialTheme.typography.bodyMedium)
                    }
                    is BatchLoad.Failed -> SheetNote(stringResource(R.string.kgc_batch_failed, state.reason), error = true)
                    is BatchLoad.Ready -> BatchChoicesContent(state.choices, onlyPending, modelLabel) { onlyPending = it }
                }
            }
            val ready = load as? BatchLoad.Ready
            val selected = ready?.choices?.let { if (onlyPending) it.pending else it.all }
            SheetActions(
                confirmText = stringResource(R.string.kgc_batch_start),
                confirmEnabled = !submitting && modelLabel != null && selected != null && selected.documentCount > 0,
                busy = submitting,
                onCancel = onDismiss,
                confirmTag = "kg_batch_start",
                onConfirm = {
                    val estimate = selected ?: return@SheetActions
                    submitting = true
                    scope.launch {
                        val failure = try {
                            batch.enqueue(estimate)
                            null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            InfinityLogger.logError("[Library] 批量图谱提交失败", failure)
                            failure.message.orEmpty()
                        }
                        submitting = false
                        onQueued(failure)
                        onDismiss()
                    }
                },
            )
        }
    }
}

@Composable
private fun BatchChoicesContent(choices: KgBatchChoices, onlyPending: Boolean, modelLabel: String?, onSelect: (Boolean) -> Unit) {
    val selected = if (onlyPending) choices.pending else choices.all
    Column(Modifier.fillMaxWidth()) {
        if (choices.offersChoice) {
            InfinitySettingsItem(
                horizontalPadding = 0.dp,
                title = stringResource(R.string.kgc_batch_pending, choices.pending.documentCount),
                subtitle = stringResource(R.string.kgc_batch_pending_desc, choices.pending.skippedCount),
                subtitleMaxLines = 2,
                role = Role.RadioButton,
                selected = onlyPending,
                onClick = { onSelect(true) },
                modifier = Modifier.testTag("kg_batch_only_pending"),
                trailingContent = if (onlyPending) { { SelectedMark() } } else null,
            )
            InfinitySettingsItem(
                horizontalPadding = 0.dp,
                title = stringResource(R.string.kgc_batch_all, choices.all.documentCount),
                subtitle = stringResource(R.string.kgc_batch_all_desc),
                subtitleMaxLines = 3,
                role = Role.RadioButton,
                selected = !onlyPending,
                onClick = { onSelect(false) },
                modifier = Modifier.testTag("kg_batch_all"),
                trailingContent = if (!onlyPending) { { SelectedMark() } } else null,
            )
        } else if (choices.all.documentCount > 0) {
            val title = if (choices.pending.documentCount > 0) stringResource(R.string.kgc_batch_pending, choices.pending.documentCount)
                else stringResource(R.string.kgc_batch_all, choices.all.documentCount)
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = InfinitySpacing.Small))
        }
        when {
            choices.all.documentCount == 0 -> SheetNote(stringResource(R.string.kgc_batch_empty))
            modelLabel == null -> SheetNote(stringResource(R.string.kgc_batch_no_model), error = true)
            else -> {
                val number = NumberFormat.getIntegerInstance()
                SheetNote(
                    stringResource(
                        R.string.kgc_batch_estimate,
                        modelLabel,
                        number.format(selected.windows),
                        number.format(selected.calls),
                        number.format(selected.outputTokenLimit),
                    ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 合集设置

/** 合集设置面板一次读取的内容。[folderMode] 只在从文件夹打开时有值。 */
internal data class KgCollectionSettingsData(
    val collectionId: String,
    val folderMode: FolderGraphMode?,
    val articles: List<KgCollectionArticle>,
    val candidates: List<KgResolutionPair>,
    val links: List<KgResolutionLink>,
    val names: Map<String, String>,
)

/** 从文件夹打开时按当前文件夹设置解析合集；切换类型后合集可能随之改变。 */
internal suspend fun loadKgCollectionSettings(database: InfinityDatabase, folderId: String?, collectionId: String?): KgCollectionSettingsData =
    withContext(Dispatchers.IO) {
        val repository = KgCollectionRepository(database)
        val id = folderId?.let { repository.folderCollection(it) } ?: requireNotNull(collectionId) { "合集不存在" }
        val resolution = KgEntityResolutionStore(database)
        KgCollectionSettingsData(
            collectionId = id,
            folderMode = folderId?.let { repository.readFolderSetting(it).toMode() },
            articles = repository.listArticles(id),
            candidates = resolution.candidates(id),
            links = resolution.listLinks(id),
            names = database.kgNodeDao().getByCollection(id).associate { it.id to it.name },
        )
    }

/** 存在手动篇序时，后来按名称插入的篇目标为“新加入”。 */
internal fun KgCollectionArticle.isNewlyAdded(all: List<KgCollectionArticle>): Boolean =
    orderSource != "manual" && all.any { it.orderSource == "manual" }

/** 候选列表最多展示的组数；其余仍计入数量并参与“检查合并”。 */
private const val CANDIDATE_ROWS = 20

/**
 * 合集设置：类型（从文件夹打开时）、篇目顺序与实体合并。
 * 调整篇序只重算时间线；实体合并只在点击“检查合并”时调用图谱模型。
 */
@Composable
internal fun KgCollectionSettingsHost(
    app: InfinityApplication,
    title: String,
    folderId: String?,
    collectionId: String?,
    onDismiss: () -> Unit,
) {
    val repository = remember(app) { KgCollectionRepository(app.database) }
    val resolution = remember(app) { app.kgEntityResolution() }
    val modelId = remember(app) { app.kgModelId() }
    val modelLabel = remember(app) { app.kgModelLabel() }
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<KgCollectionSettingsData?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var lastRun by remember { mutableStateOf<KgResolutionRun?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(reloadKey) {
        try {
            data = loadKgCollectionSettings(app.database, folderId, data?.collectionId ?: collectionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            InfinityLogger.logError("[Library] 合集设置读取失败", error)
            failure = error.message.orEmpty()
        }
    }
    // 写入后重新读取；失败原因显示在面板顶部。
    fun mutate(action: suspend (String) -> Unit) {
        val id = data?.collectionId ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action(id) }
                failure = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                InfinityLogger.logError("[Library] 合集设置写入失败", error)
                failure = error.message.orEmpty()
            } finally {
                busy = false
                reloadKey++
            }
        }
    }
    InfinityBottomSheet(show = true, onDismiss = onDismiss, title = stringResource(R.string.kgc_settings_title, title), scrollContent = true) {
        Column(Modifier.fillMaxWidth().testTag("kg_collection_settings")) {
            AnimatedVisibility(visible = failure != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                SheetNote(stringResource(R.string.kgc_failed, failure.orEmpty()), error = true)
            }
            val current = data
            if (current == null) {
                if (failure == null) CircularProgressIndicator(Modifier.padding(vertical = InfinitySpacing.Large).size(20.dp), strokeWidth = 2.dp)
                return@Column
            }
            current.folderMode?.let { mode ->
                SectionHeader(R.string.kgc_type_header)
                SheetNote(stringResource(R.string.ks_folder_desc))
                FolderGraphMode.entries.forEach { option ->
                    InfinitySettingsItem(
                        horizontalPadding = 0.dp,
                        title = stringResource(option.label),
                        subtitle = stringResource(option.description),
                        subtitleMaxLines = 3,
                        role = Role.RadioButton,
                        selected = option == mode,
                        enabled = !busy,
                        onClick = { if (option != mode) mutate { repository.setFolderSetting(requireNotNull(folderId), option.toSetting()) } },
                        modifier = Modifier.testTag("folder_graph_mode_${option.name.lowercase()}"),
                        trailingContent = if (option == mode) { { SelectedMark() } } else null,
                    )
                }
            }
            ArticleOrderSection(current.articles, busy,
                onMove = { docId, target -> mutate { repository.moveArticle(it, docId, target) } },
                onReset = { mutate { repository.resetNaturalOrder(it) } })
            EntityMergeSection(
                data = current,
                busy = busy || checking,
                checking = checking,
                lastRun = lastRun,
                modelLabel = modelLabel,
                maxOutputTokens = app.kgRequestMaxOutputTokens(),
                onDecide = { pair, same -> mutate { resolution.setUserDecision(it, pair.first.id, pair.second.id, same) } },
                onRevoke = { link -> mutate { resolution.revoke(it, link.id) } },
                onCheck = {
                    val id = current.collectionId
                    val model = modelId ?: return@EntityMergeSection
                    checking = true
                    scope.launch {
                        try {
                            lastRun = withContext(Dispatchers.IO) { resolution.runManual(id, model, app.kgRequestMaxOutputTokens()) }
                            failure = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            InfinityLogger.logError("[Library] 实体合并检查失败", error)
                            failure = error.message.orEmpty()
                        } finally {
                            checking = false
                            reloadKey++
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ArticleOrderSection(
    articles: List<KgCollectionArticle>,
    busy: Boolean,
    onMove: (docId: String, targetIndex: Int) -> Unit,
    onReset: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(R.string.kgc_order_header, Modifier.weight(1f))
        if (articles.any { it.orderSource == "manual" }) {
            TextButton(onClick = onReset, enabled = !busy, modifier = Modifier.testTag("kg_order_reset")) {
                Text(stringResource(R.string.kgc_order_reset), maxLines = 1)
            }
        }
    }
    SheetNote(stringResource(if (articles.isEmpty()) R.string.kgc_order_empty else R.string.kgc_order_desc))
    articles.forEachIndexed { index, article ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag("kg_order_row_$index"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.kgc_order_item, article.ordinal, article.title),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (article.isNewlyAdded(articles)) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(
                        stringResource(R.string.kgc_order_new),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = InfinitySpacing.Small, vertical = 2.dp),
                    )
                }
            }
            IconButton(onClick = { onMove(article.docId, index - 1) }, enabled = !busy && index > 0) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.kgc_order_up, article.title))
            }
            IconButton(onClick = { onMove(article.docId, index + 1) }, enabled = !busy && index < articles.lastIndex) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.kgc_order_down, article.title))
            }
        }
    }
}

@Composable
private fun EntityMergeSection(
    data: KgCollectionSettingsData,
    busy: Boolean,
    checking: Boolean,
    lastRun: KgResolutionRun?,
    modelLabel: String?,
    maxOutputTokens: Int,
    onDecide: (KgResolutionPair, Boolean) -> Unit,
    onRevoke: (KgResolutionLink) -> Unit,
    onCheck: () -> Unit,
) {
    SectionHeader(R.string.kgc_merge_header)
    SheetNote(stringResource(R.string.kgc_merge_desc))
    val candidates = data.candidates
    Text(
        if (candidates.isEmpty()) stringResource(R.string.kgc_merge_none)
        else pluralStringResource(R.plurals.kgc_merge_candidates, candidates.size, candidates.size),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(vertical = InfinitySpacing.XSmall),
    )
    if (candidates.isNotEmpty()) {
        val estimate = remember(candidates.size, maxOutputTokens) {
            com.promenar.infinity.data.rag.KgEntityResolution.estimate(candidates.size, maxOutputTokens)
        }
        SheetNote(
            if (modelLabel == null) stringResource(R.string.kgc_batch_no_model)
            else stringResource(R.string.kgc_merge_estimate, modelLabel, estimate.candidateCount, estimate.calls),
            error = modelLabel == null,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = onCheck, enabled = !busy && modelLabel != null, modifier = Modifier.testTag("kg_merge_check")) {
                if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(if (checking) R.string.kgc_merge_checking else R.string.kgc_merge_check),
                    maxLines = 1,
                    modifier = Modifier.padding(start = if (checking) InfinitySpacing.Small else 0.dp),
                )
            }
        }
    }
    AnimatedVisibility(visible = lastRun != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
        lastRun?.let { run -> SheetNote(stringResource(R.string.kgc_merge_result, run.decided, run.merged, run.uncertain)) }
    }
    candidates.take(CANDIDATE_ROWS).forEach { pair ->
        Column(Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.XSmall)) {
            Text(
                stringResource(R.string.kgc_merge_pair, pair.first.name, pair.second.name),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small, Alignment.End)) {
                TextButton(onClick = { onDecide(pair, false) }, enabled = !busy) { Text(stringResource(R.string.kgc_merge_different), maxLines = 1) }
                FilledTonalButton(onClick = { onDecide(pair, true) }, enabled = !busy) { Text(stringResource(R.string.kgc_merge_same), maxLines = 1) }
            }
        }
    }
    if (data.links.isNotEmpty()) {
        SectionHeader(R.string.kgc_merge_merged_header)
        data.links.forEach { link ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = InfinitySpacing.MinimumTouchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.kgc_merge_link, data.names[link.toNode] ?: link.toNode, data.names[link.fromNode] ?: link.fromNode),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            when (link.decidedBy) {
                                "user" -> R.string.kgc_merge_by_user
                                "alias" -> R.string.kgc_merge_by_alias
                                else -> R.string.kgc_merge_by_model
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onRevoke(link) }, enabled = !busy) { Text(stringResource(R.string.kgc_merge_revoke), maxLines = 1) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 面板内的通用小件

@Composable
private fun SectionHeader(@StringRes label: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = InfinitySpacing.Large, bottom = InfinitySpacing.XSmall),
    )
}

@Composable
private fun SheetNote(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = InfinitySpacing.XSmall),
    )
}

@Composable
private fun SelectedMark() {
    Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
}

@Composable
private fun SheetActions(
    confirmText: String,
    confirmEnabled: Boolean,
    busy: Boolean,
    confirmTag: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = InfinitySpacing.Large),
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.common_btn_cancel), maxLines = 1) }
        FilledTonalButton(onClick = onConfirm, enabled = confirmEnabled, modifier = Modifier.testTag(confirmTag)) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(confirmText, maxLines = 1, modifier = Modifier.padding(start = if (busy) InfinitySpacing.Small else 0.dp))
        }
    }
}
