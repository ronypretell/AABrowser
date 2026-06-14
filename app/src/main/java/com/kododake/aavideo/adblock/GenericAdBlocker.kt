package com.kododake.aavideo.adblock

object GenericAdBlocker {
    fun getInjectionJs(url: String): String? {
        val host = try {
            android.net.Uri.parse(url).host?.lowercase() ?: ""
        } catch (e: Exception) {
            ""
        }
        // Don't inject generic blocker if the site is YouTube or Pluto TV (which have dedicated blockers)
        if (host.contains("youtube.com") || host.contains("youtu.be") || host.contains("pluto.tv")) return null

        return """
            (function() {
                let originalSpeed = 1.0;
                let originalVolume = 1.0;
                let wasAdShowing = false;

                function isGenericAdShowing(video) {
                    if (!video) return false;

                    const adSelectors = [
                        '.videoAdUi',
                        '.ima-ad-container',
                        '.ima-controls-container',
                        '.vjs-ad-playing',
                        '.vjs-ad-loading',
                        '.vjs-ad-container',
                        '.jw-flag-ads',
                        '.jw-ad-container',
                        '.jw-ad-overlay',
                        '.fluid_ad_container',
                        '.fluid_video_wrapper_ad',
                        '.video-ads',
                        '.ad-container',
                        '.ad-showing',
                        '.ad-interrupting',
                        '.ytp-ad-player-overlay',
                        '.player-ad-overlay',
                        '[class*="vast-ad" i]',
                        '[class*="ima-ad" i]',
                        '[class*="ad-overlay" i]',
                        '[class*="ad-player" i]',
                        '[class*="ad-showing" i]',
                        '[class*="ad-break" i]',
                        '[class*="video-ad" i]',
                        '[class*="videoAdUi" i]',
                        '[id*="ad-container" i]',
                        '.ad-break',
                        '.ad-indicator',
                        '.ad-countdown',
                        '.pluto-ad-break-indicator'
                    ];
                    
                    for (const selector of adSelectors) {
                        try {
                            const el = document.querySelector(selector);
                            if (el && (el.offsetWidth > 0 || el.offsetHeight > 0)) {
                                return true;
                            }
                        } catch (e) {}
                    }

                    const skipBtn = findSkipButton();
                    if (skipBtn) {
                        return true;
                    }

                    const container = video.parentElement || document.body;
                    const elements = container.querySelectorAll('div, span, p, a, button');
                    for (let i = 0; i < elements.length; i++) {
                        const el = elements[i];
                        if (el.offsetWidth === 0 && el.offsetHeight === 0) continue;
                        const text = (el.textContent || el.innerText || "").trim().toLowerCase();
                        
                        // Expresión regular robusta para detectar anuncios de video y textos de cuenta regresiva en ES / EN
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
                            return true;
                        }
                    }
                    return false;
                }

                function findSkipButton() {
                    const skipSelectors = [
                        '.videoAdUiSkipButton',
                        '.ima-skip-button',
                        '.vast-skip-button',
                        '.skip-button',
                        '[class*="skip-button" i]',
                        '[class*="skip-btn" i]',
                        '[class*="skipad" i]',
                        '[class*="skip-ad" i]',
                        '[id*="skip-button" i]',
                        '[id*="skip-ad" i]',
                        '.ytp-ad-skip-button',
                        '.ytp-ad-skip-button-modern',
                        '.ytp-ad-skip-button-tv',
                        '.ytp-tv-ad-skip-button',
                        '.ytp-ad-skip-button-text'
                    ];
                    for (const selector of skipSelectors) {
                        try {
                            const btn = document.querySelector(selector);
                            if (btn && (btn.offsetWidth > 0 || btn.offsetHeight > 0)) {
                                return btn;
                            }
                        } catch (e) {}
                    }

                    const clickable = document.querySelectorAll('button, div, span, a');
                    for (let i = 0; i < clickable.length; i++) {
                        const el = clickable[i];
                        if (el.offsetWidth === 0 && el.offsetHeight === 0) continue;
                        const text = (el.textContent || el.innerText || "").trim().toLowerCase();
                        if (
                            text.indexOf('skip ad') !== -1 ||
                            text.indexOf('saltar anuncio') !== -1 ||
                            text.indexOf('omitir anuncio') !== -1 ||
                            text.indexOf('omitir') !== -1 ||
                            text.indexOf('saltar') !== -1 ||
                            text.indexOf('skip') !== -1
                        ) {
                            if (text.length < 30 && !el.querySelector('video') && !el.matches('video')) {
                                return el;
                            }
                        }
                    }
                    return null;
                }

                function runAdBlocker() {
                    const video = document.querySelector('video');
                    if (!video) return;

                    const adShowing = isGenericAdShowing(video);
                    const isLive = !isFinite(video.duration) || video.duration === Infinity;

                    if (adShowing) {
                        if (!wasAdShowing) {
                            originalVolume = video.volume > 0 ? video.volume : (originalVolume || 1.0);
                            originalSpeed = video.playbackRate === 16 ? 1.0 : video.playbackRate;
                            wasAdShowing = true;
                            console.log('Generic Ad detected! Storing original state: vol=' + originalVolume + ', speed=' + originalSpeed + ', isLive=' + isLive);
                        }
                        video.muted = true;
                        video.volume = 0.0;
                        if (!isLive) {
                            video.playbackRate = 16.0;
                        } else {
                            video.playbackRate = 1.0;
                        }

                        const skipBtn = findSkipButton();
                        if (skipBtn) {
                            try {
                                skipBtn.click();
                                console.log('Generic ad skip button clicked!');
                            } catch(e) {}
                        }
                    } else {
                        if (wasAdShowing || video.playbackRate === 16.0) {
                            video.playbackRate = isLive ? 1.0 : (originalSpeed || 1.0);
                            video.muted = false;
                            video.volume = originalVolume || 1.0;
                            wasAdShowing = false;
                            console.log('Generic Ad finished! Restoring state: vol=' + originalVolume + ', speed=' + originalSpeed);
                        }
                    }
                }

                runAdBlocker();
                const observer = new MutationObserver(runAdBlocker);
                observer.observe(document.body, { childList: true, subtree: true });
                setInterval(runAdBlocker, 400);
            })();
        """.trimIndent()
    }
}
