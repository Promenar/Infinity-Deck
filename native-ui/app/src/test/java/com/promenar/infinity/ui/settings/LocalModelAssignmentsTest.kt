package com.promenar.infinity.ui.settings

import com.promenar.infinity.data.local.inference.SlotType
import com.promenar.infinity.data.local.inference.StoredModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalModelAssignmentsTest {
    @Test
    fun `按文件元数据提供可分配的用途`() {
        val model = StoredModel("example", "model.gguf", "/models/model.gguf", 0, "GGUF", 0)
        for ((pooling, slot) in mapOf(null to SlotType.MAIN, 0 to SlotType.MAIN,
            1 to SlotType.EMBEDDING, 2 to SlotType.EMBEDDING, 3 to SlotType.EMBEDDING, 4 to SlotType.RERANK)) {
            assertEquals(listOf(slot), SlotType.entries.filter { model.copy(poolingType = pooling).supportsAssignment(it) })
        }
        assertEquals(emptyList<SlotType>(), SlotType.entries.filter { model.copy(format = "Unknown").supportsAssignment(it) })
    }
}
