package com.uzairansar.hermex

import android.app.Application
import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uzairansar.hermex.core.model.ChatMessage
import com.uzairansar.hermex.core.model.RuntimeModelSnapshot
import com.uzairansar.hermex.core.network.HermesJson
import com.uzairansar.hermex.ui.chat.ChatUiState
import com.uzairansar.hermex.ui.chat.RuntimeModelStatus
import com.uzairansar.hermex.ui.theme.HermexTheme
import kotlinx.serialization.encodeToString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RuntimeModelInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun activeFallbackPrimaryReturnAndReloadHaveDistinctLabels() {
        val state = mutableStateOf(ChatUiState(isLoading = false, isStreaming = true, activeStreamId = "turn-1"))
        val app = ApplicationProvider.getApplicationContext<Application>()
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.FRANCE) }
        val resources = app.createConfigurationContext(config).resources
        compose.setContent {
            CompositionLocalProvider(LocalResources provides resources, LocalConfiguration provides config) {
                HermexTheme { RuntimeModelStatus(state.value) }
            }
        }
        compose.onNodeWithText("Modèle effectif non confirmé").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(runtimeModel = observed("primary", false)) }
        compose.onNodeWithText("Utilisé : primary · provider").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(runtimeModel = observed("backup-one", true)) }
        compose.onNodeWithText("Secours actif").assertIsDisplayed()
        compose.onNodeWithText("Utilisé : backup-one · provider").assertIsDisplayed()
        capture(app, "runtime-fallback.png")
        compose.runOnIdle { state.value = state.value.copy(runtimeModel = observed("backup-two", true)) }
        compose.onNodeWithText("Utilisé : backup-two · provider").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(runtimeModel = observed("primary", false)) }
        compose.onNodeWithText("Secours actif").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(runtimeModel = null, fallbackPending = true) }
        compose.onNodeWithText("Modèle effectif non confirmé").assertIsDisplayed()
        compose.onNodeWithText("Secours actif").assertDoesNotExist()
        val message = HermesJson.decodeFromString<ChatMessage>("""{"role":"assistant","content":"Answer","_usedModel":"backup-two","_usedProvider":"provider","_requestedModel":"primary"}""")
        val restored = HermesJson.decodeFromString<ChatMessage>(HermesJson.encodeToString(message))
        compose.runOnIdle { state.value = ChatUiState(isLoading = false, messages = listOf(restored)) }
        compose.onNodeWithText("Dernière réponse : backup-two · provider").assertIsDisplayed()
        compose.onNodeWithText("Secours utilisé").assertIsDisplayed()
        capture(app, "runtime-restored.png")
        compose.runOnIdle { state.value = state.value.copy(isStreaming = true, activeStreamId = "turn-2") }
        compose.onNodeWithText("Modèle effectif non confirmé").assertIsDisplayed()
        compose.onNodeWithText("Secours utilisé").assertDoesNotExist()
    }

    private fun observed(model: String, fallback: Boolean) = RuntimeModelSnapshot(
        sessionId = "fixture", streamId = "turn-1", model = model,
        provider = "provider", fallbackActive = fallback, phase = "observed_output",
    )

    private fun capture(app: Application, name: String) {
        compose.waitForIdle()
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(app.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
