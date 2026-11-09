package com.promenar.infinity.data.browser

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.util.Locale

class BrowserArgumentException(override val message: String) : IllegalArgumentException(message)

/** 浏览器工具参数校验；只接受 http(s) 网址与本页快照给出的 ref。 */
object BrowserArguments {
    const val MAX_TEXT = 2000
    private val refPattern = Regex("""^e[1-9][0-9]{0,6}$""")

    fun url(args: JsonObject): String {
        val raw = string(args, "url")?.trim()?.takeIf { it.isNotEmpty() } ?: invalid("url must be a non-empty string.")
        // “host:端口”不是协议；其余带协议前缀的原样校验，以便明确拒绝 javascript:、file: 等。
        val withScheme = BrowserUrlInput.withDefaultScheme(raw)
        val uri = runCatching { URI(withScheme) }.getOrElse { invalid("url is not a valid web address.") }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") invalid("Only http and https pages can be opened.")
        if (uri.host.isNullOrBlank()) invalid("url must include a host name.")
        if (uri.rawUserInfo != null) invalid("Web addresses with embedded credentials are not allowed.")
        return uri.toString()
    }

    fun ref(args: JsonObject): String {
        val raw = string(args, "ref")?.trim() ?: invalid("ref must be a string such as e12.")
        if (!refPattern.matches(raw)) invalid("ref must look like e12, taken from the latest browser snapshot.")
        return raw
    }

    fun text(args: JsonObject): String {
        val raw = string(args, "text") ?: invalid("text must be a string.")
        if (raw.length > MAX_TEXT) invalid("text must be at most $MAX_TEXT characters.")
        return raw
    }

    fun submit(args: JsonObject): Boolean = boolean(args, "submit") ?: false

    fun includeText(args: JsonObject): Boolean = boolean(args, "include_text") ?: false

    fun textOffset(args: JsonObject): Int {
        val element = args["text_offset"] ?: return 0
        val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: invalid("text_offset must be an integer.")
        if (value < 0) invalid("text_offset must not be negative.")
        return value
    }

    private fun string(args: JsonObject, field: String): String? =
        (args[field] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun boolean(args: JsonObject, field: String): Boolean? {
        val element = args[field] ?: return null
        return (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: invalid("$field must be a boolean.")
    }

    private fun invalid(message: String): Nothing = throw BrowserArgumentException(message)
}
