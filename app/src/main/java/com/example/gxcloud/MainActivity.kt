package com.example.gxcloud

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.AtomicFile
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var discordStub: ViewStub
    private var discordContainer: FrameLayout? = null
    private var discordWebView: WebView? = null
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest

    private enum class DiscordState { CLOSED, UI_VISIBLE }
    private var discordState = DiscordState.CLOSED
    // Written from the WebView's JS thread via StreamBridge, read on the UI thread in
    // dispatchTouchEvent.
    @Volatile private var discordEnabled = false
    private var tapCount = 0
    private val tapHandler = Handler(Looper.getMainLooper())
    private val viewLocation = IntArray(2)

    // Notes state
    private lateinit var notesStub: ViewStub
    private var notesContainer: FrameLayout? = null
    private var notesVisible = false
    private val notesAutoSaveHandler = Handler(Looper.getMainLooper())
    private var notesSaveRunnable: Runnable? = null
    private val notesFile by lazy { AtomicFile(File(filesDir, "gxcloud_notes.json")) }
    private val notesList = mutableListOf<Note>()
    private var selectedNoteId: String? = null
    private var notesLoaded = false
    // Set while loadNoteIntoEditor is driving the EditTexts. setText fires afterTextChanged,
    // and autoSave reads *both* fields — so without this the title's watcher writes the
    // still-stale bodyEdit contents into the note being loaded, destroying its body.
    private var notesLoadingEditor = false
    private var notesDirty = false

    data class Note(val id: String, var title: String, var body: String, var updatedAt: Long)

    inner class StreamBridge {
        @JavascriptInterface
        fun setDiscordEnabled(enabled: Boolean) {
            discordEnabled = enabled
        }

        @JavascriptInterface
        fun openNotes() {
            runOnUiThread { showNotes() }
        }

        @JavascriptInterface
        fun getDeviceStatusJson(): String {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return "{\"batteryPercent\":${if (pct in 0..100) pct else -1}}"
        }
    }

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

        webView.addJavascriptInterface(StreamBridge(), "AndroidBridge")
        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url == view.url) view.evaluateJavascript(INJECT_SCRIPT, null)
            }
        }

        discordStub = findViewById(R.id.discordStub)
        notesStub = findViewById(R.id.notesStub)

        setupBackHandler()
        webView.loadUrl("https://play.xbox.com/")
    }

    override fun onResume() {
        super.onResume()
        webView.resumeTimers()
        webView.onResume()
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        if (discordState != DiscordState.CLOSED) discordWebView?.onResume()
        audioManager.requestAudioFocus(audioFocusRequest)
    }

    override fun onPause() {
        super.onPause()
        if (notesVisible) forceNoteSave()
        webView.onPause()
        webView.pauseTimers()
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
        if (discordState != DiscordState.CLOSED) discordWebView?.onPause()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    override fun onDestroy() {
        tapHandler.removeCallbacksAndMessages(null)
        notesAutoSaveHandler.removeCallbacksAndMessages(null)
        notesSaveRunnable = null
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        discordWebView?.let { dv ->
            dv.webViewClient = WebViewClient()
            dv.webChromeClient = null
            (dv.parent as? ViewGroup)?.removeView(dv)
            dv.destroy()
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            if (!discordEnabled && discordState == DiscordState.CLOSED) return super.dispatchTouchEvent(ev)
            val countTap = discordState != DiscordState.UI_VISIBLE || !isTapOnDiscord(ev)
            if (countTap) {
                tapCount++
                tapHandler.removeCallbacksAndMessages(null)
                if (tapCount >= 4) {
                    tapCount = 0
                    onFourTaps()
                } else {
                    tapHandler.postDelayed({ tapCount = 0 }, 600)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isTapOnDiscord(ev: MotionEvent): Boolean {
        discordWebView!!.getLocationOnScreen(viewLocation)
        return ev.rawX >= viewLocation[0] && ev.rawX <= viewLocation[0] + discordWebView!!.width &&
               ev.rawY >= viewLocation[1] && ev.rawY <= viewLocation[1] + discordWebView!!.height
    }

    private fun onFourTaps() {
        when (discordState) {
            DiscordState.CLOSED -> if (discordEnabled) openDiscord()
            DiscordState.UI_VISIBLE -> closeDiscord()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun inflateDiscord() {
        val root = discordStub.inflate() as FrameLayout
        discordContainer = root
        discordWebView = root.findViewById<WebView>(R.id.discordWebView).also { dv ->
            dv.overScrollMode = View.OVER_SCROLL_NEVER
            dv.isVerticalScrollBarEnabled = false
            dv.isHorizontalScrollBarEnabled = false
            dv.isHapticFeedbackEnabled = false
            dv.isLongClickable = false
            dv.isSaveEnabled = false
            dv.isSaveFromParentEnabled = false
            dv.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            dv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dv.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
            }
            dv.setBackgroundColor(android.graphics.Color.BLACK)
            dv.settings.apply {
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
            CookieManager.getInstance().setAcceptThirdPartyCookies(dv, true)
            dv.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
                }
            }
            dv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (url == "about:blank") { view.clearHistory(); view.onPause() }
                }
            }
        }
    }

    private fun openDiscord() {
        if (discordContainer == null) inflateDiscord()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 0)
        }
        discordWebView!!.onResume()
        discordWebView!!.settings.blockNetworkImage = false
        discordWebView!!.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        discordContainer!!.visibility = View.VISIBLE
        discordWebView!!.loadUrl("https://discord.com/app")
        discordState = DiscordState.UI_VISIBLE
    }

    private fun closeDiscord() {
        discordWebView!!.loadUrl("about:blank")
        discordWebView!!.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
        discordContainer!!.visibility = View.GONE
        discordState = DiscordState.CLOSED
    }

    // ── Notes ────────────────────────────────────────────────────────────────

    private fun inflateNotes(): FrameLayout {
        val root = notesStub.inflate() as FrameLayout
        notesContainer = root

        val listContainer = root.findViewById<LinearLayout>(R.id.notesListContainer)
        val titleEdit = root.findViewById<EditText>(R.id.notesTitleEdit)
        val bodyEdit = root.findViewById<EditText>(R.id.notesBodyEdit)
        val newBtn = root.findViewById<Button>(R.id.notesNewBtn)
        val deleteBtn = root.findViewById<Button>(R.id.notesDeleteBtn)
        val closeBtn = root.findViewById<Button>(R.id.notesCloseBtn)

        closeBtn.setOnClickListener { hideNotes() }

        newBtn.setOnClickListener {
            forceNoteSave()
            val note = Note(UUID.randomUUID().toString(), "", "", System.currentTimeMillis())
            notesList.add(0, note)
            notesDirty = true
            saveNotes()
            selectNote(note.id, listContainer, titleEdit, bodyEdit)
        }

        deleteBtn.setOnClickListener {
            val id = selectedNoteId ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setMessage("Delete this note?")
                .setPositiveButton("Delete") { _, _ ->
                    notesList.removeAll { it.id == id }
                    if (notesList.isEmpty()) {
                        notesList.add(Note(UUID.randomUUID().toString(), "", "", System.currentTimeMillis()))
                    }
                    notesDirty = true
                    saveNotes()
                    // selectNote already rebuilds the list.
                    selectNote(notesList[0].id, listContainer, titleEdit, bodyEdit)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val autoSave = {
            val id = selectedNoteId
            if (id != null && !notesLoadingEditor) {
                notesList.find { it.id == id }?.let {
                    it.title = titleEdit.text.toString()
                    it.body = bodyEdit.text.toString()
                    it.updatedAt = System.currentTimeMillis()
                    notesDirty = true
                }
                scheduleNoteSave()
            }
        }

        titleEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = autoSave()
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        bodyEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = autoSave()
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        return root
    }

    private fun showNotes() {
        if (notesContainer == null) inflateNotes()
        if (!notesLoaded) {
            notesList.clear()
            notesList.addAll(loadNotes())
            if (notesList.isEmpty()) notesList.add(Note(UUID.randomUUID().toString(), "", "", System.currentTimeMillis()))
            notesLoaded = true
        }
        val root = notesContainer!!
        val listContainer = root.findViewById<LinearLayout>(R.id.notesListContainer)
        val titleEdit = root.findViewById<EditText>(R.id.notesTitleEdit)
        val bodyEdit = root.findViewById<EditText>(R.id.notesBodyEdit)
        rebuildNotesList(listContainer, titleEdit, bodyEdit)
        if (selectedNoteId == null || notesList.none { it.id == selectedNoteId }) {
            selectedNoteId = notesList[0].id
        }
        loadNoteIntoEditor(selectedNoteId!!, titleEdit, bodyEdit)
        notesContainer!!.visibility = View.VISIBLE
        notesVisible = true
    }

    private fun hideNotes() {
        forceNoteSave()
        notesContainer?.visibility = View.GONE
        notesVisible = false
    }

    private fun selectNote(id: String, listContainer: LinearLayout, titleEdit: EditText, bodyEdit: EditText) {
        selectedNoteId = id
        loadNoteIntoEditor(id, titleEdit, bodyEdit)
        rebuildNotesList(listContainer, titleEdit, bodyEdit)
    }

    private fun loadNoteIntoEditor(id: String, titleEdit: EditText, bodyEdit: EditText) {
        val note = notesList.find { it.id == id } ?: return
        notesLoadingEditor = true
        try {
            titleEdit.setText(note.title)
            bodyEdit.setText(note.body)
            titleEdit.setSelection(note.title.length)
        } finally {
            notesLoadingEditor = false
        }
    }

    @SuppressLint("SetTextI18n")
    private fun rebuildNotesList(listContainer: LinearLayout, titleEdit: EditText, bodyEdit: EditText) {
        listContainer.removeAllViews()
        notesList.forEach { note ->
            val row = TextView(this).apply {
                text = note.title.ifEmpty { "Untitled" }
                setTextColor(if (note.id == selectedNoteId) 0xFFFFFFFF.toInt() else 0xFF999999.toInt())
                setBackgroundColor(if (note.id == selectedNoteId) 0xFF2A2A2A.toInt() else 0x00000000)
                setPadding(40, 28, 40, 28)
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener {
                    forceNoteSave()
                    selectNote(note.id, listContainer, titleEdit, bodyEdit)
                }
            }
            listContainer.addView(row)
        }
    }

    private fun scheduleNoteSave() {
        notesSaveRunnable?.let { notesAutoSaveHandler.removeCallbacks(it) }
        val r = Runnable {
            notesSaveRunnable = null
            saveNotes()
        }
        notesSaveRunnable = r
        notesAutoSaveHandler.postDelayed(r, 600)
    }

    private fun forceNoteSave() {
        notesSaveRunnable?.let { notesAutoSaveHandler.removeCallbacks(it) }
        notesSaveRunnable = null
        saveNotes()
    }

    private fun loadNotes(): List<Note> {
        return try {
            val bytes = notesFile.readFully()
            val arr = JSONArray(String(bytes))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Note(o.getString("id"), o.getString("title"), o.getString("body"), o.getLong("updatedAt"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveNotes() {
        if (!notesLoaded || !notesDirty) return
        try {
            val arr = JSONArray()
            notesList.forEach { note ->
                arr.put(JSONObject().apply {
                    put("id", note.id)
                    put("title", note.title)
                    put("body", note.body)
                    put("updatedAt", note.updatedAt)
                })
            }
            val stream = notesFile.startWrite()
            try {
                stream.write(arr.toString().toByteArray())
                notesFile.finishWrite(stream)
                // Only clear on a completed write — a failure must stay dirty so the next
                // save attempt still has a reason to run.
                notesDirty = false
            } catch (e: Exception) {
                notesFile.failWrite(stream)
                throw e
            }
        } catch (_: Exception) {}
    }

    // ── Input ────────────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        val result = webView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_MODE,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_BUTTON_START ->
                    webView.evaluateJavascript("window.__gxcloudProbeJumpPanel&&window.__gxcloudProbeJumpPanel();", null)
            }
        }
        return result
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        return webView.dispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                notesVisible -> hideNotes()
                webView.canGoBack() -> webView.goBack()
            }
        }
    }

    companion object {
        private val INJECT_SCRIPT = """
            (function() {
                if (window.__gxcloudInjected) return;
                window.__gxcloudInjected = true;

                document.documentElement.style.overscrollBehavior = 'none';
                document.body.style.overscrollBehavior = 'none';


                const triVerts = new Float32Array([-1,-1,3,-1,-1,3]);
                const EMPTY_PIXEL = new Uint8Array([0,0,0,255]);

                const bridge = document.createElement('canvas');
                const bridgeCtx = bridge.getContext('2d', { alpha: false, willReadFrequently: false });
                bridgeCtx.imageSmoothingEnabled = false;
                bridgeCtx.globalCompositeOperation = 'copy';

                const style = document.createElement('style');
                style.textContent = '* { -webkit-tap-highlight-color: transparent !important; outline: none !important; }';
                document.head.appendChild(style);

                // Returns true once the toggle is hidden (or was already hidden), so callers
                // can stop retrying. Idempotent — cheap to call repeatedly.
                const hideMenuButton = () => {
                    const toggle = document.querySelector('button[aria-label="Quick actions toggle" i]');
                    if (!toggle) return false;
                    const container = toggle.closest('.absolute') ?? toggle.parentElement;
                    if (!container) return false;
                    if (container.dataset.hidden) return true;
                    container.dataset.hidden = 'true';
                    container.style.visibility = 'hidden';
                    container.addEventListener('mouseenter', () => container.style.visibility = 'visible');
                    container.addEventListener('mouseleave', () => container.style.visibility = 'hidden');
                    container.addEventListener('touchstart', () => container.style.visibility = 'visible');
                    container.addEventListener('touchend', () => setTimeout(() => container.style.visibility = 'hidden', 1000));
                    return true;
                };

                // Exactly one stream may own the pipeline at a time. `bridge` is shared by every
                // pipeline and _casCleanup shrinks it to 1x1, so an old pipeline tearing down
                // after a new one binds would leave the new one drawing through a 1x1 bridge —
                // on top of two stacked opaque canvases and two live GL contexts.
                let activeStreamVideo = null;

                // Full unbind: menu watch, CAS pipeline, and the binding marker, so the next
                // stream can bind even if xCloud re-uses the same <video> element.
                const teardown = (video) => {
                    if (video._gxMenuCleanup) video._gxMenuCleanup();
                    if (video._casCleanup) video._casCleanup();
                    delete video.dataset.gxBound;
                    if (activeStreamVideo === video) activeStreamVideo = null;
                };

                const setupWebGLCAS = (video) => {
                    if (video.dataset.casSetup) return;
                    video.dataset.casSetup = 'true';

                    const canvas = document.createElement('canvas');
                    canvas.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;pointer-events:none;contain:strict;';
                    document.body.appendChild(canvas);
                    video.style.visibility = 'hidden';

                    // Unwind a partial setup completely, so the element stays eligible for a
                    // later acquisition event instead of being stuck with casSetup set and no
                    // pipeline behind it.
                    const abortSetup = (glCtx, program, shaders) => {
                        if (glCtx) {
                            if (shaders) for (const s of shaders) if (s) glCtx.deleteShader(s);
                            if (program) glCtx.deleteProgram(program);
                            glCtx.getExtension('WEBGL_lose_context')?.loseContext();
                        }
                        canvas.remove();
                        video.style.visibility = '';
                        delete video.dataset.casSetup;
                    };

                    const gl = canvas.getContext('webgl2', { powerPreference: 'low-power', alpha: false, depth: false, stencil: false, preserveDrawingBuffer: false, antialias: false, desynchronized: true, premultipliedAlpha: false });
                    if (!gl) {
                        abortSetup(null, null, null);
                        return;
                    }

                    const vert = '#version 300 es\nin vec4 position;\nout vec2 vUV;\nvoid main(){gl_Position=position;vUV=vec2(position.x*0.5+0.5,0.5-position.y*0.5);}';
                    const frag = '#version 300 es\nprecision mediump float;\nuniform sampler2D data;\nin vec2 vUV;\nconst float sharpenFactor=0.37;\nout vec4 fragColor;\nvoid main(){\n  vec3 e=texture(data,vUV).rgb;\n  vec3 b=textureOffset(data,vUV,ivec2(0,1)).rgb;\n  vec3 d=textureOffset(data,vUV,ivec2(-1,0)).rgb;\n  vec3 f=textureOffset(data,vUV,ivec2(1,0)).rgb;\n  vec3 h=textureOffset(data,vUV,ivec2(0,-1)).rgb;\n  const vec3 lw=vec3(0.2126,0.7152,0.0722);\n  float le=dot(e,lw);float lb=dot(b,lw);float ld=dot(d,lw);float lf=dot(f,lw);float lh=dot(h,lw);\n  float mn_l=min(min(min(ld,le),min(lf,lb)),lh);\n  float mx_l=max(max(max(ld,le),max(lf,lb)),lh);\n  float amp=mn_l/(mx_l+0.01);\n  float wm=clamp((le-0.05)*2.2222,0.0,1.0);\n  float cg=clamp((mx_l-mn_l-0.005)*28.57,0.0,1.0);\n  float w=-(wm*cg)*(amp*0.2);\n  float rw=1.0/(4.0*w+1.0);\n  float detL=clamp(((lb+ld+lf+lh)*w+le)*rw,0.0,1.0)-le;\n  float satBoost=1.0+wm*0.18;\n  float sharpL=le+detL/(1.0+abs(detL)*4.0)*sharpenFactor*satBoost;\n  fragColor=vec4(clamp(vec3(sharpL)+(e-vec3(le))*satBoost,0.0,1.0),1.0);\n}';

                    const mkShader = (type, src) => {
                        const s = gl.createShader(type);
                        gl.shaderSource(s, src);
                        gl.compileShader(s);
                        if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
                            gl.deleteShader(s);
                            return null;
                        }
                        return s;
                    };

                    const prog = gl.createProgram();
                    const vs = mkShader(gl.VERTEX_SHADER, vert);
                    const fs = mkShader(gl.FRAGMENT_SHADER, frag);
                    if (!vs || !fs) {
                        abortSetup(gl, prog, [vs, fs]);
                        return;
                    }
                    gl.attachShader(prog, vs);
                    gl.attachShader(prog, fs);
                    gl.linkProgram(prog);
                    gl.detachShader(prog, vs); gl.deleteShader(vs);
                    gl.detachShader(prog, fs); gl.deleteShader(fs);
                    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
                        abortSetup(gl, prog, null);
                        return;
                    }
                    gl.useProgram(prog);
                    gl.disable(gl.BLEND);
                    gl.disable(gl.DITHER);

                    const vao = gl.createVertexArray();
                    gl.bindVertexArray(vao);

                    const buf = gl.createBuffer();
                    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
                    gl.bufferData(gl.ARRAY_BUFFER, triVerts, gl.STATIC_DRAW);
                    const posLoc = gl.getAttribLocation(prog, 'position');
                    gl.enableVertexAttribArray(posLoc);
                    gl.vertexAttribPointer(posLoc, 2, gl.FLOAT, false, 0, 0);

                    const tex = gl.createTexture();
                    gl.bindTexture(gl.TEXTURE_2D, tex);

                    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
                    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, EMPTY_PIXEL);
                    gl.activeTexture(gl.TEXTURE0);
                    gl.uniform1i(gl.getUniformLocation(prog, 'data'), 0);
                    // Runs synchronously on 'resize'. It used to be debounced 16ms, which left a
                    // frame where present() scaled the new stream resolution into the old bridge
                    // size — xCloud changes resolution mid-stream on network conditions, so that
                    // was a visible hitch every switch. Nothing to debounce: the event only fires
                    // when the dimensions actually changed, and the equality check below already
                    // absorbs any burst.
                    const _syncSize = () => {
                        if (!video.videoWidth || !video.videoHeight) return;
                        const w = video.videoWidth;
                        const h = video.videoHeight;
                        if (canvas.width === w && canvas.height === h) return;
                        canvas.width = bridge.width = w;
                        canvas.height = bridge.height = h;
                        // Resizing the bridge resets its 2D context to defaults — reapply
                        bridgeCtx.imageSmoothingEnabled = false;
                        bridgeCtx.globalCompositeOperation = 'copy';
                        gl.viewport(0, 0, w, h);
                    };
                    _syncSize();

                    video.addEventListener('loadedmetadata', _syncSize);
                    video.addEventListener('resize', _syncSize);

                    let frameHandle = null;
                    let vfcHandle = null;
                    const hasRVFC = 'requestVideoFrameCallback' in HTMLVideoElement.prototype;

                    const present = () => {
                        if (video.readyState < 2 || video.paused || document.hidden) return;
                        bridgeCtx.drawImage(video, 0, 0, bridge.width, bridge.height);
                        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, bridge);
                        gl.drawArrays(gl.TRIANGLES, 0, 3);
                    };

                    // Draw the moment the frame is decoded. This used to set a flag that the
                    // next rAF tick acted on, which cost up to a full vsync of latency before
                    // the frame was even copied, and put the GPU work on the vsync critical
                    // path. Draw count is unchanged — rVFC fires exactly once per decoded
                    // frame, which is what the old flag gated on.
                    const onVFC = () => {
                        vfcHandle = null;
                        present();
                        if (!video.paused && !document.hidden)
                            vfcHandle = video.requestVideoFrameCallback(onVFC);
                    };

                    // Teardown detection has to survive a stopped loop. 'pause' and
                    // visibilitychange both cancel rAF, and the liveness check lives inside
                    // render() — so quitting a game or switching games left the last frame
                    // frozen under the opaque canvas with no path back to teardown. This only
                    // ticks while the loop is already stopped: zero cost while streaming.
                    let watchdog = null;
                    let watchdogDelay = 500;
                    const stopWatchdog = () => {
                        if (watchdog !== null) { clearTimeout(watchdog); watchdog = null; }
                        // Reset here, not in armWatchdog: stopWatchdog is the "loop is alive
                        // again" signal (scheduleFrame calls it on resume), so the next stall
                        // starts back at a fast 500ms first check.
                        watchdogDelay = 500;
                    };
                    const checkAlive = () => {
                        watchdog = null;
                        if (!video.srcObject || !document.contains(video) || video.ended) {
                            teardown(video);
                            return;
                        }
                        // Paused-but-alive (srcObject intact, still in the DOM): nothing can
                        // change until an event fires, so don't keep polling at 2Hz forever —
                        // that was a permanent wakeup with nothing to do. Backs off
                        // 500 -> 1000 -> 2000 -> 4000 -> 5000ms. First check is still 500ms.
                        if (frameHandle === null) {
                            watchdogDelay = Math.min(watchdogDelay * 2, 5000);
                            watchdog = setTimeout(checkAlive, watchdogDelay);
                        }
                    };
                    const armWatchdog = () => {
                        if (watchdog === null) watchdog = setTimeout(checkAlive, watchdogDelay);
                    };

                    const scheduleFrame = () => {
                        if (frameHandle !== null) return;
                        // Arm rather than bail silently: this is the path taken when setup runs
                        // against an already-paused video, and when visibilitychange restores a
                        // stream that died while backgrounded.
                        if (video.paused || document.hidden) { armWatchdog(); return; }
                        stopWatchdog();
                        frameHandle = requestAnimationFrame(render);
                        if (hasRVFC && vfcHandle === null) vfcHandle = video.requestVideoFrameCallback(onVFC);
                    };

                    const cancelFrame = () => {
                        if (frameHandle !== null) { cancelAnimationFrame(frameHandle); frameHandle = null; }
                        if (vfcHandle !== null) { video.cancelVideoFrameCallback(vfcHandle); vfcHandle = null; }
                        armWatchdog();
                    };

                    // The rAF loop still spins every frame — it keeps the compositor and the
                    // CPU governor warm, which is why it is unconditional — but rVFC now drives
                    // the draw, so this is only the liveness ticker. It replaces the old
                    // parent-scoped removal MutationObserver, and remains the draw driver on
                    // the fallback path where rVFC is unavailable.
                    let liveCheck = 0;
                    const render = () => {
                        frameHandle = null;
                        if (++liveCheck >= 60) {
                            liveCheck = 0;
                            if (!video.srcObject || !document.contains(video)) {
                                teardown(video);
                                return;
                            }
                        }
                        if (!hasRVFC) present();
                        scheduleFrame();
                    };

                    const onVisibility = () => { if (document.hidden) cancelFrame(); else scheduleFrame(); };
                    // Bound on the element, not document capture: once xCloud unmounts the
                    // <video> these still fire, whereas the document-level 'emptied' listener
                    // never sees an event from a detached node.
                    const onStreamEnd = () => teardown(video);
                    video.addEventListener('pause', cancelFrame);
                    video.addEventListener('play', scheduleFrame);
                    video.addEventListener('ended', onStreamEnd);
                    video.addEventListener('error', onStreamEnd);
                    document.addEventListener('visibilitychange', onVisibility);
                    scheduleFrame();

                    // stopWatchdog() must follow cancelFrame() — cancelFrame arms the watchdog,
                    // so the reverse order leaves a timer running against a torn-down pipeline.
                    const detach = () => {
                        cancelFrame();
                        stopWatchdog();
                        video.removeEventListener('pause', cancelFrame);
                        video.removeEventListener('play', scheduleFrame);
                        video.removeEventListener('ended', onStreamEnd);
                        video.removeEventListener('error', onStreamEnd);
                        document.removeEventListener('visibilitychange', onVisibility);
                        video.removeEventListener('loadedmetadata', _syncSize);
                        video.removeEventListener('resize', _syncSize);
                        canvas.removeEventListener('webglcontextlost', onContextLost, false);
                        canvas.remove();
                        video.style.visibility = '';
                        delete video.dataset.casSetup;
                        delete video._casCleanup;
                    };

                    const onContextLost = (e) => {
                        e.preventDefault();
                        detach();
                    };
                    const onContextRestored = () => { setupWebGLCAS(video); };
                    canvas.addEventListener('webglcontextlost', onContextLost, false);
                    canvas.addEventListener('webglcontextrestored', onContextRestored, false);

                    video._casCleanup = () => {
                        detach();
                        // Both context listeners must be gone before loseContext(): it queues a
                        // real 'webglcontextlost' task, and by the time that task runs xCloud may
                        // have re-used this same <video> for the next stream. The stale handler
                        // would then delete the new pipeline's casSetup/_casCleanup (leaking its
                        // GL context and canvas on the following switch) and unhide the video so
                        // it composites underneath the new opaque canvas every frame.
                        canvas.removeEventListener('webglcontextrestored', onContextRestored, false);
                        bridge.width = 1; bridge.height = 1;
                        gl.getExtension('WEBGL_lose_context')?.loseContext();
                    };
                };

                const foundVideo = (video) => {
                    if (video.dataset.gxBound) return;
                    // Retire the previous stream before the new one allocates anything — see
                    // activeStreamVideo above for why overlap is not survivable.
                    if (activeStreamVideo && activeStreamVideo !== video) teardown(activeStreamVideo);
                    video.dataset.gxBound = 'true';
                    activeStreamVideo = video;

                    // xCloud renders the quick-actions toggle in the same commit as the video,
                    // so it is normally already in the DOM — check synchronously first. A
                    // MutationObserver alone starves here: the stream page goes DOM-quiet once
                    // playing, and only guide open/close churn would ever wake it.
                    const retryDelays = [150, 400, 1000, 2500, 5000];
                    let attempt = 0;
                    let menuTimer = null;
                    const menuObserver = new MutationObserver(() => { hideMenuButton(); });
                    const tryHide = () => {
                        menuTimer = null;
                        if (hideMenuButton()) {
                            // Landed. Keep a narrowly-scoped watch in case xCloud re-renders
                            // the toggle and drops our dataset marker.
                            const toggle = document.querySelector('button[aria-label="Quick actions toggle" i]');
                            const container = toggle?.closest('.absolute');
                            if (container?.parentNode) menuObserver.observe(container.parentNode, { childList: true });
                            return;
                        }
                        if (attempt >= retryDelays.length) return;
                        menuTimer = setTimeout(tryHide, retryDelays[attempt++]);
                    };
                    tryHide();

                    video._gxMenuCleanup = () => {
                        clearTimeout(menuTimer);
                        menuTimer = null;
                        attempt = retryDelays.length;
                        menuObserver.disconnect();
                        delete video._gxMenuCleanup;
                    };

                    setupWebGLCAS(video);
                };

                // xCloud plays three different <video> elements over a session: the Xbox
                // splash, a rocket loading animation, and the real stream. Binding to either
                // of the first two is why CAS appeared not to activate. The stream is the one
                // with no src (it is fed by srcObject) whose direct parent is the media
                // container — the only stable anchor; the element carries no id.
                const isStreamVideo = (v) => {
                    if (!v || v.tagName !== 'VIDEO' || v.src) return false;
                    const cls = typeof v.className === 'string' ? v.className : '';
                    if (cls.startsWith('XboxSplashVideo') || cls.includes('RocketAnimationVideo')) return false;
                    // Preferred anchor; fall back to srcObject alone so an upstream markup
                    // change degrades instead of never binding at all.
                    return v.parentElement?.dataset.testid === 'media-container' || !!v.srcObject;
                };

                const bindWhenSized = (v) => {
                    if (v.dataset.gxBound) return;
                    if (v.videoWidth) foundVideo(v);
                    else v.addEventListener('loadedmetadata', () => {
                        if (v.videoWidth) foundVideo(v);
                    }, { once: true });
                };

                // Primary acquisition. Patching play() catches the element the instant xCloud
                // plays it, even while it is still detached — capture-phase listeners on
                // document never see events from a node outside the tree, which is why the
                // event-only approach missed the stream.
                const nativePlay = HTMLMediaElement.prototype.play;
                HTMLMediaElement.prototype.play = function() {
                    if (isStreamVideo(this)) bindWhenSized(this);
                    return nativePlay.apply(this, arguments);
                };

                // Secondary net, in case the stream video was already playing before this
                // script was injected. Costs nothing until a media event actually fires.
                const onMediaReady = (e) => {
                    if (isStreamVideo(e.target) && e.target.videoWidth) foundVideo(e.target);
                };
                const onMediaGone = (e) => {
                    const v = e.target;
                    if (v && v.tagName === 'VIDEO' && v.dataset.gxBound) teardown(v);
                };
                document.addEventListener('loadedmetadata', onMediaReady, true);
                document.addEventListener('playing', onMediaReady, true);
                document.addEventListener('emptied', onMediaGone, true);

                let discordEnabledJS = false;

                const injectDiscordToggle = (panel) => {
                    if (panel.dataset.discordInjected || panel.dataset.discordPending) return;
                    const section = panel.querySelector('section[data-auto-focus="true"]');
                    if (!section) {
                        panel.dataset.discordPending = 'true';
                        let waitSafety;
                        const waitObserver = new MutationObserver(() => {
                            const s = panel.querySelector('section[data-auto-focus="true"]');
                            if (s) {
                                waitObserver.disconnect();
                                clearTimeout(waitSafety);
                                delete panel.dataset.discordPending;
                                injectDiscordToggle(panel);
                            }
                        });
                        waitObserver.observe(panel, { childList: true, subtree: true });
                        // Bounded like armJumpPanelWatch's safety net below — the section can
                        // fail to ever render (panel removed, upstream markup change), and
                        // without this the observer would watch the subtree forever.
                        waitSafety = setTimeout(() => {
                            waitObserver.disconnect();
                            delete panel.dataset.discordPending;
                        }, 3000);
                        return;
                    }
                    panel.dataset.discordInjected = 'true';
                    watchForJumpPanelRemoval(panel);

                    const buildNotesEl = () => {
                        const el = document.createElement('div');
                        el.id = '__notes-item';
                        el.style.cssText = 'display:flex;align-items:center;min-height:52px;padding:0 16px;gap:12px;cursor:pointer;';
                        el.innerHTML = '<svg style="width:20px;height:20px;flex-shrink:0;fill:#fff;" viewBox="0 0 24 24"><path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-5 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z"/></svg><span style="flex:1;color:#fff;font-size:14px;">Notes</span>';
                        el.addEventListener('click', () => {
                            if (typeof AndroidBridge !== 'undefined') AndroidBridge.openNotes();
                        });
                        return el;
                    };

                    const buildToggleEl = () => {
                        const el = document.createElement('div');
                        el.id = '__discord-toggle-item';
                        el.style.cssText = 'display:flex;align-items:center;min-height:52px;padding:0 16px;gap:12px;cursor:pointer;';
                        el.innerHTML = '<svg style="width:20px;height:20px;flex-shrink:0;fill:#fff;" viewBox="0 0 127.14 96.36"><path d="M107.7 8.07A105.2 105.2 0 0 0 81.47 0a72.1 72.1 0 0 0-3.36 6.83 97.7 97.7 0 0 0-29.11 0A72.3 72.3 0 0 0 45.64 0a105.9 105.9 0 0 0-26.25 8.09C2.79 32.65-1.71 56.6.54 80.21a105.7 105.7 0 0 0 32.17 16.15 77.7 77.7 0 0 0 6.89-11.11 68.4 68.4 0 0 1-10.85-5.18l2.56-2a75.6 75.6 0 0 0 64.58 0l2.59 2a68.3 68.3 0 0 1-10.87 5.19 77 77 0 0 0 6.89 11.1 105.3 105.3 0 0 0 32.19-16.14c2.64-27.38-4.51-51.11-18.9-72.15ZM42.45 65.69C36.18 65.69 31 60 31 53s5-12.74 11.43-12.74S54 46 53.89 53s-5.05 12.69-11.44 12.69Zm42.24 0C78.41 65.69 73.25 60 73.25 53s5-12.74 11.44-12.74S96.23 46 96.12 53s-5 12.69-11.43 12.69Z"/></svg><span style="flex:1;color:#fff;font-size:14px;">Discord</span><span class="__dt-track" style="position:relative;display:inline-block;width:44px;height:24px;border-radius:12px;background:#555;flex-shrink:0;transition:background .2s;"><span class="__dt-thumb" style="position:absolute;top:2px;left:2px;width:20px;height:20px;border-radius:50%;background:#fff;transition:transform .2s;"></span></span>';
                        const track = el.querySelector('.__dt-track');
                        const thumb = el.querySelector('.__dt-thumb');
                        track.style.background = discordEnabledJS ? '#5865F2' : '#555';
                        thumb.style.transform = discordEnabledJS ? 'translateX(20px)' : 'translateX(0)';
                        el.addEventListener('click', () => {
                            discordEnabledJS = !discordEnabledJS;
                            track.style.background = discordEnabledJS ? '#5865F2' : '#555';
                            thumb.style.transform = discordEnabledJS ? 'translateX(20px)' : 'translateX(0)';
                            if (typeof AndroidBridge !== 'undefined') AndroidBridge.setDiscordEnabled(discordEnabledJS);
                        });
                        return el;
                    };

                    // ID-guarded like the reinjector below: the panel can be re-injected after
                    // watchForJumpPanelRemoval clears the marker, and xCloud hides the guide
                    // rather than destroying it — so the section (and our rows) can still be
                    // there, which is how duplicate Notes/Discord entries appeared.
                    if (!section.querySelector('#__notes-item')) section.appendChild(buildNotesEl());
                    if (!section.querySelector('#__discord-toggle-item')) section.appendChild(buildToggleEl());

                    const buildStatusOverlay = () => {
                        let pct = '--';
                        try {
                            if (typeof AndroidBridge !== 'undefined') {
                                const s = JSON.parse(AndroidBridge.getDeviceStatusJson());
                                if (s.batteryPercent >= 0) pct = s.batteryPercent;
                            }
                        } catch(e) {}
                        const now = new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit', timeZone: 'America/New_York' }).format(new Date());
                        const video = activeStreamVideo || document.querySelector('video[data-gx-bound]');
                        const width = video?.videoWidth || 0;
                        const height = video?.videoHeight || 0;
                        const quality = video?.getVideoPlaybackQuality?.();
                        const decoded = quality?.totalVideoFrames ?? video?.webkitDecodedFrameCount ?? '--';
                        const dropped = quality?.droppedVideoFrames ?? video?.webkitDroppedFrameCount ?? '--';
                        const el = document.createElement('div');
                        el.id = '__gxcloud-status-overlay';
                        el.style.cssText = 'all:initial;position:fixed;top:5%;right:32px;pointer-events:none;display:flex;flex-direction:column;align-items:flex-end;gap:2px;z-index:2147483647;';
                        const line = (label, value, bold = false) => '<span style="all:initial;display:block;color:#fff;font:' + (bold ? '600 ' : '') + '15px/1.35 sans-serif;text-shadow:0 1px 4px rgba(0,0,0,.8);">' + label + ': ' + value + '</span>';
                        el.innerHTML = line('Battery', pct + '%', true) +
                            line('Time', now) +
                            line('Resolution', width && height ? width + '×' + height : '--') +
                            line('Decoded', decoded) +
                            line('Dropped', dropped);
                        return el;
                    };

                    document.getElementById('__gxcloud-status-overlay')?.remove();
                    document.documentElement.appendChild(buildStatusOverlay());

                    const reinjector = new MutationObserver(() => {
                        if (!section.querySelector('#__notes-item')) section.insertBefore(buildNotesEl(), section.querySelector('#__discord-toggle-item') || null);
                        if (!section.querySelector('#__discord-toggle-item')) section.appendChild(buildToggleEl());
                    });
                    reinjector.observe(section, { childList: true });
                    panel._discordReinjector = reinjector;
                };

                let jumpWatchArmed = false;
                const armJumpPanelWatch = () => {
                    const tryInject = () => {
                        const panel = document.getElementById('guide-tabpanel-jump');
                        if (panel && !panel.dataset.discordInjected) {
                            injectDiscordToggle(panel);
                        }
                        return !!panel;
                    };
                    // Warm case: panel already in the DOM — inject with zero observer cost.
                    if (tryInject()) return;
                    if (jumpWatchArmed) return;
                    jumpWatchArmed = true;
                    // Cold case: wait for the panel to render (arbitrary streaming lag),
                    // then inject and disconnect. Bounded so it never runs continuously.
                    let safety;
                    const obs = new MutationObserver(() => {
                        if (tryInject()) { obs.disconnect(); clearTimeout(safety); jumpWatchArmed = false; }
                    });
                    obs.observe(document.documentElement, { childList: true, subtree: true });
                    safety = setTimeout(() => { obs.disconnect(); jumpWatchArmed = false; }, 3000);
                };
                window.__gxcloudProbeJumpPanel = armJumpPanelWatch;
                const watchForJumpPanelRemoval = (panel) => {
                    const io = new IntersectionObserver((entries) => {
                        if (!entries[0].isIntersecting) {
                            io.disconnect();
                            if (panel._discordReinjector) { panel._discordReinjector.disconnect(); panel._discordReinjector = null; }
                            delete panel.dataset.discordInjected;
                            delete panel.dataset.discordPending;
                            // Remove the rows too, not just the marker. Leaving them behind is
                            // what let the next injection stack a second copy on top.
                            panel.querySelector('#__notes-item')?.remove();
                            panel.querySelector('#__discord-toggle-item')?.remove();
                            document.getElementById('__gxcloud-status-overlay')?.remove();
                        }
                    }, { threshold: 0 });
                    io.observe(panel);
                };
                // Warm case: already streaming when the script runs (WebView restore, or
                // re-injection after a real navigation) — no play() call or media event is
                // coming, so neither hook above would ever fire.
                for (const v of document.querySelectorAll('video')) {
                    if (isStreamVideo(v)) { bindWhenSized(v); break; }
                }
            })();
        """.trimIndent()
    }
}
