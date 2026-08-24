package com.yugahashimoto.andcode.core.lifecycle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundReturnDetectorTest {
    /**
     * The first `true` a process sees is always its own cold start - RuntimeAutoStartInitializer
     * already restores the runtime for that case, so acting on it again here would be redundant.
     */
    @Test
    fun `does not fire on the very first foreground emission`() {
        val detector = ForegroundReturnDetector()

        assertFalse(detector.onForegroundChanged(true))
    }

    @Test
    fun `does not fire on a background emission`() {
        val detector = ForegroundReturnDetector()

        assertFalse(detector.onForegroundChanged(false))
    }

    @Test
    fun `fires on a foreground emission that follows a background one`() {
        val detector = ForegroundReturnDetector()
        detector.onForegroundChanged(true)

        detector.onForegroundChanged(false)

        assertTrue(detector.onForegroundChanged(true))
    }

    /** Repeated returns from the background must each fire, not just the first one. */
    @Test
    fun `fires on every subsequent return from the background`() {
        val detector = ForegroundReturnDetector()
        detector.onForegroundChanged(true)

        detector.onForegroundChanged(false)
        assertTrue(detector.onForegroundChanged(true))

        detector.onForegroundChanged(false)
        assertTrue(detector.onForegroundChanged(true))
    }
}
