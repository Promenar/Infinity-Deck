package com.promenar.infinity.data.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemotePreviewCacheTest {
    @get:Rule val directory = TemporaryFolder()

    private fun key(name: String) = RemotePreviewKey("host", "/$name", 4, 1)

    @Test fun 淘汰最早访问条目并从索引恢复() = runBlocking {
        val root = directory.newFolder()
        val cache = RemotePreviewCache(root, 8)
        for (name in listOf("a", "b")) cache.getOrFetch(key(name)) { it.write(byteArrayOf(1, 2, 3, 4)) }
        assertNotNull(cache.lookup(key("a")))
        cache.getOrFetch(key("c")) { it.write(byteArrayOf(1, 2, 3, 4)) }
        assertNull(cache.lookup(key("b")))
        assertEquals(RemotePreviewCacheStats(8, 8, 2), cache.stats.value)
        cache.close()
        val reopened = RemotePreviewCache(root, 8)
        assertNotNull(reopened.lookup(key("a")))
        assertNotNull(reopened.lookup(key("c")))
        reopened.close()
    }

    @Test fun 清理后旧任务无法回写() = runBlocking {
        val cache = RemotePreviewCache(directory.newFolder(), 8)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            cache.getOrFetch(key("a")) { output ->
                started.complete(Unit)
                release.await()
                output.write(byteArrayOf(1, 2, 3, 4))
            }
        }
        started.await()
        cache.clear()
        val newEntry = cache.getOrFetch(key("a")) { it.write(byteArrayOf(9, 2, 3, 4)) }
        release.complete(Unit)
        assertNull(first.await())
        assertArrayEquals(byteArrayOf(9, 2, 3, 4), newEntry!!.readBytes())
        assertEquals(RemotePreviewCacheStats(4, 8, 1), cache.stats.value)
        cache.close()
        Unit
    }

    @Test fun 同一键的多个调用只启动一次拉取() = runBlocking<Unit> {
        RemotePreviewCache(directory.newFolder(), 8).use { cache ->
            val pulls = java.util.concurrent.atomic.AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            suspend fun fetch() = cache.getOrFetch(key("a")) {
                pulls.incrementAndGet()
                started.complete(Unit)
                release.await()
                it.write(byteArrayOf(1, 2, 3, 4))
            }
            val first = async { fetch() }
            started.await()
            val waiting = List(6) { async { fetch() } }
            release.complete(Unit)
            val file = first.await()
            waiting.forEach { assertEquals(file, it.await()) }
            assertEquals(1, pulls.get())
        }
    }

    @Test fun 显式校验可以替换同尺寸同时间旧条目且遵守清理代际() = runBlocking<Unit> {
        val root = directory.newFolder()
        val part = directory.newFile().apply { writeBytes(byteArrayOf(9, 2, 3, 4)) }
        val hash = sha256File(part)
        RemotePreviewCache(root, 8).use { cache ->
            cache.getOrFetch(key("a")) { it.write(byteArrayOf(1, 2, 3, 4)) }
            val epoch = cache.generationToken()
            assertEquals(false, cache.publishVerified(key("a"), part, hash, epoch))
            assertArrayEquals(part.readBytes(), cache.lookup(key("a"))!!.readBytes())
            assertEquals(true, cache.publishVerified(key("a"), part, hash, epoch))
            cache.clear()
            assertNull(cache.publishVerified(key("a"), part, hash, epoch))
            assertNull(cache.lookup(key("a")))
        }
    }

    @Test fun 自动预览边界包含八MiB且超限按命中处理() {
        assertTrue(remotePreviewCanRead(REMOTE_AUTO_PREVIEW_MAX_BYTES, false))
        assertFalse(remotePreviewCanRead(REMOTE_AUTO_PREVIEW_MAX_BYTES + 1, false))
        assertTrue(remotePreviewCanRead(REMOTE_AUTO_PREVIEW_MAX_BYTES + 1, true))
    }
}
