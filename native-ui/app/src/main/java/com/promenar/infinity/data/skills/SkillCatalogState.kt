package com.promenar.infinity.data.skills

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class ProjectSkillKey(val workspace: String, val name: String)

internal data class SkillCatalogState(
    val userConfirmed: Map<String, String> = emptyMap(),
    val projectTrusted: Map<ProjectSkillKey, String> = emptyMap(),
    val projectDisabled: Set<ProjectSkillKey> = emptySet(),
    val userDisabled: Set<String> = emptySet(),
)

/** 私有状态整份校验，损坏或未知版本不产生任何信任。 */
internal class SkillCatalogStateStore(private val root: File, private val files: SkillCatalogFiles) {
    private val file get() = File(root, "catalog.json")

    fun read(): SkillCatalogState = runCatching {
        if (Files.isSymbolicLink(root.toPath()) || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) {
            return@runCatching SkillCatalogState()
        }
        val text = AgentSkillStore.decodeText(files.read(file, Int.MAX_VALUE, root)) ?: error("状态不是 UTF-8")
        val document = Json.decodeFromString<StateDocument>(text)
        check(document.version == 1)
        document.userConfirmed.forEach { (name, hash) -> validate(name, hash) }
        val trusted = document.projectTrusted.map { record ->
            validate(record.name, record.hash)
            ProjectSkillKey(record.workspace, record.name) to record.hash
        }
        val disabled = document.projectDisabled.map { record ->
            SkillMarkdownParser.validateName(record.name)
            ProjectSkillKey(record.workspace, record.name)
        }
        check(trusted.map { it.first }.distinct().size == trusted.size && disabled.distinct().size == disabled.size)
        document.userDisabled.forEach(SkillMarkdownParser::validateName)
        SkillCatalogState(document.userConfirmed, trusted.toMap(), disabled.toSet(), document.userDisabled)
    }.getOrElse { SkillCatalogState() }

    fun write(state: SkillCatalogState) {
        check(!Files.isSymbolicLink(root.toPath()) && (root.isDirectory || root.mkdirs())) { "无法创建技能状态目录" }
        val order = compareBy<ProjectSkillKey>(ProjectSkillKey::workspace, ProjectSkillKey::name)
        val document = StateDocument(
            version = 1,
            userConfirmed = state.userConfirmed.toSortedMap(),
            userDisabled = state.userDisabled.toSortedSet(),
            projectTrusted = state.projectTrusted.keys.sortedWith(order).map {
                TrustedRecord(it.workspace, it.name, state.projectTrusted.getValue(it))
            },
            projectDisabled = state.projectDisabled.sortedWith(order).map { DisabledRecord(it.workspace, it.name) },
        )
        val temporary = File.createTempFile("catalog-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(Json.encodeToString(document).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            // 不支持原子替换时报告失败，不退化为原地覆盖。
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun validate(name: String, hash: String) {
        SkillMarkdownParser.validateName(name)
        check(hash.matches(Regex("[0-9a-f]{64}")))
    }

    @Serializable private data class StateDocument(
        val version: Int,
        val userConfirmed: Map<String, String>,
        val projectTrusted: List<TrustedRecord>,
        val projectDisabled: List<DisabledRecord>,
        val userDisabled: Set<String> = emptySet(),
    )
    @Serializable private data class TrustedRecord(val workspace: String, val name: String, val hash: String)
    @Serializable private data class DisabledRecord(val workspace: String, val name: String)
}
