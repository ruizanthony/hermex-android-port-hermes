package com.uzairansar.hermex.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.uzairansar.hermex.AppContainer
import com.uzairansar.hermex.data.repository.ArchiveIdentity
import com.uzairansar.hermex.data.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl

/** Detail-only metadata reconciliation: one bounded read, never a neighboring ChatViewModel. */
@Composable
fun ActiveConversationRefresh(context: ActiveConversationContext, identity: String, sessionId: String,
    server: HttpUrl, account: String, container: AppContainer) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val repository = remember(server, account) { container.sessionRepository(server) }
    LaunchedEffect(identity, sessionId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var pause = 5_000L
            while (isActive) {
                val generation = context.beginMetadataRefresh()
                val auth = container.authRepository.currentAuthGeneration(server)
                // Capture before any IO. A different profile discovered below needs another pass;
                // never relabel a token obtained after the network read as a fresh token.
                val archive = container.archiveCoordinator
                val token = archive.beginRefresh(ArchiveIdentity(server.toString(), account, context.profile))
                try {
                    val metadata = withTimeout(15_000) { repository.loadNavigationMetadata(sessionId) }
                    if (auth == container.authRepository.currentAuthGeneration(server)) {
                        if (token.identity.profile == metadata.profile) {
                            if (archive.reconcileRefresh(token, metadata.sessions))
                                context.reconcileMetadata(identity, generation, metadata)
                        } else {
                            // Establish a direct link's profile; no tombstone is released by this read.
                            context.reconcileMetadata(identity, generation, metadata)
                        }
                    }
                    pause = 5_000L
                } catch (_: SessionRepository.NavigationProfileChanged) {
                    if (context.beginMetadataRefresh() == generation) context.suspendEligibility()
                    pause = (pause * 2).coerceAtMost(60_000)
                } catch (cancel: CancellationException) {
                    if (cancel is kotlinx.coroutines.TimeoutCancellationException) pause = (pause * 2).coerceAtMost(60_000)
                    else throw cancel
                } catch (_: Exception) { pause = (pause * 2).coerceAtMost(60_000) }
                delay(pause)
            }
        }
    }
}
