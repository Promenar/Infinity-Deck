package com.promenar.infinity.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.local.ProviderStatus
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.UnsupportedProviderListItem
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.tailnet.TailnetEndpoints
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityMenuDivider
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinityTypographicFootnote
import com.promenar.infinity.ui.common.ModelBrandIcon
import com.promenar.infinity.ui.common.ModelBrandResolver
import com.promenar.infinity.ui.common.brandAccentColor
import com.promenar.infinity.ui.common.providerDisplayName
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors

@Composable
fun ProviderListScreen(
    onNavigateToSecondary: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as InfinityApplication
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
    val providers by viewModel.providers.collectAsState()
    val unsupportedProviders by viewModel.unsupportedProviders.collectAsState()
    val models by viewModel.providerModels.collectAsState()
    val statuses by viewModel.providerStatuses.collectAsState()
    val tailnetState by app.tailnetManager.state.collectAsStateWithLifecycle()

    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    val modelCounts = remember(models) { providerModelCounts(models) }
    val mediaCounts = remember(models) { providerMediaCounts(models) }
    val tailnetProviderIds = remember(providers, tailnetState) {
        providers.filter { TailnetEndpoints.isTailnetEndpoint(it.baseUrl, tailnetState) }.mapTo(mutableSetOf()) { it.id }
    }
    val state = remember(providers, unsupportedProviders, modelCounts, mediaCounts, statuses, tailnetProviderIds) {
        ProviderListScreenState(
            providers = providers,
            unsupportedProviders = unsupportedProviders,
            modelCounts = modelCounts,
            mediaCounts = mediaCounts,
            statuses = statuses,
            tailnetProviderIds = tailnetProviderIds,
        )
    }

    val actions = remember(viewModel, onNavigateToSecondary, onNavigateBack) {
        ProviderListScreenActions(
            onNavigateBack = onNavigateBack,
            onAddProvider = { onNavigateToSecondary(NavDestinations.providerForm()) },
            onEditProvider = { id -> onNavigateToSecondary(NavDestinations.providerForm(id)) },
            onProviderModels = { id -> onNavigateToSecondary(NavDestinations.providerModels(id)) },
            onRequestDeleteProvider = { id -> showDeleteDialog = id }
        )
    }

    ProviderListScreenContent(
        state = state,
        actions = actions,
    )

    if (showDeleteDialog != null) {
        InfinityConfirmDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = stringResource(R.string.settings_delete_provider_title),
            message = stringResource(R.string.settings_delete_provider_message),
            confirmText = stringResource(R.string.settings_btn_delete),
            isDestructive = true,
            onConfirm = {
                showDeleteDialog?.let { viewModel.deleteProvider(it) }
                showDeleteDialog = null
            },
            onCancel = { showDeleteDialog = null }
        )
    }
}

internal data class ProviderListScreenState(
    val providers: List<ProviderListItem> = emptyList(),
    val unsupportedProviders: List<UnsupportedProviderListItem> = emptyList(),
    /** 按 providerId 统计的模型数；缺省视为 0 / 0。 */
    val modelCounts: Map<String, ProviderModelCount> = emptyMap(),
    /** 最近一次连接测试与模型同步记录。 */
    val statuses: Map<String, ProviderStatus> = emptyMap(),
    /** 地址指向 Tailscale 内网的提供商。 */
    val tailnetProviderIds: Set<String> = emptySet(),
    val mediaCounts: Map<String, ProviderMediaCount> = emptyMap(),
)

internal data class ProviderListScreenActions(
    val onNavigateBack: () -> Unit = {},
    val onAddProvider: () -> Unit = {},
    val onEditProvider: (String) -> Unit = {},
    val onProviderModels: (String) -> Unit = {},
    val onRequestDeleteProvider: (String) -> Unit = {}
)

@Composable
internal fun ProviderListScreenContent(
    state: ProviderListScreenState,
    actions: ProviderListScreenActions,
) {
    InfinitySettingsPageLayout(
        title = stringResource(R.string.settings_provider_management),
        onBack = actions.onNavigateBack,
        horizontalContentPadding = 0.dp,
        modifier = Modifier.testTag(UiTags.SETTINGS_PROVIDER_LIST),
        actions = {
            val addProviderDesc = stringResource(R.string.settings_add_provider)
            FilledTonalButton(
                onClick = actions.onAddProvider,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier
                    .padding(end = 8.dp)
                    .height(34.dp)
                    .semantics { contentDescription = addProviderDesc }
                    .testTag(UiTags.SETTINGS_ADD_PROVIDER),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.provider_list_add_short),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    ) { contentPadding ->
        val compactHeightEmptyState = LocalConfiguration.current.screenHeightDp < 480

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (state.providers.isEmpty() && state.unsupportedProviders.isEmpty()) {
                item("provider-empty") {
                    Box(Modifier.animateItem()) {
                        ProviderEmptyState(compactHeight = compactHeightEmptyState, onAdd = actions.onAddProvider)
                    }
                }
            } else {
                item("provider-summary") {
                    val summary = providerListSummary(state.providers, state.unsupportedProviders.size)
                    val enabledModelTotal = remember(state.modelCounts) {
                        state.modelCounts.values.sumOf { it.enabled }
                    }
                    Text(
                        text = stringResource(R.string.provider_list_models_summary, summary.total, enabledModelTotal),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = InfinitySpacing.SectionHeaderBottom)
                            .testTag(UiTags.PROVIDER_LIST_SUMMARY),
                    )
                }
                if (state.providers.isNotEmpty() || state.unsupportedProviders.isNotEmpty()) item("provider-group") {
                    // 全部提供商在一张连续分组卡片里；新增或删除时卡片高度平滑过渡，空态与分组之间淡入淡出。
                    BettboxListGroup(
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 16.dp)
                            .animateContentSize(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)),
                    ) {
                        val rowCount = state.providers.size + state.unsupportedProviders.size
                        state.providers.forEachIndexed { index, provider ->
                            key(provider.id) {
                                ProviderCard(
                                    provider = provider,
                                    counts = state.modelCounts[provider.id] ?: ProviderModelCount(0, 0),
                                    onTailnet = provider.id in state.tailnetProviderIds,
                                    onClick = { actions.onProviderModels(provider.id) },
                                    onEdit = { actions.onEditProvider(provider.id) },
                                    onDelete = { actions.onRequestDeleteProvider(provider.id) },
                                )
                                if (index < rowCount - 1) InfinityGroupDivider(startInset = ProviderDividerInset)
                            }
                        }
                        state.unsupportedProviders.forEachIndexed { index, provider ->
                            key(provider.id) {
                                UnsupportedProviderCard(
                                    provider = provider,
                                    onEdit = { actions.onEditProvider(provider.id) },
                                    onDelete = { actions.onRequestDeleteProvider(provider.id) },
                                )
                                if (state.providers.size + index < rowCount - 1) {
                                    InfinityGroupDivider(startInset = ProviderDividerInset)
                                }
                            }
                        }
                    }
                }
                item("provider-footnote") {
                    InfinityTypographicFootnote(
                        text = stringResource(R.string.provider_list_footnote),
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

private val ProviderAvatarSize = 42.dp
private val ProviderDividerInset = 14.dp + ProviderAvatarSize + 12.dp

/** 协议不受支持：整行减淡，标签提示，点按进入编辑选择受支持的协议；⋮ 只有删除。 */
@Composable
private fun UnsupportedProviderCard(
    provider: UnsupportedProviderListItem,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var menuExpanded by remember(provider.id) { mutableStateOf(false) }
    val unsupportedDescription = stringResource(R.string.settings_provider_protocol_unsupported)
    val providerName = providerDisplayName(provider.name, protocol = null)
    val deleteDescription = stringResource(R.string.shared_btn_delete)
    ProviderRowFrame(
        modifier = Modifier
            .testTag(UiTags.settingsProviderCard(provider.id))
            .semantics { stateDescription = unsupportedDescription },
        onClick = onEdit,
        onClickLabel = stringResource(R.string.common_cd_edit),
        leading = { Box(Modifier.alpha(0.55f)) { ProviderAvatar(providerName, baseUrl = null) } },
        trailing = {
            if (onDelete == null) {
                IconButton(onClick = onEdit, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget)) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.common_cd_edit))
                }
            } else Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .testTag(UiTags.settingsProviderActions(provider.id))
                        .size(InfinitySpacing.MinimumTouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "$providerName: $deleteDescription",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InfinityDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    InfinityMenuItem(
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        text = { Text(deleteDescription) },
                        destructive = true,
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        modifier = Modifier
                            .testTag("${UiTags.settingsProviderActions(provider.id)}:delete")
                            .semantics { role = Role.Button },
                    )
                }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                providerName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            ProviderStateChip(unsupportedDescription, warning = true)
        }
        Text(
            provider.rawProtocolId,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(R.string.provider_list_unsupported_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderEmptyState(compactHeight: Boolean, onAdd: () -> Unit) {
    val title = stringResource(R.string.provider_list_empty_title)
    val addButton: @Composable () -> Unit = {
        FilledTonalButton(
            onClick = onAdd,
            modifier = Modifier
                .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                .testTag(UiTags.PROVIDER_LIST_EMPTY_ADD),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.settings_add_provider))
        }
    }
    if (compactHeight) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InfinitySpacing.XLarge, vertical = InfinitySpacing.Small)
                .testTag(UiTags.PROVIDER_LIST_EMPTY),
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderEmptyIcon(size = InfinitySpacing.MinimumTouchTarget)
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            addButton()
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InfinitySpacing.XLarge, vertical = InfinitySpacing.XXLarge)
                .testTag(UiTags.PROVIDER_LIST_EMPTY),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            ProviderEmptyIcon(size = 64.dp)
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(4.dp))
            addButton()
        }
    }
}

@Composable
private fun ProviderEmptyIcon(size: androidx.compose.ui.unit.Dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Psychology,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(InfinitySpacing.XXLarge),
            )
        }
    }
}

/**
 * 提供商精炼两行卡片：名称与语义微标（内网/代理/缺密钥/停用，常态密钥静默） / 协议短标与主机名；
 * 右侧展示等宽已启用/总模型数胶囊、右箭头与 ⋮ 动作菜单。
 */
@Composable
private fun ProviderCard(
    provider: ProviderListItem,
    counts: ProviderModelCount,
    onTailnet: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val providerName = providerDisplayName(provider.name, provider.protocolType)
    var menuExpanded by remember(provider.id) { mutableStateOf(false) }
    val manageDescription = stringResource(R.string.settings_manage_models)
    val editDescription = stringResource(R.string.shared_btn_edit)
    val deleteDescription = stringResource(R.string.shared_btn_delete)
    val actionsDescription = if (onDelete == null) {
        "$providerName: $editDescription"
    } else {
        "$providerName: $editDescription / $deleteDescription"
    }
    val enabledStatus = stringResource(
        if (provider.enabled) R.string.settings_provider_state_enabled else R.string.settings_provider_state_disabled,
    )
    val credentialStatus = providerCredentialStatus(provider)
    val providerStateDescription = stringResource(
        R.string.settings_provider_status_summary,
        enabledStatus,
        credentialStatus,
    )
    val protocolShort = remember(provider.protocolType) { providerShortProtocolTag(provider.protocolType) }
    val host = remember(provider.baseUrl) { providerHostLabel(provider.baseUrl) }
    val missingCredential = providerCredentialMissing(provider)
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors

    ProviderRowFrame(
        modifier = Modifier
            .testTag(UiTags.settingsProviderCard(provider.id))
            .semantics { stateDescription = providerStateDescription },
        onClick = onClick,
        onClickLabel = manageDescription,
        leading = {
            Box(Modifier.alpha(if (provider.enabled) 1f else 0.55f)) {
                ProviderAvatar(
                    name = providerName,
                    baseUrl = provider.baseUrl,
                    onTailnet = onTailnet,
                )
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val countActive = counts.enabled > 0
                Surface(
                    shape = CircleShape,
                    color = if (countActive) {
                        scheme.primaryContainer.copy(alpha = 0.72f)
                    } else {
                        scheme.surfaceContainerHighest.copy(alpha = 0.75f)
                    },
                ) {
                    Text(
                        text = "${counts.enabled}/${counts.total}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = InfinityMonospace,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.5.sp,
                        ),
                        color = if (countActive) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(18.dp),
                )
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .testTag(UiTags.settingsProviderActions(provider.id))
                            .size(InfinitySpacing.MinimumTouchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = actionsDescription,
                            tint = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    InfinityDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        InfinityMenuItem(
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            text = { Text(editDescription) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                            modifier = Modifier
                                .testTag("${UiTags.settingsProviderActions(provider.id)}:edit")
                                .semantics { role = Role.Button },
                        )
                        InfinityMenuItem(
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ListAlt, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            text = { Text(manageDescription) },
                            onClick = {
                                menuExpanded = false
                                onClick()
                            },
                            modifier = Modifier
                                .testTag("${UiTags.settingsProviderActions(provider.id)}:models")
                                .semantics { role = Role.Button },
                        )
                        if (onDelete != null) {
                            InfinityMenuDivider()
                            InfinityMenuItem(
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                text = { Text(deleteDescription) },
                                destructive = true,
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                                modifier = Modifier
                                    .testTag("${UiTags.settingsProviderActions(provider.id)}:delete")
                                    .semantics { role = Role.Button },
                            )
                        }
                    }
                }
            }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                providerName,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold),
                color = if (provider.enabled) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onTailnet) {
                ProviderMicroBadge(
                    label = stringResource(R.string.provider_list_tailnet_tag),
                    containerColor = scheme.secondaryContainer,
                    contentColor = scheme.onSecondaryContainer,
                    icon = Icons.Rounded.Lan,
                )
            } else if (provider.useBuiltInProxy) {
                ProviderMicroBadge(
                    label = stringResource(R.string.provider_list_proxy_tag),
                    containerColor = scheme.primaryContainer.copy(alpha = 0.7f),
                    contentColor = scheme.onPrimaryContainer,
                    icon = Icons.Rounded.Shield,
                )
            }
            if (!provider.enabled) {
                ProviderMicroBadge(
                    label = enabledStatus,
                    containerColor = scheme.surfaceContainerHighest,
                    contentColor = scheme.onSurfaceVariant,
                )
            } else if (missingCredential) {
                ProviderMicroBadge(
                    label = stringResource(R.string.provider_list_missing_key_tag),
                    containerColor = domain.warningContainer,
                    contentColor = domain.warning,
                    icon = Icons.Rounded.WarningAmber,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = protocolShort,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(scheme.surfaceContainerHighest.copy(alpha = 0.75f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
            if (host.isNotEmpty()) {
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 提供商行骨架：42dp 品牌底托、精炼两行正文、尾部模型数胶囊与动作菜单。 */
@Composable
private fun ProviderRowFrame(
    modifier: Modifier,
    onClick: () -> Unit,
    onClickLabel: String,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .then(modifier)
            .padding(start = 14.dp, end = 4.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { content() }
        Spacer(Modifier.width(6.dp))
        trailing()
    }
}

@Composable
private fun ProviderMicroBadge(
    label: String,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(11.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
            color = contentColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ProviderStateChip(text: String, warning: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (warning) domain.onWarningContainer else scheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .background(if (warning) domain.warningContainer else scheme.surfaceContainerHighest, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 提供商头像：42dp 圆角方块，按名称与主机精准解析品牌（不被 URL 路径 `/anthropic` 误判），未命中时显示网关/内网矢量图标。 */
@Composable
private fun ProviderAvatar(
    name: String,
    baseUrl: String?,
    onTailnet: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val brand = remember(name, baseUrl) { ModelBrandResolver.brandKey(name, baseUrl) }
    val accent = brandAccentColor(brand)
    val bg = accent?.copy(alpha = 0.12f) ?: scheme.surfaceContainerHighest.copy(alpha = 0.85f)
    val rim = accent?.copy(alpha = 0.20f) ?: scheme.outlineVariant.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .size(ProviderAvatarSize)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(0.5.dp, rim, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (brand != null) {
            ModelBrandIcon(
                modelId = name.ifBlank { baseUrl },
                modifier = Modifier.size(22.dp),
                contentDescription = null,
                fallbackTint = accent ?: scheme.onSurfaceVariant,
                brandOverride = brand,
            )
        } else {
            Icon(
                imageVector = if (onTailnet) Icons.Rounded.Lan else Icons.Rounded.Hub,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun providerCredentialMissing(provider: ProviderListItem): Boolean = when (provider.protocolType) {
    ProtocolType.Google_VertexAI -> !provider.hasVertexCredentials
    ProtocolType.Local -> false
    else -> !provider.hasApiKey
}

@Composable
private fun providerCredentialStatus(provider: ProviderListItem): String = when (provider.protocolType) {
    ProtocolType.Google_VertexAI -> stringResource(
        if (provider.hasVertexCredentials) R.string.settings_provider_vertex_credentials_configured
        else R.string.settings_provider_vertex_credentials_not_configured,
    )
    ProtocolType.Local -> stringResource(R.string.settings_provider_credentials_not_required)
    else -> stringResource(
        if (provider.hasApiKey) R.string.settings_provider_api_key_configured
        else R.string.settings_provider_api_key_not_configured,
    )
}
