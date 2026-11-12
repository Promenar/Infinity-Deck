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
import com.promenar.infinity.data.production.PanelMessageListener
import com.promenar.infinity.data.production.captureWithdrawal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class PanelDeliveryReceipts(val sessionId: String, val origins: List<PanelMessageOrigin>)

/** 队列写盘成功后才发布状态；正文及附件不进入诊断日志。 */
class FileComposerQueueStore(
    private val root: File,
    private val openImage: (String) -> InputStream?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val isUserPersisted: suspend (String, String) -> Boolean = { _, _ -> false },
    private val applicationScope: CoroutineScope? = null,
    private val panelMessageListener: (() -> PanelMessageListener)? = null,
) : ComposerQueueStore {
    override val handlesPanelMessages: Boolean get() = applicationScope != null && panelMessageListener != null
    private val mutex = Mutex()
    private val states = mutableMapOf<String, MutableStateFlow<ComposerQueueState>>()
    private val deletedSessions = mutableSetOf<String>()
    private val preparedEdits = mutableMapOf<String, QueuedMessage>()
    private val receiptOnlySessions = mutableSetOf<String>()
    private val notifying = mutableSetOf<Pair<String, PanelMessageOrigin>>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun directory(sessionId: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(root, hash)
    }

    private suspend fun load(sessionId: String): MutableStateFlow<ComposerQueueState> = states.getOrPut(sessionId) {
        val dir = directory(sessionId)
        val file = File(dir, "queue.json")
        val detached = readReceipts(sessionId)
        if (!file.exists() && detached.isNotEmpty()) receiptOnlySessions += sessionId
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
                val direct = saved.panelPreparations.filter { isUserPersisted(sessionId, it.userMessageId) }.map { it.origin }
                val delivered = (saved.deliveredPanelMessages + direct + listOfNotNull(saved.sending?.origin.takeIf { accepted })).distinct()
                if (accepted) deleteImages(saved.sending)
                saved.panelPreparations.filterNot { it.origin in direct }.forEach { notifyPanel(sessionId, it.origin, false) }
                restored = true
                saved.copy(items = items, sending = null, sendingUserMessageId = null, sendingInLoop = false,
                    deliveredPanelMessages = delivered, panelPreparations = emptyList(), pauseReason = QueuePauseReason.RESTORED.takeIf { items.isNotEmpty() })
            } catch (_: IllegalArgumentException) {
                discardCorrupt(dir)
            } catch (_: SerializationException) {
                discardCorrupt(dir)
            }
        }
        val recovered = state.copy(deliveredPanelMessages = (state.deliveredPanelMessages + detached).distinct())
        if (restored) write(sessionId, recovered)
        recovered.deliveredPanelMessages.forEach { notifyPanel(sessionId, it, true) }
        MutableStateFlow(recovered)
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
        update(sessionId) { it.copy(sending = null, sendingUserMessageId = null, sendingInLoop = false,
            deliveredPanelMessages = (it.deliveredPanelMessages + listOfNotNull(sending.origin)).distinct()) }
        sending.origin?.let { notifyPanel(sessionId, it, true) }
        deleteImages(sending)
    }
    override suspend fun observe(sessionId: String): StateFlow<ComposerQueueState> = locked { reconcileSending(sessionId); load(sessionId) }

    override suspend fun enqueue(sessionId: String, text: String, imageUris: List<String>, documents: List<MessageDocumentAttachment>, quotes: List<MessageQuote>, origin: PanelMessageOrigin?): QueuedMessage = locked {
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
            QueuedMessage(id, text, paths, documents, System.currentTimeMillis(), quotes = quotes, origin = origin).also { item ->
                update(sessionId) { it.copy(items = it.items + item) }
            }
        } catch (failure: Throwable) {
            imageDir.deleteRecursively()
            throw failure
        }
    }

    private fun receiptFile(sessionId: String) = File(root, "${directory(sessionId).name}.reviews.json")
    private fun readReceipts(sessionId: String): List<PanelMessageOrigin> {
        val file = receiptFile(sessionId)
        if (!file.exists()) return emptyList()
        val receipt = json.decodeFromString<PanelDeliveryReceipts>(file.readText())
        require(receipt.sessionId == sessionId) { "审核回执会话不一致。" }
        return receipt.origins
    }
    private fun writeReceipts(sessionId: String, origins: List<PanelMessageOrigin>) {
        val file = receiptFile(sessionId)
        if (origins.isEmpty()) {
            check(!file.exists() || file.delete()) { "无法清理审核回执。" }
            return
        }
        check(root.isDirectory || root.mkdirs()) { "无法创建审核回执目录。" }
        val temp = File(root, "${file.name}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(json.encodeToString(PanelDeliveryReceipts(sessionId, origins.distinct())).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    override suspend fun recoverPanelMessages(): List<Pair<String, PanelMessageOrigin>> = locked {
        root.listFiles().orEmpty().filter { it.name.endsWith(".reviews.json") }.flatMap { file ->
            val receipt = json.decodeFromString<PanelDeliveryReceipts>(file.readText())
            load(receipt.sessionId).value.deliveredPanelMessages.map { receipt.sessionId to it }
        }
    }

    /** 直接开轮与排队派发均由应用级生成运行时结清，准备失败仍可回退。 */
    override suspend fun acceptForUser(sessionId: String, userMessageId: String?) {
        val direct = locked {
            val preparation = load(sessionId).value.panelPreparations.firstOrNull { it.userMessageId == userMessageId } ?: return@locked false
            update(sessionId) { it.copy(panelPreparations = it.panelPreparations - preparation,
                deliveredPanelMessages = (it.deliveredPanelMessages + preparation.origin).distinct()) }
            notifyPanel(sessionId, preparation.origin, true)
            true
        }
        if (!direct) super<ComposerQueueStore>.acceptForUser(sessionId, userMessageId)
    }

    override suspend fun rollbackForUser(sessionId: String, userMessageId: String?) {
        val direct = locked {
            val preparation = load(sessionId).value.panelPreparations.firstOrNull { it.userMessageId == userMessageId } ?: return@locked false
            update(sessionId) { it.copy(panelPreparations = it.panelPreparations - preparation) }
            notifyPanel(sessionId, preparation.origin, false)
            true
        }
        if (!direct) super<ComposerQueueStore>.rollbackForUser(sessionId, userMessageId)
    }

    override suspend fun bindPanelMessage(sessionId: String, origin: PanelMessageOrigin, userMessageId: String) = locked {
        check(sessionId !in deletedSessions) { "会话已删除" }
        update(sessionId) { it.copy(panelPreparations = it.panelPreparations.filterNot { value -> value.origin == origin } + PanelMessagePreparation(origin, userMessageId)) }
    }

    override suspend fun finishPanelMessage(sessionId: String, origin: PanelMessageOrigin, persisted: Boolean) = locked {
        update(sessionId) { it.copy(panelPreparations = it.panelPreparations.filterNot { value -> value.origin == origin },
            deliveredPanelMessages = if (persisted) (it.deliveredPanelMessages + origin).distinct() else it.deliveredPanelMessages) }
        notifyPanel(sessionId, origin, persisted)
    }

    /** 回调只投递到应用作用域；成功归档之前，已送达回执持续保留在磁盘。 */
    private fun notifyPanel(sessionId: String, origin: PanelMessageOrigin, delivered: Boolean) {
        val scope = applicationScope ?: return
        val listener = panelMessageListener ?: return
        val key = sessionId to origin
        if (delivered && !notifying.add(key)) return
        val withdrawal = try { if (delivered) null else listener().captureWithdrawal(sessionId, origin) }
        catch (_: Exception) {
            InfinityLogger.diagnostic("production_review.callback_failed", mapOf("delivered" to delivered.toString()))
            return
        }
        scope.launch {
            try {
                if (delivered) listener().delivered(sessionId, origin) else withdrawal?.invoke()
                if (delivered) locked {
                    writeReceipts(sessionId, readReceipts(sessionId) - origin)
                    if (sessionId in deletedSessions || sessionId in receiptOnlySessions) {
                        states[sessionId]?.let { it.value = it.value.copy(deliveredPanelMessages = it.value.deliveredPanelMessages - origin) }
                    } else update(sessionId) { it.copy(deliveredPanelMessages = it.deliveredPanelMessages - origin) }
                }
            } catch (_: Exception) {
                InfinityLogger.diagnostic("production_review.callback_failed", mapOf("delivered" to delivered.toString()))
            } finally {
                if (delivered) locked { notifying.remove(key) }
            }
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
        item?.origin?.let { notifyPanel(sessionId, it, false) }
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
        update(sessionId) { it.copy(sending = null, sendingUserMessageId = null, sendingInLoop = false,
            deliveredPanelMessages = (it.deliveredPanelMessages + listOfNotNull(sending.origin)).distinct()) }
        sending.origin?.let { notifyPanel(sessionId, it, true) }
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
        original.origin?.let { notifyPanel(sessionId, it, false) }
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
        val state = load(sessionId).value
        val delivered = state.deliveredPanelMessages.toSet() + state.panelPreparations.filter { isUserPersisted(sessionId, it.userMessageId) }.map { it.origin } +
            listOfNotNull(state.sending?.origin.takeIf { state.sendingUserMessageId?.let { isUserPersisted(sessionId, it) } == true })
        val origins = (state.items.mapNotNull { it.origin } + listOfNotNull(state.sending?.origin) + state.panelPreparations.map { it.origin }).toSet()
        writeReceipts(sessionId, (readReceipts(sessionId) + delivered).distinct())
        val dir = directory(sessionId)
        check(!dir.exists() || dir.deleteRecursively()) { "无法删除会话消息队列" }
        deletedSessions += sessionId
        preparedEdits.remove(sessionId)
        states.getValue(sessionId).value = ComposerQueueState(deliveredPanelMessages = delivered.toList())
        delivered.forEach { notifyPanel(sessionId, it, true) }
        (origins - delivered).forEach { notifyPanel(sessionId, it, false) }
    }
}
