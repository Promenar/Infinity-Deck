package com.promenar.infinity.recording

private val chinesePunctuation = "，。！？、；：".codePoints().toArray().toSet()
private const val MIN_CORRECTION_PERCENT = 60L

internal fun normalizeRecordingText(text: String): String = buildString {
    var previous: Int? = null
    var space = false
    text.codePoints().forEach { current ->
        if (Character.isWhitespace(current) || Character.isSpaceChar(current)) {
            space = true
        } else {
            val before = previous
            if (space && before != null && before !in chinesePunctuation && current !in chinesePunctuation &&
                !(Character.UnicodeScript.of(before) == Character.UnicodeScript.HAN &&
                    Character.UnicodeScript.of(current) == Character.UnicodeScript.HAN)) append(' ')
            appendCodePoint(current)
            previous = current
            space = false
        }
    }
}

/** 校对保留的有效字符过少时，采用规范化原文，整理游标仍可正常推进。 */
internal fun protectRecordingCorrection(original: String, corrected: String): String {
    fun count(text: String) = text.codePoints().filter {
        Character.isLetterOrDigit(it) || Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN
    }.count()
    val sourceCount = count(original)
    val correctedCount = count(corrected)
    return if (correctedCount * 100 < sourceCount * MIN_CORRECTION_PERCENT) normalizeRecordingText(original) else corrected
}
