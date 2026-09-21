package com.uzairansar.hermex.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

/**
 * The raw string remains the source of truth, never replaced by the preview.
 * Short messages keep their ordinary selectable Text. Long messages lay out at most
 * 2,000 UTF-16 units inline, and open a separate bounded reader, never an expansion.
 * contentKey is message identity, NOT text: appending a stream must not close the reader.
 * Callers already inside a keyed transcript item may use the default identity.
 */
@Composable
internal fun BoundedTranscriptText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    contentKey: Any = Unit,
) {
    key(contentKey) {
        var readerOpen by rememberSaveable { mutableStateOf(false) }
        // Keep scroll saving in the owning composition, not the separate Dialog window.
        val readerState = rememberLazyListState()
        if (text.length <= TRANSCRIPT_INLINE_CHARACTERS) {
            SelectionContainer {
                Text(text = text, modifier = modifier, style = style, color = color)
            }
        } else {
            val preview = remember(text) {
                text.substring(0, transcriptChunkEnd(text, 0, TRANSCRIPT_CHUNK_CHARACTERS))
            }
            Column(modifier) {
                SelectionContainer {
                    Text(
                        text = preview,
                        modifier = Modifier.testTag("transcript_preview"),
                        style = style,
                        color = color,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = { readerOpen = true },
                    modifier = Modifier.testTag("transcript_read_full"),
                ) { Text("Lire intégralement") }
            }
        }
        if (readerOpen) {
            Dialog(onDismissRequest = { readerOpen = false }) {
                Surface(shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        TextButton(
                            onClick = { readerOpen = false },
                            modifier = Modifier.testTag("transcript_reader_close"),
                        ) { Text("Fermer") }
                        BoundedTranscriptReader(text = text, contentKey = contentKey, style = style, state = readerState)
                    }
                }
            }
        }
    }
}

/**
 * Shared by Select Text and the dialog; also available to performance instrumentation.
 * Only visible chunks enter Text/semantics. Rows have a minimum height so even tiny
 * pathological chunks cannot cause an unbounded number of items to fill the viewport.
 * Selection is per chunk; the existing message Copy action still copies the entire raw text.
 */
@Composable
internal fun BoundedTranscriptReader(
    text: String,
    modifier: Modifier = Modifier,
    contentKey: Any = Unit,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    state: LazyListState? = null,
) {
    key(contentKey) {
        val chunks = remember(text) { markdownPlainTextChunks(text, TRANSCRIPT_CHUNK_CHARACTERS) }
        val listState = state ?: rememberLazyListState()
        val scope = rememberCoroutineScope()
        Column(modifier.heightIn(max = 520.dp).testTag("transcript_reader")) {
            Row {
                TextButton(
                    onClick = { scope.launch { listState.scrollToItem(0) } },
                    modifier = Modifier.testTag("transcript_reader_start"),
                ) { Text("Début") }
                TextButton(
                    onClick = { scope.launch { listState.scrollToItem(chunks.lastIndex) } },
                    modifier = Modifier.testTag("transcript_reader_end"),
                ) { Text("Fin") }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("transcript_reader_list"),
            ) {
                items(count = chunks.size, key = { it }) { index ->
                    SelectionContainer {
                        Text(
                            text = chunks[index],
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .padding(vertical = 4.dp).testTag("transcript_chunk_$index"),
                            style = style,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
