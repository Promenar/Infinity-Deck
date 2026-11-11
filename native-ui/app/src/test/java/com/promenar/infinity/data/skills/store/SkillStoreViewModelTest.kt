package com.promenar.infinity.data.skills.store

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.skills.FileSkillCatalog
import com.promenar.infinity.data.skills.InMemoryPreferences
import com.promenar.infinity.data.skills.SkillCatalogFiles
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.ui.skills.store.SkillStoreViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SkillStoreViewModelTest {
    @get:Rule val temp = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    @Test fun `搜索筛选分类计数排序及详情不受过滤影响`() = runTest(dispatcher) {
        val prefs = InMemoryPreferences()
        val catalog = FileSkillCatalog(temp.newFolder("user"), prefs, temp.newFolder("state"), dispatcher, { 200_000_000L }, SkillCatalogFiles(JvmBoundRealPathFileSystem()))
        val doc = "---\nname: alpha\ndescription: 测试\n---\n内容"
        val installed = catalog.installApproved(mapOf("SKILL.md" to doc.toByteArray()))
        prefs.edit().putString("skill_store_records", """[{"name":"alpha","source":"$OFFICIAL_SKILL_CATALOG_URL","official":true,"version":"1.0.0","archiveHash":"${"a".repeat(64)}","contentHash":"${installed.contentHash}","installedAt":1}]""").commit()
        fun entry(name: String, title: String, category: String, version: String) = """{"name":"$name","version":"$version","title":{"en":"$title"},"summary":{"en":"shared"},"category":"$category","archive":"$name.zip","size":5,"sha256":"${"a".repeat(64)}","files":[{"path":"SKILL.md","size":5}],"channels":["play"]}"""
        val json = """{"schema":1,"categories":[{"id":"a","title":{"en":"A"}},{"id":"b","title":{"en":"B"}}],"skills":[${entry("bravo", "Beta", "b", "1.0.0")},${entry("alpha", "Zeta", "a", "2.0.0")}]}"""
        val client = HttpClient(MockEngine { respond(json, HttpStatusCode.OK) })
        val repo = SkillStoreRepository(client, catalog, prefs, temp.newFolder("cache"), SkillChannel.PLAY, 126, io = dispatcher)
        repo.refresh(); val vm = SkillStoreViewModel(repo, catalog)
        // Ktor 的 MockEngine 让测试体在其工作线程上恢复，视图模型的首次收集因此异步到达，先等它落地。
        withContext(Dispatchers.Default) { withTimeout(5_000) { vm.state.first { it.categoryCounts.isNotEmpty() } } }
        assertThat(vm.state.value.items.map { it.entry.name }).containsExactly("alpha", "bravo").inOrder()
        assertThat(vm.state.value.categoryCounts[null]).isEqualTo(2)
        vm.setQuery("SHARED"); vm.setFilter(StoreFilter.UPDATES)
        assertThat(vm.state.value.items.map { it.entry.name }).containsExactly("alpha")
        assertThat(vm.state.value.categoryCounts["a"]).isEqualTo(1)
        assertThat(vm.state.value.categoryCounts["b"]).isEqualTo(0)
        vm.setCategory("b"); assertThat(vm.state.value.items).isEmpty()
        assertThat(vm.item("$OFFICIAL_SKILL_CATALOG_URL#alpha").value?.entry?.name).isEqualTo("alpha")
        vm.setCategory(null); vm.setFilter(StoreFilter.INSTALLED)
        assertThat(vm.state.value.installedCount).isEqualTo(1)
        client.close()
    }
}
