package com.promenar.infinity.ui.files.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.files.FileGraphState
import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileKnowledge
import com.promenar.infinity.domain.files.FilePreview
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.files.KnowledgeIndexState
import com.promenar.infinity.ui.common.InfinityConfirmDialogCard
import com.promenar.infinity.ui.common.InfinityHeaderTitle
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPaneHeaderTitle
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.files.FileBreadcrumbs
import com.promenar.infinity.ui.files.FileCollectionTagChip
import com.promenar.infinity.ui.files.FileInfoRows
import com.promenar.infinity.ui.files.FileManagerLeading
import com.promenar.infinity.ui.files.FileManagerLeadingButton
import com.promenar.infinity.ui.files.FileRow
import com.promenar.infinity.ui.files.fileMetaSegments
import com.promenar.infinity.ui.files.formatFileSize
import com.promenar.infinity.ui.files.formatFileTime
import com.promenar.infinity.ui.files.graphStatusLabel
import com.promenar.infinity.ui.files.knowledgeFailureText
import com.promenar.infinity.ui.files.knowledgeStateColor
import com.promenar.infinity.ui.files.knowledgeStateLabel
import com.promenar.infinity.ui.files.LocalFileTimeNow
import com.promenar.infinity.ui.files.state.FileCrumb
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/*
 * 文档预览与编辑入口重构的隔离高保真原型（Wave 1）：
 * 1. 取消正文顶部全宽蓝色「打开方式」按钮；可内联阅读文件从面包屑下方/标题行直接进入正文，
 *    仅不可内联预览文件（PDF/音视频/未知格式）在中间占位卡内置入「打开方式」主按钮；
 * 2. 资料库文件（有 documentId）与 Linux 主目录文本/代码文件在预览页头右上角直接提供「详情」「编辑」图标入口；
 * 3. 面包屑下按需显示紧凑「资料状态条」（仅放索引与图谱摘要，不重复路径/大小/时间），完整信息卡留在文末。
 */

private val PrototypeCardShape = RoundedCornerShape(20.dp)
private val PrototypeStatusShape = RoundedCornerShape(14.dp)

@Composable
private fun PrototypeFrame(mode: InfinityThemeMode, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        CompositionLocalProvider(LocalFileTimeNow provides FileManagerSamples.NOW) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

@Composable
private fun PrototypePreviewCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = PrototypeCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(InfinitySpacing.Large),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            content = content,
        )
    }
}

@Composable
private fun PrototypeHeaderActions(
    showDetails: Boolean,
    showEdit: Boolean,
    editContentDescription: String,
    itemName: String,
) {
    if (showDetails) {
        IconButton(onClick = {}) {
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.library_doc_details))
        }
    }
    if (showEdit) {
        IconButton(onClick = {}) {
            Icon(Icons.Rounded.Edit, contentDescription = editContentDescription)
        }
    }
    IconButton(onClick = {}) {
        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.fm_item_actions, itemName))
    }
}

/** 紧凑资料状态条：不重复显示路径/大小/修改时间，只表达索引状态、分块数、图谱与失败原因。 */
@Composable
private fun PrototypeKnowledgeStatusStrip(knowledge: FileKnowledge, modifier: Modifier = Modifier) {
    val stateColor = knowledgeStateColor(knowledge.state)
    val stateLabel = stringResource(knowledgeStateLabel(knowledge.state))
    val chunksLabel = knowledge.chunkCount?.let { pluralStringResource(R.plurals.fm_knowledge_chunks, it, it) }
    val graphLabel = knowledge.graph?.let { graphStatusLabel(it)?.let { res -> stringResource(res) } }
    val graphExtracted = knowledge.graph == FileGraphState.EXTRACTED
    val container = if (knowledge.state == KnowledgeIndexState.FAILED) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val border = if (knowledge.state == KnowledgeIndexState.FAILED) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }
    Surface(
        shape = PrototypeStatusShape,
        color = container,
        modifier = modifier.fillMaxWidth().border(0.5.dp, border, PrototypeStatusShape),
    ) {
        Column(
            Modifier.padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            ) {
                Box(Modifier.size(8.dp).background(stateColor, CircleShape))
                InfinityMetaLine(
                    segments = listOfNotNull(stateLabel, chunksLabel, graphLabel.takeUnless { graphExtracted }),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (graphExtracted) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Hub,
                            contentDescription = graphLabel,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(15.dp),
                        )
                        if (graphLabel != null) {
                            Text(
                                text = graphLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                knowledge.collectionTag?.let { FileCollectionTagChip(it) }
            }
            knowledge.failureReason?.let { reason ->
                Text(
                    text = knowledgeFailureText(reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PrototypePreviewBody(
    item: FileItem,
    preview: FilePreview,
    openWithLabel: String,
    pdfTitle: String,
) {
    when (preview) {
        is FilePreview.Text -> PrototypePreviewCard {
            if (preview.markdown) {
                MarkdownText(markdown = preview.content)
            } else {
                Text(
                    text = preview.content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        is FilePreview.Pdf -> PrototypePreviewCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.Large),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                Icon(
                    Icons.Rounded.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = pdfTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                InfinityPillButton(
                    text = openWithLabel,
                    onClick = {},
                    tone = InfinityPillTone.PRIMARY,
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                )
            }
        }
        else -> Unit
    }
}

@Composable
private fun PrototypeBottomInfoCard(item: FileItem, locationText: String) {
    val rows = mutableListOf(
        stringResource(R.string.fm_info_location) to locationText,
        stringResource(R.string.fm_info_size) to formatFileSize(item.sizeBytes),
        stringResource(R.string.fm_info_modified) to formatFileTime(item.lastModified),
    )
    val knowledge = item.knowledge
    if (knowledge != null) {
        val status = stringResource(knowledgeStateLabel(knowledge.state))
        val chunks = knowledge.chunkCount?.let { pluralStringResource(R.plurals.fm_knowledge_chunks, it, it) }
        rows += stringResource(R.string.fm_info_knowledge) to listOfNotNull(
            status,
            chunks,
            knowledge.failureReason?.let { knowledgeFailureText(it) },
        ).joinToString("\n")
    }
    PrototypePreviewCard {
        Text(
            text = stringResource(R.string.fm_info_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FileInfoRows(rows)
    }
}

@Composable
private fun PrototypeCompactPreviewScreen(
    item: FileItem,
    preview: FilePreview,
    crumbs: List<FileCrumb>,
    innerScreen: Boolean,
    showDetails: Boolean,
    showEdit: Boolean,
    editLabel: String,
    locationPath: String,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = InfinitySpacing.XSmall, vertical = InfinitySpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (innerScreen) {
                FileManagerLeadingButton(FileManagerLeading.Expand(onExpand = {}))
            } else {
                IconButton(onClick = {}) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.fm_preview_back))
                }
            }
            if (innerScreen) {
                InfinityPaneHeaderTitle(
                    title = item.name,
                    modifier = Modifier.weight(1f).padding(horizontal = InfinitySpacing.Small),
                    meta = { InfinityMetaLine(fileMetaSegments(item), maxLines = 1) },
                )
            } else {
                Column(Modifier.weight(1f).padding(horizontal = InfinitySpacing.Small)) {
                    InfinityHeaderTitle(
                        text = item.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    InfinityHeaderTitle(
                        text = locationPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        alignEnd = true,
                        heading = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            PrototypeHeaderActions(
                showDetails = showDetails,
                showEdit = showEdit,
                editContentDescription = editLabel,
                itemName = item.name,
            )
        }
        if (innerScreen) {
            FileBreadcrumbs(
                crumbs = crumbs,
                onGo = {},
                modifier = Modifier.fillMaxWidth().padding(start = InfinitySpacing.Small, end = InfinitySpacing.XSmall),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = InfinitySpacing.Large)
                .padding(top = InfinitySpacing.XSmall, bottom = InfinitySpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            if (!innerScreen) {
                InfinityMetaLine(
                    segments = fileMetaSegments(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.knowledge?.let { PrototypeKnowledgeStatusStrip(it) }
            val pdfPages = (preview as? FilePreview.Pdf)?.pageCount
            val pdfTitle = pdfPages?.let { pluralStringResource(R.plurals.fm_pdf_pages, it, it) }
                ?: stringResource(R.string.fm_preview_pdf)
            PrototypePreviewBody(
                item = item,
                preview = preview,
                openWithLabel = stringResource(R.string.fm_open_with),
                pdfTitle = pdfTitle,
            )
            PrototypeBottomInfoCard(item, locationPath)
        }
    }
}

private fun novelSampleItem(): FileItem = FileItem(
    ref = FileRef(FileManagerSamples.LIBRARY_ID, "/帝国暗面/全书大纲及规划初稿.txt"),
    name = "全书大纲及规划初稿.txt",
    isDirectory = false,
    sizeBytes = 22_323L,
    lastModified = FileManagerSamples.NOW - 2L * 3600_000L,
    mimeType = "text/plain",
    knowledge = FileKnowledge(
        state = KnowledgeIndexState.INDEXED,
        documentId = "doc-empire-outline",
        chunkCount = 14,
        graph = FileGraphState.EXTRACTED,
    ),
)

private const val NovelOutlineSampleBody = """《帝国暗面》第一卷：南天门的火种（最终定稿）
卷首语：宇宙不是由神迹构成的，而是由被观测到的规律构成的。
【核心主旨】
描写人类帝国在绝望的“暗夜时刻”，如何通过一个个体（何瑞斯）的偶然接触，重新接续上失落万年的理性文明（地球）。这一卷不仅是何瑞斯的潜伏史，更是地球逻辑作为降维打击，在帝国的神学废墟上进行的第一次实战演习。

完整目录与剧情分段
第一篇：南天门下的迷途者（Ch.1 - Ch.25）
* 核心剧情：物理接触、认知崩塌、火种协议。
* 分章重点：
  o Ch.1 - 10：【追逐晨星】详写何瑞斯科考队追踪古老信号，发现物理规律异常的“隐匿区”。
  o Ch.11 - 18：【原初地球】科考船被强行捕获，踏上洁净、理性的地球外交旗舰。
  o Ch.19 - 25：【李哲的交谈】李哲司长揭示“火种-7号”真相，何瑞斯带走“蝉翼”通讯装置。"""

/** 1. 内屏单列预览：取消顶部全宽蓝色「打开方式」，右上角补齐详情与编辑入口，面包屑下放紧凑资料状态条。 */
@Preview(name = "1. 内屏文档预览(已移除打开方式占位+详情编辑入口)", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Composable
fun filePreviewCompactIndexedDarkPreview() = PrototypeFrame(InfinityThemeMode.DARK) {
    val item = remember { novelSampleItem() }
    val crumbs = remember {
        listOf(
            FileCrumb("存储空间", "/"),
            FileCrumb("帝国暗面", "/帝国暗面"),
            FileCrumb("全书大纲及规划初稿.txt", item.ref.path),
        )
    }
    PrototypeCompactPreviewScreen(
        item = item,
        preview = FilePreview.Text(NovelOutlineSampleBody, truncated = false, markdown = false),
        crumbs = crumbs,
        innerScreen = true,
        showDetails = true,
        showEdit = true,
        editLabel = stringResource(R.string.library_doc_edit),
        locationPath = "存储空间 › 帝国暗面",
    )
}

/** 2. 外屏单列浅色大字：不可内联预览文件（PDF）只在中央占位卡放主色「打开方式」，并保留详情入口（无编辑图标）。 */
@Preview(name = "2. 外屏PDF预览 浅英大字(占位卡内打开方式)", widthDp = 360, heightDp = 860, locale = "en", fontScale = 1.3f)
@Composable
fun filePreviewPdfLightLargePreview() = PrototypeFrame(InfinityThemeMode.LIGHT) {
    val item = remember {
        FileItem(
            ref = FileRef(FileManagerSamples.LIBRARY_ID, "/Release checklist.pdf"),
            name = "Release checklist.pdf",
            isDirectory = false,
            sizeBytes = 2L * 1024 * 1024 + 120 * 1024,
            lastModified = FileManagerSamples.NOW - 30L * 3600_000L,
            mimeType = "application/pdf",
            knowledge = FileKnowledge(
                state = KnowledgeIndexState.FAILED,
                documentId = "doc-pdf-1",
                failureReason = "Text inside embedded tables could not be extracted",
            ),
        )
    }
    PrototypeCompactPreviewScreen(
        item = item,
        preview = FilePreview.Pdf("content://example.invalid/checklist", pageCount = 12),
        crumbs = emptyList(),
        innerScreen = false,
        showDetails = true,
        showEdit = false,
        editLabel = stringResource(R.string.library_doc_edit),
        locationPath = "Storage",
    )
}

/** 3. 宽屏双栏：列表 + 右侧预览，页头容纳标题 + 详情 + 编辑 + 更多而不挤压长文件名。 */
@Preview(name = "3. 双栏存储空间预览 深中742", widthDp = 742, heightDp = 720, locale = "zh-rCN")
@Composable
fun filePreviewDualIndexedDarkPreview() = PrototypeFrame(InfinityThemeMode.DARK) {
    val controller = remember { FileManagerSamples.controller(english = false) }
    val state by controller.state.collectAsState()
    val previewItem = remember {
        FileItem(
            ref = FileRef(FileManagerSamples.LIBRARY_ID, FileManagerSamples.markdownPath(false)),
            name = "离线模式架构决策与长标题验证文档.md",
            isDirectory = false,
            sizeBytes = 12 * 1024L + 410L,
            lastModified = FileManagerSamples.NOW - 2L * 3600_000L,
            mimeType = "text/markdown",
            knowledge = FileKnowledge(
                state = KnowledgeIndexState.INDEXED,
                documentId = "doc-1",
                chunkCount = 14,
                graph = FileGraphState.EXTRACTED,
            ),
        )
    }
    val previewText = """# 离线模式架构决策

状态：已采纳

## 决策
- 写操作先进入本地离线队列。
- 联网后按入队顺序重放。
- 同步冲突以服务端版本为准，本地副本另存供比对。"""
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = InfinitySpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileManagerLeadingButton(FileManagerLeading.Restore(onRestore = {}))
            InfinityHeaderTitle(
                text = "存储空间",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(horizontal = InfinitySpacing.Small),
            )
            IconButton(onClick = {}) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.fm_more))
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.width(300.dp).fillMaxHeight()) {
                FileBreadcrumbs(crumbs = listOf(FileCrumb("存储空间", "/")), onGo = {}, modifier = Modifier.padding(horizontal = InfinitySpacing.Small))
                state.items.take(5).forEach { entry ->
                    FileRow(
                        item = entry,
                        highlighted = entry.ref.path == FileManagerSamples.markdownPath(false),
                        selecting = false,
                        checked = false,
                        onClick = {},
                        onToggleSelection = {},
                    )
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(start = InfinitySpacing.Large, end = InfinitySpacing.XSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = InfinitySpacing.Small)) {
                        InfinityHeaderTitle(
                            text = previewItem.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        InfinityMetaLine(
                            segments = fileMetaSegments(previewItem),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PrototypeHeaderActions(
                        showDetails = true,
                        showEdit = true,
                        editContentDescription = stringResource(R.string.library_doc_edit),
                        itemName = previewItem.name,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = InfinitySpacing.Large)
                        .padding(bottom = InfinitySpacing.XLarge),
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                ) {
                    previewItem.knowledge?.let { PrototypeKnowledgeStatusStrip(it) }
                    PrototypePreviewBody(
                        item = previewItem,
                        preview = FilePreview.Text(previewText, truncated = false, markdown = true),
                        openWithLabel = stringResource(R.string.fm_open_with),
                        pdfTitle = "",
                    )
                }
            }
        }
    }
}

/** 4. 已索引文档编辑保护确认弹层：复用 InfinityConfirmDialogCard 样式。 */
@Preview(name = "4. 已索引文档编辑保护确认弹层", widthDp = 411, heightDp = 520, locale = "zh-rCN")
@Composable
fun filePreviewEditConfirmDarkPreview() = PrototypeFrame(InfinityThemeMode.DARK) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.56f)).padding(InfinitySpacing.Large),
        contentAlignment = Alignment.Center,
    ) {
        InfinityConfirmDialogCard(
            title = "编辑已索引文档",
            message = "「全书大纲及规划初稿.txt」已纳入资料索引。保存修改后将自动排队重建向量索引；若已抽取知识图谱，需在文档详情页重新抽取以保持一致。",
            confirmText = "继续编辑",
            cancelText = stringResource(R.string.common_btn_cancel),
            onConfirm = {},
            onCancel = {},
            icon = Icons.Rounded.Edit,
        )
    }
}

/** 5. 资料 › Linux 主目录文件预览：页头直接接入「详情与编辑」入口，同样去掉顶部大号「打开方式」。 */
@Preview(name = "5. 资料Linux主目录文件预览(直接编辑)", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Composable
fun filePreviewLinuxDarkPreview() = PrototypeFrame(InfinityThemeMode.DARK) {
    val item = remember {
        FileItem(
            ref = FileRef(FileManagerSamples.LINUX_ID, "/starlog/build.sh"),
            name = "build.sh",
            isDirectory = false,
            sizeBytes = 612L,
            lastModified = FileManagerSamples.NOW - 41L * 3600_000L,
            mimeType = "application/x-sh",
        )
    }
    val scriptPreview = """#!/bin/sh
set -eu
cargo build --release --target aarch64-unknown-linux-musl
ls -lh target/aarch64-unknown-linux-musl/release/starlog"""
    val crumbs = remember {
        listOf(
            FileCrumb("Linux 主目录", "/"),
            FileCrumb("starlog", "/starlog"),
            FileCrumb("build.sh", "/starlog/build.sh"),
        )
    }
    PrototypeCompactPreviewScreen(
        item = item,
        preview = FilePreview.Text(scriptPreview, truncated = false, markdown = false),
        crumbs = crumbs,
        innerScreen = true,
        showDetails = false,
        showEdit = true,
        editLabel = stringResource(R.string.linux_file_host_details),
        locationPath = "Linux 主目录 › starlog",
    )
}
