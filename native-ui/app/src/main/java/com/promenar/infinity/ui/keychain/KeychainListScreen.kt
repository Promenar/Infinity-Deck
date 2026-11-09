package com.promenar.infinity.ui.keychain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import com.promenar.infinity.R
import com.promenar.infinity.domain.keychain.KeychainCategory
import com.promenar.infinity.domain.keychain.KeychainItem
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityPageSearch
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.settings.GitCredentialSettingsViewModel
import com.promenar.infinity.ui.settings.openGitHubVerification
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors

internal data class KeychainListState(
    val items: List<KeychainItem>?,
    val filter: KeychainCategory? = null,
    val query: String = "",
    val searchActive: Boolean = false,
    val githubLoginAvailable: Boolean = false,
    /** 远程面板的 Git 分段：只看这一类，不显示类别筛选，新建直接添加 Git 凭据。 */
    val filterLocked: Boolean = false,
)

internal data class KeychainListActions(
    val onBack: () -> Unit = {},
    val onFilterChange: (KeychainCategory?) -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSearchActiveChange: (Boolean) -> Unit = {},
    val onOpen: (KeychainItem) -> Unit = {},
    val onNewEntry: () -> Unit = {},
    val onAddGit: () -> Unit = {},
    val onGenerateSshKey: () -> Unit = {},
    val onImportSshKey: () -> Unit = {},
    val onGitHubLogin: () -> Unit = {},
)

@Composable
fun KeychainScreen(
    initialFilter: KeychainCategory?,
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onNewEntry: () -> Unit,
    lockedFilter: KeychainCategory? = null,
) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val viewModel: KeychainViewModel = viewModel(factory = KeychainViewModel.factory(application))
    val gitViewModel: GitCredentialSettingsViewModel = viewModel(factory = GitCredentialSettingsViewModel.factory(application))
    val items by viewModel.items.collectAsState()
    val git by gitViewModel.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf(lockedFilter ?: initialFilter) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var gitEditor by remember { mutableStateOf<KeychainGitEditor?>(null) }
    var sshSheet by remember { mutableStateOf<SshKeyImportSource?>(null) }
    var generating by remember { mutableStateOf(false) }
    val generatedMessage = stringResource(R.string.keychain_ssh_generated)
    val importedMessage = stringResource(R.string.keychain_ssh_imported)
    val importedClipboardMessage = stringResource(R.string.keychain_ssh_imported_clipboard)
    KeychainListContent(
        state = KeychainListState(items, filter, query, searchActive, git.githubLoginAvailable, filterLocked = lockedFilter != null),
        actions = KeychainListActions(
            onBack = onBack,
            onFilterChange = { filter = it },
            onQueryChange = { query = it },
            onSearchActiveChange = { active -> searchActive = active; if (!active) query = "" },
            onOpen = { onOpenEntry(it.ref) },
            onNewEntry = onNewEntry,
            onAddGit = { gitEditor = KeychainGitEditor.Add },
            onGenerateSshKey = { generating = true },
            onImportSshKey = { sshSheet = SshKeyImportSource.FILE },
            onGitHubLogin = gitViewModel::startGitHubLogin,
        ),
    )
    if (generating) {
        SshKeyGenerateSheet(onDismiss = { generating = false }) { item ->
            generating = false
            android.widget.Toast.makeText(context, generatedMessage, android.widget.Toast.LENGTH_SHORT).show()
            onOpenEntry(item.ref)
        }
    }
    if (sshSheet != null) {
        SshKeyImportSheet(onDismiss = { sshSheet = null }) { item, fromClipboard ->
            sshSheet = null
            android.widget.Toast.makeText(context, if (fromClipboard) importedClipboardMessage else importedMessage, android.widget.Toast.LENGTH_SHORT).show()
            onOpenEntry(item.ref)
        }
    }
    KeychainGitSheets(
        git = git,
        editor = gitEditor,
        onDismissEditor = { gitEditor = null },
        onSave = gitViewModel::save,
        onStartGitHubLogin = gitViewModel::startGitHubLogin,
        onCloseGitHubLogin = gitViewModel::closeGitHubLogin,
        onOpenGitHubVerification = { uri, code -> openGitHubVerification(context, uri, code) },
    )
}

/** 钥匙串列表：类别筛选、搜索、按类别分组；右下角新建自定义条目或 Git 凭据。 */
@Composable
internal fun KeychainListContent(state: KeychainListState, actions: KeychainListActions, modifier: Modifier = Modifier) {
    InfinitySettingsPageLayout(
        title = state.filter?.takeIf { state.filterLocked }?.let { keychainCategoryName(it) } ?: stringResource(R.string.keychain_title),
        onBack = actions.onBack,
        modifier = modifier,
        search = InfinityPageSearch(
            query = state.query,
            active = state.searchActive,
            onQueryChange = actions.onQueryChange,
            onActiveChange = actions.onSearchActiveChange,
            placeholder = stringResource(R.string.keychain_search_placeholder),
            fieldTag = "keychain_search",
        ),
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            KeychainList(state, actions, padding)
            KeychainFab(
                lockedTo = state.filter.takeIf { state.filterLocked },
                actions = actions,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = InfinitySpacing.ScreenHorizontal, bottom = InfinitySpacing.Large),
            )
        }
    }
}

@Composable
private fun KeychainList(state: KeychainListState, actions: KeychainListActions, padding: PaddingValues) {
    val items = state.items
    val groups = remember(items, state.filter, state.query) { groupKeychainItems(items.orEmpty(), state.filter, state.query) }
    val counts = remember(items) { keychainCounts(items.orEmpty()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("keychain_list"),
        contentPadding = padding,
    ) {
        if (!state.filterLocked) {
            item(key = "filters") {
                KeychainFilterRow(state.filter, counts, items?.size ?: 0, actions.onFilterChange)
                Spacer(Modifier.height(InfinitySpacing.Large))
            }
        }
        if (state.filter == KeychainCategory.GIT && state.githubLoginAvailable) {
            item(key = "github") {
                InfinitySettingsGroup {
                    InfinitySettingsItem(
                        icon = Icons.AutoMirrored.Rounded.Login,
                        title = stringResource(R.string.keychain_github_login),
                        onClick = actions.onGitHubLogin,
                        showChevron = true,
                        modifier = Modifier.testTag("keychain_github_login"),
                    )
                }
            }
        }
        if (items != null && groups.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(if (state.query.isBlank()) R.string.keychain_settings_entry_count_zero else R.string.keychain_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.XLarge),
                )
            }
        }
        groups.forEach { group ->
            item(key = "group-${group.category.name}") {
                InfinitySettingsSection(title = keychainCategoryName(group.category)) {
                    group.items.forEach { item -> KeychainRow(item, onClick = { actions.onOpen(item) }) }
                }
            }
        }
        item(key = "footer") {
            Column(
                Modifier.padding(horizontal = InfinitySpacing.RowHorizontal),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            ) {
                if (state.filter == KeychainCategory.GIT) {
                    KeychainFootnote(stringResource(R.string.keychain_git_footer_ssh))
                }
                KeychainFootnote(stringResource(R.string.keychain_footer_backup))
                // 给右下角“新建”按钮留出位置，最后一行不被遮住。
                Spacer(Modifier.height(72.dp))
            }
        }
    }
}

@Composable
private fun KeychainFootnote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun KeychainFilterRow(
    selected: KeychainCategory?,
    counts: Map<KeychainCategory, Int>,
    total: Int,
    onSelect: (KeychainCategory?) -> Unit,
) {
    val categories = KeychainCategoryOrder.filter { (counts[it] ?: 0) > 0 || it == selected }
    // 从其它页面带着筛选进来时，选中的类别可能在屏幕右侧之外：首次显示就让它可见。
    val initialIndex = selected?.let { categories.indexOf(it) + 1 }?.coerceAtLeast(0) ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (initialIndex - 1).coerceAtLeast(0))
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().testTag("keychain_filters"),
        contentPadding = PaddingValues(horizontal = InfinitySpacing.RowHorizontal),
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        item(key = "all") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.keychain_filter_chip, stringResource(R.string.keychain_filter_all), total), maxLines = 1) },
                modifier = Modifier.testTag("keychain_filter_all"),
            )
        }
        items(categories, key = { it.name }) { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(category) },
                label = { Text(stringResource(R.string.keychain_filter_chip, keychainCategoryShortName(category), counts[category] ?: 0), maxLines = 1) },
                modifier = Modifier.testTag("keychain_filter_${category.name.lowercase()}"),
            )
        }
    }
}

@Composable
internal fun KeychainRow(item: KeychainItem, onClick: () -> Unit) {
    val segments = keychainRowSegments(item)
    InfinitySettingsItem(
        icon = keychainCategoryIcon(item.category),
        title = item.name,
        subtitleSegments = segments.takeIf { it.isNotEmpty() },
        onClick = onClick,
        showChevron = true,
        leadingContent = if (item.gitRejected) {
            {
                Icon(
                    keychainCategoryIcon(item.category),
                    contentDescription = null,
                    tint = MaterialTheme.infinityDomainColors.warning,
                    modifier = Modifier.size(InfinitySpacing.RowIconSize),
                )
            }
        } else null,
        modifier = Modifier.testTag("keychain_item_${item.ref}"),
    )
}

@Composable
private fun KeychainFab(lockedTo: KeychainCategory?, actions: KeychainListActions, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val gitOnly = lockedTo == KeychainCategory.GIT
    val sshOnly = lockedTo == KeychainCategory.SSH_KEY
    Box(modifier) {
        ExtendedFloatingActionButton(
            onClick = { if (gitOnly) actions.onAddGit() else open = true },
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text(stringResource(if (gitOnly) R.string.keychain_add_git else R.string.keychain_new), maxLines = 1) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.testTag("keychain_fab"),
        )
        InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (!sshOnly) {
                InfinityMenuItem(
                    text = { Text(stringResource(R.string.keychain_new_entry)) },
                    leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                    onClick = { open = false; actions.onNewEntry() },
                    modifier = Modifier.testTag("keychain_fab_new_entry"),
                )
                InfinityMenuItem(
                    text = { Text(stringResource(R.string.keychain_add_git)) },
                    leadingIcon = { Icon(Icons.Rounded.Source, contentDescription = null) },
                    onClick = { open = false; actions.onAddGit() },
                    modifier = Modifier.testTag("keychain_fab_add_git"),
                )
            }
            InfinityMenuItem(
                text = { Text(stringResource(R.string.keychain_ssh_generate_menu)) },
                leadingIcon = { Icon(Icons.Rounded.VpnKey, contentDescription = null) },
                onClick = { open = false; actions.onGenerateSshKey() },
                modifier = Modifier.testTag("keychain_fab_generate_ssh"),
            )
            InfinityMenuItem(
                text = { Text(stringResource(R.string.keychain_ssh_import_menu)) },
                leadingIcon = { Icon(Icons.Rounded.UploadFile, contentDescription = null) },
                onClick = { open = false; actions.onImportSshKey() },
                modifier = Modifier.testTag("keychain_fab_import_ssh"),
            )
        }
    }
}

/** 设置首页“钥匙串”行的副标题用：条目总数。 */
@Composable
internal fun rememberKeychainCount(): Int? {
    val context = LocalContext.current
    val application = context.applicationContext as com.promenar.infinity.InfinityApplication
    val items by remember(application) {
        application.keychainRepository.entries().map<List<KeychainItem>, List<KeychainItem>?> { it }.catch { emit(null) }
    }.collectAsState(initial = null)
    return items?.size
}
