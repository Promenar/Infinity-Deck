package com.promenar.infinity.data.repository

import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.dao.ToolExecutionLedgerDao
import com.promenar.infinity.data.local.db.entity.ToolExecutionLedgerEntity
import com.promenar.infinity.data.local.db.entity.ToolLedgerStatus
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.toEntity
import com.promenar.infinity.data.model.ApprovalRequest
import com.promenar.infinity.data.model.ApprovalCallIdentity
import com.promenar.infinity.data.model.LoopStatus
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.model.json
import com.promenar.infinity.domain.media.ViewedMedia
import com.promenar.infinity.domain.tool.ToolArgumentsValidation
import com.promenar.infinity.domain.tool.ToolArgumentsValidator
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.tool.ToolSchemaValidation
import com.promenar.infinity.domain.tool.ToolSchemaValidator
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.security.MessageDigest

data class ToolExecutionKey(
    val sessionId: String,
    val assistantMessageId: String,
    val toolCallId: String,
)

data class ToolInvocationIdentity(
    val runtimeToolId: String,
    val toolName: String,
    val argumentsDigest: String,
    val definitionDigest: String,
    val requiresApproval: Boolean,
) {
    init {
        require(runtimeToolId.isNotBlank()) { "runtime tool id 不能为空" }
        require(toolName.isNotBlank()) { "工具名称不能为空" }
        require(argumentsDigest.isNotBlank()) { "工具参数摘要不能为空" }
        require(definitionDigest.isNotBlank()) { "工具定义摘要不能为空" }
    }
}

fun ApprovalCallIdentity.toInvocationIdentity(): ToolInvocationIdentity = ToolInvocationIdentity(
    runtimeToolId = runtimeToolId,
    toolName = toolName,
    argumentsDigest = argumentsDigest,
    definitionDigest = definitionDigest,
    requiresApproval = requiresApproval,
)

data class ApprovalCallCandidate(
    val call: ToolCall,
    val risk: ToolRisk,
    val identity: ToolInvocationIdentity,
    val executionTarget: String? = null,
    val grantCategory: com.promenar.infinity.domain.tool.ApprovalGrantCategory? = null,
)

object ToolApprovalRequestFactory {
    const val TOOL_APPROVAL_TYPE = "tool_approval"

    private const val MAX_ARGUMENT_SUMMARY = 180

    fun create(
        assistantMessageId: String,
        candidates: List<ApprovalCallCandidate>,
        reason: String? = null,
    ): ApprovalRequest {
        val identities = candidates.map { candidate ->
            require(candidate.identity.requiresApproval) { "审批工具必须绑定 requiresApproval identity" }
            require(candidate.identity.toolName == candidate.call.name) { "审批工具名称与 identity 不一致" }
            val arguments = ToolArgumentsValidator().validate(candidate.call.arguments)
            require(arguments is ToolArgumentsValidation.Valid) { "审批工具参数必须是合法 JSON object" }
            require(arguments.sha256 == candidate.identity.argumentsDigest) { "审批工具参数与 identity 不一致" }
            ApprovalCallIdentity(
                toolCallId = candidate.call.id,
                runtimeToolId = candidate.identity.runtimeToolId,
                toolName = candidate.identity.toolName,
                argumentsDigest = candidate.identity.argumentsDigest,
                definitionDigest = candidate.identity.definitionDigest,
                requiresApproval = true,
                argumentsSummary = summarizeArguments(candidate.call.arguments),
                risk = candidate.risk.name.lowercase(),
                executionTarget = candidate.executionTarget,
                grantCategory = candidate.grantCategory,
            )
        }
        return createFromIdentities(assistantMessageId, identities, reason)
    }

    fun createFromIdentities(
        assistantMessageId: String,
        calls: List<ApprovalCallIdentity>,
        reason: String? = null,
    ): ApprovalRequest {
        val request = ApprovalRequest(
            assistantMessageId = assistantMessageId,
            calls = calls,
            reason = reason,
            type = TOOL_APPROVAL_TYPE,
        )
        return request.copy(identityHash = identityHash(request))
    }

    fun isValid(request: ApprovalRequest): Boolean =
        request.type == TOOL_APPROVAL_TYPE &&
            !request.assistantMessageId.isNullOrBlank() &&
            request.calls.isNotEmpty() &&
            request.calls.map { it.toolCallId }.distinct().size == request.calls.size &&
            request.calls.all { call ->
                call.toolCallId.isNotBlank() && call.runtimeToolId.isNotBlank() &&
                    call.toolName.isNotBlank() && call.argumentsDigest.isNotBlank() &&
                    call.definitionDigest.isNotBlank() && call.requiresApproval &&
                    call.risk.isNotBlank() && (call.executionTarget == null || call.executionTarget.isNotBlank())
            } && request.identityHash == identityHash(request)

    fun identityHash(request: ApprovalRequest): String {
        val raw = buildString {
            append(request.assistantMessageId.orEmpty())
            request.calls.forEach { call ->
                append('\u0000').append(call.toolCallId)
                append('\u0000').append(call.runtimeToolId)
                append('\u0000').append(call.toolName)
                append('\u0000').append(call.argumentsDigest)
                append('\u0000').append(call.definitionDigest)
                append('\u0000').append(call.requiresApproval)
                append('\u0000').append(call.argumentsSummary)
                append('\u0000').append(call.risk)
            }
            if (request.calls.any { it.executionTarget != null }) {
                // 无目标的历史请求保持原摘要；新增目标按完整元组绑定，防止分隔符碰撞。
                append('\u0000').append("execution_targets_v1").append('\u0000')
                append(JsonArray(request.calls.map { call ->
                    JsonArray(listOf(
                        JsonPrimitive(call.toolCallId),
                        call.executionTarget?.let(::JsonPrimitive) ?: JsonNull,
                    ))
                }))
            }
            if (request.calls.any { it.grantCategory != null }) {
                // 无类别的历史请求保持原摘要；新类别绑定调用，防止持久化请求被换类或移除类别。
                append('\u0000').append("grant_categories_v1").append('\u0000')
                append(JsonArray(request.calls.map { call ->
                    JsonArray(listOf(
                        JsonPrimitive(call.toolCallId),
                        call.grantCategory?.name?.let(::JsonPrimitive) ?: JsonNull,
                    ))
                }))
            }
        }
        return sha256Hex(raw)
    }

    private fun summarizeArguments(arguments: String): String = arguments
        .replace(Regex("\\s+"), " ")
        .trim()
        .let { if (it.length <= MAX_ARGUMENT_SUMMARY) it else it.take(MAX_ARGUMENT_SUMMARY - 3) + "..." }
}

enum class ToolInvocationIdentityErrorCode {
    TOOL_NAME_MISMATCH,
    MALFORMED_ARGUMENTS,
    ROOT_ARGUMENTS_NOT_OBJECT,
    INVALID_DEFINITION,
    SCHEMA_MISMATCH,
}

sealed interface ToolDefinitionDigestResolution {
    data class Valid(
        val digest: String,
        val canonicalSchema: String,
    ) : ToolDefinitionDigestResolution

    data class Invalid(val message: String) : ToolDefinitionDigestResolution
}

sealed interface ToolInvocationIdentityResolution {
    data class Valid(
        val identity: ToolInvocationIdentity,
        val arguments: JsonObject,
    ) : ToolInvocationIdentityResolution

    data class Invalid(
        val code: ToolInvocationIdentityErrorCode,
        val message: String,
    ) : ToolInvocationIdentityResolution
}

object ToolInvocationIdentityFactory {
    private const val DEFINITION_VERSION = "infinity:tool-definition:v2"
    private const val LEGACY_DEFINITION_PREFIX = "infinity:legacy-tool-definition:v1:"

    fun definitionDigest(tool: ProtocolTool): ToolDefinitionDigestResolution {
        val runtimeToolId = tool.runtimeToolId.trim()
        val wireName = tool.function.name.trim()
        val sourceId = tool.sourceId.trim()
        if (runtimeToolId.isEmpty() || wireName.isEmpty() || sourceId.isEmpty()) {
            return ToolDefinitionDigestResolution.Invalid("工具定义身份字段不完整")
        }
        val schema = ToolSchemaValidator().validateDefinition(tool.function.parameters)
        if (schema !is ToolSchemaValidation.Valid) {
            return ToolDefinitionDigestResolution.Invalid("工具 schema 无效或包含不支持的关键字")
        }
        if (tool.baseDefinitionDigest?.let { !Regex("[0-9a-f]{64}").matches(it) } == true) {
            return ToolDefinitionDigestResolution.Invalid("底层工具定义指纹无效")
        }
        val raw = (listOf(
            DEFINITION_VERSION,
            runtimeToolId,
            wireName,
            tool.function.description,
            schema.canonicalSchema,
            tool.risk.name,
            sourceId,
            tool.mcpServerId.orEmpty(),
        ) + listOfNotNull(tool.baseDefinitionDigest?.let { "session-source:$it" })).joinToString("\u0000")
        return ToolDefinitionDigestResolution.Valid(
            digest = sha256Hex(raw),
            canonicalSchema = schema.canonicalSchema,
        )
    }

    fun fromPreparedToolCall(
        call: ToolCall,
        tool: ProtocolTool,
        requiresApproval: Boolean,
    ): ToolInvocationIdentityResolution {
        val toolName = call.name.trim()
        if (toolName.isEmpty() || toolName != tool.function.name.trim()) {
            return ToolInvocationIdentityResolution.Invalid(
                ToolInvocationIdentityErrorCode.TOOL_NAME_MISMATCH,
                "工具调用名称与已准备定义不一致",
            )
        }
        val arguments = when (val validated = ToolArgumentsValidator().validate(call.arguments)) {
            is ToolArgumentsValidation.Valid -> validated
            is ToolArgumentsValidation.Invalid -> return ToolInvocationIdentityResolution.Invalid(
                code = when (validated.error.code) {
                    com.promenar.infinity.domain.tool.ToolArgumentsErrorCode.MALFORMED_JSON ->
                        ToolInvocationIdentityErrorCode.MALFORMED_ARGUMENTS
                    com.promenar.infinity.domain.tool.ToolArgumentsErrorCode.ROOT_NOT_OBJECT ->
                        ToolInvocationIdentityErrorCode.ROOT_ARGUMENTS_NOT_OBJECT
                },
                message = validated.error.message,
            )
        }
        val digest = definitionDigest(tool)
        if (digest !is ToolDefinitionDigestResolution.Valid) {
            return ToolInvocationIdentityResolution.Invalid(
                ToolInvocationIdentityErrorCode.INVALID_DEFINITION,
                (digest as ToolDefinitionDigestResolution.Invalid).message,
            )
        }
        val schema = ToolSchemaValidator().validate(digest.canonicalSchema, if (tool.sourceId == "builtin" && com.promenar.infinity.domain.tool.ExecToolArguments.supports(toolName)) com.promenar.infinity.domain.tool.ExecToolArguments.normalize(arguments.arguments).arguments else arguments.arguments)
        if (schema is ToolSchemaValidation.Invalid) {
            return ToolInvocationIdentityResolution.Invalid(
                ToolInvocationIdentityErrorCode.SCHEMA_MISMATCH,
                "工具参数不符合 schema：${schema.error.message}（${schema.error.code.name} @ ${schema.error.path}）",
            )
        }
        return ToolInvocationIdentityResolution.Valid(
            identity = ToolInvocationIdentity(
                runtimeToolId = tool.runtimeToolId.trim(),
                toolName = toolName,
                argumentsDigest = arguments.sha256,
                definitionDigest = digest.digest,
                requiresApproval = requiresApproval,
            ),
            arguments = arguments.arguments,
        )
    }

    fun fromLegacyToolCall(
        call: ToolCall,
        requiresApproval: Boolean,
    ): ToolInvocationIdentityResolution {
        val toolName = call.name.trim()
        if (toolName.isEmpty()) return ToolInvocationIdentityResolution.Invalid(
            ToolInvocationIdentityErrorCode.TOOL_NAME_MISMATCH,
            "工具名称不能为空",
        )
        val validated = ToolArgumentsValidator().validate(call.arguments)
        if (validated is ToolArgumentsValidation.Invalid) {
            return ToolInvocationIdentityResolution.Invalid(
                code = when (validated.error.code) {
                    com.promenar.infinity.domain.tool.ToolArgumentsErrorCode.MALFORMED_JSON ->
                        ToolInvocationIdentityErrorCode.MALFORMED_ARGUMENTS
                    com.promenar.infinity.domain.tool.ToolArgumentsErrorCode.ROOT_NOT_OBJECT ->
                        ToolInvocationIdentityErrorCode.ROOT_ARGUMENTS_NOT_OBJECT
                },
                message = validated.error.message,
            )
        }
        validated as ToolArgumentsValidation.Valid
        return ToolInvocationIdentityResolution.Valid(
            identity = ToolInvocationIdentity(
                runtimeToolId = toolName,
                toolName = toolName,
                argumentsDigest = validated.sha256,
                definitionDigest = sha256Hex("$LEGACY_DEFINITION_PREFIX$toolName"),
                requiresApproval = requiresApproval,
            ),
            arguments = validated.arguments,
        )
    }

    fun isLegacy(identity: ToolInvocationIdentity): Boolean =
        identity.definitionDigest == sha256Hex("$LEGACY_DEFINITION_PREFIX${identity.toolName}")
}

sealed interface ToolRegistrationResult {
    data class Registered(val state: ToolLedgerState) : ToolRegistrationResult
    data class Existing(val state: ToolLedgerState) : ToolRegistrationResult
    data object Conflict : ToolRegistrationResult
}

typealias ToolLedgerState = ToolLedgerStatus

sealed interface ToolExecutionOutcome {
    val state: ToolLedgerState

    data object Succeeded : ToolExecutionOutcome {
        override val state = ToolLedgerState.SUCCEEDED
    }

    data class Failed(val error: String) : ToolExecutionOutcome {
        override val state = ToolLedgerState.FAILED
    }
}

data class ToolTerminalRequest(
    val key: ToolExecutionKey,
    val toolName: String,
    val thoughtSignature: String? = null,
    val interruptionReason: String? = null,
)

enum class ToolApprovalCreation { CREATED, EXISTING, CONFLICT }

sealed interface ExactToolApprovalDecision {
    data class Decided(
        val key: ToolExecutionKey,
        val terminalMessages: List<Message>,
    ) : ExactToolApprovalDecision
    data object Conflict : ExactToolApprovalDecision
}

sealed interface ExactToolApprovalCompletion {
    data class Completed(val transition: ApprovalTransition) : ExactToolApprovalCompletion
    data object Conflict : ExactToolApprovalCompletion
}

data class ApprovalTransition(
    val sessionId: String,
    val currentAssistantMessageId: String,
    val loopStatus: LoopStatus,
    val approvalRequest: ApprovalRequest?,
    val nextAssistantMessageId: String? = null,
    val nextPendingToolCallIds: List<String> = emptyList(),
    val terminalMessages: List<Message> = emptyList(),
)

interface ToolExecutionLedger {
    suspend fun register(
        key: ToolExecutionKey,
        identity: ToolInvocationIdentity,
    ): ToolRegistrationResult

    suspend fun approve(keys: Set<ToolExecutionKey>): Int
    suspend fun reject(keys: Set<ToolExecutionKey>): Int
    suspend fun cancel(keys: Set<ToolExecutionKey>): Int
    suspend fun timeout(keys: Set<ToolExecutionKey>): Int
    suspend fun claim(key: ToolExecutionKey, expectedIdentity: ToolInvocationIdentity): Boolean
    suspend fun finish(key: ToolExecutionKey, outcome: ToolExecutionOutcome): Boolean
    suspend fun finishWithResult(
        key: ToolExecutionKey,
        toolName: String,
        content: String,
        thoughtSignature: String?,
        outcome: ToolExecutionOutcome,
        images: String? = null,
        viewedMedia: List<ViewedMedia>? = null,
    ): Message?
    suspend fun terminalizeWithResults(
        requests: Set<ToolTerminalRequest>,
        state: ToolLedgerState,
    ): List<Message>
    suspend fun state(key: ToolExecutionKey): ToolLedgerState?
    suspend fun invocationIdentity(key: ToolExecutionKey): ToolInvocationIdentity?
    suspend fun failAwaitingIdentityConflict(
        key: ToolExecutionKey,
        expectedIdentity: ToolInvocationIdentity,
        thoughtSignature: String?,
    ): Message?
    suspend fun recoverInterruptedRunning(error: String): Int
    suspend fun createToolApproval(
        keySessionId: String,
        assistantMessageId: String,
        toolCalls: List<ToolCall>,
        pendingToolCallIds: Set<String>,
        request: ApprovalRequest,
    ): ToolApprovalCreation
    suspend fun decideExactToolApproval(
        sessionId: String,
        request: ApprovalRequest,
        approved: Boolean,
    ): ExactToolApprovalDecision = ExactToolApprovalDecision.Conflict
    suspend fun completeExactToolApproval(
        sessionId: String,
        request: ApprovalRequest,
    ): ExactToolApprovalCompletion = ExactToolApprovalCompletion.Conflict
    suspend fun compensateExactToolApproval(
        sessionId: String,
        request: ApprovalRequest,
        state: ToolLedgerState,
        error: String,
    ): ExactToolApprovalDecision = ExactToolApprovalDecision.Conflict
    suspend fun completeToolApproval(sessionId: String, assistantMessageId: String): ApprovalTransition
    suspend fun recoverApprovalState(): Int
}

class ToolExecutionLedgerRepository(
    private val database: InfinityDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : ToolExecutionLedger {
    private val dao: ToolExecutionLedgerDao = database.toolExecutionLedgerDao()
    override suspend fun register(
        key: ToolExecutionKey,
        identity: ToolInvocationIdentity,
    ): ToolRegistrationResult = database.withTransaction {
        registerInTransaction(key, identity)
    }

    private suspend fun registerInTransaction(
        key: ToolExecutionKey,
        identity: ToolInvocationIdentity,
    ): ToolRegistrationResult {
        val now = clock()
        val initial = if (identity.requiresApproval) {
            ToolLedgerState.PENDING_APPROVAL
        } else {
            ToolLedgerState.APPROVED
        }
        val inserted = dao.insert(
            ToolExecutionLedgerEntity(
                sessionId = key.sessionId,
                assistantMessageId = key.assistantMessageId,
                toolCallId = key.toolCallId,
                toolName = identity.toolName,
                runtimeToolId = identity.runtimeToolId,
                argumentsDigest = identity.argumentsDigest,
                definitionDigest = identity.definitionDigest,
                requiresApproval = identity.requiresApproval,
                status = initial,
                createdAt = now,
                updatedAt = now,
            ),
        )
        if (inserted != -1L) return ToolRegistrationResult.Registered(initial)
        val existing = dao.get(key.sessionId, key.assistantMessageId, key.toolCallId)
            ?: return ToolRegistrationResult.Conflict
        return if (existing.matches(identity)) {
            ToolRegistrationResult.Existing(existing.status)
        } else {
            ToolRegistrationResult.Conflict
        }
    }

    override suspend fun approve(keys: Set<ToolExecutionKey>): Int = database.withTransaction {
        keys.sumOf { key ->
            dao.approve(key.sessionId, key.assistantMessageId, key.toolCallId, clock())
        }
    }

    override suspend fun reject(keys: Set<ToolExecutionKey>): Int = terminalizeKeys(
        keys,
        ToolLedgerState.REJECTED,
    ).size

    override suspend fun cancel(keys: Set<ToolExecutionKey>): Int = terminalizeKeys(
        keys,
        ToolLedgerState.CANCELLED,
    ).size

    override suspend fun timeout(keys: Set<ToolExecutionKey>): Int = terminalizeKeys(
        keys,
        ToolLedgerState.TIMED_OUT,
    ).size

    override suspend fun claim(
        key: ToolExecutionKey,
        expectedIdentity: ToolInvocationIdentity,
    ): Boolean = dao.claim(
        key.sessionId,
        key.assistantMessageId,
        key.toolCallId,
        expectedIdentity.runtimeToolId,
        expectedIdentity.toolName,
        expectedIdentity.argumentsDigest,
        expectedIdentity.definitionDigest,
        expectedIdentity.requiresApproval,
        clock(),
    ) == 1

    override suspend fun finish(key: ToolExecutionKey, outcome: ToolExecutionOutcome): Boolean {
        val entry = dao.get(key.sessionId, key.assistantMessageId, key.toolCallId) ?: return false
        val content = when (outcome) {
            ToolExecutionOutcome.Succeeded -> "工具执行完成。"
            is ToolExecutionOutcome.Failed -> "工具执行失败，请检查工具参数与配置后重试。"
        }
        return finishWithResult(key, entry.toolName, content, null, outcome) != null
    }

    override suspend fun finishWithResult(
        key: ToolExecutionKey,
        toolName: String,
        content: String,
        thoughtSignature: String?,
        outcome: ToolExecutionOutcome,
        images: String?,
        viewedMedia: List<ViewedMedia>?,
    ): Message? = database.withTransaction {
        val createdAt = clock()
        val message = terminalMessage(
            request = ToolTerminalRequest(key, toolName, thoughtSignature),
            state = outcome.state,
            content = content,
            createdAt = createdAt,
            images = images,
            viewedMedia = viewedMedia,
        )
        val changed = dao.finish(
            sessionId = key.sessionId,
            assistantMessageId = key.assistantMessageId,
            toolCallId = key.toolCallId,
            terminalStatus = outcome.state,
            resultMessageId = message.id,
            error = (outcome as? ToolExecutionOutcome.Failed)?.error?.take(MAX_ERROR_LENGTH),
            updatedAt = createdAt,
        )
        if (changed != 1) return@withTransaction null
        database.messageDao().insert(message.toEntity(key.sessionId))
        message
    }

    override suspend fun terminalizeWithResults(
        requests: Set<ToolTerminalRequest>,
        state: ToolLedgerState,
    ): List<Message> {
        val (allowed, error, content) = terminalDecision(state) ?: return emptyList()
        return database.withTransaction {
            val orderedRequests = requests.map { request ->
                request to dao.get(
                    request.key.sessionId,
                    request.key.assistantMessageId,
                    request.key.toolCallId,
                )
            }.sortedWith(
                compareBy<Pair<ToolTerminalRequest, ToolExecutionLedgerEntity?>> { it.second?.createdAt ?: Long.MAX_VALUE }
                    .thenBy { it.first.key.toolCallId },
            ).map { it.first }
            val messages = orderedRequests.mapNotNull { request ->
                val createdAt = clock()
                val message = terminalMessage(request, state, request.interruptionReason ?: content, createdAt)
                val changed = dao.decideTerminal(
                    sessionId = request.key.sessionId,
                    assistantMessageId = request.key.assistantMessageId,
                    toolCallId = request.key.toolCallId,
                    allowedStatuses = allowed,
                    terminalStatus = state,
                    resultMessageId = message.id,
                    error = error,
                    updatedAt = createdAt,
                )
                if (changed != 1) return@mapNotNull null
                database.messageDao().insert(message.toEntity(request.key.sessionId))
                message
            }
            if (requests.isNotEmpty()) {
                val sessionId = requests.first().key.sessionId
                val assistantMessageId = requests.first().key.assistantMessageId
                check(requests.all {
                    it.key.sessionId == sessionId && it.key.assistantMessageId == assistantMessageId
                })
                database.messageDao().updatePendingApprovalToolIds(assistantMessageId, null)
                database.sessionDao().updateToolApprovalState(
                    sessionId,
                    null,
                    LoopStatus.PAUSED.toSerializedName(),
                    clock(),
                )
            }
            messages
        }
    }

    override suspend fun state(key: ToolExecutionKey): ToolLedgerState? = dao.get(
        key.sessionId,
        key.assistantMessageId,
        key.toolCallId,
    )?.status

    override suspend fun invocationIdentity(key: ToolExecutionKey): ToolInvocationIdentity? = dao.get(
        key.sessionId,
        key.assistantMessageId,
        key.toolCallId,
    )?.toIdentityOrNull()

    override suspend fun failAwaitingIdentityConflict(
        key: ToolExecutionKey,
        expectedIdentity: ToolInvocationIdentity,
        thoughtSignature: String?,
    ): Message? = database.withTransaction {
        val createdAt = clock()
        val terminal = terminalMessage(
            ToolTerminalRequest(key, expectedIdentity.toolName, thoughtSignature),
            ToolLedgerState.FAILED,
            IDENTITY_CONFLICT_CONTENT,
            createdAt,
        )
        val changed = dao.failAwaitingIdentityConflict(
            sessionId = key.sessionId,
            assistantMessageId = key.assistantMessageId,
            toolCallId = key.toolCallId,
            runtimeToolId = expectedIdentity.runtimeToolId,
            toolName = expectedIdentity.toolName,
            argumentsDigest = expectedIdentity.argumentsDigest,
            definitionDigest = expectedIdentity.definitionDigest,
            requiresApproval = expectedIdentity.requiresApproval,
            resultMessageId = terminal.id,
            error = "工具调用与已登记身份不一致",
            updatedAt = createdAt,
        )
        if (changed != 1) return@withTransaction null
        database.messageDao().insert(terminal.toEntity(key.sessionId))
        terminal
    }

    override suspend fun recoverInterruptedRunning(error: String): Int {
        database.withTransaction { dao.deleteOrphans() }
        val running = dao.getRunning()
        return running.count { entry ->
            recoverOrNull {
                database.withTransaction {
                    val key = ToolExecutionKey(entry.sessionId, entry.assistantMessageId, entry.toolCallId)
                    val createdAt = clock()
                    val message = terminalMessage(
                        ToolTerminalRequest(key, entry.toolName),
                        ToolLedgerState.FAILED,
                        "工具执行因进程中断而失败，请确认外部结果后重新发起。",
                        createdAt,
                    )
                    val changed = dao.finish(
                        entry.sessionId,
                        entry.assistantMessageId,
                        entry.toolCallId,
                        ToolLedgerState.FAILED,
                        message.id,
                        error.take(MAX_ERROR_LENGTH),
                        createdAt,
                    )
                    if (changed == 1) {
                        database.messageDao().insert(message.toEntity(entry.sessionId))
                        true
                    } else {
                        false
                    }
                }
            } == true
        }
    }

    override suspend fun createToolApproval(
        keySessionId: String,
        assistantMessageId: String,
        toolCalls: List<ToolCall>,
        pendingToolCallIds: Set<String>,
        request: ApprovalRequest,
    ): ToolApprovalCreation = database.withTransaction {
        if (dao.getInputForSession(keySessionId).isNotEmpty()) return@withTransaction ToolApprovalCreation.CONFLICT
        val assistant = database.messageDao().getById(assistantMessageId)
            ?.takeIf { it.sessionId == keySessionId && it.role == "assistant" }
            ?: return@withTransaction ToolApprovalCreation.CONFLICT
        if (!ToolApprovalRequestFactory.isValid(request) ||
            request.assistantMessageId != assistantMessageId ||
            request.toolName != null || request.args != null
        ) return@withTransaction ToolApprovalCreation.CONFLICT
        val awaiting = dao.getAwaitingApprovalForSession(keySessionId)
        val conflictingApproval = awaiting
            .firstOrNull { it.assistantMessageId != assistantMessageId }
        if (conflictingApproval != null) return@withTransaction ToolApprovalCreation.CONFLICT
        val distinctCalls = toolCalls.distinctBy { it.id }
        if (distinctCalls.size != toolCalls.size || pendingToolCallIds.isEmpty() ||
            pendingToolCallIds.any { pendingId -> distinctCalls.none { it.id == pendingId } }
        ) return@withTransaction ToolApprovalCreation.CONFLICT
        val orderedPendingIds = distinctCalls.filter { it.id in pendingToolCallIds }.map { it.id }
        if (request.calls.map { it.toolCallId } != orderedPendingIds) {
            return@withTransaction ToolApprovalCreation.CONFLICT
        }
        val callsById = distinctCalls.associateBy { it.id }
        val identities = request.calls.associate { approvalCall ->
            val identity = approvalCall.toInvocationIdentity()
            val call = callsById[approvalCall.toolCallId]
                ?: return@withTransaction ToolApprovalCreation.CONFLICT
            val arguments = ToolArgumentsValidator().validate(call.arguments)
                as? ToolArgumentsValidation.Valid
                ?: return@withTransaction ToolApprovalCreation.CONFLICT
            if (ToolInvocationIdentityFactory.isLegacy(identity) ||
                !identity.requiresApproval || identity.toolName != call.name ||
                identity.argumentsDigest != arguments.sha256
            ) return@withTransaction ToolApprovalCreation.CONFLICT
            approvalCall.toolCallId to identity
        }
        for ((callId, identity) in identities) {
            val key = ToolExecutionKey(keySessionId, assistant.id, callId)
            when (val existing = dao.get(keySessionId, assistantMessageId, callId)) {
                null -> if (registerInTransaction(key, identity) !=
                    ToolRegistrationResult.Registered(ToolLedgerState.PENDING_APPROVAL)
                ) return@withTransaction ToolApprovalCreation.CONFLICT
                else -> if (!existing.matches(identity) ||
                    existing.status != ToolLedgerState.PENDING_APPROVAL
                ) return@withTransaction ToolApprovalCreation.CONFLICT
            }
        }
        val persistedPending = assistant.pendingApprovalToolIds?.let {
            runCatching { json.decodeFromString<List<String>>(it) }.getOrNull()
        }
        val persistedRequest = database.sessionDao().getById(keySessionId)?.approvalRequest?.let {
            runCatching { json.decodeFromString<ApprovalRequest>(it) }.getOrNull()
        }
        if (persistedPending != null || persistedRequest != null) {
            val persistedCalls = assistant.toolCalls?.let {
                runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
            }
            return@withTransaction if (
                persistedCalls == distinctCalls && persistedPending == orderedPendingIds &&
                persistedRequest == request
            ) ToolApprovalCreation.EXISTING else ToolApprovalCreation.CONFLICT
        }
        val now = clock()
        check(database.messageDao().updateToolApprovalPayload(
            assistantMessageId,
            json.encodeToString(distinctCalls),
            json.encodeToString(orderedPendingIds),
        ) == 1)
        check(database.sessionDao().updateToolApprovalState(
            keySessionId,
            json.encodeToString(request),
            LoopStatus.WAITING_FOR_APPROVAL.toSerializedName(),
            now,
        ) == 1)
        ToolApprovalCreation.CREATED
    }

    override suspend fun decideExactToolApproval(
        sessionId: String,
        request: ApprovalRequest,
        approved: Boolean,
    ): ExactToolApprovalDecision = database.withTransaction {
        val context = validateExactApprovalContext(sessionId, request)
            ?: return@withTransaction ExactToolApprovalDecision.Conflict
        val target = context.entries.first()
        val key = ToolExecutionKey(sessionId, context.assistant.id, target.toolCallId)
        val sessions = database.sessionDao()
        val session = sessions.getById(sessionId) ?: return@withTransaction ExactToolApprovalDecision.Conflict
        if (approved) {
            if (dao.approve(sessionId, context.assistant.id, target.toolCallId, clock()) != 1) {
                return@withTransaction ExactToolApprovalDecision.Conflict
            }
            sessions.updateRow(session.copy(sessionAutoWakeCount = 0, sessionAutoProcessingPaused = false))
            ExactToolApprovalDecision.Decided(key, emptyList())
        } else {
            val createdAt = clock()
            val terminal = terminalMessage(
                ToolTerminalRequest(key, target.toolName, context.assistant.thoughtSignature),
                ToolLedgerState.REJECTED,
                if (target.toolName == "request_knowledge_access") "用户未授权" else "工具执行已拒绝。",
                createdAt,
            )
            if (dao.decideTerminal(
                    sessionId = sessionId,
                    assistantMessageId = context.assistant.id,
                    toolCallId = target.toolCallId,
                    allowedStatuses = setOf(ToolLedgerState.PENDING_APPROVAL),
                    terminalStatus = ToolLedgerState.REJECTED,
                    resultMessageId = terminal.id,
                    error = "用户拒绝工具执行",
                    updatedAt = createdAt,
                ) != 1
            ) return@withTransaction ExactToolApprovalDecision.Conflict
            sessions.updateRow(session.copy(sessionAutoWakeCount = 0, sessionAutoProcessingPaused = false))
            database.messageDao().insert(terminal.toEntity(sessionId))
            ExactToolApprovalDecision.Decided(key, listOf(terminal))
        }
    }

    override suspend fun completeExactToolApproval(
        sessionId: String,
        request: ApprovalRequest,
    ): ExactToolApprovalCompletion = database.withTransaction {
        val context = validateExactApprovalContext(
            sessionId,
            request,
            allowedFirstStates = setOf(
                ToolLedgerState.SUCCEEDED,
                ToolLedgerState.FAILED,
                ToolLedgerState.REJECTED,
                ToolLedgerState.CANCELLED,
                ToolLedgerState.TIMED_OUT,
            ),
        )
            ?: return@withTransaction ExactToolApprovalCompletion.Conflict
        val decided = context.entries.first()
        val stableTerminalStates = setOf(
            ToolLedgerState.SUCCEEDED,
            ToolLedgerState.FAILED,
            ToolLedgerState.REJECTED,
            ToolLedgerState.CANCELLED,
            ToolLedgerState.TIMED_OUT,
        )
        if (decided.status !in stableTerminalStates) {
            return@withTransaction ExactToolApprovalCompletion.Conflict
        }
        val remainingCalls = request.calls.drop(1)
        val nextRequest = remainingCalls.takeIf { it.isNotEmpty() }?.let {
            ToolApprovalRequestFactory.createFromIdentities(
                assistantMessageId = context.assistant.id,
                calls = it,
                reason = request.reason,
            )
        }
        val nextIds = remainingCalls.map { it.toolCallId }
        check(database.messageDao().updatePendingApprovalToolIds(
            context.assistant.id,
            nextIds.takeIf { it.isNotEmpty() }?.let(json::encodeToString),
        ) == 1)
        val nextLoopStatus = when {
            nextRequest != null -> LoopStatus.WAITING_FOR_APPROVAL
            decided.status in setOf(
                ToolLedgerState.REJECTED,
                ToolLedgerState.CANCELLED,
                ToolLedgerState.TIMED_OUT,
            ) -> LoopStatus.PAUSED
            else -> LoopStatus.RUNNING
        }
        check(database.sessionDao().updateToolApprovalState(
            sessionId,
            nextRequest?.let(json::encodeToString),
            nextLoopStatus.toSerializedName(),
            clock(),
        ) == 1)
        ExactToolApprovalCompletion.Completed(
            ApprovalTransition(
                sessionId = sessionId,
                currentAssistantMessageId = context.assistant.id,
                loopStatus = nextLoopStatus,
                approvalRequest = nextRequest,
                nextAssistantMessageId = nextRequest?.assistantMessageId,
                nextPendingToolCallIds = nextIds,
            ),
        )
    }

    private data class ExactApprovalContext(
        val assistant: com.promenar.infinity.data.local.db.entity.MessageEntity,
        val entries: List<ToolExecutionLedgerEntity>,
    )

    private suspend fun validateExactApprovalContext(
        sessionId: String,
        request: ApprovalRequest,
        allowedFirstStates: Set<ToolLedgerState> = emptySet(),
    ): ExactApprovalContext? {
        if (!ToolApprovalRequestFactory.isValid(request)) return null
        val sessionRequest = database.sessionDao().getById(sessionId)?.approvalRequest?.let {
            runCatching { json.decodeFromString<ApprovalRequest>(it) }.getOrNull()
        }
        if (sessionRequest != request) return null
        val assistantId = request.assistantMessageId ?: return null
        val assistant = database.messageDao().getById(assistantId)
            ?.takeIf { it.sessionId == sessionId && it.role == "assistant" }
            ?: return null
        val pendingIds = assistant.pendingApprovalToolIds?.let {
            runCatching { json.decodeFromString<List<String>>(it) }.getOrNull()
        } ?: return null
        if (pendingIds != request.calls.map { it.toolCallId }) return null
        val persistedCalls = assistant.toolCalls?.let {
            runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
        } ?: return null
        val byCallId = persistedCalls.associateBy { it.id }
        val entriesById = dao.getForAssistant(sessionId, assistantId).associateBy { it.toolCallId }
        val entries = request.calls.mapIndexed { index, approvalCall ->
            val persistedCall = byCallId[approvalCall.toolCallId] ?: return null
            val expectedIdentity = approvalCall.toInvocationIdentity()
            if (ToolInvocationIdentityFactory.isLegacy(expectedIdentity) ||
                persistedCall.name != expectedIdentity.toolName
            ) return null
            val arguments = ToolArgumentsValidator().validate(persistedCall.arguments)
                as? ToolArgumentsValidation.Valid ?: return null
            if (arguments.sha256 != expectedIdentity.argumentsDigest) return null
            val entry = entriesById[approvalCall.toolCallId]
                ?.takeIf { it.matches(expectedIdentity) }
                ?: return null
            val validState = entry.status == ToolLedgerState.PENDING_APPROVAL ||
                (index == 0 && entry.status in allowedFirstStates)
            if (!validState) return null
            entry
        }
        return ExactApprovalContext(assistant, entries)
    }

    override suspend fun compensateExactToolApproval(
        sessionId: String,
        request: ApprovalRequest,
        state: ToolLedgerState,
        error: String,
    ): ExactToolApprovalDecision = database.withTransaction {
        if (state !in setOf(ToolLedgerState.FAILED, ToolLedgerState.CANCELLED, ToolLedgerState.TIMED_OUT)) {
            return@withTransaction ExactToolApprovalDecision.Conflict
        }
        val context = validateExactApprovalContext(
            sessionId,
            request,
            allowedFirstStates = setOf(ToolLedgerState.APPROVED, ToolLedgerState.RUNNING),
        ) ?: return@withTransaction ExactToolApprovalDecision.Conflict
        val target = context.entries.first()
        val key = ToolExecutionKey(sessionId, context.assistant.id, target.toolCallId)
        val createdAt = clock()
        val content = when (state) {
            ToolLedgerState.CANCELLED -> "工具执行已取消。"
            ToolLedgerState.TIMED_OUT -> "工具审批已超时。"
            else -> "工具执行失败：${error.take(MAX_ERROR_LENGTH)}"
        }
        val terminal = terminalMessage(
            ToolTerminalRequest(key, target.toolName, context.assistant.thoughtSignature),
            state,
            content,
            createdAt,
        )
        if (dao.decideTerminal(
                sessionId = sessionId,
                assistantMessageId = context.assistant.id,
                toolCallId = target.toolCallId,
                allowedStatuses = setOf(ToolLedgerState.APPROVED, ToolLedgerState.RUNNING),
                terminalStatus = state,
                resultMessageId = terminal.id,
                error = error.take(MAX_ERROR_LENGTH),
                updatedAt = createdAt,
            ) != 1
        ) return@withTransaction ExactToolApprovalDecision.Conflict
        database.messageDao().insert(terminal.toEntity(sessionId))
        ExactToolApprovalDecision.Decided(key, listOf(terminal))
    }

    override suspend fun completeToolApproval(
        sessionId: String,
        assistantMessageId: String,
    ): ApprovalTransition =
        database.withTransaction {
            val persistedApprovalRaw = database.sessionDao().getById(sessionId)?.approvalRequest
            val persistedApproval = persistedApprovalRaw?.let {
                runCatching { json.decodeFromString<ApprovalRequest>(it) }.getOrNull()
            }
            check(database.messageDao().updatePendingApprovalToolIds(assistantMessageId, null) == 1)
            val terminalMessages = mutableListOf<Message>()
            val validRemaining = dao.getAwaitingApprovalForSession(sessionId)
                .groupBy { it.assistantMessageId }
                .entries
                .mapNotNull { (messageId, entries) ->
                    val message = database.messageDao().getById(messageId)
                    val callIds = message?.toolCalls?.let {
                        runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
                    }?.mapTo(mutableSetOf()) { it.id }.orEmpty()
                    if (message != null && message.sessionId == sessionId &&
                        message.role == "assistant" && entries.all { it.toolCallId in callIds }
                    ) {
                        message to entries
                    } else {
                        terminalMessages += invalidateApprovalGroup(entries, message)
                        message?.let { database.messageDao().updatePendingApprovalToolIds(it.id, null) }
                        null
                    }
                }
            val remaining = validRemaining.minWithOrNull(compareBy<Pair<com.promenar.infinity.data.local.db.entity.MessageEntity, List<ToolExecutionLedgerEntity>>> {
                    it.first.createdAt
                }.thenBy { it.first.id })
            validRemaining.filterNot { it == remaining }.forEach { (message, entries) ->
                terminalMessages += invalidateApprovalGroup(entries, message)
                database.messageDao().updatePendingApprovalToolIds(message.id, null)
            }
            if (remaining == null) {
                check(database.sessionDao().updateToolApprovalState(
                    sessionId,
                    null,
                    LoopStatus.RUNNING.toSerializedName(),
                    clock(),
                ) == 1)
                ApprovalTransition(
                    sessionId = sessionId,
                    currentAssistantMessageId = assistantMessageId,
                    loopStatus = LoopStatus.RUNNING,
                    approvalRequest = null,
                    terminalMessages = terminalMessages,
                )
            } else {
                val (message, entries) = remaining
                val orderedEntries = orderedPendingEntries(message, entries)
                val ids = orderedEntries?.map { it.toolCallId }.orEmpty()
                val nextRequest = orderedEntries?.let {
                    recoveryApprovalRequestModel(message, it, persistedApproval)
                }
                if (nextRequest == null) {
                    val invalidated = invalidateApprovalGroup(entries, message)
                    database.messageDao().updatePendingApprovalToolIds(message.id, null)
                    database.sessionDao().updateToolApprovalState(
                        sessionId,
                        null,
                        LoopStatus.PAUSED.toSerializedName(),
                        clock(),
                    )
                    return@withTransaction ApprovalTransition(
                        sessionId = sessionId,
                        currentAssistantMessageId = assistantMessageId,
                        loopStatus = LoopStatus.PAUSED,
                        approvalRequest = null,
                        terminalMessages = terminalMessages + invalidated,
                    )
                }
                database.messageDao().updatePendingApprovalToolIds(message.id, json.encodeToString(ids))
                database.sessionDao().updateToolApprovalState(
                    sessionId,
                    persistedApprovalRaw,
                    LoopStatus.WAITING_FOR_APPROVAL.toSerializedName(),
                    clock(),
                )
                ApprovalTransition(
                    sessionId = sessionId,
                    currentAssistantMessageId = assistantMessageId,
                    loopStatus = LoopStatus.WAITING_FOR_APPROVAL,
                    approvalRequest = nextRequest,
                    nextAssistantMessageId = message.id,
                    nextPendingToolCallIds = ids,
                    terminalMessages = terminalMessages,
                )
            }
        }

    override suspend fun recoverApprovalState(): Int {
        val interruptedRunningGroups = dao.getRunning()
            .mapTo(mutableSetOf()) { it.sessionId to it.assistantMessageId }
        recoverInterruptedRunning("启动恢复发现未完成的工具执行")
        database.withTransaction {
            dao.deleteOrphans()
            database.messageDao().clearPendingApprovalWithoutLedger()
        }
        dao.getUnclaimedSafeApprovals().forEach { entry ->
            recoverOrNull {
                database.withTransaction {
                    val parent = database.messageDao().getById(entry.assistantMessageId)
                        ?.takeIf { it.sessionId == entry.sessionId && it.role == "assistant" }
                    val createdAt = clock()
                    val terminal = parent?.let {
                        terminalMessage(
                            ToolTerminalRequest(
                                ToolExecutionKey(entry.sessionId, entry.assistantMessageId, entry.toolCallId),
                                entry.toolName,
                                it.thoughtSignature,
                            ),
                            ToolLedgerState.FAILED,
                            "安全工具在执行前中断，请重新发起。",
                            createdAt,
                        )
                    }
                    if (dao.invalidateAwaitingApproval(
                            entry.sessionId,
                            entry.assistantMessageId,
                            entry.toolCallId,
                            terminal?.id,
                            "安全工具未claim即进程中断",
                            createdAt,
                        ) == 1
                    ) {
                        terminal?.let { database.messageDao().insert(it.toEntity(entry.sessionId)) }
                    }
                }
            }
        }
        val sessions = dao.getAwaitingApproval().groupBy { it.sessionId }
        val activeSessions = mutableSetOf<String>()
        sessions.forEach { (sessionId, sessionEntries) ->
            val hasActive = recoverOrNull {
                database.withTransaction {
                    val persistedApprovalRaw = database.sessionDao().getById(sessionId)?.approvalRequest
                    val persistedApproval = persistedApprovalRaw?.let {
                        runCatching { json.decodeFromString<ApprovalRequest>(it) }.getOrNull()
                    }
                    val groups = sessionEntries.groupBy { it.assistantMessageId }
                    val validGroups = mutableListOf<Pair<com.promenar.infinity.data.local.db.entity.MessageEntity, List<ToolExecutionLedgerEntity>>>()
                    val invalidGroups = mutableListOf<Pair<com.promenar.infinity.data.local.db.entity.MessageEntity?, List<ToolExecutionLedgerEntity>>>()
                    groups.forEach { (assistantMessageId, entries) ->
                        val message = database.messageDao().getById(assistantMessageId)
                        val calls = message?.toolCalls?.let {
                            runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
                        }
                        val callIds = calls?.mapTo(mutableSetOf()) { it.id }.orEmpty()
                        val pendingRaw = message?.pendingApprovalToolIds
                        val decodedPending = pendingRaw?.let {
                            runCatching { json.decodeFromString<List<String>>(it) }.getOrNull()
                        }
                        val allEntries = message?.let { dao.getForAssistant(sessionId, assistantMessageId) }.orEmpty()
                        val orderedEntries = message?.let { orderedPendingEntries(it, entries) }
                        val valid = message != null && message.sessionId == sessionId &&
                            message.role == "assistant" && calls != null &&
                            pendingRaw != null && decodedPending != null &&
                            entries.all { it.toolCallId in callIds } &&
                            decodedPending.all { it in callIds } &&
                            orderedEntries != null &&
                            orderedEntries.all { it.status == ToolLedgerState.PENDING_APPROVAL } &&
                            (sessionId to assistantMessageId) !in interruptedRunningGroups &&
                            allEntries.filter {
                                it.requiresApproval && it.status in setOf(
                                    ToolLedgerState.PENDING_APPROVAL,
                                    ToolLedgerState.APPROVED,
                                    ToolLedgerState.RUNNING,
                                )
                            }.all {
                                it.status == ToolLedgerState.PENDING_APPROVAL &&
                                    it.toolCallId in decodedPending
                            }
                        if (valid) validGroups += message!! to orderedEntries!! else invalidGroups += message to entries
                    }

                    val selected = validGroups.singleOrNull { (message, entries) ->
                        recoveryApprovalRequestModel(message, entries, persistedApproval) != null
                    }
                    (invalidGroups + validGroups.filterNot { it == selected }).forEach { (message, entries) ->
                        invalidateApprovalGroup(entries, message)
                        message?.let { database.messageDao().updatePendingApprovalToolIds(it.id, null) }
                    }
                    if (selected == null) {
                        database.sessionDao().updateToolApprovalState(
                            sessionId,
                            null,
                            LoopStatus.PAUSED.toSerializedName(),
                            clock(),
                        )
                        false
                    } else {
                        val (message, entries) = selected
                        val stableEntries = orderedPendingEntries(message, entries)
                            ?: return@withTransaction false
                        database.messageDao().updatePendingApprovalToolIds(
                            message.id,
                            json.encodeToString(stableEntries.map { it.toolCallId }.distinct()),
                        )
                        val request = recoveryApprovalRequestModel(message, stableEntries, persistedApproval)
                        if (request == null) {
                            invalidateApprovalGroup(entries, message)
                            database.messageDao().updatePendingApprovalToolIds(message.id, null)
                            database.sessionDao().updateToolApprovalState(
                                sessionId,
                                null,
                                LoopStatus.PAUSED.toSerializedName(),
                                clock(),
                            )
                            false
                        } else {
                            database.sessionDao().updateToolApprovalState(
                                sessionId,
                                persistedApprovalRaw,
                                LoopStatus.WAITING_FOR_APPROVAL.toSerializedName(),
                                clock(),
                            )
                            true
                        }
                    }
                }
            } ?: false
            if (hasActive) activeSessions += sessionId
        }
        database.sessionDao().getWithToolApprovalState()
            .filterNot { it.id in activeSessions }
            .forEach { session ->
                recoverOrNull {
                    database.withTransaction {
                        database.sessionDao().updateToolApprovalState(
                            session.id,
                            null,
                            LoopStatus.PAUSED.toSerializedName(),
                            clock(),
                        )
                    }
                }
            }
        return activeSessions.size
    }

    private fun recoveryApprovalRequestModel(
        message: com.promenar.infinity.data.local.db.entity.MessageEntity,
        entries: List<ToolExecutionLedgerEntity>,
        persistedRequest: ApprovalRequest?,
    ): ApprovalRequest? {
        if (persistedRequest == null ||
            !ToolApprovalRequestFactory.isValid(persistedRequest) ||
            persistedRequest.assistantMessageId != message.id ||
            persistedRequest.toolName != null || persistedRequest.args != null ||
            persistedRequest.calls.map { it.toolCallId } != entries.map { it.toolCallId }
        ) return null
        val calls = message.toolCalls?.let {
            runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
        } ?: return null
        val callsById = calls.associateBy { it.id }
        val candidates = entries.zip(persistedRequest.calls).map { (entry, approvalCall) ->
            val call = callsById[entry.toolCallId] ?: return null
            val identity = entry.toIdentityOrNull() ?: return null
            if (ToolInvocationIdentityFactory.isLegacy(identity) ||
                identity.toolName != call.name || !identity.requiresApproval ||
                approvalCall.toInvocationIdentity() != identity
            ) return null
            val arguments = ToolArgumentsValidator().validate(call.arguments)
                as? ToolArgumentsValidation.Valid ?: return null
            if (arguments.sha256 != identity.argumentsDigest) return null
            val risk = ToolRisk.entries.singleOrNull {
                it.name.lowercase() == approvalCall.risk
            } ?: return null
            ApprovalCallCandidate(call, risk, identity, approvalCall.executionTarget)
        }
        val expected = runCatching {
            ToolApprovalRequestFactory.create(
                assistantMessageId = message.id,
                candidates = candidates,
                reason = persistedRequest.reason,
            )
        }.getOrNull() ?: return null
        return persistedRequest.takeIf { it == expected }
    }

    private fun stableLedgerEntries(entries: List<ToolExecutionLedgerEntity>) =
        entries.sortedWith(compareBy<ToolExecutionLedgerEntity> { it.createdAt }.thenBy { it.toolCallId })

    private fun orderedPendingEntries(
        message: com.promenar.infinity.data.local.db.entity.MessageEntity,
        entries: List<ToolExecutionLedgerEntity>,
    ): List<ToolExecutionLedgerEntity>? {
        val pendingIds = message.pendingApprovalToolIds?.let {
            runCatching { json.decodeFromString<List<String>>(it) }.getOrNull()
        } ?: return null
        if (pendingIds.isEmpty() || pendingIds.distinct().size != pendingIds.size) return null
        val byId = entries.associateBy { it.toolCallId }
        if (byId.size != entries.size || byId.keys != pendingIds.toSet()) return null
        return pendingIds.map { byId[it] ?: return null }
    }

    private suspend fun invalidateApprovalGroup(
        entries: List<ToolExecutionLedgerEntity>,
        parent: com.promenar.infinity.data.local.db.entity.MessageEntity?,
    ): List<Message> {
        val terminalMessages = mutableListOf<Message>()
        stableLedgerEntries(entries).forEach { entry ->
            val key = ToolExecutionKey(entry.sessionId, entry.assistantMessageId, entry.toolCallId)
            if (parent == null || parent.sessionId != entry.sessionId) return@forEach
            val createdAt = clock()
            val message = parent.takeIf { it.role == "assistant" }?.let {
                terminalMessage(
                    ToolTerminalRequest(key, entry.toolName, it.thoughtSignature),
                    ToolLedgerState.FAILED,
                    "工具审批状态无效，已安全终止，请重新发起。",
                    createdAt,
                )
            }
            if (dao.invalidateAwaitingApproval(
                    entry.sessionId,
                    entry.assistantMessageId,
                    entry.toolCallId,
                    message?.id,
                    "工具审批布局校验失败",
                    createdAt,
                ) == 1
            ) {
                message?.let {
                    database.messageDao().insert(it.toEntity(entry.sessionId))
                    terminalMessages += it
                }
            }
        }
        return terminalMessages
    }

    private suspend fun terminalizeKeys(
        keys: Set<ToolExecutionKey>,
        state: ToolLedgerState,
    ): List<Message> = terminalizeWithResults(
        keys.mapNotNullTo(mutableSetOf()) { key ->
            dao.get(key.sessionId, key.assistantMessageId, key.toolCallId)?.let {
                ToolTerminalRequest(key, it.toolName)
            }
        },
        state,
    )

    private fun terminalDecision(state: ToolLedgerState): Triple<Set<ToolLedgerState>, String, String>? =
        when (state) {
            ToolLedgerState.REJECTED -> Triple(
                setOf(ToolLedgerState.PENDING_APPROVAL),
                "用户拒绝工具执行",
                "工具执行已拒绝。",
            )
            ToolLedgerState.CANCELLED -> Triple(
                setOf(
                    ToolLedgerState.PENDING_APPROVAL,
                    ToolLedgerState.APPROVED,
                    ToolLedgerState.RUNNING,
                ),
                "工具执行已取消",
                "工具执行已取消。",
            )
            ToolLedgerState.TIMED_OUT -> Triple(
                setOf(ToolLedgerState.PENDING_APPROVAL, ToolLedgerState.APPROVED),
                "工具审批已超时",
                "工具审批已超时。",
            )
            else -> null
        }

    private fun terminalMessage(
        request: ToolTerminalRequest,
        state: ToolLedgerState,
        content: String,
        createdAt: Long,
        images: String? = null,
        viewedMedia: List<ViewedMedia>? = null,
    ) = Message(
        id = deterministicResultMessageId(request.key, state),
        role = MessageRole.TOOL,
        toolCallId = request.key.toolCallId,
        parentMessageId = request.key.assistantMessageId,
        name = request.toolName,
        content = content,
        thoughtSignature = request.thoughtSignature,
        images = images,
        createdAt = createdAt,
        viewedMedia = viewedMedia,
    )

    private fun deterministicResultMessageId(key: ToolExecutionKey, state: ToolLedgerState): String {
        val raw = "${key.sessionId}\u0000${key.assistantMessageId}\u0000${key.toolCallId}\u0000${state.name}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return "tool_result_" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun ToolExecutionLedgerEntity.matches(identity: ToolInvocationIdentity): Boolean =
        runtimeToolId == identity.runtimeToolId &&
            toolName == identity.toolName &&
            argumentsDigest == identity.argumentsDigest &&
            definitionDigest == identity.definitionDigest &&
            requiresApproval == identity.requiresApproval

    private fun ToolExecutionLedgerEntity.toIdentityOrNull(): ToolInvocationIdentity? = runCatching {
        ToolInvocationIdentity(
            runtimeToolId = runtimeToolId,
            toolName = toolName,
            argumentsDigest = argumentsDigest,
            definitionDigest = definitionDigest,
            requiresApproval = requiresApproval,
        )
    }.getOrNull()

    private companion object {
        const val MAX_ERROR_LENGTH = 256
        const val IDENTITY_CONFLICT_CONTENT = "工具调用与已登记身份不一致，已安全终止。"
    }
}

private fun sha256Hex(raw: String): String = MessageDigest.getInstance("SHA-256")
    .digest(raw.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private suspend inline fun <T> recoverOrNull(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
