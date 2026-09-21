package com.uzairansar.hermex.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Test

class ActiveChatOwnerTest {
    private class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    @Test fun departingOldCompositionCannotClearNewSelection() {
        val owner = ActiveChatOwner()
        owner.select("a")
        val current = owner.select("b")
        val probe = Probe()
        current.put("chat", probe)
        owner.release("a")
        assertFalse(probe.cleared)
        owner.release("b")
        assertTrue(probe.cleared)
    }

    @Test fun recreatedCompositionOfSameIdentityMustNotBeClearedByItsPredecessor() {
        val owner = ActiveChatOwner()
        val old = owner.acquire("a")
        val current = owner.acquire("a")
        val probe = Probe().also { current.store.put("chat", it) }
        owner.release(old) // Old composition disposing after its replacement has acquired the slot.
        assertFalse("Identity alone is insufficient for overlapping recreation", probe.cleared)
        owner.release(current)
        assertTrue(probe.cleared)
    }

    @Test fun replacingSelectionClearsOldViewModelsAndReusesCurrentSelection() {
        val owner = ActiveChatOwner()
        val first = owner.select("server:a")
        val probe = Probe()
        first.put("chat", probe)
        assertSame(first, owner.select("server:a"))
        assertFalse(probe.cleared)
        val second = owner.select("server:b")
        assertNotSame(first, second)
        assertTrue(probe.cleared)
        val last = Probe()
        second.put("chat", last)
        val parent = ViewModelStore().apply { put("owner", owner) }
        parent.clear()
        assertTrue(last.cleared)
    }

    @Test fun repeatedTransitionsDoNotRetainOldChatOrGitViewModels() {
        val owner = ActiveChatOwner()
        val prior = mutableListOf<Probe>()
        repeat(100) { index ->
            val store = owner.select("server:$index")
            assertTrue(prior.all { it.cleared })
            listOf("chat", "git").forEach { key ->
                val probe = Probe()
                store.put(key, probe)
                prior += probe
            }
        }
        owner.release("server:99")
        assertTrue(prior.all { it.cleared })
    }
}
