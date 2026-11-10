package com.promenar.infinity.data.pet

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.pet.PetFile
import com.promenar.infinity.domain.pet.PetIssueCode
import com.promenar.infinity.domain.pet.PetLimits
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PetPackageStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun preferences(): PetPreferenceStore {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getBoolean(any(), any()) } answers { secondArg() }
        every { prefs.getString(any(), any()) } answers { secondArg() }
        every { prefs.edit() } returns editor
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return PetPreferenceStore(context)
    }

    private val metadata = PetMediaMetadataReader { file, path -> PetFile(path, file.length(), 100, 100) }
    private fun manifest(id: String = "test.pet", name: String = "宠物", redistribute: Boolean = true) = """
        {"contract":"0.2","id":"$id","name":"$name","version":"1","license":{"name":"test",
        "redistribute":$redistribute,"commercial":false},"display":{"height_dp":125,"facing":"right"},
        "clips":{"stand":{"type":"pose","file":"clips/stand.png","anchor":[0.5,1.0]}},
        "map":{"idle":{"clip":"stand"}}}
    """.trimIndent()
    private fun archive(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()
    private fun valid(manifest: String = manifest()) = archive(
        "manifest.json" to manifest, "thumbnail.webp" to "image", "clips/stand.png" to "image",
    )
    private suspend fun PetPackageStore.install(bytes: ByteArray) = import(ByteArrayInputStream(bytes))

    @Test fun `导入覆盖删除与导出恢复`() = runBlocking {
        val root = temporary.newFolder()
        val prefs = preferences()
        val store = PetPackageStore(root, prefs, metadata)
        val first = store.install(valid()) as PetImportResult.Installed
        assertThat(first.pet.directory.resolve("clips/stand.png").isFile).isTrue()
        assertThat(store.pets.value.map { it.id }).containsExactly("test.pet")
        store.install(valid(manifest(name = "新名字")))
        assertThat(store.pets.value.single().manifest.name).isEqualTo("新名字")
        val output = ByteArrayOutputStream()
        assertThat(store.export("test.pet", output)).isTrue()
        val other = PetPackageStore(temporary.newFolder(), preferences(), metadata)
        assertThat(other.install(output.toByteArray())).isInstanceOf(PetImportResult.Installed::class.java)
        prefs.setDefaultPetId("test.pet")
        prefs.setModelPet("model", "test.pet")
        prefs.setModelPet("other", "different")
        store.delete("test.pet")
        assertThat(File(root, "pets/test.pet").exists()).isFalse()
        assertThat(prefs.defaultPetId.value).isNull()
        assertThat(prefs.modelPets.value).containsExactly("other", "different")
    }

    @Test fun `路径大小解析和校验错误不安装`() = runBlocking {
        val root = temporary.newFolder()
        val store = PetPackageStore(root, preferences(), metadata, PetLimits(maxPackageBytes = 1024))
        val unsafe = store.install(archive("../bad.txt" to "x")) as PetImportResult.Invalid
        assertThat(unsafe.issues.single().code).isEqualTo(PetIssueCode.UNSAFE_PATH)
        val large = store.install(archive("large.txt" to "x".repeat(1025))) as PetImportResult.Invalid
        assertThat(large.issues.single().code).isEqualTo(PetIssueCode.PACKAGE_SIZE)
        assertThat(store.install(archive("a.txt" to "x"))).isEqualTo(PetImportResult.Failed(PetImportFailure.MANIFEST_MISSING))
        assertThat(store.install(archive("manifest.json" to "{"))).isEqualTo(PetImportResult.Failed(PetImportFailure.MANIFEST_UNREADABLE))
        assertThat(store.install(valid(manifest(id = "../bad")))).isInstanceOf(PetImportResult.Invalid::class.java)
        assertThat(store.install(valid(manifest().replace("\"idle\"", "\"other\"")))).isInstanceOf(PetImportResult.Invalid::class.java)
        assertThat(File(root, "pets").listFiles().orEmpty().toList()).isEmpty()
        assertThat(store.install("not zip".toByteArray())).isEqualTo(PetImportResult.Failed(PetImportFailure.NOT_A_ZIP))
    }

    @Test fun `不允许再分发且启动跳过损坏包`() = runBlocking {
        val root = temporary.newFolder()
        val prefs = preferences()
        val store = PetPackageStore(root, prefs, metadata)
        store.install(valid(manifest(redistribute = false)))
        assertThat(store.export("test.pet", ByteArrayOutputStream())).isFalse()
        File(root, "pets/broken").mkdirs()
        File(root, "pets/broken/manifest.json").writeText("{")
        val reloaded = PetPackageStore(root, prefs, metadata)
        val loaded = withTimeout(5_000) { reloaded.pets.first { it.isNotEmpty() } }
        assertThat(loaded.map { it.id }).containsExactly("test.pet")
    }
    @Test fun `文件数量与角色配置错误得到明确结果`() = runBlocking {
        val root = temporary.newFolder()
        val limited = PetPackageStore(root, preferences(), metadata, PetLimits(maxFiles = 2))
        val count = limited.install(valid()) as PetImportResult.Invalid
        assertThat(count.issues.single().code).isEqualTo(PetIssueCode.FILE_COUNT)
        val store = PetPackageStore(temporary.newFolder(), preferences(), metadata)
        val malformed = archive(
            "manifest.json" to manifest(), "thumbnail.webp" to "image", "clips/stand.png" to "image", "persona.json" to "{",
        )
        assertThat(store.install(malformed)).isEqualTo(PetImportResult.Failed(PetImportFailure.PERSONA_UNREADABLE))
    }
}
