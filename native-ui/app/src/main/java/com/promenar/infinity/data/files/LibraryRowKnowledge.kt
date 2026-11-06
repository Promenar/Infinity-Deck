package com.promenar.infinity.data.files

import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import com.promenar.infinity.data.library.graphStateFor
import com.promenar.infinity.data.rag.DocumentReferenceExtractor
import com.promenar.infinity.data.rag.VectorizationTask
import com.promenar.infinity.domain.files.*
import kotlinx.coroutines.flow.first

/** 一次读取工作区快照，按父子关系在内存中汇总，避免逐条查询和递归事务。 */
internal class LibraryRowKnowledge private constructor(
    val rows: List<FileEntry>,
    private val settings: Map<String, String>,
    private val collections: Map<String, Pair<String, String>>,
    private val tasks: List<VectorizationTaskEntity>,
    private val root: String,
) {
    private val byId = rows.associateBy { it.uuid }
    private val tasksByDoc = tasks.groupBy { it.docId }
    fun tasksFor(id: String): List<VectorizationTaskEntity> = tasksByDoc[id].orEmpty()
    private val folders = rows.filter { it.isDirectory && it.uuid != root }.associate { it.uuid to folderDescriptor(it) }
    private data class Descriptor(val id: String, val rootId: String?, val title: String)
    private data class Count(var total: Int = 0, var extracted: Int = 0, var extracting: Boolean = false)
    private val counts: Map<String, Count> = buildMap {
        for (file in rows) {
            if (file.isDirectory || file.mimeType !in DocumentReferenceExtractor.SUPPORTED_MIME_TYPES) continue
            val actual = documentDescriptor(file).id
            val extracting = tasksByDoc[file.uuid].orEmpty().any { it.type in setOf("document", "document_reference") && it.status == "extracting" }
            var parent = file.parentUuid
            val seen = mutableSetOf<String>()
            while (parent != null && parent != root && seen.add(parent)) {
                val folder = byId[parent]?.takeIf { it.isDirectory } ?: break
                if (folders[parent]?.id == actual) {
                    val count = getOrPut(parent) { Count() }
                    count.total++
                    if (file.kgExtractedAt != null) count.extracted++
                    if (extracting) count.extracting = true
                }
                parent = folder.parentUuid
            }
        }
    }

    private fun folderDescriptor(start: FileEntry): Descriptor {
        var folder = start
        val seen = mutableSetOf<String>()
        while (settings[folder.uuid] == "MERGE_PARENT" && seen.add(folder.uuid)) {
            val parent = folder.parentUuid?.let(byId::get)?.takeIf { it.isDirectory && it.uuid != root } ?: break
            folder = parent
        }
        return if (settings[folder.uuid] == "SHARED") Descriptor("shared", null, "通用知识")
        else Descriptor("work:${folder.uuid}", folder.uuid, folder.name)
    }

    private fun folderDocumentDescriptor(file: FileEntry): Descriptor = file.parentUuid?.let(folders::get)
        ?: Descriptor("work:${file.uuid}", file.uuid, file.name)

    private fun documentDescriptor(file: FileEntry): Descriptor {
        val setting = settings[file.uuid]
        return when {
            setting == "DOC_STANDALONE" -> Descriptor("work:${file.uuid}", file.uuid, file.name)
            setting == "DOC_SHARED" -> Descriptor("shared", null, "通用知识")
            setting?.startsWith("DOC_COLLECTION:work:") == true -> {
                val id = setting.removePrefix("DOC_COLLECTION:")
                val target = collections[id]
                if (target?.first == "work") Descriptor(id, null, target.second) else folderDocumentDescriptor(file)
            }
            else -> folderDocumentDescriptor(file)
        }
    }

    fun enrich(row: FileEntry, knowledge: FileKnowledge): FileKnowledge {
        if (row.uuid == root) return knowledge
        if (row.isDirectory) {
            val descriptor = folders[row.uuid] ?: return knowledge
            val count = counts[row.uuid] ?: return knowledge
            if (count.total == 0 || count.extracted == 0 && !count.extracting) return knowledge
            val kind = when {
                descriptor.id == "shared" -> FolderCollectionKind.SHARED
                descriptor.rootId != row.uuid -> FolderCollectionKind.MERGED
                else -> FolderCollectionKind.WORK
            }
            val title = collections[descriptor.id]?.second ?: descriptor.title
            return knowledge.copy(folderCollection = FolderCollectionSummary(descriptor.id, kind, title, count.extracted, count.total))
        }
        val latest = tasksByDoc[row.uuid].orEmpty().filter { it.type in setOf("document", "document_reference") }
            .maxWithOrNull(compareBy<VectorizationTaskEntity> { it.targetEpoch }.thenBy { it.updatedAt }.thenBy { it.createdAt })
        val graph = if (row.mimeType in DocumentReferenceExtractor.SUPPORTED_MIME_TYPES)
            graphStateFor(row.kgExtractedAt, row.updatedAt, latest?.error) else null
        val actual = documentDescriptor(row)
        val folder = folderDocumentDescriptor(row)
        val tag = if (actual.id == folder.id) null else when (settings[row.uuid]) {
            "DOC_STANDALONE" -> FileCollectionTag(actual.id, FileCollectionTagKind.STANDALONE, actual.title)
            "DOC_SHARED" -> FileCollectionTag(actual.id, FileCollectionTagKind.SHARED, actual.title)
            else -> FileCollectionTag(actual.id, FileCollectionTagKind.COLLECTION, actual.title)
        }
        return knowledge.copy(graph = graph, collectionTag = tag)
    }

    companion object {
        suspend fun load(database: InfinityDatabase, root: String, live: List<VectorizationTask> = emptyList()): LibraryRowKnowledge {
            val rows = database.fileEntryDao().getSubtree(root, "/", false)
            val settings = database.query(SimpleSQLiteQuery(
                "SELECT s.folder_id,s.mode FROM kg_folder_settings s JOIN workspace_files f ON f.uuid=s.folder_id WHERE f.workspace_root_uuid=? AND f.in_recycle_bin=0", arrayOf(root)
            )).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
            val collections = database.query(SimpleSQLiteQuery("SELECT id,kind,title FROM kg_collections")).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1) to c.getString(2)) }
            }
            val stored = database.vectorizationTaskDao().observeLibraryTasks(root).first()
            val liveRows = live.filter { it.workspaceRootUuid == root && it.type in setOf("document", "document_reference") }.map {
                VectorizationTaskEntity(id = it.id, type = it.type, status = it.status, docId = it.docId,
                    workspaceRootUuid = it.workspaceRootUuid, lastChunkIndex = it.lastChunkIndex, totalChunks = it.totalChunks,
                    error = it.error, targetEpoch = it.targetEpoch, createdAt = it.createdAt, updatedAt = it.updatedAt)
            }
            val tasks = (stored + liveRows).associateBy { it.id }.values.toList()
            return LibraryRowKnowledge(rows, settings, collections, tasks, root)
        }
    }
}
