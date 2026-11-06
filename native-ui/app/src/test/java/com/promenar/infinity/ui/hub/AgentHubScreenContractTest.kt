package com.promenar.infinity.ui.hub

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * 契约测试：锁定 Agent Hub 的状态提升 seam、可达操作入口、稳定测试锚点与无障碍资源化。
 * 采用源码扫描方式（与 ChatRouteContractTest 一致），不引用尚不存在的生产类型，
 * 仅断言源码文本内容，确保编译通过并以断言失败形式呈现预期 RED。
 */
class AgentHubScreenContractTest {
    private val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
        if (root.resolve("src/main").isDirectory) root else root.resolve("app")
    }
    private val hubSource = projectRoot.resolve(
        "src/main/java/com/promenar/infinity/ui/hub/AgentHubScreen.kt",
    )
    private val swipeSource = projectRoot.resolve(
        "src/main/java/com/promenar/infinity/ui/common/SwipeableItem.kt",
    )
    private val uiTagsSource = projectRoot.resolve(
        "src/main/java/com/promenar/infinity/ui/testing/UiTags.kt",
    )

    @Test
    fun `content seam exposes state actions and content without view model or providers`() {
        val source = hubSource.readText()
        assertThat(source).contains("data class AgentHubScreenState(")
        assertThat(source).contains("data class AgentHubScreenActions(")
        assertThat(source).contains("fun AgentHubScreenContent(")

        val content = source.substringAfter("fun AgentHubScreenContent(")
        assertThat(content).doesNotContain("viewModel(")
        assertThat(content).doesNotContain("ProviderManager")
        assertThat(content).doesNotContain("InfinityApplication")
        assertThat(content).doesNotContain("LocalContext")
    }

    @Test
    fun `hub uses tab header and settings style grouped sections instead of fab and cards`() {
        val source = hubSource.readText()
        val content = source.substringAfter("internal fun AgentHubScreenContent(")

        assertThat(content).contains("InfinityTabHeader(")
        assertThat(content).contains("InfinitySettingsSection(")
        assertThat(content).contains("R.string.hub_section_recent")
        assertThat(content).contains("R.string.hub_section_agents")
        assertThat(source).doesNotContain("FloatingActionButton")
        assertThat(source).doesNotContain("AgentExpandableCard")
        assertThat(source).doesNotContain("HorizontalDivider")
    }

    @Test
    fun `agent and session rows expose long press menus with accessibility actions`() {
        val source = hubSource.readText()
        val agentRow = source.substringAfter("private fun HubAgentRow(").substringBefore("/** 助手展开区")
        val sessionRow = source.substringAfter("private fun HubSessionRow(").substringBefore("private fun HubAgentRow(")

        listOf(agentRow, sessionRow).forEach { row ->
            assertThat(row).contains("combinedClickable(")
            assertThat(row).contains("onLongClick")
            assertThat(row).contains("CustomAccessibilityAction(")
            assertThat(row).contains("InfinityDropdownMenu(")
        }
        assertThat(agentRow).contains("UiTags.HUB_AGENT_MENU_EDIT")
    }

    @Test
    fun `agent rows expand in place to list sessions instead of opening another page`() {
        val source = hubSource.readText()
        val content = source.substringAfter("internal fun AgentHubScreenContent(")
        val agentRow = source.substringAfter("private fun HubAgentRow(").substringBefore("/** 头像宽度加间距")
        val expandedArea = source.substringAfter("private fun HubAgentSessions(").substringBefore("/** 带头像的列表行")

        assertThat(content).contains("var expandedAgentId by rememberSaveable")
        assertThat(content).contains("AnimatedVisibility(visible = expanded, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut)")
        // 展开与收起各给一次开/关轻振，并且先振动再切换状态。
        assertThat(content).contains("haptics.toggle(!expanded)")
        assertThat(content.substringAfter("haptics.toggle(!expanded)")).contains("expandedAgentId = if (expanded) null else agentId")
        assertThat(agentRow).contains("onClick = onToggleExpand")
        assertThat(agentRow).contains("Icons.Rounded.ExpandMore")
        assertThat(agentRow).contains("R.string.common_state_expanded")
        // 展开区只列出会话；新建会话统一走页头 + 菜单，列表里不再有“新建会话”行与底部“添加智能体”行。
        assertThat(expandedArea).doesNotContain("R.string.sessions_btn_new")
        assertThat(expandedArea).doesNotContain("onCreateSession")
        assertThat(source).doesNotContain("HubAddAgentRow")
        assertThat(expandedArea).contains("R.string.hub_sessions_empty")
        assertThat(source).doesNotContain("onOpenAgentSessions")
        assertThat(source).doesNotContain("onNavigateToSessionList")
    }

    @Test
    fun `session create pin and delete failures surface as a snackbar on the hub`() {
        val source = hubSource.readText()
        val route = source.substringAfter("fun AgentHubScreen(").substringBefore("private fun AddAgentDialog(")
        val content = source.substringAfter("internal fun AgentHubScreenContent(")

        assertThat(route).contains("sessionListViewModel.operationFailed.collectAsState()")
        assertThat(route).contains("R.string.sessions_operation_failed")
        assertThat(route).contains("sessionListViewModel.dismissOperationFailure()")
        assertThat(content).contains("snackbarHost = { SnackbarHost(snackbarHostState) }")
    }

    @Test
    fun `hub exposes stable ui tags for root add search empty list card and actions`() {
        val source = uiTagsSource.readText()
        assertThat(source).contains("HUB_ROOT")
        assertThat(source).contains("HUB_ADD_AGENT")
        assertThat(source).contains("HUB_SEARCH")
        assertThat(source).contains("HUB_EMPTY_STATE")
        assertThat(source).contains("HUB_EMPTY_ADD_AGENT")
        assertThat(source).contains("HUB_AGENT_LIST")
        assertThat(source).contains("HUB_AGENT_CARD")
        assertThat(source).contains("HUB_NEW_CHAT")
        assertThat(source).contains("hubSession")
        assertThat(source).contains("hubAgentSession")
        assertThat(source).contains("hubAgentNewSession")
    }

    @Test
    fun `add agent dialog asks for name type model and optional prompt without description`() {
        val source = hubSource.readText()
        val dialog = source.substringAfter("private fun AddAgentDialog(").substringBefore("/** 类型两段式按钮")

        val order = listOf(
            "R.string.hub_dialog_label_name",
            "AgentTypeSelector(",
            "R.string.hub_dialog_label_model",
            "R.string.ux2_agent_dialog_prompt_optional",
        ).map { dialog.indexOf(it) }
        assertThat(order).doesNotContain(-1)
        assertThat(order).isInOrder()
        assertThat(dialog).doesNotContain("hub_dialog_label_desc")
        assertThat(dialog).contains("onConfirm(name, type, model, systemPrompt)")
    }

    @Test
    fun `hub list has no bottom add agent row and keeps the plus menu and empty state button`() {
        val source = hubSource.readText()
        assertThat(source).doesNotContain("HubAddAgentRow")
        assertThat(source).contains("HubNewChatAction(")
        assertThat(source).contains("UiTags.HUB_EMPTY_ADD_AGENT")
        assertThat(source).contains("R.string.hub_btn_add_agent")
    }

    @Test
    fun `all four avatar surfaces pass raw icon and color to the shared avatar`() {
        val hub = hubSource.readText()
        val three = projectRoot.resolve("src/main/java/com/promenar/infinity/ui/hub/HubThreePane.kt").readText()
        val edit = projectRoot.resolve("src/main/java/com/promenar/infinity/ui/hub/AgentEditScreen.kt").readText()
        listOf(hub, three, edit).forEach { source ->
            assertThat(source).doesNotContain("agentIconVector(")
            assertThat(source).doesNotContain("agentAvatarTones(")
            assertThat(source).doesNotContain("lerp(")
        }
        assertThat(hub).contains("icon = item.agent.icon")
        assertThat(hub).contains("icon = agent.icon")
        assertThat(three).contains("icon = entry.agent.agent.icon")
        assertThat(edit).contains("AgentAvatarCodec.decode(selectedIcon)")
        assertThat(edit).contains("AgentAvatarPickerSheet(")
        assertThat(edit).doesNotContain("ColorPickerPanel(")
    }

    @Test
    fun `empty state does not render emoji text`() {
        val source = hubSource.readText()
        assertThat(source).doesNotContain("text = \"\u2728\"")
    }

    @Test
    fun `swipeable item has no hardcoded english content descriptions`() {
        val source = swipeSource.readText()
        assertThat(source).doesNotContain("contentDescription = \"Delete\"")
        assertThat(source).doesNotContain("contentDescription = \"Edit\"")
        assertThat(source).doesNotContain("\"Unpin\"")
    }

    @Test
    fun `swipeable item converts dp gesture metrics to pixels`() {
        val source = swipeSource.readText()

        assertThat(source).contains("LocalDensity.current")
        assertThat(source).contains("80.dp.toPx()")
        assertThat(source).contains("screenWidthDp.dp.toPx()")
    }

    @Test
    fun `pinned agents and sessions keep a visible marker and state description`() {
        val source = hubSource.readText()

        assertThat(source).contains("PushPin")
        assertThat(source).contains("pinnedState.takeIf { session.isPinned }")
        assertThat(source).contains("R.string.sessions_tag_pinned).takeIf { agent.isPinned }")
    }

    @Test
    fun `会话行标题固定单行，过长时横滑渐隐而不折行或省略`() {
        val row = hubSource.readText().substringAfter("private fun HubSessionRow(").substringBefore("trailingContent = {")
        assertThat(row).contains("singleLineTitle = true")
        val layout = projectRoot.resolve("src/main/java/com/promenar/infinity/ui/common/InfinitySettingsItem.kt").readText()
            .substringAfter("internal fun InfinitySettingsRowLayout(")
        // 窄列（< 280dp）放开行数的规则不得覆盖单行标题。
        assertThat(layout.indexOf("singleLineTitle -> 1")).isLessThan(layout.indexOf("expandTitle -> Int.MAX_VALUE"))
        // 单行标题走全站统一的横滑渐隐，不用跑马灯。
        assertThat(layout).contains("InfinityScrollingLine(")
        assertThat(layout).doesNotContain("basicMarquee(")
    }
}
