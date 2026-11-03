package com.promenar.infinity.security

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Document
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
class ManifestSecurityPolicyTest {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test
    fun `manifest disables backup and references explicit backup rules`() {
        val application = parse("app/src/main/AndroidManifest.xml")
            .getElementsByTagName("application")
            .item(0)

        assertThat(application.attributes.getNamedItemNS(androidNamespace, "allowBackup").nodeValue)
            .isEqualTo("false")
        assertThat(application.attributes.getNamedItemNS(androidNamespace, "fullBackupContent").nodeValue)
            .isEqualTo("@xml/backup_rules")
        assertThat(application.attributes.getNamedItemNS(androidNamespace, "dataExtractionRules").nodeValue)
            .isEqualTo("@xml/data_extraction_rules")
        // 应用允许明文 HTTP（局域网与自建服务），由地址输入框的“未加密”提示提醒用户。
        assertThat(application.attributes.getNamedItemNS(androidNamespace, "usesCleartextTraffic").nodeValue)
            .isEqualTo("true")
    }

    @Test
    fun `network security config permits cleartext traffic`() {
        val baseConfig = parse("app/src/main/res/xml/network_security_config.xml")
            .getElementsByTagName("base-config")
            .item(0)

        assertThat(baseConfig.attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
            .isEqualTo("true")
    }

    @Test
    fun `runtime application info disables backup`() {
        val applicationInfo = ApplicationProvider.getApplicationContext<Context>().applicationInfo

        assertThat(applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP).isEqualTo(0)
    }

    @Test
    fun `backup policies exclude every storage domain in each rules section`() {
        val legacyRules = parse("app/src/main/res/xml/backup_rules.xml")
        val extractionRules = parse("app/src/main/res/xml/data_extraction_rules.xml")
        val standardDomains = setOf("root", "file", "database", "sharedpref", "external")
        val androidTwelveDomains = standardDomains + setOf(
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref"
        )

        assertExcludedDomains(legacyRules.documentElement, standardDomains)
        assertExcludedDomains(
            extractionRules.getElementsByTagName("cloud-backup").item(0) as Element,
            androidTwelveDomains
        )
        assertExcludedDomains(
            extractionRules.getElementsByTagName("device-transfer").item(0) as Element,
            androidTwelveDomains
        )
    }

    private fun assertExcludedDomains(section: Element, expected: Set<String>) {
        val excludes = section.getElementsByTagName("exclude")
        val domains = buildSet {
            for (index in 0 until excludes.length) {
                add(excludes.item(index).attributes.getNamedItem("domain").nodeValue)
            }
        }
        assertThat(domains).containsAtLeastElementsIn(expected)
    }

    private fun parse(path: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return factory.newDocumentBuilder().parse(File(path))
    }
}
