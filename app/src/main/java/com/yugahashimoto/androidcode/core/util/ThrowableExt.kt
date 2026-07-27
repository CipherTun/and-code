package com.yugahashimoto.androidcode.core.util

fun Throwable.safeMessage(fallback: String = "Unknown error"): String = message?.takeIf { it.isNotBlank() } ?: fallback
