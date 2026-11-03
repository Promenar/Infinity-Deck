package com.promenar.infinity.data.chat.queue

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.MessageDocumentAttachment
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileComposerQueueStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `插队回执首次写盘失败后同进程按已持久用户恢复且不重复派发`() = runTest {
        var persisted = false
        val store = FileComposerQueueStore(folder.root, { null }, StandardTestDispatcher(testScheduler),
            isUserPersisted = { _, user -> persisted && user == "saved-steering-user" })
        val ordinary = store.enqueue("s", "ordinary")
        val item = store.enqueue("s", "steered")
        store.steer("s", item.id)
        store.claimSteering("s")
        store.bindSending("s", item.id, "saved-steering-user")
        persisted = true
        val temporary = File(folder.root.listFiles()!!.single(), "queue.json.tmp")
        temporary.mkdir()
        assertThat(runCatching { store.accept("s", item.id) }.isFailure).isTrue()
        temporary.delete()
        assertThat(store.snapshot("s").sending).isNull()
        assertThat(store.claimNext("s")?.id).isEqualTo(ordinary.id)
        assertThat(store.claimNext("s")).isNull()
    }

    @Test fun `插队认领只消费标记项且回执后普通派发不重复`() = runTest {
        val store = FileComposerQueueStore(folder.root, { null }, StandardTestDispatcher(testScheduler))
        val ordinary = store.enqueue("s", "ordinary")
        assertThat(store.claimSteering("s")).isNull()
        val steering = store.enqueue("s", "steering")
        store.steer("s", steering.id)
        assertThat(store.claimSteering("s")?.id).isEqualTo(steering.id)
        assertThat(store.claimNext("s")).isNull()
        store.bindSending("s", steering.id, "user-steering")
        store.accept("s", steering.id)
        assertThat(store.claimNext("s")?.id).isEqualTo(ordinary.id)
    }

    @Test fun `插队认领未注入时取消保留原条目`() = runTest {
        val store = FileComposerQueueStore(folder.root, { null }, StandardTestDispatcher(testScheduler))
        val item = store.enqueue("s", "keep")
        store.steer("s", item.id)
        store.claimSteering("s")
        store.rollback("s", item.id, QueuePauseReason.INTERRUPTED)
        assertThat(store.snapshot("s").items.single().id).isEqualTo(item.id)
        assertThat(store.snapshot("s").items.single().steering).isTrue()
    }

    @Test fun `提交期间取消调用者仍能确认已持久化的所有权`() = runTest {
        lateinit var operation: kotlinx.coroutines.Job
        var returned = false
        val store = FileComposerQueueStore(folder.root, {
            operation.cancel()
            ByteArrayInputStream(byteArrayOf(1))
        }, StandardTestDispatcher(testScheduler))
        operation = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            store.enqueue("s", "queued", listOf("image"))
            returned = true
        }
        operation.start()
        operation.join()
        assertThat(returned).isTrue()
        assertThat(store.snapshot("s").items.single().text).isEqualTo("queued")
    }

    @Test fun `绑定用户消息已落盘时重启结清租约避免重复发送`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileComposerQueueStore(folder.root, { ByteArrayInputStream(byteArrayOf(1)) }, dispatcher)
        val item = store.enqueue("s", "sent", listOf("image"))
        store.enqueue("s", "waiting")
        store.claimNext("s")
        store.bindSending("s", item.id, "persisted-user")
        val restored = FileComposerQueueStore(folder.root, { null }, dispatcher, isUserPersisted = { session, user -> session == "s" && user == "persisted-user" })
        assertThat(restored.snapshot("s").items.map { it.text }).containsExactly("waiting")
        assertThat(restored.snapshot("s").pauseReason).isEqualTo(QueuePauseReason.RESTORED)
        assertThat(File(item.imagePaths.single()).exists()).isFalse()
    }

    @Test fun `编辑取消保留原队列和图片并清理副本`() = runTest {
        val store = FileComposerQueueStore(folder.root, { ByteArrayInputStream(byteArrayOf(1)) }, StandardTestDispatcher(testScheduler))
        val item = store.enqueue("s", "text", listOf("image"))
        val draft = store.prepareEdit("s", item.id)!!
        assertThat(store.claimNext("s")).isNull()
        store.cancelEdit("s", draft)
        assertThat(store.snapshot("s").items.single().id).isEqualTo(item.id)
        assertThat(File(item.imagePaths.single()).exists()).isTrue()
        assertThat(File(draft.imagePaths.single()).exists()).isFalse()
    }

    @Test fun `发送中新增插队标记不会让重启数据被判损坏`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileComposerQueueStore(folder.root, { null }, dispatcher)
        val a = store.enqueue("s", "a")
        val b = store.enqueue("s", "b")
        store.steer("s", a.id)
        store.claimNext("s")
        store.steer("s", b.id)
        val restored = FileComposerQueueStore(folder.root, { null }, dispatcher).snapshot("s")
        assertThat(restored.items.map { it.id }).containsExactly(a.id, b.id).inOrder()
        assertThat(restored.items.filter { it.steering }.map { it.id }).containsExactly(b.id)
    }

    @Test fun `只有绑定的用户消息可以结清发送租约`() = runTest {
        val store = FileComposerQueueStore(folder.root, { null }, StandardTestDispatcher(testScheduler))
        val item = store.enqueue("s", "queued")
        store.claimNext("s")
        store.bindSending("s", item.id, "user-id")
        store.acceptForUser("s", "other-user")
        assertThat(store.snapshot("s").sending?.id).isEqualTo(item.id)
        store.acceptForUser("s", "user-id")
        assertThat(store.snapshot("s").sending).isNull()
    }

    @Test fun `队列清空后不残留暂停原因，下一次排队照常接续`() = runTest {
        val store = FileComposerQueueStore(folder.root, { ByteArrayInputStream(byteArrayOf(1)) }, StandardTestDispatcher(testScheduler))
        val paused = store.enqueue("s", "old")
        store.pause("s", QueuePauseReason.STOPPED)
        store.remove("s", paused.id)
        assertThat(store.snapshot("s").pauseReason).isNull()

        // 发送中被停止、随后租约结清：同样不应把暂停带给之后排队的消息。
        val sent = store.enqueue("s", "sent")
        store.claimNext("s")
        store.pause("s", QueuePauseReason.STOPPED)
        store.accept("s", sent.id)
        store.enqueue("s", "next")
        assertThat(store.snapshot("s").pauseReason).isNull()
        assertThat(FileComposerQueueStore(folder.root, { null }).snapshot("s").items.map { it.text }).containsExactly("next")
    }

    @Test fun `队列顺序和插队标记按会话隔离且唯一`() = runTest {
        val store = FileComposerQueueStore(folder.root, { ByteArrayInputStream(byteArrayOf(1)) }, StandardTestDispatcher(testScheduler))
        val a = store.enqueue("s", "a")
        val b = store.enqueue("s", "b")
        store.enqueue("other", "c")
        assertThat(store.steer("s", "missing")).isFalse()
        assertThat(store.snapshot("s").items.none { it.steering }).isTrue()
        store.moveToFront("s", b.id)
        assertThat(store.snapshot("s").items.map { it.text }).containsExactly("b", "a").inOrder()
        store.steer("s", b.id)
        store.steer("s", a.id)
        assertThat(store.snapshot("s").items.filter { it.steering }.map { it.id }).containsExactly(a.id)
        store.remove("s", a.id)
        assertThat(store.snapshot("other").items.single().text).isEqualTo("c")
        assertThat(store.snapshot("s").items.single().id).isEqualTo(b.id)
    }

    @Test fun `首次从磁盘恢复文档和暂停状态且同实例读取不重置暂停`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val document = MessageDocumentAttachment("d", "a.txt", "text/plain", "正文", 6, "sha", 2)
        val store = FileComposerQueueStore(folder.root, { null }, dispatcher)
        store.enqueue("s", "文字", documents = listOf(document))
        store.pause("s", QueuePauseReason.FAILED)
        assertThat(store.snapshot("s").pauseReason).isEqualTo(QueuePauseReason.FAILED)
        val restored = FileComposerQueueStore(folder.root, { null }, dispatcher)
        assertThat(restored.snapshot("s").pauseReason).isEqualTo(QueuePauseReason.RESTORED)
        assertThat(restored.snapshot("s").items.single().documents).containsExactly(document)
        restored.pause("s", null)
        assertThat(restored.snapshot("s").pauseReason).isNull()
    }

    @Test fun `排队消息的引用批注写盘后可恢复，旧队列文件缺字段时视为无引用`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val quote = com.promenar.infinity.data.model.MessageQuote("ai-1", "引文", "批注")
        val store = FileComposerQueueStore(folder.root, { null }, dispatcher)
        store.enqueue("s", "", quotes = listOf(quote))
        assertThat(FileComposerQueueStore(folder.root, { null }, dispatcher).snapshot("s").items.single().quotes).containsExactly(quote)

        val file = folder.root.walkTopDown().single { it.name == "queue.json" }
        file.writeText(file.readText().replace(Regex(""",?"quotes":\[[^\]]*\]"""), ""))
        assertThat(file.readText()).doesNotContain("quotes")
        assertThat(FileComposerQueueStore(folder.root, { null }, dispatcher).snapshot("s").items.single().quotes).isEmpty()
    }

    @Test fun `损坏JSON丢弃队列及其图片`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileComposerQueueStore(folder.root, { ByteArrayInputStream(byteArrayOf(1, 2)) }, dispatcher)
        val image = File(store.enqueue("s", "a", listOf("image")).imagePaths.single())
        folder.root.walkTopDown().single { it.name == "queue.json" }.writeText("{broken")
        val restored = FileComposerQueueStore(folder.root, { null }, dispatcher)
        assertThat(restored.snapshot("s").items).isEmpty()
        assertThat(image.exists()).isFalse()
    }

    @Test fun `图片复制到私有目录且移除与删除会话清理图片`() = runTest {
        val store = FileComposerQueueStore(folder.root, { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, StandardTestDispatcher(testScheduler))
        val item = store.enqueue("s", "a", listOf("content://image"))
        val image = File(item.imagePaths.single())
        assertThat(image.readBytes().toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
        store.remove("s", item.id)
        assertThat(image.exists()).isFalse()
        val second = store.enqueue("s", "b", listOf("content://image"))
        store.deleteSession("s")
        assertThat(File(second.imagePaths.single()).exists()).isFalse()
        assertThat(store.snapshot("s").items).isEmpty()
        store.pause("s", QueuePauseReason.INTERRUPTED)
        assertThat(folder.root.listFiles().orEmpty()).isEmpty()
    }

    @Test fun `复制中失败不留下部分图片或队列项`() = runTest {
        val store = FileComposerQueueStore(folder.root, { if (it == "bad") null else ByteArrayInputStream(byteArrayOf(1)) }, StandardTestDispatcher(testScheduler))
        assertThat(runCatching { store.enqueue("s", "a", listOf("ok", "bad")) }.isFailure).isTrue()
        assertThat(store.snapshot("s").items).isEmpty()
        assertThat(folder.root.walkTopDown().filter { it.isFile }.toList()).isEmpty()
    }

    @Test fun `出队保留发送租约和图片且失败回到队首暂停`() = runTest {
        val store = FileComposerQueueStore(folder.root, { ByteArrayInputStream(byteArrayOf(1)) }, StandardTestDispatcher(testScheduler))
        val first = store.enqueue("s", "a", listOf("image"))
        store.enqueue("s", "b")
        assertThat(store.claimNext("s")?.id).isEqualTo(first.id)
        assertThat(store.snapshot("s").items.single().text).isEqualTo("b")
        assertThat(File(first.imagePaths.single()).exists()).isTrue()
        store.rollback("s", first.id, QueuePauseReason.FAILED)
        assertThat(store.snapshot("s").items.map { it.text }).containsExactly("a", "b").inOrder()
        assertThat(store.snapshot("s").pauseReason).isEqualTo(QueuePauseReason.FAILED)
        assertThat(store.claimNext("s")).isNull()
        store.pause("s", null)
        store.claimNext("s")
        store.accept("s", first.id)
        assertThat(File(first.imagePaths.single()).exists()).isFalse()
    }

    @Test fun `发送租约在进程重启后恢复为暂停且插队优先于普通队首`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileComposerQueueStore(folder.root, { null }, dispatcher)
        val a = store.enqueue("s", "a")
        val b = store.enqueue("s", "b")
        store.steer("s", b.id)
        store.moveToFront("s", a.id)
        assertThat(store.claimNext("s")?.id).isEqualTo(b.id)
        val restored = FileComposerQueueStore(folder.root, { null }, dispatcher)
        assertThat(restored.snapshot("s").items.map { it.text }).containsExactly("b", "a").inOrder()
        assertThat(restored.snapshot("s").pauseReason).isEqualTo(QueuePauseReason.RESTORED)
    }

    @Test fun `编辑将图片所有权交给草稿直到消费且删除会话兜底`() = runTest {
        val store = FileComposerQueueStore(folder.root, { ByteArrayInputStream(byteArrayOf(1)) }, StandardTestDispatcher(testScheduler))
        val item = store.enqueue("s", "a", listOf("image"))
        val draft = store.prepareEdit("s", item.id)!!
        assertThat(store.snapshot("s").items.single().id).isEqualTo(item.id)
        assertThat(store.completeEdit("s", draft)).isTrue()
        assertThat(store.snapshot("s").items).isEmpty()
        assertThat(File(item.imagePaths.single()).exists()).isFalse()
        assertThat(File(draft.imagePaths.single()).exists()).isTrue()
        store.releaseDraftImages("s", draft.imagePaths)
        assertThat(File(draft.imagePaths.single()).exists()).isFalse()
    }
}
