package com.promenar.infinity.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.LocalInfinityPressCorner

/**
 * Bettbox `CommonCard` filled 变体的 Compose 对应实现。
 *
 * 页面只在具有共同语义的一组连续列表外使用该表面；列表行本身保持透明，避免退化为
 * 每行一个卡片。分组只通过容器填色和留白建立层级，不叠加会被误认为项目边界的描边。
 */
@Composable
fun BettboxListGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ProvideGroupedPress { Column(content = content) }
    }
}

/**
 * 分组内的行按压反馈铺满整行、不带圆角，外轮廓交给分组自身的裁剪。
 * [BettboxListGroup] 已自带；用 [bettboxListGroup] 修饰符拼出的分组需在可点击行外包一层。
 */
@Composable
fun ProvideGroupedPress(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInfinityPressCorner provides 0.dp, content = content)
}

/** 用于必须保持 LazyColumn 惰性或滑动手势的连续列表容器。 */
@Composable
fun Modifier.bettboxListGroup(): Modifier {
    val shape = RoundedCornerShape(20.dp)
    return this
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
}

/**
 * 惰性列表里逐项拼成一个连续分组：首项保留上圆角，末项保留下圆角，中间项为直角。
 * 用于条目较多、需要 LazyColumn 复用的分组（如模型列表），避免退化为每行一张卡片。
 */
@Composable
fun Modifier.bettboxListGroupItem(index: Int, count: Int): Modifier {
    val radius = 20.dp
    val shape = RoundedCornerShape(
        topStart = if (index == 0) radius else 0.dp,
        topEnd = if (index == 0) radius else 0.dp,
        bottomStart = if (index == count - 1) radius else 0.dp,
        bottomEnd = if (index == count - 1) radius else 0.dp,
    )
    return this
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
}
