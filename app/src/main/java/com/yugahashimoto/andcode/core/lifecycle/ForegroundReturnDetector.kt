package com.yugahashimoto.andcode.core.lifecycle

/**
 * Turns a stream of [AppForeground.foreground] emissions into "the app just returned from the
 * background" events, filtering out the very first time it becomes foregrounded - that first
 * transition is the app's own cold start, already handled by whatever the caller runs once at
 * process launch (e.g. `RuntimeAutoStartInitializer`).
 *
 * Kept as a tiny, explicitly stateful class rather than inline Flow operators so the transition
 * logic - the part that actually decides when to act - is testable with plain JUnit instead of a
 * coroutine test harness.
 */
internal class ForegroundReturnDetector {
    private var sawInitialForeground = false

    /**
     * Call with every [AppForeground.foreground] emission, in order. Returns true exactly when
     * [inForeground] is true and a `false` has been seen at some point before it - an actual return
     * from the background, not the app's initial launch into the foreground.
     */
    fun onForegroundChanged(inForeground: Boolean): Boolean {
        if (!inForeground) return false
        if (!sawInitialForeground) {
            sawInitialForeground = true
            return false
        }
        return true
    }
}
