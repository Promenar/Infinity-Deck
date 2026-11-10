package com.promenar.infinity.data.document.external

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.document.DocumentTextDecoding
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExternalDocumentStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun input(name: String?, bytes: ByteArray, declared: Long? = bytes.size.toLong()) =
        ExternalDocumentInput(name, declared) { ByteArrayInputStream(bytes) }

    @Test
    fun `暂存副本并读出正文与哈希`() = runTest {
        val root = temporary.newFolder("staging")
        val store = ExternalDocumentStore(root, io = StandardTestDispatcher(testScheduler))
        val bytes = "# 周报\n\n- 完成".toByteArray()

        val ready = store.stage { input("周报.md", bytes) } as ExternalDocumentResult.Ready

        val document = ready.document
        assertThat(document.displayName).isEqualTo("周报.md")
        assertThat(document.text).isEqualTo("# 周报\n\n- 完成")
        assertThat(document.sizeBytes).isEqualTo(bytes.size.toLong())
        assertThat(document.sha256).isEqualTo(DocumentTextDecoding.sha256(bytes))
        assertThat(document.file.readBytes()).isEqualTo(bytes)
        assertThat(document.file.parentFile!!.parentFile).isEqualTo(root)
        assertThat(ExternalDocumentStore.isValidId(document.id)).isTrue()
    }

    @Test
    fun `识别 BOM 编码`() = runTest {
        val store = ExternalDocumentStore(temporary.newFolder(), io = StandardTestDispatcher(testScheduler))
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "标题".toByteArray(Charsets.UTF_16LE)
        val ready = store.stage { input("a.md", bytes) } as ExternalDocumentResult.Ready
        assertThat(ready.document.text).isEqualTo("标题")
    }

    @Test
    fun `空文件 二进制与非法编码被拒绝且不留暂存目录`() = runTest {
        val root = temporary.newFolder()
        val store = ExternalDocumentStore(root, io = StandardTestDispatcher(testScheduler))

        assertThat(store.stage { input("a.md", ByteArray(0)) }).isEqualTo(ExternalDocumentResult.Failed(ExternalDocumentFailure.Empty))
        assertThat(store.stage { input("a.md", ByteArray(0), declared = null) })
            .isEqualTo(ExternalDocumentResult.Failed(ExternalDocumentFailure.Empty))
        assertThat(store.stage { input("a.md", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)) })
            .isEqualTo(ExternalDocumentResult.Failed(ExternalDocumentFailure.NotText))
        assertThat(store.stage { input("a.md", byteArrayOf(0xC3.toByte(), 0x28)) })
            .isEqualTo(ExternalDocumentResult.Failed(ExternalDocumentFailure.NotText))
        assertThat(root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `声明大小或实际读取超过上限时拒绝`() = runTest {
        val root = temporary.newFolder()
        val store = ExternalDocumentStore(root, maxBytes = 16, renderLimitBytes = 8, io = StandardTestDispatcher(testScheduler))

        assertThat(store.stage { input("a.md", ByteArray(4) { 'a'.code.toByte() }, declared = 17) })
            .isEqualTo(ExternalDocumentResult.Failed(ExternalDocumentFailure.TooLarge))
        // 来源少报大小时，按实际读取量截断判断。
        assertThat(store.stage { input("a.md", ByteArray(17) { 'a'.code.toByte() }, declared = 4) })
            .isEqualTo(ExternalDocumentResult.Failed(ExternalDocumentFailure.TooLarge))
        assertThat(root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `超过显示上限时不读入正文 仍给出哈希`() = runTest {
        val store = ExternalDocumentStore(temporary.newFolder(), maxBytes = 64, renderLimitBytes = 8, io = StandardTestDispatcher(testScheduler))
        val bytes = "# 很长的文档\n正文正文".toByteArray()

        val ready = store.stage { input("big.md", bytes) } as ExternalDocumentResult.Ready

        assertThat(ready.document.text).isNull()
        assertThat(ready.document.sizeBytes).isEqualTo(bytes.size.toLong())
        assertThat(ready.document.sha256).isEqualTo(DocumentTextDecoding.sha256(bytes))
    }

    @Test
    fun `来源打不开或读取中断时报告无法读取`() = runTest {
        val root = temporary.newFolder()
        val store = ExternalDocumentStore(root, io = StandardTestDispatcher(testScheduler))
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("中断")
        }

        assertThat(store.stage { throw SecurityException("没有授权") })
            .isEqualTo(ExternalDocumentResult.Failed(ExternalDocumentFailure.Unreadable))
        assertThat(store.stage { ExternalDocumentInput("a.md", null) { broken } })
            .isEqualTo(ExternalDocumentResult.Failed(ExternalDocumentFailure.Unreadable))
        assertThat(root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `按 id 恢复与丢弃 非法 id 不触碰文件系统`() = runTest {
        val root = temporary.newFolder()
        val store = ExternalDocumentStore(root, io = StandardTestDispatcher(testScheduler))
        val staged = (store.stage { input("a.md", "hello".toByteArray()) } as ExternalDocumentResult.Ready).document
        val outside = File(temporary.root, "outside.md").apply { writeText("x") }

        val restored = store.restore(staged.id) as ExternalDocumentResult.Ready
        assertThat(restored.document.text).isEqualTo("hello")
        assertThat(store.restore("../outside.md")).isNull()

        store.discard("..")
        assertThat(outside.exists()).isTrue()
        store.discard(staged.id)
        assertThat(File(root, staged.id).exists()).isFalse()
        assertThat(store.restore(staged.id)).isNull()
    }

    @Test
    fun `超过保留期的暂存目录在下次暂存时清理`() = runTest {
        val root = temporary.newFolder()
        var now = 1_000_000_000_000L
        val store = ExternalDocumentStore(root, now = { now }, io = StandardTestDispatcher(testScheduler))
        val old = (store.stage { input("old.md", "old".toByteArray()) } as ExternalDocumentResult.Ready).document
        File(root, old.id).setLastModified(now - 8L * 24 * 60 * 60 * 1000)
        val unrelated = File(root, "keep-me").apply { mkdirs() }.also { it.setLastModified(0) }

        store.stage { input("new.md", "new".toByteArray()) }

        assertThat(File(root, old.id).exists()).isFalse()
        assertThat(unrelated.exists()).isTrue()
    }

    @Test
    fun `文件名去掉路径与控制字符并保证扩展名`() {
        assertThat(ExternalDocumentStore.safeName("../../etc/passwd")).isEqualTo("passwd.md")
        assertThat(ExternalDocumentStore.safeName("a:b*c?.md")).isEqualTo("a_b_c_.md")
        assertThat(ExternalDocumentStore.safeName(".hidden.md")).isEqualTo("hidden.md")
        assertThat(ExternalDocumentStore.safeName(null)).isEqualTo("document.md")
        assertThat(ExternalDocumentStore.safeName("   ")).isEqualTo("document.md")
        assertThat(ExternalDocumentStore.safeName("x".repeat(300) + ".md").length).isAtMost(123)
    }
}
