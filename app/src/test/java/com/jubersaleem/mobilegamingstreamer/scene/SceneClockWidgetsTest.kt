package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * H9 time-contract tests: formatting vectors, elapsed boundaries,
 * and tick-delay guarantees shared by the GL renderer and preview.
 */
class SceneClockWidgetsTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun wallClockVectors() {
        assertEquals("00:00", SceneClockWidgets.formatWallClock(0L, utc))
        assertEquals(
            "13:05",
            SceneClockWidgets.formatWallClock((13 * 3600 + 5 * 60) * 1000L, utc)
        )
        // Seconds truncate: 23:59:59 reads 23:59.
        assertEquals(
            "23:59",
            SceneClockWidgets.formatWallClock((24 * 3600 - 1) * 1000L, utc)
        )
    }

    @Test
    fun wallClockRespectsZone() {
        // Epoch in Asia/Dubai (+04) reads 04:00.
        assertEquals("04:00", SceneClockWidgets.formatWallClock(0L, ZoneId.of("Asia/Dubai")))
    }

    @Test
    fun worstCasePins() {
        assertEquals("88:88", SceneClockWidgets.WALL_CLOCK_WORST_CASE)
        assertEquals("88:88:88", SceneClockWidgets.TIMER_WORST_CASE)
    }

    @Test
    fun elapsedBoundaries() {
        assertEquals("00:00:00", SceneClockWidgets.formatElapsed(0L, 0L))
        assertEquals("00:00:00", SceneClockWidgets.formatElapsed(0L, 999L))
        assertEquals("00:01:01", SceneClockWidgets.formatElapsed(0L, 61_000L))
        assertEquals("01:00:00", SceneClockWidgets.formatElapsed(0L, 3_600_000L))
        assertEquals("99:59:59", SceneClockWidgets.formatElapsed(0L, 400_000_000L))
    }

    @Test
    fun elapsedClampsNegative() {
        assertEquals("00:00:00", SceneClockWidgets.formatElapsed(10_000L, 5_000L))
    }

    @Test
    fun tickDelayBoundaries() {
        assertEquals(1_000L, SceneClockWidgets.tickDelayMs(0L, 1_000L))
        assertEquals(1L, SceneClockWidgets.tickDelayMs(999L, 1_000L))
        assertEquals(1_000L, SceneClockWidgets.tickDelayMs(1_000L, 1_000L))
        assertEquals(59_000L, SceneClockWidgets.tickDelayMs(61_000L, 60_000L))
        assertEquals(60_000L, SceneClockWidgets.tickDelayMs(120_000L, 60_000L))
    }

    @Test
    fun tickDelayNeverZeroOrNullOnValidPeriod() {
        for (now in listOf(-1L, -1_000L, -61_000L, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1)) {
            val delay = SceneClockWidgets.tickDelayMs(now, 1_000L)!!
            assertTrue(delay in 1..1_000L)
        }
        assertNull(SceneClockWidgets.tickDelayMs(0L, 0L))
        assertNull(SceneClockWidgets.tickDelayMs(0L, -5L))
    }
}
