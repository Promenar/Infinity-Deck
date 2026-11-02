package com.promenar.infinity.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.avatar.AvatarSymbol
import com.promenar.infinity.data.avatar.AvatarSymbolCatalog
import com.promenar.infinity.data.avatar.AvatarSymbolCategory
import com.promenar.infinity.domain.avatar.AgentAvatarCodec
import com.promenar.infinity.domain.avatar.AgentAvatarPalette
import com.promenar.infinity.domain.avatar.AgentAvatarSpec
import com.promenar.infinity.ui.common.AgentAvatar
import com.promenar.infinity.ui.common.AgentAvatarArt
import com.promenar.infinity.ui.common.AvatarSymbolGlyph
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityChip
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinitySearchBar
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityTabSwitcher
import com.promenar.infinity.ui.common.parseAgentColor
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTypography

/** 选择器的三个页签。 */
enum class AvatarPickerTab { SYMBOLS, GENERATED, PHOTO }

/** 符号页的分类筛选：全部、常用或某一类；序列化成字符串以便跨配置变更保存。 */
sealed interface AvatarSymbolFilter {
    data object All : AvatarSymbolFilter
    data object Popular : AvatarSymbolFilter
    data class Category(val category: AvatarSymbolCategory) : AvatarSymbolFilter

    companion object {
        val chips: List<AvatarSymbolFilter> =
            listOf(All, Popular) + AvatarSymbolCategory.entries.map { Category(it) }

        fun key(filter: AvatarSymbolFilter): String = when (filter) {
            All -> "all"
            Popular -> "popular"
            is Category -> filter.category.name
        }

        fun fromKey(key: String): AvatarSymbolFilter = when (key) {
            "all" -> All
            "popular" -> Popular
            else -> AvatarSymbolCategory.entries.firstOrNull { it.name == key }?.let { Category(it) } ?: Popular
        }
    }
}

/** 有搜索词时按目录搜索，否则按分类筛选。 */
internal fun avatarPickerSymbols(query: String, filter: AvatarSymbolFilter): List<AvatarSymbol> =
    if (query.isNotBlank()) {
        AvatarSymbolCatalog.search(query)
    } else when (filter) {
        AvatarSymbolFilter.All -> AvatarSymbolCatalog.symbols
        AvatarSymbolFilter.Popular -> AvatarSymbolCatalog.popular
        is AvatarSymbolFilter.Category -> AvatarSymbolCatalog.inCategory(filter.category)
    }

/** 打开选择器时的分类：当前符号在“常用”里就停在常用，否则直接定位到它所在的类。 */
internal fun initialAvatarFilter(spec: AgentAvatarSpec): AvatarSymbolFilter {
    val name = (spec as? AgentAvatarSpec.Symbol)?.name ?: return AvatarSymbolFilter.Popular
    if (AvatarSymbolCatalog.popular.any { it.name == name }) return AvatarSymbolFilter.Popular
    return AvatarSymbolCatalog.find(name)?.let { AvatarSymbolFilter.Category(it.category) } ?: AvatarSymbolFilter.Popular
}

internal const val GENERATED_BATCH_SIZE = 18

/** 每批生成头像的种子。第 0 批第 0 个是空种子，即按智能体 ID 生成的默认样子。 */
internal fun generatedAvatarSeeds(batch: Int): List<String> =
    List(GENERATED_BATCH_SIZE) { index -> if (batch == 0 && index == 0) "" else "$batch.$index" }

/** 颜色选项：固定色板；当前颜色不在色板里（旧版自定义色）时排在最前，保留原值可选。 */
internal data class AvatarColorOption(val hex: String, val key: String?)

internal fun avatarColorOptions(currentHex: String): List<AvatarColorOption> {
    val palette = AgentAvatarPalette.swatches.map { AvatarColorOption(it.hex, it.key) }
    val current = currentHex.trim().uppercase()
    val inPalette = palette.any { it.hex.equals(current, ignoreCase = true) }
    return if (inPalette || parseAgentColor(current) == null) palette else listOf(AvatarColorOption(current, null)) + palette
}

@Composable
private fun AvatarSymbol.label(): String =
    if (LocalConfiguration.current.locales[0].language == "zh") labelZh else labelEn

private fun AvatarSymbolFilter.labelRes(): Int = when (this) {
    AvatarSymbolFilter.All -> R.string.agent_avatar_category_all
    AvatarSymbolFilter.Popular -> R.string.agent_avatar_category_popular
    is AvatarSymbolFilter.Category -> when (category) {
        AvatarSymbolCategory.GENERAL -> R.string.agent_avatar_category_general
        AvatarSymbolCategory.CODING -> R.string.agent_avatar_category_coding
        AvatarSymbolCategory.WRITING -> R.string.agent_avatar_category_writing
        AvatarSymbolCategory.LEARNING -> R.string.agent_avatar_category_learning
        AvatarSymbolCategory.WORK -> R.string.agent_avatar_category_work
        AvatarSymbolCategory.CREATIVE -> R.string.agent_avatar_category_creative
        AvatarSymbolCategory.LIFE -> R.string.agent_avatar_category_life
        AvatarSymbolCategory.NATURE -> R.string.agent_avatar_category_nature
        AvatarSymbolCategory.CHARACTERS -> R.string.agent_avatar_category_characters
    }
}

private fun colorLabelRes(key: String?): Int = when (key) {
    "indigo" -> R.string.agent_avatar_color_indigo
    "blue" -> R.string.agent_avatar_color_blue
    "cyan" -> R.string.agent_avatar_color_cyan
    "emerald" -> R.string.agent_avatar_color_emerald
    "lime" -> R.string.agent_avatar_color_lime
    "yellow" -> R.string.agent_avatar_color_yellow
    "amber" -> R.string.agent_avatar_color_amber
    "orange" -> R.string.agent_avatar_color_orange
    "rose" -> R.string.agent_avatar_color_rose
    "pink" -> R.string.agent_avatar_color_pink
    "fuchsia" -> R.string.agent_avatar_color_fuchsia
    "violet" -> R.string.agent_avatar_color_violet
    "slate" -> R.string.agent_avatar_color_slate
    else -> R.string.agent_avatar_color_current
}

/** 设置行与选择器头部共用的头像摘要。 */
@Composable
internal fun agentAvatarSummary(spec: AgentAvatarSpec, hasPhoto: Boolean): String = when {
    hasPhoto -> stringResource(R.string.agent_avatar_summary_photo)
    spec is AgentAvatarSpec.Generated -> stringResource(R.string.agent_avatar_summary_generated)
    else -> {
        val symbol = AvatarSymbolCatalog.find((spec as AgentAvatarSpec.Symbol).name)
        stringResource(R.string.agent_avatar_summary_symbol, symbol?.label() ?: spec.name)
    }
}

/**
 * 头像选择器：顶部实时预览，符号 / 生成 / 照片三个页签，底部选颜色。
 * 只有点“完成”才通过 [onConfirm] 写回；照片沿用圆形裁剪页，由 [onPickPhoto] 发起。
 */
@Composable
fun AgentAvatarPickerSheet(
    show: Boolean,
    agentId: String,
    agentName: String,
    initialIcon: String,
    initialColor: String,
    photoUri: String?,
    onDismiss: () -> Unit,
    onConfirm: (icon: String, color: String) -> Unit,
    onPickPhoto: () -> Unit,
) {
    InfinityBottomSheet(
        show = show,
        onDismiss = onDismiss,
        height = InfinitySheetHeight.Full,
        // 网格自带滚动，面板不跟随拖动，避免两者在边界处争抢位移。
        dragGesturesEnabled = false,
        modifier = Modifier.testTag(UiTags.AGENT_AVATAR_PICKER),
    ) {
        AgentAvatarPickerContent(
            agentId = agentId,
            agentName = agentName,
            initialIcon = initialIcon,
            initialColor = initialColor,
            photoUri = photoUri,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            onPickPhoto = onPickPhoto,
        )
    }
}

@Composable
internal fun AgentAvatarPickerContent(
    agentId: String,
    agentName: String,
    initialIcon: String,
    initialColor: String,
    photoUri: String?,
    onConfirm: (icon: String, color: String) -> Unit,
    onDismiss: () -> Unit,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialSpec = remember(initialIcon) { AgentAvatarCodec.decode(initialIcon) }
    var draftIcon by rememberSaveable(initialIcon) { mutableStateOf(AgentAvatarCodec.encode(initialSpec)) }
    var draftColor by rememberSaveable(initialColor) { mutableStateOf(initialColor) }
    // 用户在本次打开中点过符号、生成头像或颜色；没点过就按“完成”不会清掉照片。
    var chosen by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable {
        mutableStateOf(
            when {
                photoUri != null -> AvatarPickerTab.PHOTO
                initialSpec is AgentAvatarSpec.Generated -> AvatarPickerTab.GENERATED
                else -> AvatarPickerTab.SYMBOLS
            }
        )
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filterKey by rememberSaveable { mutableStateOf(AvatarSymbolFilter.key(initialAvatarFilter(initialSpec))) }
    var batch by rememberSaveable { mutableIntStateOf(0) }
    val draftSpec = remember(draftIcon) { AgentAvatarCodec.decode(draftIcon) }
    val showingPhoto = photoUri != null && !chosen

    Column(modifier.fillMaxSize()) {
        // 头部：实时预览 + 名称与摘要 + 完成
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentAvatar(
                spec = draftSpec,
                color = draftColor,
                seed = agentId,
                name = agentName,
                photoUri = if (showingPhoto) photoUri else null,
                size = 64.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = agentName.ifBlank { stringResource(R.string.agent_avatar_picker_title) },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = agentAvatarSummary(draftSpec, showingPhoto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = { if (chosen) onConfirm(draftIcon, draftColor) else onDismiss() },
                modifier = Modifier.testTag(UiTags.AGENT_AVATAR_PICKER_DONE),
            ) { Text(stringResource(R.string.agent_avatar_done)) }
        }

        val tabs = AvatarPickerTab.entries
        InfinityTabSwitcher(
            itemCount = tabs.size,
            selectedIndex = tab.ordinal,
            onTabSelected = { tab = tabs[it] },
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            tabModifier = { Modifier.testTag(UiTags.agentAvatarPickerTab(tabs[it].name)) },
        ) { index, isSelected, contentColor ->
            Text(
                text = stringResource(
                    when (tabs[index]) {
                        AvatarPickerTab.SYMBOLS -> R.string.agent_avatar_tab_symbols
                        AvatarPickerTab.GENERATED -> R.string.agent_avatar_tab_generated
                        AvatarPickerTab.PHOTO -> R.string.agent_avatar_tab_photo
                    }
                ),
                style = InfinityTypography.labelMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                AvatarPickerTab.SYMBOLS -> SymbolPane(
                    query = query,
                    onQueryChange = { query = it },
                    filter = AvatarSymbolFilter.fromKey(filterKey),
                    onFilterChange = { filterKey = AvatarSymbolFilter.key(it); query = "" },
                    selectedName = (draftSpec as? AgentAvatarSpec.Symbol)?.name?.takeIf { !showingPhoto },
                    onSelect = { draftIcon = AgentAvatarCodec.encode(AgentAvatarSpec.Symbol(it.name)); chosen = true },
                )
                AvatarPickerTab.GENERATED -> GeneratedPane(
                    agentId = agentId,
                    agentName = agentName,
                    color = draftColor,
                    batch = batch,
                    onShuffle = { batch++ },
                    selectedSeed = (draftSpec as? AgentAvatarSpec.Generated)?.seed?.takeIf { !showingPhoto },
                    onSelect = { draftIcon = AgentAvatarCodec.encode(AgentAvatarSpec.Generated(it)); chosen = true },
                )
                AvatarPickerTab.PHOTO -> PhotoPane(photoUri = photoUri, onPickPhoto = onPickPhoto)
            }
        }

        if (tab != AvatarPickerTab.PHOTO) {
            ColorPane(
                selectedHex = draftColor,
                initialHex = initialColor,
                onSelect = { draftColor = it; chosen = true },
            )
        }
    }
}

@Composable
private fun SymbolPane(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: AvatarSymbolFilter,
    onFilterChange: (AvatarSymbolFilter) -> Unit,
    selectedName: String?,
    onSelect: (AvatarSymbol) -> Unit,
) {
    val symbols = remember(query, filter) { avatarPickerSymbols(query, filter) }
    val gridState = rememberLazyGridState()
    LaunchedEffect(query, filter) { gridState.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        InfinitySearchBar(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.agent_avatar_search_placeholder),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag(UiTags.AGENT_AVATAR_PICKER_SEARCH),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(AvatarSymbolFilter.chips, key = { AvatarSymbolFilter.key(it) }) { chip ->
                InfinityChip(
                    label = stringResource(chip.labelRes()),
                    selected = query.isBlank() && chip == filter,
                    onClick = { onFilterChange(chip) },
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(52.dp),
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            if (symbols.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.agent_avatar_search_empty, query.trim()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
                    )
                }
            }
            items(symbols, key = { it.name }) { symbol ->
                val selected = symbol.name == selectedName
                val label = symbol.label()
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .heightIn(min = 48.dp)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(symbol) })
                        .semantics { contentDescription = label }
                        .testTag(UiTags.agentAvatarSymbol(symbol.name)),
                    contentAlignment = Alignment.Center,
                ) {
                    AvatarSymbolGlyph(
                        codepoint = symbol.codepoint,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                        scale = 0.56f,
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneratedPane(
    agentId: String,
    agentName: String,
    color: String,
    batch: Int,
    onShuffle: () -> Unit,
    selectedSeed: String?,
    onSelect: (String) -> Unit,
) {
    val seeds = remember(batch) { generatedAvatarSeeds(batch) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.agent_avatar_generated_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            InfinityPillButton(
                text = stringResource(R.string.agent_avatar_shuffle),
                onClick = onShuffle,
                icon = Icons.Rounded.Refresh,
                compact = true,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp).testTag(UiTags.AGENT_AVATAR_PICKER_SHUFFLE),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(64.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            items(seeds.size, key = { seeds[it].ifEmpty { "default" } }) { index ->
                val seed = seeds[index]
                val selected = seed == selectedSeed
                val description = stringResource(R.string.agent_avatar_generated_variant, index + 1)
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(seed) })
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    AgentAvatarArt(
                        spec = AgentAvatarSpec.Generated(seed),
                        color = color,
                        seed = agentId,
                        name = agentName,
                        modifier = Modifier.fillMaxSize().padding(if (selected) 6.dp else 0.dp).clip(CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoPane(photoUri: String?, onPickPhoto: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (photoUri != null) {
            AgentAvatar(icon = "", color = "", seed = "", name = "", photoUri = photoUri, size = 96.dp)
        }
        FilledTonalButton(
            onClick = onPickPhoto,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(UiTags.AGENT_AVATAR_PICKER_PHOTO),
        ) { Text(stringResource(R.string.agent_avatar_photo_pick)) }
        Text(
            text = stringResource(R.string.agent_avatar_photo_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ColorPane(selectedHex: String, initialHex: String, onSelect: (String) -> Unit) {
    val options = remember(initialHex) { avatarColorOptions(initialHex) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
        Text(
            text = stringResource(R.string.agent_avatar_color),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        options.chunked(COLOR_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { option ->
                    val selected = option.hex.equals(selectedHex.trim(), ignoreCase = true)
                    val label = stringResource(colorLabelRes(option.key))
                    val swatch = parseAgentColor(option.hex) ?: MaterialTheme.colorScheme.primary
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(option.hex) })
                            .semantics { contentDescription = label }
                            .testTag(UiTags.agentAvatarColor(option.key ?: "current")),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape).padding(4.dp) else Modifier)
                                .clip(CircleShape)
                                .background(swatch),
                        )
                    }
                }
                repeat(COLOR_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val COLOR_COLUMNS = 7
