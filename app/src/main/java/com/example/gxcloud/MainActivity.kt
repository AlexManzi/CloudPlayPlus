package com.example.gxcloud

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
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
            val mode = windowManager.defaultDisplay.supportedModes
                .minByOrNull { Math.abs(it.refreshRate - 60f) }
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
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            safeBrowsingEnabled = false
            setGeolocationEnabled(false)
            allowContentAccess = false
            @Suppress("DEPRECATION")
            saveFormData = false
        }

        // Allow cookies
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                view.loadUrl(request.url.toString())
                return true
            }

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
                style.textContent = '* { -webkit-tap-highlight-color: transparent !important; outline: none !important; }';
                document.head.appendChild(style);

                const hideMenuButton = () => {
                    const toggle = document.querySelector('button[aria-label="Quick Actions Toggle"]');
                    if (toggle) {
                        const container = toggle.closest('.absolute');
                        if (container && !container.dataset.hidden) {
                            container.dataset.hidden = 'true';
                            container.style.opacity = '0';
                            container.style.transition = 'opacity 0.2s';
                            container.addEventListener('touchstart', () => container.style.opacity = '0.5');
                            container.addEventListener('touchend', () => setTimeout(() => container.style.opacity = '0', 1000));
                        }
                    }
                };

                const injectSvgFilter = () => {
                    if (!document.getElementById('gxcloud-svg-filter')) {
                        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                        svg.id = 'gxcloud-svg-filter';
                        svg.setAttribute('style', 'position:absolute;width:0;height:0;overflow:hidden');
                        svg.innerHTML = '<defs><filter id="gxcloud-sharpen" x="0" y="0" width="100%" height="100%" color-interpolation-filters="sRGB"><feConvolveMatrix order="3" kernelMatrix="0 -1 0 -1 7.3 -1 0 -1 0" edgeMode="duplicate" preserveAlpha="true"/><feColorMatrix type="saturate" values="1.2"/></filter></defs>';
                        document.body.appendChild(svg);
                    }
                };

                const applySharpening = (video) => {
                    if (!video.dataset.sharpened) {
                        video.dataset.sharpened = 'true';
                        injectSvgFilter();
                        video.style.filter = 'url(#gxcloud-sharpen)';
                    }
                };

                const observer = new MutationObserver(() => {
                    hideMenuButton();
                    const video = document.querySelector('video');
                    if (video) applySharpening(video);
                });

                observer.observe(document.body, { childList: true, subtree: true });
                hideMenuButton();
                const video = document.querySelector('video');
                if (video) applySharpening(video);
            })();
        """.trimIndent()

        view.evaluateJavascript(script, null)
    }
}