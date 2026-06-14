package com.kododake.aavideo.web

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import com.kododake.aavideo.R
import com.kododake.aavideo.model.UserAgentProfile
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.KeyEvent

data class BrowserCallbacks(
    val onUrlChange: (String) -> Unit = {},
    val onTitleChange: (String?) -> Unit = {},
    val onFaviconReceived: (String, Bitmap?) -> Unit = { _, _ -> },
    val onProgressChange: (Int) -> Unit = {},
    val onShowDownloadPrompt: (Uri) -> Unit = {},
    val onError: (Int, String?) -> Unit = { _, _ -> },
    val onCleartextNavigationRequested: (
        Uri,
        allowOnce: () -> Unit,
        allowHostPermanently: () -> Unit,
        cancel: () -> Unit
    ) -> Unit = { _, _, _, cancel -> cancel() },
    val onEnterFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit = { _, _ -> },
    val onExitFullscreen: () -> Unit = {},
    val onPermissionRequest: (PermissionRequest) -> Unit = { it.deny() }
)

fun configureWebView(
    webView: WebView,
    callbacks: BrowserCallbacks = BrowserCallbacks(),
    useDesktopMode: Boolean = false,
    userAgentProfile: UserAgentProfile = UserAgentProfile.ANDROID_CHROME,
    allowDarkPages: Boolean = false,
    adBlockEnabled: Boolean = false
) {
    with(webView) {
        setBackgroundColor(Color.TRANSPARENT)

        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = true

        WebView.setWebContentsDebuggingEnabled(false)

        val originalUserAgent = settings.userAgentString
        setTag(R.id.webview_original_user_agent_tag, originalUserAgent)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = !com.kododake.aavideo.data.BrowserPreferences.isPopupBlockerEnabled(context)

            setSupportMultipleWindows(true)

            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowContentAccess = true
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                offscreenPreRaster = true
            }
        }

        applyPageDarkening(allowDarkPages)
        applyUserAgent(userAgentProfile, useDesktopMode)
        setTag(R.id.webview_ad_block_tag, adBlockEnabled)
        addJavascriptInterface(AdBlockBridge(context), "AdBlockBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val script = com.kododake.aavideo.adblock.UnifiedAdBlocker.getDocumentStartJs()
            WebViewCompat.addDocumentStartJavaScript(this, script, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(this, MEDIA_LISTENER_JS, setOf("*"))
        }
        val scale = context.resources.displayMetrics.density * 100
        setInitialScale(scale.toInt())
        
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val activeProxyPort = com.kododake.aavideo.net.LocalDnsProxy.start()
            if (activeProxyPort > 0) {
                val proxyConfig = ProxyConfig.Builder()
                    .addProxyRule("127.0.0.1:$activeProxyPort")
                    .addBypassRule("localhost")
                    .addBypassRule("127.0.0.1")
                    .build()
                ProxyController.getInstance().setProxyOverride(proxyConfig, { it.run() }, {
                    android.util.Log.d("AABrowser", "WebView Proxy configurado en el puerto local $activeProxyPort")
                })
            }
        }

        CookieManager.getInstance().also {
            it.setAcceptCookie(true)
            it.setAcceptThirdPartyCookies(this, true)
        }

        setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (!isPlutoTvUrl(url)) return false
                e1 ?: return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                val threshold = 50
                val velocityThreshold = 50
                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > threshold && Math.abs(velocityX) > velocityThreshold) {
                        if (diffX > 0) {
                            dispatchDpadKey(KeyEvent.KEYCODE_DPAD_LEFT)
                        } else {
                            dispatchDpadKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                        }
                        return true
                    }
                } else {
                    if (Math.abs(diffY) > threshold && Math.abs(velocityY) > velocityThreshold) {
                        if (diffY > 0) {
                            dispatchDpadKey(KeyEvent.KEYCODE_DPAD_UP)
                        } else {
                            dispatchDpadKey(KeyEvent.KEYCODE_DPAD_DOWN)
                        }
                        return true
                    }
                }
                return false
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!isPlutoTvUrl(url)) return false
                dispatchDpadKey(KeyEvent.KEYCODE_DPAD_CENTER)
                return true
            }

            private fun dispatchDpadKey(keyCode: Int) {
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
        })

        setOnTouchListener { _, event ->
            if (isPlutoTvUrl(url)) {
                gestureDetector.onTouchEvent(event)
                true
            } else {
                false
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val url = uri.toString()
                if (isYouTubeTvUrl(url)) {
                    view.applyYouTubeTvUserAgent()
                } else if (isPlutoTvUrl(url)) {
                    view.applyPlutoTvConfig()
                } else {
                    if (view.settings.userAgentString == YOUTUBE_TV_USER_AGENT || view.settings.userAgentString == PLUTO_TV_USER_AGENT) {
                        view.restoreDefaultUserAgent()
                    }
                }
                if (handleCleartextIfNeeded(view, uri, callbacks, onPageStart = false)) return true
                return handleUri(view, uri)
            }

            private fun handleUri(view: WebView, uri: Uri?): Boolean {
                uri ?: return false
                val scheme = uri.scheme?.lowercase()
                if (scheme == null || scheme in setOf("http", "https", "about", "file", "data", "javascript")) {
                    return false
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val stringUrl = url ?: return
                if (isYouTubeTvUrl(stringUrl)) {
                    if (view.settings.userAgentString != YOUTUBE_TV_USER_AGENT) {
                        view.applyYouTubeTvUserAgent()
                    }
                } else if (isPlutoTvUrl(stringUrl)) {
                    if (view.settings.userAgentString != PLUTO_TV_USER_AGENT) {
                        view.applyPlutoTvConfig()
                    }
                } else {
                    if (view.settings.userAgentString == YOUTUBE_TV_USER_AGENT || view.settings.userAgentString == PLUTO_TV_USER_AGENT) {
                        view.restoreDefaultUserAgent()
                    }
                }
                val uri = Uri.parse(stringUrl)
                val scheme = uri.scheme?.lowercase()

                if (scheme == "http") {
                    val allowedOnce = getTag(R.id.webview_allow_once_uri_tag) as? String
                    if (allowedOnce == stringUrl) {
                        setTag(R.id.webview_allow_once_uri_tag, null)
                    } else if (handleCleartextIfNeeded(view, uri, callbacks, onPageStart = true)) {
                        return
                    }
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val adBlockEnabled = view.getTag(R.id.webview_ad_block_tag) as? Boolean ?: false
                if (adBlockEnabled) {
                    val response = com.kododake.aavideo.adblock.AdBlockEngine.shouldBlock(request.url.toString())
                    if (response != null) {
                        return response
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(SpeechRecognitionBridge.POLYFILL_JS, null)
                view.evaluateJavascript(MEDIA_LISTENER_JS, null)
                view.evaluateJavascript(TWO_FINGER_SWIPE_UP_JS, null)
                url?.let(callbacks.onUrlChange)
                if (isYouTubeTvUrl(url)) {
                    view.evaluateJavascript(YOUTUBE_TV_TOUCH_NAVIGATION_JS, null)
                }
                if (url != null) {
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        view.evaluateJavascript(com.kododake.aavideo.adblock.UnifiedAdBlocker.getDocumentStartJs(), null)
                    }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    val code = error.errorCode
                    val shouldShowErrorPage = when (code) {
                        WebViewClient.ERROR_HOST_LOOKUP,
                        WebViewClient.ERROR_CONNECT,
                        WebViewClient.ERROR_TIMEOUT,
                        WebViewClient.ERROR_UNKNOWN,
                        WebViewClient.ERROR_PROXY_AUTHENTICATION -> true
                        else -> false
                    }

                    if (shouldShowErrorPage) {
                        val failed = request.url?.toString().orEmpty()
                        val message = error.description?.toString().orEmpty()
                        val assetUrl = "file:///android_asset/error.html?failedUrl=${Uri.encode(failed)}&code=$code&message=${Uri.encode(message)}"
                        try {
                            view.loadUrl(assetUrl)
                        } catch (_: Exception) {
                            callbacks.onError(code, error.description?.toString())
                        }
                        return
                    }
                }
                callbacks.onError(error.errorCode, error.description?.toString())
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    val code = errorResponse.statusCode
                    if (code in 400..599 && code != 429) {
                        val failed = request.url?.toString().orEmpty()
                        val message = errorResponse.reasonPhrase.orEmpty()
                        val assetUrl = "file:///android_asset/error.html?failedUrl=${Uri.encode(failed)}&code=$code&message=${Uri.encode(message)}"
                        try {
                            view.loadUrl(assetUrl)
                        } catch (_: Exception) {
                            callbacks.onError(code, message)
                        }
                        return
                    }
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                val primary = try { error.primaryError } catch (_: Exception) { -1 }
                val url = error.url ?: ""
                val message = "SSL error: $primary"
                val assetUrl = "file:///android_asset/error.html?failedUrl=${Uri.encode(url)}&sslError=$primary&message=${Uri.encode(message)}"
                try {
                    view.loadUrl(assetUrl)
                    handler.cancel()
                    return
                } catch (_: Exception) {}

                handler.cancel()
                callbacks.onError(primary, message)
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                callbacks.onProgressChange(newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                callbacks.onTitleChange(title)
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                super.onReceivedIcon(view, icon)
                val pageUrl = view?.url?.takeIf { it.isNotBlank() } ?: return
                callbacks.onFaviconReceived(pageUrl, icon)
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null && callback != null) {
                    callbacks.onEnterFullscreen(view, callback)
                } else {
                    super.onShowCustomView(view, callback)
                }
            }

            override fun onHideCustomView() {
                callbacks.onExitFullscreen()
                super.onHideCustomView()
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return

                val allowed = setOf(
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE
                )

                val grantable = request.resources.filter { it in allowed }.toTypedArray()

                if (grantable.isEmpty()) {
                    request.deny()
                    return
                }

                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in grantable) {
                    callbacks.onPermissionRequest(request)
                } else {
                    this@with.post { request.grant(grantable) }
                }
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                val isPopupBlockerEnabled = com.kododake.aavideo.data.BrowserPreferences.isPopupBlockerEnabled(view?.context ?: return false)
                if (isPopupBlockerEnabled) {
                    result?.confirm()
                    return true
                }
                return super.onJsAlert(view, url, message, result)
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                val isPopupBlockerEnabled = com.kododake.aavideo.data.BrowserPreferences.isPopupBlockerEnabled(view?.context ?: return false)
                if (isPopupBlockerEnabled) {
                    result?.confirm()
                    return true
                }
                return super.onJsConfirm(view, url, message, result)
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: android.webkit.JsPromptResult?
            ): Boolean {
                val isPopupBlockerEnabled = com.kododake.aavideo.data.BrowserPreferences.isPopupBlockerEnabled(view?.context ?: return false)
                if (isPopupBlockerEnabled) {
                    result?.confirm(defaultValue ?: "")
                    return true
                }
                return super.onJsPrompt(view, url, message, defaultValue, result)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (view == null || resultMsg == null) return false
                val context = view.context
                val isPopupBlockerEnabled = com.kododake.aavideo.data.BrowserPreferences.isPopupBlockerEnabled(context)
                if (isPopupBlockerEnabled) {
                    return false
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val tempWebView = WebView(context)
                tempWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        view.post { view.loadUrl(url) }
                        return true
                    }
                    override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean {
                        view.post { view.loadUrl(url) }
                        return true
                    }
                }
                transport.webView = tempWebView
                resultMsg.sendToTarget()
                return true
            }
        }

        setDownloadListener(DownloadListener { url, _, _, _, _ ->
            val uri = url?.takeIf { it.isNotBlank() }?.toUri() ?: return@DownloadListener
            callbacks.onShowDownloadPrompt(uri)
        })
    }
}

private fun handleCleartextIfNeeded(view: WebView, uri: Uri?, callbacks: BrowserCallbacks, onPageStart: Boolean = false): Boolean {
    uri ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme != "http") return false

    val allowedOnce = view.getTag(R.id.webview_allow_once_uri_tag) as? String
    if (allowedOnce == uri.toString()) {
        view.setTag(R.id.webview_allow_once_uri_tag, null)
        return false
    }

    val host = uri.host?.lowercase()
    if (com.kododake.aavideo.data.BrowserPreferences.isHostAllowedCleartext(view.context, host)) return false
    if (onPageStart) view.stopLoading()
    val allowOnce = {
        view.setTag(R.id.webview_allow_once_uri_tag, uri.toString())
        view.post { view.loadUrl(uri.toString()) }
        kotlin.Unit
    }
    val allowHost = {
        view.context?.let { ctx ->
            val hostToStore = uri.host?.lowercase()
            if (hostToStore != null) com.kododake.aavideo.data.BrowserPreferences.addAllowedCleartextHost(ctx, hostToStore)
        }
        view.setTag(R.id.webview_allow_once_uri_tag, uri.toString())
        view.post { view.loadUrl(uri.toString()) }
        kotlin.Unit
    }
    val cancel = {
        if (onPageStart) view.stopLoading()
        kotlin.Unit
    }
    callbacks.onCleartextNavigationRequested(uri, allowOnce, allowHost, cancel)
    return true
}

const val YOUTUBE_TV_USER_AGENT = "Mozilla/5.0 (Linux; Tizen 2.3) AppleWebKit/538.1 (KHTML, like Gecko)Version/2.3 TV Safari/538.1"

const val YOUTUBE_TV_TOUCH_NAVIGATION_JS = """
(function() {
    if (window.hasYouTubeTvTouchNav) return;
    window.hasYouTubeTvTouchNav = true;
    
    try {
        var style = document.createElement('style');
        style.innerHTML = '* { touch-action: manipulation !important; }';
        document.head.appendChild(style);
    } catch (e) {}
    
    let startX = 0;
    let startY = 0;
    let isMoving = false;

    window.addEventListener('touchstart', function(e) {
        if (e.touches.length === 1) {
            startX = e.touches[0].clientX;
            startY = e.touches[0].clientY;
            isMoving = true;
        }
    }, { passive: true });

    window.addEventListener('touchmove', function(e) {
        if (isMoving) {
            if (e.cancelable) e.preventDefault();
        }
    }, { passive: false });

    window.addEventListener('touchend', function(e) {
        if (!isMoving) return;
        isMoving = false;
        
        const touch = e.changedTouches ? e.changedTouches[0] : null;
        if (!touch) return;

        const diffX = touch.clientX - startX;
        const diffY = touch.clientY - startY;

        const threshold = 30; // Threshold in pixels
        const absDiffX = Math.abs(diffX);
        const absDiffY = Math.abs(diffY);

        let keyToSend = null;

        if (absDiffX > threshold || absDiffY > threshold) {
            if (absDiffX > absDiffY) {
                if (diffX > 0) {
                    keyToSend = 'ArrowLeft';
                } else {
                    keyToSend = 'ArrowRight';
                }
            } else {
                if (diffY > 0) {
                    keyToSend = 'ArrowUp';
                } else {
                    keyToSend = 'ArrowDown';
                }
            }
        }

        if (keyToSend) {
            sendKey(keyToSend);
        }
    }, { passive: false });

    function sendKey(code) {
        const target = document.activeElement || document.body || document;
        
        let keyCode = 0;
        if (code === 'ArrowLeft') keyCode = 37;
        else if (code === 'ArrowUp') keyCode = 38;
        else if (code === 'ArrowRight') keyCode = 39;
        else if (code === 'ArrowDown') keyCode = 40;
        else if (code === 'Enter') keyCode = 13;
        else if (code === 'Escape') keyCode = 27;
        else if (code === 'Backspace') keyCode = 8;

        const createEvent = (type) => new KeyboardEvent(type, {
            key: code,
            code: code,
            keyCode: keyCode,
            which: keyCode,
            bubbles: true,
            cancelable: true,
            view: window
        });

        target.dispatchEvent(createEvent('keydown'));
        target.dispatchEvent(createEvent('keyup'));
    }
})();
"""

fun isYouTubeTvUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    val path = uri.path?.lowercase() ?: return false
    return (host == "youtube.com" || host == "www.youtube.com" || host == "m.youtube.com" || host == "tv.youtube.com") && (path == "/tv" || path.startsWith("/tv/") || host == "tv.youtube.com")
}

fun isPlutoTvUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return host == "pluto.tv" || host.endsWith(".pluto.tv")
}

const val PLUTO_TV_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Chromecast) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

fun WebView.applyPlutoTvConfig() {
    settings.userAgentString = PLUTO_TV_USER_AGENT
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
}

fun WebView.applyYouTubeTvUserAgent() {
    settings.userAgentString = YOUTUBE_TV_USER_AGENT
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    setInitialScale(0)
}

fun WebView.restoreDefaultUserAgent() {
    val profile = com.kododake.aavideo.data.BrowserPreferences.getUserAgentProfile(context)
    val desktop = com.kododake.aavideo.data.BrowserPreferences.shouldUseDesktopMode(context)
    applyUserAgent(profile, desktop)
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    val scale = context.resources.displayMetrics.density * 100
    setInitialScale(scale.toInt())
}

fun WebView.updateDesktopMode(enable: Boolean, profile: UserAgentProfile) {
    if (isYouTubeTvUrl(url)) {
        applyYouTubeTvUserAgent()
    } else if (isPlutoTvUrl(url)) {
        applyPlutoTvConfig()
    } else {
        applyUserAgent(profile, enable)
    }
    reload()
}

fun WebView.updateUserAgentProfile(profile: UserAgentProfile, desktop: Boolean) {
    if (isYouTubeTvUrl(url)) {
        applyYouTubeTvUserAgent()
    } else if (isPlutoTvUrl(url)) {
        applyPlutoTvConfig()
    } else {
        applyUserAgent(profile, desktop)
    }
    reload()
}

fun WebView.updatePageDarkening(enabled: Boolean) {
    applyPageDarkening(enabled)
    reload()
}

fun WebView.updateAdBlock(enabled: Boolean) {
    setTag(R.id.webview_ad_block_tag, enabled)
    reload()
}

fun WebView.releaseCompletely() {
    stopLoading()
    webChromeClient = WebChromeClient()
    webViewClient = WebViewClient()
    destroy()
}

fun WebView.applyUserAgent(profile: UserAgentProfile, desktop: Boolean) {
    setTag(R.id.webview_user_agent_profile_tag, profile.storageKey)
    settings.userAgentString = buildUserAgent(profile, desktop)
    settings.useWideViewPort = desktop
    settings.loadWithOverviewMode = desktop
}

private fun WebView.applyPageDarkening(enabled: Boolean) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, enabled)
    }
}

fun buildUserAgent(profile: UserAgentProfile, desktop: Boolean): String {
    return when (profile) {
        UserAgentProfile.ANDROID_CHROME -> if (desktop) WINDOWS_CHROME_UA else MOBILE_CHROME_UA
        UserAgentProfile.SAFARI -> if (desktop) SAFARI_MAC_UA else SAFARI_IOS_UA
    }
}

private const val CHROME_VERSION = "146.0.0.0"
private const val MOBILE_CHROME_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${CHROME_VERSION} Mobile Safari/537.36"
private const val WINDOWS_CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${CHROME_VERSION} Safari/537.36"
private const val SAFARI_MAC_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
private const val SAFARI_IOS_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

const val MEDIA_LISTENER_JS = """
(function() {
    if (window.hasAndroidMediaListeners) return;
    window.hasAndroidMediaListeners = true;
    
    var lastVideoId = null;
    
    function reportState() {
        var mediaElements = document.querySelectorAll('video, audio');
        var activeElement = null;
        var isPlaying = false;
        
        for (var i = 0; i < mediaElements.length; i++) {
            var el = mediaElements[i];
            if (!el.paused && !el.ended && el.readyState > 0) {
                isPlaying = true;
                activeElement = el;
                break;
            }
        }
        
        if (!activeElement && mediaElements.length > 0) {
            activeElement = mediaElements[0];
        }
        
        var videoId = "";
        try {
            var player = document.getElementById("movie_player") || document.querySelector(".html5-video-player");
            if (player && typeof player.getVideoData === 'function') {
                var data = player.getVideoData();
                if (data && data.video_id) {
                    videoId = data.video_id;
                }
            }
        } catch (e) {}
        if (!videoId) {
            try {
                var match = window.location.href.match(/[?&]v=([^&#]+)/);
                if (match) videoId = match[1];
            } catch (e) {}
        }
        
        if (videoId && videoId !== lastVideoId) {
            lastVideoId = videoId;
            try {
                var captionWindow = document.querySelector('.ytp-caption-window-container, .caption-window');
                if (captionWindow && captionWindow.children.length > 0) {
                    var ccBtn = document.querySelector('.ytp-subtitles-button');
                    if (ccBtn) {
                        ccBtn.click();
                    } else {
                        var target = document.activeElement || document.body || document;
                        var init = { key: 'c', code: 'KeyC', keyCode: 67, which: 67, bubbles: true, cancelable: true, view: window };
                        target.dispatchEvent(new KeyboardEvent('keydown', init));
                        target.dispatchEvent(new KeyboardEvent('keyup', init));
                    }
                }
            } catch (e) {}
            try {
                var videos = document.querySelectorAll('video');
                for (var i = 0; i < videos.length; i++) {
                    var v = videos[i];
                    if (v.textTracks) {
                        for (var j = 0; j < v.textTracks.length; j++) {
                            v.textTracks[j].mode = 'disabled';
                        }
                    }
                }
            } catch (e) {}
        }
        
        var title = "";
        var artist = "";
        var artworkUrl = "";
        var positionMs = 0;
        var durationMs = 0;
        
        if (activeElement) {
            try {
                var isAd = activeElement.playbackRate === 16.0 || 
                           document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay, .ytp-ad-preview-container, .ytp-ad-preview-container-modern, .ytp-ad-image-overlay, .ytp-ad-text, .ytp-ad-preview-text, .ytp-tv-ad-preview-text');
                
                if (!isAd && !window.location.hostname.includes("youtube.com") && !window.location.hostname.includes("youtu.be")) {
                    var adSelectors = [
                        '.videoAdUi', '.ima-ad-container', '.ima-controls-container',
                        '.vjs-ad-playing', '.vjs-ad-loading', '.vjs-ad-container',
                        '.jw-flag-ads', '.jw-ad-container', '.jw-ad-overlay',
                        '.fluid_ad_container', '.fluid_video_wrapper_ad',
                        '.ad-break', '.ad-indicator', '.ad-countdown', '.ad-overlay',
                        '.pluto-ad-break-indicator', '.ad-break-indicator', '.ad-countdown-timer',
                        '.progress-bar-ad', '[class^="ad-break"]', '[class^="ad-indicator"]',
                        '[class^="ad-countdown"]', '[class^="ad-overlay"]',
                        '[class*=" ad-break"]', '[class*=" ad-indicator"]',
                        '[class*=" ad-countdown"]', '[class*=" ad-overlay"]',
                        '.video-ads', '.ad-container', '.player-ad-overlay',
                        '[class*="vast-ad" i]', '[class*="ima-ad" i]', '[class*="ad-player" i]',
                        '[class*="ad-showing" i]', '[class*="ad-break" i]', '[class*="video-ad" i]',
                        '[class*="videoAdUi" i]', '[class*="skip-button" i]', '[class*="skip-btn" i]', '[class*="skip-ad" i]'
                    ];
                    var container = activeElement.parentElement || document.body;
                    for (var s = 0; s < adSelectors.length; s++) {
                        try {
                            var el = container.querySelector(adSelectors[s]);
                            if (el && (el.offsetWidth > 0 || el.offsetHeight > 0)) {
                                isAd = true;
                                break;
                            }
                        } catch(e) {}
                    }
                    if (!isAd) {
                        var elList = container.querySelectorAll('div, span, p, a, button');
                        for (var i = 0; i < elList.length; i++) {
                            var elItem = elList[i];
                            if (elItem.offsetWidth === 0 && elItem.offsetHeight === 0) continue;
                            var text = (elItem.textContent || elItem.innerText || "").trim().toLowerCase();
                            if (
                                /saltar\s+anuncio/i.test(text) ||
                                /omitir\s+anuncio/i.test(text) ||
                                /este\s+anuncio\s+terminar/i.test(text) ||
                                /anuncio\s+terminará\s+en/i.test(text) ||
                                /video\s+se\s+reanudará/i.test(text) ||
                                /anuncio\s+\d+\s+de\s+\d+/i.test(text) ||
                                /publicidad/i.test(text) ||
                                /anuncio/i.test(text) ||
                                /skip\s+ad/i.test(text) ||
                                /ad\s+break/i.test(text) ||
                                /sponsored/i.test(text) ||
                                /patrocinado/i.test(text)
                            ) {
                                isAd = true;
                                break;
                            }
                        }
                    }
                }

                if (!isAd) {
                    if (activeElement.volume < 1.0) {
                        activeElement.volume = 1.0;
                    }
                    if (activeElement.muted) {
                        activeElement.muted = false;
                    }
                } else {
                    activeElement.muted = true;
                    activeElement.volume = 0.0;
                }
            } catch (e) {}
            
            positionMs = Math.floor(activeElement.currentTime * 1000);
            var dur = activeElement.duration;
            if (!isNaN(dur) && isFinite(dur)) {
                durationMs = Math.floor(dur * 1000);
            }
        }
        
        function isValidTitle(t) {
            if (!t) return false;
            var clean = t.toLowerCase().trim();
            if (clean === "" || 
                clean === "youtube" || 
                clean === "inicio" || 
                clean === "home" || 
                clean === "buscar" || 
                clean === "search") {
                return false;
            }
            if (clean.indexOf("youtube") !== -1 && (clean.indexOf("tv") !== -1 || clean.indexOf("television") !== -1 || clean.indexOf("televisión") !== -1)) {
                return false;
            }
            return true;
        }
        
        // 1. Try MediaSession API first (most standard and updated by web page developers)
        if (navigator.mediaSession && navigator.mediaSession.metadata) {
            var msTitle = navigator.mediaSession.metadata.title;
            if (isValidTitle(msTitle)) {
                title = msTitle;
            }
            var msArtist = navigator.mediaSession.metadata.artist;
            if (msArtist) {
                artist = msArtist;
            }
            if (navigator.mediaSession.metadata.artwork && navigator.mediaSession.metadata.artwork.length > 0) {
                var artwork = navigator.mediaSession.metadata.artwork;
                artworkUrl = artwork[artwork.length - 1].src || "";
            }
        }
        
        // 2. Try YouTube Player API next (covers YouTube, YouTube TV, and YouTube embeds)
        if ((!title || !artworkUrl) && (window.location.hostname.includes("youtube.com") || window.location.hostname.includes("youtu.be"))) {
            // 2a. Try getVideoData from movie_player
            try {
                var players = document.querySelectorAll('#movie_player, .html5-video-player');
                for (var pi = 0; pi < players.length; pi++) {
                    var player = players[pi];
                    if (player && typeof player.getVideoData === 'function') {
                        var data = player.getVideoData();
                        if (data) {
                            if (!title && isValidTitle(data.title)) {
                                title = data.title;
                            }
                            if (!artist && data.author) {
                                artist = data.author;
                            }
                            if (!artworkUrl && data.video_id) {
                                artworkUrl = "https://img.youtube.com/vi/" + data.video_id + "/hqdefault.jpg";
                            }
                        }
                    }
                }
            } catch (e) {}
            
            // 2b. Try ytInitialPlayerResponse global (set by YouTube on page load)
            if (!title) {
                try {
                    if (window.ytInitialPlayerResponse && window.ytInitialPlayerResponse.videoDetails) {
                        var vd = window.ytInitialPlayerResponse.videoDetails;
                        if (isValidTitle(vd.title)) {
                            title = vd.title;
                        }
                        if (!artist && vd.author) {
                            artist = vd.author;
                        }
                        if (!artworkUrl && vd.videoId) {
                            artworkUrl = "https://img.youtube.com/vi/" + vd.videoId + "/hqdefault.jpg";
                        }
                    }
                } catch (e) {}
            }
            
            // 2c. Try ytPlayerConfig or ytcfg globals
            if (!title) {
                try {
                    var cfg = window.ytPlayerConfig || window.ytcfg;
                    if (cfg) {
                        var args = cfg.args || (typeof cfg.get === 'function' ? cfg.get('PLAYER_VARS') : null);
                        if (args && isValidTitle(args.title)) {
                            title = args.title;
                        }
                    }
                } catch (e) {}
            }
            
            // 2d. Try .ytp-title-text inside player (YouTube standard & TV)
            if (!title) {
                try {
                    var ytpTitle = document.querySelector('.ytp-title-text .ytp-title-link, .ytp-title-text');
                    if (ytpTitle && isValidTitle(ytpTitle.innerText)) {
                        title = ytpTitle.innerText.trim();
                    }
                } catch (e) {}
            }
            
            // 2e. Ensure video_id is extracted for artwork even if title fails
            if (!artworkUrl) {
                try {
                    var vid = "";
                    var vMatch = window.location.href.match(/[?&]v=([^&#]+)/);
                    if (vMatch) vid = vMatch[1];
                    if (!vid) {
                        var pathMatch = window.location.pathname.match(/\/(?:watch|embed|shorts|v)\/([^/?&#]+)/);
                        if (pathMatch) vid = pathMatch[1];
                    }
                    if (vid) {
                        artworkUrl = "https://img.youtube.com/vi/" + vid + "/hqdefault.jpg";
                    }
                } catch (e) {}
            }
        }
        
        // 3. Try YouTube specific selectors if still empty
        if ((!title || !artworkUrl) && window.location.hostname.includes("youtube.com")) {
            if (!title) {
                var selectors = [
                    '.video-title',
                    '.metadata-title',
                    '.metadata-title-text',
                    '.player-metadata-title',
                    '.player-metadata-title-text',
                    '.info-title',
                    '.ytp-title-link',
                    '.slim-video-metadata-title',
                    '.ytm-slim-video-metadata-title',
                    'h1.ytd-watch-metadata'
                ];
                for (var i = 0; i < selectors.length; i++) {
                    var el = document.querySelector(selectors[i]);
                    if (el && el.innerText && isValidTitle(el.innerText)) {
                        title = el.innerText.trim();
                        break;
                    }
                }
            }
            if (!artist) {
                var ytChannelEl = document.querySelector('#owner-sub-count, #upload-info #owner-name, .ytp-title-expanded-title, .slim-owner-profile-name');
                if (ytChannelEl) artist = ytChannelEl.innerText;
            }
            if (!artworkUrl) {
                var videoId = "";
                var match = window.location.search.match(/[?&]v=([^&#]+)/);
                if (match) {
                    videoId = match[1];
                } else {
                    var pathParts = window.location.pathname.split('/');
                    if (pathParts.includes("embed")) {
                        videoId = pathParts[pathParts.indexOf("embed") + 1];
                    }
                }
                if (videoId) {
                    artworkUrl = "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
                }
            }
        }
        
        // 4. Scan DOM for title/metadata classes as fallback for TV and other platforms
        if (!title) {
            try {
                var elList = document.querySelectorAll('div, span, h1, h2, h3, a, p');
                for (var i = 0; i < elList.length; i++) {
                    var el = elList[i];
                    var className = el.className;
                    if (typeof className === 'string' && (className.indexOf('title') !== -1 || className.indexOf('metadata') !== -1)) {
                        var text = el.innerText || el.textContent;
                        if (text) {
                            var trimmed = text.trim();
                            if (trimmed.length > 3 && trimmed.length < 120 && trimmed.indexOf('\n') === -1 && isValidTitle(trimmed)) {
                                title = trimmed;
                                break;
                            }
                        }
                    }
                }
            } catch (e) {}
        }
        
        // 5. Video Element Poster
        if (!artworkUrl && activeElement && activeElement.tagName.toLowerCase() === 'video') {
            var poster = activeElement.getAttribute('poster');
            if (poster) {
                try {
                    artworkUrl = new URL(poster, window.location.href).href;
                } catch (e) {
                    artworkUrl = poster;
                }
            }
        }
        if (!artworkUrl) {
            try {
                var videos = document.querySelectorAll('video');
                for (var vi = 0; vi < videos.length; vi++) {
                    var p = videos[vi].getAttribute('poster');
                    if (p) {
                        artworkUrl = new URL(p, window.location.href).href;
                        break;
                    }
                }
            } catch (e) {}
        }
        
        // 5b. Generic site meta-tags for artwork
        if (!artworkUrl) {
            var ogImg = document.querySelector('meta[property="og:image"]');
            if (ogImg) {
                artworkUrl = ogImg.content || ogImg.getAttribute('content') || "";
            }
        }
        if (!artworkUrl) {
            var twitterImg = document.querySelector('meta[name="twitter:image"]');
            if (twitterImg) {
                artworkUrl = twitterImg.content || twitterImg.getAttribute('content') || "";
            }
        }
        
        // 6. Last resort document fallbacks
        if (!title) {
            var docTitle = document.title;
            if (docTitle && docTitle.endsWith(" - YouTube")) {
                docTitle = docTitle.substring(0, docTitle.length - 10);
            }
            if (isValidTitle(docTitle)) {
                title = docTitle;
            }
        }
        if (!artist) {
            artist = window.location.hostname;
        }
        
        title = (title || "").trim();
        artist = (artist || "").trim();
        artworkUrl = (artworkUrl || "").trim();
        
        if (window.AndroidMediaBridge) {
            window.AndroidMediaBridge.onPlaybackStateChanged(isPlaying);
            if (typeof window.AndroidMediaBridge.onMediaMetadataChanged === 'function') {
                window.AndroidMediaBridge.onMediaMetadataChanged(
                    title,
                    artist,
                    artworkUrl,
                    String(positionMs),
                    String(durationMs),
                    isPlaying
                );
            }
        }
    }

    function setupListeners(element) {
        if (element.dataset.hasMediaListeners) return;
        element.dataset.hasMediaListeners = "true";
        element.addEventListener('play', reportState);
        element.addEventListener('playing', reportState);
        element.addEventListener('pause', reportState);
        element.addEventListener('ended', reportState);
        element.addEventListener('volumechange', reportState);
        element.addEventListener('seeked', reportState);
    }

    var elements = document.querySelectorAll('video, audio');
    for (var i = 0; i < elements.length; i++) {
        setupListeners(elements[i]);
    }

    var observer = new MutationObserver(function(mutations) {
        mutations.forEach(function(mutation) {
            for (var i = 0; i < mutation.addedNodes.length; i++) {
                var node = mutation.addedNodes[i];
                if (node.nodeType === Node.ELEMENT_NODE) {
                    if (node.matches('video, audio')) {
                        setupListeners(node);
                    }
                    var childMedia = node.querySelectorAll('video, audio');
                    for (var j = 0; j < childMedia.length; j++) {
                        setupListeners(childMedia[j]);
                    }
                }
            }
        });
    });
    observer.observe(document.body || document.documentElement, { childList: true, subtree: true });

    setInterval(reportState, 1500);
    reportState();
})();
"""

const val TWO_FINGER_SWIPE_UP_JS = """
(function() {
    if (window.hasTwoFingerSwipeUp) return;
    window.hasTwoFingerSwipeUp = true;
    
    let startY = 0;
    let startX = 0;
    let lastY = 0;
    let lastX = 0;
    let isTwoFinger = false;
    
    window.addEventListener('touchstart', function(e) {
        if (e.touches.length === 2) {
            startY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
            startX = (e.touches[0].clientX + e.touches[1].clientX) / 2;
            lastY = startY;
            lastX = startX;
            isTwoFinger = true;
        } else {
            isTwoFinger = false;
        }
    }, { capture: true, passive: true });
    
    window.addEventListener('touchmove', function(e) {
        if (e.touches.length === 2) {
            lastY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
            lastX = (e.touches[0].clientX + e.touches[1].clientX) / 2;
            isTwoFinger = true;
        }
    }, { capture: true, passive: true });
    
    window.addEventListener('touchend', function(e) {
        if (isTwoFinger) {
            isTwoFinger = false;
            const diffY = startY - lastY;
            const diffX = Math.abs(startX - lastX);
            if (diffY > 80 && diffX < 80) {
                if (window.Android && typeof window.Android.triggerBackGesture === 'function') {
                    window.Android.triggerBackGesture();
                }
            }
        }
    }, { capture: true, passive: true });
})();
"""

class AdBlockBridge(private val context: android.content.Context) {
    @android.webkit.JavascriptInterface
    fun isAdBlockEnabled(): Boolean {
        return com.kododake.aavideo.data.BrowserPreferences.isAdBlockEnabled(context)
    }
    @android.webkit.JavascriptInterface
    fun isPopupBlockerEnabled(): Boolean {
        return com.kododake.aavideo.data.BrowserPreferences.isPopupBlockerEnabled(context)
    }
}
