package com.promenar.infinity.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.promenar.infinity.R

val InfinitySans = FontFamily.SansSerif

/**
 * 终端、命令输出、代码块与标识符统一使用打包的 JetBrains Mono NL（无连字，SIL OFL 1.1）。
 * 厂商字体主题会替换系统 monospace，导致列宽不齐；CJK 等缺失字形仍回落系统字体。
 */
val InfinityMonospace = FontFamily(
    Font(R.font.jetbrains_mono_nl_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_nl_bold, FontWeight.Bold),
)

/** View 与 Paint 绘制（终端渲染器、文字墨迹测量）使用的同一字体；粗体由渲染器合成。 */
fun infinityMonospaceTypeface(context: Context): Typeface =
    ResourcesCompat.getFont(context, R.font.jetbrains_mono_nl_regular) ?: Typeface.MONOSPACE

val Manrope = InfinitySans
val Inter = InfinitySans
val SpaceGrotesk = InfinityMonospace

/**
 * 换行策略分三类：
 * - 界面文本（标题、标签、列表与说明）使用 [InfinityUiLineBreak]：优先排满行宽并遵守严格 CJK 避头尾规则，
 *   避免首行提前折行留白与行首标点；
 * - 阅读文本（聊天正文、Markdown、文档与检索内容）使用 [InfinityReadingLineBreak]，保证长段落排版质量与性能；
 * - 输入框使用 [InfinityInputLineBreak]，编辑时不触发整段回流；
 * - 文件名、URL、路径与模型 ID 等标识符先经 [breakableIdentifier] 在分隔符后提供断点，避免在单词中间断开。
 * 未设置时 Android 回落到贪心换行且中文可在任意字间断开，因此每个角色都必须显式声明。
 */
val InfinityUiLineBreak: LineBreak = LineBreak(
    strategy = LineBreak.Strategy.HighQuality,
    // 严格 CJK 规则：问号、句号、右引号等不得出现在行首（Heading 预设为 Loose，会放宽避头点）。
    strictness = LineBreak.Strictness.Strict,
    wordBreak = LineBreak.WordBreak.Default,
)
val InfinityReadingLineBreak: LineBreak = LineBreak.Paragraph
val InfinityInputLineBreak: LineBreak = LineBreak.Simple

/** 阅读场景样式：保留字体与行高，仅切换为段落换行策略。 */
fun TextStyle.forReading(): TextStyle = copy(lineBreak = InfinityReadingLineBreak)

/** 输入框样式：保留字体与行高，仅切换为输入换行策略。 */
fun TextStyle.forInput(): TextStyle = copy(lineBreak = InfinityInputLineBreak)

private val MaterialDefaults = Typography()

private fun TextStyle.ui(): TextStyle = copy(lineBreak = InfinityUiLineBreak)

val InfinityTypography = Typography(
    displayLarge = MaterialDefaults.displayLarge.ui(),
    displayMedium = MaterialDefaults.displayMedium.ui(),
    displaySmall = MaterialDefaults.displaySmall.ui(),
    headlineLarge = TextStyle(
        fontFamily = InfinitySans,
        fontSize = 24.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        lineBreak = InfinityUiLineBreak,
    ),
    headlineMedium = TextStyle(
        fontFamily = InfinitySans,
        fontSize = 20.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        lineBreak = InfinityUiLineBreak,
    ),
    headlineSmall = MaterialDefaults.headlineSmall.ui(),
    titleLarge = MaterialDefaults.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal).ui(),
    titleMedium = MaterialDefaults.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium).ui(),
    titleSmall = MaterialDefaults.titleSmall.ui(),
    bodyLarge = TextStyle(
        fontFamily = InfinitySans,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        lineBreak = InfinityUiLineBreak,
    ),
    bodyMedium = TextStyle(
        fontFamily = InfinitySans,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.01.em,
        fontWeight = FontWeight.Normal,
        lineBreak = InfinityUiLineBreak,
    ),
    bodySmall = TextStyle(
        fontFamily = InfinitySans,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        lineBreak = InfinityUiLineBreak,
    ),
    labelLarge = MaterialDefaults.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium).ui(),
    labelMedium = TextStyle(
        fontFamily = InfinitySans,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.02.sp,
        fontWeight = FontWeight.Medium,
        lineBreak = InfinityUiLineBreak,
    ),
    labelSmall = MaterialDefaults.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium).ui(),
)

val bodyMediumCJK = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 15.sp,
    lineHeight = 26.sp,
    letterSpacing = 0.01.em,
    fontWeight = FontWeight.Normal,
    lineBreak = InfinityReadingLineBreak,
)

@Composable
fun chatTypography(fontSize: Int): Typography {
    val base = fontSize.sp
    // 聊天正文属于阅读场景：正文角色使用段落换行，标签类仍沿用界面换行。
    return InfinityTypography.copy(
        bodyLarge = InfinityTypography.bodyLarge.forReading(),
        bodyMedium = InfinityTypography.bodyMedium.copy(
            fontSize = base,
            lineHeight = (fontSize * 1.5).sp,
            lineBreak = InfinityReadingLineBreak,
        ),
        bodySmall = InfinityTypography.bodySmall.copy(
            fontSize = (fontSize - 1).sp,
            lineHeight = ((fontSize - 1) * 1.4).sp,
            lineBreak = InfinityReadingLineBreak,
        ),
        labelSmall = InfinityTypography.labelSmall.copy(
            fontSize = (fontSize - 2).sp
        ),
        labelMedium = InfinityTypography.labelMedium.copy(
            fontSize = (fontSize - 1).sp
        ),
    )
}

/**
 * 标识符可断点：在 `/` 之后，以及 `- _ . : ? & = @` 后紧跟字母时插入零宽空格，换行只发生在分隔符处；
 * 版本号（如 `3.1`、`v2-5`）内部不插入断点。仅用于显示，不用于复制或比较。
 */
fun String.breakableIdentifier(): String = buildString(length + length / 4) {
    val source = this@breakableIdentifier
    source.forEachIndexed { index, char ->
        append(char)
        val next = source.getOrNull(index + 1)
        if (next != null && (char == '/' || char in IDENTIFIER_BREAK_BEFORE_LETTER && next.isLetter())) {
            append('\u200B')
        }
    }
}

private const val IDENTIFIER_BREAK_BEFORE_LETTER = "-_.:?&=@"

/**
 * 末字连写与数字单位绑定：
 * 1. 将“数字 + 空格 + 单位/量词”（以及 `1440px 宽`）中的普通空格替换为不换行空格（U+00A0），避免数值与单位被拆成两行；
 * 2. 在句末标点（`。！？；`）前及整段末尾最后两个相邻的中日韩文字之间插入词连接符（U+2060），自动折行时句尾至少保留两个字，
 * 避免段落策略在大字号窄屏下留下单字末行。末尾标点与闭合引号/括号不计入，幂等且不影响 ASCII 标识符；仅用于显示，不得用于比较或存储。
 */
fun String.keepCjkTailTogether(): String {
    if (length < 2) return this
    val boundUnits = NUMBER_UNIT_SPACE_REGEX.replace(this) { match ->
        val token = match.groupValues[1].ifEmpty { match.groupValues[2] }
        "$token\u00A0"
    }
    val sb = StringBuilder(boundUnits)
    var i = 2
    while (i < sb.length) {
        val ch = sb[i]
        if (ch == '。' || ch == '！' || ch == '？' || ch == '；') {
            var prev = i - 1
            while (prev > 0 && sb[prev].isClosingQuoteOrBracket()) prev--
            if (prev >= 1 && sb[prev].isCjkGlyph() && sb[prev - 1].isCjkGlyph()) {
                sb.insert(prev, WORD_JOINER)
                i += 2
                continue
            }
        }
        i++
    }
    var end = sb.length - 1
    while (end >= 0 && (sb[end].isWhitespace() || sb[end].isPunctuationLike())) end--
    if (end >= 1 && sb[end].isCjkGlyph() && sb[end - 1].isCjkGlyph()) {
        sb.insert(end, WORD_JOINER)
    }
    return sb.toString()
}

private const val WORD_JOINER = '\u2060'

private val NUMBER_UNIT_SPACE_REGEX = Regex(
    """(\d+(?:,\d{3})*(?:\.\d+)?) (?=(?:kB|KB|MB|GB|TB|B|ms|s|px|dp|sp|%|％|秒|分钟|分|小时|天|页|张|条|轮|段|行|个|项|次|步|位)(?![A-Za-z]))|(\d+(?:,\d{3})*(?:\.\d+)?(?:px|dp|sp)) (?=宽)""",
)

private fun Char.isClosingQuoteOrBracket(): Boolean =
    this == '”' || this == '’' || this == '」' || this == '』' ||
        this == '）' || this == ')' || this == '】' || this == ']' || this == '》'

private fun Char.isCjkGlyph(): Boolean = when (Character.UnicodeScript.of(code)) {
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL,
    -> true
    else -> false
}

private fun Char.isPunctuationLike(): Boolean = when (Character.getType(this).toByte()) {
    Character.CONNECTOR_PUNCTUATION,
    Character.DASH_PUNCTUATION,
    Character.START_PUNCTUATION,
    Character.END_PUNCTUATION,
    Character.INITIAL_QUOTE_PUNCTUATION,
    Character.FINAL_QUOTE_PUNCTUATION,
    Character.OTHER_PUNCTUATION,
    -> true
    else -> false
}
