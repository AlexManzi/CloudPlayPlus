package com.example.gxcloud

import android.webkit.JavascriptInterface

class StreamBridge(
    private val setDiscordEnabledCallback: (Boolean) -> Unit,
    private val openNotesCallback: () -> Unit,
    private val deviceStatusCallback: () -> String
) {
    @JavascriptInterface
    fun setDiscordEnabled(enabled: Boolean) = setDiscordEnabledCallback(enabled)

    @JavascriptInterface
    fun openNotes() = openNotesCallback()

    @JavascriptInterface
    fun getDeviceStatusJson(): String = deviceStatusCallback()
}
