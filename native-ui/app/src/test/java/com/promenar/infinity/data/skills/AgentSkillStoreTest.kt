package com.promenar.infinity.data.skills

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentSkillStoreTest {
    private val root = Files.createTempDirectory("agent-skills").toFile()
    private val prefs = InMemoryPreferences()
    private val store = AgentSkillStore(root, prefs)

    private fun skillMd(name: String, description: String = "Use for $name tasks", body: String = "Do $name.") =
        "---\nname: $name\ndescription: $description\n---\n\n$body\n"

    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun `find sees skills changed on disk by other writers`() {
        store.installMarkdown(skillMd("existing"))
        assertThat(store.list().map { it.metadata.name }).containsExactly("existing")

        // Agent 或技能目录视图直接改写 /skills 后，设置页编辑仍要读到磁盘上的当前内容。
        java.io.File(root, "agent-made").apply { mkdirs() }.resolve("SKILL.md").writeText(skillMd("agent-made", body = "Fresh."))
        java.io.File(root, "existing").deleteRecursively()

        assertThat(store.readDocument("agent-made")!!.body).isEqualTo("Fresh.")
        assertThat(store.find("existing")).isNull()
    }

    @Test
    fun `markdown install is enabled listed and readable`() {
        store.installMarkdown(skillMd("release-notes", body = "Write notes."))

        val installed = store.list().single()
        assertThat(installed.metadata.name).isEqualTo("release-notes")
        assertThat(installed.enabled).isTrue()
        assertThat(store.readDocument("release-notes")!!.body).isEqualTo("Write notes.")
        assertThat(store.enabledSkills().map { it.name }).containsExactly("release-notes")
    }

    @Test
    fun `zip with single top level folder installs bundled files`() {
        store.installZip(
            zip(
                "pdf-tools/SKILL.md" to skillMd("pdf-tools"),
                "pdf-tools/reference/forms.md" to "# Forms",
                "__MACOSX/pdf-tools/._SKILL.md" to "junk",
            ),
        )

        val installed = store.find("pdf-tools")!!
        assertThat(installed.files).containsExactly("reference/forms.md")
        assertThat(store.readFile("pdf-tools", "reference/forms.md")).isEqualTo("# Forms")
    }

    @Test
    fun `zip path traversal and missing SKILL md are rejected without residue`() {
        assertThrows(AgentSkillFormatException::class.java) {
            store.installZip(zip("SKILL.md" to skillMd("evil"), "../outside.txt" to "x"))
        }
        assertThrows(AgentSkillFormatException::class.java) {
            store.installZip(zip("a/SKILL.md" to skillMd("a-skill"), "b/README.md" to "x"))
        }
        assertThrows(AgentSkillFormatException::class.java) { store.installZip(zip("notes.md" to "x")) }

        assertThat(store.list()).isEmpty()
        assertThat(root.listFiles().orEmpty().filter { it.name.startsWith(".staging") }).isEmpty()
    }

    @Test
    fun `oversized entries are rejected`() {
        val big = "x".repeat(AgentSkillStore.MAX_FILE_BYTES + 1)

        assertThrows(AgentSkillFormatException::class.java) {
            store.installZip(zip("SKILL.md" to skillMd("big"), "data.txt" to big))
        }
        assertThat(store.list()).isEmpty()
    }

    private fun binaryZip(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun `desktop scale zip with many files and a large asset installs`() {
        val refs = (0 until 320).map { "big/refs/$it.md" to "参考 $it".toByteArray() }.toTypedArray()
        store.installZip(
            binaryZip(
                "big/SKILL.md" to skillMd("big").toByteArray(),
                "big/assets/card.png" to ByteArray(2 * 1024 * 1024) { (it % 251).toByte() },
                *refs,
            ),
        )

        val installed = store.find("big")!!
        assertThat(installed.files).hasSize(321)
        assertThat(java.io.File(root, "big/assets/card.png").length()).isEqualTo(2L * 1024 * 1024)
    }

    @Test
    fun `zip over the total limit is rejected without residue`() {
        val each = AgentSkillStore.MAX_FILE_BYTES
        val parts = (0..(AgentSkillStore.MAX_TOTAL_BYTES / each).toInt()).map { "part$it.bin" to ByteArray(each) }.toTypedArray()

        assertThrows(AgentSkillFormatException::class.java) {
            store.installZip(binaryZip("SKILL.md" to skillMd("huge").toByteArray(), *parts))
        }
        assertThat(store.list()).isEmpty()
        assertThat(root.listFiles().orEmpty().filter { it.name.startsWith(".staging") }).isEmpty()
    }

    @Test
    fun `same name requires explicit overwrite`() {
        store.installMarkdown(skillMd("dup", body = "v1"))

        assertThrows(AgentSkillConflictException::class.java) { store.installMarkdown(skillMd("dup", body = "v2")) }
        store.installMarkdown(skillMd("dup", body = "v2"), overwrite = true)

        assertThat(store.readDocument("dup")!!.body).isEqualTo("v2")
    }

    @Test
    fun `disable delete and rename keep enabled state consistent`() {
        store.installZip(zip("SKILL.md" to skillMd("old-name"), "ref.md" to "ref"))
        store.setEnabled("old-name", false)
        assertThat(store.enabledSkills()).isEmpty()

        store.save("old-name", AgentSkillMetadata("new-name", "Renamed"), "Body")

        assertThat(store.find("old-name")).isNull()
        val renamed = store.find("new-name")!!
        assertThat(renamed.enabled).isFalse()
        assertThat(renamed.files).containsExactly("ref.md")

        store.delete("new-name")
        assertThat(store.list()).isEmpty()
        assertThat(prefs.getStringSet(AgentSkillStore.ENABLED_KEY, null)).isEmpty()
    }

    @Test
    fun `reading files outside the package or binary content fails`() {
        store.installZip(zip("SKILL.md" to skillMd("files"), "bin.dat" to "a\u0000b"))

        assertThrows(AgentSkillFormatException::class.java) { store.readFile("files", "../../etc/passwd") }
        assertThrows(AgentSkillFormatException::class.java) { store.readFile("files", "missing.md") }
        assertThrows(AgentSkillFormatException::class.java) { store.readFile("files", "bin.dat") }
    }

    @Test
    fun `directory whose frontmatter name differs is ignored`() {
        store.installMarkdown(skillMd("good"))
        java.io.File(root, "mismatch").apply { mkdirs() }.resolve("SKILL.md").writeText(skillMd("other"))

        val fresh = AgentSkillStore(root, prefs)

        assertThat(fresh.list().map { it.metadata.name }).containsExactly("good")
    }
}
