package com.example.gxcloud

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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

        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url == view.url) view.evaluateJavascript(INJECT_SCRIPT, null)
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
        audioManager.requestAudioFocus(audioFocusRequest)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
        webView.destroy()
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        return webView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        return webView.dispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
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
                    const toggle = document.querySelector('button[aria-label="Quick Actions Toggle"]');
                    if (toggle) {
                        const container = toggle.closest('.absolute');
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
                    canvas.style.contain = 'strict';
                    video.parentNode.insertBefore(canvas, video);
                    video.style.visibility = 'hidden';

                    const gl = canvas.getContext('webgl2', { powerPreference: 'low-power', alpha: false, depth: false, stencil: false, preserveDrawingBuffer: false, antialias: false, desynchronized: true, premultipliedAlpha: false });
                    if (!gl) {
                        canvas.remove();
                        video.style.visibility = '';
                        return;
                    }

                    const vert = '#version 300 es\nin vec4 position;\nuniform vec2 texelSize;\nout vec2 vUV;\nout vec2 vUVb;\nout vec2 vUVd;\nout vec2 vUVf;\nout vec2 vUVh;\nvoid main(){gl_Position=position;vUV=vec2(position.x*0.5+0.5,0.5-position.y*0.5);vUVb=vUV+vec2(0.0,texelSize.y);vUVd=vUV+vec2(-texelSize.x,0.0);vUVf=vUV+vec2(texelSize.x,0.0);vUVh=vUV+vec2(0.0,-texelSize.y);}';
                    const frag = '#version 300 es\nprecision mediump float;\nuniform sampler2D data;\nin vec2 vUV;\nin vec2 vUVb;\nin vec2 vUVd;\nin vec2 vUVf;\nin vec2 vUVh;\nconst float sharpenFactor=0.35;\nout vec4 fragColor;\nvoid main(){\n  vec3 e=texture(data,vUV).rgb;\n  vec3 b=texture(data,vUVb).rgb;\n  vec3 d=texture(data,vUVd).rgb;\n  vec3 f=texture(data,vUVf).rgb;\n  vec3 h=texture(data,vUVh).rgb;\n  const vec3 lw=vec3(0.2126,0.7152,0.0722);\n  float le=dot(e,lw);float lb=dot(b,lw);float ld=dot(d,lw);float lf=dot(f,lw);float lh=dot(h,lw);\n  float mn_l=min(min(min(ld,le),min(lf,lb)),lh);\n  float mx_l=max(max(max(ld,le),max(lf,lb)),lh);\n  float amp=mn_l/(mx_l+0.01);\n  float wm=clamp((le-0.05)*2.2222,0.0,1.0);\n  float cg=clamp((mx_l-mn_l-0.005)*28.57,0.0,1.0);\n  float w=-(wm*cg)*(amp*0.2);\n  float rw=1.0/(4.0*w+1.0);\n  vec3 o=clamp(((b+d+f+h)*w+e)*rw,0.0,1.0);\n  vec3 det=o-e;\n  vec3 lim=det/(1.0+abs(det)*4.0);\n  vec3 s=e+lim*sharpenFactor;\n  float satBoost=1.0+wm*0.16;\n  fragColor=vec4(clamp(mix(vec3(le),s,satBoost),0.0,1.0),1.0);\n}';

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
                    gl.hint(gl.GENERATE_MIPMAP_HINT, gl.FASTEST);

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
                    const texelSizeLoc = gl.getUniformLocation(prog, 'texelSize');

                    let syncTimer = null;
                    const syncSize = () => { clearTimeout(syncTimer); syncTimer = setTimeout(_syncSize, 50); };
                    const _syncSize = () => {
                        if (!video.videoWidth || !video.videoHeight) return;
                        const w = video.videoWidth;
                        const h = video.videoHeight;
                        const r = video.getBoundingClientRect();
                        const vz = +getComputedStyle(video).zIndex || 0;
                        const cz = isNaN(vz) ? 1 : vz + 1;
                        canvas.style.cssText = 'position:fixed;z-index:' + cz + ';top:' + Math.round(r.top) + 'px;left:' + Math.round(r.left) + 'px;width:' + Math.round(r.width) + 'px;height:' + Math.round(r.height) + 'px;pointer-events:none;';
                        if (canvas.width === w && canvas.height === h) return;
                        canvas.width = bridge.width = w;
                        canvas.height = bridge.height = h;
                        gl.viewport(0, 0, w, h);
                        gl.uniform2f(texelSizeLoc, 1.0/w, 1.0/h);
                    };
                    _syncSize();

                    video.addEventListener('loadedmetadata', _syncSize);
                    video.addEventListener('resize', syncSize);

                    const ro = new ResizeObserver(syncSize);
                    ro.observe(video);

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
                        ro.disconnect();
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
                        ro.disconnect();
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
                        const toggle = document.querySelector('button[aria-label="Quick Actions Toggle"]');
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
                                const toggle = document.querySelector('button[aria-label="Quick Actions Toggle"]');
                                if (toggle) { clearInterval(poll); poll = null; hideMenuButton(); }
                            }, 7000);
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
