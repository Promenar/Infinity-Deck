package com.promenar.infinity.data.remote.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

class McpLoopbackNetworkSecurityTest {
    @Test
    fun `主配置与调试配置允许所有目标明文`() {
        listOf("main", "debug").forEach { sourceSet ->
            val candidates = listOf(
                File("src/$sourceSet/res/xml/network_security_config.xml"),
                File("app/src/$sourceSet/res/xml/network_security_config.xml"),
            )
            val config = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder().parse(candidates.first { it.isFile })
            val bases = config.getElementsByTagName("base-config")
            assertThat(bases.length).isEqualTo(1)
            assertThat((bases.item(0) as Element).getAttribute("cleartextTrafficPermitted"))
                .isEqualTo("true")

            assertThat(config.getElementsByTagName("domain-config").length).isEqualTo(0)
        }
    }
}
