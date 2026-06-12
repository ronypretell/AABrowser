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
import com.kododake.aavideo.R
import com.kododake.aavideo.model.UserAgentProfile

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
            javaScriptCanOpenWindowsAutomatically = true

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
        val scale = context.resources.displayMetrics.density * 100
        setInitialScale(scale.toInt())

        CookieManager.getInstance().also {
            it.setAcceptCookie(true)
            it.setAcceptThirdPartyCookies(this, true)
        }

        setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val url = uri.toString()
                if (isYouTubeTvUrl(url)) {
                    view.applyYouTubeTvUserAgent()
                } else {
                    if (view.settings.userAgentString == YOUTUBE_TV_USER_AGENT) {
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
                } else {
                    if (view.settings.userAgentString == YOUTUBE_TV_USER_AGENT) {
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
                val adBlockEnabled = view.getTag(R.id.webview_ad_block_tag) as? Boolean ?: false
                if (adBlockEnabled && url != null) {
                    com.kododake.aavideo.adblock.YouTubeAdBlocker.getInjectionJs(url)?.let { js ->
                        view.evaluateJavascript(js, null)
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

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                return false
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
    }, { passive: true });

    function sendKey(code) {
        const target = document.activeElement || document.body || document;
        
        let keyCode = 0;
        if (code === 'ArrowLeft') keyCode = 37;
        else if (code === 'ArrowUp') keyCode = 38;
        else if (code === 'ArrowRight') keyCode = 39;
        else if (code === 'ArrowDown') keyCode = 40;
        else if (code === 'Enter') keyCode = 13;

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

fun WebView.applyYouTubeTvUserAgent() {
    settings.userAgentString = YOUTUBE_TV_USER_AGENT
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    setInitialScale(0)
}

fun WebView.restoreDefaultUserAgent() {
    val profile = com.kododake.aavideo.data.BrowserPreferences.getUserAgentProfile(context)
    val desktop = com.kododake.aavideo.data.BrowserPreferences.shouldUseDesktopMode(context)
    applyUserAgent(profile, desktop)
    val scale = context.resources.displayMetrics.density * 100
    setInitialScale(scale.toInt())
}

fun WebView.updateDesktopMode(enable: Boolean, profile: UserAgentProfile) {
    if (isYouTubeTvUrl(url)) {
        applyYouTubeTvUserAgent()
    } else {
        applyUserAgent(profile, enable)
    }
    reload()
}

fun WebView.updateUserAgentProfile(profile: UserAgentProfile, desktop: Boolean) {
    if (isYouTubeTvUrl(url)) {
        applyYouTubeTvUserAgent()
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
    
    function reportState() {
        var isPlaying = false;
        var mediaElements = document.querySelectorAll('video, audio');
        for (var i = 0; i < mediaElements.length; i++) {
            if (!mediaElements[i].paused && !mediaElements[i].ended && mediaElements[i].readyState > 2) {
                isPlaying = true;
                break;
            }
        }
        if (window.AndroidMediaBridge) {
            window.AndroidMediaBridge.onPlaybackStateChanged(isPlaying);
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
