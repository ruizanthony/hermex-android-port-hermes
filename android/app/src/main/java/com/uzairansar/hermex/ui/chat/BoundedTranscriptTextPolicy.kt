package com.uzairansar.hermex.ui.chat

import java.text.BreakIterator
import java.util.Locale

/** All budgets count UTF-16 code units, as Android text layout does. */
internal const val TRANSCRIPT_INLINE_CHARACTERS = 4_000
internal const val TRANSCRIPT_CHUNK_CHARACTERS = 2_000

/**
 * Prefer platform character boundaries in a small window, not a scan of the transcript.
 * Combining marks/CRLF are kept together when they fit. A pathological grapheme larger
 * than the budget is split at a code-point boundary: the hard layout cap wins, with no
 * characters discarded. Extended emoji segmentation follows the platform BreakIterator.
 */
internal fun transcriptChunkEnd(text: String, start: Int, maximumCharacters: Int): Int {
    require(maximumCharacters >= 2) { "A UTF-16 surrogate pair needs a budget of at least two" }
    val end = start + minOf(maximumCharacters, text.length - start)
    if (end == text.length) return end
    val windowStart = maxOf(start, end - 64).let {
        if (it > start && text[it].isLowSurrogate() && text[it - 1].isHighSurrogate()) it - 1 else it
    }
    val windowEnd = minOf(text.length, end + 64)
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text.substring(windowStart, windowEnd))
    val boundary = iterator.preceding(end - windowStart + 1)
    val candidate = if (boundary != BreakIterator.DONE && windowStart + boundary > start) {
        windowStart + boundary
    } else {
        end
    }
    return if (text[candidate - 1].isHighSurrogate() && text[candidate].isLowSurrogate()) {
        candidate - 1
    } else {
        candidate
    }
}
