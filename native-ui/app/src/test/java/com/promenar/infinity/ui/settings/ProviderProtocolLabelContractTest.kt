package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.jupiter.api.Test

/** 界面名称必须来自字符串资源；冻结的历史显示名只供持久化与迁移匹配使用。 */
class ProviderProtocolLabelContractTest {
    private val uiDir = File("app/src/main/java/com/promenar/infinity/ui")

    @Test
    fun `界面代码不读取历史显示名`() {
        val offenders = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> Regex("""\.legacyDisplayName\b""").containsMatchIn(file.readText()) }
            .map { it.relativeTo(uiDir).path }
            .toList()
        assertWithMessage("界面源码目录").that(uiDir.isDirectory).isTrue()
        assertWithMessage("界面应使用 ProtocolType.label()：").that(offenders).isEmpty()
    }
}
