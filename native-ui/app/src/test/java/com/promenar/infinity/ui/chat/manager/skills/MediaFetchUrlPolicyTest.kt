package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.media.MediaFetchUrlPolicy
import org.junit.Test

class MediaFetchUrlPolicyTest {
    private val policy = MediaFetchUrlPolicy { it == "main" || it == "main.private.test" }
    @Test fun `允许各类目标的HTTP与HTTPS`() {
        listOf("http://example.test/a", "http://192.168.1.2/a", "http://100.63.255.255/a", "http://100.128.0.0/a", "http://[fd7a:115c:a1e1::1]/a", "https://example.test/a", "http://main/a", "http://main.private.test/a", "http://main.tail.ts.net/a", "http://100.64.0.1/a", "http://100.127.255.255/a", "http://[fd7a:115c:a1e0::1]/a", "http://127.0.0.1/a", "http://127.1.2.3/a", "http://localhost/a", "http://[::1]/a").forEach {
            assertThat(policy.validate(it)).isNull()
        }
    }
    @Test fun `拒绝无效协议凭据和无效URL`() {
        listOf("file:///a", "data:image/png;base64,abc", "ftp://example.test/a", "https://name:password@example.test/a", "https://name@example.test/a", "https://example.test:99999/a", "https:///a", "example.test").forEach {
            assertThat(policy.validate(it)).isNotNull()
        }
    }
    @Test fun `重定向允许HTTP且每跳仍校验URL`() {
        assertThat(policy.validate("http://main/a")).isNull()
        assertThat(policy.validate("http://127.0.0.1/a")).isNull()
        assertThat(policy.validate("http://example.test/a")).isNull()
        assertThat(policy.validate("https://name@main/a")).isNotNull()
    }
    @Test fun `MagicDNS离线可识别但未知短名交给共享DNS分类`() {
        val offline = MediaFetchUrlPolicy { false }
        assertThat(offline.isTailnet("main")).isFalse()
        assertThat(offline.isTailnet("main.tail.ts.net")).isTrue()
        assertThat(offline.isTailnet("localhost")).isFalse()
        assertThat(offline.isTailnet("127.0.0.1")).isFalse()
    }
}
