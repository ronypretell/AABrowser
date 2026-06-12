package com.kododake.aavideo.adblock

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object AdBlockEngine {
    fun shouldBlock(url: String): WebResourceResponse? {
        if (AdBlockDomainList.isBlocked(url)) {
            // Return empty 200 OK response for blocked resources
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                200,
                "OK",
                mapOf("Access-Control-Allow-Origin" to "*"),
                ByteArrayInputStream("".toByteArray())
            )
        }
        return null
    }
}
