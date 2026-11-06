package com.promenar.infinity.ui.lab.library

import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationsSource
import com.promenar.infinity.domain.files.FileManagerServices
import com.promenar.infinity.domain.files.FileOpOutcome
import com.promenar.infinity.domain.files.FileOperations
import com.promenar.infinity.domain.files.FilePreview
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.files.FileSearchHit
import com.promenar.infinity.domain.files.FileSearchQuery
import com.promenar.infinity.domain.files.PinnedFolderStore
import com.promenar.infinity.domain.library.AskEvent
import com.promenar.infinity.domain.library.AskHit
import com.promenar.infinity.domain.library.AskMode
import com.promenar.infinity.domain.library.AskScope
import com.promenar.infinity.domain.library.CitedDocument
import com.promenar.infinity.domain.library.ConversationFragment
import com.promenar.infinity.domain.library.DocumentBody
import com.promenar.infinity.domain.library.DocumentCitation
import com.promenar.infinity.domain.library.DocumentInsight
import com.promenar.infinity.domain.library.FragmentGroup
import com.promenar.infinity.domain.library.GraphEstimate
import com.promenar.infinity.domain.library.KnowledgeCitationRepository
import com.promenar.infinity.domain.library.LibraryActionResult
import com.promenar.infinity.domain.library.LibraryAskService
import com.promenar.infinity.domain.library.LibraryDocumentSummary
import com.promenar.infinity.domain.library.LibraryKnowledgeSource
import com.promenar.infinity.domain.library.LibraryOverview
import com.promenar.infinity.domain.library.PendingDocument
import com.promenar.infinity.data.rag.KgDocumentAssignment
import com.promenar.infinity.data.rag.KgDocumentCollectionInfo
import com.promenar.infinity.ui.library.LibraryDocumentCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/*
 * 资料页的假数据源：正式的 LibraryViewModel 与 LibraryAskViewModel 接上它们后，资料页按真实流程渲染。
 * 只读查询返回 [LabLibraryText] 里的虚构内容；索引、抽取、导入等写操作不执行，返回失败或空结果。
 */

/** 知识层：概览、待处理、最近、文档详情与正文、对话片段。 */
internal class LabLibraryKnowledge(private val text: LabLibraryText) : LibraryKnowledgeSource {
    override fun observeOverview(): Flow<LibraryOverview> = flowOf(text.overview)
    override fun observePending(): Flow<List<PendingDocument>> = flowOf(text.pending)
    override fun observeRecent(limit: Int): Flow<List<LibraryDocumentSummary>> = flowOf(text.recent.take(limit))
    override suspend fun retry(documentId: String): LibraryActionResult = NOT_IN_LAB
    override suspend fun rebuild(documentId: String): LibraryActionResult = NOT_IN_LAB
    override suspend fun graphEstimate(documentId: String): GraphEstimate? = null
    override suspend fun extractGraph(documentId: String, confirmed: Boolean): LibraryActionResult = NOT_IN_LAB
    override suspend fun insight(documentId: String): DocumentInsight? = text.insight(documentId)
    override suspend fun body(documentId: String, maxChars: Int): DocumentBody? = text.body(documentId)
    override suspend fun documentIdFor(ref: FileRef): String? = text.documentIdFor(ref)
    override fun observeFragmentGroups(): Flow<List<FragmentGroup>> = flowOf(text.fragmentGroups)
    override fun observeFragments(sessionId: String): Flow<List<ConversationFragment>> = flowOf(emptyList())
    override suspend fun deleteFragment(id: String) = Unit
    override suspend fun clearFragments(sessionId: String) = Unit
    override fun observeNewMemoryIds(): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun markMemoriesSeen() = Unit

    private companion object {
        val NOT_IN_LAB = LibraryActionResult.Failed("lab")
    }
}

/** 资源管理器数据：位置与固定的文件夹来自虚构数据；目录内容为空，文件操作不可用。 */
internal class LabLibraryFiles(locations: List<FileLocation>, pins: List<FileRef>) : FileManagerServices {
    override val locations: FileLocationsSource = object : FileLocationsSource {
        override fun observeLibraryLocations(): Flow<List<FileLocation>> = flowOf(locations)
        override fun observeSessionLocations(sessionId: String): Flow<List<FileLocation>> = flowOf(emptyList())
        override fun observeLinuxHome(): Flow<FileLocation?> = flowOf(null)
    }
    override val operations: FileOperations = LabFileOperations
    override val pins: PinnedFolderStore = object : PinnedFolderStore {
        override fun observePins(): Flow<List<FileRef>> = flowOf(pins)
        override suspend fun pin(ref: FileRef): Boolean = false
        override suspend fun unpin(ref: FileRef) = Unit
    }
}

private object LabFileOperations : FileOperations {
    override suspend fun list(locationId: String, path: String): List<FileItem> = emptyList()
    override suspend fun stat(ref: FileRef): FileItem? = null
    override suspend fun search(locationId: String, query: FileSearchQuery, limit: Int): List<FileSearchHit> = emptyList()
    override suspend fun createFolder(parent: FileRef, name: String): FileItem = unsupported()
    override suspend fun createTextFile(parent: FileRef, name: String, content: String): FileItem = unsupported()
    override suspend fun rename(ref: FileRef, newName: String): FileItem = unsupported()
    override suspend fun copy(sources: List<FileRef>, targetDirectory: FileRef): FileOpOutcome = unsupported()
    override suspend fun move(sources: List<FileRef>, targetDirectory: FileRef): FileOpOutcome = unsupported()
    override suspend fun delete(sources: List<FileRef>): FileOpOutcome = unsupported()
    override suspend fun compress(sources: List<FileRef>, targetDirectory: FileRef, archiveName: String): FileItem = unsupported()
    override suspend fun extract(archive: FileRef, targetDirectory: FileRef): FileItem = unsupported()
    override suspend fun importUris(uris: List<String>, targetDirectory: FileRef): FileOpOutcome = unsupported()
    override suspend fun importText(text: String, targetDirectory: FileRef, name: String): FileItem = unsupported()
    override suspend fun preview(ref: FileRef, maxTextBytes: Int): FilePreview = unsupported()
    override suspend fun shareUri(ref: FileRef): String = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("视觉实验室不执行文件操作")
}

/** 引用记录：文档页“智能体怎么用它”与概览“智能体最近用到”。 */
internal class LabLibraryCitations(private val text: LabLibraryText) : KnowledgeCitationRepository {
    override fun observeForDocument(documentId: String, limit: Int): Flow<List<DocumentCitation>> =
        flowOf(text.citationsFor(documentId).take(limit))
    override fun observeRecentDocuments(limit: Int): Flow<List<CitedDocument>> = flowOf(text.citedDocuments.take(limit))
    override fun observeTopDocuments(limit: Int): Flow<List<CitedDocument>> =
        flowOf(text.citedDocuments.sortedByDescending { it.count }.take(limit))
}

/** 问资料：输入中返回关键词命中，按搜索键依次发出检索、命中与答案。 */
internal class LabLibraryAsk(private val text: LabLibraryText) : LibraryAskService {
    override fun observeMode(): Flow<AskMode> = flowOf(AskMode.FULL)

    override suspend fun keywordSearch(query: String, scope: AskScope, limit: Int): List<AskHit> =
        text.hits().filter { it.highlightTerms.isNotEmpty() }.take(limit)

    override fun ask(query: String, scope: AskScope): Flow<AskEvent> = flow {
        emit(AskEvent.Retrieving)
        emit(AskEvent.Hits(text.hits(), AskMode.FULL))
        emit(AskEvent.AnswerDelta(text.answer))
        emit(AskEvent.AnswerDone(text.answer, text.citedHitIndexes, text.modelLabel, noAnswer = false))
    }
}

/** 文档页的所属合集与合集归属：文档都跟随顶层文件夹 starlog 这部作品。 */
internal class LabLibraryCollections(private val text: LabLibraryText) : LibraryDocumentCollections {
    override suspend fun info(documentId: String): KgDocumentCollectionInfo = text.collectionInfo(documentId)
    override suspend fun assignment(documentId: String): KgDocumentAssignment = text.collectionAssignment
}
