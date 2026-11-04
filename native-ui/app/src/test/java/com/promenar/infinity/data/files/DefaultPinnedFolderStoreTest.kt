package com.promenar.infinity.data.files

import com.promenar.infinity.domain.files.FileRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPinnedFolderStoreTest {
    @Test fun `上限去重及消失的位置过滤`() = runBlocking {
        val persistence = FakePins()
        val locations = MutableStateFlow(setOf("kb", "session:one"))
        val store = DefaultPinnedFolderStore(persistence, locations)
        repeat(6) { assertTrue(store.pin(FileRef("kb", "/folder$it"))) }
        assertFalse(store.pin(FileRef("kb", "/folder0")))
        assertFalse(store.pin(FileRef("kb", "/extra")))
        store.unpin(FileRef("kb", "/folder0"))
        assertTrue(store.pin(FileRef("session:one", "/")))
        locations.value = setOf("kb")
        assertEquals(5, store.observePins().first().size)
        assertEquals(6, persistence.read().size)
    }

    private class FakePins : PinPersistence {
        private val value = MutableStateFlow<List<FileRef>>(emptyList())
        override fun observe() = value
        override fun read() = value.value
        override fun write(pins: List<FileRef>) { value.value = pins }
    }
}
