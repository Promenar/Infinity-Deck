package com.promenar.infinity.data.remote.mcp

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class McpConnectionSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("mcp_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    private val secrets = MemoryMcpSecrets()

    @Test fun `启动清除旧开关且HTTP无需开关或令牌`() {
        preferences.edit().putBoolean("mcp_allow_loopback_http", true).putBoolean("unrelated", true).commit()
        val settings = McpConnectionSettings(preferences, secrets)
        assertThat(preferences.contains("mcp_allow_loopback_http")).isFalse()
        assertThat(preferences.getBoolean("unrelated", false)).isTrue()
        for (host in listOf("127.0.0.1", "192.168.1.2", "remote.example.test")) {
            assertThat(settings.isAllowed("server", "http", "http://$host/mcp")).isTrue()
        }
    }

    @Test
    fun `Bearer仅进入SecretStore且重建后可读取与删除`() = runTest {
        val settings = McpConnectionSettings(preferences, secrets)
        settings.saveBearerToken("local", "test-only-bearer")
        assertThat(settings.hasBearerToken("local")).isTrue()
        assertThat(settings.isAllowed("local", "http", "http://127.0.0.1:8765/mcp")).isTrue()
        assertThat(secrets.values.values.map { it.toString(Charsets.UTF_8) }).containsExactly("test-only-bearer")
        assertThat(preferences.all.values.map(Any?::toString)).doesNotContain("test-only-bearer")
        assertThat(McpConnectionSettings(preferences, secrets).readBearerToken("local")).isEqualTo("test-only-bearer")
        assertThat(settings.readBearerToken("another")).isNull()
        settings.removeBearerToken("local")
        assertThat(secrets.values).isEmpty()
        assertThat(settings.isAllowed("local", "http", "http://127.0.0.1:8765/mcp")).isTrue()
    }

    @Test
    fun `非法令牌不得写入且错误不回显输入`() = runTest {
        val settings = McpConnectionSettings(preferences, secrets)
        listOf("", " ", "invalid\r\nsecret", "Bearer token").forEach { token ->
            val failure = runCatching { settings.saveBearerToken("local", token) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure?.message).isEqualTo("MCP_BEARER_TOKEN_INVALID")
        }
        assertThat(secrets.values).isEmpty()
    }

    @Test
    fun `SecretStore不可读取时拒绝接入且不暴露底层错误`() = runTest {
        val unavailable = object : SecretStore {
            override fun put(id: SecretId, value: ByteArray) = error("private-value")
            override fun get(id: SecretId): ByteArray? = error("private-value")
            override fun contains(id: SecretId): Boolean = error("private-value")
            override fun remove(id: SecretId) = error("private-value")
        }
        val settings = McpConnectionSettings(preferences, unavailable)
        assertThat(settings.isAllowed("local", "http", "http://127.0.0.1:8765/mcp")).isTrue()
        val failure = runCatching { settings.readBearerToken("local") }.exceptionOrNull()
        assertThat(failure?.message).isEqualTo("MCP_CREDENTIAL_UNAVAILABLE")
        // 协程栈恢复可保留已脱敏的异常副本；完整可见异常链不得含底层秘密。
        assertThat(failure!!.stackTraceToString()).doesNotContain("private-value")
    }
}

internal class MemoryMcpSecrets : SecretStore {
    val values = mutableMapOf<SecretId, ByteArray>()
    override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
    override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
    override fun contains(id: SecretId): Boolean = id in values
    override fun remove(id: SecretId) { values.remove(id) }
}
