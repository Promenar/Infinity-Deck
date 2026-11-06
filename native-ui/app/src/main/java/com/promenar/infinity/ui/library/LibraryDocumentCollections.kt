package com.promenar.infinity.ui.library

import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.rag.KgCollectionRepository
import com.promenar.infinity.data.rag.KgDocumentAssignment
import com.promenar.infinity.data.rag.KgDocumentCollectionInfo

/** 文档页“所属合集”与“合集归属”两行的数据来源。 */
internal interface LibraryDocumentCollections {
    /**
     * 为假时按钮与两行照常显示，点按不打开合集设置、合集归属与重新抽取：
     * 这三处直接写本机图谱库，视觉实验室的假文档不能进入。
     */
    val editable: Boolean get() = true

    suspend fun info(documentId: String): KgDocumentCollectionInfo
    suspend fun assignment(documentId: String): KgDocumentAssignment
}

/** 正式资料页：按当前文件与文件夹设置从图谱合集表读取。 */
internal class DatabaseDocumentCollections(database: InfinityDatabase) : LibraryDocumentCollections {
    private val repository = KgCollectionRepository(database)
    override suspend fun info(documentId: String): KgDocumentCollectionInfo = repository.documentInfo(documentId)
    override suspend fun assignment(documentId: String): KgDocumentAssignment = repository.readDocumentSetting(documentId)
}
