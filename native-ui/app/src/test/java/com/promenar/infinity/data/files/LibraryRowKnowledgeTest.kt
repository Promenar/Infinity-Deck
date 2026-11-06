package com.promenar.infinity.data.files

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import com.promenar.infinity.data.rag.KgCollectionRepository
import com.promenar.infinity.data.rag.KgDocumentSetting
import com.promenar.infinity.data.rag.KgFolderSetting
import com.promenar.infinity.domain.files.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LibraryRowKnowledgeTest {
    private lateinit var db: InfinityDatabase
    private lateinit var collections: KgCollectionRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        collections = KgCollectionRepository(db)
    }
    @After fun close() = db.close()

    private suspend fun add(id: String, parent: String?, directory: Boolean, extracted: Boolean = false) {
        db.fileEntryDao().insert(FileEntry(uuid = id, workspaceRootUuid = "root", parentUuid = parent, name = id,
            hash = "h", physicalRootPath = "/fixture", materializedPath = if (id == "root") "/" else "/$id",
            isDirectory = directory, mimeType = if (directory) null else "text/plain",
            kgExtractedAt = if (extracted) 2 else null, createdAt = 1, updatedAt = 1))
    }
    private suspend fun row(id: String): FileKnowledge {
        val snapshot = LibraryRowKnowledge.load(db, "root")
        val entry = snapshot.rows.single { it.uuid == id }
        return snapshot.enrich(entry, knowledgeOf(entry, emptyList()))
    }
    @Test fun `合集标签与文件夹子树按实际归属计数`() = runBlocking<Unit> {
        add("root", null, true); add("a", "root", true); add("b", "a", true); add("empty", "root", true)
        add("one", "a", false, true); add("two", "b", false, true); add("three", "a", false)
        collections.setFolderSetting("b", KgFolderSetting.MERGE_PARENT)
        assertThat(row("one").collectionTag).isNull()
        assertThat(row("a").folderCollection?.kind).isEqualTo(FolderCollectionKind.WORK)
        assertThat(row("a").folderCollection?.total).isEqualTo(3)
        assertThat(row("b").folderCollection?.kind).isEqualTo(FolderCollectionKind.MERGED)
        assertThat(row("empty").folderCollection).isNull()
        collections.setDocumentSetting("two", KgDocumentSetting.STANDALONE)
        assertThat(row("two").collectionTag?.kind).isEqualTo(FileCollectionTagKind.STANDALONE)
        assertThat(row("a").folderCollection?.total).isEqualTo(2)
        assertThat(row("b").folderCollection).isNull()
        collections.setDocumentSetting("three", KgDocumentSetting.SHARED)
        assertThat(row("three").collectionTag?.kind).isEqualTo(FileCollectionTagKind.SHARED)
        collections.setFolderSetting("a", KgFolderSetting.SHARED)
        assertThat(row("a").folderCollection?.kind).isEqualTo(FolderCollectionKind.SHARED)
        assertThat(row("three").collectionTag).isNull()
        add("other", "root", false)
        collections.setDocumentSetting("other", KgDocumentSetting.STANDALONE)
        collections.resolveDocumentForWrite("other")
        db.openHelper.writableDatabase.execSQL("UPDATE kg_collections SET title='另一本' WHERE id='work:other'")
        collections.setDocumentSetting("three", KgDocumentSetting.COLLECTION, "work:other")
        assertThat(row("three").collectionTag?.title).isEqualTo("另一本")
        assertThat(row("three").collectionTag?.kind).isEqualTo(FileCollectionTagKind.COLLECTION)
        db.openHelper.writableDatabase.execSQL("DELETE FROM kg_collections WHERE id='work:other'")
        assertThat(row("three").collectionTag).isNull()
    }
    @Test fun `未抽取文件夹仅在抽取任务进行时显示`() = runBlocking<Unit> {
        add("root", null, true); add("a", "root", true); add("one", "a", false)
        assertThat(row("a").folderCollection).isNull()
        db.vectorizationTaskDao().insert(VectorizationTaskEntity(id = "task", type = "document_reference", status = "extracting",
            docId = "one", workspaceRootUuid = "root", createdAt = 1, updatedAt = 1))
        assertThat(row("a").folderCollection?.total).isEqualTo(1)
        assertThat(row("a").folderCollection?.extracted).isEqualTo(0)
        assertThat(row("root").folderCollection).isNull()
    }
    @Test fun `索引失败的文档不计入文件夹进度`() = runBlocking<Unit> {
        add("root", null, true); add("a", "root", true); add("one", "a", false, true); add("scan", "a", false)
        assertThat(row("a").folderCollection?.total).isEqualTo(2)
        db.vectorizationTaskDao().insert(VectorizationTaskEntity(id = "failed", type = "document_reference", status = "failed",
            docId = "scan", workspaceRootUuid = "root", error = "没有文字层", createdAt = 1, updatedAt = 1))
        assertThat(row("a").folderCollection?.total).isEqualTo(1)
        assertThat(row("a").folderCollection?.extracted).isEqualTo(1)
    }
}
