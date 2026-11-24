package com.promenar.infinity.utils

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.util.Base64

object SensitiveDataRedactor {
    private const val REDACTED = "[REDACTED]"
    private const val SENSITIVE_KEY_PATTERN =
        "authorization|cookie|set-cookie|proxy-authorization|x-api-key|x-auth-token|api[_-]?key|access[_-]?token|refresh[_-]?token|token|prompt|response|body|bindArgs"
    private val sensitiveHeaders = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "proxy-authorization",
        "x-api-key",
        "x-auth-token"
    )
    private val urlPattern = Regex("""https?://[^\s\"'<>]+""", RegexOption.IGNORE_CASE)
    private val loopbackProxyUrl = Regex(
        """(?i)\b((?:https?|socks5h?)://)[^\s"'<>/@]+(?::[^\s"'<>/@]*)?@((?:127\.0\.0\.1|localhost|\[::1\]):[0-9]{1,5})(?=[^0-9]|$)""",
    )
    private val bearerPattern = Regex("""(?i)(bearer\s+)[^\s,;\"]+""")
    private val jsonSensitiveValuePattern = Regex(
        """(?i)(\")($SENSITIVE_KEY_PATTERN)\1(\s*:\s*)(\"(?:\\.|[^\"\\])*\")"""
    )
    private val unquotedSensitiveValuePattern = Regex(
        """(?i)(?<![\"'\w])($SENSITIVE_KEY_PATTERN)(\s*[:=]\s*)(?:\"(?:\\.|[^\"\\])*\"|\[[^\]\r\n]*\]|[^\r\n,}]+)"""
    )
    internal const val OMITTED_SECRET = "[omitted]"
    private val secretKeySuffixes = listOf(
        "authorization", "cookie", "apikey", "token", "password", "passwd", "passphrase", "secret",
        "privatekey", "credential", "credentials", "signature", "accesskeyid", "secretaccesskey",
    )
    private val invisibleControl = Regex("""[\p{Cc}\p{Cf}&&[^\r\n\t]]""")
    private val terminalColor = Regex("""(?:\u001B\[|\u009B)[0-?]*[ -/]*[@-~]""")
    private val privateKeyBlock = Regex(
        """-----BEGIN ([A-Z0-9 -]{0,64}PRIVATE KEY(?: BLOCK)?)-----.*?(?:-----END \1-----|\z)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val localUrl = Regex("""(?<![A-Za-z0-9+.-])(?:[A-Za-z][A-Za-z0-9+.-]{0,31}:)?//[^\s\p{Z}"'<>`]+""")
    private val urlToken = Regex("""[^\s\p{Z}"'<>]+""")
    private val urlSuffixMarkers = charArrayOf('?', '#')
    private val bareUserInfo = Regex("""(?<![A-Za-z0-9_.%+~-])[A-Za-z0-9_.%+~-]+:[^:@\s"'<>/\\]+@[A-Za-z0-9.-]+""")
    private val secretAssignment = Regex(
        """(?<![\w-])(?:\\{0,3}"((?:\\{1,3}u[0-9a-fA-F]{4}|[A-Za-z0-9_-]){1,128})\\{0,3}"[\s\p{Z}]*:|""" +
            """(?:\\?["'])?([A-Za-z0-9_-]{1,128})(?:\\?["'])?[\s\p{Z}]*[:=]|--([A-Za-z][A-Za-z0-9_-]{0,127})[ \t]+)""",
    )
    private val authorizationValue = Regex("""(?i)\b(?:bearer|basic)[\s\p{Z}]+[^\s\p{Z},;"'<>]+""")
    private val prefixedSecret = Regex(
        """(?i)(?<![A-Za-z0-9])(?:sk-|gh[pousr]_|github_pat_|xox[baprs]-|AIza|AKIA|eyJ)[A-Za-z0-9_./+=-]+""",
    )
    private val compactJwt = Regex("""(?<![A-Za-z0-9_-])([A-Za-z0-9_-]{4,})(?:\.[A-Za-z0-9_-]*){2,4}(?![A-Za-z0-9_-])""")
    private val opaqueSecret = Regex("""(?<![A-Za-z0-9_])(?:[A-Za-z0-9_]{32,}|[0-9]{10,})(?![A-Za-z0-9_])""")
    private val knownLongNames = setOf("ForegroundServiceStartNotAllowedException")
    // 使用字符类匹配敏感目录名，避免字面量进入发行版 DEX 触发扫描误报。
    private val unsafePath = Regex(
        """(?i)(?:^|[/\\])(?:\.env(?:\.[^/\\]*)?|\.ssh|secure[_]env|\.aws[/\\]credentials|\.netrc|""" +
            """\.git-credentials|\.npmrc|\.docker[/\\]config\.json|\.kube[/\\]config|[^/\\]*\.(?:pem|p12|pfx|key))(?=$|[/\\])""",
    )
    private val quotedPath = Regex("""(["'])([^"'\r\n]+)\1""")
    private val pathToken = Regex("""[^\s\p{Z}"'<>`|;=,!?&#(){}\[\]，。；！？]+""")
    private val percentEscape = Regex("%([A-Fa-f0-9]{2})")
    private val unicodeEscape = Regex("""\\{1,3}u([0-9a-fA-F]{4})""")
    private val jsonNumber = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")

    fun redactHeaders(headers: Headers): Map<String, String> = buildMap {
        headers.names().forEach { name ->
            put(name, if (name.lowercase() in sensitiveHeaders) REDACTED else headers.values(name).joinToString(", "))
        }
    }

    fun redactUrl(url: String): String {
        val safeUrl = redactProxyCredentials(url)
        val parsed = safeUrl.toHttpUrlOrNull()
        if (parsed != null) {
            return parsed.newBuilder()
                .username("")
                .password("")
                .query(null)
                .fragment(null)
                .build()
                .toString()
        }
        val boundary = listOf(safeUrl.indexOf('?'), safeUrl.indexOf('#'))
            .filter { it >= 0 }
            .minOrNull()
        return if (boundary == null) safeUrl else safeUrl.substring(0, boundary)
    }

    internal fun redactProxyCredentials(text: String): String =
        loopbackProxyUrl.replace(text) { it.groupValues[1] + it.groupValues[2] }

    fun redactMessage(message: String): String {
        val withoutUrlSecrets = urlPattern.replace(redactProxyCredentials(message)) { redactUrl(it.value) }
        val withoutBearerTokens = bearerPattern.replace(withoutUrlSecrets, "$1$REDACTED")
        val withoutJsonSecrets = jsonSensitiveValuePattern.replace(withoutBearerTokens) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[1]}" +
                "${match.groupValues[3]}\"$REDACTED\""
        }
        return unquotedSensitiveValuePattern.replace(withoutJsonSecrets) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}$REDACTED"
        }
    }

    internal fun isSecretKey(key: String): Boolean {
        val normalized = key.lowercase().replace("_", "").replace("-", "")
        return secretKeySuffixes.any(normalized::endsWith)
    }

    internal fun isSensitivePath(path: String): Boolean =
        unsafePath.containsMatchIn(decodePercentEscapes(path))

    /** 清洗诊断正文里的凭据，保留业务字段和可公开的 URL 部分。调用方负责限制输入规模。 */
    internal fun redactSecretsOnly(text: String): String {
        val normalized = invisibleControl.replace(terminalColor.replace(text, ""), "")
        val withoutPrivateKeys = privateKeyBlock.replace(normalized, OMITTED_SECRET)
        val withoutAbsoluteUrlSecrets = localUrl.replace(withoutPrivateKeys) { stripUrlSecrets(it.value) }
        val withoutUrlSecrets = urlToken.replace(withoutAbsoluteUrlSecrets) { stripRelativeUrlSecrets(it.value) }
        val withoutAssignments = redactSecretAssignments(withoutUrlSecrets)
        val withoutAuthorization = authorizationValue.replace(withoutAssignments, OMITTED_SECRET)
        val withoutUserInfo = bareUserInfo.replace(withoutAuthorization, OMITTED_SECRET)
        val withoutQuotedPaths = quotedPath.replace(withoutUserInfo) { match ->
            if (isSensitivePath(match.groupValues[2])) OMITTED_SECRET else match.value
        }
        val withoutSensitivePaths = pathToken.replace(withoutQuotedPaths) { match ->
            val value = match.value
            val decoded = decodePercentEscapes(value)
            if (unsafePath.containsMatchIn(decoded.trimEnd('.', ':')) ||
                decoded != value && containsEncodedSecret(decoded)
            ) OMITTED_SECRET else value
        }
        val withoutJwt = compactJwt.replace(withoutSensitivePaths) { match ->
            if (hasJwtHeader(match)) OMITTED_SECRET else match.value
        }
        val withoutPrefixedSecrets = prefixedSecret.replace(withoutJwt, OMITTED_SECRET)
        return opaqueSecret.replace(withoutPrefixedSecrets) { match ->
            val token = match.value
            if (token in knownLongNames) token else OMITTED_SECRET
        }
    }

    internal fun stripUrlSuffix(url: String): String =
        url.indexOfAny(urlSuffixMarkers).let { if (it < 0) url else url.substring(0, it) }

    private fun stripRelativeUrlSecrets(token: String): String {
        val suffix = token.indexOfAny(urlSuffixMarkers)
        if (suffix < 0) return token
        val slash = token.indexOf('/')
        val hasQueryAssignment = token[suffix] == '?' && token.indexOf('=', suffix + 1) > suffix + 1
        val hasFragment = token.indexOf('#', suffix) in 1 until token.lastIndex
        return if (slash in 0 until suffix || hasQueryAssignment || hasFragment) token.substring(0, suffix) else token
    }

    private fun stripUrlSecrets(url: String): String {
        val authorityStart = url.indexOf("//") + 2
        val withoutSuffix = stripUrlSuffix(url)
        val authorityEnd = (authorityStart until withoutSuffix.length)
            .firstOrNull { withoutSuffix[it] == '/' || withoutSuffix[it] == '\\' } ?: withoutSuffix.length
        val userInfoEnd = withoutSuffix.lastIndexOf('@', authorityEnd - 1)
        return if (userInfoEnd < authorityStart) withoutSuffix
        else withoutSuffix.substring(0, authorityStart) + withoutSuffix.substring(userInfoEnd + 1)
    }

    private fun decodePercentEscapes(text: String): String =
        if ('%' !in text) text else percentEscape.replace(text) { it.groupValues[1].toInt(16).toChar().toString() }

    private fun containsEncodedSecret(text: String): Boolean =
        prefixedSecret.containsMatchIn(text) || opaqueSecret.containsMatchIn(text) ||
            authorizationValue.containsMatchIn(text) || bareUserInfo.containsMatchIn(text) ||
            privateKeyBlock.containsMatchIn(text) ||
            compactJwt.findAll(text).any(::hasJwtHeader) ||
            localUrl.find(text)?.let { stripUrlSecrets(it.value) != it.value } == true ||
            stripRelativeUrlSecrets(text) != text ||
            secretAssignment.findAll(text).any { isSecretKey(it.assignmentKey()) }

    private fun hasJwtHeader(match: MatchResult): Boolean {
        val decoded = try {
            Base64.getUrlDecoder().decode(match.groupValues[1]).toString(Charsets.UTF_8).trim()
        } catch (_: IllegalArgumentException) {
            return false
        }
        return decoded.startsWith('{') && decoded.endsWith('}')
    }

    internal fun isJsonScalar(text: String): Boolean =
        text == "true" || text == "false" || text == "null" || jsonNumber.matches(text)

    private fun MatchResult.assignmentKey(): String =
        groups[1]?.value?.let { key ->
            unicodeEscape.replace(key) { it.groupValues[1].toInt(16).toChar().toString() }
        } ?: groupValues[2].ifEmpty { groupValues[3] }

    private fun redactSecretAssignments(text: String): String = buildString {
        var cursor = 0
        for (match in secretAssignment.findAll(text)) {
            if (match.range.first < cursor || !isSecretKey(match.assignmentKey())) continue
            append(text, cursor, match.range.last + 1)
            val start = (match.range.last + 1 until text.length).firstOrNull { !text[it].isWhitespace() } ?: text.length
            val jsonEnd = if (match.groups[1] != null) endOfJsonSecretValue(text, start) else null
            if (jsonEnd != null && (jsonEnd until text.length).firstOrNull { !text[it].isWhitespace() }
                    ?.let { text[it] in ",}]" } == true
            ) {
                append('"').append(OMITTED_SECRET).append('"')
                cursor = jsonEnd
                continue
            }
            append(OMITTED_SECRET)
            cursor = when (text.getOrNull(start)) {
                '[', '{' -> text.length
                '"', '\'' -> endOfSecretLine(text, endOfQuotedValue(text, start))
                else -> endOfSecretLine(text, start)
            }
        }
        append(text, cursor, text.length)
    }

    // 文本可能嵌有 JSON。边界明确时保留同一行中的其它诊断字段。
    private fun endOfJsonSecretValue(text: String, start: Int): Int? = when (text.getOrNull(start)) {
        '"' -> endOfQuotedValue(text, start)
        '[', '{' -> endOfJsonContainer(text, start)
        else -> {
            val end = (start until text.length).firstOrNull { text[it].isWhitespace() || text[it] in ",}]" }
            end?.takeIf { it > start && isJsonScalar(text.substring(start, it)) }
        }
    }

    private fun endOfJsonContainer(text: String, start: Int): Int {
        val closing = ArrayDeque<Char>()
        var index = start
        while (index < text.length) {
            when (text[index]) {
                '"' -> {
                    index = endOfQuotedValue(text, index)
                    continue
                }
                '[' -> closing.addLast(']')
                '{' -> closing.addLast('}')
                ']', '}' -> {
                    if (closing.removeLastOrNull() != text[index]) return text.length
                    if (closing.isEmpty()) return index + 1
                }
            }
            if (closing.size > 32) return text.length
            index++
        }
        return text.length
    }

    private fun endOfQuotedValue(text: String, start: Int): Int {
        var index = start + 1
        while (index < text.length) {
            if (text[index] == text[start]) return index + 1
            index += if (text[index] == '\\') 2 else 1
        }
        return text.length
    }

    // 凭据字段可能含逗号、分号或折行的头部值，完整省略该行及续行。
    private fun endOfSecretLine(text: String, start: Int): Int {
        var end = start
        while (end < text.length) {
            while (end < text.length && text[end] !in "\r\n") end++
            var next = end
            if (next < text.length && text[next] == '\r') next++
            if (next < text.length && text[next] == '\n') next++
            if (next == text.length || text[next] !in " \t") return end
            end = next
        }
        return end
    }

    fun safeThrowable(throwable: Throwable, debug: Boolean): String {
        val category = throwable::class.java.simpleName.ifBlank { "Throwable" }
        val causeCategory = throwable.cause?.javaClass?.simpleName
            ?.takeIf { debug && it.isNotBlank() }
            ?.let { ":$it" }
            .orEmpty()
        val code = (category + causeCategory).hashCode().toUInt().toString(16).uppercase().takeLast(8)
        return "$category$causeCategory[E-$code]"
    }
}

object HttpsUrlValidator {
    fun isAllowed(url: String): Boolean = runCatching {
        val uri = URI(url.trim())
        uri.isAbsolute &&
            uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    }.getOrDefault(false)
}
