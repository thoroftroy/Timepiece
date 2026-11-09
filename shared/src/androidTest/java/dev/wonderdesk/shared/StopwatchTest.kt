package dev.wonderdesk.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StopwatchTest {
    @Test fun elapsedIsMonotonicAndPreservesPausedDuration() {
        assertEquals(3000L, stopwatchElapsed(0L, 1000L, 4000L))
        assertEquals(8500L, stopwatchElapsed(5000L, 1000L, 4500L))
        assertEquals(5000L, stopwatchElapsed(5000L, 0L, 9000L))
        assertEquals(5000L, stopwatchElapsed(5000L, 9000L, 1000L))
        assertEquals("01:01.5", elapsedLabel(61500L))
    }
    @Test fun runningStartIsDiscardedWhenBootChanges() {
        assertEquals(1000L, recoverStopwatchStart(1000L, 4, 4, 5000L))
        assertEquals(0L, recoverStopwatchStart(1000L, 4, 5, 5000L))
        assertEquals(0L, recoverStopwatchStart(6000L, -1, -1, 5000L))
        assertEquals(1000L, recoverStopwatchStart(1000L, -1, -1, 5000L))
    }
}
