package com.promenar.infinity.data.rag

import java.math.BigInteger
import java.util.Locale

/** 按文件名中的阿拉伯数字与章节中文数字自然排序。 */
object KgNaturalOrder : Comparator<String> {
    private val number = Regex("[0-9]+|(?<=第)[零〇一二两三四五六七八九十百千万]+(?=[章节回卷篇部集])|(?<=[卷章节回篇部集])[零〇一二两三四五六七八九十百千万]+")
    private val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
        '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)

    fun chapterNumber(text: String): Pair<BigInteger, String>? {
        return chapterNumbers(text).firstOrNull()
    }

    fun chapterNumbers(text: String): List<Pair<BigInteger, String>> =
        Regex("第([0-9零〇一二两三四五六七八九十百千万]+)(章|卷)").findAll(text)
            .map { value(it.groupValues[1]) to it.groupValues[2] }.toList() +
            Regex("卷([0-9零〇一二两三四五六七八九十百千万]+)").findAll(text).map { value(it.groupValues[1]) to "卷" }.toList()

    override fun compare(a: String, b: String): Int {
        val left = number.findAll(a).toList()
        val right = number.findAll(b).toList()
        if (left.isEmpty() != right.isEmpty()) return if (left.isEmpty()) 1 else -1
        var ai = 0
        var bi = 0
        for (index in 0 until minOf(left.size, right.size)) {
            val x = left[index]
            val y = right[index]
            val text = a.substring(ai, x.range.first).lowercase(Locale.ROOT)
                .compareTo(b.substring(bi, y.range.first).lowercase(Locale.ROOT))
            if (text != 0) return text
            val numeric = value(x.value).compareTo(value(y.value))
            if (numeric != 0) return numeric
            ai = x.range.last + 1
            bi = y.range.last + 1
        }
        val suffix = a.substring(ai).lowercase(Locale.ROOT).compareTo(b.substring(bi).lowercase(Locale.ROOT))
        return if (suffix != 0) suffix else a.compareTo(b)
    }

    private fun value(text: String): BigInteger {
        if (text.first().isDigit()) return BigInteger(text)
        if (text.none { it in "十百千万" }) return BigInteger(text.map { digits[it] ?: 0 }.joinToString(""))
        var total = 0L
        var section = 0L
        var digit = 0L
        text.forEach { ch ->
            when (ch) {
                '十', '百', '千' -> {
                    val unit = when (ch) { '十' -> 10; '百' -> 100; else -> 1000 }
                    section += (if (digit == 0L) 1L else digit) * unit
                    digit = 0
                }
                '万' -> { total += (section + digit).coerceAtLeast(1) * 10000; section = 0; digit = 0 }
                else -> digit = (digits[ch] ?: 0).toLong()
            }
        }
        return BigInteger.valueOf(total + section + digit)
    }
}

data class KgTimelinePosition(val order: Int, val windowIndex: Int, val charStart: Int) : Comparable<KgTimelinePosition> {
    override fun compareTo(other: KgTimelinePosition): Int =
        compareValuesBy(this, other, KgTimelinePosition::order, KgTimelinePosition::windowIndex, KgTimelinePosition::charStart)
}

data class KgNarrativeMention(
    val edgeId: String,
    val collectionId: String,
    val sourceId: String,
    val targetId: String,
    val kind: String,
    val family: String?,
    val docId: String,
    val order: Int,
    val windowIndex: Int,
    val charStart: Int,
    val flashback: Boolean = false,
    val charEnd: Int = charStart,
    val quote: String? = null,
    val positionCoarse: Boolean = false,
) {
    val position get() = KgTimelinePosition(order, windowIndex, charStart)
}

data class KgTimelineSlot(val collectionId: String, val sourceId: String, val targetId: String?, val family: String)
data class KgTimelineInterval(val edgeId: String, val slot: KgTimelineSlot, val from: KgTimelinePosition, val toExclusive: KgTimelinePosition?)
data class KgNarrativeMaterialized(
    val intervals: List<KgTimelineInterval>,
    val events: List<KgNarrativeMention>,
    val flashbacks: List<KgNarrativeMention>,
)
data class KgTimelineState(val edgeId: String, val interval: KgTimelineInterval, val history: List<KgTimelineInterval>, val flashbacks: List<KgNarrativeMention>)

/** 读取顺序决定状态变化；此运算不依赖网络或模型。 */
object KgNarrativeTimeline {
    val families = setOf("relationship", "location", "allegiance", "role", "life_status", "possession", "other_state")

    fun slot(mention: KgNarrativeMention): KgTimelineSlot? {
        val family = mention.family?.takeIf { it in families } ?: return null
        return if (family == "relationship") {
            val (first, second) = listOf(mention.sourceId, mention.targetId).sorted()
            KgTimelineSlot(mention.collectionId, first, second, family)
        } else KgTimelineSlot(mention.collectionId, if (family == "possession") mention.targetId else mention.sourceId, null, family)
    }

    fun materialize(mentions: List<KgNarrativeMention>): KgNarrativeMaterialized {
        val states = mentions.filter { it.kind == "state" && !it.flashback && slot(it) != null }.groupBy { slot(it)!! }
        val intervals = mutableListOf<KgTimelineInterval>()
        states.forEach { (key, rows) ->
            var active: KgTimelineInterval? = null
            rows.sortedWith(compareBy(KgNarrativeMention::position, KgNarrativeMention::edgeId)).forEach { row ->
                val prior = active
                if (prior == null) active = KgTimelineInterval(row.edgeId, key, row.position, null)
                else if (prior.edgeId != row.edgeId) {
                    intervals += prior.copy(toExclusive = row.position)
                    active = KgTimelineInterval(row.edgeId, key, row.position, null)
                }
            }
            active?.let(intervals::add)
        }
        return KgNarrativeMaterialized(intervals, mentions.filter { it.kind == "event" && !it.flashback }, mentions.filter { it.flashback })
    }

    fun at(timeline: KgNarrativeMaterialized, order: Int, historyLimit: Int = 3): List<KgTimelineState> {
        require(historyLimit >= 0)
        val point = KgTimelinePosition(order, Int.MAX_VALUE, Int.MAX_VALUE)
        return timeline.intervals.filter { it.from <= point && (it.toExclusive == null || point < it.toExclusive) }
            .map { interval ->
                val history = timeline.intervals.filter { it.slot == interval.slot && it.toExclusive != null && it.toExclusive <= point }
                    .sortedByDescending { it.from }.take(historyLimit)
                val flashbacks = timeline.flashbacks.filter { it.position <= point && slot(it) == interval.slot }.sortedByDescending { it.position }
                KgTimelineState(interval.edgeId, interval, history, flashbacks)
            }.sortedWith(compareBy({ it.interval.slot.collectionId }, { it.interval.slot.sourceId }, { it.interval.slot.family }))
    }
}
