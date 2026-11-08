package com.promenar.infinity.ui.common

import android.os.Trace
import android.util.Log
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownAnnotator
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarkdownAnnotationFrameTest {
    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @Test
    fun `渐显帧改变时历史块不重注解而末块更新透明度`() = runTest {
        mockkStatic(Log::class, Trace::class)
        every { Log.e(any(), any(), any()) } answers { throw thirdArg<Throwable>() }
        every { Trace.beginSection(any()) } returns Unit
        every { Trace.endSection() } returns Unit
        val clock = BroadcastFrameClock()
        val context = coroutineContext + clock
        val recomposer = Recomposer(context)
        val composition = Composition(NoOpApplier(), recomposer)
        val running = launch(context, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val history = paragraph("历史正文")
        val tail = paragraph("末段")
        val initial = StreamRevealFrame("末段", 0L, listOf(StreamRevealRange(0, 2, 0L)))
        val frame = mutableStateOf(initial)
        val bases = mutableListOf<MarkdownAnnotator>()
        var historyBuilds = 0
        var tailBuilds = 0
        val onHistory: (String) -> Unit = { assertEquals("历史正文", it); historyBuilds++ }
        val onTail: (String) -> Unit = { assertEquals("末段", it); tailBuilds++ }
        try {
            composition.setContent {
                val annotators = rememberReadingMarkdownAnnotators(SpanStyle(), Color.White, 0, frame.value)
                bases += annotators.base
                CompositionLocalProvider(LocalMarkdownAnnotator provides annotators.base) { AnnotatedProbe("历史正文", history, onHistory) }
                CompositionLocalProvider(LocalMarkdownAnnotator provides annotators.tail) { AnnotatedProbe("末段", tail, onTail) }
            }
            runCurrent()
            for (index in 1..12) {
                val now = index * 16_666_667L
                frame.value = StreamRevealFrame("末段", now, if (now < 120_000_000L) initial.recentRanges else emptyList())
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(now)
                runCurrent()
            }
            assertTrue(bases.size > 1)
            bases.forEach { assertSame(bases.first(), it) }
            assertEquals("历史块不应随末块渐显重建注解", 1, historyBuilds)
            assertTrue("末块应实际更新注解", tailBuilds > 1)
        } finally {
            composition.dispose()
            recomposer.cancel()
            running.join()
            unmockkStatic(Log::class, Trace::class)
        }
    }

    @Composable
    private fun AnnotatedProbe(content: String, node: ASTNode, onAnnotated: (String) -> Unit) {
        val settings = DefaultAnnotatorSettings(TextLinkStyles(), SpanStyle(), LocalMarkdownAnnotator.current, highlightSpanStyle = SpanStyle())
        val annotated = content.buildMarkdownAnnotatedString(node, TextStyle.Default, settings)
        onAnnotated(annotated.text)
    }

    private fun paragraph(content: String) = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
        .children.single { it.type == MarkdownElementTypes.PARAGRAPH }
}
