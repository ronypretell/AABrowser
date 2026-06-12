package com.kododake.aavideo.adblock

object AdBlockDomainList {
    private val blockedDomains = hashSetOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "adservice.google.com",
        "pubads.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "pagead2.googlesyndication.com",
        "adclick.g.doubleclick.net",
        "ad.doubleclick.net",
        "static.doubleclick.net",
        "stats.g.doubleclick.net",
        "adservice.google.co.uk",
        "adservice.google.de",
        "adservice.google.es",
        "adservice.google.fr",
        "adservice.google.it",
        "adservice.google.nl",
        "adservice.google.pl",
        "adservice.google.ca",
        "adservice.google.co.in",
        "adservice.google.co.jp",
        "adservice.google.com.br",
        "adservice.google.com.mx",
        "analytics.google.com",
        "google-analytics.com",
        "ssl.google-analytics.com",
        "fls.doubleclick.net",
        "cm.g.doubleclick.net",
        "pagead.l.doubleclick.net",
        "partnerad.l.doubleclick.net",
        "adserver.yahoo.com",
        "ads.yahoo.com",
        "analytics.yahoo.com",
        "adserver.adtech.de",
        "adserver.adtechus.com",
        "ads.youtube.com",
        "s.youtube.com",
        "youtube-ui.l.google.com",
        "video-stats.l.google.com",
        "exoclick.com",
        "juicyads.com",
        "popads.net",
        "adnxs.com",
        "rubiconproject.com",
        "openx.net",
        "pubmatic.com",
        "casalemedia.com",
        "criteo.com",
        "outbrain.com",
        "taboola.com",
        "revcontent.com",
        "mgid.com",
        "adroll.com",
        "smartadserver.com",
        "bidswitch.net",
        "indexww.com",
        "adform.net",
        "yieldlab.de",
        "adtech.de",
        "advertising.com",
        "quantserve.com",
        "scorecardresearch.com",
        "buysellads.com",
        "carbonads.net",
        "servedby-buysellads.com",
        "moatads.com",
        "iasds01.com",
        "amazon-adsystem.com",
        "ads-twitter.com",
        "static.ads-twitter.com",
        "ads.pinterest.com",
        "ads.tiktok.com",
        "analytics.tiktok.com",
        "ads.reddit.com",
        "outbrainimg.com",
        "taboolasyndication.com",
        "adnxs-simple.com",
        "ib.adnxs.com",
        "secure.adnxs.com",
        "fastclick.net",
        "mediaplex.com",
        "adtechus.com",
        "tribalfusion.com",
        "burstnet.com",
        "valueclick.com",
        "commission-junction.com",
        "cj.com",
        "tradedoubler.com",
        "awin1.com",
        "shareasale.com",
        "clickbank.net",
        "imrworldwide.com",
        "netanalise.com.br",
        "gemius.pl",
        "hotjar.com",
        "crazyegg.com",
        "mixpanel.com",
        "amplitude.com",
        "segment.io",
        "optimizely.com",
        "adzerk.net",
        "adzerk.com",
        "carbonads.com",
        "srv.carbonads.net",
        "engine.monetizer.co",
        "native.sharethrough.com",
        "ad.yieldpass.com",
        "ad.mail.ru",
        "an.yandex.ru",
        "yandex.ru/ads",
        "ads.taringa.net",
        "ads.twitch.tv",
        "ad-delivery.net"
    )

    fun isBlocked(url: String): Boolean {
        val host = try {
            val uri = android.net.Uri.parse(url)
            uri.host?.lowercase() ?: ""
        } catch (e: Exception) {
            ""
        }
        if (host.isEmpty()) return false

        var checkHost = host
        while (checkHost.isNotEmpty()) {
            if (blockedDomains.contains(checkHost)) {
                return true
            }
            val dotIndex = checkHost.indexOf('.')
            if (dotIndex != -1 && dotIndex < checkHost.length - 1) {
                checkHost = checkHost.substring(dotIndex + 1)
            } else {
                break
            }
        }

        // Check specific YouTube ad endpoints
        val path = try {
            android.net.Uri.parse(url).path?.lowercase() ?: ""
        } catch (e: Exception) {
            ""
        }
        if (host.contains("youtube") || host.contains("youtu.be")) {
            if (path.contains("/pagead/") ||
                path.contains("/api/stats/ads") ||
                path.contains("/youtubei/v1/player/ad_break") ||
                path.contains("/get_midroll_info")) {
                return true
            }
        }

        return false
    }
}
