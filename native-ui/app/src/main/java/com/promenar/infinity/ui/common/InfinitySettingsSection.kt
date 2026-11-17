package com.promenar.infinity.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinitySpacing

/** Bettbox 风格设置分组：弱化标题，设置行收进统一圆角色面。 */
@Composable
fun InfinitySettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    headerStartPadding: Dp = 0.dp,
    footer: String? = null,
    footerModifier: Modifier = Modifier,
    /** 含可点链接的页脚；给出时代替 [footer]。 */
    linkedFooter: AnnotatedString? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val groupHorizontalPadding = headerStartPadding
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = title,
            titleStyle = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(
                start = groupHorizontalPadding + InfinitySpacing.RowHorizontal,
                end = groupHorizontalPadding + InfinitySpacing.RowHorizontal,
                bottom = InfinitySpacing.SectionHeaderBottom,
            ),
        )
        InfinitySettingsGroup(
            footer = footer,
            footerModifier = footerModifier,
            linkedFooter = linkedFooter,
            modifier = Modifier.padding(horizontal = groupHorizontalPadding),
            content = content,
        )
    }
}

/** 不带重复标题的设置分组，列表中的说明收进统一说明容器并紧邻所属项目。 */
@Composable
fun InfinitySettingsGroup(
    modifier: Modifier = Modifier,
    footer: String? = null,
    footerModifier: Modifier = Modifier,
    /** 含可点链接的页脚；给出时代替 [footer]，不放进可选择文本，避免长按选择抢走点按。 */
    linkedFooter: AnnotatedString? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(bottom = InfinitySpacing.SectionGap)) {
        BettboxListGroup(modifier = Modifier.fillMaxWidth(), content = content)
        if (linkedFooter != null) {
            InfinitySectionFooterCard(
                text = linkedFooter,
                modifier = Modifier.padding(top = InfinitySpacing.FooterTop),
                textModifier = footerModifier,
            )
        } else if (!footer.isNullOrBlank()) {
            InfinitySectionFooterCard(
                text = footer,
                modifier = Modifier.padding(top = InfinitySpacing.FooterTop),
                textModifier = footerModifier,
            )
        }
    }
}
