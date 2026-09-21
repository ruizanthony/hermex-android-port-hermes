package com.uzairansar.hermex.ui.chat

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.uzairansar.hermex.R
import kotlin.math.abs

/** Dedicated gesture band: deliberately never intercept transcript/composer/reader gestures. */
@Composable
internal fun ActiveConversationControls(
    previousId: String?,
    nextId: String?,
    enabled: Boolean,
    onNavigate: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).testTag("active_navigation")
            .pointerInput(previousId, nextId, enabled) {
                if (!enabled) return@pointerInput
                val edge = 24.dp.toPx()
                val threshold = 64.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.x < edge || down.position.x > size.width - edge) return@awaitEachGesture
                    var horizontal = false
                    var displacement = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (event.changes.size != 1 || change.isConsumed) break
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!horizontal && (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop)) {
                            if (abs(dx) <= abs(dy) * 1.5f) break
                            horizontal = true
                        }
                        if (horizontal) {
                            displacement = dx
                            change.consume()
                        }
                        if (!change.pressed) {
                            if (horizontal && abs(displacement) >= threshold) {
                                (if (displacement < 0) nextId else previousId)?.let(onNavigate)
                            }
                            break
                        }
                    }
                }
            }
            .padding(horizontal = 24.dp),
    ) {
        TextButton(
            enabled = enabled && previousId != null,
            onClick = { previousId?.let(onNavigate) },
            modifier = Modifier.testTag("active_previous"),
        ) { Text(stringResource(R.string.active_conversation_previous)) }
        TextButton(
            enabled = enabled && nextId != null,
            onClick = { nextId?.let(onNavigate) },
            modifier = Modifier.testTag("active_next"),
        ) { Text(stringResource(R.string.active_conversation_next)) }
    }
}
