package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

/**
 * “添加主机”面板的回归契约，对应真机现象：保存失败只显示原始错误码、
 * 主机 ID 规则提示挂在用户名下方。
 */
class SshHostEditorDiagnosisContractTest {
    private val moduleRoot: Path = Path.of(System.getProperty("user.dir") ?: ".").let { root ->
        if (Files.isDirectory(root.resolve("src/main"))) root else root.resolve("app")
    }

    private fun source(relativePath: String): String = String(
        Files.readAllBytes(moduleRoot.resolve(relativePath)),
        Charsets.UTF_8,
    )

    private val editor get() = source("src/main/java/com/promenar/infinity/ui/settings/SshSettingsScreen.kt")
    private val draft get() = source("src/main/java/com/promenar/infinity/ui/ssh/SshHostDraft.kt")

    /** 中文资源里所有字符串的正文（不区分文件）。 */
    private fun zhStrings(): String = Files.list(moduleRoot.resolve("src/main/res/values-zh-rCN")).use { files ->
        files.filter { it.fileName.toString().endsWith(".xml") }.map { String(Files.readAllBytes(it), Charsets.UTF_8) }
            .toList().joinToString("\n")
    }

    @Test
    fun `表单校验错误码都应有中文文案而不是原样显示`() {
        val codes = Regex("""invalid\("(SSH_[A-Z_]+)"\)""").findAll(draft).map { it.groupValues[1] }.toSet()
        assertThat(codes).contains("SSH_ALIAS_INVALID")
        val resources = zhStrings().lowercase()
        // 只认资源名同时含 ssh 与错误码后缀的条目，避免误命中 Termux、备份等同名后缀的文案。
        val missing = codes.filterNot { code ->
            Regex("""name="[a-z0-9_]*ssh[a-z0-9_]*${code.lowercase().removePrefix("ssh_")}"""").containsMatchIn(resources)
        }
        assertWithMessage("这些错误码没有任何中文资源，面板会直接显示代码").that(missing).isEmpty()
    }

    @Test
    fun `别名规则提示应紧挨别名输入框而不是整组连接信息的页脚`() {
        // 曾经把提示放在“连接信息”分区页脚，渲染在最后一个字段（用户名）下方。
        val sectionFooter = Regex(
            """title = stringResource\(R\.string\.ssh_visual_connection_section\),\s*footer = stringResource\(R\.string\.ssh_alias_help\)""",
        )
        assertWithMessage("ssh_alias_help 仍是连接信息分区的页脚，视觉上属于用户名字段")
            .that(sectionFooter.containsMatchIn(editor)).isFalse()
        val aliasField = editor.substringAfter("R.string.ssh_alias,").substringBefore("SshEditorField(")
        assertWithMessage("主机 ID 输入框应以 supportingText 显示规则说明")
            .that(aliasField.substringAfter("supportingText =")).contains("R.string.ssh_alias_help")
    }

    @Test
    fun `密钥认证从钥匙串选密钥且剪贴板导入后清空剪贴板`() {
        val passwordBranch = Regex("""if \((?:\w+\.)?authType == SshAuthType\.PASSWORD\) \{""").find(editor)
        assertThat(passwordBranch).isNotNull()
        val keyBranch = editor.substring(passwordBranch!!.range.last).substringBefore("InfinitySettingsSection(")
        assertWithMessage("密钥认证分支应打开钥匙串选钥面板").that(keyBranch).contains("onPickKey")
        assertWithMessage("选钥面板应能现场生成与导入").that(editor).contains("SshKeyImportSheet(")
        val importSheet = source("src/main/java/com/promenar/infinity/ui/keychain/KeychainSshKeyScreen.kt")
        assertWithMessage("从剪贴板导入私钥后应清空剪贴板").that(importSheet).contains("clearPrimaryClip()")
    }
}
