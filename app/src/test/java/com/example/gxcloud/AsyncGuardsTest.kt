package com.example.gxcloud

import org.junit.Assert.*
import org.junit.Test

class AsyncGuardsTest {
    @Test fun deviceSamplesRefreshEveryFiveSecondsIncludingAfterAHiddenInterval() {
        var time = 0L
        var reads = 0
        val cache = ExpiringValue(5_000, { time }) { ++reads }
        assertEquals(1, cache.get())
        for (second in 1..4) {
            time = second * 1_000L
            assertEquals(1, cache.get())
        }
        time = 5_000
        assertEquals(2, cache.get())
        time = 60_000 // Reopening the page gets a fresh sample immediately.
        assertEquals(3, cache.get())
        assertEquals(3, reads)
    }

    @Test fun unavailableDeviceReadingsAreCachedUntilTheNextSample() {
        var time = 0L
        var reads = 0
        val cache = ExpiringValue<String?>(5_000, { time }) {
            reads++
            if (reads == 1) null else "Normal"
        }
        assertNull(cache.get())
        time = 1_000
        assertNull(cache.get())
        assertEquals(1, reads)
        time = 5_000
        assertEquals("Normal", cache.get())
    }

    @Test fun documentInjectionSkipsDuplicatesButPermitsReloadAndRendererReplacement() {
        val gate = DocumentInjectionGate()
        assertTrue(gate.begin(1))
        assertFalse(gate.begin(1)) // A duplicate finish callback while evaluation is pending.
        gate.complete(1, true)
        assertFalse(gate.begin(1))
        gate.invalidate()
        assertTrue(gate.begin(2)) // A same-URL reload is a new generation.
        gate.complete(1, true) // An old callback cannot finish the new injection.
        assertFalse(gate.begin(2))
        gate.complete(2, false)
        assertTrue(gate.begin(2)) // Failed submission can retry.
        gate.complete(2, true)
        assertFalse(gate.begin(2))
        gate.invalidate()
        assertTrue(gate.begin(3)) // Explicit renderer recovery injects again.
    }

    @Test fun editsDuringWriteRemainSaveableAndNewestQueuedSnapshotWins() {
        val saves = SaveRevisionTracker()
        assertNull(saves.begin())
        saves.changed()
        val first = saves.begin()!!
        assertNull(saves.begin()) // Pause/destroy must not queue the same snapshot twice.
        saves.changed()
        saves.complete(first, true)
        val second = saves.begin()!!
        saves.changed()
        val third = saves.begin()!!
        saves.complete(second, true)
        assertNull(saves.begin()) // Third snapshot remains pending despite older completion.
        saves.complete(third, true)
        assertNull(saves.begin())
    }

    @Test fun failedSaveRemainsDirtyAndCanRetry() {
        val saves = SaveRevisionTracker()
        saves.changed()
        val first = saves.begin()!!
        saves.complete(first, false)
        assertEquals(first, saves.begin())
        saves.complete(first, true)
        assertNull(saves.begin())
    }

    @Test fun staleStatsCallbackCannotReleaseReplacementRequest() {
        val gate = RequestGate()
        val old = gate.begin()!!
        assertNull(gate.begin())
        gate.invalidate() // Navigation, renderer loss, or destruction.
        val replacement = gate.begin()!!
        assertFalse(gate.complete(old))
        assertNull(gate.begin())
        assertTrue(gate.complete(replacement))
        assertNotNull(gate.begin())
    }

    @Test fun failedOrFinishedStatsRequestAllowsAnotherPoll() {
        val gate = RequestGate()
        val request = gate.begin()!!
        assertTrue(gate.complete(request))
        assertFalse(gate.complete(request))
        assertNotNull(gate.begin())
    }
}
