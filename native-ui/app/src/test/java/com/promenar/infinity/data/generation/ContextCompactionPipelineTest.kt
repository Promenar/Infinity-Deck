package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolCall
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.data.repository.ContextFoldCache
import com.promenar.infinity.data.repository.ContextFoldStore
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContextCompactionPipelineTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `只读预览没有缓存时保留请求且不创建缓存根目录`() = runTest {
        val root = temporaryDirectory.resolve("context-folds").toFile()
        val session = toolSession(6)
        val original = prompt(session)

        val result = ContextCompactionPipeline(ContextFoldStore(root)).preview(session, original)

        assertThat(result.prompt).isEqualTo(original)
        assertThat(result.foldedToolResults).isEqualTo(0)
        assertThat(result.newlyFoldedToolResults).isEqualTo(0)
        assertThat(result.summarizedTurns).isEqualTo(0)
        assertThat(result.beforeTokens).isEqualTo(result.afterTokens)
        assertThat(result.summaryFailed).isFalse()
        assertThat(root.exists()).isFalse()
    }

    @Test
    fun `只读预览正文不匹配时不删除旧缓存或修改目录内容`() = runTest {
        val root = temporaryDirectory.resolve("context-folds").toFile()
        val store = ContextFoldStore(root)
        val originalSession = toolSession(4)
        val source = originalSession.messages.first { it.toolCallId == "call-0" }
        store.put(originalSession.id, source.id, source.content, "cached fact")
        val filesBefore = root.walkTopDown().associate {
            it.relativeTo(root).path to (it.lastModified() to if (it.isFile) it.readText() else null)
        }
        val changed = originalSession.copy(messages = originalSession.messages.map {
            if (it.id == source.id) it.copy(content = "changed body ".repeat(200)) else it
        })

        val result = ContextCompactionPipeline(store).preview(changed, prompt(changed))

        assertThat(result.prompt).isEqualTo(prompt(changed))
        assertThat(result.foldedToolResults).isEqualTo(0)
        assertThat(root.walkTopDown().associate {
            it.relativeTo(root).path to (it.lastModified() to if (it.isFile) it.readText() else null)
        }).isEqualTo(filesBefore)
        assertThat(ContextFoldStore(root).peek(originalSession.id, source.id, source.content)).isEqualTo("cached fact")
    }

    @Test
    fun `只读预览仅用peek应用缓存并保留当前输出引用及全部协议结构`() = runTest {
        val session = toolSession(4)
        val source = session.messages.first { it.toolCallId == "call-0" }
        val reference = "output_id=out_" + "e".repeat(64)
        val request = prompt(session).let { base ->
            base.copy(
                maxTokens = 4_096,
                tools = listOf(ProtocolTool(function = ProtocolToolFunction("read", "Read a file", "{}"))),
                messages = base.messages.map {
                    if (it.toolCallId == "call-0") it.copy(content = "HEAD" + "x".repeat(1_000) + reference + "TAIL") else it
                },
            )
        }
        val cache = object : ContextFoldCache {
            override suspend fun get(sessionId: String, messageId: String, originalContent: String): String? =
                error("只读预览不能调用会失效缓存的读取")
            override suspend fun peek(sessionId: String, messageId: String, originalContent: String): String? =
                if (sessionId == session.id && messageId == source.id && originalContent == source.content) "cached fact" else null
            override suspend fun put(sessionId: String, messageId: String, originalContent: String, foldedContent: String): Unit =
                error("只读预览不能写入缓存")
            override suspend fun deleteSession(sessionId: String): Unit = error("只读预览不能清理缓存")
        }

        val result = ContextCompactionPipeline(cache).preview(session, request)

        val before = request.messages.first { it.toolCallId == "call-0" }
        val after = result.prompt.messages.first { it.toolCallId == "call-0" }
        assertThat(after.content).contains("cached fact")
        assertThat(after.content).contains(reference)
        assertThat(after.copy(content = before.content)).isEqualTo(before)
        assertThat(result.prompt.messages.filter { it.toolCallId != "call-0" })
            .isEqualTo(request.messages.filter { it.toolCallId != "call-0" })
        assertThat(result.prompt.copy(messages = request.messages)).isEqualTo(request)
        assertThat(result.foldedToolResults).isEqualTo(1)
        assertThat(result.newlyFoldedToolResults).isEqualTo(0)
        assertThat(result.summarizedTurns).isEqualTo(0)
        assertThat(result.beforeTokens).isEqualTo(result.afterTokens)
    }

    @Test
    fun `低于阈值时保持全部请求消息且不调用折叠或摘要`() = runTest {
        val session = toolSession(6)
        val original = prompt(session)

        val result = ContextCompactionPipeline().compact(
            input(session), ::prompt,
            summarizeTool = { error("不应折叠") },
            summarizeHistory = { _, _ -> error("不应摘要") },
        )

        assertThat(result.prompt).isEqualTo(original)
        assertThat(result.foldedToolResults).isEqualTo(0)
        assertThat(result.newlyFoldedToolResults).isEqualTo(0)
        assertThat(result.summarizedTurns).isEqualTo(0)
        assertThat(result.afterTokens).isEqualTo(result.beforeTokens)
    }

    @Test
    fun `自动折叠从最旧结果开始并在降到预算内后停止`() = runTest {
        val session = toolSession(6)
        val original = prompt(session)
        val summarized = mutableListOf<String>()

        val result = ContextCompactionPipeline().compact(
            // 窗口足够大，使阈值（2700）而非安全余量决定预算。
            input(session, context = 27_000, threshold = 0.1), ::prompt,
            summarizeTool = { summarized += it; "保留事实" },
            summarizeHistory = { _, _ -> error("预算已足够，不应摘要") },
        )

        assertThat(result.beforeTokens).isGreaterThan(2_700)
        assertThat(result.afterTokens).isAtMost(2_700)
        assertThat(summarized).hasSize(1)
        assertThat(summarized.single()).startsWith("HEAD-0")
        assertThat(result.prompt.messages.first { it.toolCallId == "call-0" }.content).contains("保留事实")
        assertThat(result.prompt.messages.filter { it.toolCallId != "call-0" })
            .isEqualTo(original.messages.filter { it.toolCallId != "call-0" })
        assertThat(result.prompt.messages.map { it.toolCallId to it.toolCalls })
            .isEqualTo(original.messages.map { it.toolCallId to it.toolCalls })
        assertThat(result.prompt.messages.first { it.toolCallId == "call-0" }.copy(
            content = original.messages.first { it.toolCallId == "call-0" }.content,
        )).isEqualTo(original.messages.first { it.toolCallId == "call-0" })
        assertThat(result.foldedToolResults).isEqualTo(1)
        assertThat(result.newlyFoldedToolResults).isEqualTo(1)
        assertThat(prompt(session)).isEqualTo(original)
    }

    @Test
    fun `手动模式忽略阈值折叠全部候选且保护最近三个完整工具调用轮`() = runTest {
        val session = toolSession(4, resultsPerRound = 2)
        val original = prompt(session)
        val stages = mutableListOf<ContextCompactionStage>()

        val result = ContextCompactionPipeline().compact(
            input(session, manual = true), ::prompt,
            summarizeTool = { "short fact" }, onProgress = stages::add,
        )

        assertThat(result.newlyFoldedToolResults).isEqualTo(2)
        assertThat(result.prompt.messages.filter { it.toolCallId?.startsWith("call-0") == true }
            .all { it.content.contains("short fact") }).isTrue()
        assertThat(result.prompt.messages.takeLast(9)).isEqualTo(original.messages.takeLast(9))
        assertThat(stages).containsExactly(ContextCompactionStage.FOLDING, ContextCompactionStage.COMPLETE).inOrder()
        assertThat(session.messages.filter { it.role == MessageRole.TOOL }.all { it.content.length > 512 })
            .isTrue()
    }

    @Test
    fun `工具结果长度边界不发起折叠`() = runTest {
        val session = toolSession(4).let { base ->
            base.copy(messages = base.messages.map {
                if (it.toolCallId == "call-0") it.copy(content = "x".repeat(512)) else it
            })
        }

        val result = ContextCompactionPipeline().compact(
            input(session, manual = true), ::prompt, summarizeTool = { error("边界结果不应折叠") },
        )

        assertThat(result.newlyFoldedToolResults).isEqualTo(0)
        assertThat(result.prompt).isEqualTo(prompt(session))
    }

    @Test
    fun `缓存命中在阈值检查前应用且计数区分缓存和新增`() = runTest {
        val session = toolSession(4)
        val original = session.messages.first { it.toolCallId == "call-0" }
        val cache = MemoryFoldCache().apply { put(session.id, original.id, original.content, "cached fact") }

        val result = ContextCompactionPipeline(cache).compact(
            input(session), ::prompt,
            summarizeTool = { error("缓存命中不应调用模型") },
            summarizeHistory = { _, _ -> error("不应摘要") },
        )

        assertThat(result.prompt.messages.first { it.toolCallId == "call-0" }.content).isEqualTo("cached fact")
        assertThat(result.foldedToolResults).isEqualTo(1)
        assertThat(result.newlyFoldedToolResults).isEqualTo(0)
        assertThat(result.beforeTokens).isEqualTo(result.afterTokens)
    }

    @Test
    fun `重新发送的已归档当前轮工具结果仍按原消息持久缓存`() = runTest {
        val session = toolSession(4).let { original ->
            original.copy(messages = original.messages.map { it.copy(isArchived = true) })
        }
        val source = session.messages.first { it.toolCallId == "call-0" }
        val root = temporaryDirectory.resolve("current-turn-folds").toFile()
        val builder: suspend (Session) -> PromptRequest = { current ->
            prompt(current.copy(messages = current.messages.map { it.copy(isArchived = false) }))
        }
        val first = ContextCompactionPipeline(ContextFoldStore(root)).compact(
            input(session, manual = true), builder, summarizeTool = { "当前轮工具事实" },
        )

        assertThat(first.newlyFoldedToolResults).isEqualTo(1)
        assertThat(ContextFoldStore(root).peek(session.id, source.id, source.content)).isNotNull()
        var requests = 0
        val restored = ContextCompactionPipeline(ContextFoldStore(root)).compact(
            input(session, manual = true), builder, summarizeTool = { requests++; "不应产生新摘要" },
        )
        assertThat(requests).isEqualTo(0)
        assertThat(restored.newlyFoldedToolResults).isEqualTo(0)
        assertThat(restored.prompt).isEqualTo(first.prompt)
        assertThat(session.messages.all { it.isArchived }).isTrue()
    }

    @Test
    fun `缓存键使用原始消息正文且命中后保留当前输出引用`() = runTest {
        val outputId = "output_id=out_" + "a".repeat(64)
        val nextOutputId = "output_id=out_" + "b".repeat(64)
        val session = toolSession(4).let { base ->
            base.copy(messages = base.messages.map {
                if (it.toolCallId == "call-0") it.copy(content = "raw".repeat(7_000)) else it
            })
        }
        val source = session.messages.first { it.toolCallId == "call-0" }
        val cache = MemoryFoldCache()
        fun preview(current: Session, reference: String) = prompt(current).let { request ->
            request.copy(messages = request.messages.map {
                if (it.toolCallId == "call-0") it.copy(content = "HEAD" + "p".repeat(1_000) + reference + "TAIL") else it
            })
        }
        val pipeline = ContextCompactionPipeline(cache)
        pipeline.compact(input(session, manual = true), { preview(it, outputId) }, summarizeTool = { "fact" })

        val result = pipeline.compact(
            input(session), { preview(it, nextOutputId) }, summarizeTool = { error("应命中原始正文缓存") },
        )

        assertThat(cache.entries.keys.single().third).isEqualTo(source.content)
        assertThat(result.prompt.messages.first { it.toolCallId == "call-0" }.content).contains(nextOutputId)
        assertThat(result.newlyFoldedToolResults).isEqualTo(0)
    }

    @Test
    fun `无效或增加token的缓存不会替换完整结果`() = runTest {
        val session = toolSession(4)
        val source = session.messages.first { it.toolCallId == "call-0" }
        val cache = MemoryFoldCache().apply { put(session.id, source.id, source.content, "界".repeat(3_000)) }

        val result = ContextCompactionPipeline(cache).compact(input(session), ::prompt)

        assertThat(result.prompt).isEqualTo(prompt(session))
        assertThat(result.foldedToolResults).isEqualTo(0)
    }

    @Test
    fun `缓存IO失败不影响生成请求的折叠结果`() = runTest {
        val cache = object : ContextFoldCache {
            override suspend fun get(sessionId: String, messageId: String, originalContent: String): String? =
                throw IOException("unavailable")
            override suspend fun put(sessionId: String, messageId: String, originalContent: String, foldedContent: String) {
                throw IOException("unavailable")
            }
            override suspend fun deleteSession(sessionId: String) = Unit
        }

        val result = ContextCompactionPipeline(cache).compact(
            input(toolSession(4), manual = true), ::prompt, summarizeTool = { "fact" },
        )

        assertThat(result.newlyFoldedToolResults).isEqualTo(1)
        assertThat(result.prompt.messages.first { it.role == "tool" }.content).contains("fact")
    }

    @Test
    fun `工具摘要超时后保留头尾及位于中间的输出引用`() = runTest {
        val reference = "output_id=out_" + "c".repeat(64)
        val session = toolSession(4).let { base ->
            base.copy(messages = base.messages.map {
                if (it.toolCallId == "call-0") it.copy(content = "HEAD" + "x".repeat(1_000) + reference + "y".repeat(1_000) + "TAIL") else it
            })
        }

        val result = ContextCompactionPipeline().compact(
            input(session, manual = true), ::prompt,
            summarizeTool = { delay(16_000); "late fact" },
        )

        val folded = result.prompt.messages.first { it.role == "tool" }.content
        assertThat(testScheduler.currentTime).isEqualTo(15_000)
        assertThat(folded).contains("HEAD")
        assertThat(folded).contains("TAIL")
        assertThat(folded).contains(reference)
        assertThat(folded).doesNotContain("late fact")
        assertThat(result.afterTokens).isLessThan(result.beforeTokens)
    }

    @Test
    fun `模型摘要被限制长度且只有减少token的内容才会应用`() = runTest {
        val session = toolSession(4)
        val result = ContextCompactionPipeline().compact(
            input(session, manual = true), ::prompt, summarizeTool = { "z".repeat(1_500) },
        )

        assertThat(result.prompt.messages.first { it.role == "tool" }.content.count { it == 'z' }).isAtMost(768)
        assertThat(result.afterTokens).isLessThan(result.beforeTokens)

        val shortAscii = session.copy(messages = session.messages.map {
            if (it.toolCallId == "call-0") it.copy(content = "HEAD" + "x".repeat(592) + "TAIL") else it
        })
        val fallback = ContextCompactionPipeline().compact(
            input(shortAscii, manual = true), ::prompt, summarizeTool = { "界".repeat(300) },
        )
        val folded = fallback.prompt.messages.first { it.role == "tool" }.content
        assertThat(folded).doesNotContain("界")
        assertThat(folded).contains("HEAD")
        assertThat(folded).contains("TAIL")
        assertThat(fallback.afterTokens).isLessThan(fallback.beforeTokens)
    }

    @Test
    fun `预算同时包含系统指令工具定义和消息`() = runTest {
        val session = twoTurns("old text")
        val build: suspend (Session) -> PromptRequest = { current ->
            prompt(current).copy(
                messages = listOf(ProtocolMessage("system", "s".repeat(2_000))) + prompt(current).messages.drop(1),
                tools = listOf(ProtocolTool(function = ProtocolToolFunction("read", "t".repeat(1_600), "{}"))),
            )
        }

        val result = ContextCompactionPipeline().compact(
            input(session, keep = 1, context = 3_000, threshold = 0.2), build,
            summarizeHistory = { current, messages -> archive(current, messages) },
        )

        assertThat(result.beforeTokens).isGreaterThan(800)
        assertThat(result.summarizedTurns).isEqualTo(1)
    }

    @Test
    fun `输出预留比阈值更严格时按输出预留触发摘要`() = runTest {
        val session = twoTurns("x".repeat(1_400))

        val result = ContextCompactionPipeline().compact(
            input(session, keep = 1, context = 2_000, reserve = 900, threshold = 0.95), ::prompt,
            summarizeHistory = { current, messages -> archive(current, messages) },
        )

        assertThat(result.beforeTokens).isGreaterThan(300)
        assertThat(result.beforeTokens).isLessThan(1_900)
        assertThat(result.summarizedTurns).isEqualTo(1)
    }

    @Test
    fun `估算等于预算不触发而小数阈值预算向下取整`() = runTest {
        val session = twoTurns("old")
        val atBudget: suspend (Session) -> PromptRequest = {
            PromptRequest(listOf(ProtocolMessage("user", "x".repeat(184))), "model")
        }
        val unchanged = ContextCompactionPipeline().compact(
            input(session, keep = 1, context = 10_000, threshold = 0.00505), atBudget,
            summarizeHistory = { _, _ -> error("等于预算不应摘要") },
        )
        assertThat(unchanged.beforeTokens).isEqualTo(50)
        assertThat(unchanged.summarizedTurns).isEqualTo(0)

        val overBudget: suspend (Session) -> PromptRequest = {
            PromptRequest(listOf(ProtocolMessage("user", "x".repeat(188))), "model")
        }
        val compacted = ContextCompactionPipeline().compact(
            input(session, keep = 1, context = 10_000, threshold = 0.00505), overBudget,
            summarizeHistory = { current, messages -> archive(current, messages) },
        )
        assertThat(compacted.beforeTokens).isEqualTo(51)
        assertThat(compacted.summarizedTurns).isEqualTo(1)
    }

    @Test
    fun `模型窗口未知时使用128000的回退而不提前摘要`() = runTest {
        val session = twoTurns("x".repeat(60_000))

        val result = ContextCompactionPipeline().compact(
            input(session, keep = 1, context = null), ::prompt,
            summarizeHistory = { _, _ -> error("未知窗口应使用128000回退") },
        )

        assertThat(result.summarizedTurns).isEqualTo(0)
        assertThat(result.prompt).isEqualTo(prompt(session))
    }

    @Test
    fun `历史候选保留当前加最近四轮并把前置非用户消息并入最早轮`() {
        val messages = buildList {
            add(Message("leading", MessageRole.ASSISTANT, "前置内容"))
            repeat(6) { turn ->
                add(Message("u$turn", MessageRole.USER, "问题$turn"))
                add(Message("a$turn", MessageRole.ASSISTANT, "回答$turn"))
                if (turn == 0) add(Message("archived", MessageRole.TOOL, "已摘要", isArchived = true))
            }
        }

        assertThat(historyCompactionCandidates(messages, "u5", 5).map(Message::id))
            .containsExactly("leading", "u0", "a0").inOrder()
        assertThat(historyCompactionCandidates(messages, null, 5).map(Message::id))
            .containsExactly("leading", "u0", "a0").inOrder()
    }

    @Test
    fun `当前轮及之后的消息都不会成为历史候选且不切开工具调用轮`() {
        val messages = listOf(
            Message("u0", MessageRole.USER, "old"),
            Message("a0", MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("call", "read", "{}"))),
            Message("t0", MessageRole.TOOL, "result", toolCallId = "call"),
            Message("u1", MessageRole.USER, "current"),
            Message("a1", MessageRole.ASSISTANT, "answer"),
            Message("u2", MessageRole.USER, "later"),
        )

        assertThat(historyCompactionCandidates(messages, "u1", 1).map(Message::id))
            .containsExactly("u0", "a0", "t0").inOrder()
        assertThat(historyCompactionCandidates(messages.takeLast(1), "u2", 1)).isEmpty()
        assertThat(historyCompactionCandidates(listOf(messages[1]), null, 1)).isEmpty()
    }

    @Test
    fun `历史摘要成功后由更新会话重建请求且源消息保持完整`() = runTest {
        val session = twoTurns("old ".repeat(1_000))
        val built = mutableListOf<Session>()
        val stages = mutableListOf<ContextCompactionStage>()

        val result = ContextCompactionPipeline().compact(
            input(session, keep = 1, context = 1_800),
            { built += it; prompt(it) },
            summarizeHistory = { current, messages ->
                assertThat(messages.map(Message::id)).containsExactly("old-user", "old-assistant").inOrder()
                archive(current, messages)
            },
            onProgress = stages::add,
        )

        assertThat(result.summarizedTurns).isEqualTo(1)
        assertThat(result.summaryFailed).isFalse()
        assertThat(built).hasSize(2)
        assertThat(result.prompt.messages.map(ProtocolMessage::role)).containsExactly("system", "user").inOrder()
        assertThat(result.prompt.messages.first().content).contains("history fact")
        assertThat(result.prompt.messages.last().content).isEqualTo("current question")
        assertThat(session.messages.none(Message::isArchived)).isTrue()
        assertThat(stages).containsAtLeast(ContextCompactionStage.SUMMARIZING, ContextCompactionStage.COMPLETE).inOrder()
    }

    @Test
    fun `历史摘要返回null或抛异常时保持原文且同一轮不重试`() = runTest {
        listOf(false, true).forEach { throws ->
            val session = twoTurns("old ".repeat(1_000))
            val state = ContextCompactionTurnState()
            val request = input(session, keep = 1, context = 1_800).copy(turnState = state)
            var calls = 0
            val summarize: suspend (Session, List<Message>) -> Session? = { _, _ ->
                calls++
                if (throws) throw IllegalStateException("unavailable")
                null
            }
            val pipeline = ContextCompactionPipeline()

            val first = pipeline.compact(request, ::prompt, summarizeHistory = summarize)
            val second = pipeline.compact(request, ::prompt, summarizeHistory = summarize)

            assertThat(calls).isEqualTo(1)
            assertThat(state.historySummaryAttempted).isTrue()
            assertThat(first.summaryFailed).isTrue()
            assertThat(first.summarizedTurns).isEqualTo(0)
            assertThat(first.prompt).isEqualTo(prompt(session))
            assertThat(second.prompt).isEqualTo(first.prompt)
        }
    }

    @Test
    fun `历史摘要返回未更新会话不会误报成功`() = runTest {
        val session = twoTurns("old")

        val result = ContextCompactionPipeline().compact(
            input(session, keep = 1, manual = true), ::prompt,
            summarizeHistory = { current, _ -> current },
        )

        assertThat(result.summaryFailed).isTrue()
        assertThat(result.summarizedTurns).isEqualTo(0)
        assertThat(result.prompt).isEqualTo(prompt(session))
    }

    @Test
    fun `手动压缩同时执行工具折叠和历史摘要`() = runTest {
        val old = toolSession(4)
        val session = old.copy(messages = old.messages + Message("current", MessageRole.USER, "current question"))

        val result = ContextCompactionPipeline().compact(
            input(session, keep = 1, manual = true).copy(currentTurnId = "current"), ::prompt,
            summarizeTool = { "tool fact" },
            summarizeHistory = { current, messages -> archive(current, messages) },
        )

        assertThat(result.newlyFoldedToolResults).isEqualTo(1)
        assertThat(result.summarizedTurns).isEqualTo(1)
        assertThat(result.prompt.messages.map(ProtocolMessage::role)).containsExactly("system", "user").inOrder()
        assertThat(result.foldedToolResults).isEqualTo(0)
    }

    @Test
    fun `手动压缩无候选时返回零结果并完成进度`() = runTest {
        val session = Session("session", "agent", messages = listOf(Message("current", MessageRole.USER, "question")))
        val stages = mutableListOf<ContextCompactionStage>()

        val result = ContextCompactionPipeline().compact(
            input(session, manual = true).copy(currentTurnId = "current"), ::prompt,
            summarizeTool = { error("没有候选") },
            summarizeHistory = { _, _ -> error("没有候选") }, onProgress = stages::add,
        )

        assertThat(result.newlyFoldedToolResults).isEqualTo(0)
        assertThat(result.summarizedTurns).isEqualTo(0)
        assertThat(stages.last()).isEqualTo(ContextCompactionStage.COMPLETE)
    }

    @Test
    fun `工具折叠和历史摘要的取消都会向调用者传播`() = runTest {
        var cancellations = 0
        try {
            ContextCompactionPipeline().compact(
                input(toolSession(4), manual = true), ::prompt,
                summarizeTool = { throw CancellationException("stop tool") },
            )
        } catch (_: CancellationException) {
            cancellations++
        }
        try {
            ContextCompactionPipeline().compact(
                input(twoTurns("old"), keep = 1, manual = true), ::prompt,
                summarizeHistory = { _, _ -> throw CancellationException("stop history") },
            )
        } catch (_: CancellationException) {
            cancellations++
        }

        assertThat(cancellations).isEqualTo(2)
    }

    @Test
    fun `头尾兜底不会截断代理对且保留位于正文中部的输出引用`() {
        val reference = "output_id=out_" + "d".repeat(64)
        val content = "A😀" + "x".repeat(100) + reference + "y".repeat(100) + "😀Z"

        val folded = ContextCompactionPipeline.headTail(content, 4)

        assertThat(folded).startsWith("A\n")
        assertThat(folded).contains("\nZ")
        assertThat(folded).contains(reference)
        assertThat(folded.none(Char::isSurrogate)).isTrue()
        assertThat(ContextCompactionPipeline.headTail("😀", 2)).isEqualTo("😀")
    }

    private fun input(
        session: Session,
        keep: Int = 5,
        context: Int? = 128_000,
        reserve: Int = 0,
        threshold: Double = 0.8,
        manual: Boolean = false,
    ) = ContextCompactionInput(
        session = session,
        currentTurnId = session.messages.lastOrNull { it.role == MessageRole.USER }?.id,
        keepRecentTurns = keep,
        threshold = threshold,
        window = PromptWindow(context, reserve),
        manual = manual,
    )

    private fun toolSession(rounds: Int, resultsPerRound: Int = 1): Session = Session(
        "session", "agent", messages = buildList {
            add(Message("user", MessageRole.USER, "task"))
            repeat(rounds) { round ->
                val calls = (0 until resultsPerRound).map { result ->
                    ToolCall(if (resultsPerRound == 1) "call-$round" else "call-$round-$result", "read", "{}")
                }
                add(Message("assistant-$round", MessageRole.ASSISTANT, "", toolCalls = calls))
                calls.forEachIndexed { index, call ->
                    add(Message("tool-$round-$index", MessageRole.TOOL, "HEAD-$round" + "x".repeat(2_000) + "TAIL-$round",
                        toolCallId = call.id, name = "read", thoughtSignature = "signature-$round"))
                }
            }
        },
    )

    private fun twoTurns(oldContent: String) = Session(
        "session", "agent", messages = listOf(
            Message("old-user", MessageRole.USER, oldContent),
            Message("old-assistant", MessageRole.ASSISTANT, "old answer"),
            Message("current", MessageRole.USER, "current question"),
        ),
    )

    private fun prompt(session: Session) = PromptRequest(
        model = "session-model",
        messages = listOf(ProtocolMessage("system", "instructions ${session.summary.orEmpty()}")) +
            session.messages.filterNot(Message::isArchived).map { message ->
                ProtocolMessage(
                    role = message.role.toSerializedName(), content = message.content,
                    toolCallId = message.toolCallId, name = message.name,
                    thoughtSignature = message.thoughtSignature,
                    toolCalls = message.toolCalls?.map { ProtocolToolCall(it.id, it.name, it.arguments) },
                )
            },
    )

    private fun archive(session: Session, messages: List<Message>): Session {
        val ids = messages.mapTo(mutableSetOf(), Message::id)
        return session.copy(summary = "history fact", messages = session.messages.map {
            if (it.id in ids) it.copy(isArchived = true) else it
        })
    }

    private class MemoryFoldCache : ContextFoldCache {
        val entries = mutableMapOf<Triple<String, String, String>, String>()
        override suspend fun get(sessionId: String, messageId: String, originalContent: String): String? =
            entries[Triple(sessionId, messageId, originalContent)]
        override suspend fun put(sessionId: String, messageId: String, originalContent: String, foldedContent: String) {
            entries[Triple(sessionId, messageId, originalContent)] = foldedContent
        }
        override suspend fun deleteSession(sessionId: String) {
            entries.keys.removeAll { it.first == sessionId }
        }
    }
}
