package com.promenar.infinity.ui.chat

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.AmbientLightEmitter
import com.promenar.infinity.ui.common.AmbientLightField
import com.promenar.infinity.ui.common.AmbientLightSample
import com.promenar.infinity.ui.common.FrostedChromeBackdropLayer
import com.promenar.infinity.ui.common.FrostedChromeRole
import com.promenar.infinity.ui.common.FrostedTopBar
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.LocalAmbientLightField
import com.promenar.infinity.ui.common.RegisterAmbientLightEmitter
import com.promenar.infinity.ui.common.ambientGlassInfiltration
import com.promenar.infinity.ui.common.publishAmbientLightBounds
import com.promenar.infinity.ui.common.rememberFrostedChromeMaterial
import com.promenar.infinity.ui.settings.MotionStyleRows
import com.promenar.infinity.ui.theme.ComposerSurfaceStyle
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotionStyles
import com.promenar.infinity.ui.theme.InfinityObsidianGlassTint
import com.promenar.infinity.ui.theme.InfinityObsidianSolidChip
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.infinityDomainColors

@Composable
private fun FormalThreePaneGlassSurface(
    color: Color,
    energy: Float,
    surfaceStyle: ComposerSurfaceStyle,
    label: String,
) {
    val motion = InfinityMotionStyles(surface = surfaceStyle, glassLightInfiltration = true)
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = InfinityThemeMode.DARK,
            colorSource = InfinityColorSource.INFINITY,
            primaryColor = Color(0xFF0EA5E9).value.toLong(),
            pureBlack = true,
            motion = motion,
        ),
    ) {
        val field = remember { AmbientLightField() }
        val emitter = remember(color, energy) {
            object : AmbientLightEmitter {
                override fun isActive(): Boolean = energy > 0f
                override fun sampleInto(out: AmbientLightSample) {
                    out.color = color
                    out.energy = energy
                }
            }
        }
        CompositionLocalProvider(LocalAmbientLightField provides field) {
            RegisterAmbientLightEmitter(emitter)
            val scheme = MaterialTheme.colorScheme
            val domain = MaterialTheme.infinityDomainColors
            val rightChrome = rememberFrostedChromeMaterial(FrostedChromeRole.DOCKED_INSPECTOR)
            Surface(Modifier.fillMaxSize(), color = scheme.background) {
                Row(Modifier.fillMaxSize()) {
                    // 真实左侧三栏（复用 AgentHubScreenContent 底层 ambientGlassInfiltration）
                    Box(
                        Modifier
                            .width(196.dp)
                            .fillMaxHeight()
                            .background(scheme.background)
                            .ambientGlassInfiltration(cornerRadius = 0.dp)
                            .padding(12.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("对话", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = scheme.primary.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Codex CLI · 正式实现", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                                    Text(label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    // 中间会话列：真实 FrostedTopBar（远处应严格 0 渗光）+ 发布真实 root bounds 的输入浮岛
                    Box(Modifier.weight(1f).fillMaxHeight().background(scheme.background)) {
                        FrostedTopBar(
                            source = null,
                            bottomEdgeAlpha = 1f,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                        ) {
                            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                                Text("Codex CLI（顶栏距浮岛 > 235dp 零渗光）", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                            }
                        }

                        val islandShape = RoundedCornerShape(22.dp)
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 14.dp, vertical = 14.dp)
                                .fillMaxWidth()
                                .height(86.dp)
                                .publishAmbientLightBounds(field, emitter),
                            shape = islandShape,
                            color = InfinityObsidianGlassTint.copy(alpha = 0.90f),
                            border = BorderStroke(1.25.dp, color.copy(alpha = 0.65f)),
                        ) {
                            Column(
                                Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(label, style = MaterialTheme.typography.labelLarge, color = color)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("输入消息…", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                    Box(
                                        Modifier.size(28.dp).clip(CircleShape).background(scheme.onSurface),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Rounded.Stop, null, tint = scheme.surface, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    // 真实右侧固定检视器（直接调用正式 FrostedChromeBackdropLayer）
                    Box(
                        Modifier
                            .width(260.dp)
                            .fillMaxHeight()
                            .border(rightChrome.border ?: BorderStroke(0.75.dp, scheme.outlineVariant)),
                    ) {
                        FrostedChromeBackdropLayer(
                            source = null,
                            material = rightChrome,
                            highlightHeight = 56.dp,
                            cornerRadius = 0.dp,
                            modifier = Modifier.matchParentSize(),
                        )
                        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Git · main", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = InfinityObsidianSolidChip,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("AmbientLightField.kt", style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace), color = scheme.onSurface)
                                    Text("+ ambientGlassInfiltration()", style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace), color = domain.signalOn)
                                    Text("+ FrostedChromeBackdropLayer", style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace), color = domain.signalOn)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview(name = "正式实现 1 · 三栏 APPROVAL（毛玻璃）", widthDp = 760, heightDp = 520)
@Composable
fun FormalGlassInfiltrationApprovalPreview() {
    FormalThreePaneGlassSurface(
        color = Color(0xFFF59E0B),
        energy = 1.18f,
        surfaceStyle = ComposerSurfaceStyle.FROSTED,
        label = "等待审批（1.18× 真实生产引擎）",
    )
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview(name = "正式实现 2 · 三栏 APPROVAL（环境光纯色关闭）", widthDp = 760, heightDp = 520)
@Composable
fun FormalGlassInfiltrationAmbientOffPreview() {
    FormalThreePaneGlassSurface(
        color = Color(0xFFF59E0B),
        energy = 1.18f,
        surfaceStyle = ComposerSurfaceStyle.AMBIENT,
        label = "环境光纯色省电档（全局浸润严格关闭）",
    )
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview(name = "正式实现 3 · 外观界面特效（中411 毛玻璃）", widthDp = 411, heightDp = 740, locale = "zh-rCN")
@Composable
fun FormalInterfaceEffectsZhPreview() {
    val motion = InfinityMotionStyles(surface = ComposerSurfaceStyle.FROSTED, glassLightInfiltration = true)
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY, motion = motion)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                InfinitySettingsSection(title = stringResource(R.string.motion_section_title)) {
                    MotionStyleRows(motion = motion, onComposerSelect = {}, onIndicatorSelect = {}, onEmptyChatSelect = {})
                }
            }
        }
    }
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview(name = "正式实现 4 · 外观界面特效（英360 1.3x 环境光）", widthDp = 360, heightDp = 760, locale = "en", fontScale = 1.3f)
@Composable
fun FormalInterfaceEffectsEnAmbientPreview() {
    val motion = InfinityMotionStyles(surface = ComposerSurfaceStyle.AMBIENT, glassLightInfiltration = true)
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY, motion = motion)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                InfinitySettingsSection(title = stringResource(R.string.motion_section_title)) {
                    MotionStyleRows(motion = motion, onComposerSelect = {}, onIndicatorSelect = {}, onEmptyChatSelect = {})
                }
            }
        }
    }
}
