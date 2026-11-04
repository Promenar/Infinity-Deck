package com.promenar.infinity.data.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.data.storage.RealPathFileSystem
import com.promenar.infinity.data.storage.BoundRealPathDirectory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSkillCatalogTest {
    @get:Rule val temporary = TemporaryFolder()
    private val prefs = InMemoryPreferences()
    private val userRoot by lazy { temporary.newFolder("users") }
    private val stateRoot by lazy { temporary.newFolder("state") }
    private val workspace by lazy { SkillWorkspace("workspace:one", temporary.newFolder("project")) }
    private var now = 1_800_000_000_000L

    private fun catalog(files: SkillCatalogFiles = SkillCatalogFiles(JvmBoundRealPathFileSystem())): FileSkillCatalog =
        FileSkillCatalog(userRoot, prefs, stateRoot, Dispatchers.IO, { now }, files)

    private fun install(root: File, name: String, body: String = "操作说明"): File =
        File(root, name).apply {
            mkdirs()
            resolve("SKILL.md").writeText("---\nname: $name\ndescription: 用于 $name\n---\n\n$body\n")
        }

    private fun project(name: String, target: SkillWorkspace = workspace): File =
        install(File(target.root, ".agents/skills"), name)

    private fun enable(vararg names: String) {
        prefs.edit().putStringSet(AgentSkillStore.ENABLED_KEY, names.toMutableSet()).commit()
    }

    private fun legacyHash(dir: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.forEach {
            val path = it.relativeTo(dir).invariantSeparatorsPath.toByteArray()
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(path.size).array())
            digest.update(path)
            digest.update(java.nio.ByteBuffer.allocate(8).putLong(it.length()).array())
            digest.update(it.readBytes())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun skill(catalog: SkillCatalog, name: String, scope: SkillScope = SkillScope.USER): DiscoveredSkill =
        catalog.scan(if (scope == SkillScope.PROJECT) workspace else null).skills.single { it.name == name && it.scope == scope }

    private suspend inline fun <reified T : Throwable> fails(crossinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        throw AssertionError("应抛出 ${T::class.simpleName}")
    }

    @Test fun `两级同名并列且用户优先按名称排序`() = runTest {
        install(userRoot, "zulu")
        install(userRoot, "alpha")
        project("alpha")
        project("bravo")
        val catalog = catalog()

        assertThat(catalog.scan(null).skills.map { it.id }).containsExactly("user:alpha", "user:zulu").inOrder()
        val snapshot = catalog.scan(workspace)
        assertThat(snapshot.skills.map { it.id }).containsExactly(
            "user:alpha", "user:zulu", "project:workspace:one:alpha", "project:workspace:one:bravo",
        ).inOrder()
        assertThat(snapshot.byName("alpha")).hasSize(2)
        assertThat(snapshot.user.first().virtualDir).isEqualTo("/skills/alpha")
        assertThat(snapshot.project.first().virtualDir).isEqualTo("/.agents/skills/alpha")
        assertThat(snapshot.project.first().status).isEqualTo(SkillStatus.PENDING_TRUST)
    }

    @Test fun `不同工作区交替扫描且内容不变时不递增revision`() = runTest {
        install(userRoot, "alpha")
        project("bravo")
        val catalog = catalog()
        catalog.scan(null)
        catalog.scan(workspace)
        val revision = catalog.revision.value

        repeat(3) {
            catalog.scan(null)
            catalog.scan(workspace)
        }
        assertThat(catalog.revision.value).isEqualTo(revision)
    }

    @Test fun `只发现一级非隐藏目录`() = runTest {
        install(userRoot, "valid")
        install(userRoot, ".staging-ignored")
        install(userRoot, ".trash-ignored")
        install(userRoot, ".git")
        userRoot.resolve("ordinary.md").writeText("忽略")
        val parent = userRoot.resolve("parent").apply { mkdirs() }
        install(parent, "nested")

        val skills = catalog().scan(null).skills
        assertThat(skills.map { it.name }).containsExactly("parent", "valid").inOrder()
        assertThat(skills.first().status).isEqualTo(SkillStatus.BROKEN)
    }

    @Test fun `损坏技能保留名称并说明缺失解析及名称不匹配原因`() = runTest {
        userRoot.resolve("missing").mkdirs()
        install(userRoot, "invalid").resolve("SKILL.md").writeText("缺少 frontmatter")
        install(userRoot, "mismatch").resolve("SKILL.md").writeText("---\nname: other\ndescription: 说明\n---\n")
        install(userRoot, "no-description").resolve("SKILL.md").writeText("---\nname: no-description\n---\n")

        val broken = catalog().scan(null).skills.associateBy { it.name }
        assertThat(broken.values.map { it.status }.toSet()).containsExactly(SkillStatus.BROKEN)
        assertThat(broken["missing"]!!.brokenReason).contains("SKILL.md")
        assertThat(broken["invalid"]!!.brokenReason).contains("frontmatter")
        assertThat(broken["mismatch"]!!.brokenReason).contains("目录名")
        assertThat(broken["no-description"]!!.brokenReason).contains("description 不能为空")
        assertThat(broken.values.all { it.metadata == null }).isTrue()
    }

    @Test fun `文件数单文件与总大小超限均损坏`() = runTest {
        val count = install(userRoot, "count")
        repeat(AgentSkillStore.MAX_FILES) { count.resolve("$it.txt").writeText("x") }
        sparse(install(userRoot, "large").resolve("large.bin"), AgentSkillStore.MAX_FILE_BYTES + 1L)
        val total = install(userRoot, "total")
        val each = AgentSkillStore.MAX_FILE_BYTES.toLong()
        repeat((AgentSkillStore.MAX_TOTAL_BYTES / each).toInt() + 1) { sparse(total.resolve("$it.bin"), each) }

        val skills = catalog().scan(null).skills.associateBy { it.name }
        assertThat(skills["count"]!!.brokenReason).contains("文件数")
        assertThat(skills["large"]!!.brokenReason).contains("MiB")
        assertThat(skills["total"]!!.brokenReason).contains("总量")
        assertThat(skills.values.all { it.status == SkillStatus.BROKEN }).isTrue()
    }

    @Test fun `桌面规模的技能在新上限内可用且摘要随内容变化`() = runTest {
        // 桌面技能实测：最多 317 个文件、最大单文件约 1.9 MiB、最大单技能约 6 MiB。
        val dir = install(userRoot, "desktop")
        repeat(320) { dir.resolve("refs/$it.md").apply { parentFile.mkdirs() }.writeText("参考 $it") }
        dir.resolve("assets/card.png").apply { parentFile.mkdirs() }.writeBytes(ByteArray(2 * 1024 * 1024) { (it % 251).toByte() })
        dir.resolve("assets/fonts.css").writeBytes(ByteArray(4 * 1024 * 1024) { 'a'.code.toByte() })
        val catalog = catalog()
        val first = skill(catalog, "desktop")
        assertThat(first.brokenReason).isNull()
        catalog.setEnabled(first, true)
        assertThat(skill(catalog, "desktop").status).isEqualTo(SkillStatus.ENABLED)
        assertThat(skill(catalog, "desktop").modifiedSinceConfirmed).isFalse()

        dir.resolve("assets/card.png").writeBytes(ByteArray(2 * 1024 * 1024) { (it % 241).toByte() })
        val changed = skill(catalog(), "desktop")
        assertThat(changed.brokenReason).isNull()
        assertThat(changed.modifiedSinceConfirmed).isTrue()
    }

    private fun sparse(file: File, size: Long) {
        java.io.RandomAccessFile(file, "rw").use { it.setLength(size) }
    }

    @Test fun `技能目录文件和中间目录符号链接均拒绝且不跟随`() = runTest {
        val outside = install(temporary.newFolder("outside"), "linked")
        Files.createSymbolicLink(userRoot.resolve("linked").toPath(), outside.toPath())
        val linkedFile = install(userRoot, "linked-file")
        Files.createSymbolicLink(linkedFile.resolve("ref.txt").toPath(), outside.resolve("SKILL.md").toPath())
        val linkedDirectory = install(userRoot, "linked-directory")
        Files.createSymbolicLink(linkedDirectory.resolve("refs").toPath(), outside.toPath())
        val catalog = catalog()

        val skills = catalog.scan(null).skills
        assertThat(skills).hasSize(3)
        skills.forEach {
            assertThat(it.status).isEqualTo(SkillStatus.BROKEN)
            assertThat(it.brokenReason).contains("符号链接")
            assertThat(catalog.readDocument(it)).isNull()
        }
    }

    @Test fun `项目入口的符号链接不扫描目标`() = runTest {
        val outside = temporary.newFolder("outside-project")
        install(outside.resolve("skills"), "external")
        Files.createSymbolicLink(workspace.root.resolve(".agents").toPath(), outside.toPath())
        assertThat(catalog().scan(workspace).project).isEmpty()
    }

    @Test fun `git目录不进入限额清单内容哈希或增量读取`() = runTest {
        val dir = project("git-safe")
        val git = dir.resolve(".git").apply { mkdirs() }
        repeat(210) { git.resolve("$it").writeBytes(ByteArray(30_000)) }
        val files = CountingFiles()
        val catalog = catalog(files)
        val pending = skill(catalog, "git-safe", SkillScope.PROJECT)
        catalog.trust(pending)
        val enabled = skill(catalog, "git-safe", SkillScope.PROJECT)
        val reads = files.reads
        val revision = catalog.revision.value
        git.resolve("0").writeText("更新 Git 内部状态")

        assertThat(skill(catalog, "git-safe", SkillScope.PROJECT)).isEqualTo(enabled)
        assertThat(enabled.files).isEmpty()
        assertThat(files.reads).isEqualTo(reads)
        assertThat(catalog.revision.value).isEqualTo(revision)
        assertThat(skill(catalog(), "git-safe", SkillScope.PROJECT).status).isEqualTo(SkillStatus.ENABLED)
    }

    @Test fun `解析openai两层字段引号注释与隐式调用布尔值`() = runTest {
        val dir = install(userRoot, "yaml")
        dir.resolve("agents").mkdirs()
        dir.resolve("agents/openai.yaml").writeText(
            "interface:\n  display_name: \"文档: # 助手\" # 注释\n  short_description: '处理''文档'\n" +
                "policy:\n  allow_implicit_invocation: false\n",
        )
        enable("yaml")
        val catalog = catalog()
        val parsed = skill(catalog, "yaml")

        assertThat(parsed.displayName).isEqualTo("文档: # 助手")
        assertThat(parsed.shortDescription).isEqualTo("处理'文档")
        assertThat(parsed.allowImplicitInvocation).isFalse()
        assertThat(catalog.scan(null).implicitCatalog).isEmpty()
    }

    @Test fun `缺失或损坏的openai配置使用缺省值且不损坏技能`() = runTest {
        install(userRoot, "missing-yaml")
        val invalid = install(userRoot, "bad-yaml")
        invalid.resolve("agents").mkdirs()
        val yaml = invalid.resolve("agents/openai.yaml")
        val catalog = catalog()
        for (text in listOf("[broken", "interface:\n  display_name: \"未闭合\n", "policy:\n  allow_implicit_invocation: nope\n")) {
            yaml.writeText(text)
            val parsed = skill(catalog, "bad-yaml")
            assertThat(parsed.status).isEqualTo(SkillStatus.DISABLED)
            assertThat(parsed.displayName).isNull()
            assertThat(parsed.shortDescription).isNull()
            assertThat(parsed.allowImplicitInvocation).isTrue()
        }
        val missing = skill(catalog, "missing-yaml")
        assertThat(missing.displayName).isNull()
        assertThat(missing.allowImplicitInvocation).isTrue()
    }

    @Test fun `YAML普通字符串中的引号不会丢失显式调用策略`() = runTest {
        val dir = install(userRoot, "plain-yaml")
        dir.resolve("agents").mkdirs()
        dir.resolve("agents/openai.yaml").writeText(
            "interface:\n  display_name: Author's helper # 注释\n" +
                "  short_description: A 6\" reference\npolicy:\n  allow_implicit_invocation: false\n",
        )
        val parsed = skill(catalog(), "plain-yaml")
        assertThat(parsed.displayName).isEqualTo("Author's helper")
        assertThat(parsed.shortDescription).isEqualTo("A 6\" reference")
        assertThat(parsed.allowImplicitInvocation).isFalse()
    }

    @Test fun `旧存储启用集合兼容且确认哈希跨实例保存`() = runTest {
        val store = AgentSkillStore(userRoot, prefs)
        store.installMarkdown("---\nname: legacy\ndescription: 兼容\n---\n原始内容\n")
        val catalog = catalog()
        val initial = skill(catalog, "legacy")
        assertThat(initial.status).isEqualTo(SkillStatus.ENABLED)
        assertThat(initial.modifiedSinceConfirmed).isFalse()

        userRoot.resolve("legacy/SKILL.md").appendText("新内容")
        assertThat(skill(catalog(), "legacy").modifiedSinceConfirmed).isTrue()
        store.setEnabled("legacy", false)
        assertThat(skill(catalog, "legacy").status).isEqualTo(SkillStatus.DISABLED)
        assertThat(skill(catalog, "legacy").modifiedSinceConfirmed).isFalse()
    }

    @Test fun `用户新技能默认停用启用确认停用与重新启用`() = runTest {
        val dir = install(userRoot, "user")
        val catalog = catalog()
        val disabled = skill(catalog, "user")
        assertThat(disabled.status).isEqualTo(SkillStatus.DISABLED)
        catalog.setEnabled(disabled, true)
        val enabled = skill(catalog, "user")
        assertThat(enabled.status).isEqualTo(SkillStatus.ENABLED)
        dir.resolve("reference.txt").writeText("新附带文件")
        assertThat(skill(catalog, "user").modifiedSinceConfirmed).isTrue()
        catalog.acknowledgeModified(enabled)
        assertThat(skill(catalog, "user").modifiedSinceConfirmed).isFalse()
        dir.resolve("reference.txt").appendText("另一次变化")
        catalog.setEnabled(enabled, true)
        assertThat(skill(catalog, "user").modifiedSinceConfirmed).isFalse()
        catalog.setEnabled(enabled, false)
        assertThat(prefs.getStringSet(AgentSkillStore.ENABLED_KEY, null)).doesNotContain("user")
        assertThat(skill(catalog, "user").status).isEqualTo(SkillStatus.DISABLED)
    }

    @Test fun `项目首次待信任内容变化撤销信任停用和重新启用`() = runTest {
        val dir = project("project")
        val catalog = catalog()
        val pending = skill(catalog, "project", SkillScope.PROJECT)
        assertThat(pending.status).isEqualTo(SkillStatus.PENDING_TRUST)
        catalog.trust(pending)
        val enabled = skill(catalog, "project", SkillScope.PROJECT)
        assertThat(enabled.status).isEqualTo(SkillStatus.ENABLED)
        catalog.setEnabled(enabled, false)
        assertThat(skill(catalog(), "project", SkillScope.PROJECT).status).isEqualTo(SkillStatus.DISABLED)
        catalog.setEnabled(enabled, true)
        assertThat(skill(catalog, "project", SkillScope.PROJECT).status).isEqualTo(SkillStatus.ENABLED)
        dir.resolve("reference.txt").writeText("内容变化")
        val changed = skill(catalog, "project", SkillScope.PROJECT)
        assertThat(changed.status).isEqualTo(SkillStatus.PENDING_TRUST)
        assertThat(changed.modifiedSinceConfirmed).isFalse()
        fails<IllegalStateException> { catalog.setEnabled(enabled, true) }
        catalog.trust(changed)
        assertThat(skill(catalog, "project", SkillScope.PROJECT).status).isEqualTo(SkillStatus.ENABLED)
    }

    @Test fun `不同工作区信任与停用互相隔离且句柄保留原工作区`() = runTest {
        val other = SkillWorkspace("other", temporary.newFolder("other"))
        project("shared")
        project("shared", other)
        val catalog = catalog()
        val first = catalog.scan(workspace).project.single()
        val second = catalog.scan(other).project.single()
        catalog.trust(first)
        assertThat(catalog.scan(other).project.single().status).isEqualTo(SkillStatus.PENDING_TRUST)
        catalog.trust(second)
        catalog.setEnabled(catalog.scan(workspace).project.single(), false)

        assertThat(catalog.scan(workspace).project.single().status).isEqualTo(SkillStatus.DISABLED)
        assertThat(catalog.scan(other).project.single().status).isEqualTo(SkillStatus.ENABLED)
        assertThat(catalog.readDocument(first)!!.metadata.name).isEqualTo("shared")
    }

    @Test fun `管理操作即使快照与签名陈旧仍确认调用时内容`() = runTest {
        val dir = project("fresh")
        val catalog = catalog()
        val pending = skill(catalog, "fresh", SkillScope.PROJECT)
        val document = dir.resolve("SKILL.md")
        val timestamp = Files.getLastModifiedTime(document.toPath())
        val changed = document.readText().replace("操作说明", "当前内容")
        document.writeText(changed)
        Files.setLastModifiedTime(document.toPath(), timestamp)
        catalog.trust(pending)

        assertThat(skill(catalog(), "fresh", SkillScope.PROJECT).status).isEqualTo(SkillStatus.ENABLED)
        assertThat(catalog.readDocument(pending)!!.body).isEqualTo("当前内容")
    }

    @Test fun `状态损坏未知版本或不完整记录均失败关闭`() = runTest {
        project("trusted")
        val catalog = catalog()
        catalog.trust(skill(catalog, "trusted", SkillScope.PROJECT))
        val file = stateRoot.listFiles()!!.single { it.extension == "json" }
        val valid = file.readText()
        for (corrupt in listOf("{", valid.replace("\"version\":1", "\"version\":99"), "{\"version\":1}")) {
            file.writeText(corrupt)
            assertThat(skill(catalog, "trusted", SkillScope.PROJECT).status).isEqualTo(SkillStatus.PENDING_TRUST)
            assertThat(skill(catalog(), "trusted", SkillScope.PROJECT).status).isEqualTo(SkillStatus.PENDING_TRUST)
        }
    }

    @Test fun `签名不变不重读技能文件而内容变化递增revision`() = runTest {
        val dir = install(userRoot, "cached")
        val files = CountingFiles()
        val catalog = catalog(files)
        val initial = skill(catalog, "cached")
        val reads = files.reads
        val revision = catalog.revision.value
        assertThat(reads).isGreaterThan(0)
        assertThat(skill(catalog, "cached")).isEqualTo(initial)
        assertThat(files.reads).isEqualTo(reads)
        assertThat(catalog.revision.value).isEqualTo(revision)

        dir.resolve("SKILL.md").appendText("修改正文")
        skill(catalog, "cached")
        assertThat(files.reads).isGreaterThan(reads)
        assertThat(catalog.revision.value).isGreaterThan(revision)
    }

    @Test fun `内容哈希包括相对路径且忽略文件遍历顺序`() = runTest {
        val dir = project("hash")
        dir.resolve("a.txt").writeText("same")
        val catalog = catalog()
        catalog.trust(skill(catalog, "hash", SkillScope.PROJECT))
        assertThat(dir.resolve("a.txt").renameTo(dir.resolve("b.txt"))).isTrue()
        assertThat(skill(catalog, "hash", SkillScope.PROJECT).status).isEqualTo(SkillStatus.PENDING_TRUST)
        assertThat(dir.resolve("b.txt").renameTo(dir.resolve("a.txt"))).isTrue()
        assertThat(skill(catalog(), "hash", SkillScope.PROJECT).status).isEqualTo(SkillStatus.ENABLED)
    }

    @Test fun `包内隐藏普通文件纳入哈希而读取仍遵循保留路径限制`() = runTest {
        val dir = project("hidden-file")
        dir.resolve(".infinity-note").writeText("隐藏附带内容")
        val catalog = catalog()
        val pending = skill(catalog, "hidden-file", SkillScope.PROJECT)
        assertThat(pending.status).isEqualTo(SkillStatus.PENDING_TRUST)
        assertThat(pending.files).contains(".infinity-note")
        catalog.trust(pending)
        dir.resolve(".infinity-note").appendText("变化")
        assertThat(skill(catalog, "hidden-file", SkillScope.PROJECT).status).isEqualTo(SkillStatus.PENDING_TRUST)
        fails<AgentSkillFormatException> { catalog.readFile(pending, ".infinity-note") }
    }

    @Test fun `两级正文与文件可读路径归一化且拒绝越界git和不在清单内文件`() = runTest {
        val user = install(userRoot, "read")
        val project = project("read")
        listOf(user, project).forEach {
            it.resolve("refs").mkdirs()
            it.resolve("refs/info.md").writeText("\uFEFF有效内容")
            it.resolve(".git").mkdirs()
            it.resolve(".git/config").writeText("禁止读取")
        }
        val catalog = catalog()
        for (entry in catalog.scan(workspace).skills) {
            assertThat(catalog.readDocument(entry)!!.body).isEqualTo("操作说明")
            assertThat(catalog.readFile(entry, "refs\\info.md")).isEqualTo("有效内容")
            assertThat(catalog.readFile(entry, "SKILL.md")).contains("name: read")
            for (path in listOf("../outside", "/etc/passwd", "C:\\secret", "refs/../../out", ".git/config", "missing")) {
                fails<AgentSkillFormatException> { catalog.readFile(entry, path) }
            }
        }
    }

    @Test fun `读取拒绝超限非UTF8空字节以及扫描后被换成链接的文件`() = runTest {
        val dir = install(userRoot, "limits")
        dir.resolve("big.txt").writeBytes(ByteArray(256 * 1024 + 1) { 65 })
        dir.resolve("binary").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        dir.resolve("nul").writeBytes(byteArrayOf(65, 0, 66))
        dir.resolve("safe").writeText("safe")
        val catalog = catalog()
        val entry = skill(catalog, "limits")
        for (path in listOf("big.txt", "binary", "nul")) {
            fails<AgentSkillFormatException> { catalog.readFile(entry, path) }
        }
        Files.delete(dir.resolve("safe").toPath())
        Files.createSymbolicLink(dir.resolve("safe").toPath(), temporary.newFile("outside.txt").toPath())
        fails<AgentSkillFormatException> { catalog.readFile(entry, "safe") }
        assertThat(catalog.readDocument(entry)).isNull()
    }

    @Test fun `正文读取也遵循256KiB上限且损坏或消失时返回null`() = runTest {
        val dir = install(userRoot, "document", "x".repeat(256 * 1024))
        val catalog = catalog()
        val entry = skill(catalog, "document")
        assertThat(entry.status).isEqualTo(SkillStatus.DISABLED)
        assertThat(catalog.readDocument(entry)).isNull()
        dir.resolve("SKILL.md").delete()
        assertThat(catalog.readDocument(entry)).isNull()
    }

    @Test fun `并发扫描和信任串行且外部删除可恢复`() = runTest {
        val dir = project("concurrent")
        val files = object : SkillCatalogFiles(JvmBoundRealPathFileSystem()) {
            var removeOnce = true
            override fun stream(file: File, limit: Long, anchor: File, sink: (ByteArray, Int) -> Unit): Long {
                if (removeOnce && file.name == "SKILL.md") {
                    removeOnce = false
                    file.delete()
                }
                return super.stream(file, limit, anchor, sink)
            }
        }
        val catalog = catalog(files)
        assertThat(skill(catalog, "concurrent", SkillScope.PROJECT).status).isEqualTo(SkillStatus.BROKEN)
        install(dir.parentFile, "concurrent")
        val pending = skill(catalog, "concurrent", SkillScope.PROJECT)
        (1..12).map { async(Dispatchers.Default) { catalog.trust(pending); catalog.scan(workspace) } }.awaitAll()
        assertThat(skill(catalog(), "concurrent", SkillScope.PROJECT).status).isEqualTo(SkillStatus.ENABLED)
    }

    @Test fun `非法状态和作用域调用拒绝且BROKEN优先`() = runTest {
        val user = install(userRoot, "user")
        project("project")
        enable("user")
        val catalog = catalog()
        val snapshot = catalog.scan(workspace)
        val userEntry = snapshot.user.single()
        val projectEntry = snapshot.project.single()
        fails<IllegalStateException> { catalog.setEnabled(projectEntry, true) }
        fails<IllegalStateException> { catalog.setEnabled(projectEntry, false) }
        fails<IllegalStateException> { catalog.trust(userEntry) }
        fails<IllegalStateException> { catalog.acknowledgeModified(projectEntry) }
        user.resolve("SKILL.md").delete()
        val broken = skill(catalog, "user")
        assertThat(broken.status).isEqualTo(SkillStatus.BROKEN)
        fails<IllegalStateException> { catalog.setEnabled(broken, true) }
        fails<IllegalStateException> { catalog.setEnabled(userEntry, false) }
        assertThat(catalog.readDocument(userEntry)).isNull()
    }

    @Test fun `陈旧待信任与损坏快照在当前内容可用时允许切换`() = runTest {
        project("stale")
        val dir = install(userRoot, "repaired")
        val markdown = dir.resolve("SKILL.md").readText()
        dir.resolve("SKILL.md").delete()
        val catalog = catalog()
        val pending = skill(catalog, "stale", SkillScope.PROJECT)
        val broken = skill(catalog, "repaired")
        catalog.trust(pending)
        catalog.setEnabled(pending, false)
        assertThat(skill(catalog, "stale", SkillScope.PROJECT).status).isEqualTo(SkillStatus.DISABLED)
        dir.resolve("SKILL.md").writeText(markdown)
        catalog.setEnabled(broken, true)
        assertThat(skill(catalog, "repaired").status).isEqualTo(SkillStatus.ENABLED)
    }

    @Test fun `读取瞬间替换中间目录为链接不能泄露外部文件`() = runTest {
        val dir = install(userRoot, "race")
        val refs = dir.resolve("refs").apply { mkdirs() }
        refs.resolve("info.txt").writeText("内部内容")
        val outside = temporary.newFolder("race-outside")
        outside.resolve("info.txt").writeText("外部内容")
        val held = dir.resolve("refs-hold")
        val access = object : SkillCatalogFiles(JvmBoundRealPathFileSystem()) {
            var attack = false
            override fun stream(file: File, limit: Long, anchor: File, sink: (ByteArray, Int) -> Unit): Long {
                if (!attack || file != refs.resolve("info.txt")) return super.stream(file, limit, anchor, sink)
                assertThat(refs.renameTo(held)).isTrue()
                Files.createSymbolicLink(refs.toPath(), outside.toPath())
                try {
                    return super.stream(file, limit, anchor, sink)
                } finally {
                    Files.delete(refs.toPath())
                    assertThat(held.renameTo(refs)).isTrue()
                }
            }
        }
        val catalog = catalog(access)
        val entry = skill(catalog, "race")
        access.attack = true
        fails<AgentSkillFormatException> { catalog.readFile(entry, "refs/info.txt") }
        assertThat(refs.resolve("info.txt").readText()).isEqualTo("内部内容")
    }

    @Test fun `删除两级技能并清除暂存副本与全部状态`() = runTest {
        install(userRoot, "user")
        val project = project("project")
        val catalog = catalog()
        val user = skill(catalog, "user")
        catalog.setEnabled(user, true)
        val pending = skill(catalog, "project", SkillScope.PROJECT)
        catalog.trust(pending)
        catalog.setEnabled(skill(catalog, "project", SkillScope.PROJECT), false)
        catalog.delete(user)
        catalog.delete(pending)

        assertThat(catalog.scan(workspace).skills).isEmpty()
        assertThat(stateRoot.resolve("trash/$now-user-user").exists()).isFalse()
        assertThat(stateRoot.resolve("trash/$now-project-project").exists()).isFalse()
        assertThat(prefs.getStringSet(AgentSkillStore.ENABLED_KEY, null)).doesNotContain("user")
        install(userRoot, "user", "重装后的内容")
        install(project.parentFile, "project")
        assertThat(skill(catalog, "user").status).isEqualTo(SkillStatus.DISABLED)
        enable("user")
        assertThat(skill(catalog, "user").modifiedSinceConfirmed).isFalse()
        assertThat(skill(catalog, "project", SkillScope.PROJECT).status).isEqualTo(SkillStatus.PENDING_TRUST)
    }

    @Test fun `首次扫描清理超过30天的回收项且不跟随链接`() = runTest {
        val trash = stateRoot.resolve("trash").apply { mkdirs() }
        val old = now - 31L * 24 * 60 * 60 * 1000
        val boundary = now - 30L * 24 * 60 * 60 * 1000
        trash.resolve("$old-user-old").mkdirs()
        trash.resolve("$old-user-line\nbreak").mkdirs()
        trash.resolve("$boundary-project-boundary").mkdirs()
        val outside = temporary.newFolder("keep-outside")
        outside.resolve("safe").writeText("保留")
        Files.createSymbolicLink(trash.resolve("$old-user-link").toPath(), outside.toPath())
        val catalog = catalog()
        catalog.scan(null)

        assertThat(trash.resolve("$old-user-old").exists()).isFalse()
        assertThat(trash.resolve("$old-user-line\nbreak").exists()).isFalse()
        assertThat(Files.exists(trash.resolve("$old-user-link").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse()
        assertThat(outside.resolve("safe").readText()).isEqualTo("保留")
        assertThat(trash.resolve("$boundary-project-boundary").isDirectory).isTrue()
        trash.resolve("$old-user-late").mkdirs()
        catalog.scan(null)
        assertThat(trash.resolve("$old-user-late").isDirectory).isTrue()
    }

    @Test fun `跨卷复制删除成功并且失败保留原目录与状态`() = runTest {
        val dir = project("cross-volume")
        dir.resolve("reference.txt").writeText("保留内容")
        val files = object : SkillCatalogFiles(JvmBoundRealPathFileSystem()) {
            var failCopy = true
            override fun rename(source: File, target: File): Boolean = false
            override fun copyTree(source: File, target: File) {
                if (failCopy) throw IOException("模拟目标卷不可写")
                super.copyTree(source, target)
            }
        }
        val catalog = catalog(files)
        val entry = skill(catalog, "cross-volume", SkillScope.PROJECT)
        catalog.trust(entry)
        fails<IOException> { catalog.delete(entry) }
        assertThat(dir.resolve("reference.txt").readText()).isEqualTo("保留内容")
        assertThat(skill(catalog, "cross-volume", SkillScope.PROJECT).status).isEqualTo(SkillStatus.ENABLED)
        files.failCopy = false
        catalog.delete(entry)
        assertThat(dir.exists()).isFalse()
        assertThat(stateRoot.resolve("trash/$now-project-cross-volume").exists()).isFalse()
    }

    @Test fun `跨卷清除部分源文件失败时从完整副本恢复`() = runTest {
        val dir = install(userRoot, "rollback")
        dir.resolve("reference.txt").writeText("必须恢复")
        val files = object : SkillCatalogFiles(JvmBoundRealPathFileSystem()) {
            var failDelete = true
            override fun rename(source: File, target: File): Boolean = false
            override fun deleteTree(root: File) {
                if (root == dir && failDelete) {
                    failDelete = false
                    root.resolve("reference.txt").delete()
                    throw IOException("模拟删除一部分后失败")
                }
                super.deleteTree(root)
            }
        }
        val catalog = catalog(files)
        val entry = skill(catalog, "rollback")
        catalog.setEnabled(entry, true)
        fails<IOException> { catalog.delete(entry) }
        assertThat(dir.resolve("reference.txt").readText()).isEqualTo("必须恢复")
        assertThat(dir.resolve("SKILL.md").isFile).isTrue()
        assertThat(skill(catalog, "rollback").status).isEqualTo(SkillStatus.ENABLED)
    }

    @Test fun `删除损坏链接技能只删除链接且不改目标`() = runTest {
        val outside = install(temporary.newFolder("outside-delete"), "linked")
        Files.createSymbolicLink(userRoot.resolve("linked").toPath(), outside.toPath())
        val catalog = catalog()
        catalog.delete(skill(catalog, "linked"))
        assertThat(outside.resolve("SKILL.md").isFile).isTrue()
        assertThat(Files.exists(stateRoot.resolve("trash/$now-user-linked").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse()
        assertThat(catalog.scan(null).skills).isEmpty()
    }

    @Test fun `未知或伪造项目句柄不能访问磁盘`() = runTest {
        project("handle")
        val catalog = catalog()
        val entry = skill(catalog, "handle", SkillScope.PROJECT)
        assertThat(catalog().readDocument(entry)).isNull()
        fails<AgentSkillFormatException> { catalog().readFile(entry, "SKILL.md") }
        fails<IllegalStateException> { catalog.trust(entry.copy(name = "../outside")) }
        fails<IllegalStateException> { catalog.delete(entry.copy(scope = SkillScope.USER)) }
    }

    private class CountingFiles : SkillCatalogFiles(JvmBoundRealPathFileSystem()) {
        var reads = 0
        override fun stream(file: File, limit: Long, anchor: File, sink: (ByteArray, Int) -> Unit): Long {
            if (file.name != "catalog.json") reads++
            return super.stream(file, limit, anchor, sink)
        }
    }

    @Test fun `批准的完整包自动启用且保留手动停用并绑定安装时哈希`() = runTest {
        val catalog = catalog()
        fun payload(body: String) = mapOf("SKILL.md" to
            "---\nname: approved\ndescription: 安装测试\n---\n$body".toByteArray())
        val installed = catalog.installApproved(payload("批准内容"))
        assertThat(installed.status).isEqualTo(SkillStatus.ENABLED)
        assertThat(skill(catalog(), "approved").modifiedSinceConfirmed).isFalse()
        userRoot.resolve("approved/SKILL.md").appendText("后续变化")
        assertThat(skill(catalog(), "approved").modifiedSinceConfirmed).isTrue()
        catalog.setEnabled(installed, false)
        catalog.installApproved(payload("重新批准的内容"), overwrite = true)
        assertThat(skill(catalog(), "approved").status).isEqualTo(SkillStatus.DISABLED)
        repeat(2) { assertThat(skill(catalog, "approved").status).isEqualTo(SkillStatus.DISABLED) }
    }

    @Test fun `缓存生成修改与删除不影响确认哈希或revision`() = runTest {
        val dir = install(userRoot, "cached-python")
        val catalog = catalog()
        catalog.setEnabled(skill(catalog, "cached-python"), true)
        val before = skill(catalog, "cached-python")
        val revision = catalog.revision.value
        dir.resolve("scripts/nested/__pycache__/run.cpython.pyc").apply { parentFile.mkdirs(); writeText("缓存") }
        dir.resolve("standalone.pyc").writeText("缓存")
        assertThat(skill(catalog, "cached-python")).isEqualTo(before)
        assertThat(catalog.revision.value).isEqualTo(revision)
        dir.resolve("scripts/nested/__pycache__/run.cpython.pyc").writeText("缓存变化")
        dir.resolve("standalone.pyc").delete()
        assertThat(skill(catalog(), "cached-python").modifiedSinceConfirmed).isFalse()
        dir.resolve("scripts/run.py").writeText("实际脚本")
        assertThat(skill(catalog(), "cached-python").modifiedSinceConfirmed).isTrue()
    }

    @Test fun `旧确认仅在完整旧摘要匹配时迁移且迁移后缓存变化可忽略`() = runTest {
        val dir = install(userRoot, "legacy-python")
        val cacheFile = dir.resolve("scripts/__pycache__/run.pyc").apply { parentFile.mkdirs(); writeText("旧缓存") }
        enable("legacy-python")
        val states = SkillCatalogStateStore(stateRoot, SkillCatalogFiles(JvmBoundRealPathFileSystem()))
        states.write(SkillCatalogState(userConfirmed = mapOf("legacy-python" to legacyHash(dir))))
        assertThat(skill(catalog(), "legacy-python").modifiedSinceConfirmed).isFalse()
        cacheFile.writeText("新缓存")
        assertThat(skill(catalog(), "legacy-python").modifiedSinceConfirmed).isFalse()
        dir.resolve("SKILL.md").appendText("真实内容变化")
        assertThat(skill(catalog(), "legacy-python").modifiedSinceConfirmed).isTrue()
    }

    @Test fun `安装拒绝越界冲突和超限且失败不改变原包`() = runTest {
        val catalog = catalog()
        val payload = mapOf("SKILL.md" to "---\nname: safe-install\ndescription: 测试\n---\n正文".toByteArray())
        catalog.installApproved(payload)
        fails<AgentSkillConflictException> { catalog.installApproved(payload) }
        fails<AgentSkillFormatException> { catalog.installApproved(payload + ("../escape" to byteArrayOf(1)), true) }
        fails<AgentSkillFormatException> { catalog.installApproved(payload + ("large" to ByteArray(AgentSkillStore.MAX_FILE_BYTES + 1)), true) }
        assertThat(skill(catalog(), "safe-install").modifiedSinceConfirmed).isFalse()
    }

    @Test fun `安装暂存被替换为未批准内容时拒绝发布`() = runTest {
        val files = object : SkillCatalogFiles(JvmBoundRealPathFileSystem()) {
            override fun stream(file: File, limit: Long, anchor: File, sink: (ByteArray, Int) -> Unit): Long {
                if (file.name == "SKILL.md" && file.path.contains(".approved-")) {
                    file.writeText(file.readText().replace("批准正文", "篡改正文"))
                }
                return super.stream(file, limit, anchor, sink)
            }
        }
        val catalog = catalog(files)
        fails<IllegalStateException> {
            catalog.installApproved(mapOf("SKILL.md" to "---\nname: tampered-install\ndescription: 测试\n---\n批准正文".toByteArray()))
        }
        assertThat(userRoot.resolve("tampered-install").exists()).isFalse()
        assertThat(catalog.scan(null).user).isEmpty()
    }

    @Test fun `项目旧确认迁移保留停用且真实脚本变化仍撤销信任`() = runTest {
        val dir = project("project-python")
        dir.resolve("__pycache__/run.pyc").apply { parentFile.mkdirs(); writeText("缓存") }
        val key = ProjectSkillKey(workspace.key, "project-python")
        val hash = legacyHash(dir)
        SkillCatalogStateStore(stateRoot, SkillCatalogFiles(JvmBoundRealPathFileSystem())).write(
            SkillCatalogState(projectTrusted = mapOf(key to hash), projectDisabled = setOf(key)))
        assertThat(skill(catalog(), "project-python", SkillScope.PROJECT).status).isEqualTo(SkillStatus.DISABLED)
        dir.resolve("__pycache__/run.pyc").delete()
        assertThat(skill(catalog(), "project-python", SkillScope.PROJECT).status).isEqualTo(SkillStatus.DISABLED)
        dir.resolve("run.py").writeText("实际脚本")
        assertThat(skill(catalog(), "project-python", SkillScope.PROJECT).status).isEqualTo(SkillStatus.PENDING_TRUST)
    }

    @Test fun `迁移不能把两次读取间交换的未批准内容确认为可信`() = runTest {
        val dir = install(userRoot, "migration-race", "批准内容A")
        dir.resolve("0.pyc").writeText("缓存")
        val original = dir.resolve("SKILL.md").readText()
        val oldHash = legacyHash(dir)
        val markdown = dir.resolve("SKILL.md")
        val timestamp = Files.getLastModifiedTime(markdown.toPath())
        markdown.writeText(original.replace("批准内容A", "篡改内容B"))
        Files.setLastModifiedTime(markdown.toPath(), timestamp)
        enable("migration-race")
        SkillCatalogStateStore(stateRoot, SkillCatalogFiles(JvmBoundRealPathFileSystem())).write(
            SkillCatalogState(userConfirmed = mapOf("migration-race" to oldHash)))
        val files = object : SkillCatalogFiles(JvmBoundRealPathFileSystem()) {
            override fun stream(file: File, limit: Long, anchor: File, sink: (ByteArray, Int) -> Unit): Long {
                if (file.name == "0.pyc") {
                    markdown.writeText(original)
                    Files.setLastModifiedTime(markdown.toPath(), timestamp)
                }
                return super.stream(file, limit, anchor, sink)
            }
        }
        assertThat(skill(catalog(files), "migration-race").modifiedSinceConfirmed).isTrue()
        assertThat(SkillCatalogStateStore(stateRoot, files).read().userConfirmed["migration-race"]).isEqualTo(oldHash)
    }

    @Test fun `安装发布失败恢复原目录确认与停用状态`() = runTest {
        val fileSystem = JvmBoundRealPathFileSystem()
        var failPublish = false
        fileSystem.beforeOperation = { operation, path ->
            if (failPublish && operation == "move" && path.parent.fileName.toString().startsWith(".approved-")) {
                throw IOException("模拟发布失败")
            }
        }
        val catalog = catalog(SkillCatalogFiles(fileSystem))
        fun payload(body: String) = mapOf("SKILL.md" to "---\nname: rollback-install\ndescription: 测试\n---\n$body".toByteArray())
        val installed = catalog.installApproved(payload("原始正文"))
        catalog.setEnabled(installed, false)
        failPublish = true
        fails<IOException> { catalog.installApproved(payload("批准新正文"), true) }
        assertThat(userRoot.resolve("rollback-install/SKILL.md").readText()).contains("原始正文")
        assertThat(skill(catalog(), "rollback-install").status).isEqualTo(SkillStatus.DISABLED)
        catalog.setEnabled(skill(catalog, "rollback-install"), true)
        assertThat(skill(catalog(), "rollback-install").modifiedSinceConfirmed).isFalse()
    }

    @Test fun `覆盖旧存储未确认且已停用的技能不会重新启用`() = runTest {
        install(userRoot, "legacy-stopped")
        val catalog = catalog()
        val installed = catalog.installApproved(mapOf("SKILL.md" to
            "---\nname: legacy-stopped\ndescription: 测试\n---\n批准的新正文".toByteArray()), true)
        assertThat(installed.status).isEqualTo(SkillStatus.DISABLED)
        assertThat(skill(catalog(), "legacy-stopped").status).isEqualTo(SkillStatus.DISABLED)
    }

    @Test fun `发布期间根目录换为链接不会移动删除或覆盖外部技能`() = runTest {
        install(userRoot, "root-race", "原始正文")
        val outside = temporary.newFolder("install-outside")
        install(outside, "root-race", "外部正文")
        val held = temporary.root.resolve("held-users")
        val fileSystem = JvmBoundRealPathFileSystem()
        var attacked = false
        fileSystem.beforeOperation = { operation, path ->
            if (!attacked && operation == "move" && path.fileName.toString() == "root-race") {
                attacked = true
                assertThat(userRoot.renameTo(held)).isTrue()
                fileSystem.movedDirectories[userRoot.toPath()] = held.toPath()
                Files.createSymbolicLink(userRoot.toPath(), outside.toPath())
            }
        }
        val catalog = catalog(SkillCatalogFiles(fileSystem))
        fails<Exception> {
            catalog.installApproved(mapOf("SKILL.md" to
                "---\nname: root-race\ndescription: 测试\n---\n批准新正文".toByteArray()), true)
        }
        assertThat(outside.resolve("root-race/SKILL.md").readText()).contains("外部正文")
        assertThat(held.resolve("root-race/SKILL.md").readText()).contains("原始正文")
    }

    @Test fun `备份或发布移动完成后同步失败仍恢复原技能与状态`() = runTest {
        val base = JvmBoundRealPathFileSystem()
        var phase: String? = null
        val fileSystem = object : RealPathFileSystem by base {
            override fun openFileSystemRoot(): BoundRealPathDirectory = AfterMoveDirectory(base.openFileSystemRoot()) { source, target ->
                val failed = source == "sync-failure" && when (phase) {
                    "backup" -> target.startsWith(".replaced-")
                    "publish" -> target == "sync-failure"
                    else -> false
                }
                if (failed) { phase = null; throw IOException("模拟移动后同步失败") }
            }
        }
        val catalog = catalog(SkillCatalogFiles(fileSystem))
        fun payload(body: String) = mapOf("SKILL.md" to "---\nname: sync-failure\ndescription: 测试\n---\n$body".toByteArray())
        catalog.installApproved(payload("原始正文"))
        for (failurePhase in listOf("backup", "publish")) {
            phase = failurePhase
            fails<IOException> { catalog.installApproved(payload("批准新正文"), true) }
            assertThat(userRoot.resolve("sync-failure/SKILL.md").readText()).contains("原始正文")
            assertThat(skill(catalog(), "sync-failure").status).isEqualTo(SkillStatus.ENABLED)
            assertThat(skill(catalog(), "sync-failure").modifiedSinceConfirmed).isFalse()
        }
    }

    private class AfterMoveDirectory(
        private val delegate: BoundRealPathDirectory,
        private val afterMove: (String, String) -> Unit,
    ) : BoundRealPathDirectory by delegate {
        override fun openDirectory(name: String): BoundRealPathDirectory = AfterMoveDirectory(delegate.openDirectory(name), afterMove)
        override fun moveNew(source: String, targetDirectory: BoundRealPathDirectory, target: String) {
            delegate.moveNew(source, (targetDirectory as AfterMoveDirectory).delegate, target)
            afterMove(source, target)
        }
    }
}
