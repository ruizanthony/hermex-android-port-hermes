package com.uzairansar.hermex

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uzairansar.hermex.ui.chat.BoundedTranscriptText
import com.uzairansar.hermex.ui.chat.MarkdownText
import com.uzairansar.hermex.ui.chat.TRANSCRIPT_CHUNK_CHARACTERS
import com.uzairansar.hermex.ui.chat.markdownPlainTextChunks
import com.uzairansar.hermex.ui.theme.HermexTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoundedTranscriptTextInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val raw = "BEGIN " + "abc e\u0301 \uD83D\uDE00\r\n".repeat(4_000) + " FINAL SENTINEL"

    @Test fun boundedPreviewAndVirtualReaderReachTheExactEnd() {
        compose.setContent { HermexTheme { BoundedTranscriptText(raw) } }
        compose.onNodeWithTag("transcript_preview").assertTextContains("BEGIN ", substring = true)
        assertSemanticsBudget()
        compose.onNodeWithTag("transcript_read_full").assertIsDisplayed().performClick()
        val chunks = markdownPlainTextChunks(raw, TRANSCRIPT_CHUNK_CHARACTERS)
        compose.onNodeWithTag("transcript_reader_list").performScrollToIndex(chunks.lastIndex)
        compose.onNodeWithTag("transcript_chunk_${chunks.lastIndex}").assertTextEquals(chunks.last())
        compose.onNodeWithTag("transcript_chunk_${chunks.lastIndex}")
            .assertTextContains(" FINAL SENTINEL", substring = true)
        assertSemanticsBudget()
        val composedChunks = compose.onAllNodes(SemanticsMatcher("reader chunk") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("transcript_chunk_")
        }, useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue("The full transcript must not be composed", composedChunks < chunks.size)
        compose.onNodeWithTag("transcript_reader_close").performClick()
        compose.onNodeWithTag("transcript_reader").assertDoesNotExist()
        compose.onNodeWithTag("transcript_preview").assertTextContains("BEGIN ", substring = true)
    }

    @Test fun markdownReaderRemainsOpenAndKeepsPositionOnAppendButResetsForAnotherMessage() {
        val text = mutableStateOf(raw)
        val id = mutableStateOf("first-message")
        compose.setContent { HermexTheme { MarkdownText(text.value, isStreaming = true, contentKey = id.value) } }
        compose.onNodeWithTag("transcript_read_full").performClick()
        compose.onNodeWithTag("transcript_reader_list").performScrollToIndex(4)
        compose.runOnIdle { text.value += " STREAM APPEND" }
        compose.onNodeWithTag("transcript_chunk_4").assertIsDisplayed()
        compose.onNodeWithTag("transcript_reader_end").performClick()
        compose.onNodeWithText(" STREAM APPEND", substring = true).assertExists()
        assertSemanticsBudget()
        compose.runOnIdle {
            id.value = "second-message"
            text.value = "SECOND " + "z".repeat(12_000)
        }
        compose.onNodeWithTag("transcript_reader").assertDoesNotExist()
        compose.onNodeWithTag("transcript_preview").assertTextContains("SECOND ", substring = true)
    }

    @Test fun readerDisclosureAndScrollRestoreWithoutPersistingTranscriptInSavedState() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { HermexTheme { BoundedTranscriptText(raw, contentKey = "stable-message") } }
        compose.onNodeWithTag("transcript_read_full").performClick()
        compose.onNodeWithTag("transcript_reader_list").performScrollToIndex(4)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("transcript_reader").assertExists()
        compose.onNodeWithTag("transcript_chunk_4").assertIsDisplayed()
        assertSemanticsBudget()
    }

    private fun assertSemanticsBudget() {
        val values = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }
        assertTrue(values.isNotEmpty())
        assertTrue("No full transcript in semantics", values.all { it.text.length <= 4_000 })
    }
}
