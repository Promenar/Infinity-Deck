package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.data.remote.protocol.PromptRequest
import org.junit.Test

class ApproximateTokenEstimatorTest {
    @Test
    fun `中英文估算兼容既有单位并保留短ASCII余量`() {
        assertThat(ApproximateTokenEstimator.estimate("abcd")).isEqualTo(1)
        assertThat(ApproximateTokenEstimator.estimate("中文")).isEqualTo(2)
        assertThat(ApproximateTokenEstimator.estimate("a")).isEqualTo(1)
        assertThat(ApproximateTokenEstimator.estimate("")).isEqualTo(0)
    }

    @Test
    fun `用户上下文计入预算而非用户字段不增加输入`() {
        val user = ProtocolMessage("user", "输入")
        val block = "资料".repeat(100)
        assertThat(ApproximateTokenEstimator.estimate(user.copy(turnContext = block)))
            .isEqualTo(ApproximateTokenEstimator.estimate(user) + ApproximateTokenEstimator.estimate(block) + 1)
        val assistant = ProtocolMessage("assistant", "回复")
        assertThat(ApproximateTokenEstimator.estimate(assistant.copy(turnContext = block)))
            .isEqualTo(ApproximateTokenEstimator.estimate(assistant))
    }

    @Test
    fun `相同对话增大工具定义会计入请求预算`() {
        val prompt = PromptRequest(model = "model", messages = listOf(ProtocolMessage("user", "读取资料")))
        val withTools = prompt.copy(tools = listOf(ProtocolTool(
            function = ProtocolToolFunction("read_file", "工具说明".repeat(100), "{}"),
        )))
        assertThat(ApproximateTokenEstimator.estimate(withTools))
            .isGreaterThan(ApproximateTokenEstimator.estimate(prompt) + 300)
    }

    @Test fun `媒体乘法溢出边界按正数饱和`() {
        val message = ProtocolMessage("user", "输入", videoData = List(32768) {
            com.promenar.infinity.data.remote.protocol.VideoInput("")
        })
        assertThat(ApproximateTokenEstimator.estimate(message)).isEqualTo(Int.MAX_VALUE)
        assertThat(ApproximateTokenEstimator.estimate(PromptRequest(model = "demo", messages = listOf(message))))
            .isEqualTo(Int.MAX_VALUE)
    }
}
