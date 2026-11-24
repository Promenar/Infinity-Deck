package com.promenar.infinity.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.local.inference.LocalInferenceEngine
import com.promenar.infinity.data.local.inference.LoadConfig
import com.promenar.infinity.data.local.inference.LocalModelSlotStore
import com.promenar.infinity.data.local.inference.ModelDownloader
import com.promenar.infinity.data.local.inference.SlotState
import com.promenar.infinity.data.local.inference.SlotType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocalModelsViewModelTest {
    @Test fun `重新启用引擎只在显式开启时恢复槽位且手动加载不改变开关`() = runTest {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val path = File(context.filesDir, "model.gguf").apply { writeText("模型测试夹具") }.path
            val store = LocalModelSlotStore(context)
            store.saveSlotPath(SlotType.MAIN, path)
            val engine = mockk<LocalInferenceEngine> {
                every { mainSlot } returns MutableStateFlow(SlotState())
                every { embeddingSlot } returns MutableStateFlow(SlotState())
                every { rerankSlot } returns MutableStateFlow(SlotState())
                every { defaultLoadConfig(any()) } returns LoadConfig(threadCount = 1)
                coEvery { loadModel(any(), any(), any()) } returns Result.success(Unit)
                coEvery { unloadModel(any()) } returns Unit
            }
            val app = mockk<InfinityApplication> {
                every { applicationContext } returns context
                every { localInferenceEngine } returns engine
                every { recordings } returns mockk {
                    every { recommendedModel } returns mockk {
                        every { state } returns MutableStateFlow<ModelDownloader.DownloadState?>(null)
                    }
                }
            }
            val vm = LocalModelsViewModel(app, dispatcher)
            assertThat(vm.autoLoadOnStartup.value).isFalse()
            vm.setEngineEnabled(true)
            runCurrent()
            coVerify(exactly = 0) { engine.loadModel(any(), any(), any()) }
            assertThat(vm.savedSlotPaths.value[SlotType.MAIN]).isEqualTo(path)

            vm.loadModel(SlotType.EMBEDDING, path)
            runCurrent()
            assertThat(vm.autoLoadOnStartup.value).isFalse()
            assertThat(store.readAutoLoadOnStartup()).isFalse()

            vm.setEngineEnabled(false)
            runCurrent()
            vm.setAutoLoadOnStartup(true)
            assertThat(store.readAutoLoadOnStartup()).isTrue()
            vm.setEngineEnabled(true)
            runCurrent()
            coVerify(exactly = 1) { engine.loadModel(SlotType.MAIN, path, any()) }
            coVerify(exactly = 2) { engine.loadModel(SlotType.EMBEDDING, path, any()) }

            vm.setEngineEnabled(false)
            runCurrent()
            vm.setAutoLoadOnStartup(false)
            vm.setEngineEnabled(true)
            runCurrent()
            coVerify(exactly = 1) { engine.loadModel(SlotType.MAIN, path, any()) }
            assertThat(store.readAutoLoadOnStartup()).isFalse()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
