package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderEndpointResolverTest {
    @Test
    fun `所有可广告 API Key 预设解析到唯一推理端点`() {
        val cases = listOf(
            Triple(ProtocolType.OpenAI_ChatCompletions, "https://api.openai.com", "https://api.openai.com/v1/chat/completions"),
            Triple(ProtocolType.OpenAI_Responses, "https://api.openai.com", "https://api.openai.com/v1/responses"),
            Triple(ProtocolType.Anthropic_Messages, "https://api.anthropic.com", "https://api.anthropic.com/v1/messages"),
            Triple(ProtocolType.DeepSeek, "https://api.deepseek.com", "https://api.deepseek.com/chat/completions"),
            Triple(ProtocolType.Moonshot_Kimi, "https://api.moonshot.cn", "https://api.moonshot.cn/v1/chat/completions"),
            Triple(ProtocolType.Qwen_DashScope, "https://dashscope.aliyuncs.com/compatible-mode", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"),
            Triple(ProtocolType.Zhipu_GLM, "https://open.bigmodel.cn/api/paas", "https://open.bigmodel.cn/api/paas/v4/chat/completions"),
            Triple(ProtocolType.Doubao_ByteDance, "https://ark.cn-beijing.volces.com/api", "https://ark.cn-beijing.volces.com/api/v3/chat/completions"),
            Triple(ProtocolType.Baichuan, "https://api.baichuan-ai.com", "https://api.baichuan-ai.com/v1/chat/completions"),
            Triple(ProtocolType.Mistral_Chat, "https://api.mistral.ai", "https://api.mistral.ai/v1/chat/completions"),
        )

        cases.forEach { (protocol, configured, expected) ->
            assertThat(
                ProviderEndpointResolver.resolve(
                    protocol,
                    configured,
                    ProviderEndpointOperation.INFERENCE,
                ),
            ).isEqualTo(expected)
        }
    }

    @Test
    fun `仅有权威且已适配 schema 的预设暴露动态模型列表`() {
        val cases = listOf(
            Triple(ProtocolType.OpenAI_ChatCompletions, "https://api.openai.com", "https://api.openai.com/v1/models"),
            Triple(ProtocolType.OpenAI_Responses, "https://api.openai.com", "https://api.openai.com/v1/models"),
            Triple(ProtocolType.Anthropic_Messages, "https://api.anthropic.com", "https://api.anthropic.com/v1/models"),
            Triple(ProtocolType.DeepSeek, "https://api.deepseek.com", "https://api.deepseek.com/models"),
            Triple(ProtocolType.Mistral_Chat, "https://api.mistral.ai", "https://api.mistral.ai/v1/models"),
        )

        cases.forEach { (protocol, configured, expected) ->
            assertThat(
                ProviderEndpointResolver.resolve(
                    protocol,
                    configured,
                    ProviderEndpointOperation.MODELS,
                ),
            ).isEqualTo(expected)
        }
    }

    @Test
    fun `没有权威 Bearer 列模适配的预设 typed unsupported 而不猜路径`() {
        val unsupported = listOf(
            ProtocolType.Moonshot_Kimi,
            ProtocolType.Qwen_DashScope,
            ProtocolType.Zhipu_GLM,
            ProtocolType.Doubao_ByteDance,
            ProtocolType.Baichuan,
            ProtocolType.Google_VertexAI,
        )

        unsupported.forEach { protocol ->
            val error = assertThrows(UnsupportedProviderOperationException::class.java) {
                ProviderEndpointResolver.resolve(
                    protocol,
                    protocol.defaultBaseUrl,
                    ProviderEndpointOperation.MODELS,
                )
            }
            assertThat(error.operation).isEqualTo(ProviderEndpointOperation.MODELS)
            assertThat(error.toString()).doesNotContain(protocol.defaultBaseUrl)
        }
    }

    @Test
    fun `已退役和未实现的历史协议保留解码但所有网络目标 fail closed`() {
        listOf(ProtocolType.Cohere_Chat, ProtocolType.Yi_ZeroOne).forEach { protocol ->
            ProviderEndpointOperation.entries.forEach { operation ->
                assertThrows(UnsupportedProviderProtocolException::class.java) {
                    ProviderEndpointResolver.resolve(protocol, protocol.defaultBaseUrl, operation)
                }
            }
        }
    }

    @Test
    fun `Generic host only endpoint fails closed instead of guessing v1`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProviderEndpointResolver.resolve(
                ProtocolType.Generic_OpenAI_Compat,
                "https://generic.example.invalid",
                ProviderEndpointOperation.INFERENCE,
            )
        }

        assertThat(error).hasMessageThat().contains("Generic OpenAI Compat")
    }

    @Test
    fun `Generic 无版本完整推理路径不能猜测模型列表端点`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderEndpointResolver.resolve(
                ProtocolType.Generic_OpenAI_Compat,
                "https://generic.example.invalid/chat/completions",
                ProviderEndpointOperation.MODELS,
            )
        }
    }

    @Test
    fun `DeepSeek 已保存 v1 完整路径原样使用且模型列表保持同一前缀`() {
        val configured = "https://api.deepseek.com/v1/chat/completions"

        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.DeepSeek,
                configured,
                ProviderEndpointOperation.INFERENCE,
            ),
        ).isEqualTo(configured)
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.DeepSeek,
                configured,
                ProviderEndpointOperation.MODELS,
            ),
        ).isEqualTo("https://api.deepseek.com/v1/models")
    }

    @Test
    fun `Vertex sync stream 与 OAuth 使用 typed target 的官方 v1 完整端点`() {
        val baseUrl = "https://us-central1-aiplatform.googleapis.com"

        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.Google_VertexAI,
                baseUrl,
                ProviderEndpointTarget.VertexInference(
                    projectId = "project-safe",
                    location = VERTEX_DEFAULT_LOCATION,
                    model = "gemini-2.5-pro",
                    streaming = false,
                ),
            ),
        ).isEqualTo(
            "https://us-central1-aiplatform.googleapis.com/v1/projects/project-safe/locations/us-central1/publishers/google/models/gemini-2.5-pro:generateContent",
        )
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.Google_VertexAI,
                baseUrl,
                ProviderEndpointTarget.VertexInference(
                    projectId = "project-safe",
                    location = VERTEX_DEFAULT_LOCATION,
                    model = "gemini-2.5-pro",
                    streaming = true,
                ),
            ),
        ).isEqualTo(
            "https://us-central1-aiplatform.googleapis.com/v1/projects/project-safe/locations/us-central1/publishers/google/models/gemini-2.5-pro:streamGenerateContent?alt=sse",
        )
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.Google_VertexAI,
                baseUrl,
                ProviderEndpointTarget.VertexOAuthToken,
            ),
        ).isEqualTo("https://oauth2.googleapis.com/token")
    }

    @Test
    fun `host 与版本前缀只补齐缺失的推理路径`() {
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.OpenAI_ChatCompletions,
                "https://api.openai.com",
                ProviderEndpointOperation.INFERENCE,
            ),
        ).isEqualTo("https://api.openai.com/v1/chat/completions")
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.OpenAI_ChatCompletions,
                "https://api.openai.com/v1/",
                ProviderEndpointOperation.INFERENCE,
            ),
        ).isEqualTo("https://api.openai.com/v1/chat/completions")
    }

    @Test
    fun `完整协议路径规范化后保持原样且不产生双路径`() {
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.OpenAI_Responses,
                "https://example.test/v1/responses/",
                ProviderEndpointOperation.INFERENCE,
            ),
        ).isEqualTo("https://example.test/v1/responses")
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.Anthropic_Messages,
                "https://example.test/v1/messages",
                ProviderEndpointOperation.INFERENCE,
            ),
        ).isEqualTo("https://example.test/v1/messages")
    }

    @Test
    fun `完整 chat inference 路径保持配置版本且不再追加默认路径`() {
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.Generic_OpenAI_Compat,
                "https://generic.example.invalid/chat/completions",
                ProviderEndpointOperation.INFERENCE,
            ),
        ).isEqualTo("https://generic.example.invalid/chat/completions")
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.OpenAI_ChatCompletions,
                "https://proxy.example.invalid/v2/chat/completions",
                ProviderEndpointOperation.INFERENCE,
            ),
        ).isEqualTo("https://proxy.example.invalid/v2/chat/completions")
    }

    @Test fun `Vertex官方端点接受HTTP`() {
        assertThat(ProviderEndpointResolver.resolve(
            ProtocolType.Google_VertexAI, "http://us-central1-aiplatform.googleapis.com",
            ProviderEndpointTarget.VertexInference("project-safe", VERTEX_DEFAULT_LOCATION, "gemini-2.5-pro", false),
        )).startsWith("http://us-central1-aiplatform.googleapis.com/")
    }

    @Test
    fun `Vertex inference 只接受 location 对应的官方 HTTP 或 HTTPS origin`() {
        listOf(
            "https://attacker.example.invalid",
            "https://europe-west1-aiplatform.googleapis.com",
            "https://us-central1-aiplatform.googleapis.com.attacker.example",
            "https://us-central1-aiplatform.googleapis.com/custom-prefix",
        ).forEach { configured ->
            assertThrows(IllegalArgumentException::class.java) {
                ProviderEndpointResolver.resolve(
                    ProtocolType.Google_VertexAI,
                    configured,
                    ProviderEndpointTarget.VertexInference(
                        projectId = "project-safe",
                        location = VERTEX_DEFAULT_LOCATION,
                        model = "gemini-2.5-pro",
                        streaming = false,
                    ),
                )
            }
        }

        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.Google_VertexAI,
                "https://aiplatform.googleapis.com",
                ProviderEndpointTarget.VertexInference(
                    projectId = "project-safe",
                    location = "global",
                    model = "gemini-2.5-pro",
                    streaming = false,
                ),
            ),
        ).isEqualTo(
            "https://aiplatform.googleapis.com/v1/projects/project-safe/locations/global/publishers/google/models/gemini-2.5-pro:generateContent",
        )
    }

    @Test
    fun `Vertex 官方 origin 拒绝 userinfo 显式端口与 query`() {
        listOf(
            "https://user@us-central1-aiplatform.googleapis.com",
            "https://us-central1-aiplatform.googleapis.com:443",
            "https://us-central1-aiplatform.googleapis.com?alt=sse",
        ).forEach { configured ->
            assertThrows(IllegalArgumentException::class.java) {
                ProviderEndpointResolver.resolve(
                    ProtocolType.Google_VertexAI,
                    configured,
                    ProviderEndpointTarget.VertexInference(
                        projectId = "project-safe",
                        location = VERTEX_DEFAULT_LOCATION,
                        model = "gemini-2.5-pro",
                        streaming = false,
                    ),
                )
            }
        }
    }

    @Test
    fun `Vertex 非默认 region 使用匹配的官方 regional origin`() {
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.Google_VertexAI,
                "https://europe-west4-aiplatform.googleapis.com",
                ProviderEndpointTarget.VertexInference(
                    projectId = "project-safe",
                    location = "europe-west4",
                    model = "gemini-2.5-pro",
                    streaming = false,
                ),
            ),
        ).isEqualTo(
            "https://europe-west4-aiplatform.googleapis.com/v1/projects/project-safe/locations/europe-west4/publishers/google/models/gemini-2.5-pro:generateContent",
        )
    }

    @Test
    fun `Vertex project location model 分别拒绝 dot segment`() {
        listOf(
            Triple(".", VERTEX_DEFAULT_LOCATION, "gemini-2.5-pro"),
            Triple("project-safe", "..", "gemini-2.5-pro"),
            Triple("project-safe", VERTEX_DEFAULT_LOCATION, "."),
        ).forEach { (project, location, model) ->
            assertThrows(IllegalArgumentException::class.java) {
                ProviderEndpointResolver.resolve(
                    ProtocolType.Google_VertexAI,
                    "",
                    ProviderEndpointTarget.VertexInference(
                        projectId = project,
                        location = location,
                        model = model,
                        streaming = false,
                    ),
                )
            }
        }
    }

    @Test
    fun `厂商前缀保留并拼接各自版本路径`() {
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.Qwen_DashScope,
                "https://dashscope.aliyuncs.com/compatible-mode",
                ProviderEndpointOperation.INFERENCE,
            ),
        ).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.Zhipu_GLM,
                "https://open.bigmodel.cn/api/paas/",
                ProviderEndpointOperation.INFERENCE,
            ),
        ).isEqualTo("https://open.bigmodel.cn/api/paas/v4/chat/completions")
    }

    @Test
    fun `模型清单从完整推理路径回到相同协议前缀`() {
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.OpenAI_ChatCompletions,
                "https://proxy.test/provider/v1/chat/completions",
                ProviderEndpointOperation.MODELS,
            ),
        ).isEqualTo("https://proxy.test/provider/v1/models")
    }

    @Test
    fun `模型清单拒绝从歧义的完整推理资源路径继续向下拼接`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProviderEndpointResolver.resolve(
                ProtocolType.OpenAI_ChatCompletions,
                "https://proxy.test/vendor/chat/completions",
                ProviderEndpointOperation.MODELS,
            )
        }

        assertThat(error).hasMessageThat().isEqualTo(
            "Provider base URL 的完整推理路径无法无歧义转换为模型列表端点",
        )
    }

    @Test
    fun `Anthropic 连接探测使用权威无成本端点且不依赖非空模型列表`() {
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.Anthropic_Messages,
                "https://api.anthropic.com",
                ProviderEndpointOperation.CONNECTION_PROBE,
            ),
        ).isEqualTo("https://api.anthropic.com/v1/models?limit=1")
    }
}
