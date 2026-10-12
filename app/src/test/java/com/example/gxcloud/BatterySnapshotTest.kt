package com.example.gxcloud

import org.junit.Assert.*
import org.junit.Test

class BatterySnapshotTest {
    @Test fun percentageHandlesDifferentScalesAndEndpointsWithoutOverflow() {
        assertEquals(75, BatterySnapshot.from(150, 200, 4000).percentage)
        assertEquals(0, BatterySnapshot.from(0, 100, 4000).percentage)
        assertEquals(100, BatterySnapshot.from(Int.MAX_VALUE, Int.MAX_VALUE, 4000).percentage)
    }

    @Test fun missingOrInvalidFieldsRemainIndependentlyUnavailable() {
        for ((level, scale) in listOf(-1 to 100, 50 to 0, 50 to -1, 101 to 100)) {
            val snapshot = BatterySnapshot.from(level, scale, 4000)
            assertNull(snapshot.percentage)
            assertEquals(4000, snapshot.milliVolts)
        }
        for (voltage in listOf(-1, 0)) {
            val snapshot = BatterySnapshot.from(50, 100, voltage)
            assertEquals(50, snapshot.percentage)
            assertNull(snapshot.milliVolts)
        }
    }
}
