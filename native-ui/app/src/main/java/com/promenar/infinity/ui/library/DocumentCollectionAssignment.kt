package com.promenar.infinity.ui.library

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.rag.KgCollectionRepository
import com.promenar.infinity.data.rag.KgCollectionSummary
import com.promenar.infinity.data.rag.KgDocumentAssignment
import com.promenar.infinity.data.rag.KgDocumentSetting
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 文档的合集归属：默认跟随所在文件夹；也可以设为独立合集、归入通用知识，或加入另一部已有作品。
 * 一次可设置一篇或多篇（资料页资源管理器的多选）。已抽取的图谱随归属变化迁移，不重新调用模型。
 */

/** 文档详情“合集归属”行显示的当前方式。 */
@StringRes
internal fun KgDocumentSetting.rowLabel(): Int = when (this) {
    KgDocumentSetting.FOLLOW_FOLDER -> R.string.kda_mode_follow
    KgDocumentSetting.STANDALONE -> R.string.kda_mode_standalone
    KgDocumentSetting.SHARED -> R.string.kda_mode_shared
    KgDocumentSetting.COLLECTION -> R.string.kda_mode_collection
}

/** 要设置归属的一篇文档。 */
internal data class AssignmentDocument(val id: String, val name: String)

private data class AssignmentData(val current: List<KgDocumentAssignment>, val collections: List<KgCollectionSummary>)

/** 多篇文档共同的归属方式与目标合集；方式不一致时为 null，面板里不预选任何一项。 */
internal fun commonAssignment(current: List<KgDocumentAssignment>): Pair<KgDocumentSetting, String?>? {
    val choices = current.map { it.mode to it.targetCollectionId.takeIf { _ -> it.mode == KgDocumentSetting.COLLECTION } }.distinct()
    return choices.singleOrNull()
}

/** 可加入的作品合集：排除所选文档自己的独立合集。 */
internal fun assignableFor(collections: List<KgCollectionSummary>, documentIds: Collection<String>): List<KgCollectionSummary> {
    val own = documentIds.mapTo(HashSet()) { "work:$it" }
    return collections.filter { it.id !in own }
}

/**
 * “选择合集”列出的合集：所选文档都在同一个文件夹合集里时，那个合集已由“跟随文件夹”表示，不再重复列出；
 * 它正是当前选中的目标时保留，便于看清当前归属。
 */
internal fun joinableCollections(
    collections: List<KgCollectionSummary>,
    current: List<KgDocumentAssignment>,
    target: String?,
): List<KgCollectionSummary> {
    val folder = current.map { it.folderCollectionId }.distinct().singleOrNull()
    return collections.filter { it.id != folder || it.id == target }
}

@Composable
internal fun DocumentCollectionAssignmentSheet(
    app: InfinityApplication,
    documentId: String,
    documentName: String,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) = DocumentCollectionAssignmentSheet(app, listOf(AssignmentDocument(documentId, documentName)), onChanged, onDismiss)

/** 一篇或多篇文档的合集归属面板；多篇时一次写入，任一篇失败整体不变。 */
@Composable
internal fun DocumentCollectionAssignmentSheet(
    app: InfinityApplication,
    documents: List<AssignmentDocument>,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val repository = remember(app) { KgCollectionRepository(app.database) }
    val scope = rememberCoroutineScope()
    val ids = remember(documents) { documents.map { it.id } }
    val multiple = ids.size > 1
    var data by remember(ids) { mutableStateOf<AssignmentData?>(null) }
    var failure by remember(ids) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(ids, reloadKey) {
        try {
            data = withContext(Dispatchers.IO) {
                AssignmentData(
                    ids.map { repository.readDocumentSetting(it) },
                    assignableFor(repository.listAssignableCollections(ids.first()), ids),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            InfinityLogger.logError("[Library] 读取合集归属失败", error)
            failure = error.message.orEmpty()
        }
    }
    fun choose(mode: KgDocumentSetting, target: String? = null) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (multiple) repository.setDocumentSettings(ids, mode, target)
                    else repository.setDocumentSetting(ids.first(), mode, target)
                }
                failure = null
                onChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                InfinityLogger.logError("[Library] 修改合集归属失败", error)
                failure = error.message.orEmpty()
            } finally {
                busy = false
                reloadKey++
            }
        }
    }
    val title = if (multiple) pluralStringResource(R.plurals.lrc_assign_title_multi, ids.size, ids.size)
        else stringResource(R.string.kda_title, documents.first().name)
    InfinityBottomSheet(show = true, onDismiss = onDismiss, title = title, scrollContent = true) {
        Column(Modifier.fillMaxWidth().testTag("library_doc_assignment")) {
            AnimatedVisibility(visible = failure != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                SheetNote(stringResource(R.string.kgc_failed, failure.orEmpty()), error = true)
            }
            SheetNote(stringResource(R.string.kda_desc))
            val current = data
            if (current == null) {
                if (failure == null) CircularProgressIndicator(Modifier.padding(vertical = InfinitySpacing.Large).size(20.dp), strokeWidth = 2.dp)
                return@Column
            }
            val common = commonAssignment(current.current)
            val mode = common?.first
            val target = common?.second
            AssignmentOption(
                title = stringResource(R.string.kda_mode_follow),
                subtitle = if (multiple) stringResource(R.string.lrc_follow_multi_desc)
                    else stringResource(R.string.kda_follow_desc, current.current.first().folderCollectionTitle),
                selected = mode == KgDocumentSetting.FOLLOW_FOLDER,
                enabled = !busy,
                tag = "follow",
                onClick = { choose(KgDocumentSetting.FOLLOW_FOLDER) },
            )
            AssignmentOption(
                title = stringResource(R.string.kda_mode_standalone),
                subtitle = stringResource(if (multiple) R.string.lrc_standalone_multi_desc else R.string.kda_standalone_desc),
                selected = mode == KgDocumentSetting.STANDALONE,
                enabled = !busy,
                tag = "standalone",
                onClick = { choose(KgDocumentSetting.STANDALONE) },
            )
            AssignmentOption(
                title = stringResource(R.string.kda_mode_shared),
                subtitle = stringResource(R.string.kda_shared_desc),
                selected = mode == KgDocumentSetting.SHARED,
                enabled = !busy,
                tag = "shared",
                onClick = { choose(KgDocumentSetting.SHARED) },
            )
            SectionHeader(R.string.kda_join_header)
            val joinable = joinableCollections(current.collections, current.current, target)
            if (joinable.isEmpty()) {
                SheetNote(stringResource(R.string.kda_join_empty))
            }
            joinable.forEach { collection ->
                AssignmentOption(
                    title = collection.title,
                    subtitle = pluralStringResource(R.plurals.kda_collection_docs, collection.documentCount, collection.documentCount),
                    selected = mode == KgDocumentSetting.COLLECTION && target == collection.id,
                    enabled = !busy,
                    tag = "join_${collection.id}",
                    onClick = { choose(KgDocumentSetting.COLLECTION, collection.id) },
                )
            }
        }
    }
}

@Composable
private fun AssignmentOption(title: String, subtitle: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    InfinitySettingsItem(
        horizontalPadding = 0.dp,
        title = title,
        subtitle = subtitle,
        subtitleMaxLines = 3,
        role = Role.RadioButton,
        selected = selected,
        enabled = enabled,
        onClick = { if (!selected) onClick() },
        modifier = Modifier.testTag("library_doc_assignment_$tag"),
        trailingContent = if (selected) { { SelectedMark() } } else null,
    )
}
