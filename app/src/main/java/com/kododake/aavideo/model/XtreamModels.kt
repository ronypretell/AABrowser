package com.kododake.aavideo.model

/**
 * Differentiates between M3U playlist URLs and Xtream Codes API credentials.
 */
enum class PlaylistType {
    M3U,
    XTREAM
}

/**
 * Credentials for connecting to an Xtream Codes API server.
 */
data class XtreamCredentials(
    val profileName: String,
    val username: String,
    val password: String,
    val serverUrl: String // e.g. "http://server.com:8080"
)

/**
 * Server info returned by the Xtream Codes API authentication endpoint.
 */
data class XtreamServerInfo(
    val url: String = "",
    val port: String = "",
    val httpsPort: String = "",
    val serverProtocol: String = "http",
    val rtmpPort: String = "",
    val timezone: String = "",
    val timestampNow: Long = 0,
    val timeNow: String = ""
)

/**
 * User info returned by the Xtream Codes API authentication endpoint.
 */
data class XtreamUserInfo(
    val username: String = "",
    val password: String = "",
    val message: String = "",
    val auth: Int = 0,
    val status: String = "",
    val expDate: String = "",
    val isTrial: Boolean = false,
    val activeCons: Int = 0,
    val createdAt: String = "",
    val maxConnections: Int = 0,
    val allowedOutputFormats: List<String> = emptyList()
)

/**
 * Combined authentication response.
 */
data class XtreamAuthResponse(
    val userInfo: XtreamUserInfo,
    val serverInfo: XtreamServerInfo
)

/**
 * A category (works for Live, VOD, and Series).
 */
data class XtreamCategory(
    val categoryId: String,
    val categoryName: String,
    val parentId: Int = 0
)

/**
 * A live TV stream/channel.
 */
data class XtreamLiveStream(
    val num: Int = 0,
    val name: String = "",
    val streamType: String = "live",
    val streamId: Int = 0,
    val streamIcon: String = "",
    val epgChannelId: String = "",
    val added: String = "",
    val categoryId: String = "",
    val customSid: String = "",
    val tvArchive: Int = 0,
    val directSource: String = "",
    val tvArchiveDuration: Int = 0
)

/**
 * A VOD (movie) stream.
 */
data class XtreamVodStream(
    val num: Int = 0,
    val name: String = "",
    val streamType: String = "movie",
    val streamId: Int = 0,
    val streamIcon: String = "",
    val rating: String = "",
    val ratingFivestar: Double = 0.0,
    val added: String = "",
    val categoryId: String = "",
    val containerExtension: String = "mp4",
    val customSid: String = "",
    val directSource: String = ""
)

/**
 * A series entry (top-level).
 */
data class XtreamSeries(
    val num: Int = 0,
    val name: String = "",
    val seriesId: Int = 0,
    val cover: String = "",
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val releaseDate: String = "",
    val lastModified: String = "",
    val rating: String = "",
    val ratingFivestar: Double = 0.0,
    val backdropPath: List<String> = emptyList(),
    val youtubeTrailer: String = "",
    val episodeRunTime: String = "",
    val categoryId: String = ""
)

/**
 * Full series info including seasons and episodes.
 */
data class XtreamSeriesInfo(
    val seasons: List<XtreamSeason> = emptyList(),
    val episodes: Map<String, List<XtreamEpisode>> = emptyMap(),
    val seriesInfo: XtreamSeries = XtreamSeries()
)

/**
 * A season within a series.
 */
data class XtreamSeason(
    val seasonNumber: Int = 0,
    val name: String = "",
    val airDate: String = "",
    val episodeCount: Int = 0,
    val cover: String = "",
    val coverBig: String = ""
)

/**
 * An episode within a series season.
 */
data class XtreamEpisode(
    val id: String = "",
    val episodeNum: Int = 0,
    val title: String = "",
    val containerExtension: String = "mp4",
    val season: Int = 0,
    val directSource: String = "",
    val plot: String = "",
    val durationSecs: Int = 0,
    val duration: String = "",
    val coverBig: String = "",
    val rating: Double = 0.0
)
