package com.promenar.infinity.domain.storage

import org.junit.Assert.*
import org.junit.Test

class SftpRemoteMountSpecTest {
    @Test fun 版本化编解码保留Unicode与资料库引用() {
        val spec = SftpRemoteMountSpec("nas-1", "/资料/归档///", "mount-id")
        val encoded = SftpRemoteMountSpec.encode(spec)
        assertEquals("""{"v":1,"host_alias":"nas-1","root_path":"/资料/归档","library_mount_id":"mount-id"}""", encoded)
        assertEquals(spec.copy(rootPath = "/资料/归档"), SftpRemoteMountSpec.decode(encoded))
        assertEquals("/", SftpRemoteMountSpec.decode("""{"v":1,"host_alias":"nas","root_path":"/"}""")?.rootPath)
    }

    @Test fun 拒绝不合法的版本路径和别名() {
        listOf("相对", "/a//b", "/a/.", "/a/../b", "/a\\b", "/a\u0000", "/a\n", "//").forEach {
            assertThrows(ExternalFolderException::class.java) { SftpRemoteMountSpec.canonicalPath(it) }
        }
        listOf(
            """{"v":2,"host_alias":"nas","root_path":"/"}""",
            """{"v":"1","host_alias":"nas","root_path":"/"}""",
            """{"v":1,"host_alias":"NAS","root_path":"/"}""",
            """{"v":1,"host_alias":"nas","root_path":"/","library_mount_id":null}""",
            """{"v":1,"host_alias":"nas","host_alias":"other","root_path":"/"}""",
        ).forEach { assertNull(SftpRemoteMountSpec.decode(it)) }
    }
}
