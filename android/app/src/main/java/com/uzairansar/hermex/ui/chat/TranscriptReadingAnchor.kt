package com.uzairansar.hermex.ui.chat

import androidx.compose.ui.semantics.SemanticsPropertyKey

/** Exact viewport anchor: item index, pixel scroll offset and stable item identity.
 * Unlike VerticalScrollAxisRange, this does not estimate the height of unmeasured rows.
 */
internal val TranscriptReadingAnchor = SemanticsPropertyKey<Triple<Int, Int, String>>("TranscriptReadingAnchor")
internal val TranscriptLoadComplete = SemanticsPropertyKey<Boolean>("TranscriptLoadComplete")
