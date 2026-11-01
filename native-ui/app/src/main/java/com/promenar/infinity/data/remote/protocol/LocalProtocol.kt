package com.promenar.infinity.data.remote.protocol

import com.promenar.infinity.data.local.inference.LocalInferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.promenar.infinity.domain.generation.CompletionReason

class LocalProtocol(
    private val engine: LocalInferenceEngine,
    private val modelName: String = ""
) : LlmProtocol {

    override val protocolType: ProtocolType = ProtocolType.Local

    private val promptTemplate: PromptTemplate by lazy {
        detectTemplate(modelName)
    }

    override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
        val formattedPrompt = promptTemplate.format(
            messages = localMessagesWithToolMedia(request.messages),
            tools = request.tools?.canonicalizedForRequest().takeIf { request.toolChoice != ProtocolToolChoice.NONE },
        )

        val genConfig = ProtocolParamAdapter.buildGenerateConfig(request)

        engine.generate(formattedPrompt, genConfig).collect { tokenText ->
            emit(StreamChunk.TextDelta(content = tokenText))
        }

        emit(StreamChunk.Completed(CompletionReason.END_TURN))
    }

    override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
        val formattedPrompt = promptTemplate.format(
            messages = localMessagesWithToolMedia(request.messages),
            tools = request.tools?.canonicalizedForRequest().takeIf { request.toolChoice != ProtocolToolChoice.NONE },
        )

        val genConfig = ProtocolParamAdapter.buildGenerateConfig(request)
        val result = StringBuilder()

        engine.generate(formattedPrompt, genConfig).collect { tokenText ->
            result.append(tokenText)
        }

        return PromptResponse(content = result.toString())
    }

    override suspend fun listModels(): List<String> {
        return listOf(modelName.ifEmpty { "Local Model" })
    }

    override fun cancel() {
        // Kotlin Flow cancellation handles this automatically
    }

    private fun detectTemplate(name: String): PromptTemplate {
        val lower = name.lowercase()
        return when {
            "llama-3" in lower || "llama3" in lower -> Llama3Template()
            "qwen" in lower -> ChatMLTemplate()
            "mistral" in lower -> MistralTemplate()
            "gemma" in lower -> GemmaTemplate()
            else -> ChatMLTemplate()
        }
    }
}

sealed class PromptTemplate {
    abstract fun format(messages: List<ProtocolMessage>, tools: List<ProtocolTool>?): String
}

class ChatMLTemplate : PromptTemplate() {
    override fun format(messages: List<ProtocolMessage>, tools: List<ProtocolTool>?): String {
        val sb = StringBuilder()
        for (msg in messages) {
            sb.append("<|im_start|>${msg.role}\n${msg.textWithTurnContext()}<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}

class Llama3Template : PromptTemplate() {
    override fun format(messages: List<ProtocolMessage>, tools: List<ProtocolTool>?): String {
        val sb = StringBuilder("<|begin_of_text|>")
        for (msg in messages) {
            sb.append("<|start_header_id|>${msg.role}<|end_header_id|>\n\n")
            sb.append("${msg.textWithTurnContext()}<|eot_id|>")
        }
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }
}

class MistralTemplate : PromptTemplate() {
    override fun format(messages: List<ProtocolMessage>, tools: List<ProtocolTool>?): String {
        val sb = StringBuilder("<s>")
        val systemMsg = messages.find { it.role == "system" }
        val userMsgs = messages.filter { it.role == "user" }

        if (systemMsg != null) {
            sb.append("[INST] ${systemMsg.content}\n\n")
        } else {
            sb.append("[INST] ")
        }

        val lastUser = userMsgs.lastOrNull()
        if (lastUser != null) {
            sb.append("${lastUser.textWithTurnContext()} [/INST]")
        }

        return sb.toString()
    }
}

class GemmaTemplate : PromptTemplate() {
    override fun format(messages: List<ProtocolMessage>, tools: List<ProtocolTool>?): String {
        val sb = StringBuilder("<bos>")
        for (msg in messages) {
            val role = if (msg.role == "assistant") "model" else msg.role
            sb.append("<start_of_turn>$role\n${msg.textWithTurnContext()}<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }
}
