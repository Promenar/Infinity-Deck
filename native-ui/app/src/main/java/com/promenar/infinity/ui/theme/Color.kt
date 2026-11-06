package com.promenar.infinity.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable

/**
 * 冰蓝：默认主色的种子。目标是柔和风格下深色主色接近 #9FD3FF、浅色主色与白字对比度满足 WCAG AA。
 * 待主控验证的候选值：与 #9FD3FF 同色相（HSV 约 207°），未经构建实测；生成结果以 SeededColorSchemeStyleTest 的断言为准。
 */
val InfinityIceBlueSeed = Color(0xFF3D9BE9)

/** 内置淡紫配色的种子：用户选“默认配色”且配色风格不是柔和时，按该风格以它生成配色。 */
internal val InfinityIndigoSeed = Color(0xFF6366F1)

/** 预设主色，冰蓝排第一且是默认主色。 */
val InfinityPresetColors = listOf(
    InfinityIceBlueSeed,
    InfinityIndigoSeed,
    Color(0xFFF43F5E),
    Color(0xFF10B981),
    Color(0xFFF59E0B),
    Color(0xFF06B6D4),
    Color(0xFF8B5CF6),
    Color(0xFFF97316),
    Color(0xFF14B8A6),
    Color(0xFFD946EF),
    Color(0xFF0EA5E9),
)

@Immutable
data class InfinityDomainColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val overlayContent: Color,
    val ragReady: Color,
    val ragIndexing: Color,
    val ragError: Color,
    val ragPending: Color,
    val codeKeyword: Color,
    val codeDeclaration: Color,
    val codeLiteral: Color,
    val codeFunction: Color,
    val codeString: Color,
    val codeComment: Color,
    /** 网络指示灯的红黄绿：不随动态取色变化，已连接 / 连接中或受限 / 未连接一眼可辨。 */
    val signalOn: Color,
    val signalPending: Color,
    val signalDown: Color,
    /** 深色模式：浮岛质感与光效在深浅模式下取不同的材质与色阶。 */
    val isDark: Boolean,
)

// 深色背景用更亮的色阶，浅色背景用饱和度更高的色阶，两种模式下灯都显得“亮着”。
internal val InfinityDarkSignalOn = Color(0xFF4ADE80)
internal val InfinityDarkSignalPending = Color(0xFFFACC15)
internal val InfinityDarkSignalDown = Color(0xFFF87171)
internal val InfinityLightSignalOn = Color(0xFF22C55E)
internal val InfinityLightSignalPending = Color(0xFFF59E0B)
internal val InfinityLightSignalDown = Color(0xFFEF4444)

// Agent 浏览器在网页上标出元素的描边色：网页底色不随应用主题变化，取在白底与暗底上都醒目的固定色。
val InfinityBrowserAgentHighlight = Color(0xFF4548C7)
val InfinityBrowserHandoffHighlight = Color(0xFFC25E00)

// 触摸锁全屏遮罩：手机放进口袋时的纯黑底，文字与强调色取暗底上的浅色，不随应用主题变化。
val InfinityTouchLockScrim = Color(0xFF000000)
val InfinityTouchLockContent = Color(0xFFE8E6DF)
val InfinityTouchLockAccent = Color(0xFFAFA9EC)
val InfinityTouchLockWarning = Color(0xFFFAC775)

// 颜色运算端点：取色器的明度/饱和度渐变、指示灯提亮与面板压暗都以纯白、纯黑为端点，不随主题变化。
internal val InfinityPureWhite = Color(0xFFFFFFFF)
internal val InfinityPureBlack = Color(0xFF000000)

/** 引导页星空的星色：冷白为主，少量偏暖。 */
internal val InfinityStarCold = Color(0xFFE9E8FF)
internal val InfinityStarWarm = Color(0xFFFFD6AA)
/** 浅色主题下 LED 面板底板：极淡的墨绿灰，衬出熄灭的灯位。 */
internal val InfinityLedFaceplateLight = Color(0x12141E19)

internal val InfinityDarkDomainColors = InfinityDomainColors(
    success = Color(0xFF4ADE80),
    onSuccess = Color(0xFF003914),
    successContainer = Color(0xFF005322),
    onSuccessContainer = Color(0xFF7BFA9F),
    warning = Color(0xFFFFB95F),
    onWarning = Color(0xFF472A00),
    warningContainer = Color(0xFF654000),
    onWarningContainer = Color(0xFFFFDDB5),
    info = Color(0xFFAFC6FF),
    onInfo = Color(0xFF002E69),
    infoContainer = Color(0xFF17457F),
    onInfoContainer = Color(0xFFD8E2FF),
    overlayContent = Color(0xFFFFFFFF),
    ragReady = Color(0xFF4ADE80),
    ragIndexing = Color(0xFF60A5FA),
    ragError = Color(0xFFF87171),
    ragPending = Color(0xFF9CA3AF),
    codeKeyword = Color(0xFFD8B4FE),
    codeDeclaration = Color(0xFF93C5FD),
    codeLiteral = Color(0xFFFDE68A),
    codeFunction = Color(0xFF6EE7B7),
    codeString = Color(0xFFBAE6FD),
    codeComment = Color(0xFF9CA3AF),
    signalOn = InfinityDarkSignalOn,
    signalPending = InfinityDarkSignalPending,
    signalDown = InfinityDarkSignalDown,
    isDark = true,
)

internal val InfinityLightDomainColors = InfinityDomainColors(
    success = Color(0xFF146C2E),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFA2F6B6),
    onSuccessContainer = Color(0xFF002108),
    warning = Color(0xFF825500),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDDB5),
    onWarningContainer = Color(0xFF2A1800),
    info = Color(0xFF385D98),
    onInfo = Color(0xFFFFFFFF),
    infoContainer = Color(0xFFD8E2FF),
    onInfoContainer = Color(0xFF001A41),
    overlayContent = Color(0xFFFFFFFF),
    ragReady = Color(0xFF146C2E),
    ragIndexing = Color(0xFF385D98),
    ragError = Color(0xFFBA1A1A),
    ragPending = Color(0xFF5F6368),
    codeKeyword = Color(0xFF7E22CE),
    codeDeclaration = Color(0xFF1D4ED8),
    codeLiteral = Color(0xFF92400E),
    codeFunction = Color(0xFF047857),
    codeString = Color(0xFF075985),
    codeComment = Color(0xFF5F6368),
    signalOn = InfinityLightSignalOn,
    signalPending = InfinityLightSignalPending,
    signalDown = InfinityLightSignalDown,
    isDark = false,
)


internal val InfinityDarkColorScheme = darkColorScheme(
    primary = Color(0xFFC0C1FF),
    onPrimary = Color(0xFF1000A9),
    primaryContainer = Color(0xFF8083FF),
    onPrimaryContainer = Color(0xFF0D0096),
    inversePrimary = Color(0xFF494BD6),
    secondary = Color(0xFFC8C5CA),
    onSecondary = Color(0xFF303033),
    secondaryContainer = Color(0xFF2A2A2C),
    onSecondaryContainer = Color(0xFFE5E1E4),
    tertiary = Color(0xFFFFB783),
    onTertiary = Color(0xFF4F2500),
    tertiaryContainer = Color(0xFFD97721),
    onTertiaryContainer = Color(0xFF452000),
    background = Color(0xFF131315),
    onBackground = Color(0xFFE5E1E4),
    surface = Color(0xFF131315),
    onSurface = Color(0xFFE5E1E4),
    surfaceDim = Color(0xFF131315),
    surfaceBright = Color(0xFF39393B),
    surfaceContainerLowest = Color(0xFF0E0E10),
    surfaceContainerLow = Color(0xFF1C1B1D),
    surfaceContainer = Color(0xFF201F22),
    surfaceContainerHigh = Color(0xFF2A2A2C),
    surfaceContainerHighest = Color(0xFF353437),
    surfaceVariant = Color(0xFF353437),
    onSurfaceVariant = Color(0xFFC7C4D7),
    outline = Color(0xFF908FA0),
    outlineVariant = Color(0xFF464554),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceTint = Color(0xFFC0C1FF),
    inverseSurface = Color(0xFFE5E1E4),
    inverseOnSurface = Color(0xFF313032),
)

internal val InfinityLightColorScheme = lightColorScheme(
    primary = Color(0xFF4548C7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE0FF),
    onPrimaryContainer = Color(0xFF00006E),
    inversePrimary = Color(0xFFC0C1FF),
    secondary = Color(0xFF5D5D67),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E1EC),
    onSecondaryContainer = Color(0xFF1A1A22),
    tertiary = Color(0xFF8E4E00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC5),
    onTertiaryContainer = Color(0xFF2E1500),
    background = Color(0xFFFBF8FB),
    onBackground = Color(0xFF1B1B1E),
    surface = Color(0xFFFBF8FB),
    onSurface = Color(0xFF1B1B1E),
    surfaceDim = Color(0xFFDBD9DC),
    surfaceBright = Color(0xFFFBF8FB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2F5),
    surfaceContainer = Color(0xFFEFECEF),
    surfaceContainerHigh = Color(0xFFE9E7E9),
    surfaceContainerHighest = Color(0xFFE3E1E3),
    surfaceVariant = Color(0xFFE4E1EB),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C5D0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceTint = Color(0xFF4548C7),
    inverseSurface = Color(0xFF303033),
    inverseOnSurface = Color(0xFFF3F0F3),
)

/** 终端与命令输出的 ANSI 16 色（深色底）：红、绿、黄、蓝对齐应用错误、成功、警示与主色，黑色提亮以便可读。 */
val InfinityAnsiDark = listOf(
    Color(0xFF6E6C75), Color(0xFFFFB4AB), Color(0xFF7DD3A0), Color(0xFFF2C14E),
    Color(0xFFC0C1FF), Color(0xFFE8B4F8), Color(0xFF8FD6E8), Color(0xFFE5E1E4),
    Color(0xFF8B8A99), Color(0xFFFFDAD6), Color(0xFFA8E8C0), Color(0xFFFFDF8E),
    Color(0xFFE1E0FF), Color(0xFFF6D9FF), Color(0xFFC0F0FF), Color(0xFFFFFFFF),
)

/** 终端与命令输出的 ANSI 16 色（浅色底）：各色加深以保持对比度，白色映射为深灰。 */
val InfinityAnsiLight = listOf(
    Color(0xFF1C1B1F), Color(0xFFBA1A1A), Color(0xFF1E7A4C), Color(0xFF7A5900),
    Color(0xFF4A4BC8), Color(0xFF8E3FA8), Color(0xFF00687A), Color(0xFF5F5E66),
    Color(0xFF47464F), Color(0xFF93000A), Color(0xFF00522F), Color(0xFF5B4300),
    Color(0xFF2F2FA8), Color(0xFF6C2085), Color(0xFF004E5C), Color(0xFF1C1B1F),
)

/**
 * 知识图谱实体类型色（深色底），顺序：人物、组织、地点、事件、物品、概念、文档、其它。
 * 节点为同一圆形，靠这组颜色与内部图标区分类型；彼此色相拉开，在 16% 底色与描边下都可辨。
 */
val InfinityKgEntityDark = listOf(
    Color(0xFFF6A08F), Color(0xFF81C995), Color(0xFF5CC8C8), Color(0xFFF48FB1),
    Color(0xFF7CACF8), Color(0xFFC58AF9), Color(0xFFF2C75C), Color(0xFFA8B0BE),
)

/** 知识图谱实体类型色（浅色底）：同一色相加深，保证描边、图标与文字对比度。 */
val InfinityKgEntityLight = listOf(
    Color(0xFFC4472B), Color(0xFF1E8E3E), Color(0xFF00838F), Color(0xFFC2185B),
    Color(0xFF1A73E8), Color(0xFF8E3FD1), Color(0xFF9A6A00), Color(0xFF5F6673),
)

/** 生成头像（渐变首字母）的固定配色：渐变两端都在中等 tone，首字母恒为白字加淡投影，左上角是白色柔光。 */
val InfinityAvatarInitialInk = Color.White
val InfinityAvatarInitialShadow = Color.Black.copy(alpha = 0.18f)
val InfinityAvatarHighlight = Color.White.copy(alpha = 0.35f)

/** 渐隐遮罩（BlendMode.DstIn）只取 alpha：不透明处保留内容、透明处擦除，与主题无关。 */
val InfinityFadeMaskOpaque = Color.Black
