package com.promenar.infinity.ui.hub

import androidx.compose.foundation.interaction.MutableInteractionSource
import com.promenar.infinity.ui.common.dimOnPress
import com.promenar.infinity.ui.theme.InfinityMotion
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.activity.compose.LocalActivity
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Toll
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.promenar.infinity.ui.chat.LedMorphWordmark
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityRetryButton
import com.promenar.infinity.ui.developer.DeveloperModeStore
import com.promenar.infinity.ui.developer.DeveloperUnlockCounter
import com.promenar.infinity.ui.developer.DeveloperUnlockResult
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinityTabHeader
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityPopoverAlign
import com.promenar.infinity.ui.common.InfinityPopoverAnchor
import com.promenar.infinity.ui.common.InfinityPopoverSpec
import com.promenar.infinity.ui.common.infinityPopoverAnchor
import com.promenar.infinity.ui.common.rememberInfinityPopoverAnchor
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.settings.EnvironmentConnectionRows
import com.promenar.infinity.ui.settings.EnvironmentConnectionState
import com.promenar.infinity.ui.settings.SettingsViewModel
import com.promenar.infinity.ui.settings.rememberEnvironmentConnectionState
import com.promenar.infinity.ui.settings.SettingsAsyncErrorCode
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.avatar.rememberAvatarCropLauncher
import com.promenar.infinity.ui.theme.forInput
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSettingsHomeScreen(
    onNavigateToSecondary: (String) -> Unit,
    selectedRoute: String? = null,
) {
    val context = LocalContext.current
    val viewModel = viewModel<SettingsViewModel>(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val userName by viewModel.userName.collectAsState()
    val userAvatar by viewModel.userAvatar.collectAsState()
    val profilePrefersBrand by viewModel.profilePrefersBrand.collectAsState()
    val language by viewModel.language.collectAsState()
    val settingsError by viewModel.settingsError.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val environment = rememberEnvironmentConnectionState()
    val developerModeStore = remember(context) { DeveloperModeStore.get(context) }
    val developerModeEnabled by developerModeStore.enabled.collectAsState()
    val diagnosticsChooserTitle = stringResource(R.string.settings_diagnostics_export)
    val coroutineScope = rememberCoroutineScope()

    var showNameEditor by remember { mutableStateOf(false) }
    var showProfileMenu by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(userName) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    val languageAnchor = rememberInfinityPopoverAnchor()
    var showAbout by remember { mutableStateOf(false) }
    val aboutAnchor = rememberInfinityPopoverAnchor()
    val avatarCropLauncher = rememberAvatarCropLauncher(
        onCropped = { viewModel.updateUserAvatar(it.toString()) },
        onFailure = viewModel::reportAvatarImportFailure,
    )

    val state = remember(userName, userAvatar, profilePrefersBrand, settingsError, selectedRoute, language, mcpServers.size, environment, developerModeEnabled) {
        UserSettingsHomeScreenState(
            userName = userName,
            selectedRoute = selectedRoute,
            userAvatar = userAvatar,
            profilePrefersBrand = profilePrefersBrand,
            error = settingsError,
            localInferenceAvailable = BuildConfig.LOCAL_INFERENCE_AVAILABLE,
            language = language,
            mcpServerCount = mcpServers.size,
            environment = environment,
            developerModeEnabled = developerModeEnabled,
        )
    }
    val exportDiagnostics: () -> Unit = {
        coroutineScope.launch {
            val file = com.promenar.infinity.utils.InfinityLogger.prepareDiagnosticExport(context)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/x-ndjson"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, diagnosticsChooserTitle),
            )
        }
    }

    val repositoryUrl = stringResource(R.string.ux2_project_repository_url)
    val actions = UserSettingsHomeScreenActions(
            onChangeAvatar = {
                avatarCropLauncher.launch()
            },
            onEditName = {
                editingName = userName
                showNameEditor = true
            },
            onToggleProfileDisplay = { viewModel.setProfilePrefersBrand(!profilePrefersBrand) },
            onOpenProfileMenu = { showProfileMenu = true },
            onShowLanguageDialog = { if (!languageAnchor.consumeRecentDismiss()) showLanguageDialog = true },
            onNavigateToSecondary = onNavigateToSecondary,
            onAboutClick = { if (!aboutAnchor.consumeRecentDismiss()) showAbout = true },
            onOpenGithub = {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(repositoryUrl),
                    ),
                )
            },
            onRetryError = viewModel::retryLastError,
            onExportDiagnostics = exportDiagnostics,
        )

    UserSettingsHomeScreenContent(state = state, actions = actions, languageAnchor = languageAnchor, aboutAnchor = aboutAnchor)

    if (showAbout) {
        AboutInfinitySheet(
            onOpenGithub = actions.onOpenGithub,
            onDismiss = { showAbout = false },
            popover = InfinityPopoverSpec(aboutAnchor, align = InfinityPopoverAlign.Auto),
            onVersionTap = rememberDeveloperUnlockTap(developerModeStore),
        )
    }

    if (showProfileMenu) {
        ProfileMenuSheet(
            hasAvatar = state.userAvatar != null,
            showsBrand = state.profileShowsBrand,
            onToggleDisplay = {
                showProfileMenu = false
                actions.onToggleProfileDisplay()
            },
            onSetAvatar = {
                showProfileMenu = false
                actions.onChangeAvatar()
            },
            onEditName = {
                showProfileMenu = false
                actions.onEditName()
            },
            onDismiss = { showProfileMenu = false },
        )
    }

    if (showNameEditor) {
        NameEditDialog(
            currentName = editingName,
            onDismiss = { showNameEditor = false },
            onSave = {
                viewModel.updateUserName(it)
                showNameEditor = false
            }
        )
    }

    if (showLanguageDialog) {
        val activity = LocalActivity.current
        LanguageSelectorSheet(
            currentLanguage = language,
            onSelect = { lang ->
                viewModel.setLanguage(lang)
                showLanguageDialog = false
                activity?.recreate()
            },
            onDismiss = { showLanguageDialog = false },
            popover = InfinityPopoverSpec(languageAnchor, align = InfinityPopoverAlign.Auto),
        )
    }
}

internal data class UserSettingsHomeScreenState(
    val userName: String = "",
    /** 宽屏双列时右列正在显示的根页面，对应条目高亮；单列为 null。 */
    val selectedRoute: String? = null,
    val userAvatar: String? = null,
    /** 已设头像时仍选择在顶部显示品牌标识。 */
    val profilePrefersBrand: Boolean = false,
    val localInferenceAvailable: Boolean = false,
    val error: SettingsAsyncErrorCode? = null,
    /** 偏好中保存的界面语言（"zh" / "en"），语言行按它显示，不读系统区域。 */
    val language: String = "zh",
    val mcpServerCount: Int? = null,
    val environment: EnvironmentConnectionState = EnvironmentConnectionState(),
    /** 版本号连点解锁后为 true，“关于”前多出开发者分组。 */
    val developerModeEnabled: Boolean = false,
) {
    /** 没有头像时总显示品牌标识；有头像时按用户的选择。 */
    val profileShowsBrand: Boolean get() = userAvatar == null || profilePrefersBrand
}

internal data class UserSettingsHomeScreenActions(
    val onChangeAvatar: () -> Unit = {},
    val onEditName: () -> Unit = {},
    val onToggleProfileDisplay: () -> Unit = {},
    val onOpenProfileMenu: () -> Unit = {},
    val onShowLanguageDialog: () -> Unit = {},
    val onNavigateToSecondary: (String) -> Unit = {},
    val onAboutClick: () -> Unit = {},
    val onOpenGithub: () -> Unit = {},
    val onRetryError: () -> Unit = {},
    val onExportDiagnostics: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserSettingsHomeScreenContent(
    state: UserSettingsHomeScreenState,
    actions: UserSettingsHomeScreenActions,
    /** 语言行的位置：宽窗口下语言选择贴着该行弹出。 */
    languageAnchor: InfinityPopoverAnchor? = null,
    /** “关于”行的位置：宽窗口下关于面板贴着该行弹出。 */
    aboutAnchor: InfinityPopoverAnchor? = null,
) {
    InfinitySettingsPageLayout(
        title = stringResource(R.string.settings_title),
        modifier = Modifier.testTag(UiTags.SETTINGS_ROOT),
        horizontalContentPadding = 0.dp,
        header = { InfinityTabHeader(title = stringResource(R.string.settings_title)) },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(UiTags.SETTINGS_APP_LIST),
            contentPadding = contentPadding,
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(
                        start = InfinitySpacing.ScreenHorizontal,
                        top = InfinitySpacing.Small,
                        end = InfinitySpacing.ScreenHorizontal,
                        bottom = InfinitySpacing.SectionGap,
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    UserProfileHeader(
                        userName = state.userName,
                        avatarUri = state.userAvatar,
                        showsBrand = state.profileShowsBrand,
                        onOpenMenu = actions.onOpenProfileMenu,
                    )
                    state.error?.let { error ->
                        Text(
                            text = stringResource(
                                when (error) {
                                    SettingsAsyncErrorCode.AVATAR_IMPORT_FAILED -> R.string.settings_error_avatar
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = InfinitySpacing.Small),
                        )
                        InfinityRetryButton(
                            onClick = actions.onRetryError,
                            modifier = Modifier.padding(vertical = InfinitySpacing.XSmall),
                        )
                    }
                }
            }

            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_section_general),
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    InfinityValueRow(
                        icon = Icons.Rounded.Language,
                        title = stringResource(R.string.settings_language),
                        value = stringResource(if (state.language == "en") R.string.settings_language_en else R.string.settings_language_zh),
                        onClick = actions.onShowLanguageDialog,
                        modifier = if (languageAnchor != null) Modifier.infinityPopoverAnchor(languageAnchor) else Modifier,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Palette,
                        title = stringResource(R.string.settings_appearance),
                        subtitle = stringResource(R.string.settings_appearance_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.THEME_CONFIG) },
                        selected = state.selectedRoute == NavDestinations.THEME_CONFIG,
                    )
                }
            }

            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_ia_section_models),
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Storage,
                        title = stringResource(R.string.settings_provider_management),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.PROVIDER_LIST) },
                        selected = state.selectedRoute == NavDestinations.PROVIDER_LIST,
                        modifier = Modifier.testTag(UiTags.SETTINGS_PROVIDER_ENTRY),
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.SmartToy,
                        title = stringResource(R.string.settings_default_models),
                        subtitle = stringResource(R.string.settings_ia_default_models_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.DEFAULT_MODELS) },
                        selected = state.selectedRoute == NavDestinations.DEFAULT_MODELS,
                        modifier = Modifier.testTag(UiTags.SETTINGS_DEFAULT_MODELS_ENTRY),
                    )
                    if (state.localInferenceAvailable) {
                        Box(modifier = Modifier.testTag(UiTags.SETTINGS_LOCAL_INFERENCE_ENTRY)) {
                            UserSettingsNavigationItem(
                                icon = Icons.Rounded.Memory,
                                title = stringResource(R.string.settings_local_models),
                                subtitle = stringResource(R.string.settings_local_models_desc),
                                onClick = { actions.onNavigateToSecondary(NavDestinations.LOCAL_MODELS) },
                                selected = state.selectedRoute == NavDestinations.LOCAL_MODELS,
                            )
                        }
                    }
                }
            }

            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_ia_section_agent_runtime),
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Loop,
                        title = stringResource(R.string.settings_execution_budget),
                        subtitle = stringResource(R.string.settings_ia_budget_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.AGENT_BUDGET) },
                        selected = state.selectedRoute == NavDestinations.AGENT_BUDGET,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Build,
                        title = stringResource(R.string.settings_ia_builtin_tools_title),
                        subtitle = stringResource(R.string.settings_ia_builtin_tools_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.BUILTIN_TOOLS) },
                        selected = state.selectedRoute == NavDestinations.BUILTIN_TOOLS,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.AutoAwesome,
                        title = stringResource(R.string.settings_ia_skills_title),
                        subtitle = stringResource(R.string.settings_ia_skills_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.SKILLS_CONFIG) },
                        selected = state.selectedRoute == NavDestinations.SKILLS_CONFIG,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Hub,
                        title = stringResource(R.string.settings_ia_mcp_title),
                        subtitle = when (val count = state.mcpServerCount) {
                            null, 0 -> stringResource(R.string.settings_ia_mcp_empty)
                            else -> pluralStringResource(R.plurals.settings_ia_mcp_count, count, count)
                        },
                        onClick = { actions.onNavigateToSecondary(NavDestinations.MCP_SERVERS) },
                        selected = state.selectedRoute == NavDestinations.MCP_SERVERS,
                    )
                }
            }

            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_ia_section_knowledge),
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Psychology,
                        title = stringResource(R.string.settings_ia_kb_settings_title),
                        subtitle = stringResource(R.string.settings_ia_kb_settings_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.RAG_GLOBAL_CONFIG) },
                        selected = state.selectedRoute == NavDestinations.RAG_GLOBAL_CONFIG,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.AccountTree,
                        title = stringResource(R.string.settings_ia_kg_title),
                        subtitle = stringResource(R.string.settings_ia_kg_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.RAG_ADVANCED_KG) },
                        selected = state.selectedRoute == NavDestinations.RAG_ADVANCED_KG,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Bookmarks,
                        title = stringResource(R.string.ltm_title),
                        subtitle = stringResource(R.string.settings_ia_ltm_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.LONG_TERM_MEMORY) },
                        selected = state.selectedRoute == NavDestinations.LONG_TERM_MEMORY,
                    )
                    // 检索调试只在开发者模式下出现在设置目录。
                    if (state.developerModeEnabled) {
                        UserSettingsNavigationItem(
                            icon = Icons.Rounded.Tune,
                            title = stringResource(R.string.settings_ia_debug_title),
                            subtitle = stringResource(R.string.settings_ia_debug_desc),
                            onClick = { actions.onNavigateToSecondary(NavDestinations.RAG_DEBUG) },
                            selected = state.selectedRoute == NavDestinations.RAG_DEBUG,
                        )
                    }
                }
            }

            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_ia_section_environment),
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    EnvironmentConnectionRows(
                        state = state.environment,
                        onOpen = actions.onNavigateToSecondary,
                        selectedRoute = state.selectedRoute,
                    )
                }
            }

            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_ia_section_data),
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Toll,
                        title = stringResource(R.string.settings_token_usage),
                        subtitle = stringResource(R.string.settings_batch1_home_token_summary),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.TOKEN_USAGE) },
                        selected = state.selectedRoute == NavDestinations.TOKEN_USAGE,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Backup,
                        title = stringResource(R.string.settings_backup),
                        subtitle = stringResource(R.string.settings_backup_desc),
                        onClick = { actions.onNavigateToSecondary(NavDestinations.BACKUP_SETTINGS) },
                        selected = state.selectedRoute == NavDestinations.BACKUP_SETTINGS,
                    )
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.BugReport,
                        title = stringResource(R.string.settings_diagnostics_export),
                        onClick = actions.onExportDiagnostics,
                    )
                }
            }

            if (state.developerModeEnabled) {
                item {
                    InfinitySettingsSection(
                        title = stringResource(R.string.devmode_section),
                        headerStartPadding = InfinitySpacing.ScreenHorizontal,
                    ) {
                        UserSettingsNavigationItem(
                            icon = Icons.Rounded.DeveloperMode,
                            title = stringResource(R.string.devmode_entry_title),
                            subtitle = stringResource(R.string.devmode_entry_desc),
                            onClick = { actions.onNavigateToSecondary(NavDestinations.DEVELOPER) },
                            selected = state.selectedRoute == NavDestinations.DEVELOPER,
                            modifier = Modifier.testTag("settings_developer"),
                        )
                    }
                }
            }

            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_section_about),
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    UserSettingsNavigationItem(
                        icon = Icons.Rounded.Info,
                        title = stringResource(R.string.settings_about_infinity),
                        subtitle = appVersionLine(AppVersionInfo.current()),
                        onClick = actions.onAboutClick,
                        modifier = if (aboutAnchor != null) Modifier.infinityPopoverAnchor(aboutAnchor) else Modifier,
                    )
                }
            }

            item {
                GitHubProjectFooter(onOpen = actions.onOpenGithub)
                Spacer(modifier = Modifier.size(InfinitySpacing.Small))
            }
        }
    }
}

@Composable
private fun UserSettingsNavigationItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    InfinitySettingsItem(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        modifier = if (selected) modifier.background(MaterialTheme.colorScheme.secondaryContainer) else modifier,
        showChevron = true,
        selected = selected.takeIf { it },
    )
}

/**
 * 设置页顶部的个人区：没有头像或用户选择品牌时显示灯珠变形字标，否则显示头像与名字。
 * 两种形态都只有一个点按目标，点它弹出个人资料面板，在面板里切换显示、换头像或改名。
 * 两种形态同占 [ProfileHeaderHeight]，切换时下面的列表不跳；名字两行或大字号让名片更高时，高度变化走动画。
 */
@Composable
private fun UserProfileHeader(
    userName: String,
    avatarUri: String?,
    showsBrand: Boolean,
    onOpenMenu: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ProfileHeaderHeight)
            .animateContentSize(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)),
        contentAlignment = Alignment.Center,
    ) {
        if (showsBrand) {
            ProfileBrandMark(onClick = onOpenMenu)
        } else {
            UserProfileCard(userName, avatarUri, onClick = onOpenMenu)
        }
    }
}

/** 个人区高度：与首次引导的灯珠字标同高，头像名片在其中垂直居中。 */
private val ProfileHeaderHeight = 170.dp

/** 与首次引导同一个灯珠变形字标，灯珠数不缩减；点按打开个人资料面板。字标没有容器，按下时整体变暗。 */
@Composable
private fun ProfileBrandMark(onClick: () -> Unit) {
    val menuLabel = stringResource(R.string.settings_profile_menu)
    val press = remember { MutableInteractionSource() }
    LedMorphWordmark(
        modifier = Modifier
            .fillMaxWidth()
            .height(ProfileHeaderHeight)
            .testTag(UiTags.SETTINGS_PROFILE_BRAND)
            .semantics { contentDescription = menuLabel }
            .dimOnPress(press)
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClickLabel = menuLabel, onClick = onClick),
        centerFraction = 0.5f,
    )
}

/** 头像与名字居中，整体是一个点按目标，点按打开个人资料面板；与品牌字标一样按下整体变暗。 */
@Composable
private fun UserProfileCard(
    userName: String,
    avatarUri: String?,
    onClick: () -> Unit,
) {
    val menuLabel = stringResource(R.string.settings_profile_menu)
    val press = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .testTag(UiTags.SETTINGS_PROFILE_CARD)
            .dimOnPress(press)
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClickLabel = menuLabel, onClick = onClick)
            .padding(InfinitySpacing.Small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .testTag(UiTags.SETTINGS_USER_AVATAR),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUri != null) {
                AsyncImage(
                    model = avatarUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                Text(
                    text = userName.take(1).uppercase(),
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(modifier = Modifier.height(InfinitySpacing.Small))
        Text(
            text = userName,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(UiTags.SETTINGS_USER_NAME),
        )
    }
}

/** 渠道显示名：侧载版或 Play 版。 */
@Composable
private fun channelLabel(info: AppVersionInfo): String = stringResource(
    if (info.isSideload) R.string.ux2_settings_channel_sideload else R.string.ux2_settings_channel_play,
)

/** “关于”行副标题与关于面板里的版本一行：“版本名（versionCode）· 渠道”。 */
@Composable
internal fun appVersionLine(info: AppVersionInfo): String =
    appVersionLine(info, stringResource(R.string.settings_about_version), channelLabel(info))

/**
 * 设置首页底部：居中一个 GitHub 标志按钮打开项目主页。标志 20dp、次要文字色，触控区 48dp，带内容描述。
 * 版本号在“关于”行副标题里，复制版本信息在关于面板里。
 */
@Composable
internal fun GitHubProjectFooter(onOpen: () -> Unit) {
    val githubLabel = stringResource(R.string.ux2_settings_github_open)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(InfinitySpacing.MinimumTouchTarget)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = githubLabel, onClick = onOpen)
                .semantics { contentDescription = githubLabel }
                .testTag("settings_github_button"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github_mark),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 关于面板：版本一行（版本名、versionCode、渠道）与提交短 SHA，下面是“复制版本信息”和“在 GitHub 上打开”两行。
 * 复制内容与原设置页底部一致；Android 13 起系统自带复制提示，只在更早版本补一条。
 */
@Composable
internal fun AboutInfinitySheet(
    onOpenGithub: () -> Unit,
    onDismiss: () -> Unit,
    popover: InfinityPopoverSpec? = null,
    versionInfo: AppVersionInfo = AppVersionInfo.current(),
    /** 点版本号一行：连点开启开发者模式。 */
    onVersionTap: () -> Unit = {},
) {
    val context = LocalContext.current
    val channel = channelLabel(versionInfo)
    val copyText = appVersionCopyText(versionInfo, stringResource(R.string.ux2_settings_version_copy_text), channel)
    val copyLabel = stringResource(R.string.ux2_settings_version_copy_action)
    val copiedToast = stringResource(R.string.ux2_settings_version_copied)
    InfinityBottomSheet(
        show = true,
        scrollContent = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_about_infinity),
        popover = popover,
    ) {
        Text(
            text = appVersionLine(versionInfo),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clickable(interactionSource = null, indication = null, onClick = onVersionTap)
                .testTag("settings_about_version"),
        )
        Text(
            text = stringResource(R.string.settings_about_commit, versionInfo.commitOrUnknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = InfinitySpacing.XSmall, bottom = InfinitySpacing.Small),
        )
        InfinitySettingsItem(
            horizontalPadding = 0.dp,
            icon = Icons.Rounded.ContentCopy,
            title = copyLabel,
            onClick = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(copyLabel, copyText))
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    android.widget.Toast.makeText(context, copiedToast, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.testTag("settings_about_copy_version"),
        )
        InfinitySettingsItem(
            horizontalPadding = 0.dp,
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            title = stringResource(R.string.ux2_settings_github_open),
            onClick = {
                onDismiss()
                onOpenGithub()
            },
            modifier = Modifier.testTag("settings_about_github"),
        )
    }
}

/** 版本号连点：按 [DeveloperUnlockCounter] 的结果弹提示，满次数时打开开发者模式。 */
@Composable
private fun rememberDeveloperUnlockTap(store: DeveloperModeStore): () -> Unit {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val counter = remember { DeveloperUnlockCounter() }
    var toast by remember { mutableStateOf<android.widget.Toast?>(null) }
    return remember(store, context, resources) {
        {
            val message = when (val result = counter.tap(android.os.SystemClock.uptimeMillis(), store.enabled.value)) {
                DeveloperUnlockResult.Silent -> null
                is DeveloperUnlockResult.Remaining ->
                    resources.getQuantityString(R.plurals.devmode_taps_remaining, result.taps, result.taps)
                DeveloperUnlockResult.Unlocked -> {
                    store.setEnabled(true)
                    resources.getString(R.string.devmode_unlocked)
                }
                DeveloperUnlockResult.AlreadyEnabled -> resources.getString(R.string.devmode_already_enabled)
            }
            if (message != null) {
                // 连点时新提示顶掉旧提示，避免排队弹很久。
                toast?.cancel()
                toast = android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).also { it.show() }
            }
        }
    }
}

@Composable
private fun NameEditDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    InfinityConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_edit_name),
        message = "",
        confirmText = stringResource(R.string.settings_btn_save),
        cancelText = stringResource(R.string.settings_btn_cancel),
        onConfirm = { onSave(name) },
        onCancel = onDismiss,
        content = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                singleLine = true
            )
        },
    )
}

@Composable
internal fun LanguageSelectorSheet(
    currentLanguage: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    popover: InfinityPopoverSpec? = null,
) {
    InfinityBottomSheet(
        show = true,
        scrollContent = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_language),
        popover = popover,
    ) {
                LanguageOption(
                    label = stringResource(R.string.settings_language_zh),
                    isSelected = currentLanguage == "zh",
                    onSelect = { onSelect("zh") }
                )
                LanguageOption(
                    label = stringResource(R.string.settings_language_en),
                    isSelected = currentLanguage == "en",
                    onSelect = { onSelect("en") }
                )
    }
}

/** 点个人区弹出：有头像时可在头像与品牌动画间切换，另可换头像、改名字。 */
@Composable
private fun ProfileMenuSheet(
    hasAvatar: Boolean,
    showsBrand: Boolean,
    onToggleDisplay: () -> Unit,
    onSetAvatar: () -> Unit,
    onEditName: () -> Unit,
    onDismiss: () -> Unit,
) {
    InfinityBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_profile_menu),
    ) {
        if (hasAvatar) {
            InfinitySettingsItem(
                icon = if (showsBrand) Icons.Rounded.Person else Icons.Rounded.AllInclusive,
                title = stringResource(if (showsBrand) R.string.settings_profile_show_avatar else R.string.settings_profile_show_brand),
                onClick = onToggleDisplay,
                modifier = Modifier.testTag(UiTags.SETTINGS_PROFILE_DISPLAY_TOGGLE),
                horizontalPadding = 0.dp,
            )
        }
        InfinitySettingsItem(
            icon = Icons.Rounded.PhotoCamera,
            title = stringResource(if (hasAvatar) R.string.settings_profile_change_avatar else R.string.settings_profile_set_avatar),
            onClick = onSetAvatar,
            horizontalPadding = 0.dp,
        )
        InfinitySettingsItem(
            icon = Icons.Rounded.Edit,
            title = stringResource(R.string.settings_edit_name),
            onClick = onEditName,
            horizontalPadding = 0.dp,
        )
    }
}

@Composable
internal fun LanguageOption(
    label: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    InfinitySettingsItem(
                horizontalPadding = 0.dp,
        title = label,
        onClick = onSelect,
        role = Role.RadioButton,
        selected = isSelected,
        trailingContent = {
            if (isSelected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
        },
    )
}
