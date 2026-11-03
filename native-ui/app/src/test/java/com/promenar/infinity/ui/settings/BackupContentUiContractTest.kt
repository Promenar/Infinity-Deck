package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class BackupContentUiContractTest {
    @Test
    fun `core backup content cannot be toggled while keys remain optional`() {
        val initial = BackupUiState()

        assertThat(BackupUiState::class.java.declaredFields.map { it.name }).containsNoneOf(
            "sessionsChecked", "libraryChecked", "filesChecked", "settingsChecked",
        )
        assertThat(initial.withKeysIncluded(true).keysChecked).isTrue()
        assertThat(initial.keysChecked).isFalse()
    }

    @Test
    fun `screen exposes optional layers while core stays locked`() {
        val source = String(
            Files.readAllBytes(Path.of("app/src/main/java/com/promenar/infinity/ui/settings/BackupSettingsScreen.kt")),
            Charsets.UTF_8,
        )

        listOf("sessions", "library", "files", "settings").forEach { type ->
            assertThat(source).doesNotContain("toggleCheck(\"$type\"")
        }
        assertThat(source).contains("onIncludeKeysChange = viewModel::setIncludeKeys")
        assertThat(source).contains("onIncludeKeysChange = actions.onIncludeKeysChange")
        assertThat(source).contains("onLayerChange = { layer, selected -> viewModel.setLayerSelected(layer, selected) }")
        assertThat(source).contains("footer = backupLayersFooter(uiState)")
        assertThat(source).doesNotContain("settings_batch1_backup_core_footer")
        val layers = String(
            Files.readAllBytes(Path.of("app/src/main/java/com/promenar/infinity/ui/settings/BackupLayerSections.kt")),
            Charsets.UTF_8,
        )
        // 核心层开关常亮且不可关，密钥开启时加密锁定。
        assertThat(layers).contains("if (layer == BackupLayer.CORE)")
        assertThat(layers).contains("Icons.Rounded.Lock")
        assertThat(layers).contains("enabled = !state.keysChecked")
    }

    @Test
    fun `key export and cloud restore are wired to real gated entry points`() {
        val source = String(
            Files.readAllBytes(Path.of("app/src/main/java/com/promenar/infinity/ui/settings/BackupSettingsScreen.kt")),
            Charsets.UTF_8,
        )

        assertThat(source).contains("stringResource(R.string.backup_restore_cloud)")
        assertThat(source).contains("showExportPasswordDialog = true")
        assertThat(source).contains("showUploadPasswordDialog = true")
        assertThat(source).contains("onRefreshRemote = viewModel::listRemote")
        assertThat(source).contains("onClick = actions.onRefreshRemote")
        assertThat(source).contains("viewModel.restoreSelectedRemote(")
    }

    @Test
    fun `WebDAV sheet consumes async save result and exposes canonical recovery without optimistic dismissal`() {
        val source = String(
            Files.readAllBytes(Path.of("app/src/main/java/com/promenar/infinity/ui/settings/BackupSettingsScreen.kt")),
            Charsets.UTF_8,
        )

        assertThat(source).contains("saveAndTestWebDavConfig(")
        assertThat(source).contains("val accepted = viewModel.saveWebDavConfig(")
        assertThat(source).contains("if (accepted) tempWebdavPass = \"\"")
        assertThat(source).contains("viewModel.resetWebDavAuth()")
        assertThat(source).doesNotContain("if (accepted) showWebdavSheet = false")
        assertThat(source).contains("text = stringResource(R.string.backup_save_config)")
        val resetSection = source.substring(
            source.indexOf("val blockedCode ="),
            source.indexOf("private fun ExportButton"),
        )
        assertThat(resetSection).contains("BackupErrorCode.CONNECTION_FAILED")
        assertThat(resetSection).contains("BackupErrorCode.CONFIGURATION_MISSING")
        assertThat(resetSection.substringBefore("backupOperationText")).doesNotContain("BackupErrorCode.RESTORE_FAILED")
        assertThat(resetSection).contains("stringResource(R.string.backup_reset_webdav_security)")
        assertThat(source).doesNotContain("\"Exporting...\"")
        assertThat(source).doesNotContain("\"Importing...\"")
        assertThat(source).doesNotContain(", \"user\")")
        assertThat(source).contains("stringResource(R.string.backup_exporting)")
        assertThat(source).contains("if (uiState.isImporting) R.string.backup_importing")
        assertThat(source).contains("stringResource(R.string.backup_webdav_user_hint)")
    }

    @Test
    fun `production BackupRepository construction is deferred behind IO lazy operations`() {
        val source = String(
            Files.readAllBytes(Path.of("app/src/main/java/com/promenar/infinity/ui/settings/BackupViewModel.kt")),
            Charsets.UTF_8,
        )

        assertThat(source).contains("operations = decorateOperations(LazyBackupOperations(Dispatchers.IO)")
        assertThat(source).contains("RepositoryBackupOperations(BackupRepository(application))")
        assertThat(source).doesNotContain("operations = RepositoryBackupOperations(BackupRepository(application))")
    }

    @Test
    fun `backup copy accurately describes archive format and conditional encryption`() {
        val english = String(Files.readAllBytes(Path.of("app/src/main/res/values/strings.xml")), Charsets.UTF_8)
        val chinese = String(Files.readAllBytes(Path.of("app/src/main/res/values-zh-rCN/strings.xml")), Charsets.UTF_8)

        // 品牌名在资源中以 brand 实体书写，构建时展开。
        assertThat(english).contains(">&brand; secure archive<")
        assertThat(chinese).contains(">&brand; 安全归档<")
        assertThat(english).doesNotContain(">JSON Archive<")
        assertThat(chinese).doesNotContain(">JSON 归档<")
        assertThat(english).contains("Backups can be password-encrypted; encryption is required when keys are included.")
        assertThat(chinese).contains("备份可选使用密码加密；包含密钥时必须加密。")
        assertThat(english).doesNotContain("end-to-end encrypted cross-device sync")
        assertThat(chinese).doesNotContain("端到端加密的跨设备同步")
    }

    @Test
    fun `large accessibility fonts stack local backup actions`() {
        assertThat(shouldStackBackupActions(1.0f)).isFalse()
        assertThat(shouldStackBackupActions(1.49f)).isFalse()
        assertThat(shouldStackBackupActions(1.5f)).isTrue()
        assertThat(shouldStackBackupActions(2.0f)).isTrue()
    }
}
