package com.kododake.aavideo.adblock

object UnifiedAdBlocker {
    fun getDocumentStartJs(): String {
        return """
            (function() {
                if (window.hasUnifiedAdBlocker) return;
                window.hasUnifiedAdBlocker = true;

                function isAdBlockEnabled() {
                    return typeof AdBlockBridge !== 'undefined' && AdBlockBridge.isAdBlockEnabled();
                }

                function isPopupBlockerEnabled() {
                    return typeof AdBlockBridge !== 'undefined' && AdBlockBridge.isPopupBlockerEnabled();
                }

                const url = window.location.href;
                const host = window.location.hostname;

                if (host.includes("youtube.com") || host.includes("youtu.be")) {
                    runYouTubeBlocker();
                } else if (host.includes("pluto.tv")) {
                    runPlutoTvBlocker();
                } else {
                    runGenericBlocker();
                }

                function runYouTubeBlocker() {
                    let originalSpeed = 1.0;
                    let originalVolume = 1.0;
                    let wasAdShowing = false;
                    
                    function skipAds() {
                        if (!isAdBlockEnabled()) return;
                        const video = document.querySelector('video');
                        if (!video) return;

                        const adShowing = document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay, .ytp-ad-preview-container, .ytp-ad-preview-container-modern, .ytp-ad-image-overlay, .ytp-ad-text, .ytp-ad-preview-text, .ytp-tv-ad-preview-text');
                        
                        if (adShowing) {
                            if (!wasAdShowing) {
                                originalVolume = video.volume > 0 ? video.volume : (originalVolume || 1.0);
                                originalSpeed = video.playbackRate === 16 ? 1.0 : video.playbackRate;
                                wasAdShowing = true;
                            }
                            
                            video.muted = true;
                            video.volume = 0.0;
                            video.playbackRate = 16.0;
                            
                            const skipButtons = [
                                '.ytp-ad-skip-button',
                                '.ytp-ad-skip-button-modern',
                                '.ytp-ad-skip-button-tv',
                                '.ytp-tv-ad-skip-button',
                                '.ytp-ad-skip-button-text',
                                '.ytp-ad-skip-button-slot',
                                '.ytp-ad-skip-button-container'
                            ];
                            for (const selector of skipButtons) {
                                const skipBtn = document.querySelector(selector);
                                if (skipBtn) {
                                    skipBtn.click();
                                }
                            }
                        } else {
                            if (wasAdShowing || video.playbackRate === 16.0) {
                                video.playbackRate = originalSpeed || 1.0;
                                video.muted = false;
                                video.volume = originalVolume || 1.0;
                                wasAdShowing = false;
                            }
                        }
                        
                        const adsToHide = [
                            '.ytp-ad-overlay-container',
                            'ytd-promoted-sparkles-web-renderer',
                            'ytd-companion-card-renderer',
                            '.video-ads',
                            '.ytp-ad-module',
                            'ytd-ad-slot-renderer',
                            '#masthead-ad',
                            '.ytp-tv-ad-overlay'
                        ];
                        for (const selector of adsToHide) {
                            const elements = document.querySelectorAll(selector);
                            for (const el of elements) {
                                if (el && el.style.display !== 'none') {
                                    el.style.display = 'none';
                                }
                            }
                        }
                    }

                    skipAds();
                    const observer = new MutationObserver(skipAds);
                    observer.observe(document.body || document.documentElement, { childList: true, subtree: true });
                    setInterval(skipAds, 400);
                }

                function runPlutoTvBlocker() {
                    let originalSpeed = 1.0;
                    let originalVolume = 1.0;
                    let wasAdShowing = false;

                    function isPlutoTvAdShowing() {
                        const video = document.querySelector('video');
                        if (!video) return false;

                        const adSelectors = [
                            '.ad-break', '.ad-indicator', '.ad-countdown', '.ad-overlay',
                            '.pluto-ad-break-indicator', '.ad-break-indicator', '.ad-countdown-timer',
                            '.progress-bar-ad', '[class^="ad-break"]', '[class^="ad-indicator"]',
                            '[class^="ad-countdown"]', '[class^="ad-overlay"]',
                            '[class*=" ad-break"]', '[class*=" ad-indicator"]',
                            '[class*=" ad-countdown"]', '[class*=" ad-overlay"]'
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
                        if (!isAdBlockEnabled()) return;
                        const video = document.querySelector('video');
                        if (!video) return;

                        const adShowing = isPlutoTvAdShowing();
                        const isLive = window.location.pathname.includes('/live-tv') || !isFinite(video.duration) || video.duration === Infinity;
                        
                        if (adShowing) {
                            if (!wasAdShowing) {
                                originalVolume = video.volume > 0 ? video.volume : (originalVolume || 1.0);
                                originalSpeed = video.playbackRate === 16 ? 1.0 : video.playbackRate;
                                wasAdShowing = true;
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
                            }
                        }
                    }

                    skipAds();
                    const observer = new MutationObserver(skipAds);
                    observer.observe(document.body || document.documentElement, { childList: true, subtree: true });
                    setInterval(skipAds, 400);
                }

                function runGenericBlocker() {
                    let originalSpeed = 1.0;
                    let originalVolume = 1.0;
                    let wasAdShowing = false;

                    try {
                        if (isAdBlockEnabled() || isPopupBlockerEnabled()) {
                            const style = document.createElement('style');
                            style.innerHTML = `
                                .videoAdUi, .ima-ad-container, .ima-controls-container,
                                .vjs-ad-playing, .vjs-ad-loading, .vjs-ad-container,
                                .jw-flag-ads, .jw-ad-container, .jw-ad-overlay,
                                .fluid_ad_container, .fluid_video_wrapper_ad,
                                .ad-break, .ad-indicator, .ad-countdown, .ad-overlay,
                                .pluto-ad-break-indicator, .ad-break-indicator, .ad-countdown-timer,
                                .progress-bar-ad, .player-ad-overlay,
                                [class*="vast-ad" i], [class*="ima-ad" i],
                                [class*="n4g" i], [id*="n4g" i], [class*="popunder" i], [id*="popunder" i],
                                [class*="pop-under" i], [id*="pop-under" i], [class*="ad-box" i], [class*="ad-wrapper" i],
                                [class*="ad-slot" i], [class*="ad-placement" i] {
                                    position: fixed !important;
                                    left: -99999px !important;
                                    top: -99999px !important;
                                    pointer-events: none !important;
                                }
                            `;
                            (document.head || document.documentElement).appendChild(style);
                        }
                    } catch (e) {}

                    function closeFloatingAds() {
                        if (!isAdBlockEnabled() && !isPopupBlockerEnabled()) return;
                        try {
                            // Target specifically direct children of body where popups/overlays are spawned, or divs with popup classes.
                            // This reduces query target size from thousands of nested elements to a few dozens, preventing CPU lag.
                            const elements = document.querySelectorAll('body > div, body > section, body > iframe, body > aside, body > dialog, div[class*="popup" i], div[class*="overlay" i], div[class*="modal" i]');
                            for (let i = 0; i < elements.length; i++) {
                                const el = elements[i];
                                if (el.offsetWidth === 0 && el.offsetHeight === 0) continue;
                                
                                let style;
                                try {
                                    style = window.getComputedStyle(el);
                                } catch (e) {
                                    continue;
                                }
                                
                                const isFloating = style.position === 'fixed' || style.position === 'absolute';
                                
                                const classNameLower = (el.getAttribute('class') || "").toLowerCase();
                                const idLower = (el.getAttribute('id') || "").toLowerCase();
                                
                                const menuKeywords = ['menu', 'nav', 'sidebar', 'drawer', 'panel', 'dropdown', 'header', 'footer', 'search-box', 'search-container'];
                                let isMenu = false;
                                for (const kw of menuKeywords) {
                                    if (classNameLower.includes(kw) || idLower.includes(kw)) {
                                        const isAdWord = classNameLower.includes('ad') || classNameLower.includes('banner') || classNameLower.includes('pop') || classNameLower.includes('promo') ||
                                                         idLower.includes('ad') || idLower.includes('banner') || idLower.includes('pop') || idLower.includes('promo');
                                        if (!isAdWord) {
                                            isMenu = true;
                                            break;
                                        }
                                    }
                                }

                                const playerKeywords = ['player', 'video', 'control', 'vjs-', 'jw-', 'plyr', 'skin', 'media-', 'reproductor', 'controles'];
                                let isPlayerComponent = false;
                                for (const kw of playerKeywords) {
                                    if (classNameLower.includes(kw) || idLower.includes(kw)) {
                                        isPlayerComponent = true;
                                        break;
                                    }
                                }

                                if (isFloating && !isMenu && !isPlayerComponent) {
                                    // CRITICAL: If this element contains a video or is a video itself, NEVER hide it or move it!
                                    if (el.querySelector('video') || el.tagName.toLowerCase() === 'video') {
                                        continue;
                                    }

                                    // CRITICAL: If this element is or contains a real CAPTCHA (hCaptcha, reCAPTCHA, Cloudflare Turnstile), NEVER hide it!
                                    const captchaIframe = el.tagName.toLowerCase() === 'iframe' ? 
                                        (el.src && (el.src.includes('captcha') || el.src.includes('recaptcha') || el.src.includes('turnstile') || el.src.includes('cloudflare'))) : 
                                        el.querySelector('iframe[src*="captcha"], iframe[src*="recaptcha"], iframe[src*="hcaptcha"], iframe[src*="turnstile"]');
                                    if (captchaIframe || classNameLower.includes('captcha') || idLower.includes('captcha')) {
                                        continue;
                                    }

                                    // CRITICAL: If this element is or contains the main video player iframe, NEVER hide it!
                                    const iframe = el.tagName.toLowerCase() === 'iframe' ? el : el.querySelector('iframe');
                                    if (iframe) {
                                        const src = iframe.src || "";
                                        const playerKeywords = ['embed', 'player', 'video', 'stream', 'dood', 'voe', 'mixdrop', 'fembed', 'play', 'waaw', 'vidsrc', 'jwplayer', 'vimeo'];
                                        let isPlayerIframe = false;
                                        for (const kw of playerKeywords) {
                                            if (src.toLowerCase().includes(kw)) {
                                                isPlayerIframe = true;
                                                break;
                                            }
                                        }
                                        if (isPlayerIframe) {
                                            continue;
                                        }
                                    }

                                    let isClearlyAd = false;
                                    const innerHTML = el.innerHTML ? el.innerHTML.toLowerCase() : "";

                                    const adKeywords = ['banner', 'advertisement', 'publicidad', 'adsense', 'ad-container', 'taboola', 'outbrain', 'popunder', 'popup', 'overlay', 'dialog', 'modal', 'sponsor', 'google_ads', 'cont-anuncios', 'n4g', 'promo', 'offer', 'oferta', 'sales', 'descuento', 'discount', 'anuncio', 'ad-', 'robot', 'human', 'verify', 'verific', 'download', 'descargar', 'reproducir', 'play-', 'cf7'];
                                    for (const kw of adKeywords) {
                                        if (classNameLower.includes(kw) || idLower.includes(kw) || innerHTML.includes(kw)) {
                                            isClearlyAd = true;
                                            break;
                                        }
                                    }

                                    if (el.querySelector('iframe') || el.tagName.toLowerCase() === 'iframe') {
                                        isClearlyAd = true;
                                    }

                                    let foundClose = false;
                                    const clickables = el.querySelectorAll('button, span, div, a, img, svg');
                                    const rectEl = el.getBoundingClientRect();

                                    for (let j = 0; j < clickables.length; j++) {
                                        const c = clickables[j];
                                        if (c.offsetWidth === 0 && c.offsetHeight === 0) continue;
                                        
                                        const txt = (c.textContent || c.innerText || "").trim().toLowerCase();
                                        const cName = (c.getAttribute('class') || "").toLowerCase();
                                        const cId = (c.getAttribute('id') || "").toLowerCase();
                                        const alt = c.getAttribute('alt') || "";
                                        const ariaLabel = c.getAttribute('aria-label') || "";

                                        const isTextClose = /^(x|×|✕|✖|❌|close|cerrar|skip|ocultar|dismiss|hide|quitar|cancelar|clear)$/i.test(txt);
                                        const closePattern = /(close|cerrar|dismiss|btn[-_]x|popup[-_]x|icon[-_]x|cancel|exit|remove|clear|cross|closebtn|cls[-_]\d+|cls)/i;
                                        const isClassClose = closePattern.test(cName);
                                        const isIdClose = closePattern.test(cId);
                                        const isAltClose = closePattern.test(alt) || alt.toLowerCase().includes('x') || alt.toLowerCase().includes('cerrar');
                                        const isAriaClose = ariaLabel && (closePattern.test(ariaLabel) || ariaLabel.toLowerCase().includes('x') || ariaLabel.toLowerCase().includes('cerrar'));
                                        const isImgClose = c.tagName.toLowerCase() === 'img' && c.src && /(close|cerrar|cancel|exit|cross|[-_]x\b)/i.test(c.src);

                                        let isCornerClose = false;
                                        const rectC = c.getBoundingClientRect();
                                        if (rectC.width > 0 && rectC.height > 0 && rectC.width <= 60 && rectC.height <= 60) {
                                            const isNearTopRight = (rectC.right >= rectEl.right - 60) && (rectC.top <= rectEl.top + 60);
                                            const isNearTopLeft = (rectC.left <= rectEl.left + 60) && (rectC.top <= rectEl.top + 60);
                                            if (isNearTopRight || isNearTopLeft) {
                                                isCornerClose = true;
                                            }
                                        }

                                        if (isTextClose || isClassClose || isIdClose || isAltClose || isAriaClose || isImgClose || isCornerClose) {
                                            isClearlyAd = true;
                                            
                                            function safeClick(target) {
                                                try {
                                                    const touchStart = new TouchEvent('touchstart', { bubbles: true, cancelable: true });
                                                    target.dispatchEvent(touchStart);
                                                    const touchEnd = new TouchEvent('touchend', { bubbles: true, cancelable: true });
                                                    target.dispatchEvent(touchEnd);
                                                } catch (e) {}
                                                try {
                                                    const mouseDown = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
                                                    target.dispatchEvent(mouseDown);
                                                    const mouseUp = new MouseEvent('mouseup', { bubbles: true, cancelable: true });
                                                    target.dispatchEvent(mouseUp);
                                                } catch (e) {}
                                                try {
                                                    target.click();
                                                } catch (e) {}
                                            }

                                            safeClick(c);
                                            foundClose = true;
                                        }
                                    }

                                    if (isClearlyAd) {
                                        try {
                                            el.style.setProperty('position', 'fixed', 'important');
                                            el.style.setProperty('left', '-99999px', 'important');
                                            el.style.setProperty('top', '-99999px', 'important');
                                            el.style.setProperty('pointer-events', 'none', 'important');
                                        } catch (e) {}
                                    }
                                }
                            }
                        } catch (e) {}
                    }

                    function isGenericAdShowing(video) {
                        if (!video) return false;

                        const adSelectors = [
                            '.videoAdUi', '.ima-ad-container', '.ima-controls-container',
                            '.vjs-ad-playing', '.vjs-ad-loading', '.vjs-ad-container',
                            '.jw-flag-ads', '.jw-ad-container', '.jw-ad-overlay',
                            '.fluid_ad_container', '.fluid_video_wrapper_ad',
                            '.video-ads', '.ad-container', '.ad-showing', '.ad-interrupting',
                            '.ytp-ad-player-overlay', '.player-ad-overlay',
                            '[class*="vast-ad" i]', '[class*="ima-ad" i]', '[class*="ad-overlay" i]',
                            '[class*="ad-player" i]', '[class*="ad-showing" i]', '[class*="ad-break" i]',
                            '[class*="video-ad" i]', '[class*="videoAdUi" i]', '[id*="ad-container" i]',
                            '.ad-break', '.ad-indicator', '.ad-countdown', '.pluto-ad-break-indicator'
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
                            '.videoAdUiSkipButton', '.ima-skip-button', '.vast-skip-button', '.skip-button',
                            '[class*="skip-button" i]', '[class*="skip-btn" i]', '[class*="skipad" i]',
                            '[class*="skip-ad" i]', '[id*="skip-button" i]', '[id*="skip-ad" i]',
                            '.ytp-ad-skip-button', '.ytp-ad-skip-button-modern', '.ytp-ad-skip-button-tv',
                            '.ytp-tv-ad-skip-button', '.ytp-ad-skip-button-text'
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
                        closeFloatingAds();
                        if (!isAdBlockEnabled()) return;
                        const video = document.querySelector('video');
                        if (!video) return;

                        const adShowing = isGenericAdShowing(video);
                        const isLive = !isFinite(video.duration) || video.duration === Infinity;

                        if (adShowing) {
                            if (!wasAdShowing) {
                                originalVolume = video.volume > 0 ? video.volume : (originalVolume || 1.0);
                                originalSpeed = video.playbackRate === 16 ? 1.0 : video.playbackRate;
                                wasAdShowing = true;
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
                                } catch(e) {}
                            }
                        } else {
                            if (wasAdShowing || video.playbackRate === 16.0) {
                                video.playbackRate = isLive ? 1.0 : (originalSpeed || 1.0);
                                video.muted = false;
                                video.volume = originalVolume || 1.0;
                                wasAdShowing = false;
                            }
                        }
                    }

                    let lastRunTime = 0;
                    function runAdBlockerThrottled() {
                        const now = Date.now();
                        if (now - lastRunTime < 500) return;
                        lastRunTime = now;
                        runAdBlocker();
                    }

                    runAdBlockerThrottled();
                    const observer = new MutationObserver(runAdBlockerThrottled);
                    observer.observe(document.body || document.documentElement, { childList: true, subtree: true });
                    window.addEventListener('scroll', runAdBlockerThrottled, { passive: true });
                    window.addEventListener('click', runAdBlockerThrottled, { passive: true });
                    setInterval(runAdBlockerThrottled, 800);
                }
            })();
        """.trimIndent()
    }
}
