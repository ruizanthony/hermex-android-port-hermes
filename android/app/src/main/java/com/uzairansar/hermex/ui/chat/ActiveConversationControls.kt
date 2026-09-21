package com.uzairansar.hermex.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.uzairansar.hermex.R

@Composable
internal fun ActiveConversationControls(previousId: String?, nextId: String?, enabled: Boolean,
    onNavigate: (String) -> Unit, eligibleIds: List<String> = listOfNotNull(previousId, nextId)) {
    Row(horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .testTag("active_navigation")
            .conversationSwipe("navigation-band", previousId, nextId, enabled, onNavigate, eligibleIds)
            .padding(horizontal = 24.dp)) {
        TextButton(enabled = enabled && previousId != null, onClick = { previousId?.let(onNavigate) },
            modifier = Modifier.testTag("active_previous")) { Text(stringResource(R.string.active_conversation_previous)) }
        TextButton(enabled = enabled && nextId != null, onClick = { nextId?.let(onNavigate) },
            modifier = Modifier.testTag("active_next")) { Text(stringResource(R.string.active_conversation_next)) }
    }
}
