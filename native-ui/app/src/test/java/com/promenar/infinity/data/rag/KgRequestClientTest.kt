package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.PromptResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class KgRequestClientTest {
    @Test
    fun `默认无输出上限且保留图谱调用指定的正数上限`() = runTest {
        val requests = mutableListOf<PromptRequest>()
        val protocol = mockk<LlmProtocol> {
            coEvery { sendPromptSync(capture(requests)) } returns PromptResponse("摘要")
        }
        val client = testKgClient(protocol)
        val parse: (String) -> ExtractionResult = { ExtractionResult(emptyList(), emptyList()) }

        assertThat(client.extract("owner::model", "指令", "数据", 5_000, responseParser = parse).isSuccess).isTrue()
        assertThat(client.extract("owner::model", "指令", "数据", 5_000, 512, responseParser = parse).isSuccess).isTrue()
        val invalid = runCatching { client.extract("owner::model", "指令", "数据", 5_000, 0) }

        assertThat(invalid.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(requests.map(PromptRequest::maxTokens)).containsExactly(null, 512).inOrder()
    }
}
