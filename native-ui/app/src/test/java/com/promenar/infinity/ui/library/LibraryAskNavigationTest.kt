package com.promenar.infinity.ui.library

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LibraryAskNavigationTest {
    @Test fun `外屏输入与清空都不换页，避免输入框离开组合打断输入法`() {
        assertThat(libraryAskNavigation(twoPane = false, text = "a", askInStack = false)).isEqualTo(LibraryAskNavigation.STAY)
        assertThat(libraryAskNavigation(twoPane = false, text = "a", askInStack = true)).isEqualTo(LibraryAskNavigation.STAY)
        assertThat(libraryAskNavigation(twoPane = false, text = "", askInStack = true)).isEqualTo(LibraryAskNavigation.STAY)
    }

    @Test fun `两列时结果页随输入在右列开合`() {
        assertThat(libraryAskNavigation(twoPane = true, text = "a", askInStack = false)).isEqualTo(LibraryAskNavigation.OPEN)
        assertThat(libraryAskNavigation(twoPane = true, text = "ab", askInStack = true)).isEqualTo(LibraryAskNavigation.STAY)
        assertThat(libraryAskNavigation(twoPane = true, text = " ", askInStack = true)).isEqualTo(LibraryAskNavigation.CLOSE)
        assertThat(libraryAskNavigation(twoPane = true, text = "", askInStack = false)).isEqualTo(LibraryAskNavigation.STAY)
    }
}
