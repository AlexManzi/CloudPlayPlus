package com.example.gxcloud

import java.util.UUID

// One instance per process distinguishes Activity recreation from process restoration.
internal class LaunchDestination(val processToken: String = UUID.randomUUID().toString()) {
    fun resolve(preferRemotePlay: Boolean, savedUrl: String?, savedProcessToken: String?): String {
        if (savedProcessToken == processToken && (savedUrl == CLOUD_URL || savedUrl == REMOTE_URL)) {
            return savedUrl
        }
        return if (preferRemotePlay) REMOTE_URL else CLOUD_URL
    }

    companion object {
        const val CLOUD_URL = "https://play.xbox.com/"
        const val REMOTE_URL = "https://play.xbox.com/remoteplay"
    }
}
