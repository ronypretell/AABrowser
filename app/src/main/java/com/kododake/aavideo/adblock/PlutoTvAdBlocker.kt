package com.kododake.aavideo.adblock

object PlutoTvAdBlocker {
    fun getInjectionJs(url: String): String? {
        val host = try {
            android.net.Uri.parse(url).host?.lowercase() ?: ""
        } catch (e: Exception) {
            ""
        }
        if (!host.contains("pluto.tv")) return null

        return """
            (function() {
                let originalSpeed = 1.0;
                let originalVolume = 1.0;
                let wasAdShowing = false;

                function isPlutoTvAdShowing() {
                    const video = document.querySelector('video');
                    if (!video) return false;

                    const adSelectors = [
                        '.ad-break',
                        '.ad-indicator',
                        '.ad-countdown',
                        '.ad-overlay',
                        '.pluto-ad-break-indicator',
                        '.ad-break-indicator',
                        '.ad-countdown-timer',
                        '.progress-bar-ad',
                        '[class^="ad-break"]',
                        '[class^="ad-indicator"]',
                        '[class^="ad-countdown"]',
                        '[class^="ad-overlay"]',
                        '[class*=" ad-break"]',
                        '[class*=" ad-indicator"]',
                        '[class*=" ad-countdown"]',
                        '[class*=" ad-overlay"]'
                    ];
                    
                    const container = video.parentElement || document.body;
                    for (const selector of adSelectors) {
                        const el = container.querySelector(selector);
                        if (el && (el.offsetWidth > 0 || el.offsetHeight > 0)) {
                            return true;
                        }
                    }

                    const elements = container.querySelectorAll('div, span, p');
                    for (let i = 0; i < elements.length; i++) {
                        const el = elements[i];
                        if (el.offsetWidth === 0 && el.offsetHeight === 0) continue;
                        
                        const text = (el.textContent || el.innerText || "").trim().toLowerCase();
                        if (/^(ad\s+\d+\s+of\s+\d+|anuncio\s+\d+\s+de\s+\d+|publicidad\s+\d+\s+de\s+\d+|ad\s+break|publicidad|anuncio|ad)$/i.test(text) || 
                            text.indexOf('ad 1 of') !== -1 ||
                            text.indexOf('ad 2 of') !== -1 ||
                            text.indexOf('ad 3 of') !== -1 ||
                            text.indexOf('ad 4 of') !== -1 ||
                            text.indexOf('ad 5 of') !== -1 ||
                            (text.startsWith('anuncio de ') && text.endsWith('s')) ||
                            (text.startsWith('publicidad de ') && text.endsWith('s'))) {
                            return true;
                        }
                    }
                    return false;
                }

                function skipAds() {
                    const video = document.querySelector('video');
                    if (!video) return;

                    const adShowing = isPlutoTvAdShowing();
                    const isLive = window.location.pathname.includes('/live-tv') || !isFinite(video.duration) || video.duration === Infinity;
                    
                    if (adShowing) {
                        if (!wasAdShowing) {
                            originalVolume = video.volume > 0 ? video.volume : (originalVolume || 1.0);
                            originalSpeed = video.playbackRate === 16 ? 1.0 : video.playbackRate;
                            wasAdShowing = true;
                            console.log('Pluto TV Ad detected: storing original state (vol=' + originalVolume + ', speed=' + originalSpeed + ', isLive=' + isLive + ')');
                        }
                        video.muted = true;
                        video.volume = 0.0;
                        if (!isLive) {
                            video.playbackRate = 16.0;
                        } else {
                            video.playbackRate = 1.0;
                        }
                    } else {
                        if (wasAdShowing || video.playbackRate === 16.0) {
                            video.playbackRate = isLive ? 1.0 : (originalSpeed || 1.0);
                            video.muted = false;
                            video.volume = originalVolume || 1.0;
                            wasAdShowing = false;
                            console.log('Pluto TV Ad finished: restoring state (vol=' + originalVolume + ', speed=' + originalSpeed + ')');
                        }
                    }
                }

                skipAds();
                
                const observer = new MutationObserver(skipAds);
                observer.observe(document.body, {
                    childList: true,
                    subtree: true
                });
                
                setInterval(skipAds, 400);
            })();
        """.trimIndent()
    }
}
