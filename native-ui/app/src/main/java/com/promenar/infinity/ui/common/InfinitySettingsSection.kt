package com.promenar.infinity.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.keepCjkTailTogether

/** 去框化商业级排印脚注：取代“盒中套盒”的描边说明框，用于设置分组底部轻量提示。 */
@Composable
fun InfinityTypographicFootnote(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    Text(
        text = text.keepCjkTailTogether(),
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** 含可点击链接的去框化排印脚注。 */
@Composable
fun InfinityTypographicFootnote(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
) {
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** Bettbox 风格设置分组：弱化标题，设置行收进统一圆角色面。 */
@Composable
fun InfinitySettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    headerStartPadding: Dp = 0.dp,
    trailingHeader: String? = null,
    footer: String? = null,
    footerModifier: Modifier = Modifier,
    /** 含可点链接的页脚；给出时代替 [footer]。 */
    linkedFooter: AnnotatedString? = null,
    /** 为 true 时使用去框化 12sp 商业级排印脚注，避免“盒中套盒”描边框。 */
    typographicFooter: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val groupHorizontalPadding = headerStartPadding
    Column(modifier = modifier.fillMaxWidth()) {
        if (trailingHeader != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = groupHorizontalPadding + InfinitySpacing.RowHorizontal,
                        end = groupHorizontalPadding + InfinitySpacing.RowHorizontal,
                        bottom = InfinitySpacing.SectionHeaderBottom,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsSectionHeader(
                    title = title,
                    titleStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = trailingHeader,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        } else {
            SettingsSectionHeader(
                title = title,
                titleStyle = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(
                    start = groupHorizontalPadding + InfinitySpacing.RowHorizontal,
                    end = groupHorizontalPadding + InfinitySpacing.RowHorizontal,
                    bottom = InfinitySpacing.SectionHeaderBottom,
                ),
            )
        }
        InfinitySettingsGroup(
            footer = footer,
            footerModifier = footerModifier,
            linkedFooter = linkedFooter,
            typographicFooter = typographicFooter,
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
    /** 为 true 时使用去框化 12sp 商业级排印脚注。 */
    typographicFooter: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(bottom = InfinitySpacing.SectionGap)) {
        BettboxListGroup(modifier = Modifier.fillMaxWidth(), content = content)
        if (linkedFooter != null) {
            if (typographicFooter) {
                InfinityTypographicFootnote(
                    text = linkedFooter,
                    modifier = footerModifier.padding(top = 2.dp),
                )
            } else {
                InfinitySectionFooterCard(
                    text = linkedFooter,
                    modifier = Modifier.padding(top = InfinitySpacing.FooterTop),
                    textModifier = footerModifier,
                )
            }
        } else if (!footer.isNullOrBlank()) {
            if (typographicFooter) {
                InfinityTypographicFootnote(
                    text = footer,
                    modifier = footerModifier.padding(top = 2.dp),
                )
            } else {
                InfinitySectionFooterCard(
                    text = footer,
                    modifier = Modifier.padding(top = InfinitySpacing.FooterTop),
                    textModifier = footerModifier,
                )
            }
        }
    }
}
