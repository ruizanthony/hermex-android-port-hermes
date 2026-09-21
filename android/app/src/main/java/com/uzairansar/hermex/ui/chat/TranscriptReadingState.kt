package com.uzairansar.hermex.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver

/** Saved alongside LazyListState, never alongside a transcript or a ViewModel. */
internal class TranscriptReadingState(following: Boolean = true, readingOlder: Boolean = false) {
    val followState = mutableStateOf(following)
    val olderState = mutableStateOf(readingOlder)
    companion object {
        val Saver = listSaver<TranscriptReadingState, Boolean>(
            save = { listOf(it.followState.value, it.olderState.value) },
            restore = { TranscriptReadingState(it[0], it[1]) },
        )
    }
}
