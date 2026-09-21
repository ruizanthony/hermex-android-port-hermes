package com.uzairansar.hermex.ui.chat

/** Per-instance, debug-only instrumentation seam. No replacement of the Android service. */
internal data class StreamForegroundTestHooks(
    val onCreated: (StreamRecoveryService) -> Unit = {},
    val beforePromotion: () -> Unit = {},
)
