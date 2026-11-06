package com.promenar.infinity.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Psychology
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.local.ProviderStatus
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.UnsupportedProviderListItem
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.ui.common.label
import com.promenar.infinity.ui.common.providerDisplayName
import com.promenar.infinity.data.tailnet.TailnetEndpoints
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.ModelBrandIcon
import com.promenar.infinity.ui.common.ModelBrandResolver
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityMenuDivider
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.common.InfinityMetaLine

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
    val tailnetProviderIds = remember(providers, tailnetState) {
        providers.filter { TailnetEndpoints.isTailnetEndpoint(it.baseUrl, tailnetState) }.mapTo(mutableSetOf()) { it.id }
    }
    val state = remember(providers, unsupportedProviders, modelCounts, statuses, tailnetProviderIds) {
        ProviderListScreenState(
            providers = providers,
            unsupportedProviders = unsupportedProviders,
            modelCounts = modelCounts,
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
        actions = actions
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
    actions: ProviderListScreenActions
) {
    InfinitySettingsPageLayout(
        title = stringResource(R.string.settings_provider_management),
        onBack = actions.onNavigateBack,
        horizontalContentPadding = 0.dp,
        modifier = Modifier.testTag(UiTags.SETTINGS_PROVIDER_LIST),
        actions = {
            IconButton(
                onClick = actions.onAddProvider,
                modifier = Modifier
                    .sizeIn(
                        minWidth = InfinitySpacing.MinimumTouchTarget,
                        minHeight = InfinitySpacing.MinimumTouchTarget,
                    )
                    .testTag(UiTags.SETTINGS_ADD_PROVIDER),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.settings_add_provider),
                )
            }
        }
    ) { contentPadding ->
        val compactHeightEmptyState = LocalConfiguration.current.screenHeightDp < 480
        val now = rememberMinuteClock()

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
                    Text(
                        text = pluralStringResource(R.plurals.provider_list_summary, summary.total, summary.total, summary.enabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 32.dp)
                            .padding(top = 4.dp, bottom = InfinitySpacing.SectionHeaderBottom)
                            .testTag(UiTags.PROVIDER_LIST_SUMMARY),
                    )
                }
                item("provider-group") {
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
                                    status = state.statuses[provider.id],
                                    onTailnet = provider.id in state.tailnetProviderIds,
                                    nowMillis = now,
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
            }
        }
    }
}

private val ProviderAvatarSize = 40.dp
private val ProviderDividerInset = InfinitySpacing.RowHorizontal + ProviderAvatarSize + 12.dp

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
 * 提供商行：名称与状态标签 / 协议与主机名 / 内网、凭证与模型数 / 最近一次连接测试。
 * 点按进入模型列表，⋮ 菜单为编辑、管理模型、删除。
 */
@Composable
private fun ProviderCard(
    provider: ProviderListItem,
    counts: ProviderModelCount,
    status: ProviderStatus?,
    onTailnet: Boolean,
    nowMillis: Long,
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
    val protocolLabel = provider.protocolType.label()
    val host = remember(provider.baseUrl) { providerHostLabel(provider.baseUrl) }
    val missingCredential = providerCredentialMissing(provider)
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val secondary = scheme.onSurfaceVariant
    val separator = " · "  // wrap-check: allow 凭据状态需单独着色的富文本行
    val metaLine = buildAnnotatedString {
        if (onTailnet) {
            append(stringResource(R.string.provider_list_tailnet_tag))
            append(separator)
        }
        withStyle(SpanStyle(color = if (missingCredential) domain.warning else secondary)) { append(credentialStatus) }
        append(separator)
        append(pluralStringResource(R.plurals.provider_list_model_count, counts.total, counts.enabled, counts.total))
    }

    ProviderRowFrame(
        modifier = Modifier
            .testTag(UiTags.settingsProviderCard(provider.id))
            .semantics { stateDescription = providerStateDescription },
        onClick = onClick,
        onClickLabel = manageDescription,
        leading = {
            Box(Modifier.alpha(if (provider.enabled) 1f else 0.55f)) { ProviderAvatar(providerName, provider.baseUrl) }
        },
        trailing = {
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
                        tint = scheme.onSurfaceVariant,
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
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                providerName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (provider.enabled) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!provider.enabled) {
                Spacer(Modifier.width(6.dp))
                ProviderStateChip(enabledStatus, warning = false)
            }
        }
        Text(
            text = buildAnnotatedString {
                append(protocolLabel)
                if (host.isNotEmpty()) {
                    append(separator)
                    withStyle(SpanStyle(fontFamily = InfinityMonospace)) { append(host) }
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(metaLine, style = MaterialTheme.typography.bodySmall, color = secondary)
        ProviderConnectionLine(provider.id, status, nowMillis)
    }
}

/** 最近一次连接测试：彩色圆点 + “连接正常 · 2 小时前”；从未测试时给出提示。 */
@Composable
private fun ProviderConnectionLine(providerId: String, status: ProviderStatus?, nowMillis: Long) {
    val record = status?.lastConnection
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val dotColor: Color = when (record?.statusKind()) {
        InfinityStatusKind.Success -> domain.success
        InfinityStatusKind.Error -> scheme.error
        InfinityStatusKind.Warning -> domain.warning
        InfinityStatusKind.Info -> domain.info
        null -> scheme.outline
    }
    val segments = if (record == null) {
        listOf(stringResource(R.string.provider_connection_never_tested))
    } else {
        listOf(record.outcomeLabel(), relativeTimeLabel(record.testedAtMillis, nowMillis))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp).testTag(UiTags.providerConnectionStatus(providerId)),
    ) {
        Box(Modifier.size(6.dp).background(dotColor, CircleShape))
        Spacer(Modifier.width(6.dp))
        InfinityMetaLine(segments, style = MaterialTheme.typography.bodySmall, color = if (record == null) scheme.outline else scheme.onSurfaceVariant)
    }
}

/** 提供商行骨架：40dp 头像、三到四行正文、尾部 48dp 动作。 */
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
            .padding(start = InfinitySpacing.RowHorizontal, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
        trailing()
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

/** 提供商头像：40dp 圆角方块，能识别品牌时显示品牌标志，否则显示名称首字。 */
@Composable
private fun ProviderAvatar(name: String, baseUrl: String?) {
    val brand = remember(name, baseUrl) { ModelBrandResolver.brandKey(name, baseUrl) }
    Box(
        modifier = Modifier
            .size(ProviderAvatarSize)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (brand != null) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.secondaryContainer,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (brand != null) {
            ModelBrandIcon(modelId = brand, modifier = Modifier.size(22.dp), contentDescription = null)
        } else {
            Text(
                text = name.trim().take(1).uppercase().ifEmpty { "?" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
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
