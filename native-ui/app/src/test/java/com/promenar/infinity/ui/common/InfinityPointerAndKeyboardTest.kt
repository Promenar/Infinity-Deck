package com.promenar.infinity.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.chat.LiveStep
import com.promenar.infinity.ui.chat.LiveStepKind
import com.promenar.infinity.ui.chat.LiveTailSlotHeight
import com.promenar.infinity.ui.chat.resolveAssistantFooterMinHeight
import com.promenar.infinity.ui.chat.shouldExitLiveTailImmediately
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InfinityPointerAndKeyboardTest {

    @Test
    fun secondaryClickTriggersOnlyInsideBoundsAndWithinSlop() {
        val size = IntSize(200, 60)
        assertTrue(
            shouldTriggerSecondaryClick(
                enabled = true,
                downPosition = Offset(50f, 30f),
                upPosition = Offset(54f, 32f),
                size = size,
                touchSlopPx = 12f,
                movedBeyondSlop = false,
            ),
        )
        assertFalse(
            shouldTriggerSecondaryClick(
                enabled = false,
                downPosition = Offset(50f, 30f),
                upPosition = Offset(50f, 30f),
                size = size,
                touchSlopPx = 12f,
                movedBeyondSlop = false,
            ),
        )
        assertFalse(
            shouldTriggerSecondaryClick(
                enabled = true,
                downPosition = Offset(50f, 30f),
                upPosition = Offset(52f, 30f),
                size = size,
                touchSlopPx = 12f,
                movedBeyondSlop = true,
            ),
        )
        assertFalse(
            shouldTriggerSecondaryClick(
                enabled = true,
                downPosition = Offset(50f, 30f),
                upPosition = Offset(80f, 30f),
                size = size,
                touchSlopPx = 12f,
                movedBeyondSlop = false,
            ),
        )
        assertFalse(
            shouldTriggerSecondaryClick(
                enabled = true,
                downPosition = Offset(2f, 2f),
                upPosition = Offset(-1f, 2f),
                size = size,
                touchSlopPx = 12f,
                movedBeyondSlop = false,
            ),
        )
    }

    @Test
    fun horizontalWheelScrollPrefersXAndFallsBackToY() {
        assertEquals(128f, horizontalWheelScrollDeltaPx(Offset(2f, 5f), stepPx = 64f), 0.001f)
        assertEquals(-64f, horizontalWheelScrollDeltaPx(Offset(0f, -1f), stepPx = 64f), 0.001f)
        assertEquals(0f, horizontalWheelScrollDeltaPx(Offset.Zero, stepPx = 64f), 0.001f)
    }

    @Test
    fun plainEscapeDownRequiresNoModifiers() {
        assertTrue(isPlainEscapeDown(Key.Escape, KeyEventType.KeyDown, false, false, false, false))
        assertFalse(isPlainEscapeDown(Key.Escape, KeyEventType.KeyUp, false, false, false, false))
        assertFalse(isPlainEscapeDown(Key.Escape, KeyEventType.KeyDown, true, false, false, false))
        assertFalse(isPlainEscapeDown(Key.Escape, KeyEventType.KeyDown, false, true, false, false))
        assertFalse(isPlainEscapeDown(Key.Enter, KeyEventType.KeyDown, false, false, false, false))
    }

    @Test
    fun composerEnterSendsShiftEnterNewlinesAndRespectsImeComposition() {
        assertEquals(
            ComposerKeyAction.SUBMIT,
            resolveComposerKeyAction(Key.Enter, KeyEventType.KeyDown, false, false, false, false, isComposing = false, canSubmit = true),
        )
        assertEquals(
            ComposerKeyAction.SUBMIT,
            resolveComposerKeyAction(Key.NumPadEnter, KeyEventType.KeyDown, false, false, false, false, isComposing = false, canSubmit = true),
        )
        assertEquals(
            ComposerKeyAction.CONSUME_NOOP,
            resolveComposerKeyAction(Key.Enter, KeyEventType.KeyDown, false, false, false, false, isComposing = false, canSubmit = false),
        )
        assertEquals(
            ComposerKeyAction.PASS_THROUGH,
            resolveComposerKeyAction(Key.Enter, KeyEventType.KeyDown, isShiftPressed = true, false, false, false, isComposing = false, canSubmit = true),
        )
        assertEquals(
            ComposerKeyAction.PASS_THROUGH,
            resolveComposerKeyAction(Key.Enter, KeyEventType.KeyDown, false, false, false, false, isComposing = true, canSubmit = true),
        )
        assertEquals(
            ComposerKeyAction.PASS_THROUGH,
            resolveComposerKeyAction(Key.Enter, KeyEventType.KeyUp, false, false, false, false, isComposing = false, canSubmit = true),
        )
        assertEquals(
            ComposerKeyAction.DISMISS,
            resolveComposerKeyAction(Key.Escape, KeyEventType.KeyDown, false, false, false, false, isComposing = false, canSubmit = true),
        )
    }

    @Test
    fun inlineEditorUsesPrimaryEnterToSubmitAndEscapeToCancel() {
        assertEquals(
            InlineEditorKeyAction.PASS_THROUGH,
            resolveInlineEditorKeyAction(Key.Enter, KeyEventType.KeyDown, false, false, false, false, isComposing = false, canSubmit = true),
        )
        assertEquals(
            InlineEditorKeyAction.SUBMIT,
            resolveInlineEditorKeyAction(Key.Enter, KeyEventType.KeyDown, false, isCtrlPressed = true, false, false, isComposing = false, canSubmit = true),
        )
        assertEquals(
            InlineEditorKeyAction.SUBMIT,
            resolveInlineEditorKeyAction(Key.Enter, KeyEventType.KeyDown, false, false, false, isMetaPressed = true, isComposing = false, canSubmit = true),
        )
        assertEquals(
            InlineEditorKeyAction.PASS_THROUGH,
            resolveInlineEditorKeyAction(Key.Enter, KeyEventType.KeyDown, false, isCtrlPressed = true, false, false, isComposing = false, canSubmit = false),
        )
        assertEquals(
            InlineEditorKeyAction.PASS_THROUGH,
            resolveInlineEditorKeyAction(Key.Enter, KeyEventType.KeyDown, false, isCtrlPressed = true, false, false, isComposing = true, canSubmit = true),
        )
        assertEquals(
            InlineEditorKeyAction.CANCEL,
            resolveInlineEditorKeyAction(Key.Escape, KeyEventType.KeyDown, false, false, false, false, isComposing = false, canSubmit = true),
        )
    }

    @Test
    fun liveTailHandoffSkipsShrinkOnlyWhenReplacedByThinkingOrToolRow() {
        val thinking = LiveStep(LiveStepKind.THINKING, GenerationMotionPhase.STREAM, targetId = "m1")
        val tool = LiveStep(LiveStepKind.TOOL, GenerationMotionPhase.TOOL, targetId = "t1")
        val waiting = LiveStep(LiveStepKind.WAITING_MODEL, GenerationMotionPhase.WAIT)
        val preparing = LiveStep(LiveStepKind.PREPARING_TOOL, GenerationMotionPhase.STREAM)
        val content = LiveStep(LiveStepKind.CONTENT, GenerationMotionPhase.STREAM, targetId = "m1")

        assertTrue(shouldExitLiveTailImmediately(isGenerating = true, liveStep = thinking))
        assertTrue(shouldExitLiveTailImmediately(isGenerating = true, liveStep = tool))
        assertFalse(shouldExitLiveTailImmediately(isGenerating = false, liveStep = thinking))
        assertFalse(shouldExitLiveTailImmediately(isGenerating = true, liveStep = waiting))
        assertFalse(shouldExitLiveTailImmediately(isGenerating = true, liveStep = preparing))
        assertFalse(shouldExitLiveTailImmediately(isGenerating = true, liveStep = content))
        assertFalse(shouldExitLiveTailImmediately(isGenerating = true, liveStep = null))
    }

    @Test
    fun assistantFooterMinHeightMatchesStepRowDuringIntermediateToolSteps() {
        assertEquals(LiveTailSlotHeight, resolveAssistantFooterMinHeight(isGenerating = true, lastStepIsContent = false))
        assertEquals(LiveTailSlotHeight + 8.dp, resolveAssistantFooterMinHeight(isGenerating = true, lastStepIsContent = true))
        assertEquals(LiveTailSlotHeight + 8.dp, resolveAssistantFooterMinHeight(isGenerating = false, lastStepIsContent = false))
        assertEquals(LiveTailSlotHeight + 8.dp, resolveAssistantFooterMinHeight(isGenerating = false, lastStepIsContent = true))
    }
}
