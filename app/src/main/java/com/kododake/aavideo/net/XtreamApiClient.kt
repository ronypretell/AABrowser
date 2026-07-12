package com.kododake.aavideo.net

import com.kododake.aavideo.model.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for the Xtream Codes API protocol used by IPTV Smarters Pro.
 * Handles authentication, category/stream listing, and stream URL construction.
 */
class XtreamApiClient(
    private val credentials: XtreamCredentials
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String
        get() {
            val url = credentials.serverUrl.trimEnd('/')
            return "$url/player_api.php?username=${credentials.username}&password=${credentials.password}"
        }

    // --- Authentication ---

    fun authenticate(
        onSuccess: (XtreamAuthResponse) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val request = Request.Builder().url(baseUrl).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onFailure(e.localizedMessage ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onFailure("Server returned ${response.code}")
                    return
                }
                try {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)

                    val userInfoJson = json.optJSONObject("user_info")
                    if (userInfoJson == null) {
                        onFailure("Invalid response: missing user_info")
                        return
                    }

                    val auth = userInfoJson.optInt("auth", 0)
                    if (auth != 1) {
                        onFailure("Authentication failed")
                        return
                    }

                    val userInfo = parseUserInfo(userInfoJson)
                    val serverInfo = parseServerInfo(json.optJSONObject("server_info"))
                    onSuccess(XtreamAuthResponse(userInfo, serverInfo))
                } catch (e: Exception) {
                    onFailure(e.localizedMessage ?: "Parse error")
                }
            }
        })
    }

    // --- Live TV ---

    fun getLiveCategories(
        onSuccess: (List<XtreamCategory>) -> Unit,
        onFailure: (String) -> Unit
    ) {
        fetchCategories("get_live_categories", onSuccess, onFailure)
    }

    fun getLiveStreams(
        categoryId: String? = null,
        onSuccess: (List<XtreamLiveStream>) -> Unit,
        onFailure: (String) -> Unit
    ) {
        var url = "$baseUrl&action=get_live_streams"
        if (!categoryId.isNullOrEmpty()) {
            url += "&category_id=$categoryId"
        }
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onFailure(e.localizedMessage ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onFailure("Server returned ${response.code}")
                    return
                }
                try {
                    val body = response.body?.string() ?: "[]"
                    val jsonArray = JSONArray(body)
                    val streams = mutableListOf<XtreamLiveStream>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        streams.add(parseLiveStream(obj))
                    }
                    onSuccess(streams)
                } catch (e: Exception) {
                    onFailure(e.localizedMessage ?: "Parse error")
                }
            }
        })
    }

    // --- VOD (Movies) ---

    fun getVodCategories(
        onSuccess: (List<XtreamCategory>) -> Unit,
        onFailure: (String) -> Unit
    ) {
        fetchCategories("get_vod_categories", onSuccess, onFailure)
    }

    fun getVodStreams(
        categoryId: String? = null,
        onSuccess: (List<XtreamVodStream>) -> Unit,
        onFailure: (String) -> Unit
    ) {
        var url = "$baseUrl&action=get_vod_streams"
        if (!categoryId.isNullOrEmpty()) {
            url += "&category_id=$categoryId"
        }
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onFailure(e.localizedMessage ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onFailure("Server returned ${response.code}")
                    return
                }
                try {
                    val body = response.body?.string() ?: "[]"
                    val jsonArray = JSONArray(body)
                    val streams = mutableListOf<XtreamVodStream>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        streams.add(parseVodStream(obj))
                    }
                    onSuccess(streams)
                } catch (e: Exception) {
                    onFailure(e.localizedMessage ?: "Parse error")
                }
            }
        })
    }

    // --- Series ---

    fun getSeriesCategories(
        onSuccess: (List<XtreamCategory>) -> Unit,
        onFailure: (String) -> Unit
    ) {
        fetchCategories("get_series_categories", onSuccess, onFailure)
    }

    fun getSeries(
        categoryId: String? = null,
        onSuccess: (List<XtreamSeries>) -> Unit,
        onFailure: (String) -> Unit
    ) {
        var url = "$baseUrl&action=get_series"
        if (!categoryId.isNullOrEmpty()) {
            url += "&category_id=$categoryId"
        }
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onFailure(e.localizedMessage ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onFailure("Server returned ${response.code}")
                    return
                }
                try {
                    val body = response.body?.string() ?: "[]"
                    val jsonArray = JSONArray(body)
                    val seriesList = mutableListOf<XtreamSeries>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        seriesList.add(parseSeriesEntry(obj))
                    }
                    onSuccess(seriesList)
                } catch (e: Exception) {
                    onFailure(e.localizedMessage ?: "Parse error")
                }
            }
        })
    }

    fun getSeriesInfo(
        seriesId: Int,
        onSuccess: (XtreamSeriesInfo) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val url = "$baseUrl&action=get_series_info&series_id=$seriesId"
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onFailure(e.localizedMessage ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onFailure("Server returned ${response.code}")
                    return
                }
                try {
                    val body = response.body?.string() ?: "{}"
                    val json = JSONObject(body)
                    val seriesInfo = parseSeriesInfo(json)
                    onSuccess(seriesInfo)
                } catch (e: Exception) {
                    onFailure(e.localizedMessage ?: "Parse error")
                }
            }
        })
    }

    // --- Stream URL Builders ---

    fun buildLiveStreamUrl(streamId: Int): String {
        val serverUrl = credentials.serverUrl.trimEnd('/')
        return "$serverUrl/live/${credentials.username}/${credentials.password}/$streamId.ts"
    }

    fun buildVodStreamUrl(streamId: Int, extension: String = "mp4"): String {
        val serverUrl = credentials.serverUrl.trimEnd('/')
        return "$serverUrl/movie/${credentials.username}/${credentials.password}/$streamId.$extension"
    }

    fun buildSeriesStreamUrl(streamId: String, extension: String = "mp4"): String {
        val serverUrl = credentials.serverUrl.trimEnd('/')
        return "$serverUrl/series/${credentials.username}/${credentials.password}/$streamId.$extension"
    }

    // --- Private Helpers ---

    private fun fetchCategories(
        action: String,
        onSuccess: (List<XtreamCategory>) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val url = "$baseUrl&action=$action"
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onFailure(e.localizedMessage ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onFailure("Server returned ${response.code}")
                    return
                }
                try {
                    val body = response.body?.string() ?: "[]"
                    val jsonArray = JSONArray(body)
                    val categories = mutableListOf<XtreamCategory>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        categories.add(
                            XtreamCategory(
                                categoryId = obj.optString("category_id", ""),
                                categoryName = obj.optString("category_name", ""),
                                parentId = obj.optInt("parent_id", 0)
                            )
                        )
                    }
                    onSuccess(categories)
                } catch (e: Exception) {
                    onFailure(e.localizedMessage ?: "Parse error")
                }
            }
        })
    }

    private fun parseUserInfo(json: JSONObject): XtreamUserInfo {
        val allowedFormats = mutableListOf<String>()
        val formatsArray = json.optJSONArray("allowed_output_formats")
        if (formatsArray != null) {
            for (i in 0 until formatsArray.length()) {
                allowedFormats.add(formatsArray.optString(i, ""))
            }
        }
        return XtreamUserInfo(
            username = json.optString("username", ""),
            password = json.optString("password", ""),
            message = json.optString("message", ""),
            auth = json.optInt("auth", 0),
            status = json.optString("status", ""),
            expDate = json.optString("exp_date", ""),
            isTrial = json.optString("is_trial", "0") == "1",
            activeCons = json.optString("active_cons", "0").toIntOrNull() ?: 0,
            createdAt = json.optString("created_at", ""),
            maxConnections = json.optString("max_connections", "0").toIntOrNull() ?: 0,
            allowedOutputFormats = allowedFormats
        )
    }

    private fun parseServerInfo(json: JSONObject?): XtreamServerInfo {
        if (json == null) return XtreamServerInfo()
        return XtreamServerInfo(
            url = json.optString("url", ""),
            port = json.optString("port", ""),
            httpsPort = json.optString("https_port", ""),
            serverProtocol = json.optString("server_protocol", "http"),
            rtmpPort = json.optString("rtmp_port", ""),
            timezone = json.optString("timezone", ""),
            timestampNow = json.optLong("timestamp_now", 0),
            timeNow = json.optString("time_now", "")
        )
    }

    private fun parseLiveStream(json: JSONObject): XtreamLiveStream {
        return XtreamLiveStream(
            num = json.optInt("num", 0),
            name = json.optString("name", ""),
            streamType = json.optString("stream_type", "live"),
            streamId = json.optInt("stream_id", 0),
            streamIcon = json.optString("stream_icon", ""),
            epgChannelId = json.optString("epg_channel_id", ""),
            added = json.optString("added", ""),
            categoryId = json.optString("category_id", ""),
            customSid = json.optString("custom_sid", ""),
            tvArchive = json.optInt("tv_archive", 0),
            directSource = json.optString("direct_source", ""),
            tvArchiveDuration = json.optInt("tv_archive_duration", 0)
        )
    }

    private fun parseVodStream(json: JSONObject): XtreamVodStream {
        return XtreamVodStream(
            num = json.optInt("num", 0),
            name = json.optString("name", ""),
            streamType = json.optString("stream_type", "movie"),
            streamId = json.optInt("stream_id", 0),
            streamIcon = json.optString("stream_icon", ""),
            rating = json.optString("rating", ""),
            ratingFivestar = json.optDouble("rating_5based", 0.0),
            added = json.optString("added", ""),
            categoryId = json.optString("category_id", ""),
            containerExtension = json.optString("container_extension", "mp4"),
            customSid = json.optString("custom_sid", ""),
            directSource = json.optString("direct_source", "")
        )
    }

    private fun parseSeriesEntry(json: JSONObject): XtreamSeries {
        val backdropPaths = mutableListOf<String>()
        val backdropArray = json.optJSONArray("backdrop_path")
        if (backdropArray != null) {
            for (i in 0 until backdropArray.length()) {
                backdropPaths.add(backdropArray.optString(i, ""))
            }
        }
        return XtreamSeries(
            num = json.optInt("num", 0),
            name = json.optString("name", ""),
            seriesId = json.optInt("series_id", 0),
            cover = json.optString("cover", ""),
            plot = json.optString("plot", ""),
            cast = json.optString("cast", ""),
            director = json.optString("director", ""),
            genre = json.optString("genre", ""),
            releaseDate = json.optString("releaseDate", ""),
            lastModified = json.optString("last_modified", ""),
            rating = json.optString("rating", ""),
            ratingFivestar = json.optDouble("rating_5based", 0.0),
            backdropPath = backdropPaths,
            youtubeTrailer = json.optString("youtube_trailer", ""),
            episodeRunTime = json.optString("episode_run_time", ""),
            categoryId = json.optString("category_id", "")
        )
    }

    private fun parseSeriesInfo(json: JSONObject): XtreamSeriesInfo {
        // Parse seasons
        val seasons = mutableListOf<XtreamSeason>()
        val seasonsArray = json.optJSONArray("seasons")
        if (seasonsArray != null) {
            for (i in 0 until seasonsArray.length()) {
                val sObj = seasonsArray.getJSONObject(i)
                seasons.add(
                    XtreamSeason(
                        seasonNumber = sObj.optInt("season_number", 0),
                        name = sObj.optString("name", ""),
                        airDate = sObj.optString("air_date", ""),
                        episodeCount = sObj.optInt("episode_count", 0),
                        cover = sObj.optString("cover", ""),
                        coverBig = sObj.optString("cover_big", "")
                    )
                )
            }
        }

        // Parse episodes (grouped by season number as string key)
        val episodes = mutableMapOf<String, List<XtreamEpisode>>()
        val episodesObj = json.optJSONObject("episodes")
        if (episodesObj != null) {
            val keys = episodesObj.keys()
            while (keys.hasNext()) {
                val seasonKey = keys.next()
                val episodeArray = episodesObj.optJSONArray(seasonKey) ?: continue
                val episodeList = mutableListOf<XtreamEpisode>()
                for (i in 0 until episodeArray.length()) {
                    val eObj = episodeArray.getJSONObject(i)
                    episodeList.add(
                        XtreamEpisode(
                            id = eObj.optString("id", ""),
                            episodeNum = eObj.optInt("episode_num", 0),
                            title = eObj.optString("title", ""),
                            containerExtension = eObj.optString("container_extension", "mp4"),
                            season = eObj.optInt("season", 0),
                            directSource = eObj.optString("direct_source", ""),
                            plot = eObj.optString("plot", ""),
                            durationSecs = eObj.optInt("duration_secs", 0),
                            duration = eObj.optString("duration", ""),
                            coverBig = eObj.optString("info", JSONObject().toString()).let {
                                try {
                                    JSONObject(it).optString("cover_big", "")
                                } catch (e: Exception) {
                                    eObj.optString("cover_big", "")
                                }
                            },
                            rating = eObj.optDouble("rating", 0.0)
                        )
                    )
                }
                episodes[seasonKey] = episodeList
            }
        }

        return XtreamSeriesInfo(
            seasons = seasons,
            episodes = episodes,
            seriesInfo = json.optJSONObject("info")?.let { parseSeriesEntry(it) } ?: XtreamSeries()
        )
    }

    fun shutdown() {
        httpClient.dispatcher.cancelAll()
    }
}
