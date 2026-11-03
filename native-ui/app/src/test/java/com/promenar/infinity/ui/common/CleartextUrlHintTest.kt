package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CleartextUrlHintTest {
    @Test
    fun `离开本机的 http 地址提示未加密`() {
        listOf("http://192.168.1.8:5244/dav", "http://nas", "http://example.com/v1", "HTTP://Example.com").forEach {
            assertThat(isCleartextRemoteUrl(it)).isTrue()
        }
    }

    @Test
    fun `https、本机回环与 tailnet 地址不提示`() {
        listOf(
            "https://example.com", "http://127.0.0.1:8080", "http://localhost:11434", "http://[::1]:8080",
            "http://100.64.0.5:8188", "http://main.tail1234.ts.net", "", "not a url", "ftp://example.com",
        ).forEach { assertThat(isCleartextRemoteUrl(it)).isFalse() }
    }
}
