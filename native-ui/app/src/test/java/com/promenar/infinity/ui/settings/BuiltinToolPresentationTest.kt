package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BuiltinToolPresentationTest {
    private fun tool(id: String) = SkillInfo(id, id, "", enabled = true)

    @Test
    fun `内置工具按联网、文件、执行、媒体、计划、其它分组`() {
        val tools = listOf(
            "web_search", "web_fetch", "calculator", "pet", "image_generation", "file_read", "file_write",
            "file_list", "file_search", "file_diff", "file_patch", "exec_js", "run_script",
            "production_define", "production_attach", "production_get", "production_update", "production_remove",
            "initialize_plan", "update_plan", "get_plan", "drop_plan", "create_file", "create_directory",
            "move_file", "delete_file", "shell_exec", "python_exec", "python_sandbox", "view_media", "media_fetch",
        ).map(::tool)
        val groups = tools.groupedByToolCategory()
        assertThat(groups.map { it.first }).containsExactly(
            BuiltinToolCategory.WEB,
            BuiltinToolCategory.FILES,
            BuiltinToolCategory.EXECUTION,
            BuiltinToolCategory.MEDIA,
            BuiltinToolCategory.PLANNING,
            BuiltinToolCategory.OTHER,
        ).inOrder()
        val byCategory = groups.toMap().mapValues { (_, list) -> list.map { it.id } }
        assertThat(byCategory[BuiltinToolCategory.FILES]).containsExactly(
            "file_read", "file_write", "file_list", "file_search", "file_diff", "file_patch",
            "create_file", "create_directory", "move_file", "delete_file",
        ).inOrder()
        assertThat(byCategory[BuiltinToolCategory.EXECUTION])
            .containsExactly("exec_js", "run_script", "shell_exec", "python_exec", "python_sandbox").inOrder()
        assertThat(byCategory[BuiltinToolCategory.MEDIA]).containsExactly("image_generation", "view_media", "media_fetch").inOrder()
        assertThat(byCategory[BuiltinToolCategory.PLANNING]).containsAtLeast("production_define", "production_attach", "production_get", "production_update", "production_remove")
        assertThat(byCategory[BuiltinToolCategory.OTHER]).containsExactly("calculator", "pet")
        assertThat(groups.sumOf { it.second.size }).isEqualTo(tools.size)
    }

    @Test
    fun `Agent 浏览器四个工具归入联网，排在网页抓取之后`() {
        val ids = listOf("web_search", "web_fetch", "browser_open", "browser_snapshot", "browser_click", "browser_type")
        val groups = ids.map(::tool).groupedByToolCategory()
        assertThat(groups.map { it.first }).containsExactly(BuiltinToolCategory.WEB)
        assertThat(groups.single().second.map { it.id }).containsExactlyElementsIn(ids).inOrder()
    }

    @Test
    fun `未知工具归入其它，空分组不出现`() {
        val groups = listOf(tool("web_fetch"), tool("future_tool")).groupedByToolCategory()
        assertThat(groups.map { it.first }).containsExactly(BuiltinToolCategory.WEB, BuiltinToolCategory.OTHER).inOrder()
    }
}
