package com.promenar.infinity.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 穿透式目录导航栈中的一级；根目录不入栈。 */
data class FolderCrumb(
    val id: String,
    val name: String,
) : java.io.Serializable

/**
 * 面板与浏览器内的二级页头：左侧唯一的返回按钮，右侧为当前层级标题，标题下可放路径或说明，末尾放页面操作。
 * 标题按 [depth] 的增减做纵向切换，返回按钮出现与消失时横向展开收起。
 */
@Composable
fun InfinityNavigationHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backContentDescription: String = stringResource(R.string.common_cd_back),
    backButtonTag: String? = null,
    depth: Int = 0,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    supporting: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = InfinitySpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = onBack != null,
            enter = expandHorizontally(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)) +
                fadeIn(tween(InfinityMotion.DurationMedium)),
            exit = shrinkHorizontally(tween(InfinityMotion.DurationShort, easing = InfinityMotion.EmphasizedAccelerate)) +
                fadeOut(tween(InfinityMotion.DurationFadeOut)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    // 退出动画期间 onBack 已为空，此时点击不做任何事。
                    onClick = { onBack?.invoke() },
                    modifier = Modifier
                        .size(40.dp)
                        .then(if (backButtonTag != null) Modifier.testTag(backButtonTag) else Modifier),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = backContentDescription,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(InfinitySpacing.Medium))
            }
        }
        Column(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = title to depth,
                transitionSpec = { InfinityMotion.sharedAxisY(forward = targetState.second >= initialState.second) },
                label = "navigation_header_title",
            ) { (currentTitle, _) ->
                InfinityHeaderTitle(
                    text = currentTitle,
                    style = titleStyle,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            supporting?.invoke()
        }
        actions()
    }
}

/**
 * 目录浏览页头：一个返回按钮回到上一级（根目录时由 [onExitRoot] 回到外层列表，未提供则不显示），
 * 标题为当前目录名，下方是可点击的上级路径。知识库、会话工作区与外部文件夹共用。
 */
@Composable
fun FolderBreadcrumbsBar(
    folderStack: List<FolderCrumb>,
    rootTitle: String,
    onNavigateBack: () -> Unit,
    onNavigateToCrumb: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backButtonTag: String = "rag_folder_back_btn",
    onExitRoot: (() -> Unit)? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    /** 根目录名已在别处醒目显示时设为 false：根部只留操作按钮，[rootTitle] 仍用作上级路径首段。 */
    showRootTitle: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val atRoot = folderStack.isEmpty()
    val allCrumbs = remember(folderStack, rootTitle) {
        listOf(FolderCrumb(id = "", name = rootTitle)) + folderStack
    }
    InfinityNavigationHeader(
        title = if (atRoot && !showRootTitle) "" else allCrumbs.last().name,
        modifier = modifier,
        onBack = if (atRoot) onExitRoot else onNavigateBack,
        backContentDescription = stringResource(if (atRoot) R.string.common_cd_back else R.string.rag_back_to_parent),
        backButtonTag = backButtonTag,
        depth = folderStack.size,
        titleStyle = titleStyle,
        supporting = if (atRoot) null else ({ FolderAncestorTrail(allCrumbs.dropLast(1), onNavigateToCrumb) }),
        actions = actions,
    )
}

/** 上级路径：逐级可点，始终单行；过长时停在最近的上级，开头一侧渐隐，可横向拖动查看完整路径。 */
@Composable
private fun FolderAncestorTrail(ancestors: List<FolderCrumb>, onNavigateToCrumb: (Int) -> Unit) {
    InfinityScrollingLine(
        modifier = Modifier
            .fillMaxWidth()
            // 路径段自带的内边距向左抵消，使首段文字与标题对齐。
            .offset(x = -InfinitySpacing.XSmall),
        alignEnd = true,
    ) {
        ancestors.forEachIndexed { index, crumb ->
            if (index > 0) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                text = crumb.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button) { onNavigateToCrumb(index) }
                    .padding(horizontal = InfinitySpacing.XSmall, vertical = 6.dp),
            )
        }
    }
}

/** 点击第 [index] 级面包屑（0 为根）后的导航栈。 */
fun List<FolderCrumb>.navigateToCrumb(index: Int): List<FolderCrumb> =
    if (index <= 0) emptyList() else take(index)
