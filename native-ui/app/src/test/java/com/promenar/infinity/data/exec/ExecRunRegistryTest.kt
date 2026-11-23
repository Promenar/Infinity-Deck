package com.promenar.infinity.data.exec

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExecRunRegistryTest {
    private fun info(id: String) = ExecRunInfo(id, ExecRunKind.SHELL, id, 60, 0L)

    @Test
    fun `停止只作用于指定且仍在运行的命令`() {
        val registry = ExecRunRegistry()
        val stopped = mutableListOf<String>()
        listOf("a", "b", "c").forEach { id -> registry.register(ExecRunRegistry.Active(info(id)) { stopped += id }) }
        registry.unregister("c")
        assertThat(registry.stop(listOf("b", "c"))).isEqualTo(1)
        assertThat(stopped).containsExactly("b")
        assertThat(registry.stop()).isEqualTo(2)
        assertThat(stopped).containsExactly("b", "a", "b").inOrder()
        assertThat(registry.runs.value.map { it.info.id }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `单个停止回调抛错不影响其它命令`() {
        val registry = ExecRunRegistry()
        var second = false
        registry.register(ExecRunRegistry.Active(info("a")) { error("boom") })
        registry.register(ExecRunRegistry.Active(info("b")) { second = true })
        assertThat(registry.stop()).isEqualTo(2)
        assertThat(second).isTrue()
    }

    @Test
    fun `摘要取首个非空行并标记多行与截断`() {
        assertThat(ExecRunInfo.summarize("\n  ls -la  \n")).isEqualTo("ls -la")
        assertThat(ExecRunInfo.summarize("import os\n\nprint(1)")).isEqualTo("import os …")
        val long = "x".repeat(78) + "😀😀"
        assertThat(ExecRunInfo.summarize(long)).isEqualTo("x".repeat(78) + "…")
        assertThat(ExecRunInfo.summarize("")).isEmpty()
    }

    @Test
    fun `通知以最近启动的命令为摘要并列出全部可停止命令`() {
        val registry = ExecRunRegistry()
        registry.register(ExecRunRegistry.Active(ExecRunInfo("a", ExecRunKind.SHELL, "sleep 9", 60, 5_000L)) {})
        registry.register(ExecRunRegistry.Active(ExecRunInfo("b", ExecRunKind.PYTHON, "print(2)", 60, 1_000L)) {})
        val model = ExecNotificationModel.from(registry.runs.value)
        assertThat(model.count).isEqualTo(2)
        assertThat(model.latest?.id).isEqualTo("a")
        assertThat(model.stopIds).containsExactly("a", "b").inOrder()
        assertThat(ExecNotificationModel.from(emptyList())).isEqualTo(ExecNotificationModel(0, null, emptyList()))
    }

    @Test
    fun `更新运行条目可升级为后台态并保留原停止回调`() {
        val registry = ExecRunRegistry()
        var stopped = false
        registry.register(ExecRunRegistry.Active(ExecRunInfo("a", ExecRunKind.LINUX, "codex login", 60, 1_000L, background = false)) { stopped = true })
        registry.update("a") { it.copy(background = true, timeoutSeconds = 7200) }
        val updated = registry.runs.value.single().info
        assertThat(updated.background).isTrue()
        assertThat(updated.timeoutSeconds).isEqualTo(7200)
        assertThat(registry.stop(listOf("a"))).isEqualTo(1)
        assertThat(stopped).isTrue()
    }
}
