package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * 宿主身份集中由 Runtime Environment 固定段通过 AppBrand 声明，工具文案不重复定义助手角色。
 * 其它运行环境文案统一称“本应用 / the host app”；Python 沙箱的 infinity.* 接口名与 InfinityError 是代码标识，不受限。
 */
class ModelVisibleHostNameTest {
    @Test
    fun `系统提示词与工具描述的字符串字面量不出现应用名称`() {
        // Gradle 单元测试的工作目录可能是模块或仓库根，按候选路径定位主源码。
        val sourceRoot = listOf("src/main/java", "app/src/main/java", "native-ui/app/src/main/java")
            .map(::File).firstOrNull { it.resolve("com/promenar/infinity").isDirectory }
        assertWithMessage("未找到 app 主源码目录：${File("").absolutePath}").that(sourceRoot).isNotNull()
        val manager = sourceRoot!!.resolve("com/promenar/infinity/ui/chat/manager")
        val files = manager.resolve("skills").listFiles { file -> file.extension == "kt" }!!.toList() +
            manager.resolve("SystemPromptSections.kt")
        // 防止目录变动后空跑通过。
        assertWithMessage("扫描到的源码文件数").that(files.size).isAtLeast(10)

        val literal = Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\"")
        val hostName = Regex("Infinity Deck(?!Error)")
        val offenders = files.flatMap { file ->
            file.readLines().withIndex().flatMap { (index, line) ->
                literal.findAll(line).map { it.value }.filter(hostName::containsMatchIn).map { "${file.name}:${index + 1} $it" }
            }
        }
        assertWithMessage("模型可见文案中的应用名称").that(offenders).isEmpty()
    }
}
