package com.uzairansar.hermex.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ArchiveSuccessorTest {
    @Test fun preGestureOrderChoosesNextThenPreviousAndNeverWraps() {
        assertEquals("c", archiveSuccessor(listOf("a", "b", "c"), "a", setOf("a", "b")))
        assertEquals("b", archiveSuccessor(listOf("a", "b", "c"), "c", setOf("c")))
        assertNull(archiveSuccessor(listOf("a"), "a", setOf("a")))
        assertNull(archiveSuccessor(listOf("a", "b"), "missing", emptySet()))
    }
}
