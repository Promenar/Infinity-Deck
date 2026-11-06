package com.promenar.infinity.ui.library

import com.promenar.infinity.data.rag.KgCollectionSummary
import com.promenar.infinity.data.rag.KgDocumentAssignment
import com.promenar.infinity.data.rag.KgDocumentSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentCollectionAssignmentTest {
    private fun assignment(mode: KgDocumentSetting, target: String? = null) = KgDocumentAssignment(mode, target, "work:folder", "产品调研")
    private fun collection(id: String) = KgCollectionSummary(id, "work", id, null, 1, 0, 0)

    @Test fun `多篇方式一致时预选该方式，不一致时不预选`() {
        assertEquals(KgDocumentSetting.SHARED to null, commonAssignment(listOf(assignment(KgDocumentSetting.SHARED), assignment(KgDocumentSetting.SHARED))))
        assertNull(commonAssignment(listOf(assignment(KgDocumentSetting.SHARED), assignment(KgDocumentSetting.FOLLOW_FOLDER))))
        assertNull(commonAssignment(emptyList()))
    }

    @Test fun `加入其它合集时目标也要一致；非合集方式忽略残留目标`() {
        assertEquals(KgDocumentSetting.COLLECTION to "work:a",
            commonAssignment(listOf(assignment(KgDocumentSetting.COLLECTION, "work:a"), assignment(KgDocumentSetting.COLLECTION, "work:a"))))
        assertNull(commonAssignment(listOf(assignment(KgDocumentSetting.COLLECTION, "work:a"), assignment(KgDocumentSetting.COLLECTION, "work:b"))))
        assertEquals(KgDocumentSetting.FOLLOW_FOLDER to null,
            commonAssignment(listOf(assignment(KgDocumentSetting.FOLLOW_FOLDER, "work:a"), assignment(KgDocumentSetting.FOLLOW_FOLDER))))
    }

    @Test fun `可加入的合集排除所选文档自己的独立合集`() {
        val all = listOf(collection("work:first"), collection("work:second"), collection("work:novel"))
        assertEquals(listOf("work:novel"), assignableFor(all, listOf("first", "second")).map { it.id })
    }
}
