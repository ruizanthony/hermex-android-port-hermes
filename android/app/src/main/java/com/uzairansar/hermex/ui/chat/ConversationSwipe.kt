package com.uzairansar.hermex.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Per-down snapshot; a refresh cannot retarget a gesture already in progress. */
internal class ConversationSwipe(private val previous: String?, private val next: String?,
    private val slop: Float, private val threshold: Float, private val longPressMillis: Long) {
    private var rejected = false
    private var horizontal = false
    var target: String? = null
        private set
    fun update(dx: Float, dy: Float, elapsed: Long, consumed: Boolean, pointers: Int, pressed: Boolean): Boolean {
        if (rejected) return false
        if (consumed || pointers != 1 || (!horizontal && elapsed >= longPressMillis)) {
            rejected = true; return false
        }
        if (!horizontal && (abs(dx) > slop || abs(dy) > slop)) {
            if (abs(dx) <= abs(dy) * 1.5f) { rejected = true; return false }
            horizontal = true
        }
        if (!pressed && horizontal && abs(dx) >= threshold) target = if (dx < 0) next else previous
        return horizontal
    }
}

/** Main pass runs after children: horizontal code/selection/AndroidView owns consumed input.
 * Long-press, multitouch and vertical intent permanently cancel this down sequence. */
@Composable
internal fun Modifier.conversationSwipe(identity: String, previous: String?, next: String?,
    enabled: Boolean, onNavigate: (String) -> Unit, eligibleIds: List<String> = listOfNotNull(previous, next)): Modifier {
    val neighbors = rememberUpdatedState(previous to next)
    val eligible = rememberUpdatedState(eligibleIds)
    val available = rememberUpdatedState(enabled)
    val navigate = rememberUpdatedState(onNavigate)
    val toolbar = LocalTextToolbar.current
    return pointerInput(identity, toolbar) {
        val edge = 24.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!available.value || toolbar.status == TextToolbarStatus.Shown ||
                down.position.x < edge || down.position.x > size.width - edge) return@awaitEachGesture
            val (before, after) = neighbors.value
            val swipe = ConversationSwipe(before, after, viewConfiguration.touchSlop,
                64.dp.toPx(), viewConfiguration.longPressTimeoutMillis)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!available.value || toolbar.status == TextToolbarStatus.Shown) break
                if (swipe.update(change.position.x - down.position.x, change.position.y - down.position.y,
                        change.uptimeMillis - down.uptimeMillis, change.isConsumed, event.changes.size, change.pressed)) {
                    change.consume()
                }
                if (!change.pressed) {
                    // Preserve the frozen target, but never navigate to a row removed meanwhile.
                    swipe.target?.takeIf { it in eligible.value }
                        ?.let { navigate.value(it) }
                    break
                }
            }
        }
    }
}
