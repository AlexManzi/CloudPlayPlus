package com.example.gxcloud

import android.annotation.SuppressLint
import android.os.BatteryManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var discordController: DiscordController
    private lateinit var notesController: NotesController
    private lateinit var quickMenuController: QuickMenuController
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest


    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        // Keep screen on while gaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Limit to 60Hz to save battery
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val lp = window.attributes
            display?.let { d ->
                // Only consider modes at the panel's current resolution — some devices expose
                // 60Hz modes at a lower physical size, which would silently downscale the stream.
                val cur = d.mode
                d.supportedModes
                    .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                    .minByOrNull { abs(it.refreshRate - 60f) }
                    ?.let { lp.preferredDisplayModeId = it.modeId }
            }
            lp.preferMinimalPostProcessing = true
            window.attributes = lp
        }

        // True fullscreen - hide system bars
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        setContentView(R.layout.activity_main)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .build()

        webView = findViewById(R.id.webview)

        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        window.setBackgroundDrawable(null)
        webView.setLayerType(View.LAYER_TYPE_NONE, null)
        webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // ContentCapture is API 30+; minSdk is 26.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            webView.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
        }
        webView.isHapticFeedbackEnabled = false
        webView.isSoundEffectsEnabled = false
        webView.isLongClickable = false
        webView.setOnHoverListener { _, _ -> true }
        webView.isSaveEnabled = false
        webView.isSaveFromParentEnabled = false

        // WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0"
            safeBrowsingEnabled = false
            setGeolocationEnabled(false)
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            textZoom = 100
            allowFileAccess = false
            setNeedInitialFocus(false)
            setOffscreenPreRaster(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setAlgorithmicDarkeningAllowed(false)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // forceDark is API 29+; minSdk is 26, so this would throw NoSuchMethodError
                // on 26-28. Those versions have no dark-mode coercion to disable anyway.
                @Suppress("DEPRECATION")
                forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
            }
        }

        // Allow cookies
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        discordController = DiscordController(this, findViewById(R.id.discordStub))
        notesController = NotesController(this, findViewById(R.id.notesStub))
        quickMenuController = QuickMenuController(
            stub = findViewById(R.id.quickMenuStub),
            requestStats = { callback ->
                webView.evaluateJavascript(
                    "JSON.stringify(window.__gxcloudGetStreamStats ? window.__gxcloudGetStreamStats() : null)",
                    callback
                )
            },
            checkWebGpu = {
                webView.evaluateJavascript(
                    "window.__gxcloudCheckWebGpu&&window.__gxcloudCheckWebGpu();void 0",
                    null
                )
            },
            setCasMode = { mode ->
                webView.evaluateJavascript("window.__gxcloudSetCasMode&&window.__gxcloudSetCasMode('$mode')", null)
            },
            batteryPercent = {
                val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            },
            openNotes = { notesController.show() },
            toggleDiscordEnabled = {
                val enabled = !discordController.enabled
                discordController.enabled = enabled
                // Turning the feature off must also release an already-open Discord
                // WebView; when on, the existing four-tap gesture is the only opener.
                if (!enabled && discordController.isVisible) discordController.close()
                enabled
            },
            isDiscordEnabled = { discordController.enabled }
        )
        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url == view.url) view.evaluateJavascript(injectScript, null)
            }
        }

        setupBackHandler()
        webView.loadUrl("https://play.xbox.com/")
    }

    override fun onResume() {
        super.onResume()
        webView.resumeTimers()
        webView.onResume()
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        discordController.onResume()
        quickMenuController.onResume()
        audioManager.requestAudioFocus(audioFocusRequest)
    }

    override fun onPause() {
        super.onPause()
        if (notesController.isVisible) notesController.forceSave()
        webView.onPause()
        webView.pauseTimers()
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
        discordController.onPause()
        quickMenuController.onPause()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    override fun onDestroy() {
        quickMenuController.destroy()
        notesController.destroy()
        discordController.destroy()
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        discordController.onRequestPermissionsResult(requestCode)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // xCloud only polls the gamepad for page navigation while its window has
            // focus (document.hasFocus()); without it, focus highlights never appear.
            if (!quickMenuController.isVisible && !notesController.isVisible && !webView.hasFocus()) {
                webView.requestFocus()
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        // Normal dispatch already reaches the focused WebView. Forwarding first can
        // deliver an unhandled controller event to it twice through the fallback.
        if (webView.hasFocus() && (
                    event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                    event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
                    event.isFromSource(InputDevice.SOURCE_DPAD)
                )) return super.dispatchKeyEvent(event)
        return webView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (suppressQuickMenuGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                suppressQuickMenuGesture = false
            }
            return true
        }
        if (quickMenuController.isVisible) return super.dispatchTouchEvent(ev)

        if (!notesController.isVisible) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> quickMenuGestureStartedAt = ev.eventTime
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount >= QUICK_MENU_FINGERS &&
                        ev.eventTime - quickMenuGestureStartedAt <= QUICK_MENU_GESTURE_WINDOW_MS
                    ) {
                        // The first three pointers may already have reached the WebView.
                        // Cancel them before exposing the menu so Remote Play never keeps
                        // a stale touch sequence alive behind the native overlay.
                        MotionEvent.obtain(ev).also { cancel ->
                            cancel.action = MotionEvent.ACTION_CANCEL
                            webView.dispatchTouchEvent(cancel)
                            cancel.recycle()
                        }
                        suppressQuickMenuGesture = true
                        quickMenuController.show()
                        return true
                    }
                }
            }
        }
        discordController.handleTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Keep explicit forwarding when another view owns focus (e.g. an overlay).
        if (webView.hasFocus() && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            return super.dispatchGenericMotionEvent(event)
        }
        return webView.dispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                quickMenuController.isVisible -> if (!quickMenuController.handleBack()) quickMenuController.hide()
                notesController.isVisible -> notesController.hide()
                webView.canGoBack() -> webView.goBack()
            }
        }
    }

    private var quickMenuGestureStartedAt = 0L
    private var suppressQuickMenuGesture = false

    private companion object {
        const val QUICK_MENU_FINGERS = 4
        const val QUICK_MENU_GESTURE_WINDOW_MS = 300L
    }

    private val injectScript by lazy {
        resources.openRawResource(R.raw.gxcloud_inject)
            .bufferedReader()
            .use { it.readText() }
    }
}
