package com.uzairansar.hermex.ui.chat

import org.junit.Assert.*
import org.junit.Test

class BoundedTranscriptTextTest {
    @Test fun readerReconstructsMultiMegabyteCorpusIncludingItsTail() {
        val raw = "début\r\n" + "plain e\u0301 \uD83D\uDE00 👩‍💻\r\n".repeat(100_000) + "EXACT END"
        val chunks = markdownPlainTextChunks(raw, TRANSCRIPT_CHUNK_CHARACTERS)
        assertTrue(chunks.all { it.isNotEmpty() && it.length <= TRANSCRIPT_CHUNK_CHARACTERS })
        assertTrue(chunks.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
        assertEquals(raw, chunks.joinToString(""))
        assertTrue(chunks.last().endsWith("EXACT END"))
    }

    @Test fun pathologicalGraphemeCannotExceedTheHardBudgetOrLoseCharacters() {
        val raw = "e" + "\u0301".repeat(20_000) + "\uD83D\uDE00"
        val chunks = markdownPlainTextChunks(raw, TRANSCRIPT_CHUNK_CHARACTERS)
        assertEquals(raw, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= TRANSCRIPT_CHUNK_CHARACTERS })
        assertEquals(listOf(""), markdownPlainTextChunks("", TRANSCRIPT_CHUNK_CHARACTERS))
    }

    @Test fun streamingAppendKeepsCompletedChunkIndicesAndPreviewPrefixStable() {
        val initial = "A\uD83D\uDE00 e\u0301\r\n".repeat(4_000)
        val before = markdownPlainTextChunks(initial, TRANSCRIPT_CHUNK_CHARACTERS)
        val after = markdownPlainTextChunks(initial + "more data".repeat(1_000), TRANSCRIPT_CHUNK_CHARACTERS)
        before.dropLast(1).forEachIndexed { index, chunk -> assertEquals(chunk, after[index]) }
        assertEquals(before.first(), after.first())
    }

    @Test fun ordinarySelectionKeepsItsOriginalSingleSelectableText() {
        val selection = source("ChatRoute.kt").substringAfter("fun SelectableMessageTextSheet(").substringBefore("private fun EditMessageSheet")
        assertTrue(selection.contains("if (text.length > TRANSCRIPT_INLINE_CHARACTERS)"))
        assertTrue(selection.contains("SelectionContainer"))
        assertTrue(selection.contains(".verticalScroll(scrollState)"))
    }

    @Test fun toolHeadersAndAttachmentReadersCannotBypassTheGuard() {
        val route = source("ChatRoute.kt")
        val liveTool = route.substringAfter("fun LiveToolActivityCard(").substringBefore("\n@Composable")
        assertFalse("Single-line ellipsis still lays out the full string", liveTool.contains("Text(\n                trimmed,"))
        val tools = route.substringAfter("fun ToolActivityCard(").substringBefore("\n@Composable")
        assertFalse(tools.contains("text = summary,"))
        val tool = route.substringAfter("fun ToolCallCard(").substringBefore("\n@Composable")
        assertTrue(tool.contains("BoundedTranscriptText("))
        val row = route.substringAfter("fun MessageRow(").substringBefore("\n@Composable")
        assertTrue(row.contains("if (visibleText.length > TRANSCRIPT_INLINE_CHARACTERS) null"))
        assertTrue(route.contains("BoundedTranscriptReader(text = content)"))
    }

    @Test fun everyTranscriptSurfaceUsesTheBudgetWithoutParsingLongMedia() {
        val route = source("ChatRoute.kt")
        listOf("UserMessageBubble", "ReasoningAccessoryCard", "ToolDetailSection", "MarkerMessageCard").forEach { name ->
            val body = route.substringAfter("fun $name(").substringBefore("\n@Composable")
            assertTrue("$name bypasses the text budget", body.contains("BoundedTranscriptText("))
        }
        val selection = route.substringAfter("fun SelectableMessageTextSheet(").substringBefore("private fun EditMessageSheet")
        assertTrue(selection.contains("BoundedTranscriptReader("))
        val assistant = route.substringAfter("fun AssistantMessageRow(").substringBefore("private fun TranscriptMediaContentView")
        assertTrue(assistant.contains("if (visibleText.length > TRANSCRIPT_INLINE_CHARACTERS)"))
        assertTrue(assistant.indexOf("if (visibleText.length > TRANSCRIPT_INLINE_CHARACTERS)") < assistant.indexOf("TranscriptMediaParser.segments"))
        assertTrue(source("MarkdownText.kt").substringAfter("private fun CodeText(").substringBefore("private sealed interface").contains("BoundedTranscriptText("))
    }

    @Test fun readerDisclosureSurvivesSavedStateWithoutSavingTheRawString() {
        val reader = source("BoundedTranscriptText.kt")
        assertTrue(reader.contains("rememberSaveable { mutableStateOf(false) }"))
        assertFalse(reader.contains("rememberSaveable(text)"))
    }

    // Source-level wiring contract complements the device tests: these paths must
    // branch before parsing and may never eagerly compose the full chunk list.
    @Test fun markdownGuardsBeforeAnyParserAndReaderIsHeightBoundedAndLazy() {
        val markdown = source("MarkdownText.kt")
        val entry = markdown.substringAfter("fun MarkdownText(").substringBefore("private fun StructuredMarkdownText")
        assertTrue("Guard belongs before streaming/Markdown parsing", entry.contains("if (markdown.length > TRANSCRIPT_INLINE_CHARACTERS)"))
        assertTrue(entry.indexOf("BoundedTranscriptText(") < entry.indexOf("val motion ="))
        assertFalse("Chunking is not virtualization", markdown.contains("plainTextChunks.forEach"))
        val reader = source("BoundedTranscriptText.kt")
        assertTrue(reader.contains("LazyColumn("))
        assertTrue(reader.contains("heightIn(max = 520.dp)"))
        assertFalse(reader.contains("chunks.forEach"))
        assertFalse(reader.contains("AnnotatedString(text)"))
        assertTrue("State must be keyed by identity, never streamed text", reader.contains("key(contentKey)"))
        assertFalse(reader.contains("remember(text) { mutableStateOf"))
    }

    private fun source(name: String): String {
        val file = java.io.File("src/main/java/com/uzairansar/hermex/ui/chat/$name")
        assertTrue("Missing production surface $name", file.isFile)
        return file.readText()
    }

    @Test fun readerBoundariesPreserveCombiningMarksAndCrLf() {
        val raw = "abce\u0301\r\nfin\uD83D\uDE00"
        val chunks = markdownPlainTextChunks(raw, 4)
        assertEquals(raw, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 4 })
        assertFalse("Do not detach a combining accent", chunks.any { it.startsWith("\u0301") })
        assertFalse("Do not split CRLF", chunks.any { it.startsWith("\n") })
        assertFalse(chunks.any { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
    }

    @Test fun longTextEntersBoundedPathImmediatelyAboveFourThousand() {
        assertNull(markdownPlainTextChunksForLargeContent("x".repeat(4_000)))
        val raw = "x".repeat(4_001)
        val chunks = markdownPlainTextChunksForLargeContent(raw)
        assertNotNull("Large messages must not reach the ordinary renderer", chunks)
        assertTrue(chunks!!.all { it.length <= 2_000 })
        assertEquals(raw, chunks.joinToString(""))
    }
}
