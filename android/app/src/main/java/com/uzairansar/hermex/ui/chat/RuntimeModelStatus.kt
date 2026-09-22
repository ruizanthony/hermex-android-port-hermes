package com.uzairansar.hermex.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.uzairansar.hermex.R
import com.uzairansar.hermex.core.model.ChatMessage
import com.uzairansar.hermex.core.model.ContextWindowSnapshot

/** Only the final response of this turn receives the terminal server attribution. */
internal fun List<ChatMessage>.withRuntimeAttribution(usage: ContextWindowSnapshot): List<ChatMessage> {
    val model = usage.usedModel?.takeIf { it.isNotBlank() } ?: return this
    val index = indexOfLast { it.role == "assistant" && it.displayText.isNotBlank() }
    if (index < 0 || index < indexOfLast { it.role == "user" }) return this
    return mapIndexed { position, message ->
        if (position != index) message else message.copy(
            usedModel = model,
            usedProvider = usage.usedProvider,
            requestedModel = usage.requestedModel,
            requestedProvider = usage.requestedProvider,
        )
    }
}

internal fun runtimeIdentityLabel(model: String, provider: String?): String =
    if (provider.isNullOrBlank()) model else "$model · $provider"

@Composable
internal fun RuntimeModelStatus(state: ChatUiState) {
    val active = state.isStreaming || state.activeStreamId != null
    val observed = state.runtimeModel?.takeIf {
        active && state.activeStreamId != null && it.streamId == state.activeStreamId &&
            it.phase == "observed_output" && !it.model.isNullOrBlank()
    }
    val last = state.messages.lastOrNull { it.role == "assistant" && it.displayText.isNotBlank() }
    val fallback = if (active) observed?.fallbackActive == true else
        !last?.usedModel.isNullOrBlank() && !last?.requestedModel.isNullOrBlank()
    Column(modifier = Modifier.fillMaxWidth().testTag("runtime_model_status")) {
        Text(
            text = when {
                active && observed != null -> stringResource(R.string.runtime_model_used,
                    runtimeIdentityLabel(observed.model.orEmpty(), observed.provider))
                active -> stringResource(R.string.runtime_model_unconfirmed)
                !last?.usedModel.isNullOrBlank() -> stringResource(R.string.runtime_model_last,
                    runtimeIdentityLabel(last?.usedModel.orEmpty(), last?.usedProvider))
                else -> stringResource(R.string.runtime_model_unconfirmed)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (fallback || (active && state.fallbackPending)) {
            Text(
                text = stringResource(when {
                    fallback && active -> R.string.runtime_model_fallback_active
                    fallback -> R.string.runtime_model_fallback_used
                    else -> R.string.runtime_model_fallback_pending
                }),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun RuntimeResponseAttribution(model: String?, provider: String?, requestedModel: String?) {
    if (model.isNullOrBlank()) return
    val label = stringResource(R.string.runtime_model_used, runtimeIdentityLabel(model, provider))
    Text(
        text = if (requestedModel.isNullOrBlank()) label else
            "$label · ${stringResource(R.string.runtime_model_fallback_used)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("response_model_attribution"),
    )
}
