package com.promenar.infinity.data.subagent

import com.promenar.infinity.data.exec.ToolLiveOutputStore
import com.promenar.infinity.data.generation.ApproximateTokenEstimator
import com.promenar.infinity.data.generation.ContextCompactionPipeline
import com.promenar.infinity.data.model.cacheUsageAggregate
import com.promenar.infinity.domain.repository.plus
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.model.params.ReasoningEffort
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolCall
import com.promenar.infinity.data.remote.protocol.ReasoningReplay
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.data.repository.ToolInvocationIdentityFactory
import com.promenar.infinity.data.repository.ToolInvocationIdentityResolution
import com.promenar.infinity.domain.model.ExecutionModeCodec
import com.promenar.infinity.domain.tool.ToolExecutionPolicy
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import com.promenar.infinity.domain.tool.ToolLiveOutputSink
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.SystemPromptSections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 子代理请求模型的通道；路由失败时抛出带原因的异常。 */
fun interface SubagentModelClient {
    /** 每轮先确定实际请求模型；简单客户端直接使用传入的 ID。 */
    suspend fun prepare(modelId: String): PreparedSubagentModel = PreparedSubagentModel(modelId, this)

    suspend fun stream(
        modelId: String,
        messages: List<ProtocolMessage>,
        tools: List<ProtocolTool>,
        maxOutputTokens: Int,
        reasoningEffort: ReasoningEffort?,
        sessionId: String,
    ): Flow<StreamChunk>
}

/** 模型元数据与发送器绑定同一条路由，构建提示词后不再解析路由。 */
data class PreparedSubagentModel(val modelId: String, val client: SubagentModelClient)

/** 子代理模型不可用的原因，可直接展示并交回主 Agent。 */
class SubagentModelUnavailable(message: String) : IllegalStateException(message)

data class SubagentToolOutcome(val content: String, val failed: Boolean)

/** 子代理借用父会话的工具执行底座：工具集、授权目标、白名单与技能执行。 */
interface SubagentToolHost {
    suspend fun tools(session: Session): List<ProtocolTool>
    suspend fun target(session: Session, tool: ProtocolTool, arguments: String): String?
    suspend fun isGranted(sessionId: String, toolName: String, target: String): Boolean
    suspend fun remember(sessionId: String, toolName: String, target: String)
    fun preview(toolName: String, arguments: JsonObject): String
    suspend fun execute(tool: ProtocolTool, arguments: JsonObject, context: SkillExecutionContext): SubagentToolOutcome
}

/**
 * 在调用方持有的协程内运行子代理；上下文持久化与生命周期由应用级运行器管理。
 * 子代理使用独立上下文、父会话的执行模式和受限工具集，最终正文完整交回。
 */
class SubagentRunner(
    private val models: SubagentModelClient,
    private val host: SubagentToolHost,
    private val approvals: SubagentApprovalBroker,
    private val liveOutput: ToolLiveOutputStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val contextLimitText: String = CONTEXT_LIMIT,
    private val modelSupportsToolCalling: (String) -> Boolean = { true },
    private val modelUnavailableText: (String) -> String = SubagentRuntimeTexts().modelUnavailable,
    private val modelToolCallingUnsupportedText: String = SubagentRuntimeTexts().modelToolCallingUnsupported,
) {
    /** [session] 每轮与每次工具调用前重新读取，父会话中途收紧执行模式或停用工具时立即生效；返回 null 表示会话已删除。 */
    suspend fun run(
        parent: SkillExecutionContext,
        session: suspend () -> Session?,
        stepId: String,
        initial: SubagentRun,
        preset: SubagentPreset?,
        execution: SubagentExecution = SubagentExecution(),
        publish: suspend (SubagentRun, Boolean) -> Unit,
    ): SubagentRun {
        var run = initial
        val modelSessionId = "subagent:${parent.sessionId}:$stepId:${initial.index}"
        suspend fun update(next: SubagentRun, persist: Boolean = true) {
            run = next.copy(events = next.events.takeLast(SubagentLimits.MAX_EVENTS))
            publish(run, persist)
        }
        suspend fun event(kind: SubagentEvent.Kind, text: String, toolName: String? = null, failed: Boolean = false, persist: Boolean = true) =
            update(run.copy(events = run.events + SubagentEvent(kind, text.take(SubagentLimits.MAX_EVENT_CHARS), toolName, failed, clock())), persist)

        // 循环只在完成、失败或取消时离开。
        suspend fun loop(): SubagentRun {
            val first = session() ?: return finish(run, SubagentStatus.CANCELLED, error = SESSION_GONE, publish = ::update)
            val restored = execution.load() ?: SubagentExecutionState()
            val pendingOnLoad = restored.pendingInstructions
            var state = restored.copy(
                pendingInstructions = emptyList(),
                executionStartStep = if (execution.resume) restored.completedSteps else restored.executionStartStep,
                wrapUpAfterStep = if (execution.resume) null else restored.wrapUpAfterStep,
                inputTokens = maxOf(restored.inputTokens, initial.inputTokens.toLong()),
                outputTokens = maxOf(restored.outputTokens, initial.outputTokens.toLong()),
                tokensEstimated = restored.tokensEstimated || initial.tokensEstimated,
            )
            val messages = restored.messages.toMutableList()
            if (messages.isEmpty()) {
                messages += ProtocolMessage("system", systemPrompt(preset, availableTools(host.tools(first), initial.toolProfile, initial.allowedTools), first.executionMode, first.runMode, null))
                messages += ProtocolMessage("user", initial.prompt)
            } else {
                // 中断时无法确定未落盘的工具是否已产生副作用，补齐协议配对，不自动重做。
                completeInterruptedCalls(messages)
            }
            val consumed = restored.consumedInstructionIds.toMutableSet()
            val consumedUnidentified = mutableSetOf<SubagentInstruction>()
            suspend fun saveState() {
                state = state.copy(messages = messages.toList(), pendingInstructions = emptyList(), consumedInstructionIds = consumed.toList())
                execution.save(state)
            }
            var lastUsageCheckpoint: Long? = null
            var firstBoundary = true
            saveState()
            while (true) {
                currentCoroutineContext().ensureActive()
                val current = session() ?: return finish(run, SubagentStatus.CANCELLED, error = SESSION_GONE, publish = ::update)
                val inbox = (if (firstBoundary) pendingOnLoad else emptyList()) + execution.drainInbox()
                firstBoundary = false
                for (instruction in inbox) {
                    if (instruction.id.isNotEmpty() && !consumed.add(instruction.id)) continue
                    if (instruction.id.isEmpty() && !consumedUnidentified.add(instruction)) continue
                    val at = instruction.at.takeIf { it > 0L } ?: clock()
                    messages += ProtocolMessage("user", if (instruction.wrapUp) "$WRAP_UP_PROMPT\n\n${instruction.message}".trimEnd() else instruction.message)
                    val additions = buildList {
                        if (instruction.message.isNotBlank()) add(SubagentEvent(SubagentEvent.Kind.PARENT_MESSAGE, instruction.message.take(SubagentLimits.MAX_EVENT_CHARS), at = at, by = SubagentEvent.Actor.PARENT))
                        if (instruction.wrapUp) add(SubagentEvent(SubagentEvent.Kind.WRAP_UP, instruction.message.take(SubagentLimits.MAX_EVENT_CHARS), at = at, by = SubagentEvent.Actor.PARENT))
                    }
                    if (instruction.wrapUp && state.wrapUpAfterStep == null) state = state.copy(wrapUpAfterStep = state.completedSteps + SubagentLimits.WRAP_UP_GRACE_STEPS)
                    // 先确认完整上下文与消费 id，再发布有界事件，收件箱不会因旧快照被覆盖。
                    saveState()
                    update(run.copy(status = if (state.wrapUpAfterStep != null) SubagentStatus.WRAPPING_UP else SubagentStatus.RUNNING, events = run.events + additions))
                }
                val tools = availableTools(host.tools(current), initial.toolProfile, initial.allowedTools)
                if (!modelSupportsToolCalling(run.modelId)) throw SubagentModelUnavailable(
                    "${modelUnavailableText(run.modelId)} $modelToolCallingUnsupportedText",
                )
                val preparedModel = try { models.prepare(run.modelId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { throw SubagentModelUnavailable(modelUnavailableText(run.modelId)) }
                val system = ProtocolMessage("system", systemPrompt(preset, tools, current.executionMode, current.runMode, preparedModel.modelId))
                if (messages.firstOrNull()?.role == "system") messages[0] = system else messages.add(0, system)
                val stepsThisExecution = state.completedSteps - state.executionStartStep
                val turnLimit = run.maxTurns?.takeIf { it > 0 }
                val reachedTurns = turnLimit != null && stepsThisExecution >= turnLimit
                val reachedSteps = run.maxSteps > 0 && stepsThisExecution >= run.maxSteps
                val reachedTokens = run.maxTokens > 0 && state.inputTokens + state.outputTokens >= run.maxTokens
                val reachedWrapUp = state.wrapUpAfterStep?.let { state.completedSteps >= it } == true
                val finalRound = reachedTurns || reachedSteps || reachedTokens || reachedWrapUp
                if (finalRound) messages += ProtocolMessage("user", when {
                    reachedTurns -> TURN_LIMIT_PROMPT
                    reachedWrapUp -> WRAP_UP_FINAL_PROMPT
                    else -> FINAL_ANSWER_PROMPT
                })
                saveState()
                val round = state.completedSteps + 1
                val roundTools = if (finalRound) emptyList() else tools
                val request = compactForRequest(messages, roundTools, execution.contextWindow(run.modelId))
                if (request == null) {
                    event(SubagentEvent.Kind.ERROR, contextLimitText, failed = true)
                    return finish(run, SubagentStatus.FAILED, error = contextLimitText, publish = ::update)
                }
                update(run.copy(status = if (state.wrapUpAfterStep != null) SubagentStatus.WRAPPING_UP else SubagentStatus.RUNNING, step = round, currentTool = null, liveOutputKey = null), persist = false)
                val roundInput = state.inputTokens
                val roundOutput = state.outputTokens
                val roundRead = run.cacheReadInput
                val roundWrite = run.cacheWriteInput
                val roundLongWrite = run.cacheWriteLongTtlInput
                val roundCache = run.cache
                val previousCacheRequests = run.cacheRequests
                val cacheRequestId = java.util.UUID.randomUUID().toString()
                val cacheRequestDay = java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                // 首帧用量之前重置也须识别在途请求；空快照不算有用量记录。
                update(run.copy(cacheRequests = previousCacheRequests + SubagentCacheRequestUsage(cacheRequestId, cacheRequestDay)))
                val response = streamRound(preparedModel.client, modelSessionId, preparedModel.modelId, ReasoningEffort.fromWireName(run.reasoningEffort), request.messages, roundTools, request.maxOutputTokens) { input, output, estimated, read, write, longWrite, force ->
                    // 流内用量立即更新内存，每秒保存检查点；步骤结束或取消时强制结算。
                    state = state.copy(inputTokens = roundInput + input, outputTokens = roundOutput + output, tokensEstimated = state.tokensEstimated || estimated)
                    withContext(NonCancellable) {
                        val now = clock()
                        val checkpoint = force || (lastUsageCheckpoint?.let { now - it >= 1_000L } ?: true)
                        update(run.copy(inputTokens = state.inputTokens.clamp(), outputTokens = state.outputTokens.clamp(), totalTokens = (state.inputTokens + state.outputTokens).clamp(), tokensEstimated = state.tokensEstimated,
                            cacheReadInput = sumReported(roundRead, read), cacheWriteInput = sumReported(roundWrite, write),
                            cacheWriteLongTtlInput = sumReported(roundLongWrite, longWrite),
                            cache = roundCache + com.promenar.infinity.data.model.TokenUsage(
                                input = input.clamp(), cacheReadInput = read, cacheWriteInput = write, cacheWriteLongTtlInput = longWrite,
                            ).cacheUsageAggregate(),
                            cacheRequests = previousCacheRequests + SubagentCacheRequestUsage(cacheRequestId, cacheRequestDay,
                                com.promenar.infinity.data.model.TokenUsage(input = input.clamp(), cacheReadInput = read,
                                    cacheWriteInput = write, cacheWriteLongTtlInput = longWrite)),
                        ), persist = checkpoint)
                        if (checkpoint) { saveState(); lastUsageCheckpoint = now }
                    }
                }
                val calls = if (finalRound || response.failure != null) emptyList() else response.toolCalls
                withContext(NonCancellable) {
                    state = state.copy(
                        completedSteps = round,
                        inputTokens = roundInput + response.input,
                        outputTokens = roundOutput + response.output,
                        tokensEstimated = state.tokensEstimated || response.estimated,
                    )
                    messages += ProtocolMessage(
                        role = "assistant",
                        content = response.content,
                        reasoning = response.reasoning.takeIf(String::isNotBlank),
                        toolCalls = response.toolCalls.takeIf { it.isNotEmpty() },
                        reasoningReplay = response.replay,
                    )
                    update(
                        run.copy(inputTokens = state.inputTokens.clamp(), outputTokens = state.outputTokens.clamp(), totalTokens = (state.inputTokens + state.outputTokens).clamp(), tokensEstimated = state.tokensEstimated),
                        persist = true,
                    )
                    saveState()
                }
                currentCoroutineContext().ensureActive()
                response.failure?.let { failure ->
                    event(SubagentEvent.Kind.ERROR, failure, failed = true)
                    return finish(run, SubagentStatus.FAILED, error = failure, publish = ::update)
                }
                // 最终正文放在结果区，时间线只记录调用工具前的中间说明。
                if (calls.isNotEmpty() && response.content.isNotBlank()) event(SubagentEvent.Kind.TEXT, response.content, persist = false)
                if (calls.isEmpty()) {
                    // 模型生成结论期间也可能收到新指令，先交给下一步处理再结束执行。
                    val hasPending = execution.drainInbox().any {
                        if (it.id.isNotEmpty()) it.id !in consumed else it !in consumedUnidentified
                    }
                    if (hasPending) {
                        if (response.content.isNotBlank()) event(SubagentEvent.Kind.TEXT, response.content, persist = false)
                        continue
                    }
                    val result = response.content.trim()
                    return if (result.isEmpty()) finish(run, SubagentStatus.FAILED, error = EMPTY_RESULT, publish = ::update)
                    else finish(run, SubagentStatus.COMPLETED, result = if (reachedTurns) "$TURN_LIMIT_RESULT\n\n$result" else result, publish = ::update)
                }
                for (call in calls) {
                    currentCoroutineContext().ensureActive()
                    when (executeCall(parent, session, stepId, tools, call, { run }, ::update, ::event) { content ->
                        messages += ProtocolMessage("tool", content, toolCallId = call.id, name = call.name)
                        saveState()
                    }) {
                        is CallOutcome.Result -> Unit
                        CallOutcome.ApprovalTimedOut -> {
                            event(SubagentEvent.Kind.ERROR, APPROVAL_TIMEOUT, call.name, failed = true)
                            return finish(run, SubagentStatus.FAILED, error = APPROVAL_TIMEOUT, publish = ::update)
                        }
                        CallOutcome.SessionGone -> return finish(run, SubagentStatus.CANCELLED, error = SESSION_GONE, publish = ::update)
                    }
                }
            }
        }

        return try {
            loop()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { update(run.copy(status = SubagentStatus.CANCELLED, currentTool = null, liveOutputKey = null, endedAt = clock())) }
            }
            throw cancelled
        } catch (error: Exception) {
            // 只记录异常类型：原文可能含路径或地址；单个子代理失败不影响同批其它子代理。
            val message = (error as? SubagentModelUnavailable)?.message ?: internalError(error)
            withContext(NonCancellable) {
                runCatching { event(SubagentEvent.Kind.ERROR, message, failed = true) }
                runCatching { finish(run, SubagentStatus.FAILED, error = message, publish = ::update) }.getOrElse {
                    run.copy(status = SubagentStatus.FAILED, error = message, currentTool = null, liveOutputKey = null, endedAt = clock())
                }
            }
        }
    }

    private sealed interface CallOutcome {
        data class Result(val content: String) : CallOutcome
        data object ApprovalTimedOut : CallOutcome
        data object SessionGone : CallOutcome
    }

    private suspend fun executeCall(
        parent: SkillExecutionContext,
        sessions: suspend () -> Session?,
        stepId: String,
        tools: List<ProtocolTool>,
        call: ProtocolToolCall,
        current: () -> SubagentRun,
        update: suspend (SubagentRun, Boolean) -> Unit,
        event: suspend (SubagentEvent.Kind, String, String?, Boolean, Boolean) -> Unit,
        recordResult: suspend (String) -> Unit,
    ): CallOutcome {
        suspend fun result(content: String, failed: Boolean): CallOutcome.Result = withContext(NonCancellable) {
            recordResult(content)
            event(SubagentEvent.Kind.TOOL_RESULT, content, call.name, failed, true)
            CallOutcome.Result(content)
        }
        event(SubagentEvent.Kind.TOOL_CALL, call.arguments, call.name, false, false)
        if (call.name == com.promenar.infinity.domain.ask.AskUserLimits.TOOL_NAME) {
            val message = com.promenar.infinity.ui.chat.manager.AskUserProtocol.unavailable()
            return result(message, true)
        }
        val session = sessions() ?: return CallOutcome.SessionGone
        val available = availableTools(host.tools(session), current().toolProfile, current().allowedTools)
        val tool = tools.singleOrNull { it.function.name == call.name }?.takeIf { it in available }
        if (tool == null) {
            val names = available.filter { it in tools }.map { it.function.name }.sorted()
            val message = "当前不可用的工具 `${call.name.take(80)}`。" +
                if (names.isEmpty()) "没有可用工具，请直接给出结论。" else "可用工具：${names.joinToString(", ")}。"
            return result(message, true)
        }
        val resolved = ToolInvocationIdentityFactory.fromPreparedToolCall(ToolCall(call.id, call.name, call.arguments), tool, requiresApproval = false)
        if (resolved !is ToolInvocationIdentityResolution.Valid) {
            val message = "工具参数无效：${(resolved as ToolInvocationIdentityResolution.Invalid).message}"
            return result(message, true)
        }
        val arguments = resolved.arguments
        val target = host.target(session, tool, call.arguments)
        val mode = ExecutionModeCodec.parseOrWorkspace(session.executionMode.ifEmpty { "workspace" })
        val needsApproval = ToolExecutionPolicy.requiresApproval(mode, call.name, tool.risk, call.arguments, target, usesLinux = session.runMode == com.promenar.infinity.domain.model.SessionRunMode.LINUX)
        if (needsApproval) {
            val activeStatus = if (current().status == SubagentStatus.WRAPPING_UP) SubagentStatus.WRAPPING_UP else SubagentStatus.RUNNING
            val preview = approvalPreview(call.name, arguments)
            update(current().copy(status = SubagentStatus.WAITING_APPROVAL, currentTool = call.name), true)
            event(SubagentEvent.Kind.APPROVAL, preview, call.name, false, true)
            val outcome = approvals.request(session.id, current(), call.name, tool.risk, target, preview)
            update(current().copy(status = activeStatus), true)
            when (outcome) {
                SubagentApprovalOutcome.TIMED_OUT -> return CallOutcome.ApprovalTimedOut
                SubagentApprovalOutcome.DENIED -> {
                    return result(DENIED, true)
                }
                SubagentApprovalOutcome.APPROVED_REMEMBER, SubagentApprovalOutcome.APPROVED -> Unit
            }
            currentCoroutineContext().ensureActive()
            // 等待期间文件可能被移动、工作区可能切换：目标与审批时不一致就不执行。
            val now = sessions() ?: return CallOutcome.SessionGone
            if (host.target(now, tool, call.arguments) != target) {
                return result(TARGET_CHANGED, true)
            }
            if (tool !in availableTools(host.tools(now), current().toolProfile, current().allowedTools)) {
                return result("当前不可用的工具 `${call.name.take(80)}`。", true)
            }
        }
        val key = "subagent:$stepId:${current().index}:${call.id}"
        val sink = liveOutput?.open(key) ?: ToolLiveOutputSink.None
        update(current().copy(currentTool = call.name, liveOutputKey = key.takeIf { liveOutput != null }), false)
        val context = object : SkillExecutionContext by parent {
            override val runMode = session.runMode
            override val sourceSubagentId = current().id
            override val approvedExecutionTarget = if (needsApproval) target else null
            override val isApprovalBoundExecution = needsApproval
            override val liveOutput = sink
            override val toolStepId = key
        }
        val outcome = try {
            host.execute(tool, arguments, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SubagentToolOutcome("工具内部错误（${error::class.java.simpleName}）", failed = true)
        } finally {
            liveOutput?.close(key, sink)
        }
        return withContext(NonCancellable) {
            recordResult(outcome.content)
            update(current().copy(currentTool = null, liveOutputKey = null), false)
            event(SubagentEvent.Kind.TOOL_RESULT, outcome.content, call.name, outcome.failed, true)
            CallOutcome.Result(outcome.content)
        }
    }

    internal suspend fun ignoredTools(session: Session, spec: SubagentSpec): List<String> {
        val requested = spec.allowedTools ?: return emptyList()
        val names = availableTools(host.tools(session), spec.toolProfile).mapTo(mutableSetOf()) { it.function.name }
        return requested.distinct().filterNot { it in names }
    }

    private fun availableTools(tools: List<ProtocolTool>, profile: SubagentToolProfile, allowedTools: List<String>? = null): List<ProtocolTool> =
        (SubagentLimits.toolset(tools, profile) + tools.filter { it.function.name in setOf("session_read", "session_send") }).distinctBy { it.function.name }.filter {
            it.function.name !in CONTROL_TOOLS && it.runtimeToolId !in CONTROL_TOOLS &&
                (it.runtimeToolId !in setOf("session_read", "session_send") || it.function.name == it.runtimeToolId) &&
                (allowedTools == null || it.function.name in allowedTools)
        }

    private data class RoundRequest(val messages: List<ProtocolMessage>, val maxOutputTokens: Int)

    /** 只折叠发送副本；先保留近三轮，预算不足时再折叠近期结果、历史正文及完整工具轮。 */
    private fun compactForRequest(messages: List<ProtocolMessage>, tools: List<ProtocolTool>, contextWindow: Int?): RoundRequest? {
        val window = contextWindow?.takeIf { it > 0 } ?: 32_768
        val maxOutput = minOf(SubagentLimits.MAX_OUTPUT_TOKENS, (window / 4).coerceAtLeast(1))
        val margin = minOf(1_024, (window / 16).coerceAtLeast(1))
        val budget = minOf(window.toLong() * 4 / 5, (window.toLong() - maxOutput - margin).coerceAtLeast(0))
        val toolTokens = estimateTools(tools)
        val result = messages.toMutableList()
        fun estimate(): Long = result.sumOf { ApproximateTokenEstimator.estimate(it).toLong() } + toolTokens
        fun prepared(): RoundRequest = RoundRequest(result.toList(), maxOutput)
        if (estimate() <= budget) return prepared()

        val rounds = result.indices.filter { result[it].role == "assistant" && !result[it].toolCalls.isNullOrEmpty() }
        val recent = rounds.takeLast(3).toSet()
        val owners = mutableMapOf<String, Int>()
        val resultOwners = mutableMapOf<Int, Int>()
        result.forEachIndexed { index, message ->
            message.toolCalls.orEmpty().forEach { owners[it.id] = index }
            if (message.role == "tool") owners[message.toolCallId]?.let { resultOwners[index] = it }
        }
        val orderedResults = resultOwners.keys.filter { resultOwners[it] !in recent } +
            resultOwners.keys.filter { resultOwners[it] in recent }
        for (index in orderedResults) {
            val message = result[index]
            if (message.content.length <= 512) continue
            result[index] = message.copy(content = ContextCompactionPipeline.headTail(message.content))
            if (estimate() <= budget) return prepared()
        }

        // 用户指令与系统约束不截断；历史思考签名与正文一同移除，避免回传失配的签名。
        for (index in result.indices) {
            val message = result[index]
            if (message.role != "assistant" || !message.toolCalls.isNullOrEmpty()) continue
            result[index] = ProtocolMessage("assistant", ContextCompactionPipeline.headTail(message.content)
                .replace("Truncated tool result;", "历史助手正文已折叠；"))
            if (estimate() <= budget) return prepared()
        }

        // 工具参数或签名也可能很大，必须把调用与全部对应结果作为一个整体折叠。
        for (original in messages.filter { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() }) {
            val ids = original.toolCalls.orEmpty().mapTo(mutableSetOf()) { it.id }
            val owner = result.indexOfFirst { it === original }
            if (owner < 0) continue
            var end = owner + 1
            while (end < result.size && result[end].role == "tool" && result[end].toolCallId in ids) end++
            result[owner] = ProtocolMessage("assistant", "[工具调用与结果已折叠；完整原文保存在子代理历史中。]\n" +
                ContextCompactionPipeline.headTail(original.content))
            result.subList(owner + 1, end).clear()
            if (estimate() <= budget) return prepared()
        }

        // 大量短历史也会耗尽消息开销；只移除已无工具配对的历史助手消息。
        val removable = result.filter { it.role == "assistant" && it.toolCalls.isNullOrEmpty() }
        for (message in removable) {
            result.remove(message)
            if (estimate() <= budget) return prepared()
        }
        return null
    }

    private fun estimateTools(tools: List<ProtocolTool>): Long = tools.sumOf {
        ApproximateTokenEstimator.estimate(it.function.name + it.function.description + it.function.parameters).toLong()
    }

    /** 恢复时补齐所有未返回的调用；外部副作用可能已发生，交给模型显式核对状态。 */
    private fun completeInterruptedCalls(messages: MutableList<ProtocolMessage>) {
        val restored = buildList {
            var pending = emptyList<ProtocolToolCall>()
            for (message in messages) {
                if (message.role != "tool" && pending.isNotEmpty()) {
                    pending.forEach { add(ProtocolMessage("tool", INTERRUPTED_TOOL_RESULT, toolCallId = it.id, name = it.name)) }
                    pending = emptyList()
                }
                add(message)
                if (message.role == "assistant") pending = message.toolCalls.orEmpty()
                if (message.role == "tool") pending = pending.filterNot { it.id == message.toolCallId }
            }
            pending.forEach { add(ProtocolMessage("tool", INTERRUPTED_TOOL_RESULT, toolCallId = it.id, name = it.name)) }
        }
        messages.clear()
        messages.addAll(restored)
    }

    private suspend fun finish(
        run: SubagentRun,
        status: SubagentStatus,
        result: String? = null,
        error: String? = null,
        publish: suspend (SubagentRun, Boolean) -> Unit,
    ): SubagentRun {
        val finished = run.copy(status = status, result = result, error = error, currentTool = null, liveOutputKey = null, endedAt = clock())
        publish(finished, true)
        return finished
    }

    private class RoundResponse(
        val content: String,
        val reasoning: String,
        val toolCalls: List<ProtocolToolCall>,
        val replay: ReasoningReplay?,
        val input: Long,
        val output: Long,
        val estimated: Boolean,
        val failure: String?,
    )

    private suspend fun streamRound(
        client: SubagentModelClient,
        sessionId: String,
        modelId: String,
        reasoningEffort: ReasoningEffort?,
        messages: List<ProtocolMessage>,
        tools: List<ProtocolTool>,
        maxOutputTokens: Int,
        onUsage: suspend (Long, Long, Boolean, Int?, Int?, Int?, Boolean) -> Unit,
    ): RoundResponse {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = LinkedHashMap<String, Pair<String, StringBuilder>>()
        var replay: ReasoningReplay? = null
        var input = 0
        var output = 0
        var total = 0
        var cacheRead: Int? = null
        var cacheWrite: Int? = null
        var cacheLongWrite: Int? = null
        var observedAtUsage = 0L
        var completed = false
        var failure: String? = null
        fun observedOutput(): Long = ApproximateTokenEstimator.estimate(content.toString() + reasoning).toLong() +
            calls.values.sumOf { ApproximateTokenEstimator.estimate(it.first + it.second).toLong() }
        fun snapshot(): Triple<Long, Long, Boolean> {
            if (total <= 0) return Triple(messages.sumOf { ApproximateTokenEstimator.estimate(it).toLong() } + estimateTools(tools), observedOutput(), true)
            val unreported = (observedOutput() - observedAtUsage).coerceAtLeast(0L)
            return Triple(maxOf(input, total - output).toLong(), output.toLong() + unreported, unreported > 0)
        }
        try {
            client.stream(modelId, messages, tools, maxOutputTokens, reasoningEffort, sessionId).collect { chunk ->
                when (chunk) {
                    is StreamChunk.TextDelta -> {
                        content.append(chunk.content)
                        chunk.reasoning?.let(reasoning::append)
                    }
                    is StreamChunk.Thinking -> reasoning.append(chunk.content)
                    is StreamChunk.ToolCallDelta -> {
                        val old = calls[chunk.id]
                        calls[chunk.id] = (chunk.name.ifBlank { old?.first.orEmpty() }) to (old?.second ?: StringBuilder()).append(chunk.arguments)
                    }
                    is StreamChunk.Usage -> {
                        // 流式用量通常是累计快照，取最大值而不是相加。
                        input = maxOf(input, chunk.usage.input)
                        output = maxOf(output, chunk.usage.output)
                        total = maxOf(total, chunk.usage.total, input + output)
                        cacheRead = maxReported(cacheRead, chunk.usage.cacheReadInput)
                        cacheWrite = maxReported(cacheWrite, chunk.usage.cacheWriteInput)
                        cacheLongWrite = maxReported(cacheLongWrite, chunk.usage.cacheWriteLongTtlInput)
                        observedAtUsage = observedOutput()
                        onUsage(maxOf(input, total - output).toLong(), output.toLong(), false, cacheRead, cacheWrite, cacheLongWrite, false)
                    }
                    is StreamChunk.ReasoningState -> replay = chunk.replay
                    is StreamChunk.Error -> if (failure == null) failure = "模型请求失败（${chunk.code.name}）"
                    is StreamChunk.Completed -> completed = true
                    is StreamChunk.Citations, is StreamChunk.ToolCallLifecycle -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val (inputTokens, outputTokens, estimated) = snapshot()
                onUsage(inputTokens, outputTokens, estimated, cacheRead, cacheWrite, cacheLongWrite, true)
            }
            throw cancelled
        } catch (error: Exception) {
            // 只回显本模块给出的路由原因，网络异常的原文可能含地址等细节。
            failure = (error as? SubagentModelUnavailable)?.message ?: "模型请求失败（${error::class.java.simpleName}）"
        }
        if (failure == null && !completed) failure = "模型输出意外中断"
        val toolCalls = calls.map { (id, value) -> ProtocolToolCall(id, value.first, value.second.toString().ifBlank { "{}" }) }
        val (inputTokens, outputTokens, estimated) = snapshot()
        withContext(NonCancellable) { onUsage(inputTokens, outputTokens, estimated, cacheRead, cacheWrite, cacheLongWrite, true) }
        return RoundResponse(content.toString(), reasoning.toString(), toolCalls, replay, inputTokens, outputTokens, estimated, failure)
    }

    private fun systemPrompt(preset: SubagentPreset?, tools: List<ProtocolTool>, executionMode: String, runMode: com.promenar.infinity.domain.model.SessionRunMode?, modelId: String?): String = buildString {
        append(SYSTEM_PROMPT)
        append("\n\n").append(SystemPromptSections.runtimeEnvironment(modelId))
        append("\n\n").append(com.promenar.infinity.ui.chat.manager.SystemPromptSections.permissions(ExecutionModeCodec.parseOrWorkspace(executionMode), runMode = runMode ?: com.promenar.infinity.domain.model.SessionRunMode.BUILTIN))
        preset?.let { append("\n\n").append(it.instruction) }
        if (tools.isEmpty()) append("\n\n本次没有可用工具，请直接根据任务内容作答。")
        else append("\n\n").append(if (runMode == com.promenar.infinity.domain.model.SessionRunMode.LINUX)
            com.promenar.infinity.ui.chat.manager.SystemPromptSections.LINUX_SHELL_GUIDANCE else WORKSPACE_PATHS)
    }

    /** 审批卡展示的内容：已知工具用专门的预览，其余工具展示完整参数，用户批准的就是实际执行的。 */
    private fun approvalPreview(toolName: String, arguments: JsonObject): String =
        host.preview(toolName, arguments).ifBlank { PRETTY_JSON.encodeToString(JsonObject.serializer(), arguments) }

    private fun Long.clamp(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        internal const val DENIED = "用户拒绝了该操作，请改用其它方式或在结论中说明。"
        internal const val APPROVAL_TIMEOUT = "审批在 10 分钟内未处理，子代理已停止。"
        internal const val EMPTY_RESULT = "子代理没有给出结论。"
        internal const val SESSION_GONE = "父会话已删除，子代理已停止。"
        internal const val TARGET_CHANGED = "审批期间操作目标已变化，本次调用未执行；请重新确认目标后再试。"
        internal const val CONTEXT_LIMIT = "子代理上下文超过模型可用窗口；必要系统约束、用户指令或工具定义无法安全容纳，请缩小任务或追加指令范围。"
        internal fun internalError(error: Exception) = "子代理内部错误（${error::class.java.simpleName}）"
        private val PRETTY_JSON = kotlinx.serialization.json.Json { prettyPrint = true }
        private val CONTROL_TOOLS = setOf(SubagentLimits.WAIT_TOOL_NAME, "session_branch", "session_list", "session_stop")
        private const val INTERRUPTED_TOOL_RESULT = "工具执行被中断，状态未知。请先核对实际状态，不要直接重复可能产生副作用的操作。"
        private const val WORKSPACE_PATHS = "工作区文件使用以 / 开头的虚拟路径（相对工作区根目录），文件工具接受 path 或 uuid；" +
            "先用 list_files、search_files 查找，不要猜测路径，也不要使用设备上的绝对路径。"
        private const val SYSTEM_PROMPT = "你是主智能体派生的子代理，在独立上下文中完成交给你的一项子任务。" +
            "你只与主智能体通信：不要向用户提问，信息不足时基于已有信息给出最佳结论并说明假设。" +
            "完成后只输出交给主智能体的结论正文，简洁、结构清楚，包含关键事实、路径与未解决事项。"
        private const val FINAL_ANSWER_PROMPT = "已达到步数或用量上限。请停止调用工具，根据目前掌握的信息给出最终结论。"
        private const val TURN_LIMIT_RESULT = "未完成：已到达轮数上限"
        private const val TURN_LIMIT_PROMPT = "已到达轮数上限。请停止调用工具，交回阶段结论并说明未完成事项。"
        private const val WRAP_UP_PROMPT = "停止当前工作，交回阶段结论。请只进行必要收尾，说明已经完成与尚未完成的事项。"
        private const val WRAP_UP_FINAL_PROMPT = "收尾步数已用完。请停止调用工具，立即交回阶段结论并说明未完成事项。"
    }
}

private fun sumReported(a: Int?, b: Int?): Int? = if (a == null && b == null) null else
    ((a ?: 0).toLong() + (b ?: 0)).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

private fun maxReported(a: Int?, b: Int?): Int? = when {
    b == null -> a
    a == null -> b.coerceAtLeast(0)
    else -> maxOf(a, b.coerceAtLeast(0))
}
