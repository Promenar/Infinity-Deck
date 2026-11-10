package com.promenar.infinity.data.pet

import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.data.rag.KgTokenUsage
import com.promenar.infinity.data.rag.KgUsageRecord
import com.promenar.infinity.data.rag.KgUsageStore
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.domain.pet.PetLineLimiter
import com.promenar.infinity.domain.pet.PetLinePromptBuilder
import com.promenar.infinity.domain.pet.PetPersona
import com.promenar.infinity.domain.pet.cleanPetLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PetLineGenerator(
    private val preferences: PetPreferenceStore,
    private val usageStore: KgUsageStore,
    private val request: suspend (PromptRequest, (TokenUsage) -> Unit) -> Pair<String, TokenUsage>?,
    private val limiter: PetLineLimiter = PetLineLimiter(System::currentTimeMillis),
) {
    suspend fun generate(persona: PetPersona, signal: String, data: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            val modelId = preferences.linesModelId.value
            if (!preferences.linesEnabled.value || modelId.isBlank() || !limiter.tryAcquire()) return@withContext null
            val prompt = PetLinePromptBuilder.build(persona, signal, data)
            var usage: TokenUsage? = null
            try {
                withTimeoutOrNull(10_000) {
                    val result = request(
                        PromptRequest(
                            messages = listOf(ProtocolMessage("system", prompt.system), ProtocolMessage("user", prompt.user)),
                            model = modelId,
                            maxTokens = 256,
                            stream = false,
                        ),
                    ) { usage = it }
                    if (result != null) {
                        usage = result.second
                        cleanPetLine(result.first)
                    } else null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                usage?.let { recorded ->
                    withContext(NonCancellable) {
                        runCatching {
                            usageStore.record(KgUsageRecord(modelId, KgTokenUsage(
                                recorded.input.toLong(), recorded.output.toLong(), recorded.estimated,
                            )))
                        }
                    }
                }
            }
        }

    fun recordFallbackLine() {
        limiter.recordLine()
    }
}
