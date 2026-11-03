package com.promenar.infinity.data.remote.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 各协议 usage 字段到 [ProtocolUsage] 的统一归一。
 *
 * 统一语义：`input` 为本轮全部输入（含缓存读取与缓存写入部分），缓存字段是其子集；
 * `output` 含推理 Token，推理字段是其子集。可空字段为 null 表示服务商未报告，0 表示报告为 0。
 */
internal object ProtocolUsageParsers {

    /** OpenAI Chat Completions 及兼容端点（含 DeepSeek 的 prompt_cache_hit/miss 字段）。 */
    fun openAiChat(usage: JsonObject): ProtocolUsage {
        val promptDetails = usage["prompt_tokens_details"] as? JsonObject
        val completionDetails = usage["completion_tokens_details"] as? JsonObject
        val cacheHit = usage.reportedCount("prompt_cache_hit_tokens")
        val cacheMiss = usage.reportedCount("prompt_cache_miss_tokens")
        val input = usage.reportedCount("prompt_tokens")
            ?: if (cacheHit != null && cacheMiss != null) saturatingAdd(cacheHit, cacheMiss) else null
        return ProtocolUsage(
            input = input ?: 0,
            output = usage.count("completion_tokens") ?: 0,
            total = usage.count("total_tokens") ?: 0,
            cacheReadInput = promptDetails?.count("cached_tokens") ?: cacheHit ?: usage.count("cached_tokens"),
            cacheWriteInput = promptDetails?.count("cache_write_tokens") ?: promptDetails?.count("cache_creation_input_tokens"),
            cacheWriteLongTtlInput = (promptDetails?.get("cache_write_tokens_details") as? JsonObject)?.count("ttl_12h_tokens"),
            reasoningOutput = completionDetails?.count("reasoning_tokens"),
            inputReported = input != null,
        )
    }

    /** OpenAI Responses API：input_tokens 已含缓存部分，output_tokens 已含推理部分。 */
    fun openAiResponses(usage: JsonObject): ProtocolUsage {
        val reportedInput = usage.reportedCount("input_tokens")
        val input = reportedInput ?: 0
        val output = usage.count("output_tokens") ?: 0
        return ProtocolUsage(
            input = input,
            output = output,
            total = usage.count("total_tokens") ?: saturatingAdd(input, output),
            cacheReadInput = (usage["input_tokens_details"] as? JsonObject)?.count("cached_tokens"),
            cacheWriteInput = (usage["input_tokens_details"] as? JsonObject)?.count("cache_write_tokens"),
            cacheWriteLongTtlInput = ((usage["input_tokens_details"] as? JsonObject)?.get("cache_write_tokens_details") as? JsonObject)?.count("ttl_12h_tokens"),
            reasoningOutput = (usage["output_tokens_details"] as? JsonObject)?.count("reasoning_tokens"),
            inputReported = reportedInput != null,
        )
    }

    /**
     * Anthropic Messages：input_tokens 不含缓存部分，归一后 input = input_tokens + 缓存读取 + 缓存写入。
     * [start] 为已保存的累计 usage，[delta] 为 message_delta 的 usage；同名字段以 delta 为准。
     */
    fun anthropic(start: JsonObject?, delta: JsonObject? = null): ProtocolUsage {
        fun field(key: String): Int? = delta?.count(key) ?: start?.count(key)
        val reportedUncached = delta?.reportedCount("input_tokens") ?: start?.reportedCount("input_tokens")
        val uncached = reportedUncached ?: 0
        val cacheRead = field("cache_read_input_tokens")
        val cacheWrite = field("cache_creation_input_tokens")
        val input = saturatingAdd(saturatingAdd(uncached, cacheRead ?: 0), cacheWrite ?: 0)
        val output = field("output_tokens") ?: 0
        return ProtocolUsage(
            input = input,
            output = output,
            total = saturatingAdd(input, output),
            cacheReadInput = cacheRead,
            cacheWriteInput = cacheWrite,
            cacheWriteLongTtlInput = (delta?.get("cache_creation") as? JsonObject)?.count("ephemeral_1h_input_tokens")
                ?: (start?.get("cache_creation") as? JsonObject)?.count("ephemeral_1h_input_tokens"),
            inputReported = reportedUncached != null,
        )
    }

    /** Gemini / Vertex：提示输入含缓存，再计入服务端工具输入；思考 Token 并入 output 并另记推理。 */
    fun gemini(metadata: JsonObject): ProtocolUsage {
        val thoughts = metadata.count("thoughtsTokenCount")
        val prompt = metadata.reportedCount("promptTokenCount")
        return ProtocolUsage(
            input = saturatingAdd(prompt ?: 0, metadata.count("toolUsePromptTokenCount") ?: 0),
            output = saturatingAdd(metadata.count("candidatesTokenCount") ?: 0, thoughts ?: 0),
            total = metadata.count("totalTokenCount") ?: 0,
            cacheReadInput = metadata.count("cachedContentTokenCount"),
            reasoningOutput = thoughts,
            inputReported = prompt != null,
        )
    }

    private fun JsonObject.count(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0)

    private fun JsonObject.reportedCount(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }

    private fun saturatingAdd(a: Int, b: Int): Int =
        (a.toLong() + b.toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
