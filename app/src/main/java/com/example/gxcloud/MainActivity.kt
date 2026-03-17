package com.example.gxcloud

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while gaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Limit to 60Hz to save battery
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val mode = display?.supportedModes
                ?.minByOrNull { Math.abs(it.refreshRate - 60f) }
            mode?.let {
                window.attributes.preferredDisplayModeId = it.modeId
            }
        }

        // True fullscreen - hide system bars
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)

        // WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            safeBrowsingEnabled = false
            offscreenPreRaster = false
            setGeolocationEnabled(false)
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            databaseEnabled = false
        }

        // Allow cookies
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectScript(view)
            }
        }

        setupBackHandler()
        webView.loadUrl("https://play.xbox.com/")
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }
    }

    private fun injectScript(view: WebView) {
        val script = """
            (function() {
                if (window.__gxcloudInjected) return;
                window.__gxcloudInjected = true;

                const style = document.createElement('style');
                style.textContent = '* { -webkit-tap-highlight-color: transparent !important; outline: none !important; } button[aria-label="Exit preview"] { visibility: hidden !important; }';
                document.head.appendChild(style);

                let menuHidden = false;
                const hideMenuButton = () => {
                    if (menuHidden) return;
                    const toggle = document.querySelector('button[aria-label="Quick Actions Toggle"]');
                    if (toggle) {
                        const container = toggle.closest('.absolute');
                        if (container && !container.dataset.hidden) {
                            container.dataset.hidden = 'true';
                            container.style.opacity = '0';
                            container.style.transition = 'opacity 0.2s';
                            container.addEventListener('touchstart', () => container.style.opacity = '0.5');
                            container.addEventListener('touchend', () => setTimeout(() => container.style.opacity = '0', 1000));
                            menuHidden = true;
                        }
                    }
                };

                const setupWebGLCAS = (video) => {
                    if (video.dataset.casSetup) return;
                    video.dataset.casSetup = 'true';

                    const canvas = document.createElement('canvas');
                    document.body.appendChild(canvas);
                    video.style.visibility = 'hidden';

                    const gl = canvas.getContext('webgl2');
                    if (!gl) {
                        canvas.remove();
                        video.style.visibility = '';
                        return;
                    }

                    const vert = '#version 300 es\nin vec4 position;\nvoid main(){gl_Position=position;}';
                    const frag = '#version 300 es\nprecision highp float;\nuniform sampler2D data;\nuniform vec2 iResolution;\nuniform float sharpenFactor;\nout vec4 fragColor;\nvoid main(){\n  vec2 uv=gl_FragCoord.xy/iResolution.xy;\n  vec2 ts=1.0/iResolution.xy;\n  vec3 e=texture(data,uv).rgb;\n  vec3 b=texture(data,uv+ts*vec2(0,1)).rgb;\n  vec3 d=texture(data,uv+ts*vec2(-1,0)).rgb;\n  vec3 f=texture(data,uv+ts*vec2(1,0)).rgb;\n  vec3 h=texture(data,uv+ts*vec2(0,-1)).rgb;\n  vec3 mn=min(min(min(d,e),min(f,b)),h);\n  vec3 mx=max(max(max(d,e),max(f,b)),h);\n  vec3 amp=clamp(min(mn,2.0-mx)/mx,0.0,1.0);\n  amp=inversesqrt(amp);\n  vec3 w=-(1.0/(amp*5.6));\n  vec3 rw=1.0/(4.0*w+1.0);\n  vec3 o=clamp(((b+d+f+h)*w+e)*rw,0.0,1.0);\n  vec3 s=mix(e,o,sharpenFactor);\n  vec3 l=vec3(dot(s,vec3(0.2126,0.7152,0.0722)));\n  fragColor=vec4(mix(l,s,1.2),1.0);\n}';

                    const mkShader = (type, src) => {
                        const s = gl.createShader(type);
                        gl.shaderSource(s, src);
                        gl.compileShader(s);
                        return s;
                    };

                    const prog = gl.createProgram();
                    gl.attachShader(prog, mkShader(gl.VERTEX_SHADER, vert));
                    gl.attachShader(prog, mkShader(gl.FRAGMENT_SHADER, frag));
                    gl.linkProgram(prog);
                    gl.useProgram(prog);

                    const vao = gl.createVertexArray();
                    gl.bindVertexArray(vao);

                    const buf = gl.createBuffer();
                    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
                    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,3,-1,-1,3]), gl.STATIC_DRAW);
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

                    gl.uniform1i(gl.getUniformLocation(prog, 'data'), 0);
                    gl.uniform1f(gl.getUniformLocation(prog, 'sharpenFactor'), 0.5);
                    const resLoc = gl.getUniformLocation(prog, 'iResolution');

                    const syncSize = () => {
                        const r = video.getBoundingClientRect();
                        canvas.width = video.videoWidth || Math.round(r.width);
                        canvas.height = video.videoHeight || Math.round(r.height);
                        canvas.style.cssText = 'position:fixed;top:' + r.top + 'px;left:' + r.left + 'px;width:' + r.width + 'px;height:' + r.height + 'px;pointer-events:none;z-index:9999;';
                        gl.viewport(0, 0, canvas.width, canvas.height);
                        gl.uniform2f(resLoc, canvas.width, canvas.height);
                        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, canvas.width, canvas.height, 0, gl.RGB, gl.UNSIGNED_BYTE, null);
                    };
                    syncSize();

                    const ro = new ResizeObserver(syncSize);
                    ro.observe(video);

                    const useRVFC = 'requestVideoFrameCallback' in HTMLVideoElement.prototype;
                    let frameHandle = null;

                    const render = () => {
                        if (video.readyState >= 2) {
                            gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, gl.RGB, gl.UNSIGNED_BYTE, video);
                            gl.drawArrays(gl.TRIANGLES, 0, 3);
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
                        ro.disconnect();
                        canvas.remove();
                        video.style.visibility = '';
                        delete video.dataset.casSetup;
                        delete video._casCleanup;
                    };
                };

                let observerTimeout = null;
                let pollInterval = null;

                const startPolling = () => {
                    pollInterval = setInterval(() => {
                        hideMenuButton();
                        const video = document.querySelector('video');
                        if (video && menuHidden) {
                            clearInterval(pollInterval);
                            pollInterval = null;
                            setupWebGLCAS(video);
                            watchForVideoRemoval(video);
                        }
                    }, 2000);
                };

                const startWatching = () => {
                    menuHidden = false;
                    observer.observe(document.body, { childList: true, subtree: true });
                    observerTimeout = setTimeout(() => {
                        observer.disconnect();
                        startPolling();
                    }, 20000);
                };

                const watchForVideoRemoval = (video) => {
                    const parent = video.parentNode;
                    if (!parent) return;
                    const removalObserver = new MutationObserver(() => {
                        if (!document.contains(video)) {
                            removalObserver.disconnect();
                            if (video._casCleanup) video._casCleanup();
                            startWatching();
                        }
                    });
                    removalObserver.observe(parent, { childList: true });
                };

                const observer = new MutationObserver(() => {
                    hideMenuButton();
                    const video = document.querySelector('video');
                    if (video && menuHidden) {
                        clearTimeout(observerTimeout);
                        setupWebGLCAS(video);
                        observer.disconnect();
                        watchForVideoRemoval(video);
                    }
                });

                startWatching();
                hideMenuButton();
                const video = document.querySelector('video');
                if (video) {
                    clearTimeout(observerTimeout);
                    setupWebGLCAS(video);
                    observer.disconnect();
                    watchForVideoRemoval(video);
                }
            })();
        """.trimIndent()

        view.evaluateJavascript(script, null)
    }
}