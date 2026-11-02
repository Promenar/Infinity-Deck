package com.promenar.infinity.util

import android.content.res.Resources
import com.promenar.infinity.R
import io.mockk.every
import io.mockk.mockk
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * JVM 单测不打包 Android 资源（未开启 includeAndroidResources），`Resources.getString` 取不到应用字符串。
 * 这里直接解析 `app/src/main/res` 下的字符串 XML，按资源 id 在 [R.string] 中的字段名取值，
 * 指定语言目录缺失的键回退到默认 `values/`，与运行时的资源查找一致。
 */
object XmlStringResources {
    private val idToName: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    /** [qualifier] 为空表示默认语言；例如 `zh-rCN` 对应 `values-zh-rCN/`。 */
    fun resources(qualifier: String? = null): Resources {
        val strings = load("values") + (qualifier?.let { load("values-$it") } ?: emptyMap())
        return mockk {
            every { getString(any<Int>()) } answers {
                val id = firstArg<Int>()
                val name = idToName[id] ?: throw Resources.NotFoundException("String resource ID #0x${id.toString(16)}")
                strings[name] ?: throw Resources.NotFoundException("String resource $name")
            }
        }
    }

    private fun load(dir: String): Map<String, String> {
        val files = File("app/src/main/res/$dir").listFiles { file -> file.extension == "xml" }.orEmpty()
        val factory = DocumentBuilderFactory.newInstance()
        return files.sortedBy { it.name }.flatMap { file ->
            val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { index ->
                val element = nodes.item(index) as Element
                element.getAttribute("name") to unescape(element.textContent)
            }
        }.toMap()
    }

    private fun unescape(raw: String): String =
        raw.trim().removeSurrounding("\"").replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
}
