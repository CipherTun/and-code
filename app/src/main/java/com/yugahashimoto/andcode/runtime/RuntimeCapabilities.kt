package com.yugahashimoto.andcode.runtime

/** Capabilities exposed by a runtime so the UI never guesses from an implementation class. */
data class RuntimeCapabilities(
    val permissions: Boolean = false,
    val questions: Boolean = false,
    val toolEvents: Boolean = false,
    val providerModelList: Boolean = false,
    val resume: Boolean = false,
    /**
     * True when sending a message while a turn is running must always queue behind it rather than
     * interrupt it, regardless of the user's send-behavior setting.
     *
     * Some runtimes (Antigravity) run each turn as a brand-new one-shot process; "interrupting" it
     * means killing that process outright, which surfaces as a crash rather than a cancellation. See
     * [com.yugahashimoto.andcode.runtime.local.AntigravityRuntime.send] for the failure this avoids.
     */
    val forcesQueue: Boolean = false,
)
