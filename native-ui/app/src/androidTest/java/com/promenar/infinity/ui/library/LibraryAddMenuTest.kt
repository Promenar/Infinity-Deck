package com.promenar.infinity.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryAddMenuTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun addMenuDismissesAndDispatchesExactlyTheSelectedAction() {
        val actions = mutableListOf<Int>()
        val record = R.string.recording_entry
        val importFiles = R.string.library_add_import
        val note = R.string.library_note_title
        val add = rule.activity.getString(R.string.library_add_action)
        rule.setContent {
            InfinityTheme {
                LibraryAddMenu(
                    onRecord = { actions.add(record) },
                    onImport = { actions.add(importFiles) },
                    onNote = { actions.add(note) },
                )
            }
        }
        fun node(label: Int) = rule.onNodeWithText(rule.activity.getString(label))

        rule.onNodeWithText(add).performClick()
        node(record).assertIsDisplayed()
        node(importFiles).assertIsDisplayed()
        node(note).assertIsDisplayed()
        pressBack()
        node(record).assertDoesNotExist()
        rule.runOnIdle { assertEquals(emptyList<Int>(), actions) }

        listOf(record, importFiles, note).forEach { action ->
            rule.onNodeWithText(add).performClick()
            node(action).performClick()
            node(action).assertDoesNotExist()
        }
        rule.runOnIdle { assertEquals(listOf(record, importFiles, note), actions) }
    }
}
