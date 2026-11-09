package com.promenar.infinity.data.keychain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeychainHostTest {
    @Test fun `主机大小写末尾点和国际域名归一化`() {
        assertEquals("xn--fsqu00a.xn--0zwm56d", KeychainHost.normalize("例子.测试.", null))
        assertEquals("example.com:8443", KeychainHost.normalize("EXAMPLE.COM.", 8443))
        assertEquals("example.com", KeychainHost.normalize("Example.com", null))
        assertEquals("example.com:443", KeychainHost.normalize("example.com", 443))
        assertEquals("example.com:8443", KeychainHost.fromUrl("https://Example.COM:8443/login?next=%2F"))
    }

    @Test fun `拒绝非法主机和端口`() {
        assertNull(KeychainHost.normalize("a.example.com/path", null))
        assertNull(KeychainHost.normalize("example.com", 0))
        assertNull(KeychainHost.normalize("example.com", 65536))
        assertNull(KeychainHost.normalize("*.example.com", null))
    }
}
