package com.promenar.infinity.ui.chat.manager

import com.promenar.infinity.data.model.BillingUsage
import com.promenar.infinity.data.model.RagUsage
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionStats
import com.promenar.infinity.data.model.TokenMetric
import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.data.rag.EmbeddingClient
import com.promenar.infinity.data.rag.RecursiveCharacterTextSplitter
import com.promenar.infinity.data.rag.VectorStore
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class PostProcessorParams(
    val sessionId: String,
    val assistantMsgId: String,
    val userMsgId: String,
    val userContent: String,
    val assistantContent: String,
    val agent: com.promenar.infinity.data.model.Agent,
    val session: Session,
    val ragEnabled: Boolean,
    val ragUsage: RagUsage? = null,
    val accumulatedUsage: TokenUsage? = null,
    val totalContextTokens: Int = 0,
    val modelId: String,
    val providerId: String? = null,
    val providerName: String? = null
)

class PostProcessor(
    private val store: ChatStore,
    private val sessionManager: SessionManager,
    private val messageManager: MessageManager,
    private val embeddingClient: EmbeddingClient? = null,
    private val vectorStore: VectorStore? = null,
    private val textSplitter: RecursiveCharacterTextSplitter? = null,
    /** 模型标题在此异步生成，不拖住本轮收尾；为 null 时就地等待。 */
    private val titleScope: CoroutineScope? = null,
) {
    private var onGenerateTitle: (suspend (params: PostProcessorParams) -> String?)? = null

    fun setTitleGenerator(generator: (suspend (params: PostProcessorParams) -> String?)?) {
        onGenerateTitle = generator
    }

    suspend fun updateStats(params: PostProcessorParams) {
        val finalUsage = params.accumulatedUsage ?: TokenUsage(
            input = params.totalContextTokens,
            output = estimateTokens(params.assistantContent)
        )
        val total = finalUsage.input + finalUsage.output
        val usage = finalUsage.copy(total = total)

        val billingUsage = BillingUsage(
            chatInput = TokenMetric(usage.input, params.accumulatedUsage == null),
            chatOutput = TokenMetric(usage.output, params.accumulatedUsage == null),
            ragSystem = params.ragUsage?.let { TokenMetric(it.ragSystem, it.isEstimated) } ?: TokenMetric(),
            total = total + (params.ragUsage?.ragSystem ?: 0),
            costUSD = 0.0
        )

        sessionManager.updateSession(
            params.sessionId,
            mapOf("stats" to (store.getSession(params.sessionId)?.stats ?: SessionStats())
                .copy(totalTokens = billingUsage.total, billing = billingUsage))
        )

        val isDefaultTitle = params.session.title.isBlank() ||
                params.session.title == params.agent.name ||
                params.session.title == "New Conversation" ||
                params.session.title == "New Chat" ||
                params.session.title == "新会话" ||
                params.session.title.startsWith("New Conversation") ||
                params.session.title.startsWith("New Chat")

        if (params.session.messages.size <= 2 || isDefaultTitle) {
            val fallback = fallbackTitle(params.userContent)
            sessionManager.updateSessionTitle(params.sessionId, fallback)
            val titleGenerator = onGenerateTitle ?: return
            val generate: suspend () -> Unit = {
                val generated = try {
                    titleGenerator(params)?.trim()?.takeIf { it.isNotBlank() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                // 生成期间用户已改名时保留用户的标题。
                if (generated != null && store.getSession(params.sessionId)?.title == fallback) {
                    sessionManager.updateSessionTitle(params.sessionId, generated)
                }
            }
            titleScope?.launch { generate() } ?: generate()
        }
    }

    suspend fun archiveMessagesToRag(
        sessionId: String,
        messages: List<com.promenar.infinity.data.model.Message>,
        modelId: String,
        onProgress: ((Float, String) -> Unit)? = null
    ) {
        if (messages.isEmpty()) return
        
        val msgIds = messages.map { it.id }
        messageManager.setVectorizationStatus(sessionId, msgIds, "processing")
        onProgress?.invoke(0.1f, "Preparing ${messages.size} messages")

        val client = embeddingClient
        val store = vectorStore
        val splitter = textSplitter

        if (client == null || store == null || splitter == null) {
            val missing = listOfNotNull(
                if (client == null) "EmbeddingClient" else null,
                if (store == null) "VectorStore" else null,
                if (splitter == null) "TextSplitter" else null
            ).joinToString(", ")
            InfinityLogger.log("[$TAG] archiveMessagesToRag skipped: $missing not configured — session=$sessionId msgs=${messages.size}")
            messageManager.setVectorizationStatus(sessionId, msgIds, "skipped")
            return
        }

        val archiveStart = System.currentTimeMillis()
        InfinityLogger.log("[$TAG] archiveMessagesToRag start: session=$sessionId msgs=${messages.size} msgIds=${msgIds.first().take(8)}...~${msgIds.last().take(8)}")

        try {
            val combinedText = messages.joinToString("\n\n") { msg ->
                "${msg.role.name}: ${msg.content}"
            }

            val chunks = splitter.splitText(combinedText)
            if (chunks.isEmpty()) {
                InfinityLogger.log("[$TAG] archiveMessagesToRag: 0 chunks after split, marking success — session=$sessionId")
                messageManager.setVectorizationStatus(sessionId, msgIds, "success")
                return
            }

            onProgress?.invoke(0.3f, "Split into ${chunks.size} chunks")

            val embedStart = System.currentTimeMillis()
            val embeddingResult = withContext(Dispatchers.IO) {
                client.embedDocuments(chunks)
            }
            val embedMs = System.currentTimeMillis() - embedStart
            InfinityLogger.log("[$TAG] archiveMessagesToRag embedding: ${chunks.size} chunks, dim=${embeddingResult.embeddings.firstOrNull()?.size}, time=${embedMs}ms")

            onProgress?.invoke(0.7f, "Embeddings generated")

            val vectorRecords = chunks.mapIndexed { index, chunk ->
                VectorStore.NewVectorRecord(
                    sessionId = sessionId,
                    content = chunk,
                    embedding = embeddingResult.embeddings[index],
                    metadata = """{"type":"memory","modelId":"$modelId"}""",
                    startMessageId = msgIds.first(),
                    endMessageId = msgIds.last()
                )
            }

            withContext(Dispatchers.IO) {
                store.addVectorRecords(vectorRecords)
            }

            val totalMs = System.currentTimeMillis() - archiveStart
            InfinityLogger.log("[$TAG] archiveMessagesToRag done: ${vectorRecords.size} vectors stored, session=$sessionId total=${totalMs}ms")
            onProgress?.invoke(0.95f, "Stored ${vectorRecords.size} vectors")
            messageManager.setVectorizationStatus(sessionId, msgIds, "success")
        } catch (cancelled: CancellationException) {
            messageManager.setVectorizationStatus(sessionId, msgIds, "error")
            throw cancelled
        } catch (e: Exception) {
            InfinityLogger.logError("$TAG.archiveMessagesToRag", e)
            messageManager.setVectorizationStatus(sessionId, msgIds, "error")
            throw e
        }
    }

    companion object {
        private const val TAG = "PostProcessor"
        const val MAX_TITLE_LENGTH = 10

        /** 标题字数上限：含中日韩文字时按字计，否则按字符计并在单词边界截断。 */
        const val MAX_LATIN_TITLE_LENGTH = 40

        /**
         * 依次用快速模型、当前会话模型生成标题；[send] 按稳定模型 ID 经精确路由发送并返回正文。
         * 都没有可用标题时返回 null，由调用方保留回退标题。
         */
        suspend fun generateTitleWithFallback(
            params: PostProcessorParams,
            quickModelId: String?,
            send: suspend (PromptRequest) -> String?,
        ): String? {
            val prompt = """为下面这段对话写一个简短标题，概括核心主题。
要求：
1. 使用对话所用的语言；中文不超过 10 个字，英文等其它语言不超过 6 个词；
2. 不要标点符号、引号或书名号；
3. 只输出标题本身，不要解释。

用户：${params.userContent.take(200)}
助手：${params.assistantContent.take(200)}"""
            val quickModel = quickModelId?.takeIf { it.isNotBlank() }
            val sessionModel = params.modelId.takeIf { it.isNotBlank() }
                ?: params.session.modelId?.takeIf { it.isNotBlank() }
            // 优先使用设置中的“快速模型”，没有标题时降级为当前会话模型。
            return listOfNotNull(quickModel, sessionModel.takeIf { it != quickModel }).firstNotNullOfOrNull { model ->
                callTitleLlm(model, prompt, send, params.sessionId)
            }
        }

        private suspend fun callTitleLlm(
            modelId: String,
            prompt: String,
            send: suspend (PromptRequest) -> String?,
            sessionId: String,
        ): String? = try {
            withTimeout(TITLE_TIMEOUT_MS) {
                cleanTitle(
                    send(
                        PromptRequest(
                            messages = listOf(ProtocolMessage(role = "user", content = prompt)),
                            model = modelId,
                            // 思考型模型的推理也计入输出上限。
                            maxTokens = 2048,
                            stream = false,
                            sessionId = sessionId,
                        )
                    )
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            InfinityLogger.logError(TAG, e)
            null
        }

        private const val TITLE_TIMEOUT_MS = 20_000L

        internal fun cleanTitle(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val firstLine = raw.lines().firstOrNull { it.isNotBlank() } ?: return null
            val stripped = firstLine
                .replace(Regex("""^(?:[#*\s\-:："“'《（【]|(?:会话标题|标题|主题|Topic|Title)\s*[:：])+"""), "")
                .replace(Regex("""[#*\s\-:："”'》）】.。]+$"""), "")
                .replace(Regex("""[《》"“”：:，,。！？!?·\n\r\t]"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (stripped.isBlank()) return null
            return limitTitle(stripped)
        }

        /** 模型标题不可用时取用户首条消息。 */
        fun fallbackTitle(userContent: String): String =
            cleanTitle(userContent.trim().replace(Regex("""[\n\r\t]+"""), " ")) ?: "会话"

        private fun limitTitle(title: String): String {
            if (title.any { isCjkCodePoint(it.code) }) return title.take(MAX_TITLE_LENGTH).trim()
            if (title.length <= MAX_LATIN_TITLE_LENGTH) return title
            val cut = title.take(MAX_LATIN_TITLE_LENGTH + 1).substringBeforeLast(' ').trimEnd(' ', '.', ';', '-')
            return cut.ifBlank { title.take(MAX_LATIN_TITLE_LENGTH) }
        }

        fun estimateTokens(text: String): Int {
            if (text.isEmpty()) return 0
            // 英文 BPE ≈ 4 字符/token；CJK ≈ 1.5 字符/token（统一按 4 字符会低估中文 2–3 倍）
            var cjk = 0
            var other = 0
            for (ch in text) {
                if (isCjkCodePoint(ch.code)) cjk++ else other++
            }
            val estimated = (other / 4.0) + (cjk / 1.5)
            return estimated.toInt().coerceAtLeast(1)
        }

        private fun isCjkCodePoint(code: Int): Boolean =
            code in 0x2E80..0x9FFF || code in 0x3400..0x4DBF ||
                code in 0xF900..0xFAFF || code in 0x3000..0x303F || code in 0xFF00..0xFFEF
    }
}
