package com.promenar.infinity.data.skills.store

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import io.ktor.utils.io.ByteChannel
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillStorePackageTest {
    @get:Rule val temp = TemporaryFolder()
    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { z -> files.forEach { (path, value) -> z.putNextEntry(ZipEntry(path)); z.write(value.toByteArray()); z.closeEntry() } } }.toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun entry(bytes: ByteArray, files: List<SkillStoreFile> = listOf(SkillStoreFile("SKILL.md", doc.toByteArray().size.toLong()))) = SkillStoreEntry("demo", "1.0.0", "demo", "", null, "", "", "https://example.com/a.zip", bytes.size.toLong(), hash(bytes), files, false, setOf(SkillChannel.PLAY), null)
    private val doc = "---\nname: demo\ndescription: 测试\n---\n正文"
    @Test fun `下载校验哈希大小并复用缓存`() = runTest {
        val bytes = zip("SKILL.md" to doc); var requests = 0
        val client = HttpClient(MockEngine { requests++; respond(bytes, HttpStatusCode.OK) })
        val store = SkillStorePackage(client, temp.root)
        val good = entry(bytes)
        store.download(good); store.download(good)
        assertThat(requests).isEqualTo(1)
        val bad = good.copy(sha256 = "0".repeat(64))
        assertThat((runCatching { store.download(bad) }.exceptionOrNull() as StoreException).failure).isEqualTo(StoreFailure.HASH_MISMATCH)
        val short = good.copy(size = good.size - 1, sha256 = "1".repeat(64))
        assertThat((runCatching { store.download(short) }.exceptionOrNull() as StoreException).failure).isEqualTo(StoreFailure.TOO_LARGE)
        assertThat(temp.root.listFiles()!!.none { it.name.endsWith(".part") }).isTrue()
        client.close()
    }
    @Test fun `HTTP状态与写盘失败分别归类`() = runTest {
        val bytes = zip("SKILL.md" to doc)
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.BadGateway) })
        val store = SkillStorePackage(client, temp.root)
        val http = runCatching { store.download(entry(bytes)) }.exceptionOrNull() as StoreException
        assertThat(http.failure).isEqualTo(StoreFailure.HTTP)
        assertThat(http.httpStatus).isEqualTo(502)
        val blocked = SkillStorePackage(client, temp.newFile())
        assertThat((runCatching { blocked.download(entry(bytes)) }.exceptionOrNull() as StoreException).failure).isEqualTo(StoreFailure.STORAGE)
        client.close()
    }
    @Test fun `取消下载清除部分文件`() = runTest {
        val started = CompletableDeferred<Unit>()
        val channel = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine { respond(channel, HttpStatusCode.OK) })
        val store = SkillStorePackage(client, temp.root)
        val bytes = zip("SKILL.md" to doc)
        val job = async { store.download(entry(bytes)) { _, _ -> started.complete(Unit) } }
        channel.writeFully(bytes, 0, 1)
        started.await(); job.cancelAndJoin()
        assertThat(temp.root.listFiles()!!.none { it.name.endsWith(".part") }).isTrue()
        client.close()
    }
    @Test fun `解包拒绝清单名称顶层目录和路径穿越`() = runTest {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }); val store = SkillStorePackage(client, temp.root)
        suspend fun check(bytes: ByteArray, entry: SkillStoreEntry, expected: StoreFailure) {
            val file = temp.newFile(); file.writeBytes(bytes)
            assertThat((runCatching { store.extract(entry, file) }.exceptionOrNull() as StoreException).failure).isEqualTo(expected)
        }
        val normal = zip("SKILL.md" to doc)
        check(normal, entry(normal).copy(files = listOf(SkillStoreFile("SKILL.md", 1))), StoreFailure.FILE_LIST_MISMATCH)
        val mismatch = zip("SKILL.md" to doc.replace("demo", "other"))
        check(mismatch, entry(mismatch, listOf(SkillStoreFile("SKILL.md", doc.replace("demo", "other").toByteArray().size.toLong()))), StoreFailure.NAME_MISMATCH)
        val wrapped = zip("demo/SKILL.md" to doc); check(wrapped, entry(wrapped), StoreFailure.FILE_LIST_MISMATCH)
        val traversal = zip("../SKILL.md" to doc); check(traversal, entry(traversal), StoreFailure.INVALID_PACKAGE)
        client.close()
    }
}
