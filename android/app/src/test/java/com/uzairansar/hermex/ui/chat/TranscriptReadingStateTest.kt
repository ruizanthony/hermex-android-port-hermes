package com.uzairansar.hermex.ui.chat

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class TranscriptReadingStateTest {
    @Test fun savedReaderDoesNotAutoFollowOnReturnButAFollowerStillDoes() {
        for (following in listOf(false, true)) {
            val original = TranscriptReadingState(following, !following)
            val saved = with(TranscriptReadingState.Saver) { SaverScope { true }.save(original) }
            val restored = TranscriptReadingState.Saver.restore(saved!!)!!
            assertEquals(following, restored.followState.value)
            assertEquals(!following, restored.olderState.value)
            assertEquals(following, shouldAutoScrollTranscript(restored.followState.value, false))
            assertEquals(following, transcriptFollowState(restored.followState.value,
                TranscriptScrollObservation(false, false, true)))
        }
    }
}
