package com.promenar.infinity.ui.files

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationCapabilities
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.ui.files.state.FileManagerUiState
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Test

/** 设置次级页形态（Linux 主目录）的列表规则与列表行时间格式。 */
class FileSettingsPageTest {
    private val zone: ZoneId = ZoneOffset.ofHours(8)
    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private val caps = FileLocationCapabilities(writable = true, deletable = true, archive = true)
    private val home = FileLocation("linux:home", FileLocationKind.LINUX_HOME, "Linux 主目录", caps)

    @Test
    fun `同年省略年份，跨年写全`() {
        val now = at("2026-11-12T20:00")
        assertThat(formatFileListTime(at("2026-11-02T01:45"), now, zone)).isEqualTo("11-02 01:45")
        assertThat(formatFileListTime(at("2026-01-01T00:00"), now, zone)).isEqualTo("01-01 00:00")
        assertThat(formatFileListTime(at("2025-12-31T23:59"), now, zone)).isEqualTo("2025-12-31 23:59")
        // 年份按所给时区计算：东八区已进入 2026 年、UTC 仍在 2025 年时，文件算作 2026 年。
        assertThat(formatFileListTime(at("2026-01-01T07:30"), at("2025-12-31T09:00"), zone)).isEqualTo("2026-01-01 07:30")
    }

    @Test
    fun `设置次级页形态的子文件夹不显示上一级行，其它宿主照常`() {
        val sub = FileManagerUiState(location = home, path = "/starlog")
        val root = FileManagerUiState(location = home, path = "/")
        assertThat(sub.showUpEntry(FileManagerHostSpec())).isTrue()
        assertThat(sub.showUpEntry(FileManagerHostSpec(settingsPage = true))).isFalse()
        assertThat(root.showUpEntry(FileManagerHostSpec())).isFalse()
    }
}
