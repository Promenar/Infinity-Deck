package com.promenar.infinity.ui.library

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.rag.KgCollectionRepository
import com.promenar.infinity.data.rag.KgFolderSetting
import com.promenar.infinity.data.rag.KgNaturalOrder
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.library.PendingStage
import com.promenar.infinity.ui.files.FileFolderHeaderScope
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 资料库文件夹顶部的图谱合集条：合集名、类型、已抽取篇数与按篇序排列的分段进度，
 * 以及“合集设置”“抽取图谱”。文件夹里还没有任何文档抽取过图谱时不显示，文件夹看起来与普通文件夹一致。
 */

/** 合集条的类型：当前文件夹自身的设置决定文案，合集可能来自上级文件夹。 */
internal enum class CollectionStripKind { WORK, SHARED, MERGED }

internal data class LibraryCollectionStripData(
    val folderId: String,
    val collectionTitle: String,
    val kind: CollectionStripKind,
    /** 按篇序排列的各篇是否已抽取。 */
    val segments: List<Boolean>,
) {
    val extracted: Int get() = segments.count { it }
    val total: Int get() = segments.size
}

/** 分段超过这个数量时改用连续进度条，避免细到看不清。 */
private const val MAX_SEGMENTS = 48

internal suspend fun loadLibraryCollectionStrip(database: InfinityDatabase, folderId: String, folderName: String): LibraryCollectionStripData =
    withContext(Dispatchers.IO) {
        val repository = KgCollectionRepository(database)
        val collectionId = repository.folderCollection(folderId)
        val setting = repository.readFolderSetting(folderId)
        val files = database.fileEntryDao()
        val rootRef = collectionId.removePrefix("work:").takeIf { collectionId.startsWith("work:") }
        // 作品合集的范围是它的根文件夹；通用知识跨多个文件夹，只统计当前文件夹。
        val scopeFolder = rootRef?.takeIf { files.findActiveByUuid(it)?.isDirectory == true } ?: folderId
        val articles = repository.listArticles(collectionId)
        val ordinal = articles.associate { it.docId to it.ordinal }
        val docs = repository.folderDocumentIds(scopeFolder)
            .filter { repository.collectionForDocument(it) == collectionId }
            .mapNotNull { files.findActiveByUuid(it) }
            .sortedWith(compareBy<com.promenar.infinity.data.local.db.entity.FileEntry> { ordinal[it.uuid] ?: Int.MAX_VALUE }
                .thenComparator { a, b -> KgNaturalOrder.compare(a.name, b.name) })
        val title = database.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT title FROM kg_collections WHERE id=?", arrayOf(collectionId))).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        LibraryCollectionStripData(
            folderId = folderId,
            collectionTitle = title ?: if (rootRef == null) "" else files.findActiveByUuid(rootRef)?.name ?: folderName,
            kind = when {
                collectionId == "shared" -> CollectionStripKind.SHARED
                setting == KgFolderSetting.MERGE_PARENT && rootRef != folderId -> CollectionStripKind.MERGED
                else -> CollectionStripKind.WORK
            },
            segments = docs.map { it.kgExtractedAt != null },
        )
    }

/**
 * 资料页资源管理器的页头插槽：解析当前路径对应的资料库文件夹，读到合集数据且已有抽取结果时显示合集条。
 * [refreshKey] 由宿主在合集设置关闭、批量抽取入队后递增，强制重读。
 */
@Composable
internal fun LibraryCollectionStrip(
    app: InfinityApplication,
    scope: FileFolderHeaderScope,
    refreshKey: Int,
    onSettings: (folderId: String, folderName: String) -> Unit,
    onExtract: (folderId: String) -> Unit,
) {
    var data by remember(scope.locationId, scope.path) { mutableStateOf<LibraryCollectionStripData?>(null) }
    val extracting = scope.items.any { it.knowledge?.stage == PendingStage.EXTRACTING_GRAPH }
    // 条目的抽取时间变化（逐篇抽完）时重读分段。
    val itemsSignature = scope.items.map { it.knowledge?.state to it.knowledge?.stage }
    val folderName = scope.path.trimEnd('/').substringAfterLast('/')
    LaunchedEffect(scope.locationId, scope.path, itemsSignature, refreshKey) {
        if (scope.path == "/") {
            data = null
            return@LaunchedEffect
        }
        data = try {
            val folderId = app.fileManagerServices.operations.stat(FileRef(scope.locationId, scope.path))
                ?.knowledge?.folderId
            folderId?.let { loadLibraryCollectionStrip(app.database, it, folderName) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            InfinityLogger.logError("[Library] 合集条读取失败", error)
            null
        }
    }
    val current = data?.takeIf { it.total > 0 && (it.extracted > 0 || extracting) } ?: return
    CollectionStripCard(
        data = current,
        extracting = extracting,
        onSettings = { onSettings(current.folderId, folderName) },
        onExtract = { onExtract(current.folderId) },
    )
}

@Composable
private fun CollectionStripCard(
    data: LibraryCollectionStripData,
    extracting: Boolean,
    onSettings: () -> Unit,
    onExtract: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val kindLabel = stringResource(
        when (data.kind) {
            CollectionStripKind.WORK -> R.string.kcs_kind_work
            CollectionStripKind.SHARED -> R.string.kcs_kind_shared
            CollectionStripKind.MERGED -> R.string.kcs_kind_merged
        },
    )
    val status = if (extracting) stringResource(R.string.kcs_status_extracting, data.extracted, data.total)
    else stringResource(R.string.kcs_status, kindLabel, data.extracted, data.total)
    Surface(
        color = scheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.XSmall)
            .testTag(LibraryTags.COLLECTION_STRIP),
    ) {
        Column(
            Modifier
                .animateContentSize(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate))
                .padding(start = InfinitySpacing.Large, end = InfinitySpacing.Small, top = InfinitySpacing.Medium, bottom = InfinitySpacing.XSmall),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                Box(
                    Modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(scheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Hub, contentDescription = null, tint = scheme.onTertiaryContainer, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(end = InfinitySpacing.Small)) {
                    Text(
                        stringResource(R.string.kcs_title, data.collectionTitle.ifBlank { kindLabel }),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            CollectionSegments(data.segments, Modifier.padding(top = InfinitySpacing.Medium, end = InfinitySpacing.Small))
            Row(
                Modifier.fillMaxWidth().padding(top = InfinitySpacing.XSmall),
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSettings, modifier = Modifier.testTag(LibraryTags.COLLECTION_STRIP_SETTINGS)) {
                    Text(stringResource(R.string.kgc_action_settings), maxLines = 1)
                }
                FilledTonalButton(
                    onClick = onExtract,
                    enabled = !extracting && data.extracted < data.total,
                    modifier = Modifier.testTag(LibraryTags.COLLECTION_STRIP_EXTRACT),
                ) {
                    Text(stringResource(R.string.kgc_action_extract), maxLines = 1)
                }
            }
        }
    }
}

/** 按篇序的分段进度；篇数过多时退为连续进度条。读屏只读状态文字，不逐段朗读。 */
@Composable
private fun CollectionSegments(segments: List<Boolean>, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(2.dp)
    if (segments.size > MAX_SEGMENTS) {
        LinearProgressIndicator(
            progress = { segments.count { it }.toFloat() / segments.size },
            modifier = modifier.fillMaxWidth().height(4.dp).clearAndSetSemantics {},
            color = scheme.primary,
            trackColor = scheme.surfaceContainerHighest,
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
        return
    }
    Row(
        modifier.fillMaxWidth().height(4.dp).clip(shape).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { done ->
            Box(Modifier.weight(1f).height(4.dp).background(if (done) scheme.primary else scheme.surfaceContainerHighest))
        }
    }
}
