package com.opencode.android.runtime

/** Capabilities exposed by a runtime so the UI never guesses from an implementation class. */
data class RuntimeCapabilities(
    val permissions: Boolean = false,
    val questions: Boolean = false,
    val toolEvents: Boolean = false,
    val providerModelList: Boolean = false,
    val resume: Boolean = false,
)
