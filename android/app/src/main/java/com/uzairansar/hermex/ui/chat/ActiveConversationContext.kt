package com.uzairansar.hermex.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Survives rotation, never process death: stale eligibility is not saved into a Bundle. */
class ActiveConversationContext : ViewModel() {
    private var projection by mutableStateOf<Pair<String, List<String>>?>(null)

    fun update(accountIdentity: String, ids: List<String>) {
        projection = accountIdentity to ids.toList()
    }

    fun idsFor(accountIdentity: String): List<String> =
        projection?.takeIf { it.first == accountIdentity }?.second.orEmpty()

    fun clear() { projection = null }
}
