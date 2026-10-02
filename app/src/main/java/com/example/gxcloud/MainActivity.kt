package com.example.gxcloud

import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
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
import android.webkit.RenderProcessGoneDetail
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private lateinit var webViewContainer: FrameLayout
    private var recoveryView: View? = null
    private var documentGeneration = 0L
    private var casMode = "normal"
    // Persisted, unlike casMode: it's a property of the user's networks, not a
    // per-session look, so it should survive an app restart.
    private var preferIpv6 = false
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private var resumed = false
    private lateinit var discordController: DiscordController
    private lateinit var notesController: NotesController
    private lateinit var quickMenuController: QuickMenuController
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest


    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        casMode = normalizeCasMode(savedInstanceState?.getString("casMode"))
        preferIpv6 = prefs.getBoolean(PREF_PREFER_IPV6, false)

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

        webViewContainer = findViewById(R.id.mainWebViewContainer)
        webView = findViewById(R.id.webview)

        discordController = DiscordController(this, findViewById(R.id.discordStub))
        notesController = NotesController(this, findViewById(R.id.notesStub))
        quickMenuController = QuickMenuController(
            stub = findViewById(R.id.quickMenuStub),
            requestStats = { callback ->
                val view = webView
                val generation = documentGeneration
                if (view == null) {
                    val stats = JSONObject().put("state", "Stream unavailable").put("casMode", casMode)
                    callback(JSONObject.quote(stats.toString()))
                } else {
                    view.evaluateJavascript(
                        "JSON.stringify(window.__gxcloudGetStreamStats ? window.__gxcloudGetStreamStats() : null)"
                    ) { raw ->
                        if (view === webView && generation == documentGeneration) callback(raw)
                    }
                }
            },
            setCasMode = { mode ->
                casMode = normalizeCasMode(mode)
                webView?.evaluateJavascript("window.__gxcloudSetCasMode&&window.__gxcloudSetCasMode('$casMode')", null)
            },
            getCasMode = { casMode },
            batteryPercent = {
                val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            },
            ramStatus = { readRamStatus() },
            thermalStatus = { readThermalStatus() },
            wattage = { readWattage() },
            openNotes = { notesController.show() },
            toggleDiscordEnabled = {
                val enabled = !discordController.enabled
                discordController.enabled = enabled
                // Turning the feature off must also release an already-open Discord
                // WebView; when on, the existing four-tap gesture is the only opener.
                if (!enabled && discordController.isVisible) discordController.close()
                enabled
            },
            isDiscordEnabled = { discordController.enabled },
            togglePreferIpv6 = {
                preferIpv6 = !preferIpv6
                prefs.edit().putBoolean(PREF_PREFER_IPV6, preferIpv6).apply()
                webView?.evaluateJavascript("window.__gxcloudSetPreferIpv6&&window.__gxcloudSetPreferIpv6($preferIpv6)", null)
                preferIpv6
            },
            isPreferIpv6 = { preferIpv6 }
        )
        configureWebView(webView!!)
        setupBackHandler()
        webView?.loadUrl(XBOX_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setBackgroundColor(android.graphics.Color.BLACK)
        window.setBackgroundDrawable(null)
        view.setLayerType(View.LAYER_TYPE_NONE, null)
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // ContentCapture is API 30+; minSdk is 26.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
        }
        view.isHapticFeedbackEnabled = false
        view.isSoundEffectsEnabled = false
        view.isLongClickable = false
        view.setOnHoverListener { _, _ -> true }
        view.isSaveEnabled = false
        view.isSaveFromParentEnabled = false

        // WebView settings
        view.settings.apply {
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (view === webView) documentGeneration++
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (view === webView && url == view.url) {
                    // Supply the selections before the script's warm stream acquisition.
                    view.evaluateJavascript(
                        "window.__gxcloudInitialCasMode='$casMode';window.__gxcloudInitialPreferIpv6=$preferIpv6;\n" + injectScript,
                        null
                    )
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === webView) {
                    webView = null
                    documentGeneration++
                    showRecovery()
                }
                // Each affected WebView handles its own callback, even for a shared renderer.
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                return true
            }
        }
    }

    private fun showRecovery() {
        if (recoveryView != null) return
        val recovery = layoutInflater.inflate(R.layout.layout_webview_recovery, webViewContainer, false)
        recovery.findViewById<TextView>(R.id.recoveryMessage).setText(R.string.xbox_connection_lost)
        recovery.findViewById<Button>(R.id.recoveryRetry).setOnClickListener {
            if (webView != null || !resumed) return@setOnClickListener
            val replacement = WebView(this)
            replacement.id = R.id.webview
            webView = replacement
            documentGeneration++
            configureWebView(replacement)
            webViewContainer.removeView(recovery)
            recoveryView = null
            webViewContainer.addView(replacement, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            replacement.resumeTimers()
            replacement.onResume()
            replacement.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            replacement.loadUrl(XBOX_URL)
            if (!quickMenuController.isVisible && !notesController.isVisible) replacement.requestFocus()
        }
        recoveryView = recovery
        webViewContainer.addView(recovery)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("casMode", casMode)
        super.onSaveInstanceState(outState)
    }

    private fun normalizeCasMode(mode: String?): String = when (mode) {
        "off", "high" -> mode
        else -> "normal"
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        webView?.resumeTimers()
        webView?.onResume()
        webView?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        discordController.onResume()
        quickMenuController.onResume()
        audioManager.requestAudioFocus(audioFocusRequest)
    }

    override fun onPause() {
        resumed = false
        super.onPause()
        if (notesController.isVisible) notesController.forceSave()
        webView?.onPause()
        webView?.pauseTimers()
        webView?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
        discordController.onPause()
        quickMenuController.onPause()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    override fun onDestroy() {
        quickMenuController.destroy()
        notesController.destroy()
        discordController.destroy()
        webView?.let { view ->
            view.webViewClient = WebViewClient()
            view.webChromeClient = null
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
        documentGeneration++
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
            if (!quickMenuController.isVisible && !notesController.isVisible && webView?.hasFocus() == false) {
                webView?.requestFocus()
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }

    // Back is delegated unchanged to AndroidX's callback registered in setupBackHandler.
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        // Normal dispatch already reaches the focused WebView. Forwarding first can
        // deliver an unhandled controller event to it twice through the fallback.
        if (webView?.hasFocus() == true && (
                    event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                    event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
                    event.isFromSource(InputDevice.SOURCE_DPAD)
                )) return super.dispatchKeyEvent(event)
        return webView?.dispatchKeyEvent(event) == true || super.dispatchKeyEvent(event)
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
                            webView?.dispatchTouchEvent(cancel)
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
        if (webView?.hasFocus() == true && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            return super.dispatchGenericMotionEvent(event)
        }
        return webView?.dispatchGenericMotionEvent(event) == true || super.dispatchGenericMotionEvent(event)
    }

    // System-wide RAM: ActivityManager's own memory-pressure API, public since API 16
    // with no permission needed — unlike /proc/stat (blocked for apps since Android 7,
    // confirmed on this device: see AGENTS.md "Power Profile") and the kgsl GPU files
    // (blocked even for adb shell), this one was never a restricted path, so it always
    // has a real reading.
    private fun readRamStatus(): String? {
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            if (info.totalMem <= 0) return null
            val usedBytes = info.totalMem - info.availMem
            val usedPercent = (100 * usedBytes / info.totalMem).toInt()
            val usedGb = usedBytes / 1_073_741824.0
            val totalGb = info.totalMem / 1_073_741824.0
            String.format("%.1f/%.1f GB (%d%%)", usedGb, totalGb, usedPercent)
        } catch (_: Exception) {
            null
        }
    }

    // Android's own thermal-throttling verdict, computed from the device's real thermal
    // sensors — the officially supported way to ask "is this device under stress," unlike
    // the CPU%/GPU% paths that turned out to be blocked. Public API since Android 10
    // (API 29); minSdk here is 26, so guarded for 26-28 where the API doesn't exist yet.
    private fun readThermalStatus(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // Instantaneous power draw in watts: BatteryManager's CURRENT_NOW (microamps) times
    // the voltage from the sticky ACTION_BATTERY_CHANGED intent (millivolts). Both are
    // public framework APIs backed by the battery HAL — a different path than the raw
    // kgsl/power_supply sysfs nodes that require root, so this can still work even where
    // those are blocked. Returns null if either reading is missing/unsupported on this device.
    private fun readWattage(): Double? {
        return try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val microAmps = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (microAmps == Int.MIN_VALUE || microAmps == 0) return null
            val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val milliVolts = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            if (milliVolts <= 0) return null
            (abs(microAmps) / 1_000_000.0) * (milliVolts / 1000.0)
        } catch (_: Exception) {
            null
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                quickMenuController.isVisible -> if (!quickMenuController.handleBack()) quickMenuController.hide()
                notesController.isVisible -> notesController.hide()
                webView?.canGoBack() == true -> webView?.goBack()
            }
        }
    }

    private var quickMenuGestureStartedAt = 0L
    private var suppressQuickMenuGesture = false

    private companion object {
        const val XBOX_URL = "https://play.xbox.com/"
        const val QUICK_MENU_FINGERS = 4
        const val QUICK_MENU_GESTURE_WINDOW_MS = 300L
        const val PREFS_NAME = "gxcloud"
        const val PREF_PREFER_IPV6 = "preferIpv6"
    }

    private val injectScript by lazy {
        resources.openRawResource(R.raw.gxcloud_inject)
            .bufferedReader()
            .use { it.readText() }
    }
}
