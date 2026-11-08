package com.promenar.infinity.data.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillSourceGroupsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `按来源分组且批量逐项确认内容并保留跳过项`() = runTest {
        val user = temporary.newFolder("user")
        val project = SkillWorkspace("project", temporary.newFolder("project"))
        fun install(root: File, name: String) = root.resolve("$name/SKILL.md").apply {
            parentFile.mkdirs(); writeText("---\nname: $name\ndescription: 批量测试\n---\n正文")
        }
        val userFile = install(user, "user-demo")
        val projectFile = install(project.root.resolve(".agents/skills"), "project-demo")
        user.resolve("broken").mkdir()
        val catalog = BuiltinSkillCatalog(FileSkillCatalog(user, InMemoryPreferences(), temporary.newFolder("state"),
            Dispatchers.IO, System::currentTimeMillis, SkillCatalogFiles(JvmBoundRealPathFileSystem()))) {
            "---\nname: infinity-deck\ndescription: 内置测试\n---\n指南"
        }
        val initial = catalog.scan(project)
        val groups = initial.sourceGroups()
        assertThat(groups.map { it.scope }).containsExactly(SkillScope.BUILTIN, SkillScope.USER, SkillScope.PROJECT).inOrder()
        assertThat(groups.first().canToggle).isFalse()
        val enabled = catalog.setGroupEnabled(initial, null, true)
        assertThat(enabled.updatedIds).containsExactly("user:user-demo", "project:project:project-demo")
        assertThat(enabled.skippedIds).containsExactly("builtin:infinity-deck", "user:broken")
        assertThat(enabled.failures).isEmpty()
        assertThat(catalog.scan(project).user.single { it.name == "user-demo" }.modifiedSinceConfirmed).isFalse()
        userFile.appendText("正文变化")
        projectFile.appendText("正文变化")
        assertThat(catalog.scan(project).project.single().status).isEqualTo(SkillStatus.PENDING_TRUST)
        catalog.setGroupEnabled(catalog.scan(project), SkillScope.USER, true)
        assertThat(catalog.scan(project).user.single { it.name == "user-demo" }.modifiedSinceConfirmed).isFalse()
        assertThat(catalog.scan(project).project.single().status).isEqualTo(SkillStatus.PENDING_TRUST)
        catalog.setGroupEnabled(catalog.scan(project), null, true)
        catalog.setGroupEnabled(catalog.scan(project), null, false)
        val disabled = catalog.scan(project)
        assertThat(disabled.builtin.single().status).isEqualTo(SkillStatus.ENABLED)
        assertThat(disabled.user.single { it.name == "user-demo" }.status).isEqualTo(SkillStatus.DISABLED)
        assertThat(disabled.project.single().status).isEqualTo(SkillStatus.DISABLED)
    }
}
