package com.example.gxcloud

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )

        setContentView(R.layout.activity_main)

        window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT

        webView = findViewById(R.id.webview)

        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Edg/122.0.0.0"
            safeBrowsingEnabled = false
            setGeolocationEnabled(false)
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
        }

        // Allow cookies
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(INJECT_SCRIPT, null)
            }
        }

        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)

        setupBackHandler()
        webView.loadUrl("https://play.xbox.com/")
    }

    override fun onResume() {
        super.onResume()
        webView.resumeTimers()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
        webView.destroy()
        super.onDestroy()
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

                    const gl = canvas.getContext('webgl2', { powerPreference: 'low-power', alpha: false, depth: false, stencil: false, preserveDrawingBuffer: false, antialias: false, desynchronized: false });
                    if (!gl) {
                        canvas.remove();
                        video.style.visibility = '';
                        return;
                    }

                    const vert = '#version 300 es\nin vec4 position;\nvoid main(){gl_Position=position;}';
                    const frag = '#version 300 es\nprecision mediump float;\nuniform sampler2D data;\nuniform vec2 iResolution;\nuniform float sharpenFactor;\nout vec4 fragColor;\nvoid main(){\n  vec2 uv=gl_FragCoord.xy/iResolution.xy;\n  vec2 ts=1.0/iResolution.xy;\n  vec3 e=texture(data,uv).rgb;\n  vec3 b=texture(data,uv+ts*vec2(0,1)).rgb;\n  vec3 d=texture(data,uv+ts*vec2(-1,0)).rgb;\n  vec3 f=texture(data,uv+ts*vec2(1,0)).rgb;\n  vec3 h=texture(data,uv+ts*vec2(0,-1)).rgb;\n  vec3 mn=min(min(min(d,e),min(f,b)),h);\n  vec3 mx=max(max(max(d,e),max(f,b)),h);\n  vec3 amp=clamp(min(mn,2.0-mx)/mx,0.0,1.0);\n  amp=inversesqrt(amp);\n  vec3 w=-(1.0/(amp*5.6));\n  vec3 rw=1.0/(4.0*w+1.0);\n  vec3 o=clamp(((b+d+f+h)*w+e)*rw,0.0,1.0);\n  vec3 s=mix(e,o,sharpenFactor);\n  vec3 l=vec3(dot(s,vec3(0.2126,0.7152,0.0722)));\n  fragColor=vec4(mix(l,s,1.15),1.0);\n}';

                    const mkShader = (type, src) => {
                        const s = gl.createShader(type);
                        gl.shaderSource(s, src);
                        gl.compileShader(s);
                        return s;
                    };

                    const prog = gl.createProgram();
                    const vs = mkShader(gl.VERTEX_SHADER, vert);
                    const fs = mkShader(gl.FRAGMENT_SHADER, frag);
                    gl.attachShader(prog, vs);
                    gl.attachShader(prog, fs);
                    gl.linkProgram(prog);
                    gl.detachShader(prog, vs); gl.deleteShader(vs);
                    gl.detachShader(prog, fs); gl.deleteShader(fs);
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

                    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
                    gl.activeTexture(gl.TEXTURE0);
                    gl.uniform1i(gl.getUniformLocation(prog, 'data'), 0);
                    const resLoc = gl.getUniformLocation(prog, 'iResolution');
                    gl.uniform1f(gl.getUniformLocation(prog, 'sharpenFactor'), 0.35);

                    const bridge = document.createElement('canvas');
                    const bridgeCtx = bridge.getContext('2d');

                    let syncTimer = null;
                    const syncSize = () => { clearTimeout(syncTimer); syncTimer = setTimeout(_syncSize, 100); };
                    const _syncSize = () => {
                        const r = video.getBoundingClientRect();
                        const w = Math.round(video.videoWidth || Math.round(r.width));
                        const h = Math.round(video.videoHeight || Math.round(r.height));
                        if (canvas.width === w && canvas.height === h) return;
                        canvas.width = w;
                        canvas.height = h;
                        bridge.width = w;
                        bridge.height = h;
                        canvas.style.cssText = 'position:fixed;z-index:99999;top:' + r.top + 'px;left:' + r.left + 'px;width:' + r.width + 'px;height:' + r.height + 'px;pointer-events:none;';
                        gl.viewport(0, 0, w, h);
                        gl.uniform2f(resLoc, w, h);
                    };
                    _syncSize();

                    video.addEventListener('loadedmetadata', syncSize);
                    video.addEventListener('resize', syncSize);

                    const ro = new ResizeObserver(syncSize);
                    ro.observe(video);

                    const useRVFC = 'requestVideoFrameCallback' in HTMLVideoElement.prototype;
                    let frameHandle = null;
                    let lastTime = -1;

                    const render = () => {
                        if (video.readyState >= 2 && !video.paused && !document.hidden) {
                            const t = video.currentTime;
                            if (t !== lastTime) {
                                lastTime = t;
                                bridgeCtx.drawImage(video, 0, 0, bridge.width, bridge.height);
                                gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, gl.RGB, gl.UNSIGNED_BYTE, bridge);
                                gl.drawArrays(gl.TRIANGLES, 0, 3);
                            }
                        }
                        frameHandle = useRVFC
                            ? video.requestVideoFrameCallback(render)
                            : requestAnimationFrame(render);
                    };
                    frameHandle = useRVFC
                        ? video.requestVideoFrameCallback(render)
                        : requestAnimationFrame(render);

                    video._casCleanup = () => {
                        if (useRVFC) video.cancelVideoFrameCallback(frameHandle);
                        else cancelAnimationFrame(frameHandle);
                        clearTimeout(syncTimer);
                        video.removeEventListener('loadedmetadata', syncSize);
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
                            }, 2000);
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