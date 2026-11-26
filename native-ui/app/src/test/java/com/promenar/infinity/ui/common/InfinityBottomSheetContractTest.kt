package com.promenar.infinity.ui.common

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class InfinityBottomSheetContractTest {

    private val moduleRoot: Path = Path.of(System.getProperty("user.dir") ?: ".").let { root ->
        if (Files.isDirectory(root.resolve("src/main"))) root else root.resolve("app")
    }

    @Test
    fun `未指定高度策略时按内容高度显示`() {
        // 大内容面板须显式声明 Large；短选择列表默认按内容高度，避免出现大片空白。
        assertThat(INFINITY_BOTTOM_SHEET_DEFAULT_HEIGHT).isEqualTo(InfinitySheetHeight.Content)
    }

    @Test
    fun `取消全屏模式且大上滑面板统一收口为93占比高度`() {
        assertThat(InfinitySheetHeight.entries).containsExactly(
            InfinitySheetHeight.Large,
            InfinitySheetHeight.Content,
        )
        assertThat(INFINITY_LARGE_SHEET_HEIGHT_FRACTION).isWithin(0.0001f).of(0.93f)
        assertThat(INFINITY_CENTERED_SHEET_HEIGHT_FRACTION).isWithin(0.0001f).of(0.88f)
    }

    @Test
    fun `窄屏底部面板高度约束仅施加于内容槽，避免截断ModalBottomSheet外层高度导致顶对齐悬空`() {
        // 典型折叠屏外屏（947dp 高、16dp 底部导航栏、32dp 拖动条）：总表面高度严格等于 93%。
        val contentHeight = infinityNarrowSheetContentHeight(
            windowHeightDp = 947.dp,
            bottomInsetDp = 16.dp,
            dragHandleHeightDp = 32.dp,
        )
        assertThat((contentHeight + 16.dp + 32.dp).value).isWithin(0.05f).of(947f * 0.93f)
        // 极小分屏窗口下保持保底可读高度。
        assertThat(infinityNarrowSheetContentHeight(windowHeightDp = 200.dp, bottomInsetDp = 48.dp)).isEqualTo(160.dp)

        // 契约守卫：ModalBottomSheet(...) 外层 modifier 绝不能携带 fillMaxHeight / heightIn。
        val source = Files.readString(moduleRoot.resolve("src/main/java/com/promenar/infinity/ui/common/InfinityBottomSheet.kt"))
        val modalCall = source.substringAfter("ModalBottomSheet(").substringBefore("sheetState = sheetState")
        assertThat(modalCall).doesNotContain("fillMaxHeight(")
        assertThat(modalCall).doesNotContain("heightIn(")
        assertThat(source).contains("infinityNarrowSheetContentHeight(")
    }

    @Test
    fun `宽窗口居中弹窗支持自定义最大宽度且制作选项弹层收窄至440dp`() {
        val sheetSource = Files.readString(moduleRoot.resolve("src/main/java/com/promenar/infinity/ui/common/InfinityBottomSheet.kt"))
        assertThat(sheetSource.split("centeredMaxWidth: Dp = 640.dp").size - 1).isEqualTo(3)
        assertThat(sheetSource).contains(".widthIn(max = centeredMaxWidth)")
        assertThat(sheetSource).doesNotContain(".widthIn(max = 640.dp)")
        assertThat(sheetSource).contains("centeredMaxWidth = centeredMaxWidth,")

        val productionDesign = Files.readString(moduleRoot.resolve("src/main/java/com/promenar/infinity/ui/production/ProductionDesign.kt"))
        val choiceSheetBody = productionDesign.substringAfter("internal fun <T> ProductionChoiceSheet(")
        assertThat(choiceSheetBody).contains("centeredMaxWidth = 440.dp")
    }
}
