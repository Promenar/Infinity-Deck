package com.promenar.infinity.ui.common

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import com.promenar.infinity.ui.common.motion.resolveMotionPalette
import org.junit.Test

class AmbientLightFieldTest {

    private val darkPalette = resolveMotionPalette(
        primary = Color(0xFF0EA5E9),
        tertiary = Color(0xFF8B5CF6),
        warning = Color(0xFFF59E0B),
        outlineVariant = Color(0xFF272A30),
        onSurfaceVariant = Color(0xFF94A3B8),
        dark = true,
    )
    private val errorRed = Color(0xFFEF4444)

    @Test
    fun `物理距离衰减贴合为1、随距离平滑衰减、超出上限严格归零`() {
        assertThat(ambientDistanceFalloff(0f, 235f)).isWithin(1e-4f).of(1f)
        assertThat(ambientDistanceFalloff(-10f, 235f)).isWithin(1e-4f).of(1f)
        val near16 = ambientDistanceFalloff(16f, 235f)
        val mid56 = ambientDistanceFalloff(56f, 235f)
        val far147 = ambientDistanceFalloff(147f, 235f)
        assertThat(near16).isGreaterThan(0.85f)
        assertThat(mid56).isLessThan(near16)
        assertThat(mid56).isGreaterThan(0.60f)
        assertThat(far147).isLessThan(mid56)
        assertThat(far147).isGreaterThan(0.18f)
        assertThat(ambientDistanceFalloff(235f, 235f)).isEqualTo(0f)
        assertThat(ambientDistanceFalloff(533f, 235f)).isEqualTo(0f)
        assertThat(ambientDistanceFalloff(10f, 0f)).isEqualTo(0f)
    }

    @Test
    fun `三栏布局左右侧栏按各自相邻切面受光，远端顶栏返回null零渗光`() {
        val composer = Rect(left = 300f, top = 560f, right = 680f, bottom = 650f)
        val leftSidebar = Rect(left = 0f, top = 0f, right = 284f, bottom = 680f)
        val rightInspector = Rect(left = 696f, top = 0f, right = 1056f, bottom = 680f)
        val farTopBar = Rect(left = 284f, top = 0f, right = 696f, bottom = 56f)

        val leftCoupling = checkNotNull(computeAmbientGlassCoupling(leftSidebar, composer, 235f))
        assertThat(leftCoupling.edge).isEqualTo(InfiltrationEdge.RIGHT)
        assertThat(leftCoupling.falloff).isWithin(0.02f).of(0.90f)
        assertThat(leftCoupling.anchorPx).isWithin(1f).of(560f + 90f * 0.45f)

        val rightCoupling = checkNotNull(computeAmbientGlassCoupling(rightInspector, composer, 235f))
        assertThat(rightCoupling.edge).isEqualTo(InfiltrationEdge.LEFT)
        assertThat(rightCoupling.falloff).isWithin(0.02f).of(0.90f)

        // 顶栏距浮岛 504 > 235，严格返回 null，保证零绘制与零逐帧订阅
        assertThat(computeAmbientGlassCoupling(farTopBar, composer, 235f)).isNull()
    }

    @Test
    fun `软键盘抬升浮岛靠近顶栏后顶栏下缘按物理距离柔和受光`() {
        val topBar = Rect(left = 284f, top = 0f, right = 696f, bottom = 56f)
        val liftedComposer = Rect(left = 300f, top = 196f, right = 680f, bottom = 286f) // 间距 140 < 235

        val coupling = checkNotNull(computeAmbientGlassCoupling(topBar, liftedComposer, 235f))
        assertThat(coupling.edge).isEqualTo(InfiltrationEdge.BOTTOM)
        assertThat(coupling.falloff).isGreaterThan(0.20f)
        assertThat(coupling.falloff).isLessThan(0.35f)
    }

    @Test
    fun `悬浮扩大抽屉切过浮岛与竖屏抽屉覆盖浮岛均解析正确受光边缘`() {
        val composer = Rect(left = 300f, top = 560f, right = 680f, bottom = 650f)
        val widenedRightDrawer = Rect(left = 500f, top = 16f, right = 1060f, bottom = 664f)
        val floatingLeftDrawer = Rect(left = 0f, top = 0f, right = 360f, bottom = 680f)
        val portraitDrawer = Rect(left = 0f, top = 50f, right = 390f, bottom = 800f)
        val portraitComposer = Rect(left = 16f, top = 690f, right = 374f, bottom = 780f)

        val rightOverlap = checkNotNull(computeAmbientGlassCoupling(widenedRightDrawer, composer, 235f))
        assertThat(rightOverlap.edge).isEqualTo(InfiltrationEdge.LEFT)
        assertThat(rightOverlap.falloff).isWithin(1e-3f).of(0.94f)

        val leftOverlap = checkNotNull(computeAmbientGlassCoupling(floatingLeftDrawer, composer, 235f))
        assertThat(leftOverlap.edge).isEqualTo(InfiltrationEdge.RIGHT)
        assertThat(leftOverlap.falloff).isWithin(1e-3f).of(0.94f)

        val portraitOverlap = checkNotNull(computeAmbientGlassCoupling(portraitDrawer, portraitComposer, 235f))
        assertThat(portraitOverlap.edge).isEqualTo(InfiltrationEdge.BOTTOM)
        assertThat(portraitOverlap.falloff).isWithin(1e-3f).of(0.86f)
    }

    @Test
    fun `收起胶囊条位于浮岛上方近距时由底缘承接光效，拖远后归零`() {
        val composer = Rect(left = 16f, top = 580f, right = 880f, bottom = 670f)
        val nearStrip = Rect(left = 824f, top = 340f, right = 872f, bottom = 564f)
        val farStrip = Rect(left = 824f, top = 20f, right = 872f, bottom = 240f)

        val nearCoupling = checkNotNull(computeAmbientGlassCoupling(nearStrip, composer, 235f))
        assertThat(nearCoupling.edge).isEqualTo(InfiltrationEdge.BOTTOM)
        assertThat(nearCoupling.falloff).isGreaterThan(0.85f)

        assertThat(computeAmbientGlassCoupling(farStrip, composer, 235f)).isNull()
    }

    @Test
    fun `阶段差异化强度：待审批与异常显著强于日常流式与等待，且审批色取统一警示色`() {
        val streamSample = AmbientLightSample()
        resolveAmbientPhaseEmission(
            generating = 1f,
            error = 0f,
            timeSeconds = 0.25f,
            act = 0.7f,
            weightOf = { if (it == GenerationMotionPhase.STREAM) 1f else 0f },
            palette = darkPalette,
            errorColor = errorRed,
            out = streamSample,
        )

        val approvalSample = AmbientLightSample()
        resolveAmbientPhaseEmission(
            generating = 1f,
            error = 0f,
            timeSeconds = 0.25f, // slowBlink(0.25f) == 1.0 峰值
            act = 0f,
            weightOf = { if (it == GenerationMotionPhase.APPROVAL) 1f else 0f },
            palette = darkPalette,
            errorColor = errorRed,
            out = approvalSample,
        )

        val errorSample = AmbientLightSample()
        resolveAmbientPhaseEmission(
            generating = 0f,
            error = 1f,
            timeSeconds = 0f,
            act = 0f,
            weightOf = { 0f },
            palette = darkPalette,
            errorColor = errorRed,
            out = errorSample,
        )

        val idleSample = AmbientLightSample()
        resolveAmbientPhaseEmission(
            generating = 0f,
            error = 0f,
            timeSeconds = 0.25f,
            act = 0.8f,
            weightOf = { if (it == GenerationMotionPhase.STREAM) 1f else 0f },
            palette = darkPalette,
            errorColor = errorRed,
            out = idleSample,
        )

        assertThat(streamSample.color).isEqualTo(darkPalette.a)
        assertThat(approvalSample.color).isEqualTo(darkPalette.approval)
        assertThat(errorSample.color).isEqualTo(errorRed)
        assertThat(approvalSample.energy).isGreaterThan(streamSample.energy * 1.8f)
        assertThat(errorSample.energy).isGreaterThan(streamSample.energy * 1.8f)
        assertThat(idleSample.energy).isEqualTo(0f)
        assertThat(idleSample.color).isEqualTo(Color.Transparent)
    }

    @Test
    fun `光源栈后进先出：推入子代理再退出时自动恢复父会话光源与坐标`() {
        val field = AmbientLightField()
        val parentEmitter = object : AmbientLightEmitter {
            override fun isActive(): Boolean = true
            override fun sampleInto(out: AmbientLightSample) = Unit
        }
        val subagentEmitter = object : AmbientLightEmitter {
            override fun isActive(): Boolean = true
            override fun sampleInto(out: AmbientLightSample) = Unit
        }
        val parentRect = Rect(100f, 500f, 500f, 600f)
        val subagentRect = Rect(120f, 480f, 520f, 580f)

        field.registerEmitter(parentEmitter)
        field.updateSourceBounds(parentEmitter, parentRect)
        assertThat(field.activeEmitter).isSameInstanceAs(parentEmitter)
        assertThat(field.sourceBoundsInRoot).isEqualTo(parentRect)

        field.registerEmitter(subagentEmitter)
        field.updateSourceBounds(subagentEmitter, subagentRect)
        assertThat(field.activeEmitter).isSameInstanceAs(subagentEmitter)
        assertThat(field.sourceBoundsInRoot).isEqualTo(subagentRect)

        // 亚像素抖动不触发边界刷新
        field.updateSourceBounds(subagentEmitter, Rect(120.4f, 480.3f, 520.2f, 580.1f))
        assertThat(field.sourceBoundsInRoot).isEqualTo(subagentRect)

        // 退出子代理后自动恢复父会话光源与原边界
        field.unregisterEmitter(subagentEmitter)
        assertThat(field.activeEmitter).isSameInstanceAs(parentEmitter)
        assertThat(field.sourceBoundsInRoot).isEqualTo(parentRect)

        field.unregisterEmitter(parentEmitter)
        assertThat(field.activeEmitter).isNull()
        assertThat(field.sourceBoundsInRoot).isEqualTo(Rect.Zero)
    }
}
