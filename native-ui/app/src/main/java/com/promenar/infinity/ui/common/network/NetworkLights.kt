package com.promenar.infinity.ui.common.network

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.network.PublicNetworkStatus
import com.promenar.infinity.data.tailnet.TailnetEndpoints
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.data.tailnet.TailnetState
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors

/** Tailscale 指示灯的状态；null 表示整盏灯不显示。 */
enum class TailscaleLightStatus { CONNECTED, CONNECTING, NEEDS_LOGIN, FAILED, OFF }

/**
 * 内置 Tailscale 开启时始终显示；未开启或安装包不含内置 Tailscale 时，只在有内网模型时显示“未开启”。
 * STOPPED 是开启后、子进程拉起前的短暂状态，与 STARTING 一样视为连接中。
 */
fun tailscaleLightStatus(phase: TailnetPhase, hasTailnetModels: Boolean): TailscaleLightStatus? = when (phase) {
    TailnetPhase.RUNNING -> TailscaleLightStatus.CONNECTED
    TailnetPhase.STARTING, TailnetPhase.STOPPED -> TailscaleLightStatus.CONNECTING
    TailnetPhase.NEEDS_LOGIN -> TailscaleLightStatus.NEEDS_LOGIN
    TailnetPhase.FAILED -> TailscaleLightStatus.FAILED
    TailnetPhase.DISABLED, TailnetPhase.UNAVAILABLE ->
        if (hasTailnetModels) TailscaleLightStatus.OFF else null
}

/** 点按 Tailscale 灯：失败时就地重试，其它状态前往 Tailscale 设置。 */
enum class TailscaleLightAction { OPEN_SETTINGS, RETRY }

fun TailscaleLightStatus.action(): TailscaleLightAction =
    if (this == TailscaleLightStatus.FAILED) TailscaleLightAction.RETRY else TailscaleLightAction.OPEN_SETTINGS

/** 模型选择器所需的网络状态：两盏灯、接口在 tailnet 内的提供商与本机推理提供商。 */
data class NetworkLightsState(
    val public: PublicNetworkStatus,
    val tailscale: TailscaleLightStatus?,
    val tailnetProviderIds: Set<String>,
    val localProviderIds: Set<String> = emptySet(),
)

fun networkLightsState(
    public: PublicNetworkStatus,
    tailnet: TailnetState,
    providers: List<ProviderListItem>,
): NetworkLightsState {
    // 未配置凭据的提供商不可用，其模型不会出现在选择器里，不计入内网模型。
    val tailnetProviderIds = providers
        .filter { (it.hasApiKey || it.hasVertexCredentials) && TailnetEndpoints.isTailnetEndpoint(it.baseUrl, tailnet) }
        .mapTo(mutableSetOf()) { it.id }
    val localProviderIds = providers
        .filter { it.protocolType is com.promenar.infinity.data.remote.protocol.ProtocolType.Local }
        .mapTo(mutableSetOf()) { it.id } +
        com.promenar.infinity.data.local.inference.LocalSessionModelBridge.LOCAL_RUNTIME_PROVIDER_ID
    return NetworkLightsState(
        public = public,
        tailscale = tailscaleLightStatus(tailnet.phase, tailnetProviderIds.isNotEmpty()),
        tailnetProviderIds = tailnetProviderIds,
        localProviderIds = localProviderIds,
    )
}

/** 模型行品牌图标右下角的小灯：显示该模型所走网络的状态。 */
sealed interface ModelNetworkBadge {
    data class Public(val status: PublicNetworkStatus) : ModelNetworkBadge
    data class Tailnet(val status: TailscaleLightStatus) : ModelNetworkBadge
}

/** 经 Tailscale 内网访问的模型显示 Tailscale 灯，其它经网络访问的模型显示公网灯；本机推理模型不显示。 */
fun NetworkLightsState.badgeFor(model: com.promenar.infinity.ui.common.ModelSelectionUiModel): ModelNetworkBadge? {
    if (model.providerId in localProviderIds) return null
    val tailnet = tailscale?.takeIf { model.providerId in tailnetProviderIds }
    return if (tailnet != null) ModelNetworkBadge.Tailnet(tailnet) else ModelNetworkBadge.Public(public)
}

/** 灯色只分三档：已连接为绿，连接中或受限为黄，未连接（离线、需登录、失败、未开启）为红。 */
enum class SignalTone { ON, PENDING, DOWN }

fun PublicNetworkStatus.tone(): SignalTone = when (this) {
    PublicNetworkStatus.CONNECTED -> SignalTone.ON
    PublicNetworkStatus.LIMITED -> SignalTone.PENDING
    PublicNetworkStatus.OFFLINE -> SignalTone.DOWN
}

fun TailscaleLightStatus.tone(): SignalTone = when (this) {
    TailscaleLightStatus.CONNECTED -> SignalTone.ON
    TailscaleLightStatus.CONNECTING -> SignalTone.PENDING
    TailscaleLightStatus.NEEDS_LOGIN, TailscaleLightStatus.FAILED, TailscaleLightStatus.OFF -> SignalTone.DOWN
}

private enum class LightShape { FILLED, HOLLOW, RING, DIAMOND }

private data class LightLook(val shape: LightShape, val tone: SignalTone)

@Composable
private fun SignalTone.color(): Color = when (this) {
    SignalTone.ON -> MaterialTheme.infinityDomainColors.signalOn
    SignalTone.PENDING -> MaterialTheme.infinityDomainColors.signalPending
    SignalTone.DOWN -> MaterialTheme.infinityDomainColors.signalDown
}

// 形状与颜色同时区分状态，色觉障碍时仍可分辨；红灯之间靠形状区分需登录、失败与未开启。
private fun PublicNetworkStatus.look(): LightLook = when (this) {
    PublicNetworkStatus.CONNECTED, PublicNetworkStatus.LIMITED -> LightLook(LightShape.FILLED, tone())
    PublicNetworkStatus.OFFLINE -> LightLook(LightShape.HOLLOW, tone())
}

private fun TailscaleLightStatus.look(): LightLook = when (this) {
    TailscaleLightStatus.CONNECTED, TailscaleLightStatus.NEEDS_LOGIN -> LightLook(LightShape.FILLED, tone())
    TailscaleLightStatus.CONNECTING -> LightLook(LightShape.RING, tone())
    TailscaleLightStatus.FAILED -> LightLook(LightShape.DIAMOND, tone())
    TailscaleLightStatus.OFF -> LightLook(LightShape.HOLLOW, tone())
}

@Composable
private fun PublicNetworkStatus.label(): String = stringResource(
    when (this) {
        PublicNetworkStatus.CONNECTED -> R.string.network_public_connected_label
        PublicNetworkStatus.LIMITED -> R.string.network_public_limited_label
        PublicNetworkStatus.OFFLINE -> R.string.network_public_offline_label
    },
)

@Composable
private fun PublicNetworkStatus.stateText(): String = stringResource(
    when (this) {
        PublicNetworkStatus.CONNECTED -> R.string.network_state_connected
        PublicNetworkStatus.LIMITED -> R.string.network_state_limited
        PublicNetworkStatus.OFFLINE -> R.string.network_state_offline
    },
)

@Composable
private fun TailscaleLightStatus.label(): String = stringResource(
    when (this) {
        TailscaleLightStatus.CONNECTED -> R.string.network_tailscale_name
        TailscaleLightStatus.CONNECTING -> R.string.network_tailscale_connecting_label
        TailscaleLightStatus.NEEDS_LOGIN -> R.string.network_tailscale_needs_login_label
        TailscaleLightStatus.FAILED -> R.string.network_tailscale_failed_label
        TailscaleLightStatus.OFF -> R.string.network_tailscale_off_label
    },
)

@Composable
internal fun TailscaleLightStatus.stateText(): String = stringResource(
    when (this) {
        TailscaleLightStatus.CONNECTED -> R.string.network_state_connected
        TailscaleLightStatus.CONNECTING -> R.string.network_state_connecting
        TailscaleLightStatus.NEEDS_LOGIN -> R.string.network_state_needs_login
        TailscaleLightStatus.FAILED -> R.string.network_state_failed
        TailscaleLightStatus.OFF -> R.string.network_state_off
    },
)

/** 系统“移除动画”开启时灯圈不旋转。 */
@Composable
internal fun rememberAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
            .getOrDefault(true)
    }
}

/** 单盏灯：颜色与形状同时区分状态，颜色变化 300ms 淡入淡出；实心与菱形的灯带一圈柔光，像点亮的指示灯。 */
@Composable
private fun NetworkLightDot(look: LightLook, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    val color by animateColorAsState(look.tone.color(), tween(InfinityMotion.DurationMedium), label = "lightColor")
    val spinning = look.shape == LightShape.RING && rememberAnimationsEnabled()
    val rotation = if (spinning) {
        val transition = rememberInfiniteTransition(label = "lightSpin")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Restart),
            label = "lightRotation",
        ).value
    } else {
        0f
    }
    Canvas(modifier.size(size).rotate(rotation)) {
        val stroke = 1.5.dp.toPx()
        val radius = this.size.minDimension / 2f
        if (look.shape == LightShape.FILLED || look.shape == LightShape.DIAMOND) {
            val glowRadius = radius * GLOW_SCALE
            drawCircle(
                brush = Brush.radialGradient(
                    0f to color.copy(alpha = 0.7f),
                    1f to color.copy(alpha = 0f),
                    center = center,
                    radius = glowRadius,
                ),
                radius = glowRadius,
            )
        }
        when (look.shape) {
            LightShape.FILLED -> drawCircle(color, radius)
            LightShape.HOLLOW -> drawCircle(color, radius - stroke / 2f, style = Stroke(stroke))
            LightShape.RING -> {
                val inset = stroke / 2f
                // 静止时保留半圈，旋转时为四分之三圈。
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = if (spinning) 270f else 180f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    style = Stroke(stroke),
                )
            }
            LightShape.DIAMOND -> {
                val path = Path().apply {
                    moveTo(center.x, 0f)
                    lineTo(this@Canvas.size.width, center.y)
                    lineTo(center.x, this@Canvas.size.height)
                    lineTo(0f, center.y)
                    close()
                }
                drawPath(path, color)
            }
        }
    }
}

/**
 * 模型选择器搜索框下方的“网络”行：公网灯始终显示，Tailscale 灯按 [NetworkLightsState.tailscale] 出现。
 * Tailscale 灯是 48dp 高的可点区域：失败时点按重试，其它状态点按前往设置；
 * 所在页面无法跳转设置（[onOpenTailscaleSettings] 为 null）时，除失败外只显示状态。
 */
@Composable
fun NetworkLightsRow(
    state: NetworkLightsState,
    onOpenTailscaleSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onRetryTailscale: () -> Unit = rememberTailscaleRetry(),
) {
    Row(
        modifier = modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(NETWORK_LIGHTS_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.network_lights_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp).clearAndSetSemantics {},
        )
        val publicDescription = stringResource(
            R.string.network_light_cd,
            stringResource(R.string.network_public_name),
            state.public.stateText(),
        )
        LightChip(
            look = state.public.look(),
            label = state.public.label(),
            modifier = Modifier
                .testTag(NETWORK_LIGHT_PUBLIC_TAG)
                .clearAndSetSemantics { contentDescription = publicDescription },
        )
        state.tailscale?.let { status ->
            val action = status.action()
            val description = stringResource(
                R.string.network_light_cd,
                stringResource(R.string.network_tailscale_name),
                status.stateText(),
            )
            val actionLabel = stringResource(
                if (action == TailscaleLightAction.RETRY) R.string.network_tailscale_action_retry
                else R.string.network_tailscale_action_open,
            )
            val onClick: (() -> Unit)? = when (action) {
                TailscaleLightAction.RETRY -> onRetryTailscale
                TailscaleLightAction.OPEN_SETTINGS -> onOpenTailscaleSettings
            }
            LightChip(
                look = status.look(),
                label = status.label(),
                modifier = Modifier
                    .testTag(NETWORK_LIGHT_TAILSCALE_TAG)
                    .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                    .clearAndSetSemantics {
                        contentDescription = description
                        if (onClick != null) {
                            role = Role.Button
                            onClick(label = actionLabel) { onClick(); true }
                        }
                    },
            )
        }
    }
}

@Composable
private fun LightChip(look: LightLook, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NetworkLightDot(look)
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 模型行品牌图标右下角的小灯，与“网络”行对应的灯同色同形；外圈用 [ringColor] 与图标分开。 */
@Composable
fun ModelNetworkLightBadge(badge: ModelNetworkBadge, ringColor: Color, modifier: Modifier = Modifier) {
    val (look, description) = when (badge) {
        is ModelNetworkBadge.Public ->
            badge.status.look() to stringResource(R.string.network_model_via_public_cd, badge.status.stateText())
        is ModelNetworkBadge.Tailnet ->
            badge.status.look() to stringResource(R.string.network_model_via_tailnet_cd, badge.status.stateText())
    }
    Box(
        modifier = modifier.size(12.dp).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(12.dp)) { drawCircle(ringColor) }
        NetworkLightDot(look, size = 8.dp)
    }
}

private const val GLOW_SCALE = 2.1f

const val NETWORK_LIGHTS_TAG = "network_lights"
const val NETWORK_LIGHT_PUBLIC_TAG = "network_light_public"
const val NETWORK_LIGHT_TAILSCALE_TAG = "network_light_tailscale"

/** 收集公网、内置 Tailscale 与提供商列表，得到模型选择器的网络状态。 */
@Composable
fun rememberNetworkLightsState(): NetworkLightsState {
    val app = LocalContext.current.applicationContext as com.promenar.infinity.InfinityApplication
    val public by app.publicNetworkMonitor.status.collectAsStateWithLifecycle()
    val tailnet by app.tailnetManager.state.collectAsStateWithLifecycle()
    val providers by com.promenar.infinity.data.manager.ProviderManager.getInstance().providers.collectAsStateWithLifecycle()
    return remember(public, tailnet, providers) {
        networkLightsState(public, tailnet, providers.filter { it.enabled })
    }
}

/** 立即重新拉起内置 Tailscale（忽略失败退避）。 */
@Composable
fun rememberTailscaleRetry(): () -> Unit {
    val app = LocalContext.current.applicationContext as com.promenar.infinity.InfinityApplication
    return remember(app) { { app.tailnetManager.retry() } }
}
