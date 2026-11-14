package com.promenar.infinity.ui.library

import com.promenar.infinity.domain.files.FileManagerServices
import com.promenar.infinity.domain.files.FileOperations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryNoteSaveTest {
    @Test
    fun `自定义笔记标题使用可索引的 Markdown 扩展名`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fileOperations = mockk<FileOperations>()
            coEvery { fileOperations.importText(any(), any(), any()) } returns mockk()
            val files = mockk<FileManagerServices> {
                every { operations } returns fileOperations
                every { locations } returns mockk { every { observeLibraryLocations() } returns emptyFlow() }
                every { pins } returns mockk { every { observePins() } returns emptyFlow() }
            }
            val viewModel = LibraryViewModel(
                knowledge = mockk {
                    every { observeOverview() } returns emptyFlow()
                    every { observePending() } returns emptyFlow()
                    every { observeRecent(any()) } returns emptyFlow()
                    every { observeFragmentGroups() } returns emptyFlow()
                    every { observeNewMemoryIds() } returns emptyFlow()
                },
                files = files,
                citations = mockk {
                    every { observeRecentDocuments(any()) } returns emptyFlow()
                    every { observeTopDocuments(any()) } returns emptyFlow()
                },
            )

            viewModel.saveNote("会议纪要", "测试正文")
            viewModel.saveNote("同步记录.MD", "另一个正文")
            advanceUntilIdle()

            coVerify(exactly = 1) { fileOperations.importText("测试正文", LibraryViewModel.LIBRARY_ROOT, "会议纪要.md") }
            coVerify(exactly = 1) { fileOperations.importText("另一个正文", LibraryViewModel.LIBRARY_ROOT, "同步记录.MD") }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
