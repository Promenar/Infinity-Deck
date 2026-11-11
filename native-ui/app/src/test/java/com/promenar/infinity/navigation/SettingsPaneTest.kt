package com.promenar.infinity.navigation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.settings.settingsTwoPaneAvailable
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class SettingsPaneTest {
    private fun fill(pattern: String, vararg args: Pair<String, String?>): String {
        val map = args.toMap()
        return fillRoutePattern(pattern, encode = { it.replace(" ", "%20") }) { map[it] }
    }

    @Test
    fun `路由模板还原为可再次导航的具体路由`() {
        assertThat(fill(NavDestinations.THEME_CONFIG)).isEqualTo("theme_config")
        assertThat(fill(NavDestinations.PROVIDER_MODELS, "providerId" to "extra_1")).isEqualTo("provider_models/extra_1")
        assertThat(fill(NavDestinations.PROVIDER_FORM, "providerId" to "extra 1")).isEqualTo("provider_form?providerId=extra%201")
        // 新建提供商时参数为空：整项查询参数去掉，与 NavDestinations.providerForm() 一致。
        assertThat(fill(NavDestinations.PROVIDER_FORM, "providerId" to null)).isEqualTo(NavDestinations.providerForm())
        assertThat(fill(NavDestinations.LINUX_TERMINAL, "session" to null, "dir" to "/root", "cmd" to null)).isEqualTo("linux_terminal?dir=/root")
    }

    @Test
    fun `右列只承载设置页与 Linux 文件页，终端图谱与会话交给主导航`() {
        listOf(
            NavDestinations.THEME_CONFIG, NavDestinations.providerForm("extra_1"), NavDestinations.providerForm(),
            NavDestinations.providerModels("default"), NavDestinations.SSH_CONFIG, NavDestinations.LINUX_ENVIRONMENT,
            NavDestinations.LINUX_PACKAGES, NavDestinations.RAG_ADVANCED_KG, NavDestinations.TAILSCALE_CONFIG,
        ).forEach { assertThat(isSettingsPaneRoute(it)).isTrue() }
        // Linux 环境里打开的主目录、整个根目录与单文件详情在右列内推入。
        listOf(
            NavDestinations.linuxFiles(), "linux_files?path=%2Froot", "linux_system_files?path=%2F",
            "linux_file?path=%2Froot%2Fa.pdf",
        ).forEach { assertThat(isSettingsPaneRoute(it)).isTrue() }
        listOf(
            NavDestinations.linuxTerminal(), "linux_terminal?dir=%2Froot", "ssh_terminal/host",
            NavDestinations.KNOWLEDGE_GRAPH, NavDestinations.chatHero("s1"), NavDestinations.agentEdit("a1"),
        ).forEach { assertThat(isSettingsPaneRoute(it)).isFalse() }
    }

    @Test
    fun `变宽时只收回从设置目录进入的整页设置子页`() {
        assertThat(settingsPagesAboveMainTabs(listOf("provider_list", "provider_form?providerId=x")))
            .containsExactly("provider_list", "provider_form?providerId=x").inOrder()
        // 主标签页之上没有页面、夹着会话或终端、或底层不是设置目录条目（例如会话里打开的 Tailscale）都不收回。
        assertThat(settingsPagesAboveMainTabs(emptyList())).isNull()
        assertThat(settingsPagesAboveMainTabs(listOf("skills_config", "linux_terminal"))).isNull()
        assertThat(settingsPagesAboveMainTabs(listOf("tailscale_config"))).isNull()
        assertThat(settingsPagesAboveMainTabs(listOf("theme_config", null))).isNull()
    }

    private class FakeBackStack(vararg routes: String) : SettingsBackStack {
        val routes = routes.toMutableList()
        override val topRoute: String? get() = routes.lastOrNull()
        override val hasPrevious: Boolean get() = routes.size > 1
        override fun pop() {
            routes.removeAt(routes.lastIndex)
        }
        override fun push(route: String) {
            routes += route
        }
    }

    @Test
    fun `变宽时逐层弹出整页设置子页直到主标签页`() {
        val stack = FakeBackStack(NavDestinations.MAIN_TAB_SCAFFOLD, "skills_config", "linux_environment")
        assertThat(stack.takeSettingsPagesAboveMainTabs()).containsExactly("skills_config", "linux_environment").inOrder()
        assertThat(stack.routes).containsExactly(NavDestinations.MAIN_TAB_SCAFFOLD)
    }

    @Test
    fun `变宽时不收回的返回栈保持原样`() {
        listOf(
            // 已在主标签页、栈顶是终端、会话里打开的设置页、底层不是设置首页条目、找不到主标签页。
            listOf(NavDestinations.MAIN_TAB_SCAFFOLD),
            listOf(NavDestinations.MAIN_TAB_SCAFFOLD, "skills_config", "linux_terminal"),
            listOf(NavDestinations.MAIN_TAB_SCAFFOLD, NavDestinations.chatHero("s1"), "tailscale_config"),
            listOf(NavDestinations.MAIN_TAB_SCAFFOLD, "tailscale_config"),
            listOf("provider_list", "provider_models/x"),
        ).forEach { routes ->
            val stack = FakeBackStack(*routes.toTypedArray())
            assertThat(stack.takeSettingsPagesAboveMainTabs()).isNull()
            assertThat(stack.routes).containsExactlyElementsIn(routes).inOrder()
        }
    }

    @Test
    fun `右列层级随栈顶变化推进、后退与换根`() {
        val root = SettingsPaneEntry("a", "skills_config")
        val linux = SettingsPaneEntry("b", "linux_environment")
        val packages = SettingsPaneEntry("c", "linux_packages")
        var stack = trackSettingsPaneStack(emptyList(), root, previousId = null)
        stack = trackSettingsPaneStack(stack, linux, previousId = "a")
        stack = trackSettingsPaneStack(stack, packages, previousId = "b")
        assertThat(stack).containsExactly(root, linux, packages).inOrder()
        // 后退：栈顶回到已有条目。
        assertThat(trackSettingsPaneStack(stack, root, previousId = null)).containsExactly(root)
        assertThat(trackSettingsPaneStack(stack, linux, previousId = "a")).containsExactly(root, linux).inOrder()
        // 左列换根：新条目没有下一层。
        val theme = SettingsPaneEntry("d", "theme_config")
        assertThat(trackSettingsPaneStack(stack, theme, previousId = null)).containsExactly(theme)
        // 同一路由的新实例按 id 区分，接在下一层之后。
        val linuxAgain = SettingsPaneEntry("e", "linux_environment")
        assertThat(trackSettingsPaneStack(listOf(root, linux), linuxAgain, previousId = "b"))
            .containsExactly(root, linux, linuxAgain).inOrder()
        // 重建恢复后监听器首次回调为原栈顶：层级保持。
        assertThat(trackSettingsPaneStack(stack, packages, previousId = "b")).containsExactly(root, linux, packages).inOrder()
    }

    @Test
    fun `折叠屏内屏与平板分两列，窄窗口与小平板竖屏保持单列`() {
        assertThat(settingsTwoPaneAvailable(widthDp = 814, heightDp = 898)).isTrue()
        assertThat(settingsTwoPaneAvailable(widthDp = 1280, heightDp = 800)).isTrue()
        assertThat(settingsTwoPaneAvailable(widthDp = 380, heightDp = 872)).isFalse()
        // 600–779dp：有导航栏但右列会窄于 380dp。
        assertThat(settingsTwoPaneAvailable(widthDp = 700, heightDp = 900)).isFalse()
        assertThat(settingsTwoPaneAvailable(widthDp = 780, heightDp = 900)).isTrue()
        // 手机横屏高度不足。
        assertThat(settingsTwoPaneAvailable(widthDp = 872, heightDp = 380)).isFalse()
    }

    @Test
    fun `设置目录条目都是右列根页面`() {
        SettingsPaneRoots.forEach { assertThat(isSettingsPaneRoute(it)).isTrue() }
    }

    @Test
    fun `宠物设置页是紧跟外观的右列根页面`() {
        assertThat(SettingsPaneRoots).containsAtLeast(NavDestinations.THEME_CONFIG, NavDestinations.PET_SETTINGS).inOrder()
        assertThat(SettingsPaneRoots.indexOf(NavDestinations.PET_SETTINGS))
            .isEqualTo(SettingsPaneRoots.indexOf(NavDestinations.THEME_CONFIG) + 1)
    }

    @Test
    fun `首页智能体运行与知识库条目按决定顺序成为右列根页面`() {
        val runtime = listOf(
            NavDestinations.AGENT_BUDGET, NavDestinations.BUILTIN_TOOLS,
            NavDestinations.SKILLS_CONFIG, NavDestinations.MCP_SERVERS,
        )
        val knowledge = listOf(
            NavDestinations.RAG_GLOBAL_CONFIG, NavDestinations.RAG_ADVANCED_KG,
            NavDestinations.LONG_TERM_MEMORY, NavDestinations.RAG_DEBUG,
        )
        assertThat(SettingsPaneRoots).containsAtLeastElementsIn(runtime).inOrder()
        assertThat(SettingsPaneRoots).containsAtLeastElementsIn(knowledge).inOrder()
        // 向量统计是调试页的下一层，不是根页面，但仍在右列打开。
        assertThat(SettingsPaneRoots).doesNotContain(NavDestinations.RAG_VECTOR_STATS)
        assertThat(isSettingsPaneRoute(NavDestinations.RAG_VECTOR_STATS)).isTrue()
    }

    @Test
    fun `会话面板也会打开的连接页变宽时不收回，智能体运行页照常收回`() {
        listOf(
            NavDestinations.NETWORK_ACCESS, NavDestinations.LINUX_ENVIRONMENT,
            NavDestinations.SSH_CONFIG, NavDestinations.TAILSCALE_CONFIG,
        ).forEach { assertThat(settingsPagesAboveMainTabs(listOf(it))).isNull() }
        assertThat(settingsPagesAboveMainTabs(listOf(NavDestinations.AGENT_BUDGET)))
            .containsExactly(NavDestinations.AGENT_BUDGET)
        assertThat(settingsPagesAboveMainTabs(listOf(NavDestinations.RAG_DEBUG, NavDestinations.RAG_VECTOR_STATS)))
            .containsExactly(NavDestinations.RAG_DEBUG, NavDestinations.RAG_VECTOR_STATS).inOrder()
    }

    @Test
    fun `折叠重建界面后仍记得刚才是双列，右列层级改为整页而不是清空`() {
        // 开合屏不在 configChanges 中，Activity 会重建；“上一刻是否双列”必须跨重建保存。
        val tabs = String(
            Files.readAllBytes(Path.of("app/src/main/java/com/promenar/infinity/ui/MainTabScaffold.kt")),
            Charsets.UTF_8,
        )
        assertThat(tabs).contains("var wasTwoPane by rememberSaveable { mutableStateOf(settingsTwoPane) }")
    }
}
