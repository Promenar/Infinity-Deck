package com.promenar.infinity.ui.rag

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RagSearchResultsTest {
    @Test
    fun `buildRagSearchSnippet strips leading markdown heading hashes`() {
        val markdownContent = """
            # Infinity Deck Audit Notes

            The launch code for the visual audit demo is BLUE-TIGER-42.

            ## Concepts
            Alpha indexing splits documents into chunks.
        """.trimIndent()

        val snippet = buildRagSearchSnippet(
            content = markdownContent,
            query = "BLUE-TIGER",
            maxChars = 180,
        )

        assertThat(snippet).doesNotContain("# ")
        assertThat(snippet).doesNotContain("## ")
        assertThat(snippet).contains("Infinity Deck Audit Notes")
        assertThat(snippet).contains("BLUE-TIGER-42")
    }

    @Test
    fun `buildRagSearchSnippet preserves normal hashtags that are not headings`() {
        val content = "Issue tracker reference #1234 fixed."
        val snippet = buildRagSearchSnippet(
            content = content,
            query = "1234",
            maxChars = 100,
        )

        assertThat(snippet).contains("#1234")
    }
}
