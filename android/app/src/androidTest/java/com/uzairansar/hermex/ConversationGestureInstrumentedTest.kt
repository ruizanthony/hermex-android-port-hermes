package com.uzairansar.hermex

import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.uzairansar.hermex.ui.chat.conversationSwipe
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated arbitration tests; actual ChatRoute replacement is covered separately. */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class ConversationGestureInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @After fun close() { compose.runOnUiThread { compose.activity.setContent {} } }

    @Test fun contentGesturePreservesChildHorizontalScrollEdgesLongPressAndFrozenTarget() {
        val navigated = mutableListOf<String>()
        var next by mutableStateOf("b")
        var childScroll: androidx.compose.foundation.ScrollState? = null
        compose.runOnUiThread { compose.activity.setContent {
            Box(Modifier.fillMaxSize().testTag("content")
                .conversationSwipe("a", null, next, true, { navigated += it }, listOf("a", "b", "c"))) {
                val scroll = rememberScrollState()
                childScroll = scroll
                Row(Modifier.fillMaxWidth().height(100.dp).testTag("code").horizontalScroll(scroll)) {
                    Text("Synthetic horizontal code ".repeat(100), maxLines = 1)
                }
            }
        } }
        compose.onNodeWithTag("code").performTouchInput { swipeLeft(startX = width * .8f, endX = width * .2f) }
        compose.runOnIdle { assertTrue(childScroll!!.value > 0); assertTrue(navigated.isEmpty()) }
        compose.onNodeWithTag("content").performTouchInput {
            swipe(Offset(2f, center.y), Offset(width * .7f, center.y), 300)
        }
        compose.runOnIdle { assertTrue(navigated.isEmpty()) }
        compose.onNodeWithTag("content").performTouchInput {
            down(Offset(width * .8f, center.y)); advanceEventTime(700)
            moveTo(Offset(width * .2f, center.y)); up()
        }
        compose.runOnIdle { assertTrue(navigated.isEmpty()) }
        compose.onNodeWithTag("content").performTouchInput { down(Offset(width * .8f, center.y)) }
        compose.runOnIdle { next = "c" }
        compose.onNodeWithTag("content").performTouchInput {
            moveTo(Offset(width * .2f, center.y), 100); up()
        }
        compose.runOnIdle { assertEquals(listOf("b"), navigated) }
    }
}
