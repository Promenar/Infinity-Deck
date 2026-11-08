package com.promenar.infinity.ui.hub

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class UserSettingsHomeScreenContractTest {
    private val screenSource = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/infinity/ui/hub/UserSettingsHomeScreen.kt"),
    ).toString(Charsets.UTF_8)

    private val providerListSourceFile = Path.of("app/src/main/java/com/promenar/infinity/ui/settings/ProviderListScreen.kt")
    private val providerListSource: String
        get() = if (Files.exists(providerListSourceFile)) {
            Files.readAllBytes(providerListSourceFile).toString(Charsets.UTF_8)
        } else {
            ""
        }

    private val uiTagsSource = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/infinity/ui/testing/UiTags.kt"),
    ).toString(Charsets.UTF_8)

    @Test
    fun `settings-home 禁止内嵌 PrimaryTabRow 与 APP-PROVIDER Tab 机制`() {
        assertThat(screenSource).doesNotContain("enum class SettingsTab")
        assertThat(screenSource).doesNotContain("PrimaryTabRow(")
        assertThat(screenSource).doesNotContain("SettingsTab(")
    }

    @Test
    fun `settings-home 不能再在主页内联 model picker 并应导航至默认模型页`() {
        assertThat(screenSource).doesNotContain("ModelPicker(")
        assertThat(screenSource).contains("DEFAULT_MODELS")
    }

    @Test
    fun `settings-home 旧 provider_form 或 provider_models 路由不应仍内嵌在主页动作中`() {
        assertThat(screenSource).doesNotContain("provider_form")
        assertThat(screenSource).doesNotContain("provider_models")
    }

    @Test
    fun `UserSettings 首页目的地应包含 theme_config 且为 provider_list 和 default_models`() {
        assertThat(screenSource).contains("THEME_CONFIG")
        assertThat(screenSource).contains("PROVIDER_LIST")
        assertThat(screenSource).contains("DEFAULT_MODELS")

        // 禁止在 UserSettings 中发明或硬编码 appearance / provider
        assertThat(screenSource).doesNotContain("onNavigateToSecondary(\"appearance\")")
        assertThat(screenSource).doesNotContain("onNavigateToSecondary(\"provider\")")
    }

    @Test
    fun `settings-home 页面布局应先显示 profile 且不显示账户标签并按其余分区顺序排列`() {
        val content = screenSource.substringAfter("fun UserSettingsHomeScreenContent(")
            .substringBefore("private fun UserProfileHeader(")
        val orderedMarkers = listOf(
            "settings_section_general",
            "settings_ia_section_models",
            "settings_ia_section_agent_runtime",
            "settings_ia_section_knowledge",
            "settings_ia_section_environment",
            "settings_ia_section_data",
            "settings_section_about",
        )
        val indices = orderedMarkers.map(content::indexOf)

        assertThat(indices).doesNotContain(-1)
        assertThat(indices).isInStrictOrder()
        assertThat(content.substringBefore("settings_section_general"))
            .contains("UserProfileHeader(")
        assertThat(content).doesNotContain("settings_section_account")
        val aiSection = content.substringAfter("settings_ia_section_models")
            .substringBefore("settings_ia_section_agent_runtime")
        assertThat(aiSection.indexOf("PROVIDER_LIST")).isLessThan(aiSection.indexOf("DEFAULT_MODELS"))
    }

    @Test
    fun `settings-home 必须恰好包含一个 theme_config 和 provider_list 和 default_models 导航目的地`() {
        fun countOccurrences(sub: String): Int {
            var count = 0
            var idx = 0
            while (true) {
                idx = screenSource.indexOf(sub, idx)
                if (idx == -1) break
                count++
                idx += sub.length
            }
            return count
        }

        assertThat(countOccurrences("onNavigateToSecondary(NavDestinations.THEME_CONFIG)")).isEqualTo(1)
        assertThat(countOccurrences("onNavigateToSecondary(NavDestinations.PROVIDER_LIST)")).isEqualTo(1)
        assertThat(countOccurrences("onNavigateToSecondary(NavDestinations.DEFAULT_MODELS)")).isEqualTo(1)
    }

    @Test
    fun `app settings are grouped into continuous material lists`() {
        assertThat(screenSource).contains("InfinitySettingsSection(")
        assertThat(screenSource).doesNotContain("settings_section_account")
        assertThat(screenSource).contains("settings_section_general")
        assertThat(screenSource).contains("settings_ia_section_models")
        assertThat(screenSource).contains("settings_ia_section_agent_runtime")
        assertThat(screenSource).contains("settings_ia_section_knowledge")
        assertThat(screenSource).contains("settings_ia_section_environment")
        assertThat(screenSource).contains("settings_ia_section_data")
        assertThat(screenSource).contains("settings_section_about")
    }

    @Test
    fun `智能体运行与知识库分组各四行且按决定顺序排列`() {
        val content = screenSource.substringAfter("fun UserSettingsHomeScreenContent(")
            .substringBefore("private fun UserProfileHeader(")
        val runtime = content.substringAfter("settings_ia_section_agent_runtime").substringBefore("settings_ia_section_knowledge")
        val runtimeRoutes = listOf("AGENT_BUDGET", "BUILTIN_TOOLS", "SKILLS_CONFIG", "MCP_SERVERS").map(runtime::indexOf)
        assertThat(runtimeRoutes).doesNotContain(-1)
        assertThat(runtimeRoutes).isInStrictOrder()
        val knowledge = content.substringAfter("settings_ia_section_knowledge").substringBefore("settings_ia_section_environment")
        val knowledgeRoutes = listOf("RAG_GLOBAL_CONFIG", "RAG_ADVANCED_KG", "LONG_TERM_MEMORY", "RAG_DEBUG").map(knowledge::indexOf)
        assertThat(knowledgeRoutes).doesNotContain(-1)
        assertThat(knowledgeRoutes).isInStrictOrder()
        assertThat(content).contains("EnvironmentConnectionRows(")
    }

    @Test
    fun `语言行按偏好显示且页脚文案走资源`() {
        assertThat(screenSource).doesNotContain("LocalConfiguration.current.locales")
        assertThat(screenSource).contains("state.language == \"en\"")
        assertThat(screenSource).doesNotContain("\"Infinity Deck AI • Project Narcis\"")
        assertThat(screenSource).contains("settings_about_version")
    }

    @Test
    fun `settings home uses the dedicated settings page layout and content padding`() {
        val content = screenSource.substringAfter("fun UserSettingsHomeScreenContent(")
            .substringBefore("private fun UserProfileHeader(")

        assertThat(content).contains("InfinitySettingsPageLayout(")
        assertThat(content).contains("contentPadding = contentPadding")
        assertThat(content).doesNotContain("TopAppBar(")
    }

    @Test
    fun `设置首页入口使用统一图标列与紧凑设置行`() {
        val content = screenSource.substringAfter("fun UserSettingsHomeScreenContent(")
            .substringBefore("private fun UserProfileHeader(")

        assertThat(content).contains("UserSettingsNavigationItem(")
        assertThat(content).contains("InfinitySettingsItem(")
        assertThat(content).contains("subtitle = subtitle")
        assertThat(content).contains("headerStartPadding = InfinitySpacing.ScreenHorizontal")
        assertThat(content).doesNotContain("Icons.Outlined")
        assertThat(content).contains("showChevron = true")
        assertThat(content).doesNotContain("footer =")
    }

    @Test
    fun `个人区是页面顶部居中名片而非列表行`() {
        val profile = screenSource.substringAfter("private fun UserProfileHeader(")
            .substringBefore("private fun channelLabel(")
        val header = screenSource.substringAfter("fun UserSettingsHomeScreenContent(")
            .substringBefore("settings_section_general")
        // 名片不放进列表卡片，整体水平居中。
        assertThat(header).contains("Modifier.fillMaxWidth()")
        assertThat(header).contains("horizontalAlignment = Alignment.CenterHorizontally")
        assertThat(header).doesNotContain("BettboxListGroup(")
        assertThat(profile).contains("size(96.dp)")
        assertThat(profile).contains("MaterialTheme.typography.titleLarge")
        assertThat(profile).contains("maxLines = 2")
        // 名片上只有头像与名字：不放提示语、改名铅笔、相机角标或切换按钮，整体一个点按目标打开面板。
        assertThat(profile).doesNotContain("settings_profile_local_hint")
        assertThat(profile).doesNotContain("Icons.Rounded.Edit")
        assertThat(profile).doesNotContain("Icons.Rounded.PhotoCamera")
        assertThat(profile).doesNotContain("IconButton(")
        assertThat(profile).doesNotContain("onEditName")
        assertThat(profile).doesNotContain("onChangeAvatar")
        assertThat(profile).contains("SETTINGS_PROFILE_CARD")
        assertThat(profile).contains("UserProfileCard(userName, avatarUri, onClick = onOpenMenu)")
        assertThat(screenSource).contains("GitHubProjectFooter")
    }

    @Test
    fun `没有头像时显示品牌标识，有头像时按用户选择`() {
        assertThat(UserSettingsHomeScreenState(userAvatar = null).profileShowsBrand).isTrue()
        assertThat(UserSettingsHomeScreenState(userAvatar = null, profilePrefersBrand = false).profileShowsBrand).isTrue()
        assertThat(UserSettingsHomeScreenState(userAvatar = "/data/avatar.img").profileShowsBrand).isFalse()
        assertThat(UserSettingsHomeScreenState(userAvatar = "/data/avatar.img", profilePrefersBrand = true).profileShowsBrand).isTrue()
    }

    @Test
    fun `品牌标识复用引导页同一个灯珠字标且不缩减灯珠数`() {
        val brand = screenSource.substringAfter("private fun ProfileBrandMark(")
            .substringBefore("private fun UserProfileCard(")
        assertThat(brand).contains("LedMorphWordmark(")
        assertThat(brand).doesNotContain("particleScale")
        assertThat(brand).contains("height(ProfileHeaderHeight)")
        assertThat(screenSource).contains("private val ProfileHeaderHeight = 170.dp")
        val header = screenSource.substringAfter("private fun UserProfileHeader(")
            .substringBefore("private fun ProfileBrandMark(")
        // 不论有无头像，点字标都打开个人资料面板。
        assertThat(header).contains("ProfileBrandMark(onClick = onOpenMenu)")
        // 品牌与名片同占一个最小高度，切换时下面的设置列表不跳；超出时高度变化走动画。
        assertThat(header).contains(".heightIn(min = ProfileHeaderHeight)")
        assertThat(header).contains(".animateContentSize(")
        assertThat(header).contains("contentAlignment = Alignment.Center")
    }

    @Test
    fun `个人资料面板有头像时才提供切换，并按有无头像区分设置与更换`() {
        val sheet = screenSource.substringAfter("private fun ProfileMenuSheet(")
            .substringBefore("internal fun LanguageOption(")
        assertThat(sheet).contains("if (hasAvatar)")
        assertThat(sheet).contains("SETTINGS_PROFILE_DISPLAY_TOGGLE")
        assertThat(sheet).contains("if (showsBrand) R.string.settings_profile_show_avatar else R.string.settings_profile_show_brand")
        assertThat(sheet).contains("if (hasAvatar) R.string.settings_profile_change_avatar else R.string.settings_profile_set_avatar")
        assertThat(sheet).contains("R.string.settings_edit_name")
        val route = screenSource.substringAfter("ProfileMenuSheet(\n            hasAvatar")
            .substringBefore("onSetAvatar")
        assertThat(route).contains("actions.onToggleProfileDisplay()")
    }

    @Test
    fun `provider screen has one material add action and a clear empty state`() {
        assertThat(providerListSource).contains("InfinitySettingsPageLayout(")
        assertThat(providerListSource).doesNotContain("TopAppBar(")
        assertThat(providerListSource).contains("onClick = actions.onAddProvider")
        assertThat(providerListSource).contains("UiTags.SETTINGS_ADD_PROVIDER")
        assertThat(providerListSource).contains("provider_list_empty_title")
        assertThat(providerListSource).contains("UiTags.PROVIDER_LIST_EMPTY_ADD")
        assertThat(providerListSource).contains("MaterialTheme.typography")
        assertThat(providerListSource).contains("InfinitySpacing.XLarge")
        assertThat(providerListSource).doesNotContain("InfinityTypography")
        assertThat(providerListSource).doesNotContain("InfinityColors")
    }

    @Test
    fun `provider empty state adapts to compact landscape height`() {
        assertThat(providerListSource).contains("LocalConfiguration.current.screenHeightDp")
        assertThat(providerListSource).contains("compactHeightEmptyState")
        assertThat(providerListSource).contains("ProviderEmptyState(")
    }

    @Test
    fun `provider row has one manage target and overflow owns edit and delete`() {
        // 三段式提供商行（D2）：整行点按进入模型管理，编辑、管理模型与删除都在溢出菜单里。
        assertThat(providerListSource).contains("ProviderRowFrame(")
        assertThat(providerListSource).doesNotContain("ListItem(")
        assertThat(providerListSource).contains("Icons.Rounded.MoreVert")
        assertThat(providerListSource).contains("InfinityDropdownMenu(")
        assertThat(providerListSource).contains("InfinityMenuItem(")
        assertThat(providerListSource).contains("UiTags.settingsProviderCard(")
        assertThat(providerListSource).contains("UiTags.settingsProviderActions(")
        assertThat(providerListSource).contains("settings_provider_status_summary")
        assertThat(providerListSource).contains("settings_provider_state_enabled")
        assertThat(providerListSource).contains("settings_provider_state_disabled")
        assertThat(providerListSource).contains("settings_provider_api_key_configured")
        assertThat(providerListSource).contains("settings_provider_api_key_not_configured")
        assertThat(providerListSource).contains("settings_provider_vertex_credentials_configured")
        assertThat(providerListSource).contains("settings_provider_vertex_credentials_not_configured")
        assertThat(providerListSource).contains("settings_provider_credentials_not_required")
        assertThat(providerListSource).contains("stateDescription = providerStateDescription")
        assertThat(providerListSource).contains("style = MaterialTheme.typography.bodyLarge")
        assertThat(providerListSource).doesNotContain("useLargeTextLayout")
        assertThat(providerListSource).doesNotContain("leadingContent = leadingContent")
        assertThat(providerListSource).contains("providerHostLabel(")
        assertThat(providerListSource).contains("InfinityMonospace")
        assertThat(providerListSource).doesNotContain("InfinityGlassCard")
    }

    @Test
    fun `提供商同处一个分组且主机名凭证模型数与连接状态写在行内`() {
        assertThat(providerListSource).contains("verticalArrangement = Arrangement.spacedBy(0.dp)")
        assertThat(providerListSource).contains("BettboxListGroup(")
        assertThat(providerListSource).doesNotContain("footer = provider.baseUrl")
        assertThat(providerListSource).contains("R.string.settings_provider_status_summary")
        assertThat(providerListSource).contains("R.plurals.provider_list_model_count")
        assertThat(providerListSource).contains("ProviderConnectionLine(")
        assertThat(providerListSource).contains("provider.rawProtocolId")
        assertThat(providerListSource).doesNotContain("state.providers.map")
    }

    @Test
    fun `content seam exposes state actions and content without view model or android side effects`() {
        assertThat(screenSource).contains("data class UserSettingsHomeScreenState(")
        assertThat(screenSource).contains("data class UserSettingsHomeScreenActions(")
        assertThat(screenSource).contains("fun UserSettingsHomeScreenContent(")

        val content = screenSource.substringAfter("fun UserSettingsHomeScreenContent(")
        assertThat(content).doesNotContain("viewModel(")
        assertThat(content).doesNotContain("SettingsViewModel")
    }

    @Test
    fun `state only carries values rendered by the title-only home surface`() {
        val state = screenSource.substringAfter("data class UserSettingsHomeScreenState(")
            .substringBefore(")\n\ninternal data class UserSettingsHomeScreenActions")

        assertThat(state).contains("val userName")
        assertThat(state).contains("val userAvatar")
        assertThat(state).contains("val localInferenceAvailable")
        assertThat(state).doesNotContain("val tokenCost")
        assertThat(state).doesNotContain("val themePreferences")
        assertThat(state).doesNotContain("val providerCount")
        assertThat(state).doesNotContain("val configuredDefaultModelsCount")
        assertThat(state).doesNotContain("val versionName")

        assertThat(providerListSource).contains("val providers")
    }

    @Test
    fun `app settings content no longer collects or receives view model`() {
        assertThat(screenSource).doesNotContain("viewModel: SettingsViewModel")
    }

    @Test
    fun `route wires real view model state into content via state and actions`() {
        assertThat(screenSource).contains("UserSettingsHomeScreenState(")
        assertThat(screenSource).contains("UserSettingsHomeScreenActions(")
        assertThat(screenSource).contains("UserSettingsHomeScreenContent(")
    }

    @Test
    fun `settings home observes manager state without synchronous full refresh`() {
        assertThat(screenSource).doesNotContain("viewModel.refreshProviders()")
    }

    @Test
    fun `local inference entry is gated by state local inference availability`() {
        assertThat(screenSource).contains("state.localInferenceAvailable")
        assertThat(screenSource).contains("UiTags.SETTINGS_LOCAL_INFERENCE_ENTRY")
    }

    @Test
    fun `settings ui tags cover root tabs lists add provider card actions and local inference`() {
        assertThat(uiTagsSource).contains("SETTINGS_ROOT")
        assertThat(uiTagsSource).contains("SETTINGS_APP_LIST")
        assertThat(uiTagsSource).contains("SETTINGS_PROVIDER_LIST")
        assertThat(uiTagsSource).contains("SETTINGS_ADD_PROVIDER")
        assertThat(uiTagsSource).contains("SETTINGS_PROVIDER_ACTIONS")
        assertThat(uiTagsSource).contains("SETTINGS_LOCAL_INFERENCE_ENTRY")
    }

    @Test
    fun `provider card and actions tags are stable and unique per provider id`() {
        assertThat(uiTagsSource).contains("fun settingsProviderCard(providerId: String): String")
        assertThat(uiTagsSource).contains("fun settingsProviderActions(providerId: String): String")

        assertThat(providerListSource).contains("UiTags.settingsProviderCard(")
        assertThat(providerListSource).contains("UiTags.settingsProviderActions(")
    }
}
