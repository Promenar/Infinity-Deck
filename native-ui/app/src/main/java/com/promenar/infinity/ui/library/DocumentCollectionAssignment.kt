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
 * 单篇文档的合集归属：默认跟随所在文件夹；也可以单独成为作品、归入通用知识，或手动加入另一部已有作品。
 * 已抽取的图谱随归属变化迁移，不重新调用模型。
 */

/** 文档详情“合集归属”行显示的当前方式。 */
@StringRes
internal fun KgDocumentSetting.rowLabel(): Int = when (this) {
    KgDocumentSetting.FOLLOW_FOLDER -> R.string.kda_mode_follow
    KgDocumentSetting.STANDALONE -> R.string.kda_mode_standalone
    KgDocumentSetting.SHARED -> R.string.kda_mode_shared
    KgDocumentSetting.COLLECTION -> R.string.kda_mode_collection
}

private data class AssignmentData(val current: KgDocumentAssignment, val collections: List<KgCollectionSummary>)

@Composable
internal fun DocumentCollectionAssignmentSheet(
    app: InfinityApplication,
    documentId: String,
    documentName: String,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val repository = remember(app) { KgCollectionRepository(app.database) }
    val scope = rememberCoroutineScope()
    var data by remember(documentId) { mutableStateOf<AssignmentData?>(null) }
    var failure by remember(documentId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(documentId, reloadKey) {
        try {
            data = withContext(Dispatchers.IO) {
                AssignmentData(repository.readDocumentSetting(documentId), repository.listAssignableCollections(documentId))
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
                withContext(Dispatchers.IO) { repository.setDocumentSetting(documentId, mode, target) }
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
    InfinityBottomSheet(show = true, onDismiss = onDismiss, title = stringResource(R.string.kda_title, documentName), scrollContent = true) {
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
            val mode = current.current.mode
            val target = current.current.targetCollectionId.takeIf { mode == KgDocumentSetting.COLLECTION }
            AssignmentOption(
                title = stringResource(R.string.kda_mode_follow),
                subtitle = stringResource(R.string.kda_follow_desc, current.current.folderCollectionTitle),
                selected = mode == KgDocumentSetting.FOLLOW_FOLDER,
                enabled = !busy,
                tag = "follow",
                onClick = { choose(KgDocumentSetting.FOLLOW_FOLDER) },
            )
            AssignmentOption(
                title = stringResource(R.string.kda_mode_standalone),
                subtitle = stringResource(R.string.kda_standalone_desc),
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
            if (current.collections.none { it.id != current.current.folderCollectionId || it.id == target }) {
                SheetNote(stringResource(R.string.kda_join_empty))
            }
            // 所在文件夹的合集已由“跟随文件夹”表示，不再重复列出（已选中时保留，便于看清当前归属）。
            val joinable = current.collections.filter { it.id != current.current.folderCollectionId || it.id == target }
            joinable.forEach { collection ->
                AssignmentOption(
                    title = collection.title,
                    subtitle = pluralStringResource(R.plurals.kda_collection_docs, collection.documentCount, collection.documentCount),
                    selected = target == collection.id,
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
