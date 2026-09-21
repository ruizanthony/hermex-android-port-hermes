package com.uzairansar.hermex.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/** Replace a single detail slot; retain lightweight Compose positions, not VMs/transcripts. */
@Composable
fun ActiveChatContent(identity: String, content: @Composable () -> Unit) {
    val owner: ActiveChatOwner = viewModel(key = "active-chat-owner")
    val savedStates = rememberSaveableStateHolder()
    val lease = remember(owner, identity) { owner.acquire(identity) }
    val store = lease.store
    val slotOwner = remember(store) {
        object : ViewModelStoreOwner { override val viewModelStore = store }
    }
    DisposableEffect(owner, lease) {
        onDispose { owner.release(lease) }
    }
    key(identity) {
        savedStates.SaveableStateProvider(identity) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides slotOwner, content = content)
        }
    }
}
