package com.example.gxcloud

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
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
    private var discordEnabled = false
    private var tapCount = 0
    private val tapHandler = Handler(Looper.getMainLooper())
    private val jumpPanelProbeHandler = Handler(Looper.getMainLooper())
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
            display?.supportedModes?.minByOrNull { abs(it.refreshRate - 60f) }?.let {
                lp.preferredDisplayModeId = it.modeId
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
            } else {
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
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
        webView.destroy()
        discordWebView?.webViewClient = WebViewClient()
        discordWebView?.webChromeClient = null
        discordWebView?.destroy()
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
                    saveNotes()
                    selectNote(notesList[0].id, listContainer, titleEdit, bodyEdit)
                    rebuildNotesList(listContainer, titleEdit, bodyEdit)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val autoSave = {
            val id = selectedNoteId
            if (id != null) {
                notesList.find { it.id == id }?.let {
                    it.title = titleEdit.text.toString()
                    it.body = bodyEdit.text.toString()
                    it.updatedAt = System.currentTimeMillis()
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
        titleEdit.setText(note.title)
        bodyEdit.setText(note.body)
        titleEdit.setSelection(note.title.length)
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
        val r = Runnable { saveNotes() }
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
        if (!notesLoaded) return
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
                KeyEvent.KEYCODE_BUTTON_START -> {
                    for (delay in longArrayOf(100, 250, 450)) {
                        jumpPanelProbeHandler.postDelayed({ webView.evaluateJavascript("window.__gxcloudProbeJumpPanel&&window.__gxcloudProbeJumpPanel();", null) }, delay)
                    }
                }
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
                style.textContent = '* { -webkit-tap-highlight-color: transparent !important; outline: none !important; } button[aria-label="Exit preview"] { visibility: hidden !important; }';
                document.head.appendChild(style);

                const hideMenuButton = () => {
                    const toggle = document.querySelector('button[aria-label="Quick actions toggle" i]');
                    if (toggle) {
                        const container = toggle.closest('.absolute') ?? toggle.parentElement;
                        if (container && !container.dataset.hidden) {
                            container.dataset.hidden = 'true';
                            container.style.visibility = 'hidden';
                            container.addEventListener('mouseenter', () => container.style.visibility = 'visible');
                            container.addEventListener('mouseleave', () => container.style.visibility = 'hidden');
                            container.addEventListener('touchstart', () => container.style.visibility = 'visible');
                            container.addEventListener('touchend', () => setTimeout(() => container.style.visibility = 'hidden', 1000));
                        }
                    }
                };

                const setupWebGLCAS = (video) => {
                    if (video.dataset.casSetup) return;
                    video.dataset.casSetup = 'true';

                    const canvas = document.createElement('canvas');
                    canvas.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;pointer-events:none;contain:strict;';
                    document.body.appendChild(canvas);
                    video.style.visibility = 'hidden';

                    const gl = canvas.getContext('webgl2', { powerPreference: 'low-power', alpha: false, depth: false, stencil: false, preserveDrawingBuffer: false, antialias: false, desynchronized: true, premultipliedAlpha: false });
                    if (!gl) {
                        canvas.remove();
                        video.style.visibility = '';
                        return;
                    }

                    const vert = '#version 300 es\nin vec4 position;\nout vec2 vUV;\nvoid main(){gl_Position=position;vUV=vec2(position.x*0.5+0.5,0.5-position.y*0.5);}';
                    const frag = '#version 300 es\nprecision mediump float;\nuniform sampler2D data;\nin vec2 vUV;\nconst float sharpenFactor=0.36;\nout vec4 fragColor;\nvoid main(){\n  vec3 e=texture(data,vUV).rgb;\n  vec3 b=textureOffset(data,vUV,ivec2(0,1)).rgb;\n  vec3 d=textureOffset(data,vUV,ivec2(-1,0)).rgb;\n  vec3 f=textureOffset(data,vUV,ivec2(1,0)).rgb;\n  vec3 h=textureOffset(data,vUV,ivec2(0,-1)).rgb;\n  const vec3 lw=vec3(0.2126,0.7152,0.0722);\n  float le=dot(e,lw);float lb=dot(b,lw);float ld=dot(d,lw);float lf=dot(f,lw);float lh=dot(h,lw);\n  float mn_l=min(min(min(ld,le),min(lf,lb)),lh);\n  float mx_l=max(max(max(ld,le),max(lf,lb)),lh);\n  float amp=mn_l/(mx_l+0.01);\n  float wm=clamp((le-0.05)*2.2222,0.0,1.0);\n  float cg=clamp((mx_l-mn_l-0.005)*28.57,0.0,1.0);\n  float w=-(wm*cg)*(amp*0.2);\n  float rw=1.0/(4.0*w+1.0);\n  vec3 det=clamp(((b+d+f+h)*w+e)*rw,0.0,1.0)-e;\n  vec3 s=e+det/(1.0+abs(det)*4.0)*sharpenFactor;\n  float satBoost=1.0+wm*0.18;\n  fragColor=vec4(clamp(mix(vec3(le),s,satBoost),0.0,1.0),1.0);\n}';

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
                        canvas.remove();
                        video.style.visibility = '';
                        return;
                    }
                    gl.attachShader(prog, vs);
                    gl.attachShader(prog, fs);
                    gl.linkProgram(prog);
                    gl.detachShader(prog, vs); gl.deleteShader(vs);
                    gl.detachShader(prog, fs); gl.deleteShader(fs);
                    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
                        canvas.remove();
                        video.style.visibility = '';
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
                    let syncTimer = null;
                    const syncSize = () => { clearTimeout(syncTimer); syncTimer = setTimeout(_syncSize, 16); };
                    const _syncSize = () => {
                        if (!video.videoWidth || !video.videoHeight) return;
                        const w = video.videoWidth;
                        const h = video.videoHeight;
                        if (canvas.width === w && canvas.height === h) return;
                        canvas.width = bridge.width = w;
                        canvas.height = bridge.height = h;
                        gl.viewport(0, 0, w, h);
                    };
                    _syncSize();

                    video.addEventListener('loadedmetadata', _syncSize);
                    video.addEventListener('resize', syncSize);

                    let frameHandle = null;
                    let vfcHandle = null;
                    let newFrame = false;
                    const hasRVFC = 'requestVideoFrameCallback' in HTMLVideoElement.prototype;

                    const onVFC = () => {
                        vfcHandle = null;
                        newFrame = true;
                        if (!video.paused && !document.hidden)
                            vfcHandle = video.requestVideoFrameCallback(onVFC);
                    };

                    const scheduleFrame = () => {
                        if (frameHandle !== null || video.paused || document.hidden) return;
                        frameHandle = requestAnimationFrame(render);
                        if (hasRVFC && vfcHandle === null) vfcHandle = video.requestVideoFrameCallback(onVFC);
                    };

                    const cancelFrame = () => {
                        if (frameHandle !== null) { cancelAnimationFrame(frameHandle); frameHandle = null; }
                        if (vfcHandle !== null) { video.cancelVideoFrameCallback(vfcHandle); vfcHandle = null; }
                        newFrame = false;
                    };

                    const render = () => {
                        frameHandle = null;
                        if (video.readyState >= 2 && !video.paused && !document.hidden) {
                            if (newFrame || !hasRVFC) {
                                newFrame = false;
                                bridgeCtx.drawImage(video, 0, 0, bridge.width, bridge.height);
                                gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, bridge);
                                gl.drawArrays(gl.TRIANGLES, 0, 3);
                            }
                        }
                        scheduleFrame();
                    };

                    const onVisibility = () => { if (document.hidden) cancelFrame(); else scheduleFrame(); };
                    video.addEventListener('pause', cancelFrame);
                    video.addEventListener('play', scheduleFrame);
                    document.addEventListener('visibilitychange', onVisibility);
                    scheduleFrame();

                    canvas.addEventListener('webglcontextlost', (e) => {
                        e.preventDefault();
                        cancelFrame();
                        video.removeEventListener('pause', cancelFrame);
                        video.removeEventListener('play', scheduleFrame);
                        document.removeEventListener('visibilitychange', onVisibility);
                        clearTimeout(syncTimer);
                        video.removeEventListener('loadedmetadata', _syncSize);
                        video.removeEventListener('resize', syncSize);
                        canvas.remove();
                        delete video.dataset.casSetup;
                        delete video._casCleanup;
                    }, false);
                    canvas.addEventListener('webglcontextrestored', () => {
                        setupWebGLCAS(video);
                    }, false);

                    video._casCleanup = () => {
                        cancelFrame();
                        video.removeEventListener('pause', cancelFrame);
                        video.removeEventListener('play', scheduleFrame);
                        document.removeEventListener('visibilitychange', onVisibility);
                        clearTimeout(syncTimer);
                        video.removeEventListener('loadedmetadata', _syncSize);
                        video.removeEventListener('resize', syncSize);
                        canvas.remove();
                        bridge.width = 1; bridge.height = 1;
                        gl.getExtension('WEBGL_lose_context')?.loseContext();
                        video.style.visibility = '';
                        delete video.dataset.casSetup;
                        delete video._casCleanup;
                    };
                };

                const foundVideo = (video) => {
                    let menuFound = false;
                    let poll = null;
                    const menuObserver = new MutationObserver((mutations) => {
                        if (!mutations.some(m => m.addedNodes.length > 0)) return;
                        const toggle = document.querySelector('button[aria-label="Quick actions toggle" i]');
                        if (toggle) {
                            menuFound = true;
                            clearTimeout(menuTimeout);
                            hideMenuButton();
                            const container = toggle.closest('.absolute');
                            if (container?.parentNode) {
                                menuObserver.disconnect();
                                menuObserver.observe(container.parentNode, { childList: true });
                            }
                        }
                    });
                    menuObserver.observe(document.body, { childList: true, subtree: true });
                    const menuTimeout = setTimeout(() => {
                        if (!menuFound) {
                            menuObserver.disconnect();
                            poll = setInterval(() => {
                                const toggle = document.querySelector('button[aria-label="Quick actions toggle" i]');
                                if (toggle) { clearInterval(poll); poll = null; hideMenuButton(); }
                            }, 20000);
                        }
                    }, 10000);
                    setupWebGLCAS(video);
                    watchForVideoRemoval(video, menuObserver, () => { if (poll) { clearInterval(poll); poll = null; } });
                };

                const startWatching = () => {
                    observer.observe(document.body, { childList: true, subtree: true });
                };

                const watchForVideoRemoval = (video, menuObserver, onCleanup) => {
                    const parent = video.parentNode;
                    if (!parent) return;
                    const removalObserver = new MutationObserver(() => {
                        if (!document.contains(video)) {
                            removalObserver.disconnect();
                            menuObserver.disconnect();
                            onCleanup();
                            if (video._casCleanup) video._casCleanup();
                            startWatching();
                        }
                    });
                    removalObserver.observe(parent, { childList: true });
                };

                const observer = new MutationObserver((mutations) => {
                    if (!mutations.some(m => m.addedNodes.length > 0)) return;
                    const video = document.querySelector('video');
                    if (video) {
                        observer.disconnect();
                        foundVideo(video);
                    }
                });

                let discordEnabledJS = false;

                const injectDiscordToggle = (panel) => {
                    if (panel.dataset.discordInjected) return;
                    const section = panel.querySelector('section[data-auto-focus="true"]');
                    if (!section) {
                        const waitObserver = new MutationObserver(() => {
                            const s = panel.querySelector('section[data-auto-focus="true"]');
                            if (s) { waitObserver.disconnect(); injectDiscordToggle(panel); }
                        });
                        waitObserver.observe(panel, { childList: true, subtree: true });
                        return;
                    }
                    panel.dataset.discordInjected = 'true';

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

                    section.appendChild(buildNotesEl());
                    section.appendChild(buildToggleEl());

                    const reinjector = new MutationObserver(() => {
                        if (!section.querySelector('#__notes-item')) section.insertBefore(buildNotesEl(), section.querySelector('#__discord-toggle-item') || null);
                        if (!section.querySelector('#__discord-toggle-item')) section.appendChild(buildToggleEl());
                    });
                    reinjector.observe(section, { childList: true });
                    panel._discordReinjector = reinjector;
                };

                window.__gxcloudProbeJumpPanel = () => {
                    // const panel = document.getElementById('jump-panel');
                    const panel = document.getElementById('guide-tabpanel-jump');
                    if (!panel || panel.dataset.discordInjected) return;
                    injectDiscordToggle(panel);
                    watchForJumpPanelRemoval(panel);
                };
                const watchForJumpPanelRemoval = (panel) => {
                    const parent = panel.parentNode;
                    if (!parent) return;
                    const removalObserver = new MutationObserver(() => {
                        if (!parent.contains(panel)) {
                            removalObserver.disconnect();
                            if (panel._discordReinjector) { panel._discordReinjector.disconnect(); panel._discordReinjector = null; }
                            delete panel.dataset.discordInjected;
                        }
                    });
                    removalObserver.observe(parent, { childList: true });
                };
                startWatching();
                const video = document.querySelector('video');
                if (video) {
                    observer.disconnect();
                    foundVideo(video);
                }
            })();
        """.trimIndent()
    }
}
