package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.backup.appBackupSourceBases
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class BackupSourceBasesContractTest {
    private fun source(path: String) = String(Files.readAllBytes(Path.of(path)), Charsets.UTF_8)

    @Test
    fun `session workspace parent is a trusted base and earlier bases keep their journal order`() {
        val filesDir = Files.createTempDirectory("backup-bases").toFile()
        try {
            val application = source("app/src/main/java/com/promenar/infinity/InfinityApplication.kt")
            // 会话专用空间的根目录挂在这个父目录下；不在受信 base 中时，导出会跳过其中文件，恢复会在提交时失败。
            val parent = Regex("""sessionWorkspaceParent: java\.nio\.file\.Path\s+get\(\) = File\(filesDir, "([^"]+)"\)""")
                .find(application)?.groupValues?.get(1)

            val bases = appBackupSourceBases(filesDir).map { filesDir.toPath().relativize(it).toString() }

            assertThat(parent).isNotNull()
            assertThat(bases).containsExactly("", "WorkSpace", "workspaces", parent).inOrder()
        } finally { filesDir.deleteRecursively() }
    }

    @Test
    fun `export and startup restore build the data source from the same bases`() {
        val repository = source("app/src/main/java/com/promenar/infinity/data/repository/BackupRepository.kt")
        val runtime = source("app/src/main/java/com/promenar/infinity/data/backup/BackupRuntime.kt")

        assertThat(repository).contains("trustedSourceBases = appBackupSourceBases(app.filesDir)")
        assertThat(runtime).contains("trustedSourceBases = appBackupSourceBases(appContext.filesDir)")
        assertThat(repository + runtime).doesNotContain("trustedSourceBases = linkedSetOf(")
    }
}
