package com.example.gxcloud

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewStub
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class DiscordController(
    private val activity: Activity,
    private val stub: ViewStub
) {
    private enum class State { CLOSED, UI_VISIBLE }

    @Volatile
    var enabled = false
    val isVisible: Boolean get() = state == State.UI_VISIBLE

    private var state = State.CLOSED
    private var container: FrameLayout? = null
    private var webView: WebView? = null
    private var tapCount = 0
    private val viewLocation = IntArray(2)
    private val tapResetHandler = Handler(Looper.getMainLooper())
    private val tapResetRunnable = Runnable { tapCount = 0 }

    fun handleTouch(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return false
        if (!enabled && state == State.CLOSED) return false
        val countTap = state != State.UI_VISIBLE || !isTapOnDiscord(event)
        if (countTap) {
            tapCount++
            if (tapCount >= 4) {
                tapCount = 0
                tapResetHandler.removeCallbacks(tapResetRunnable)
                when (state) {
                    State.CLOSED -> if (enabled) open()
                    State.UI_VISIBLE -> close()
                }
            } else {
                tapResetHandler.removeCallbacks(tapResetRunnable)
                tapResetHandler.postDelayed(tapResetRunnable, 600)
            }
        }
        return false
    }

    fun open() {
        if (container == null) inflate()
        webView!!.onResume()
        webView!!.settings.blockNetworkImage = false
        webView!!.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        container!!.visibility = android.view.View.VISIBLE
        state = State.UI_VISIBLE

        if (hasRecordAudioPermission()) {
            loadDiscord()
        } else {
            // Do not load Discord until Android resolves its runtime prompt. Otherwise WebView
            // can reject Discord's first audio-capture request before the app has permission.
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_REQUEST
            )
        }
    }

    fun onRequestPermissionsResult(requestCode: Int) {
        if (requestCode == RECORD_AUDIO_PERMISSION_REQUEST && state == State.UI_VISIBLE) {
            // Load after either decision: Discord remains usable without a microphone, while
            // WebView's permission callback below denies capture when Android denied it.
            loadDiscord()
        }
    }

    fun close() {
        webView?.loadUrl("about:blank")
        webView?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
        container?.visibility = android.view.View.GONE
        state = State.CLOSED
    }

    fun onResume() {
        if (state != State.CLOSED) webView?.onResume()
    }

    fun onPause() {
        if (state != State.CLOSED) webView?.onPause()
    }

    fun destroy() {
        tapResetHandler.removeCallbacks(tapResetRunnable)
        webView?.let {
            it.webViewClient = WebViewClient()
            it.webChromeClient = null
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        container = null
    }

    private fun isTapOnDiscord(event: MotionEvent): Boolean {
        val view = webView ?: return false
        view.getLocationOnScreen(viewLocation)
        return event.rawX >= viewLocation[0] && event.rawX <= viewLocation[0] + view.width &&
            event.rawY >= viewLocation[1] && event.rawY <= viewLocation[1] + view.height
    }

    @Suppress("SetJavaScriptEnabled")
    private fun inflate() {
        val root = stub.inflate() as FrameLayout
        container = root
        webView = root.findViewById<WebView>(R.id.discordWebView).also { view ->
            view.overScrollMode = WebView.OVER_SCROLL_NEVER
            view.isVerticalScrollBarEnabled = false
            view.isHorizontalScrollBarEnabled = false
            view.isHapticFeedbackEnabled = false
            view.isLongClickable = false
            view.isSaveEnabled = false
            view.isSaveFromParentEnabled = false
            view.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            view.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                view.importantForContentCapture = android.view.View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
            }
            view.setBackgroundColor(android.graphics.Color.BLACK)
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                setSupportZoom(false)
                builtInZoomControls = false
                textZoom = 100
                safeBrowsingEnabled = false
                setOffscreenPreRaster(false)
                setNeedInitialFocus(false)
                setGeolocationEnabled(false)
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            view.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    if (hasRecordAudioPermission() &&
                        request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    ) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        request.deny()
                    }
                }
            }
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (url == "about:blank") { view.clearHistory(); view.onPause() }
                }
            }
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun loadDiscord() {
        if (state == State.UI_VISIBLE) webView?.loadUrl("https://discord.com/app")
    }

    private companion object {
        const val RECORD_AUDIO_PERMISSION_REQUEST = 1001
    }
}
