package com.kododake.aavideo.adblock

object YouTubeAdBlocker {
    fun getInjectionJs(url: String): String? {
        val host = try {
            android.net.Uri.parse(url).host?.lowercase() ?: ""
        } catch (e: Exception) {
            ""
        }
        if (!host.contains("youtube.com") && !host.contains("youtu.be")) return null

        return """
            (function() {
                let originalSpeed = 1.0;
                let originalVolume = 1.0;
                let wasAdShowing = false;
                
                function skipAds() {
                    const video = document.querySelector('video');
                    if (!video) return;

                    const adShowing = document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay, .ytp-ad-preview-container, .ytp-ad-preview-container-modern, .ytp-ad-image-overlay, .ytp-ad-text, .ytp-ad-preview-text, .ytp-tv-ad-preview-text');
                    
                    if (adShowing) {
                        if (!wasAdShowing) {
                            originalVolume = video.volume > 0 ? video.volume : (originalVolume || 1.0);
                            originalSpeed = video.playbackRate === 16 ? 1.0 : video.playbackRate;
                            wasAdShowing = true;
                            console.log('Ad detected: storing original state (vol=' + originalVolume + ', speed=' + originalSpeed + ')');
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
                                console.log('Ad skip button clicked');
                            }
                        }
                    } else {
                        if (wasAdShowing || video.playbackRate === 16.0) {
                            video.playbackRate = originalSpeed || 1.0;
                            video.muted = false;
                            video.volume = originalVolume || 1.0;
                            wasAdShowing = false;
                            console.log('Ad finished: restoring state (vol=' + originalVolume + ', speed=' + originalSpeed + ')');
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
                observer.observe(document.body, {
                    childList: true,
                    subtree: true
                });
                
                setInterval(skipAds, 400);
            })();
        """.trimIndent()
    }
}
