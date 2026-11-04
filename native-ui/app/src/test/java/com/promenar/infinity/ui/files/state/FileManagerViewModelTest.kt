package com.promenar.infinity.ui.files.state

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileManagerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `工厂按宿主选择位置流并随 ViewModel 清理`() = runTest(dispatcher) {
        val services = FakeFileServices()
        services.locations.library.value = listOf(location("library"))
        services.locations.session.value = listOf(location("workspace"))
        services.locations.linux.value = location("linux")
        val libraryStore = ViewModelStore()
        val sessionStore = ViewModelStore()
        val linuxStore = ViewModelStore()
        val library = ViewModelProvider(libraryStore, FileManagerViewModel.factory(services, FileManagerScope.Library))[FileManagerViewModel::class.java]
        val session = ViewModelProvider(sessionStore, FileManagerViewModel.factory(services, FileManagerScope.Session("session-example")))[FileManagerViewModel::class.java]
        val linux = ViewModelProvider(linuxStore, FileManagerViewModel.factory(services, FileManagerScope.LinuxHome))[FileManagerViewModel::class.java]
        runCurrent()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                library.state.first { it.location?.id == "library" }
                session.state.first { it.location?.id == "workspace" }
                linux.state.first { it.location?.id == "linux" }
            }
        }
        assertThat(services.locations.requestedSession).isEqualTo("session-example")
        services.locations.linux.value = null
        withContext(Dispatchers.Default) { withTimeout(5_000) { linux.state.first { it.location == null } } }
        libraryStore.clear()
        sessionStore.clear()
        linuxStore.clear()
    }
}
