package com.promenar.infinity.data.model

import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.domain.model.ExecutionModeCodec
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

private inline fun <reified T> encodeToJson(value: T): String =
    json.encodeToString(serializer<T>(), value)

private inline fun <reified T> decodeFromJson(text: String): T =
    json.decodeFromString(serializer<T>(), text)

private fun decodeMessageDocumentEnvelopeOrNull(text: String): MessageDocumentEnvelope? = try {
    decodeFromJson<MessageDocumentEnvelope>(text)
        .takeIf {
            it.schema == MessageDocumentEnvelope.SCHEMA &&
                (it.documents != null || it.viewedMedia != null || it.legacyFilesPayload != null)
        }
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

internal fun decodeTextDocumentAttachmentsOrNull(text: String): List<MessageDocumentAttachment>? =
    decodeMessageDocumentEnvelopeOrNull(text)?.documents

internal fun decodeKgPathsOrNull(
    text: String,
    decoder: (String) -> List<KgPath> = { decodeFromJson<List<KgPath>>(it) },
): List<KgPath>? = try {
    decoder(text)
} catch (_: SerializationException) {
    logKgPathsDecodeFailure()
    null
} catch (_: IllegalArgumentException) {
    logKgPathsDecodeFailure()
    null
}

private fun logKgPathsDecodeFailure() {
    InfinityLogger.logError(
        "MessageMapper.KgPathsDecode",
        IllegalArgumentException("kgPaths JSON 解析失败"),
    )
}

internal fun decodeMessageQuotesOrNull(text: String): List<MessageQuote>? = try {
    decodeFromJson<List<MessageQuote>>(text).takeIf { it.isNotEmpty() }
} catch (_: SerializationException) {
    logMessageQuotesDecodeFailure()
    null
} catch (_: IllegalArgumentException) {
    logMessageQuotesDecodeFailure()
    null
}

private fun logMessageQuotesDecodeFailure() {
    InfinityLogger.logError(
        "MessageMapper.QuotesDecode",
        IllegalArgumentException("quotes JSON 解析失败"),
    )
}

internal fun MessageEntity.contentForModel(): String =
    if (role == "user") MessageQuoteFormatter.formatUserContent(content, quotes?.let(::decodeMessageQuotesOrNull)) else content

fun SessionEntity.toDomain(): Session = Session(
    id = id,
    agentId = agentId,
    title = title,
    lastMessage = lastMessage,
    time = time,
    unread = unread,
    modelId = modelId,
    customPrompt = customPrompt,
    isPinned = isPinned == 1,
    scrollOffset = scrollOffset,
    draft = draft,
    executionMode = ExecutionModeCodec.serialize(ExecutionModeCodec.parseOrWorkspace(executionMode)),
    loopStatus = LoopStatus.fromSerializedName(loopStatus),
    pendingIntervention = pendingIntervention,
    approvalRequest = approvalRequest?.let { decodeFromJson<ApprovalRequest>(it) },
    messages = emptyList(),
    ragOptions = ragOptions?.let { decodeFromJson<RagOptions>(it) },
    inferenceParams = inferenceParams?.let { decodeFromJson<InferenceParams>(it) },
    activeTask = activeTask?.let { decodeFromJson<TaskState>(it) },
    stats = stats?.let { decodeFromJson<SessionStats>(it) },
    options = options?.let { decodeFromJson<SessionOptions>(it) } ?: SessionOptions(),
    activeMcpServerIds = activeMcpServerIds?.let { decodeFromJson<List<String>>(it) } ?: emptyList(),
    activeSkillIds = activeSkillIds?.let { decodeFromJson<List<String>>(it) } ?: emptyList(),
    workspacePath = workspacePath,
    workspaceRootUuid = workspaceRootUuid,
    activeTaskTreeId = activeTaskTreeId,
    workspaceId = workspaceId,
    runMode = SessionRunMode.fromStored(runMode),
    parentSessionId = parentSessionId,
    branchPlanJson = branchPlanJson,
    worktreeDirectory = worktreeDirectory,
    worktreeBranch = worktreeBranch,
    worktreeBase = worktreeBase,
    parentSessionDeleted = parentSessionDeleted,
    sessionAutoWakeCount = sessionAutoWakeCount,
    sessionAutoProcessingPaused = sessionAutoProcessingPaused,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Session.toEntity(): SessionEntity = SessionEntity(
    id = id,
    agentId = agentId,
    title = title,
    lastMessage = lastMessage,
    time = time,
    unread = unread,
    modelId = modelId,
    customPrompt = customPrompt,
    isPinned = if (isPinned) 1 else 0,
    scrollOffset = scrollOffset,
    draft = draft,
    executionMode = ExecutionModeCodec.serialize(ExecutionModeCodec.parseOrWorkspace(executionMode)),
    loopStatus = loopStatus.toSerializedName(),
    pendingIntervention = pendingIntervention,
    approvalRequest = approvalRequest?.let { encodeToJson(it) },
    ragOptions = ragOptions?.let { encodeToJson(it) },
    inferenceParams = inferenceParams?.let { encodeToJson(it) },
    activeTask = activeTask?.let { encodeToJson(it) },
    stats = stats?.let { encodeToJson(it) },
    options = options?.let { encodeToJson(it) },
    activeMcpServerIds = if (activeMcpServerIds.isNotEmpty()) encodeToJson(activeMcpServerIds) else null,
    activeSkillIds = if (activeSkillIds.isNotEmpty()) encodeToJson(activeSkillIds) else null,
    workspacePath = workspacePath,
    workspaceRootUuid = workspaceRootUuid,
    activeTaskTreeId = activeTaskTreeId,
    workspaceId = workspaceId,
    runMode = runMode?.name,
    parentSessionId = parentSessionId,
    branchPlanJson = branchPlanJson,
    worktreeDirectory = worktreeDirectory,
    worktreeBranch = worktreeBranch,
    worktreeBase = worktreeBase,
    parentSessionDeleted = parentSessionDeleted,
    sessionAutoWakeCount = sessionAutoWakeCount,
    sessionAutoProcessingPaused = sessionAutoProcessingPaused,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun MessageEntity.toDomain(): Message {
    val envelope = files?.let(::decodeMessageDocumentEnvelopeOrNull)
    return Message(
        id = id,
        role = MessageRole.fromSerializedName(role),
        content = content,
        modelId = modelId,
        status = status,
        reasoning = reasoning,
        thoughtSignature = thoughtSignature,
        images = images,
        userDocuments = envelope?.documents,
        quotes = quotes?.let(::decodeMessageQuotesOrNull),
        legacyFilesPayload = if (envelope != null) envelope.legacyFilesPayload else files,
        legacyAttachmentsPayload = legacyAttachments,
        userImages = userImages?.let { decodeFromJson<List<String>>(it) },
        tokens = tokens?.let { decodeFromJson<TokenUsage>(it) },
        citations = citations?.let { decodeFromJson<List<Citation>>(it) },
        ragReferences = ragReferences?.let { decodeFromJson<List<RagReference>>(it) },
        kgPaths = kgPaths?.let(::decodeKgPathsOrNull),
        ragProgress = ragProgress?.let { decodeFromJson<RagProgress>(it) },
        ragMetadata = ragMetadata?.let { decodeFromJson<RagMetadata>(it) },
        ragReferencesLoading = ragReferencesLoading == 1,
        executionSteps = executionSteps?.let { decodeFromJson<List<ExecutionStep>>(it) },
        toolCalls = toolCalls?.let { decodeFromJson<List<ToolCall>>(it) },
        pendingApprovalToolIds = pendingApprovalToolIds?.let { decodeFromJson<List<String>>(it) },
        toolCallId = toolCallId,
        parentMessageId = parentMessageId,
        name = name,
        planningTask = planningTask?.let { decodeFromJson<TaskState>(it) },
        isArchived = isArchived == 1,
        vectorizationStatus = vectorizationStatus,
        layoutHeight = layoutHeight,
        toolResults = toolResults?.let { decodeFromJson<List<ToolResultArtifact>>(it) },
        isError = isError == 1,
        errorMessage = errorMessage,
        createdAt = createdAt,
        viewedMedia = envelope?.viewedMedia,
        turnContext = turnContext,
        requestInstruction = requestInstruction,
    )
}

fun Message.toEntity(sessionId: String): MessageEntity = MessageEntity(
    id = id,
    sessionId = sessionId,
    role = role.toSerializedName(),
    content = content,
    modelId = modelId,
    status = status,
    reasoning = reasoning,
    thoughtSignature = thoughtSignature,
    images = images,
    files = if (userDocuments != null || viewedMedia != null) {
        encodeToJson(
            MessageDocumentEnvelope(
                documents = userDocuments,
                viewedMedia = viewedMedia,
                legacyFilesPayload = legacyFilesPayload,
            ),
        )
    } else legacyFilesPayload,
    legacyAttachments = legacyAttachmentsPayload,
    userImages = userImages?.let { encodeToJson(it) },
    quotes = quotes?.takeIf { it.isNotEmpty() }?.let { encodeToJson(it) },
    tokens = tokens?.let { encodeToJson(it) },
    citations = citations?.let { encodeToJson(it) },
    ragReferences = ragReferences?.let { encodeToJson(it) },
    kgPaths = kgPaths?.let { encodeToJson(it) },
    ragProgress = ragProgress?.let { encodeToJson(it) },
    ragMetadata = ragMetadata?.let { encodeToJson(it) },
    ragReferencesLoading = if (ragReferencesLoading) 1 else 0,
    executionSteps = executionSteps?.let { encodeToJson(it) },
    toolCalls = toolCalls?.let { encodeToJson(it) },
    pendingApprovalToolIds = pendingApprovalToolIds?.let { encodeToJson(it) },
    toolCallId = toolCallId,
    parentMessageId = parentMessageId,
    name = name,
    planningTask = planningTask?.let { encodeToJson(it) },
    isArchived = if (isArchived) 1 else 0,
    vectorizationStatus = vectorizationStatus,
    layoutHeight = layoutHeight,
    toolResults = toolResults?.let { encodeToJson(it) },
    isError = if (isError) 1 else 0,
    errorMessage = errorMessage,
    createdAt = createdAt,
    turnContext = turnContext,
    requestInstruction = requestInstruction,
)
