package com.promenar.infinity.ui.welcome

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityObsidianDialogContainer
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.infinityBrandAccentColor
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 阶段二 v2 原型（按用户反馈重构，隔离于 debug 源码）：
 * 1. 全面采用中立明确的地域/国家描述（「中国提供商 / 海外提供商」「China Providers / Global Providers」），杜绝「国内/国外」预设国籍措辞；
 * 2. 取消三段模式切换与四格卡片分类，回归与「设置 → 添加提供商」完全一致的「提供商预设下拉/弹层选择」模式；
 * 3. 在预设名称后紧跟紧凑小徽章直观标注「一键登录」「含免费模型」「免费额度」「每日免费」「新客赠额」「试用」等权益；
 * 4. 下方凭证卡片根据所选预设动态切换（OpenRouter 呈现一键 OAuth PKCE 主卡片 + 代理提示；API Key 提供商呈现 2 步领 Key + 剪贴板智能检测）；
 * 5. 本地 GGUF 推理不纳入新手首次引导，仅留给高阶用户在常规设置中自行配置。
 */

private enum class BadgeTone { PRIMARY, SUCCESS, NEUTRAL }

private data class ProtoInlineBadge(
    val text: String,
    val tone: BadgeTone,
)

private data class ProtoPresetRowItem(
    val name: String,
    val subtitle: String,
    @DrawableRes val iconRes: Int,
    val accentKey: String,
    val badges: List<ProtoInlineBadge> = emptyList(),
    val selected: Boolean = false,
)

@Composable
private fun OnboardingV2Shell(
    content: @Composable () -> Unit,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = InfinityThemeMode.DARK,
            colorSource = InfinityColorSource.INFINITY,
            pureBlack = true,
        ),
    ) {
        OnboardingBackdrop(Modifier.fillMaxSize()) {
            content()
        }
    }
}

@Composable
private fun ProtoMicroBadge(badge: ProtoInlineBadge) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val (bg, fg) = when (badge.tone) {
        BadgeTone.PRIMARY -> scheme.primary.copy(alpha = 0.16f) to scheme.primary
        BadgeTone.SUCCESS -> domain.success.copy(alpha = 0.15f) to domain.success
        BadgeTone.NEUTRAL -> scheme.surfaceContainerHighest.copy(alpha = 0.9f) to scheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
    ) {
        Text(
            text = badge.text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ProtoBrandIconTile(
    @DrawableRes iconRes: Int,
    accentKey: String,
    tileSize: Dp = 36.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = infinityBrandAccentColor(accentKey)
    val bg = accent?.copy(alpha = 0.14f) ?: InfinityPureWhite.copy(alpha = 0.08f)
    val rim = accent?.copy(alpha = 0.28f) ?: InfinityPureWhite.copy(alpha = 0.14f)
    val tint = if (accentKey == "openrouter" || accentKey == "custom") InfinityPureWhite else Color.Unspecified
    Box(
        modifier = Modifier
            .size(tileSize)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(0.6.dp, rim, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(tileSize * 0.56f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProtoPresetTriggerCard(
    sectionLabel: String,
    item: ProtoPresetRowItem,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = sectionLabel,
            style = OnboardingType.caption().copy(fontWeight = FontWeight.Medium),
            color = onboardingFaintText,
        )
        OnboardingCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProtoBrandIconTile(item.iconRes, item.accentKey, tileSize = 38.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurface,
                            maxLines = 1,
                        )
                        item.badges.forEach { ProtoMicroBadge(it) }
                    }
                    Text(
                        text = item.subtitle,
                        style = OnboardingType.caption(),
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ProtoOnboardingV2Header(
    stepText: String,
    title: String,
    subtitle: String,
    skipLabel: String = "稍后配置",
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stepText, style = OnboardingType.eyebrow(), color = onboardingFaintText)
            OutlinedButton(
                onClick = {},
                shape = CircleShape,
                border = BorderStroke(0.8.dp, onboardingLineColor),
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text(skipLabel, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
        OnboardingProgressBar(count = 4, current = 1)
        Spacer(Modifier.height(2.dp))
        Text(text = title, style = OnboardingType.title(), color = scheme.onSurface)
        Text(text = subtitle, style = OnboardingType.body(), color = scheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProtoPresetSheetRow(item: ProtoPresetRowItem) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProtoBrandIconTile(item.iconRes, item.accentKey, tileSize = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = scheme.onSurface,
                    maxLines = 1,
                )
                item.badges.forEach { ProtoMicroBadge(it) }
            }
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.selected) {
            Icon(Icons.Rounded.Check, null, tint = scheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Preview(name = "1_OnboardingV2_CN_ChinaProvider", widthDp = 390, heightDp = 860, showBackground = true)
@Composable
fun OnboardingV2CnChinaProviderPreview() {
    OnboardingV2Shell {
        val scheme = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProtoOnboardingV2Header(
                stepText = "第 2 步，共 4 步 · 连接 AI",
                title = "选择 AI 提供商",
                subtitle = "下拉选择任意中国或海外提供商预设，免填接口地址即可开始对话。",
            )

            // 统一下拉预设选择行（紧跟免费/试用徽章）
            ProtoPresetTriggerCard(
                sectionLabel = "提供商预设",
                item = ProtoPresetRowItem(
                    name = "硅基流动",
                    subtitle = "api.siliconflow.cn · 中国提供商",
                    iconRes = R.drawable.ic_provider_siliconflow,
                    accentKey = "siliconflow",
                    badges = listOf(
                        ProtoInlineBadge("免费额度", BadgeTone.SUCCESS),
                        ProtoInlineBadge("含免费模型", BadgeTone.SUCCESS),
                    ),
                ),
            )

            // 动态凭据引导卡：2 步领 Key + 剪贴板自动识别
            OnboardingCard {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = CircleShape, color = scheme.primary.copy(alpha = 0.18f), modifier = Modifier.size(22.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("1", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = scheme.primary)
                            }
                        }
                        Text("去硅基流动官网领取 API 密钥", style = OnboardingType.body().copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
                    }
                    FilledTonalButton(
                        onClick = {},
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                    ) {
                        Text("打开控制台领取免费密钥", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        text = "登录控制台 → 新建 API 密钥 → 复制后返回本应用",
                        style = OnboardingType.caption(),
                        color = scheme.onSurfaceVariant,
                    )

                    HorizontalDivider(color = onboardingLineColor)

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = CircleShape, color = scheme.primary.copy(alpha = 0.18f), modifier = Modifier.size(22.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("2", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = scheme.primary)
                            }
                        }
                        Text("粘贴密钥（接口地址已自动配置）", style = OnboardingType.body().copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
                    }

                    // 剪贴板智能检测条
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = scheme.primary.copy(alpha = 0.12f),
                        border = BorderStroke(0.8.dp, scheme.primary.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.ContentPaste, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("检测到剪贴板密钥", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = scheme.primary)
                                Text("sk-7a9f••••••••3e21", style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace), color = scheme.onSurfaceVariant)
                            }
                            FilledTonalButton(
                                onClick = {},
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Text("一键填入", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    // 简洁密钥输入槽
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF0A0B0F),
                        border = BorderStroke(1.dp, onboardingLineColor),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.VpnKey, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                            Text("sk-••••••••••••••••••••••••", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace), color = scheme.onSurface)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {},
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Icon(Icons.Rounded.Tune, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("高级地址与名称", style = MaterialTheme.typography.labelMedium)
                    Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(16.dp))
                }
            }

            OnboardingPrimaryButton(onClick = {}) {
                Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("验证并连接", style = OnboardingType.button())
            }
        }
    }
}

@Preview(name = "2_OnboardingV2_CN_PresetSheet_Badges", widthDp = 390, heightDp = 930, showBackground = true)
@Composable
fun OnboardingV2CnPresetSheetBadgesPreview() {
    OnboardingV2Shell {
        val scheme = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(top = 28.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = InfinityObsidianDialogContainer,
                border = BorderStroke(0.8.dp, InfinityPureWhite.copy(alpha = 0.12f)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(InfinityPureWhite.copy(alpha = 0.22f)),
                    )
                    Text(
                        text = "选择提供商预设",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                    )

                    // 无框搜索胶囊（复刻 v0.82.0 样式）
                    Surface(
                        shape = CircleShape,
                        color = scheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.Search, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Text("搜索提供商名称或域名…", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                    }

                    // 分组 1：一键授权（OAuth）
                    Text("一键授权（OAuth）", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                    BettboxListGroup {
                        ProtoPresetSheetRow(
                            ProtoPresetRowItem(
                                name = "OpenRouter",
                                subtitle = "openrouter.ai · 海外聚合 300+ 模型",
                                iconRes = R.drawable.ic_provider_openrouter,
                                accentKey = "openrouter",
                                badges = listOf(
                                    ProtoInlineBadge("一键登录", BadgeTone.PRIMARY),
                                    ProtoInlineBadge("含免费模型", BadgeTone.SUCCESS),
                                ),
                            ),
                        )
                    }

                    // 分组 2：中国提供商
                    Text("中国提供商", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                    BettboxListGroup {
                        val chinaItems = listOf(
                            ProtoPresetRowItem(
                                name = "硅基流动",
                                subtitle = "api.siliconflow.cn · 2 个端点变体",
                                iconRes = R.drawable.ic_provider_siliconflow,
                                accentKey = "siliconflow",
                                badges = listOf(
                                    ProtoInlineBadge("免费额度", BadgeTone.SUCCESS),
                                    ProtoInlineBadge("含免费模型", BadgeTone.SUCCESS),
                                ),
                                selected = true,
                            ),
                            ProtoPresetRowItem(
                                name = "魔搭社区",
                                subtitle = "api-inference.modelscope.cn",
                                iconRes = R.drawable.ic_provider_qwen,
                                accentKey = "qwen",
                                badges = listOf(ProtoInlineBadge("每日免费", BadgeTone.SUCCESS)),
                            ),
                            ProtoPresetRowItem(
                                name = "DeepSeek",
                                subtitle = "api.deepseek.com",
                                iconRes = R.drawable.ic_provider_deepseek,
                                accentKey = "deepseek",
                                badges = listOf(ProtoInlineBadge("按量直连", BadgeTone.NEUTRAL)),
                            ),
                            ProtoPresetRowItem(
                                name = "智谱 GLM",
                                subtitle = "open.bigmodel.cn · 4 个端点变体",
                                iconRes = R.drawable.ic_provider_zhipu,
                                accentKey = "zhipu",
                                badges = listOf(ProtoInlineBadge("新客试用", BadgeTone.SUCCESS)),
                            ),
                            ProtoPresetRowItem(
                                name = "通义千问",
                                subtitle = "dashscope.aliyuncs.com · 2 个端点变体",
                                iconRes = R.drawable.ic_provider_qwen,
                                accentKey = "qwen",
                                badges = listOf(ProtoInlineBadge("新客免费", BadgeTone.SUCCESS)),
                            ),
                        )
                        chinaItems.forEachIndexed { idx, item ->
                            if (idx > 0) InfinityGroupDivider(startInset = 60.dp)
                            ProtoPresetSheetRow(item)
                        }
                    }

                    // 分组 3：海外提供商
                    Text("海外提供商", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                    BettboxListGroup {
                        val globalItems = listOf(
                            ProtoPresetRowItem(
                                name = "Gemini",
                                subtitle = "generativelanguage.googleapis.com",
                                iconRes = R.drawable.ic_provider_gemini,
                                accentKey = "gemini",
                                badges = listOf(ProtoInlineBadge("免费额度", BadgeTone.SUCCESS)),
                            ),
                            ProtoPresetRowItem(
                                name = "OpenAI",
                                subtitle = "api.openai.com",
                                iconRes = R.drawable.ic_provider_openai,
                                accentKey = "openai",
                            ),
                            ProtoPresetRowItem(
                                name = "Anthropic",
                                subtitle = "api.anthropic.com",
                                iconRes = R.drawable.ic_provider_anthropic,
                                accentKey = "anthropic",
                            ),
                        )
                        globalItems.forEachIndexed { idx, item ->
                            if (idx > 0) InfinityGroupDivider(startInset = 60.dp)
                            ProtoPresetSheetRow(item)
                        }
                    }

                    // 分组 4：自定义（不含本地 GGUF）
                    Text("自定义接口", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                    BettboxListGroup {
                        ProtoPresetSheetRow(
                            ProtoPresetRowItem(
                                name = "自定义提供商",
                                subtitle = "OpenAI / Anthropic / Gemini 兼容协议",
                                iconRes = R.drawable.ic_provider_custom,
                                accentKey = "custom",
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "3_OnboardingV2_CN_OpenRouter_OAuth", widthDp = 390, heightDp = 820, showBackground = true)
@Composable
fun OnboardingV2CnOpenRouterSelectedPreview() {
    OnboardingV2Shell {
        val scheme = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProtoOnboardingV2Header(
                stepText = "第 2 步，共 4 步 · 连接 AI",
                title = "选择 AI 提供商",
                subtitle = "已选择支持官方 OAuth 一键登录的聚合平台，授权后自动写入本机钥匙串。",
            )

            ProtoPresetTriggerCard(
                sectionLabel = "提供商预设",
                item = ProtoPresetRowItem(
                    name = "OpenRouter",
                    subtitle = "openrouter.ai · 海外聚合 300+ 模型",
                    iconRes = R.drawable.ic_provider_openrouter,
                    accentKey = "openrouter",
                    badges = listOf(
                        ProtoInlineBadge("一键登录", BadgeTone.PRIMARY),
                        ProtoInlineBadge("含免费模型", BadgeTone.SUCCESS),
                    ),
                ),
            )

            // 动态切换为 OpenRouter 一键授权主卡片
            OnboardingCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // 诚实的中立地域代理提示
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.infinityDomainColors.warning.copy(alpha = 0.10f),
                        border = BorderStroke(0.8.dp, MaterialTheme.infinityDomainColors.warning.copy(alpha = 0.30f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Icon(Icons.Rounded.Public, null, tint = MaterialTheme.infinityDomainColors.warning, modifier = Modifier.size(18.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "海外提供商 · 中国大陆建议配合代理",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = scheme.onSurface,
                                )
                                Text(
                                    text = "授权换取密钥与调用海外模型自动走内置代理或系统代理",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 15.sp),
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            FilledTonalButton(
                                onClick = {},
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                Text("内置代理", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Button(
                        onClick = {},
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Login, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("使用 OpenRouter 一键登录", style = OnboardingType.button())
                    }

                    OutlinedButton(
                        onClick = {},
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                    ) {
                        Icon(Icons.Rounded.Key, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("改用手动粘贴 API 密钥", style = MaterialTheme.typography.labelLarge)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Shield, null, tint = onboardingFaintText, modifier = Modifier.size(14.dp))
                        Text(
                            text = "直连 OpenRouter 官方授权页，不触碰账号密码，不经第三方中转。",
                            style = OnboardingType.caption().copy(fontSize = 11.sp),
                            color = onboardingFaintText,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "4_OnboardingV2_EN_Global_Dropdown", widthDp = 390, heightDp = 880, showBackground = true)
@Composable
fun OnboardingV2EnGlobalDropdownPreview() {
    OnboardingV2Shell {
        val scheme = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProtoOnboardingV2Header(
                stepText = "STEP 2 OF 4 · CONNECT AI",
                title = "Choose an AI provider",
                subtitle = "Pick a preset from Global or China providers—or sign in with one tap via OpenRouter.",
                skipLabel = "Skip",
            )

            ProtoPresetTriggerCard(
                sectionLabel = "Provider Preset",
                item = ProtoPresetRowItem(
                    name = "OpenRouter",
                    subtitle = "openrouter.ai · 300+ global models",
                    iconRes = R.drawable.ic_provider_openrouter,
                    accentKey = "openrouter",
                    badges = listOf(
                        ProtoInlineBadge("One-Click", BadgeTone.PRIMARY),
                        ProtoInlineBadge("Free tier", BadgeTone.SUCCESS),
                    ),
                ),
            )

            OnboardingCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {},
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Login, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sign in with OpenRouter", style = OnboardingType.button())
                    }
                    OutlinedButton(
                        onClick = {},
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                    ) {
                        Icon(Icons.Rounded.Key, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Or paste API key manually", style = MaterialTheme.typography.labelLarge)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Lock, null, tint = onboardingFaintText, modifier = Modifier.size(13.dp))
                        Text(
                            text = "OAuth 2.0 PKCE · Key stored locally in Android Keystore",
                            style = OnboardingType.caption().copy(fontSize = 11.sp),
                            color = onboardingFaintText,
                        )
                    }
                }
            }

            // 下拉分组预览（展示英文明确国家/地域分组与免费徽章）
            Text("PRESET LIST GROUPS (PREVIEW)", style = OnboardingType.eyebrow().copy(fontSize = 10.5.sp), color = onboardingFaintText)
            BettboxListGroup {
                val sampleEn = listOf(
                    ProtoPresetRowItem(
                        name = "Gemini",
                        subtitle = "Global · generativelanguage.googleapis.com",
                        iconRes = R.drawable.ic_provider_gemini,
                        accentKey = "gemini",
                        badges = listOf(ProtoInlineBadge("Free tier", BadgeTone.SUCCESS)),
                    ),
                    ProtoPresetRowItem(
                        name = "SiliconFlow",
                        subtitle = "China · api.siliconflow.cn",
                        iconRes = R.drawable.ic_provider_siliconflow,
                        accentKey = "siliconflow",
                        badges = listOf(
                            ProtoInlineBadge("Starter credit", BadgeTone.SUCCESS),
                            ProtoInlineBadge("Free models", BadgeTone.SUCCESS),
                        ),
                    ),
                    ProtoPresetRowItem(
                        name = "ModelScope",
                        subtitle = "China · api-inference.modelscope.cn",
                        iconRes = R.drawable.ic_provider_qwen,
                        accentKey = "qwen",
                        badges = listOf(ProtoInlineBadge("Free daily", BadgeTone.SUCCESS)),
                    ),
                )
                sampleEn.forEachIndexed { idx, item ->
                    if (idx > 0) InfinityGroupDivider(startInset = 60.dp)
                    ProtoPresetSheetRow(item)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Preview(name = "5_SettingsV2_AddProvider_Unified", widthDp = 390, heightDp = 880, showBackground = true)
@Composable
fun SettingsV2AddProviderUnifiedPreview() {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = InfinityThemeMode.DARK,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        val scheme = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = scheme.onSurface, modifier = Modifier.size(22.dp))
                    Text("添加提供商", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = scheme.onSurface)
                }

                // 常规设置与新手引导共用同一套预设下拉行与徽章体系
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("提供商预设", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                    BettboxListGroup {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ProtoBrandIconTile(R.drawable.ic_provider_openrouter, "openrouter", tileSize = 36.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    itemVerticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("OpenRouter", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
                                    ProtoMicroBadge(ProtoInlineBadge("一键登录", BadgeTone.PRIMARY))
                                    ProtoMicroBadge(ProtoInlineBadge("含免费模型", BadgeTone.SUCCESS))
                                }
                                Text("OpenAI 兼容 · openrouter.ai", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("凭证与授权", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                    BettboxListGroup {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = scheme.primary.copy(alpha = 0.12f),
                                border = BorderStroke(0.8.dp, scheme.primary.copy(alpha = 0.30f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.Login, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("一键授权登录 OpenRouter", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = scheme.onSurface)
                                        Text("浏览器一键授权，自动写入个人 API 密钥", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = scheme.onSurfaceVariant)
                                    }
                                    FilledTonalButton(
                                        onClick = {},
                                        shape = CircleShape,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        modifier = Modifier.height(32.dp),
                                    ) {
                                        Text("登录", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("或手动粘贴 API 密钥", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                                FilledTonalButton(
                                    onClick = {},
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    modifier = Modifier.height(26.dp),
                                ) {
                                    Text("去官网获取", style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.width(3.dp))
                                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, modifier = Modifier.size(12.dp))
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = scheme.surfaceContainerLowest,
                                border = BorderStroke(0.8.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(Icons.Rounded.Key, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                    Text("sk-or-v1-...", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace), color = scheme.onSurfaceVariant)
                                }
                            }
                        }
                        InfinityGroupDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("通过内置代理连接", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
                                Text("中国大陆访问 OpenRouter 建议开启", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            InfinitySwitch(checked = true, onCheckedChange = {})
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {}, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).height(46.dp)) {
                        Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("测试连接")
                    }
                    Button(
                        onClick = {},
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("保存提供商")
                    }
                }
            }
        }
    }
}
