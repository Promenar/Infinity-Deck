package com.promenar.infinity.data.local.db

import java.text.Normalizer
import java.util.Locale

/** 规范化实体名称与类型；实体身份由所属图谱和归一化结果共同确定。 */
object KgIdentity {
    fun display(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().replace(Regex("[\\s\\p{Z}]+"), " ")

    /** 逐码点展开大小写映射，保护 dotless-i，Cherokee 采用其规定的大写折叠形式。 */
    fun normalize(value: String): String = buildString {
        display(value).codePoints().forEach { code ->
            val scalar = String(Character.toChars(code))
            append(when {
                code == 0x0131 -> scalar
                code in 0x13A0..0x13F5 || code in 0x13F8..0x13FD || code in 0xAB70..0xABBF -> scalar.uppercase(Locale.ROOT)
                else -> scalar.uppercase(Locale.ROOT).lowercase(Locale.ROOT)
            })
        }
    }.replace("ß", "ss").replace('ς', 'σ')
}
