package com.promenar.infinity.data.skills.store

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.skills.FileSkillCatalog
import com.promenar.infinity.data.skills.InMemoryPreferences
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.data.skills.SkillCatalogFiles
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillStoreRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val prefs = InMemoryPreferences()
    private val user by lazy { temp.newFolder("user") }
    private val state by lazy { temp.newFolder("state") }
    private val cache by lazy { temp.newFolder("cache") }
    private val doc = "---\nname: demo\ndescription: 测试\n---\n正文"
    private val zip by lazy { ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { it.putNextEntry(ZipEntry("SKILL.md")); it.write(doc.toByteArray()); it.closeEntry() } }.toByteArray() }
    private val hash by lazy { MessageDigest.getInstance("SHA-256").digest(zip).joinToString("") { "%02x".format(it) } }
    private var version = "1.0.0"
    private var requests = 0
    private var fail = false
    private var now = 200_000_000L
    private val client by lazy { HttpClient(MockEngine { request ->
        requests++
        if (fail) respond("", HttpStatusCode.BadGateway)
        else if (request.url.encodedPath.endsWith(".zip")) respond(zip, HttpStatusCode.OK)
        else respond("""{"schema":1,"title":{"en":"Catalog"},"categories":[{"id":"general","title":{"en":"General"}}],"skills":[{"name":"demo","version":"$version","title":{"en":"Demo"},"summary":{"en":"Text"},"category":"general","author":"A","license":"MIT","archive":"demo.zip","size":${zip.size},"sha256":"$hash","files":[{"path":"SKILL.md","size":${doc.toByteArray().size}}],"channels":["sideload"],"minAppVersionCode":200}]}""", HttpStatusCode.OK)
    }) }
    private val catalog by lazy { FileSkillCatalog(user, prefs, state, Dispatchers.Unconfined, { now }, SkillCatalogFiles(JvmBoundRealPathFileSystem())) }
    private fun repo() = SkillStoreRepository(client, catalog, prefs, cache, SkillChannel.PLAY, 126, clock = { now }, io = Dispatchers.Unconfined)
    private fun key(url: String = OFFICIAL_SKILL_CATALOG_URL) = "$url#demo"
    private suspend fun failure(block: suspend () -> Unit): StoreFailure = (runCatching { block() }.exceptionOrNull() as StoreException).failure

    @Test fun `来源校验上限失败不保存及官方不可移除`() = runTest {
        val r = repo(); r.initialize()
        assertThat(failure { r.addSource("http://bad.test/a") }).isEqualTo(StoreFailure.INVALID_URL)
        assertThat(failure { r.addSource(OFFICIAL_SKILL_CATALOG_URL) }).isEqualTo(StoreFailure.DUPLICATE_SOURCE)
        fail = true; assertThat(failure { r.addSource("https://bad.test/index") }).isEqualTo(StoreFailure.HTTP)
        assertThat(r.snapshot.value.sources).hasSize(1)
        fail = false
        repeat(10) { r.addSource("https://source$it.test/index") }
        assertThat(failure { r.addSource("https://extra.test/index") }).isEqualTo(StoreFailure.SOURCE_LIMIT)
        r.removeSource(OFFICIAL_SKILL_CATALOG_URL); assertThat(r.snapshot.value.sources).hasSize(11)
        assertThat(repo().also { it.initialize() }.snapshot.value.sources).hasSize(11)
    }
    @Test fun `安装更新状态本地修改外部删除及离线缓存`() = runTest {
        val r = repo(); r.refresh(); var item = r.snapshot.value.items.single()
        assertThat(item.installState).isEqualTo(StoreInstallState.NotInstalled)
        assertThat(item.limitedOnChannel).isTrue(); assertThat(item.requiresAppUpdate).isTrue()
        assertThat((r.install(key(), false) {} as InstallResult.Done).enabled).isTrue()
        item = r.snapshot.value.items.single(); assertThat(item.installState).isInstanceOf(StoreInstallState.Installed::class.java)
        catalog.setEnabled(catalog.scan(null).user.single(), false)
        version = "2.0.0"; r.refresh(); assertThat(r.updateCount.value).isEqualTo(1)
        val updated = r.install(key(), false) {} as InstallResult.Done
        assertThat(updated.updated).isTrue(); assertThat(updated.enabled).isFalse()
        user.resolve("demo/SKILL.md").appendText("修改")
        r.rescan(); assertThat(r.snapshot.value.items.single().localModified).isTrue()
        assertThat((r.install(key(), false) {} as InstallResult.Confirm).reason).isEqualTo(ConfirmReason.LOCAL_MODIFIED)
        fail = true; r.refresh(); assertThat(r.snapshot.value.sources.single().status).isInstanceOf(SourceStatus.Failed::class.java)
        assertThat(repo().also { it.initialize() }.snapshot.value.items).hasSize(1)
        catalog.delete(catalog.scan(null).user.single()); r.rescan()
        assertThat(r.snapshot.value.items.single().installState).isEqualTo(StoreInstallState.NotInstalled)
        assertThat(prefs.getString("skill_store_records", "")).isEqualTo("[]")
    }
    @Test fun `第三方安装默认停用并对同名占用确认`() = runTest {
        val r = repo(); val url = r.addSource("https://third.test/index")
        catalog.installApproved(mapOf("SKILL.md" to doc.toByteArray()))
        assertThat((r.install(key(url), false) {} as InstallResult.Confirm).reason).isEqualTo(ConfirmReason.NAME_TAKEN)
        assertThat((r.install(key(url), true) {} as InstallResult.Done).enabled).isFalse()
        r.uninstall(key(url)); assertThat(catalog.scan(null).user).isEmpty()
    }
    @Test fun `自动检查节流和关闭`() = runTest {
        val r = repo(); r.maybeAutoCheck(); val first = requests
        r.maybeAutoCheck(); assertThat(requests).isEqualTo(first)
        now += 24 * 60 * 60 * 1000L + 1; r.setAutoCheck(false); r.maybeAutoCheck(); assertThat(requests).isEqualTo(first)
        r.setAutoCheck(true); r.maybeAutoCheck(); assertThat(requests).isGreaterThan(first)
    }
}
