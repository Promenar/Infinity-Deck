package com.promenar.infinity.data.memory

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class LongTermMemoryRepositoryTest {
    @Test fun `共享与会话隔离记录按归属分别读取更新和删除`() = runBlocking<Unit> {
        val shared = repo.save(LongTermMemoryKind.USER, "共同标题", "共享").memory
        val isolated = repo.save(LongTermMemoryKind.USER, "共同标题", "隔离", source =
            LongTermMemorySource("a", "s1", null, ownerSessionId = "s1")).memory
        assertThat(isolated.id).isNotEqualTo(shared.id)
        assertThat(repo.getForOwner(shared.id, null)?.content).isEqualTo("共享")
        assertThat(repo.getForOwner(isolated.id, null)).isNull()
        assertThat(repo.getForOwner(shared.id, "s1")).isNull()
        assertThat(repo.getForOwner(isolated.id, "s2")).isNull()
        assertThat(repo.getAllForOwner("s1").map { it.id }).containsExactly(isolated.id)
        assertThat(repo.getAllForOwner(null).map { it.id }).containsExactly(shared.id)
        assertThat(repo.deleteForOwner(isolated.id, "s2")).isNull()
        assertThat(repo.getForOwner(isolated.id, "s1")).isNotNull()
        repo.save(LongTermMemoryKind.USER, "共同标题", "管理页更新", isolated.id)
        assertThat(repo.getForOwner(isolated.id, "s1")?.content).isEqualTo("管理页更新")
        assertThat(runCatching { repo.save(LongTermMemoryKind.USER, "共同标题", "越权", shared.id,
            LongTermMemorySource("a", "s1", null, ownerSessionId = "s1")) }.isFailure).isTrue()
        assertThat(repo.deleteForOwner(isolated.id, "s1")?.id).isEqualTo(isolated.id)
    }

    @Test fun `恢复拒绝非UUID标识防止无界元数据进入提示`() = runBlocking<Unit> {
        val memory = repo.save(LongTermMemoryKind.USER, "标题", "正文").memory
        assertThat(runCatching { repo.restore(memory.copy(id = "x".repeat(10000))) }.isFailure).isTrue()
        assertThat(runCatching { repo.restore(memory.copy(id = "not-a-uuid")) }.isFailure).isTrue()
        assertThat(repo.getAll()).hasSize(1)
    }

    private lateinit var db: InfinityDatabase
    private lateinit var repo: RoomLongTermMemoryRepository
    private var clock = 10L
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java).build()
        repo = RoomLongTermMemoryRepository(db) { clock++ }
    }
    @After fun teardown() { db.close() }

    @Test fun `新建去空白返回来源时间和可观察列表`() = runBlocking<Unit> {
        val source = LongTermMemorySource("a", "s", "m")
        val result = repo.save(LongTermMemoryKind.USER, " 中文偏好 ", " 使用中文 ", source = source)
        assertThat(result.previous).isNull()
        assertThat(java.util.UUID.fromString(result.memory.id).toString()).isEqualTo(result.memory.id)
        assertThat(result.memory.title).isEqualTo("中文偏好")
        assertThat(result.memory.content).isEqualTo("使用中文")
        assertThat(result.memory.sourceMessageId).isEqualTo("m")
        assertThat(repo.get(result.memory.id)).isEqualTo(result.memory)
        assertThat(repo.observeAll().first()).containsExactly(result.memory)
    }
    @Test fun `按id覆盖保留创建时间并返回旧值`() = runBlocking<Unit> {
        val old = repo.save(LongTermMemoryKind.USER, "语言", "中文").memory
        val result = repo.save(LongTermMemoryKind.FEEDBACK, "输出", "简短", old.id, LongTermMemorySource(null, "s2", "m2"))
        assertThat(result.previous).isEqualTo(old)
        assertThat(result.memory.id).isEqualTo(old.id)
        assertThat(result.memory.createdAt).isEqualTo(old.createdAt)
        assertThat(result.memory.updatedAt).isGreaterThan(old.updatedAt)
        assertThat(result.memory.sourceAgentId).isNull()
        assertThat(repo.getAll()).hasSize(1)
    }
    @Test fun `忽略大小写首尾空白的标题在不同助手间去重`() = runBlocking<Unit> {
        val old = repo.save(LongTermMemoryKind.PROJECT, " Infinity Deck ", "开发中", source = LongTermMemorySource("a", "s1", "m1")).memory
        val write = repo.save(LongTermMemoryKind.PROJECT, "iNfInItY dEcK", "验收中", source = LongTermMemorySource("b", "s2", "m2"))
        assertThat(write.previous).isEqualTo(old)
        assertThat(write.memory.id).isEqualTo(old.id)
        assertThat(write.memory.sourceAgentId).isEqualTo("b")
        assertThat(repo.getAll()).hasSize(1)
    }
    @Test fun `指定不存在的id不能悄悄新建`() = runBlocking<Unit> {
        val error = runCatching { repo.save(LongTermMemoryKind.USER, "标题", "正文", "missing") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(repo.getAll()).isEmpty()
    }
    @Test fun `上限阻止第201条但允许原位覆盖`() = runBlocking<Unit> {
        repeat(200) { repo.save(LongTermMemoryKind.USER, "标题$it", "正文") }
        val error = runCatching { repo.save(LongTermMemoryKind.USER, "新标题", "正文") }.exceptionOrNull()
        assertThat(error?.message).contains("合并")
        assertThat(error?.message).contains("删除")
        repo.save(LongTermMemoryKind.USER, "标题0", "更新")
        assertThat(repo.getAll()).hasSize(200)
    }
    @Test fun `并发新建同标题只留一条且上限不被并发绕过`() = runBlocking<Unit> {
        coroutineScope { (1..12).map { async(Dispatchers.IO) { repo.save(LongTermMemoryKind.USER, "共用标题", "正文$it") } }.awaitAll() }
        assertThat(repo.getAll()).hasSize(1)
        repeat(198) { repo.save(LongTermMemoryKind.USER, "标题$it", "正文") }
        val writes = coroutineScope { (1..6).map { async(Dispatchers.IO) { runCatching { repo.save(LongTermMemoryKind.USER, "并发$it", "正文") }.isSuccess } }.awaitAll() }
        assertThat(writes.count { it }).isEqualTo(1)
        assertThat(repo.getAll()).hasSize(200)
    }
    @Test fun `标题正文长度校验不写入无效内容`() = runBlocking<Unit> {
        listOf("" to "正文", "  " to "正文", "字".repeat(81) to "正文", "标题" to "", "标题" to "字".repeat(2001)).forEach { (title, content) ->
            assertThat(runCatching { repo.save(LongTermMemoryKind.USER, title, content) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
        repo.save(LongTermMemoryKind.USER, "字".repeat(80), "字".repeat(2000))
        assertThat(repo.getAll()).hasSize(1)
        assertThrows(IllegalArgumentException::class.java) { LongTermMemoryKind.fromStorage("other") }
    }
    @Test fun `拒绝可辨识凭据且错误不包含原始内容`() = runBlocking<Unit> {
        val samples = listOf("api_key=synthetic-value", "密码：仅供测试", "Bearer synthetic-test-token", "-----BEGIN PRIVATE KEY-----", "sk-" + "x".repeat(24), "https://user:synthetic@host.invalid")
        for (sample in samples) {
            val error = runCatching { repo.save(LongTermMemoryKind.REFERENCE, "参考", sample) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(error?.message).doesNotContain(sample)
            assertThat(runCatching { repo.save(LongTermMemoryKind.USER, sample.take(80), "安全正文") }.isFailure).isTrue()
        }
        assertThat(runCatching { repo.save(LongTermMemoryKind.USER, "password", "synthetic-value") }.isFailure).isTrue()
        assertThat(repo.getAll()).isEmpty()
        repo.save(LongTermMemoryKind.REFERENCE, "凭据使用规则", "不要在对话中提供密码，文件位置为 docs/setup.md")
        assertThat(repo.getAll()).hasSize(1)
    }
    @Test fun `删除返回旧值并可精确恢复覆盖或删除`() = runBlocking<Unit> {
        val old = repo.save(LongTermMemoryKind.USER, "偏好", "中文", source = LongTermMemorySource("a", "s", "m")).memory
        repo.save(LongTermMemoryKind.FEEDBACK, "偏好", "英文", old.id)
        repo.restore(old)
        assertThat(repo.get(old.id)).isEqualTo(old)
        assertThat(repo.delete(old.id)).isEqualTo(old)
        assertThat(repo.delete(old.id)).isNull()
        repo.restore(old)
        assertThat(repo.get(old.id)).isEqualTo(old)
    }
    @Test fun `恢复仍拒绝凭据且不会突破上限`() = runBlocking<Unit> {
        val old = repo.save(LongTermMemoryKind.USER, "偏好", "中文").memory
        assertThat(runCatching { repo.restore(old.copy(content = "password=synthetic")) }.isFailure).isTrue()
        repeat(199) { repo.save(LongTermMemoryKind.USER, "标题$it", "正文") }
        repo.restore(old)
        assertThat(runCatching { repo.restore(old.copy(id = java.util.UUID.randomUUID().toString())) }.isFailure).isTrue()
        assertThat(repo.getAll()).hasSize(200)
    }
    @Test fun `列表按更新时间降序返回且来源不产生外键`() = runBlocking<Unit> {
        val old = repo.save(LongTermMemoryKind.USER, "偏好", "中文").memory
        val newer = repo.save(LongTermMemoryKind.PROJECT, "项目", "开发中", source = LongTermMemorySource("deleted-a", "deleted-s", "deleted-m")).memory
        assertThat(repo.getAll().map { it.id }).containsExactly(newer.id, old.id).inOrder()
        assertThat(repo.observeAll().first().map { it.id }).containsExactly(newer.id, old.id).inOrder()
    }

    @Test fun `用户记忆有独立表并且不绑定会话外键`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        try {
            val sqlite = database.openHelper.writableDatabase
            assertThat(sqlite.query("SELECT name FROM sqlite_master WHERE type='table' AND name='long_term_memories'").use { it.count }).isEqualTo(1)
            assertThat(sqlite.query("PRAGMA foreign_key_list(long_term_memories)").use { it.count }).isEqualTo(0)
        } finally { database.close() }
    }
}
