package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.linux.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LinuxMirrorServiceTest {
    private val directory = Files.createTempDirectory("linux-mirrors").toFile()
    private val runtime = LinuxPackageTestRuntime(File(directory, "rootfs"))
    private val preferences = MemoryMirrorPreferences()
    private fun service(http: LinuxMirrorHttp = LinuxMirrorHttp { true }) = LinuxMirrorService(runtime, preferences, http)
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun everyPresetWritesVersionedMainAndCommunity(): Unit = runBlocking {
        val service = service()
        for ((id, host) in listOf(
            LinuxMirrorId.TUNA to "mirrors.tuna.tsinghua.edu.cn", LinuxMirrorId.USTC to "mirrors.ustc.edu.cn",
            LinuxMirrorId.ALIYUN to "mirrors.aliyun.com", LinuxMirrorId.OFFICIAL to "dl-cdn.alpinelinux.org",
        )) {
            service.select(LinuxMirrorChoice(id))
            assertEquals("https://$host/alpine/v3.24/main\nhttps://$host/alpine/v3.24/community\n",
                File(runtime.paths.rootfs, "etc/apk/repositories").readText())
            assertEquals(id, service.selected.value.id)
        }
    }

    @Test fun customHttpMirrorWritesRepositories(): Unit = runBlocking {
        service().select(LinuxMirrorChoice(LinuxMirrorId.CUSTOM, "http://192.168.1.2/alpine"))
        assertEquals("http://192.168.1.2/alpine/v3.24/main\nhttp://192.168.1.2/alpine/v3.24/community\n",
            File(runtime.paths.rootfs, "etc/apk/repositories").readText())
    }

    @Test fun customRootNormalizesAlpinePrefixAndRejectsUnsafeUrls(): Unit = runBlocking {
        val service = service()
        for (url in listOf("https://mirror.example", "https://mirror.example/alpine/")) {
            service.select(LinuxMirrorChoice(LinuxMirrorId.CUSTOM, url))
            assertEquals("https://mirror.example/alpine/v3.24/main\nhttps://mirror.example/alpine/v3.24/community\n",
                File(runtime.paths.rootfs, "etc/apk/repositories").readText())
        }
        for (url in listOf("ftp://mirror.example", "https://u:p@mirror.example", "https://mirror.example/?x=1", "https://mirror.example/#frag", "https://mirror.example/\nhttps://evil", "https://mirror.example/%0aevil", "https://mirror.example:0", "file:///tmp/mirror")) {
            try { service.select(LinuxMirrorChoice(LinuxMirrorId.CUSTOM, url)); fail(url) } catch (_: IllegalArgumentException) { }
        }
        assertEquals(LinuxMirrorId.CUSTOM, service.selected.value.id)
    }

    @Test fun notInstalledPersistsSelectionAndReplaysWhenRootfsExists(): Unit = runBlocking {
        runtime.paths.rootfs.deleteRecursively()
        runtime.state.value = LinuxRuntimeState.NotInstalled(0)
        val service = service()
        assertEquals(LinuxMirrorId.TUNA, service.selected.value.id)
        service.select(LinuxMirrorChoice(LinuxMirrorId.USTC))
        assertFalse(runtime.paths.rootfs.exists())
        runtime.world("")
        File(runtime.paths.rootfs, "etc/alpine-release").writeText("3.24.3\n")
        service.select(service.selected.value)
        assertTrue(File(runtime.paths.rootfs, "etc/apk/repositories").readText().contains("mirrors.ustc.edu.cn"))
        assertEquals(LinuxMirrorId.USTC, service().selected.value.id)
    }

    @Test fun symbolicLinkTargetAndParentAreRejectedWithoutChangingSelection(): Unit = runBlocking {
        val target = File(directory, "outside").apply { writeText("keep") }
        val repositories = File(runtime.paths.rootfs, "etc/apk/repositories")
        Files.createSymbolicLink(repositories.toPath(), target.toPath())
        try { service().select(LinuxMirrorChoice(LinuxMirrorId.OFFICIAL)); fail() } catch (_: java.io.IOException) { }
        assertEquals("keep", target.readText())
        assertEquals(LinuxMirrorId.TUNA, preferences.choice.id)
        repositories.delete()
        File(runtime.paths.rootfs, "etc/apk").deleteRecursively()
        Files.createSymbolicLink(File(runtime.paths.rootfs, "etc/apk").toPath(), directory.toPath())
        try { service().select(LinuxMirrorChoice(LinuxMirrorId.USTC)); fail() } catch (_: java.io.IOException) { }
        assertFalse(File(directory, "repositories").exists())
    }

    @Test fun invalidReleaseDoesNotWriteOrPersist(): Unit = runBlocking {
        File(runtime.paths.rootfs, "etc/alpine-release").writeText("edge\n")
        try { service().select(LinuxMirrorChoice(LinuxMirrorId.OFFICIAL)); fail() } catch (_: IllegalArgumentException) { }
        assertFalse(File(runtime.paths.rootfs, "etc/apk/repositories").exists())
        assertEquals(LinuxMirrorId.TUNA, preferences.choice.id)
    }

    @Test fun persistenceFailureRestoresTheRepositoryAndSelectedChoice(): Unit = runBlocking {
        service().select(LinuxMirrorChoice(LinuxMirrorId.TUNA))
        val repository = File(runtime.paths.rootfs, "etc/apk/repositories")
        val before = repository.readText()
        var failSave = true
        val failedPreferences = object : LinuxMirrorPreferences by preferences {
            override fun saveChoice(choice: LinuxMirrorChoice) {
                if (failSave) { failSave = false; throw java.io.IOException("disk error") }
                preferences.saveChoice(choice)
            }
        }
        val service = LinuxMirrorService(runtime, failedPreferences, LinuxMirrorHttp { true })
        try { service.select(LinuxMirrorChoice(LinuxMirrorId.USTC)); fail() } catch (_: java.io.IOException) { }
        assertEquals(before, repository.readText())
        assertEquals(LinuxMirrorId.TUNA, service.selected.value.id)
        assertEquals(LinuxMirrorId.TUNA, service().selected.value.id)
    }

    @Test fun speedTestEmitsSequentialResultsIncludesCustomAndPersistsFailures() = runTest {
        val urls = mutableListOf<String>()
        val service = service(LinuxMirrorHttp { url ->
            urls += url
            if (url.contains("ustc")) delay(6_000) else delay(20)
            !url.contains("aliyun")
        })
        service.select(LinuxMirrorChoice(LinuxMirrorId.CUSTOM, "https://mirror.example/alpine"))
        val results = service.speedTest().toList()
        assertEquals(listOf(LinuxMirrorId.TUNA, LinuxMirrorId.USTC, LinuxMirrorId.ALIYUN, LinuxMirrorId.OFFICIAL, LinuxMirrorId.CUSTOM), results.map { it.first })
        assertNotNull(results[0].second)
        assertNull(results[1].second)
        assertNull(results[2].second)
        assertTrue(urls.all { it.endsWith("/alpine/v3.24/main/aarch64/APKINDEX.tar.gz") })
        assertEquals(results.toMap(), service.latencies.value)
        assertEquals(results.toMap(), service().latencies.value)
    }

    @Test fun cancellationDoesNotBecomeACompletedSpeedTest() = runTest {
        val started = CompletableDeferred<Unit>()
        val service = service(LinuxMirrorHttp { started.complete(Unit); awaitCancellation() })
        val job = launch { service.speedTest().toList() }
        started.await()
        job.cancelAndJoin()
        assertTrue(preferences.results.isEmpty())
    }
}

internal class MemoryMirrorPreferences : LinuxMirrorPreferences {
    var choice = LinuxMirrorChoice(LinuxMirrorId.TUNA)
    var results: Map<LinuxMirrorId, Long?> = emptyMap()
    override fun readChoice() = choice
    override fun readLatencies() = results
    override fun saveChoice(choice: LinuxMirrorChoice) { this.choice = choice }
    override fun saveLatencies(latencies: Map<LinuxMirrorId, Long?>) { results = latencies.toMap() }
}
