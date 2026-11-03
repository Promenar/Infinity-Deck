package com.promenar.infinity.data.chat.queue

import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.MessageQuote
import com.promenar.infinity.utils.InfinityLogger
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 队列写盘成功后才发布状态；正文及附件不进入诊断日志。 */
class FileComposerQueueStore(
    private val root: File,
    private val openImage: (String) -> InputStream?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val isUserPersisted: suspend (String, String) -> Boolean = { _, _ -> false },
) : ComposerQueueStore {
    private val mutex = Mutex()
    private val states = mutableMapOf<String, MutableStateFlow<ComposerQueueState>>()
    private val deletedSessions = mutableSetOf<String>()
    private val preparedEdits = mutableMapOf<String, QueuedMessage>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun directory(sessionId: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(root, hash)
    }

    private suspend fun load(sessionId: String): MutableStateFlow<ComposerQueueState> = states.getOrPut(sessionId) {
        val dir = directory(sessionId)
        val file = File(dir, "queue.json")
        var restored = false
        val state = if (sessionId in deletedSessions || !file.exists()) ComposerQueueState() else {
            // 读取失败必须保留原文件；只有内容损坏才丢弃队列。
            val raw = file.readText()
            try {
                val saved = json.decodeFromString<ComposerQueueState>(raw)
                val accepted = saved.sendingUserMessageId?.let { isUserPersisted(sessionId, it) } == true
                val recovered = saved.sending.takeUnless { accepted }?.let { if (saved.items.any { item -> item.steering }) it.copy(steering = false) else it }
                val items = listOfNotNull(recovered) + saved.items
                require(items.map { it.id }.distinct().size == items.size)
                require(items.count { it.steering } <= 1)
                require(items.all { item -> item.imagePaths.all { path ->
                    File(path).canonicalFile.parentFile?.parentFile == File(dir, "images").canonicalFile
                } })
                if (accepted) deleteImages(saved.sending)
                restored = true
                saved.copy(items = items, sending = null, sendingUserMessageId = null, sendingInLoop = false, pauseReason = QueuePauseReason.RESTORED.takeIf { items.isNotEmpty() })
            } catch (_: IllegalArgumentException) {
                discardCorrupt(dir)
            } catch (_: SerializationException) {
                discardCorrupt(dir)
            }
        }
        if (restored) write(sessionId, state)
        MutableStateFlow(state)
    }

    private fun discardCorrupt(dir: File): ComposerQueueState {
        check(!dir.exists() || dir.deleteRecursively()) { "无法清理损坏的消息队列" }
        InfinityLogger.diagnostic("composer_queue.corrupt", mapOf("discarded" to "true"))
        return ComposerQueueState()
    }

    private fun write(sessionId: String, state: ComposerQueueState) {
        val dir = directory(sessionId)
        check(dir.isDirectory || dir.mkdirs()) { "无法创建消息队列目录" }
        val temp = File(dir, "queue.json.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(json.encodeToString(state).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temp.toPath(), File(dir, "queue.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(NonCancellable) {
        withContext(ioDispatcher) { mutex.withLock { block() } }
    }
    private suspend fun update(sessionId: String, transform: (ComposerQueueState) -> ComposerQueueState) {
        if (sessionId in deletedSessions) return
        val flow = load(sessionId)
        // 暂停只针对还在排队或发送中的消息；队列清空后不把旧的暂停原因带给下一次排队。
        val next = transform(flow.value).let { if (it.items.isEmpty() && it.sending == null) it.copy(pauseReason = null) else it }
        if (next == flow.value) return
        write(sessionId, next)
        flow.value = next
    }
    /** 回执写盘失败后，绑定消息可在同一进程里恢复确认，不重新发送已落盘的用户消息。 */
    private suspend fun reconcileSending(sessionId: String) {
        val state = load(sessionId).value
        val sending = state.sending ?: return
        if (!state.sendingInLoop) return
        val userId = state.sendingUserMessageId ?: return
        if (!isUserPersisted(sessionId, userId)) return
        update(sessionId) { it.copy(sending = null, sendingUserMessageId = null, sendingInLoop = false) }
        deleteImages(sending)
    }
    override suspend fun observe(sessionId: String): StateFlow<ComposerQueueState> = locked { reconcileSending(sessionId); load(sessionId) }

    override suspend fun enqueue(sessionId: String, text: String, imageUris: List<String>, documents: List<MessageDocumentAttachment>, quotes: List<MessageQuote>): QueuedMessage = locked {
        check(sessionId !in deletedSessions) { "会话已删除" }
        load(sessionId)
        val id = UUID.randomUUID().toString()
        val imageDir = File(directory(sessionId), "images/$id")
        try {
            val paths = imageUris.mapIndexed { index, uri ->
                check(imageDir.isDirectory || imageDir.mkdirs()) { "无法创建队列图片目录" }
                val target = File(imageDir, "$index.image")
                checkNotNull(openImage(uri)) { "无法读取排队消息的图片" }.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
                }
                target.absolutePath
            }
            QueuedMessage(id, text, paths, documents, System.currentTimeMillis(), quotes = quotes).also { item ->
                update(sessionId) { it.copy(items = it.items + item) }
            }
        } catch (failure: Throwable) {
            imageDir.deleteRecursively()
            throw failure
        }
    }

    private fun deleteImages(item: QueuedMessage?) {
        item?.imagePaths?.forEach { path ->
            val file = File(path)
            check(!file.exists() || file.delete()) { "无法清理队列图片" }
            file.parentFile?.takeIf { it.list().orEmpty().isEmpty() }?.delete()
        }
    }
    override suspend fun remove(sessionId: String, id: String) = locked {
        val item = load(sessionId).value.items.find { it.id == id }
        update(sessionId) { it.copy(items = it.items.filterNot { item -> item.id == id }) }
        deleteImages(item)
    }
    override suspend fun moveToFront(sessionId: String, id: String) = locked {
        update(sessionId) { state -> state.items.find { it.id == id }?.let { item ->
            state.copy(items = listOf(item) + state.items.filterNot { it.id == id })
        } ?: state }
    }
    override suspend fun steer(sessionId: String, id: String): Boolean = locked {
        if (load(sessionId).value.items.none { it.id == id }) return@locked false
        update(sessionId) { state -> state.items.find { it.id == id }?.let { item ->
            state.copy(items = listOf(item.copy(steering = true)) + state.items.filterNot { it.id == id }.map { it.copy(steering = false) }, pauseReason = null)
        } ?: state }
        true
    }
    override suspend fun pause(sessionId: String, reason: QueuePauseReason?) = locked {
        update(sessionId) { if (it.items.isEmpty() && it.sending == null) it else it.copy(pauseReason = reason) }
    }
    override suspend fun claimNext(sessionId: String): QueuedMessage? = locked {
        reconcileSending(sessionId)
        val state = load(sessionId).value
        if (state.pauseReason != null || state.sending != null || sessionId in deletedSessions) return@locked null
        val available = state.items.filterNot { it.id == preparedEdits[sessionId]?.id }
        val item = available.firstOrNull { it.steering } ?: available.firstOrNull() ?: return@locked null
        update(sessionId) { it.copy(items = it.items.filterNot { queued -> queued.id == item.id }, sending = item.copy(steering = false), sendingUserMessageId = null, sendingInLoop = false) }
        item.copy(steering = false)
    }
    override suspend fun claimSteering(sessionId: String): QueuedMessage? = claimForLoop(sessionId, true)
    override suspend fun claimBoundary(sessionId: String): QueuedMessage? = claimForLoop(sessionId, false)
    private suspend fun claimForLoop(sessionId: String, steeringOnly: Boolean): QueuedMessage? = locked {
        reconcileSending(sessionId)
        val state = load(sessionId).value
        if (state.pauseReason != null || state.sending != null || sessionId in deletedSessions) return@locked null
        val item = if (steeringOnly) state.items.firstOrNull { it.steering } else state.items.firstOrNull()
        if (item == null || item.id == preparedEdits[sessionId]?.id) return@locked null
        update(sessionId) { it.copy(items = it.items.filterNot { queued -> queued.id == item.id }, sending = item,
            sendingUserMessageId = null, sendingInLoop = true) }
        item
    }
    override suspend fun bindSending(sessionId: String, id: String, userMessageId: String) = locked {
        update(sessionId) { if (it.sending?.id == id) it.copy(sendingUserMessageId = userMessageId) else it }
    }
    override suspend fun accept(sessionId: String, id: String) = locked {
        val sending = load(sessionId).value.sending?.takeIf { it.id == id } ?: return@locked
        update(sessionId) { it.copy(sending = null, sendingUserMessageId = null, sendingInLoop = false) }
        deleteImages(sending)
    }
    override suspend fun rollback(sessionId: String, id: String, reason: QueuePauseReason) = locked {
        reconcileSending(sessionId)
        update(sessionId) { state -> state.sending?.takeIf { it.id == id }?.let { item ->
            val items = state.items.map { if (item.steering) it.copy(steering = false) else it }
            state.copy(items = listOf(item) + items, sending = null, sendingUserMessageId = null, sendingInLoop = false, pauseReason = reason)
        } ?: state }
    }
    override suspend fun prepareEdit(sessionId: String, id: String): QueuedMessage? = locked {
        if (preparedEdits.containsKey(sessionId)) return@locked null
        val item = load(sessionId).value.items.find { it.id == id } ?: return@locked null
        val draftDir = File(directory(sessionId), "drafts/${UUID.randomUUID()}")
        try {
            val paths = item.imagePaths.mapIndexed { index, path ->
                check(draftDir.isDirectory || draftDir.mkdirs())
                File(path).copyTo(File(draftDir, "$index.image")).absolutePath
            }
            item.copy(imagePaths = paths).also { draft ->
                update(sessionId) { it.copy(editingId = id) }
                preparedEdits[sessionId] = draft
            }
        } catch (failure: Throwable) {
            draftDir.deleteRecursively()
            throw failure
        }
    }
    override suspend fun completeEdit(sessionId: String, draft: QueuedMessage): Boolean = locked {
        if (preparedEdits[sessionId] != draft) return@locked false
        val original = load(sessionId).value.items.find { it.id == draft.id } ?: return@locked false
        update(sessionId) { it.copy(items = it.items.filterNot { queued -> queued.id == draft.id }, editingId = null) }
        preparedEdits.remove(sessionId)
        // 已提交的草稿副本不能因原图片清理失败而丢失。
        runCatching { deleteImages(original) }.onFailure {
            InfinityLogger.diagnostic("composer_queue.image_cleanup_failed", mapOf("operation" to "edit"))
        }
        true
    }
    override suspend fun cancelEdit(sessionId: String, draft: QueuedMessage) = locked {
        if (preparedEdits[sessionId] == draft) {
            update(sessionId) { it.copy(editingId = null) }
            preparedEdits.remove(sessionId)
        }
        deleteImages(draft)
    }
    override suspend fun releaseDraftImages(sessionId: String, paths: List<String>) = locked {
        val draftRoot = File(directory(sessionId), "drafts").canonicalFile
        paths.forEach { path ->
            val file = File(path).canonicalFile
            if (file.parentFile?.parentFile == draftRoot) deleteImages(QueuedMessage("", "", listOf(path), createdAt = 0))
        }
    }
    override suspend fun deleteSession(sessionId: String) = locked {
        val dir = directory(sessionId)
        check(!dir.exists() || dir.deleteRecursively()) { "无法删除会话消息队列" }
        deletedSessions += sessionId
        preparedEdits.remove(sessionId)
        load(sessionId).value = ComposerQueueState()
    }
}
