package com.uzairansar.hermex.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore

/** A detail slot owns at most one heavy conversation, including its Git VM. */
class ActiveChatOwner : ViewModel() {
    private var identity: String? = null
    private var store = ViewModelStore()

    fun select(identity: String): ViewModelStore {
        if (this.identity != identity) {
            store.clear()
            store = ViewModelStore()
            this.identity = identity
        }
        return store
    }

    fun release(identity: String) {
        if (this.identity == identity) {
            store.clear()
            this.identity = null
        }
    }

    override fun onCleared() { store.clear() }
}
