package com.promenar.infinity.data.library.ask

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.memory.LongTermMemory
import com.promenar.infinity.data.memory.LongTermMemoryKind
import kotlinx.coroutines.runBlocking
import org.junit.Test

class MemoryEmbeddingCacheTest {
    private fun memory(content: String) = LongTermMemory("id", LongTermMemoryKind.USER, "标题", content, null, null, null, 1, 2)

    @Test fun cacheInvalidatesOnContentOrModelChange() = runBlocking {
        val store = mutableMapOf<String, MemoryEmbeddingEntity>()
        var calls = 0
        suspend fun lookup(memory: LongTermMemory, model: String) = findMemoryVectors(
            listOf(memory), model, { model }, { ids -> ids.mapNotNull(store::get) },
            { rows -> rows.forEach { store[it.memoryId] = it } },
            { texts -> calls++; texts.map { floatArrayOf(1f, 0f) } },
        )
        assertThat(lookup(memory("甲"), "model-a")).hasSize(1)
        assertThat(lookup(memory("甲"), "model-a")).hasSize(1)
        assertThat(calls).isEqualTo(1)
        lookup(memory("乙"), "model-a")
        lookup(memory("乙"), "model-b")
        assertThat(calls).isEqualTo(3)
    }

    @Test fun failedEmbeddingFallsBackToKeyword() = runBlocking {
        val values = findMemoryVectors(listOf(memory("甲")), "model-a", { "model-a" },
            { emptyList() }, { error("不应写缓存") }, { error("模拟嵌入失败") })
        assertThat(values).isEmpty()
    }

    @Test fun modelChangeDuringBatchDoesNotStoreVectors() = runBlocking {
        var current = "model-a"
        var saved = false
        val values = findMemoryVectors(listOf(memory("甲")), "model-a", { current },
            { emptyList() }, { saved = true }, { current = "model-b"; listOf(floatArrayOf(1f, 0f)) })
        assertThat(saved).isFalse()
        assertThat(values).isEmpty()
    }
}
