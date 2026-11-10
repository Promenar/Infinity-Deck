package com.promenar.infinity.ui.document.external

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.share.core.ShareKnowledgeFolder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalDocumentViewerSupportTest {
    @Test
    fun `位置按存储空间路径显示 根目录只显示存储空间`() {
        assertThat(storageLocationLabel("存储空间", "/")).isEqualTo("存储空间")
        assertThat(storageLocationLabel("存储空间", "/工作/周报")).isEqualTo("存储空间 / 工作 / 周报")
    }

    @Test
    fun `记住的文件夹层级可往返 损坏时为空`() {
        val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("external-folder-memory-test", 0)
        val memory = PreferencesExternalFolderMemory(preferences)
        val trail = listOf(ShareKnowledgeFolder("a", "工作", "/工作"), ShareKnowledgeFolder("b", "周报", "/工作/周报"))

        assertThat(memory.load()).isEmpty()
        memory.save(trail)
        assertThat(memory.load()).isEqualTo(trail)

        preferences.edit().putString("last_storage_folder_trail", "{broken").commit()
        assertThat(memory.load()).isEmpty()
    }
}
