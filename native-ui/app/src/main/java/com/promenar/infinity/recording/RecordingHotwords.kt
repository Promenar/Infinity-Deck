package com.promenar.infinity.recording

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale
import java.util.UUID

@Serializable
internal data class RecordingHotword(val id: String, val text: String, val spoken: String = "", val enabled: Boolean = true)

internal data class RecordingHotwordImport(val added: Int, val existing: Int, val invalid: Int)

internal object RecordingHotwordPolicy {
    const val MAX_LIBRARY = 1000
    const val MAX_SESSION = 50
    const val MAX_LENGTH = 50
    private val json = Json { encodeDefaults = true }

    fun normalize(entry: RecordingHotword): RecordingHotword {
        val result = entry.copy(text = entry.text.trim(), spoken = entry.spoken.trim())
        require(result.id.isNotBlank() && result.id.length <= 100 && result.id.none(Char::isISOControl))
        require(result.text.isNotBlank() && result.text.length <= MAX_LENGTH && result.spoken.length <= MAX_LENGTH)
        return result
    }

    fun validate(entries: List<RecordingHotword>): List<RecordingHotword> {
        require(entries.size <= MAX_LIBRARY)
        val clean = entries.map(::normalize)
        require(clean.map { it.id }.distinct().size == clean.size)
        require(clean.map { it.text.lowercase(Locale.ROOT) }.distinct().size == clean.size)
        return clean
    }

    fun encode(entries: List<RecordingHotword>): String = json.encodeToString(validate(entries))
    fun decode(text: String): List<RecordingHotword> = validate(json.decodeFromString<List<RecordingHotword>>(text))

    fun sessionWords(words: List<String>): List<String> = words.map(String::trim).filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }.also {
            require(it.size <= MAX_SESSION && it.all { word -> word.length <= MAX_LENGTH })
        }

    fun splitSession(text: String): List<String> = text.split(Regex("[、,，\\r\\n]+"))
        .map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase(Locale.ROOT) }
        .map { it.take(MAX_LENGTH) }.take(MAX_SESSION)

    fun import(text: String, entries: List<RecordingHotword>): Pair<List<RecordingHotword>, RecordingHotwordImport> {
        val (fresh, result) = planImport(text, entries)
        return (entries + fresh.map { it.copy(id = UUID.randomUUID().toString()) }) to result
    }

    fun previewImport(text: String, entries: List<RecordingHotword>): RecordingHotwordImport =
        planImport(text, entries).second

    private fun planImport(text: String, entries: List<RecordingHotword>): Pair<List<RecordingHotword>, RecordingHotwordImport> {
        val fresh = mutableListOf<RecordingHotword>()
        val names = entries.mapTo(mutableSetOf()) { it.text.lowercase(Locale.ROOT) }
        var existing = 0
        var invalid = 0
        text.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
            val pieces = line.split(Regex("[=＝]"), limit = 3)
            val entry = runCatching {
                require(pieces.size <= 2)
                normalize(RecordingHotword("import", pieces[0], pieces.getOrElse(1) { "" }))
            }.getOrNull()
            when {
                entry == null -> invalid++
                entry.text.lowercase(Locale.ROOT) in names -> existing++
                entries.size + fresh.size >= MAX_LIBRARY -> invalid++
                else -> {
                    fresh.add(entry)
                    names.add(entry.text.lowercase(Locale.ROOT))
                }
            }
        }
        return fresh to RecordingHotwordImport(fresh.size, existing, invalid)
    }
}

/** 文件是热词资料的唯一持久化来源；备份适配器也通过同一原子入口恢复。 */
internal class RecordingHotwordStore(private val file: File) {
    fun read(): List<RecordingHotword> = synchronized(fileLock) {
        if (!file.exists()) emptyList() else {
            require(file.length() <= MAX_FILE_BYTES)
            RecordingHotwordPolicy.decode(file.readText())
        }
    }

    fun delete() = synchronized(fileLock) { check(!file.exists() || file.delete()) }

    fun write(entries: List<RecordingHotword>) = synchronized(fileLock) {
        val bytes = RecordingHotwordPolicy.encode(entries).toByteArray()
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temporary = File(file.parentFile, "${file.name}.new")
        try {
            temporary.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            Unit
        } finally { temporary.delete() }
    }

    companion object {
        const val FILE_NAME = "recording-hotwords.json"
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private val fileLock = Any()
    }
}

internal fun effectiveRecordingHotwords(session: RecordingSession, library: List<RecordingHotword>): List<RecordingHotword> {
    val enabled = if (session.useHotwordLibrary) library.filter { it.enabled } else emptyList()
    val byText = enabled.associateBy { it.text.lowercase(Locale.ROOT) }
    val local = session.hotwords.mapIndexed { index, word ->
        // 本录音的同名词保留热词库已配置的读法，写法采用本录音的大小写。
        byText[word.lowercase(Locale.ROOT)]?.copy(id = "session-$index", text = word)
            ?: RecordingHotword("session-$index", word)
    }
    return (local + enabled).distinctBy { it.text.lowercase(Locale.ROOT) }
}

internal fun recordingHotwordGlossary(entries: List<RecordingHotword>): String = entries.joinToString("、") { it.text }.take(400)

internal fun recordingTokenCharacters(lines: Sequence<String>): Set<Char> = lines.flatMap { line ->
    line.trim().split(Regex("\\s+"), limit = 2).first().removePrefix("▁").asSequence()
}.filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }.toSet()

internal fun unknownRecordingCharacters(text: String, vocabulary: Set<Char>): List<Char> =
    text.filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN && it !in vocabulary }.toList().distinct()

private fun cjk(char: Char): Boolean = when (Character.UnicodeScript.of(char.code)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
    else -> false
}

internal fun recordingRecognizerHotwords(entries: List<RecordingHotword>, vocabulary: Set<Char>): List<String> = entries.mapNotNull { entry ->
    val word = entry.spoken.ifBlank { entry.text }.replace(Regex("[/\\r\\n]"), " ")
    if (unknownRecordingCharacters(word + entry.text, vocabulary).isNotEmpty()) null else buildString {
        word.forEach { char ->
            if (cjk(char)) append(' ').append(char).append(' ') else append(char)
        }
    }.replace(Regex("\\s+"), " ").trim().takeIf(String::isNotBlank)
}.distinct()

/** 合并为一次匹配，避免替换结果被其它读法二次改写。 */
internal class RecordingHotwordReplacer(entries: List<RecordingHotword>) {
    private val replacements = entries.filter { it.spoken.isNotBlank() && it.spoken != it.text }
        .sortedByDescending { it.spoken.replace(Regex("\\s+"), "").length }
    private val regex = replacements.takeIf { it.isNotEmpty() }?.joinToString("|") { "(${recordingWordPattern(it.spoken.trim(), flexibleSpaces = true)})" }
        ?.let { Regex(it, RegexOption.IGNORE_CASE) }

    fun replace(text: String): String = regex?.replace(text) { match ->
        replacements[match.groups.drop(1).indexOfFirst { it != null }].text
    } ?: text
}

/** 拉丁词按完整字母数字片段匹配，中文词可出现在连续句子中。 */
private fun recordingWordPattern(word: String, flexibleSpaces: Boolean): String {
    val body = if (flexibleSpaces) word.split(Regex("\\s+")).joinToString("\\s*") { Regex.escape(it) } else Regex.escape(word)
    fun latin(char: Char) = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'
    return (if (latin(word.first())) "(?<![A-Za-z0-9])" else "") + body +
        (if (latin(word.last())) "(?![A-Za-z0-9])" else "")
}

internal fun recordingHotwordPattern(entries: List<RecordingHotword>): Regex? {
    val words = entries.map { it.text.trim() }.filter(String::isNotEmpty).sortedByDescending(String::length)
    return words.takeIf(List<String>::isNotEmpty)?.joinToString("|") { recordingWordPattern(it, flexibleSpaces = false) }
        ?.let { Regex(it, RegexOption.IGNORE_CASE) }
}
