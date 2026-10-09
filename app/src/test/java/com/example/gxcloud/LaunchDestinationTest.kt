package com.example.gxcloud

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchDestinationTest {
    @Test fun processRestartAppliesChangedPreferenceInBothDirections() {
        val destination = LaunchDestination("new-process")
        assertEquals(LaunchDestination.REMOTE_URL, destination.resolve(
            true, LaunchDestination.CLOUD_URL, "old-process"
        ))
        assertEquals(LaunchDestination.CLOUD_URL, destination.resolve(
            false, LaunchDestination.REMOTE_URL, "old-process"
        ))
    }

    @Test fun sameProcessRecreationKeepsCurrentSessionDespiteChangedPreference() {
        val destination = LaunchDestination("current-process")
        assertEquals(LaunchDestination.CLOUD_URL, destination.resolve(
            true, LaunchDestination.CLOUD_URL, "current-process"
        ))
        assertEquals(LaunchDestination.REMOTE_URL, destination.resolve(
            false, LaunchDestination.REMOTE_URL, "current-process"
        ))
    }

    @Test fun freshLaunchAndMissingOrInvalidSavedStateUsePreference() {
        val destination = LaunchDestination("current-process")
        assertEquals(LaunchDestination.CLOUD_URL, destination.resolve(false, null, null))
        assertEquals(LaunchDestination.REMOTE_URL, destination.resolve(true, null, null))
        assertEquals(LaunchDestination.REMOTE_URL, destination.resolve(
            true, LaunchDestination.CLOUD_URL, null
        ))
        assertEquals(LaunchDestination.CLOUD_URL, destination.resolve(
            false, "https://example.com/", "current-process"
        ))
    }
}
