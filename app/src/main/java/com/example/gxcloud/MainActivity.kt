package com.example.gxcloud

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
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
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // Keep screen on while gaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Limit to 60Hz to save battery
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mode = display?.supportedModes
                ?.minByOrNull { abs(it.refreshRate - 60f) }
            mode?.let {
                window.attributes.preferredDisplayModeId = it.modeId
            }
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
                    .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .build()

        webView = findViewById(R.id.webview)
        WebView.setWebContentsDebuggingEnabled(false)

        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.setLayerType(View.LAYER_TYPE_NONE, null)
        webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

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

                const quadVerts = new Float32Array([-1,-1,3,-1,-1,3]);

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
                    video.parentNode.insertBefore(canvas, video);
                    video.style.visibility = 'hidden';

                    const gl = canvas.getContext('webgl2', { powerPreference: 'low-power', alpha: false, depth: false, stencil: false, preserveDrawingBuffer: false, antialias: false, desynchronized: true, failIfMajorPerformanceCaveat: true });
                    if (!gl) {
                        canvas.remove();
                        video.style.visibility = '';
                        return;
                    }

                    const vert = '#version 300 es\nin vec4 position;\nout vec2 vUV;\nvoid main(){gl_Position=position;vUV=vec2(position.x*0.5+0.5,0.5-position.y*0.5);}';
                    const frag = '#version 300 es\nprecision mediump float;\nuniform sampler2D data;\nuniform vec2 texelSize;\nuniform float sharpenFactor;\nin vec2 vUV;\nout vec4 fragColor;\nvoid main(){\n  vec3 e=texture(data,vUV).rgb;\n  vec3 b=texture(data,vUV+texelSize*vec2(0,1)).rgb;\n  vec3 d=texture(data,vUV+texelSize*vec2(-1,0)).rgb;\n  vec3 f=texture(data,vUV+texelSize*vec2(1,0)).rgb;\n  vec3 h=texture(data,vUV+texelSize*vec2(0,-1)).rgb;\n  vec3 lw=vec3(0.2126,0.7152,0.0722);\n  float le=dot(e,lw);float lb=dot(b,lw);float ld=dot(d,lw);float lf=dot(f,lw);float lh=dot(h,lw);\n  float mn_l=min(min(min(ld,le),min(lf,lb)),lh);\n  float mx_l=max(max(max(ld,le),max(lf,lb)),lh);\n  float amp=sqrt(clamp(min(mn_l,2.0-mx_l)/(mx_l+0.01),0.0,1.0));\n  float wm=smoothstep(0.05,0.5,le);\n  float cg=smoothstep(0.005,0.04,mx_l-mn_l);\n  float w=-(wm*amp*cg/8.0);\n  float rw=1.0/(4.0*w+1.0);\n  vec3 o=clamp(((b+d+f+h)*w+e)*rw,0.0,1.0);\n  vec3 det=o-e;\n  vec3 lim=det/(1.0+abs(det)*4.0);\n  vec3 s=e+lim*sharpenFactor;\n  float satBoost=mix(1.0,1.16,wm);\n  fragColor=vec4(clamp(mix(vec3(dot(s,lw)),s,satBoost),0.0,1.0),1.0);\n}';

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

                    const vao = gl.createVertexArray();
                    gl.bindVertexArray(vao);

                    const buf = gl.createBuffer();
                    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
                    gl.bufferData(gl.ARRAY_BUFFER, quadVerts, gl.STATIC_DRAW);
                    const posLoc = gl.getAttribLocation(prog, 'position');
                    gl.enableVertexAttribArray(posLoc);
                    gl.vertexAttribPointer(posLoc, 2, gl.FLOAT, false, 0, 0);

                    const tex = gl.createTexture();
                    gl.bindTexture(gl.TEXTURE_2D, tex);

                    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
                    gl.activeTexture(gl.TEXTURE0);
                    gl.uniform1i(gl.getUniformLocation(prog, 'data'), 0);
                    const texelSizeLoc = gl.getUniformLocation(prog, 'texelSize');
                    gl.uniform1f(gl.getUniformLocation(prog, 'sharpenFactor'), 0.35);

                    const bridge = document.createElement('canvas');
                    const bridgeCtx = bridge.getContext('2d', { alpha: false, willReadFrequently: false });
                    bridgeCtx.imageSmoothingEnabled = false;

                    let syncTimer = null;
                    const syncSize = () => { clearTimeout(syncTimer); syncTimer = setTimeout(_syncSize, 200); };
                    const _syncSize = () => {
                        if (!video.videoWidth || !video.videoHeight) return;
                        const w = video.videoWidth;
                        const h = video.videoHeight;
                        const r = video.getBoundingClientRect();
                        const vz = parseInt(window.getComputedStyle(video).zIndex) || 0;
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

                    const useRVFC = 'requestVideoFrameCallback' in HTMLVideoElement.prototype;
                    let frameHandle = null;

                    const scheduleFrame = () => {
                        if (frameHandle !== null || video.paused || document.hidden) return;
                        frameHandle = useRVFC
                            ? video.requestVideoFrameCallback(render)
                            : requestAnimationFrame(render);
                    };

                    const cancelFrame = () => {
                        if (frameHandle === null) return;
                        if (useRVFC) video.cancelVideoFrameCallback(frameHandle);
                        else cancelAnimationFrame(frameHandle);
                        frameHandle = null;
                    };

                    const render = () => {
                        frameHandle = null;
                        if (video.readyState >= 2 && !video.paused && !document.hidden) {
                            bridgeCtx.drawImage(video, 0, 0, bridge.width, bridge.height);
                            gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, bridge);
                            gl.drawArrays(gl.TRIANGLES, 0, 3);
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
                            const poll = setInterval(() => {
                                const toggle = document.querySelector('button[aria-label="Quick Actions Toggle"]');
                                if (toggle) { clearInterval(poll); hideMenuButton(); }
                            }, 3000);
                        }
                    }, 10000);
                    setupWebGLCAS(video);
                    watchForVideoRemoval(video, menuObserver);
                };

                const startWatching = () => {
                    observer.observe(document.body, { childList: true, subtree: true });
                };

                const watchForVideoRemoval = (video, menuObserver) => {
                    const parent = video.parentNode;
                    if (!parent) return;
                    const removalObserver = new MutationObserver(() => {
                        if (!document.contains(video)) {
                            removalObserver.disconnect();
                            menuObserver.disconnect();
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