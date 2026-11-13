package com.promenar.infinity.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTextTest {
    @Test fun normalizesBpeSpacingWithoutJoiningEnglishWords() {
        val cases = listOf(
            "首先， 采集线程需要和识别线程分开， 这样写盘" to "首先，采集线程需要和识别线程分开，这样写盘",
            "比如 Android、 Kotlin 还有 GitHub 这些词" to "比如 Android、Kotlin 还有 GitHub 这些词",
            "  Hello   Android  world!  " to "Hello Android world!",
            " \t中  文\n测试 ， 好 。 是 ！ 对 ？ 甲 、 乙 ； 丙 ： 丁  " to "中文测试，好。是！对？甲、乙；丙：丁",
            "中文   Android\t123  中文" to "中文 Android 123 中文",
            "𠀀 𠀁" to "𠀀𠀁",
            " \n " to "",
        )
        cases.forEach { (input, expected) -> assertEquals(expected, normalizeRecordingText(input)) }
    }

    @Test fun rejectsSubstantialContentLossAndNormalizesTheSource() {
        assertEquals("一二三四五六七八九十", protectRecordingCorrection("一 二 三 四 五 六 七 八 九 十", "一二三四五"))
        assertEquals("一二三四五六七八", protectRecordingCorrection("嗯一二三四五六七八啊", "一二三四五六七八"))
        assertEquals("123abc", protectRecordingCorrection("123456abcd", "123abc"))
        assertEquals("123456abcd", protectRecordingCorrection("123456abcd", "123ab！？，。"))
        assertEquals("完整原文", protectRecordingCorrection("完整原文", ""))
        assertEquals("𠀀𠀁𠀂𠀃𠀄", protectRecordingCorrection("𠀀𠀁𠀂𠀃𠀄", "𠀀𠀁"))
        assertEquals("〇一二三", protectRecordingCorrection("〇一二三", "一二"))
        assertEquals("校对结果", protectRecordingCorrection("", "校对结果"))
        assertEquals("", protectRecordingCorrection("", ""))
    }
}
