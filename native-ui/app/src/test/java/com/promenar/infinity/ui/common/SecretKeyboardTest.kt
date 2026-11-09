package com.promenar.infinity.ui.common

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

class SecretKeyboardTest {
    @Test
    fun `密钥输入声明为无密码变体的普通文本且关闭候选与个性化学习`() {
        assertThat(SECRET_INPUT_TYPE and InputType.TYPE_MASK_CLASS).isEqualTo(InputType.TYPE_CLASS_TEXT)
        assertThat(SECRET_INPUT_TYPE and InputType.TYPE_MASK_VARIATION).isEqualTo(InputType.TYPE_TEXT_VARIATION_NORMAL)
        assertThat(SECRET_INPUT_TYPE and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).isNotEqualTo(0)
        assertThat(secretImeOptions(EditorInfo.IME_ACTION_DONE))
            .isEqualTo(EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
    }

    @Test
    fun `界面源码不再使用会触发安全键盘的密码键盘类型`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/.mimosa/" !in it.invariantSeparatorsPath }
            .filter { file -> file.readLines().any { line -> !line.trimStart().startsWith("*") && Regex("KeyboardType\\.(Number)?Password\\b").containsMatchIn(line) } }
            .map { it.name }
            .toList()
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `使用密钥键盘的界面都让输入框退出系统自动填充`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val missing = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/.mimosa/" !in it.invariantSeparatorsPath }
            .filter { file ->
                val code = file.readLines().filterNot { it.trimStart().startsWith("import ") }.joinToString("\n")
                "SecretKeyboardOptions" in code && "excludeFromAutofill()" !in code
            }
            .map { it.name }
            .toList()
        assertThat(missing).isEmpty()
    }
}
