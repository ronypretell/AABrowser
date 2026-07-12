package com.kododake.aavideo

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.chip.Chip
import com.kododake.aavideo.databinding.ActivityIptvBinding
import com.kododake.aavideo.databinding.DialogAddIptvBinding
import com.kododake.aavideo.databinding.ItemIptvCategoryBinding
import com.kododake.aavideo.databinding.ItemIptvChannelBinding
import com.kododake.aavideo.databinding.ItemIptvPlaylistBinding
import com.kododake.aavideo.model.*
import com.kododake.aavideo.net.XtreamApiClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class IptvActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIptvBinding
    private val httpClient = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null

    // Playlists & Channels data
    private val playlists = mutableListOf<IptvPlaylist>()
    private var allChannels = listOf<IptvChannel>()
    private var filteredChannels = listOf<IptvChannel>()
    private var categories = listOf<String>()

    // Xtream data
    private var xtreamApiClient: XtreamApiClient? = null
    private var currentXtreamPlaylist: IptvPlaylist? = null
    private var currentXtreamAuthResponse: XtreamAuthResponse? = null
    private var allXtreamCategories = listOf<XtreamCategory>()
    private var filteredXtreamCategories = listOf<XtreamCategory>()
    private var currentContentType: XtreamContentType = XtreamContentType.LIVE

    // Series detail data
    private var currentSeriesInfo: XtreamSeriesInfo? = null
    private var currentSeasonKey: String? = null

    // Adapters
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var mainChannelAdapter: ChannelAdapter // State B Adapter
    private lateinit var channelAdapter: ChannelAdapter     // State C Sidebar Adapter
    private lateinit var categoryAdapter: CategoryAdapter   // State E Adapter
    private lateinit var episodeAdapter: EpisodeAdapter      // State F Adapter

    // Sidebar state
    private var isSidebarOpen = false
    private var sidebarWidthPx = 0f

    // SharedPreferences constants
    private val PREFS_NAME = "iptv_prefs"
    private val KEY_PLAYLISTS = "playlists_json"

    // ImageLoader LruCache
    private val imageCache = LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt())
    private val imageLoaderExecutor = Executors.newFixedThreadPool(4)

    // Xtream content type enum
    enum class XtreamContentType { LIVE, VOD, SERIES }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIptvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup standard screen fit
        WindowCompat.setDecorFitsSystemWindows(window, true)

        setupUI()
        loadSavedPlaylists()
    }

    private fun setupUI() {
        // Recycler for Playlists (State A)
        playlistAdapter = PlaylistAdapter(playlists, 
            onPlaylistClick = { playlist ->
                if (playlist.type == PlaylistType.XTREAM) {
                    authenticateXtream(playlist)
                } else {
                    loadPlaylistChannels(playlist)
                }
            },
            onDeleteClick = { playlist ->
                showDeletePlaylistDialog(playlist)
            }
        )
        binding.recyclerViewPlaylists.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewPlaylists.adapter = playlistAdapter

        // Recycler for Channels Main Dashboard (State B)
        mainChannelAdapter = ChannelAdapter(emptyList()) { channel ->
            playChannel(channel)
        }
        binding.recyclerViewChannelsMain.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewChannelsMain.adapter = mainChannelAdapter

        // Recycler for Channels Sidebar (State C Player)
        channelAdapter = ChannelAdapter(emptyList()) { channel ->
            playChannel(channel)
        }
        binding.recyclerViewChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewChannels.adapter = channelAdapter

        // Recycler for Categories (State E)
        categoryAdapter = CategoryAdapter(emptyList()) { category ->
            loadXtreamStreamsByCategory(category)
        }
        binding.recyclerViewCategories.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewCategories.adapter = categoryAdapter

        // Recycler for Episodes (State F)
        episodeAdapter = EpisodeAdapter(emptyList()) { episode ->
            playEpisode(episode)
        }
        binding.recyclerViewEpisodes.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewEpisodes.adapter = episodeAdapter

        // Add Playlist Button
        binding.buttonAddPlaylist.setOnClickListener {
            showAddPlaylistDialog()
        }

        // Back button in management view (State A to MainActivity)
        binding.buttonBackToMain.setOnClickListener {
            finish()
        }

        // Back button in channel selection (State B to State A or State E)
        binding.buttonBackToPlaylists.setOnClickListener {
            binding.channelsViewContainer.visibility = View.GONE
            if (currentXtreamPlaylist != null) {
                // If coming from Xtream, go back to category list
                binding.categoryListContainer.visibility = View.VISIBLE
            } else {
                binding.playlistViewContainer.visibility = View.VISIBLE
            }
        }

        // Exit player button (State C to State B)
        binding.buttonExitPlayer.setOnClickListener {
            stopPlayback()
        }

        // Toggle Sidebar button (State C)
        binding.buttonToggleSidebar.setOnClickListener {
            if (isSidebarOpen) closeSidebar() else openSidebar()
        }

        // Close Sidebar button (inside the sidebar)
        binding.buttonCloseSidebar.setOnClickListener {
            closeSidebar()
        }

        // Setup channels sidebar translation
        sidebarWidthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            320f,
            resources.displayMetrics
        )
        binding.channelsSidebar.translationX = sidebarWidthPx

        // Search text watcher - State B Main List
        binding.editTextSearchChannelsMain.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Category spinner listener - State B Main List
        binding.spinnerCategoriesMain.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyFilters()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Search text watcher - State C Sidebar List
        binding.editTextSearchChannels.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Category spinner listener - State C Sidebar List
        binding.spinnerCategories.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyFilters()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // --- Xtream Dashboard (State D) ---
        binding.buttonBackFromDashboard.setOnClickListener {
            binding.xtreamDashboardContainer.visibility = View.GONE
            binding.playlistViewContainer.visibility = View.VISIBLE
            currentXtreamPlaylist = null
            xtreamApiClient?.shutdown()
            xtreamApiClient = null
        }

        binding.cardLiveTv.setOnClickListener {
            currentContentType = XtreamContentType.LIVE
            loadXtreamCategories(XtreamContentType.LIVE)
        }

        binding.cardMovies.setOnClickListener {
            currentContentType = XtreamContentType.VOD
            loadXtreamCategories(XtreamContentType.VOD)
        }

        binding.cardSeries.setOnClickListener {
            currentContentType = XtreamContentType.SERIES
            loadXtreamCategories(XtreamContentType.SERIES)
        }

        // --- Category List (State E) ---
        binding.buttonBackFromCategories.setOnClickListener {
            binding.categoryListContainer.visibility = View.GONE
            binding.xtreamDashboardContainer.visibility = View.VISIBLE
        }

        binding.buttonAllChannels.setOnClickListener {
            loadXtreamStreamsByCategory(null)
        }

        // Search categories
        binding.editTextSearchCategories.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyCategoryFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // --- Series Detail (State F) ---
        binding.buttonBackFromSeriesDetail.setOnClickListener {
            binding.seriesDetailContainer.visibility = View.GONE
            // Go back to channel/series list (State B)
            binding.channelsViewContainer.visibility = View.VISIBLE
        }
    }

    // --- PLAYLIST PERSISTENCE ---

    private fun loadSavedPlaylists() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PLAYLISTS, "[]")
        playlists.clear()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val typeStr = obj.optString("type", "M3U")
                val type = try { PlaylistType.valueOf(typeStr) } catch (e: Exception) { PlaylistType.M3U }
                playlists.add(
                    IptvPlaylist(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        type = type,
                        username = obj.optString("username", null),
                        password = obj.optString("password", null),
                        serverUrl = obj.optString("serverUrl", null)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        updatePlaylistView()
    }

    private fun savePlaylists() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (pl in playlists) {
            val obj = JSONObject()
            obj.put("id", pl.id)
            obj.put("name", pl.name)
            obj.put("url", pl.url)
            obj.put("type", pl.type.name)
            pl.username?.let { obj.put("username", it) }
            pl.password?.let { obj.put("password", it) }
            pl.serverUrl?.let { obj.put("serverUrl", it) }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_PLAYLISTS, jsonArray.toString()).apply()
        updatePlaylistView()
    }

    private fun updatePlaylistView() {
        if (playlists.isEmpty()) {
            binding.textViewEmptyState.visibility = View.VISIBLE
            binding.recyclerViewPlaylists.visibility = View.GONE
        } else {
            binding.textViewEmptyState.visibility = View.GONE
            binding.recyclerViewPlaylists.visibility = View.VISIBLE
            playlistAdapter.notifyDataSetChanged()
        }
    }

    // --- DIALOGS ---

    private fun showAddPlaylistDialog() {
        val dialog = Dialog(this)
        val dialogBinding = DialogAddIptvBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Toggle between M3U and Xtream Codes fields
        var isXtreamMode = false
        dialogBinding.togglePlaylistType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isXtreamMode = (checkedId == R.id.buttonTypeXtream)
                dialogBinding.containerM3uFields.visibility = if (isXtreamMode) View.GONE else View.VISIBLE
                dialogBinding.containerXtreamFields.visibility = if (isXtreamMode) View.VISIBLE else View.GONE
            }
        }

        dialogBinding.buttonCancelAdd.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.buttonConfirmAdd.setOnClickListener {
            if (isXtreamMode) {
                val profileName = dialogBinding.editTextXtreamProfileName.text.toString().trim()
                val username = dialogBinding.editTextXtreamUsername.text.toString().trim()
                val password = dialogBinding.editTextXtreamPassword.text.toString().trim()
                val serverUrl = dialogBinding.editTextXtreamServerUrl.text.toString().trim()

                if (profileName.isEmpty() || username.isEmpty() || password.isEmpty() || serverUrl.isEmpty()) {
                    Toast.makeText(this, getString(R.string.iptv_fill_all_fields), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                dialog.dismiss()
                addXtreamPlaylist(profileName, username, password, serverUrl)
            } else {
                val name = dialogBinding.editTextPlaylistName.text.toString().trim()
                val url = dialogBinding.editTextPlaylistUrl.text.toString().trim()

                if (name.isEmpty() || url.isEmpty()) {
                    Toast.makeText(this, getString(R.string.iptv_fill_all_fields), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                dialog.dismiss()
                downloadAndAddPlaylist(name, url)
            }
        }

        dialog.show()
    }

    private fun showDeletePlaylistDialog(playlist: IptvPlaylist) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.iptv_delete_confirm))
            .setMessage(getString(R.string.iptv_delete_confirm_msg))
            .setNegativeButton(getString(R.string.iptv_btn_cancel), null)
            .setPositiveButton(getString(R.string.bookmark_delete)) { _, _ ->
                if (playlist.type == PlaylistType.M3U) {
                    val file = File(cacheDir, "iptv_playlist_${playlist.id}.m3u")
                    if (file.exists()) {
                        file.delete()
                    }
                }
                playlists.remove(playlist)
                savePlaylists()
                Toast.makeText(this, "Lista eliminada", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // --- XTREAM CODES FLOW ---

    private fun addXtreamPlaylist(profileName: String, username: String, password: String, serverUrl: String) {
        binding.playlistProgressBar.visibility = View.VISIBLE
        binding.buttonAddPlaylist.isEnabled = false

        val credentials = XtreamCredentials(profileName, username, password, serverUrl)
        val client = XtreamApiClient(credentials)

        client.authenticate(
            onSuccess = { authResponse ->
                mainHandler.post {
                    binding.playlistProgressBar.visibility = View.GONE
                    binding.buttonAddPlaylist.isEnabled = true

                    val playlistId = System.currentTimeMillis().toString()
                    playlists.add(
                        IptvPlaylist(
                            id = playlistId,
                            name = profileName,
                            url = serverUrl,
                            type = PlaylistType.XTREAM,
                            username = username,
                            password = password,
                            serverUrl = serverUrl
                        )
                    )
                    savePlaylists()
                    Toast.makeText(this, getString(R.string.iptv_auth_success), Toast.LENGTH_SHORT).show()
                    client.shutdown()
                }
            },
            onFailure = { error ->
                mainHandler.post {
                    binding.playlistProgressBar.visibility = View.GONE
                    binding.buttonAddPlaylist.isEnabled = true
                    Toast.makeText(this, "${getString(R.string.iptv_auth_failed)}: $error", Toast.LENGTH_LONG).show()
                    client.shutdown()
                }
            }
        )
    }

    private fun authenticateXtream(playlist: IptvPlaylist) {
        binding.playlistProgressBar.visibility = View.VISIBLE

        val credentials = XtreamCredentials(
            profileName = playlist.name,
            username = playlist.username ?: "",
            password = playlist.password ?: "",
            serverUrl = playlist.serverUrl ?: playlist.url
        )

        val client = XtreamApiClient(credentials)
        xtreamApiClient?.shutdown()
        xtreamApiClient = client
        currentXtreamPlaylist = playlist

        client.authenticate(
            onSuccess = { authResponse ->
                mainHandler.post {
                    binding.playlistProgressBar.visibility = View.GONE
                    currentXtreamAuthResponse = authResponse
                    showXtreamDashboard(authResponse)
                }
            },
            onFailure = { error ->
                mainHandler.post {
                    binding.playlistProgressBar.visibility = View.GONE
                    Toast.makeText(this, "${getString(R.string.iptv_auth_failed)}: $error", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun showXtreamDashboard(authResponse: XtreamAuthResponse) {
        binding.playlistViewContainer.visibility = View.GONE
        binding.xtreamDashboardContainer.visibility = View.VISIBLE

        val playlist = currentXtreamPlaylist ?: return
        binding.textViewXtreamProfileName.text = playlist.name

        val userInfo = authResponse.userInfo
        val statusText = if (userInfo.status == "Active")
            getString(R.string.iptv_status_active) else getString(R.string.iptv_status_expired)
        binding.textViewXtreamUserInfo.text = "${getString(R.string.iptv_user_label, userInfo.username)} | $statusText"

        // Expiry date
        try {
            val expTimestamp = userInfo.expDate.toLongOrNull()
            if (expTimestamp != null && expTimestamp > 0) {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val expDate = dateFormat.format(Date(expTimestamp * 1000))
                binding.textViewXtreamExpiry.text = getString(R.string.iptv_expires, expDate)
            } else {
                binding.textViewXtreamExpiry.text = getString(R.string.iptv_expires, userInfo.expDate)
            }
        } catch (e: Exception) {
            binding.textViewXtreamExpiry.text = getString(R.string.iptv_expires, userInfo.expDate)
        }

        binding.textViewXtreamConnections.text = getString(
            R.string.iptv_active_connections, userInfo.activeCons, userInfo.maxConnections
        )

        // Reset counts - they will be populated when categories load
        binding.textViewLiveTvCount.text = ""
        binding.textViewMoviesCount.text = ""
        binding.textViewSeriesCount.text = ""
    }

    private fun loadXtreamCategories(type: XtreamContentType) {
        val client = xtreamApiClient ?: return

        binding.xtreamDashboardContainer.visibility = View.GONE
        binding.categoryListContainer.visibility = View.VISIBLE
        binding.categoryProgressBar.visibility = View.VISIBLE
        binding.editTextSearchCategories.setText("")

        val title = when (type) {
            XtreamContentType.LIVE -> getString(R.string.iptv_live_tv)
            XtreamContentType.VOD -> getString(R.string.iptv_movies)
            XtreamContentType.SERIES -> getString(R.string.iptv_series)
        }
        binding.textViewCategoryTitle.text = title

        val onSuccess: (List<XtreamCategory>) -> Unit = { categories ->
            mainHandler.post {
                binding.categoryProgressBar.visibility = View.GONE
                allXtreamCategories = categories
                filteredXtreamCategories = categories
                categoryAdapter.updateList(categories)
            }
        }

        val onFailure: (String) -> Unit = { error ->
            mainHandler.post {
                binding.categoryProgressBar.visibility = View.GONE
                Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
            }
        }

        when (type) {
            XtreamContentType.LIVE -> client.getLiveCategories(onSuccess, onFailure)
            XtreamContentType.VOD -> client.getVodCategories(onSuccess, onFailure)
            XtreamContentType.SERIES -> client.getSeriesCategories(onSuccess, onFailure)
        }
    }

    private fun loadXtreamStreamsByCategory(category: XtreamCategory?) {
        val client = xtreamApiClient ?: return
        val categoryId = category?.categoryId

        binding.categoryListContainer.visibility = View.GONE
        binding.channelsViewContainer.visibility = View.VISIBLE
        binding.textViewPlaylistTitle.text = category?.categoryName ?: getString(R.string.iptv_all_channels)

        // Show loading
        mainChannelAdapter.updateList(emptyList())

        when (currentContentType) {
            XtreamContentType.LIVE -> {
                client.getLiveStreams(categoryId,
                    onSuccess = { streams ->
                        mainHandler.post {
                            val channels = streams.map { stream ->
                                IptvChannel(
                                    name = stream.name,
                                    url = client.buildLiveStreamUrl(stream.streamId),
                                    logoUrl = stream.streamIcon,
                                    category = ""
                                )
                            }
                            allChannels = channels
                            setupChannelSpinnersForXtream(streams.map { it.categoryId }.distinct())
                            applyFilters()
                        }
                    },
                    onFailure = { error ->
                        mainHandler.post {
                            Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
            XtreamContentType.VOD -> {
                client.getVodStreams(categoryId,
                    onSuccess = { streams ->
                        mainHandler.post {
                            val channels = streams.map { vod ->
                                IptvChannel(
                                    name = vod.name,
                                    url = client.buildVodStreamUrl(vod.streamId, vod.containerExtension),
                                    logoUrl = vod.streamIcon,
                                    category = if (vod.rating.isNotEmpty()) "★ ${vod.rating}" else ""
                                )
                            }
                            allChannels = channels
                            setupChannelSpinnersForXtream(emptyList())
                            applyFilters()
                        }
                    },
                    onFailure = { error ->
                        mainHandler.post {
                            Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
            XtreamContentType.SERIES -> {
                client.getSeries(categoryId,
                    onSuccess = { seriesList ->
                        mainHandler.post {
                            val channels = seriesList.map { series ->
                                IptvChannel(
                                    name = series.name,
                                    url = "series:${series.seriesId}",
                                    logoUrl = series.cover,
                                    category = if (series.rating.isNotEmpty()) "★ ${series.rating}" else ""
                                )
                            }
                            allChannels = channels
                            setupChannelSpinnersForXtream(emptyList())
                            applyFilters()
                        }
                    },
                    onFailure = { error ->
                        mainHandler.post {
                            Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }
    }

    private fun setupChannelSpinnersForXtream(categoryIds: List<String>) {
        categories = listOf(getString(R.string.iptv_all_categories))
        val customSpinnerAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, categories) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                if (view is TextView) {
                    view.setTextColor(Color.WHITE)
                    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                }
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                if (view is TextView) {
                    view.setTextColor(Color.WHITE)
                    view.setBackgroundColor(Color.parseColor("#1C1C23"))
                }
                return view
            }
        }
        binding.spinnerCategoriesMain.adapter = customSpinnerAdapter
        binding.spinnerCategories.adapter = customSpinnerAdapter
        binding.editTextSearchChannelsMain.setText("")
        binding.editTextSearchChannels.setText("")
        binding.spinnerCategoriesMain.setSelection(0)
        binding.spinnerCategories.setSelection(0)
    }

    private fun loadSeriesDetail(seriesId: Int) {
        val client = xtreamApiClient ?: return

        binding.channelsViewContainer.visibility = View.GONE
        binding.seriesDetailContainer.visibility = View.VISIBLE
        binding.seriesDetailProgress.visibility = View.VISIBLE

        client.getSeriesInfo(seriesId,
            onSuccess = { seriesInfo ->
                mainHandler.post {
                    binding.seriesDetailProgress.visibility = View.GONE
                    currentSeriesInfo = seriesInfo
                    displaySeriesDetail(seriesInfo)
                }
            },
            onFailure = { error ->
                mainHandler.post {
                    binding.seriesDetailProgress.visibility = View.GONE
                    Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun displaySeriesDetail(info: XtreamSeriesInfo) {
        val series = info.seriesInfo
        binding.textViewSeriesTitle.text = series.name
        binding.textViewSeriesGenre.text = series.genre.ifEmpty { "N/A" }
        binding.textViewSeriesRating.text = if (series.rating.isNotEmpty()) "★ ${series.rating}" else ""
        binding.textViewSeriesPlot.text = series.plot.ifEmpty { "" }
        binding.textViewSeriesCast.text = series.cast.ifEmpty { "" }

        // Load series cover
        loadLogo(series.cover, binding.imageViewSeriesCover)

        // Setup season chips
        binding.chipGroupSeasons.removeAllViews()
        val sortedSeasons = info.seasons.sortedBy { it.seasonNumber }

        if (sortedSeasons.isEmpty() && info.episodes.isNotEmpty()) {
            // Some APIs return episodes without season metadata
            for (seasonKey in info.episodes.keys.sortedBy { it.toIntOrNull() ?: 0 }) {
                addSeasonChip(seasonKey, "Season $seasonKey", seasonKey == info.episodes.keys.first())
            }
        } else {
            for (season in sortedSeasons) {
                val seasonKey = season.seasonNumber.toString()
                addSeasonChip(seasonKey, season.name.ifEmpty { getString(R.string.iptv_season_label, season.seasonNumber) },
                    season == sortedSeasons.first())
            }
        }
    }

    private fun addSeasonChip(seasonKey: String, label: String, isChecked: Boolean) {
        val chip = Chip(this).apply {
            text = label
            isCheckable = true
            this.isChecked = isChecked
            setTextColor(Color.WHITE)
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                if (isChecked) Color.parseColor("#8B5CF6") else Color.parseColor("#2A2A3A")
            )
            setOnClickListener {
                // Update all chip colors
                for (i in 0 until binding.chipGroupSeasons.childCount) {
                    val c = binding.chipGroupSeasons.getChildAt(i) as? Chip
                    c?.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                        Color.parseColor("#2A2A3A")
                    )
                }
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#8B5CF6")
                )
                loadEpisodesForSeason(seasonKey)
            }
        }
        binding.chipGroupSeasons.addView(chip)

        if (isChecked) {
            loadEpisodesForSeason(seasonKey)
        }
    }

    private fun loadEpisodesForSeason(seasonKey: String) {
        currentSeasonKey = seasonKey
        val episodes = currentSeriesInfo?.episodes?.get(seasonKey) ?: emptyList()
        episodeAdapter.updateList(episodes)
    }

    private fun playEpisode(episode: XtreamEpisode) {
        val client = xtreamApiClient ?: return
        val streamUrl = client.buildSeriesStreamUrl(episode.id, episode.containerExtension)
        val channel = IptvChannel(
            name = episode.title.ifEmpty { "Episode ${episode.episodeNum}" },
            url = streamUrl,
            logoUrl = episode.coverBig,
            category = ""
        )
        playChannel(channel)
    }

    private fun applyCategoryFilter() {
        val query = binding.editTextSearchCategories.text.toString().trim().lowercase()
        filteredXtreamCategories = if (query.isEmpty()) {
            allXtreamCategories
        } else {
            allXtreamCategories.filter { it.categoryName.lowercase().contains(query) }
        }
        categoryAdapter.updateList(filteredXtreamCategories)
    }

    // --- ASYNC M3U DOWNLOAD & PARSING ---

    private fun downloadAndAddPlaylist(name: String, url: String) {
        binding.playlistProgressBar.visibility = View.VISIBLE
        binding.buttonAddPlaylist.isEnabled = false

        val playlistId = System.currentTimeMillis().toString()
        val request = Request.Builder().url(url).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    binding.playlistProgressBar.visibility = View.GONE
                    binding.buttonAddPlaylist.isEnabled = true
                    Toast.makeText(this@IptvActivity, getString(R.string.iptv_error_loading), Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    mainHandler.post {
                        binding.playlistProgressBar.visibility = View.GONE
                        binding.buttonAddPlaylist.isEnabled = true
                        Toast.makeText(this@IptvActivity, getString(R.string.iptv_error_loading), Toast.LENGTH_LONG).show()
                    }
                    return
                }

                val bodyContent = response.body?.string() ?: ""
                if (!bodyContent.contains("#EXTM3U")) {
                    mainHandler.post {
                        binding.playlistProgressBar.visibility = View.GONE
                        binding.buttonAddPlaylist.isEnabled = true
                        Toast.makeText(this@IptvActivity, "El enlace no parece ser una lista M3U válida", Toast.LENGTH_LONG).show()
                    }
                    return
                }

                val file = File(cacheDir, "iptv_playlist_$playlistId.m3u")
                try {
                    file.writeText(bodyContent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val testChannels = parseM3uFile(file)
                mainHandler.post {
                    binding.playlistProgressBar.visibility = View.GONE
                    binding.buttonAddPlaylist.isEnabled = true

                    if (testChannels.isEmpty()) {
                        Toast.makeText(this@IptvActivity, "No se encontraron canales válidos en la lista", Toast.LENGTH_SHORT).show()
                        if (file.exists()) file.delete()
                    } else {
                        playlists.add(IptvPlaylist(playlistId, name, url, PlaylistType.M3U))
                        savePlaylists()
                        Toast.makeText(this@IptvActivity, "Lista agregada con éxito (${testChannels.size} canales)", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun loadPlaylistChannels(playlist: IptvPlaylist) {
        currentXtreamPlaylist = null // Mark as M3U flow
        binding.playlistProgressBar.visibility = View.VISIBLE
        val file = File(cacheDir, "iptv_playlist_${playlist.id}.m3u")

        if (!file.exists()) {
            Toast.makeText(this, "Cargando desde servidor...", Toast.LENGTH_SHORT).show()
            reDownloadAndLoad(playlist)
            return
        }

        Thread {
            val channels = parseM3uFile(file)
            val uniqueCategories = channels.map { it.category }.distinct().filter { it.isNotEmpty() }.sorted()

            mainHandler.post {
                binding.playlistProgressBar.visibility = View.GONE
                if (channels.isEmpty()) {
                    Toast.makeText(this, "No se pudieron cargar los canales", Toast.LENGTH_SHORT).show()
                } else {
                    allChannels = channels
                    categories = listOf(getString(R.string.iptv_all_categories)) + uniqueCategories
                    
                    binding.textViewPlaylistTitle.text = playlist.name

                    // Premium Custom Dark Spinner Adapter
                    val customSpinnerAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, categories) {
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                            val view = super.getView(position, convertView, parent)
                            if (view is TextView) {
                                view.setTextColor(Color.WHITE)
                                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            }
                            return view
                        }

                        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                            val view = super.getDropDownView(position, convertView, parent)
                            if (view is TextView) {
                                view.setTextColor(Color.WHITE)
                                view.setBackgroundColor(Color.parseColor("#1C1C23"))
                            }
                            return view
                        }
                    }

                    // Setup spinners
                    binding.spinnerCategoriesMain.adapter = customSpinnerAdapter
                    binding.spinnerCategories.adapter = customSpinnerAdapter

                    // Transition to Channel Selection Dashboard (State B)
                    binding.playlistViewContainer.visibility = View.GONE
                    binding.channelsViewContainer.visibility = View.VISIBLE

                    // Clear active inputs
                    binding.editTextSearchChannelsMain.setText("")
                    binding.editTextSearchChannels.setText("")
                    binding.spinnerCategoriesMain.setSelection(0)
                    binding.spinnerCategories.setSelection(0)

                    applyFilters()
                }
            }
        }.start()
    }

    private fun reDownloadAndLoad(playlist: IptvPlaylist) {
        val request = Request.Builder().url(playlist.url).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    binding.playlistProgressBar.visibility = View.GONE
                    Toast.makeText(this@IptvActivity, "Error de red al descargar", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    mainHandler.post {
                        binding.playlistProgressBar.visibility = View.GONE
                        Toast.makeText(this@IptvActivity, "Error al descargar lista", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                val bodyContent = response.body?.string() ?: ""
                val file = File(cacheDir, "iptv_playlist_${playlist.id}.m3u")
                try {
                    file.writeText(bodyContent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                mainHandler.post {
                    loadPlaylistChannels(playlist)
                }
            }
        })
    }

    private fun parseM3uFile(file: File): List<IptvChannel> {
        val channels = mutableListOf<IptvChannel>()
        var currentName = ""
        var currentLogo = ""
        var currentGroup = ""

        try {
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXTINF:")) {
                        currentLogo = parseM3uAttribute(trimmed, "tvg-logo")
                        currentGroup = parseM3uAttribute(trimmed, "group-title")

                        val commaIndex = trimmed.lastIndexOf(',')
                        currentName = if (commaIndex != -1 && commaIndex < trimmed.length - 1) {
                            trimmed.substring(commaIndex + 1).trim()
                        } else {
                            ""
                        }
                    } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        if (currentName.isEmpty()) {
                            currentName = trimmed.substringAfterLast('/').substringBefore('?')
                        }
                        channels.add(IptvChannel(currentName, trimmed, currentLogo, currentGroup))
                        currentName = ""
                        currentLogo = ""
                        currentGroup = ""
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return channels
    }

    private fun parseM3uAttribute(line: String, attrName: String): String {
        val target = "$attrName="
        val startIdx = line.indexOf(target)
        if (startIdx == -1) return ""
        val valueStart = startIdx + target.length
        if (valueStart >= line.length) return ""

        val quoteChar = line[valueStart]
        if (quoteChar == '"' || quoteChar == '\'') {
            val nextQuote = line.indexOf(quoteChar, valueStart + 1)
            if (nextQuote != -1) {
                return line.substring(valueStart + 1, nextQuote)
            }
        } else {
            var endIdx = valueStart
            while (endIdx < line.length && line[endIdx] != ' ' && line[endIdx] != ',') {
                endIdx++
            }
            return line.substring(valueStart, endIdx)
        }
        return ""
    }

    // --- SEARCH & FILTER ---

    private fun applyFilters() {
        val allCatLabel = getString(R.string.iptv_all_categories)

        // 1. Filter Main Dashboard (State B)
        val queryMain = binding.editTextSearchChannelsMain.text.toString().trim().lowercase()
        val selectedCatMain = binding.spinnerCategoriesMain.selectedItem?.toString() ?: ""

        val filteredMain = allChannels.filter { channel ->
            val matchesSearch = channel.name.lowercase().contains(queryMain)
            val matchesCategory = (selectedCatMain == allCatLabel || channel.category == selectedCatMain)
            matchesSearch && matchesCategory
        }
        mainChannelAdapter.updateList(filteredMain)

        // 2. Filter Sidebar (State C Player)
        val querySidebar = binding.editTextSearchChannels.text.toString().trim().lowercase()
        val selectedCatSidebar = binding.spinnerCategories.selectedItem?.toString() ?: ""

        filteredChannels = allChannels.filter { channel ->
            val matchesSearch = channel.name.lowercase().contains(querySidebar)
            val matchesCategory = (selectedCatSidebar == allCatLabel || channel.category == selectedCatSidebar)
            matchesSearch && matchesCategory
        }
        channelAdapter.updateList(filteredChannels)
    }

    // --- SIDEBAR ANIMATION ---

    private fun openSidebar() {
        if (isSidebarOpen) return
        isSidebarOpen = true
        binding.channelsSidebar.visibility = View.VISIBLE
        binding.channelsSidebar.animate()
            .translationX(0f)
            .setDuration(300)
            .start()
    }

    private fun closeSidebar() {
        if (!isSidebarOpen) return
        isSidebarOpen = false
        binding.channelsSidebar.animate()
            .translationX(sidebarWidthPx)
            .setDuration(300)
            .withEndAction {
                binding.channelsSidebar.visibility = View.GONE
            }
            .start()
    }

    // --- PLAYBACK CONTROL ---

    private fun playChannel(channel: IptvChannel) {
        // Check if this is a series entry (special URL format)
        if (channel.url.startsWith("series:")) {
            val seriesId = channel.url.removePrefix("series:").toIntOrNull()
            if (seriesId != null) {
                loadSeriesDetail(seriesId)
                return
            }
        }

        binding.textViewPlayingChannel.text = channel.name
        binding.streamProgressBar.visibility = View.VISIBLE

        // Transition to Player View (State C)
        binding.channelsViewContainer.visibility = View.GONE
        binding.seriesDetailContainer.visibility = View.GONE
        binding.playerViewContainer.visibility = View.VISIBLE
        setFullScreen(true)

        // Initialize ExoPlayer if needed
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(this).build().apply {
                binding.exoPlayerView.player = this
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                binding.streamProgressBar.visibility = View.VISIBLE
                            }
                            else -> {
                                binding.streamProgressBar.visibility = View.GONE
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(this@IptvActivity, "Error al reproducir: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                        binding.streamProgressBar.visibility = View.GONE
                    }
                })
            }
        }

        // Set channel media stream
        val mediaItem = MediaItem.fromUri(channel.url)
        exoPlayer?.let { player ->
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
        }

        // Close sidebar on new channel play
        closeSidebar()
    }

    private fun stopPlayback() {
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null

        // Return to State B (Channel Selection)
        setFullScreen(false)
        binding.playerViewContainer.visibility = View.GONE
        binding.channelsViewContainer.visibility = View.VISIBLE
    }

    private fun setFullScreen(fullscreen: Boolean) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (fullscreen) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // --- ASYNC LOGO IMAGES LOADER ---

    private fun loadLogo(url: String, imageView: ImageView) {
        imageView.tag = url
        val cached = imageCache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageDrawable(null)
        if (url.isEmpty()) return

        imageLoaderExecutor.execute {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                connection.doInput = true
                connection.connect()

                if (connection.responseCode == 200) {
                    val input = connection.inputStream
                    val bitmap = BitmapFactory.decodeStream(input)
                    if (bitmap != null) {
                        imageCache.put(url, bitmap)
                        mainHandler.post {
                            if (imageView.tag == url) {
                                imageView.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Fail silently
            }
        }
    }

    // --- LIFECYCLE OVERRIDES ---

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
        xtreamApiClient?.shutdown()
        xtreamApiClient = null
        imageLoaderExecutor.shutdown()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            binding.playerViewContainer.visibility == View.VISIBLE -> {
                if (isSidebarOpen) {
                    closeSidebar()
                } else {
                    stopPlayback()
                }
            }
            binding.seriesDetailContainer.visibility == View.VISIBLE -> {
                binding.seriesDetailContainer.visibility = View.GONE
                binding.channelsViewContainer.visibility = View.VISIBLE
            }
            binding.channelsViewContainer.visibility == View.VISIBLE -> {
                binding.channelsViewContainer.visibility = View.GONE
                if (currentXtreamPlaylist != null) {
                    binding.categoryListContainer.visibility = View.VISIBLE
                } else {
                    binding.playlistViewContainer.visibility = View.VISIBLE
                }
            }
            binding.categoryListContainer.visibility == View.VISIBLE -> {
                binding.categoryListContainer.visibility = View.GONE
                binding.xtreamDashboardContainer.visibility = View.VISIBLE
            }
            binding.xtreamDashboardContainer.visibility == View.VISIBLE -> {
                binding.xtreamDashboardContainer.visibility = View.GONE
                binding.playlistViewContainer.visibility = View.VISIBLE
                currentXtreamPlaylist = null
                xtreamApiClient?.shutdown()
                xtreamApiClient = null
            }
            else -> {
                super.onBackPressed()
            }
        }
    }

    // --- RECYCLERVIEW DATA TYPES & ADAPTERS ---

    data class IptvPlaylist(
        val id: String,
        val name: String,
        val url: String,
        val type: PlaylistType = PlaylistType.M3U,
        val username: String? = null,
        val password: String? = null,
        val serverUrl: String? = null
    )

    data class IptvChannel(
        val name: String,
        val url: String,
        val logoUrl: String,
        val category: String
    )

    inner class PlaylistAdapter(
        private val list: List<IptvPlaylist>,
        private val onPlaylistClick: (IptvPlaylist) -> Unit,
        private val onDeleteClick: (IptvPlaylist) -> Unit
    ) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

        inner class PlaylistViewHolder(val itemBinding: ItemIptvPlaylistBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
            val itemBinding = ItemIptvPlaylistBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return PlaylistViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
            val playlist = list[position]
            holder.itemBinding.textViewPlaylistName.text = playlist.name

            // Set type badge
            if (playlist.type == PlaylistType.XTREAM) {
                holder.itemBinding.textViewPlaylistType.text = getString(R.string.iptv_type_xtream)
                holder.itemBinding.textViewPlaylistType.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#8B5CF6"))
                holder.itemBinding.textViewPlaylistUrl.text =
                    getString(R.string.iptv_user_label, playlist.username ?: "")
            } else {
                holder.itemBinding.textViewPlaylistType.text = getString(R.string.iptv_type_m3u)
                holder.itemBinding.textViewPlaylistType.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#1565C0"))
                holder.itemBinding.textViewPlaylistUrl.text = playlist.url
            }

            holder.itemBinding.root.setOnClickListener {
                onPlaylistClick(playlist)
            }
            holder.itemBinding.buttonDeletePlaylist.setOnClickListener {
                onDeleteClick(playlist)
            }
        }

        override fun getItemCount(): Int = list.size
    }

    inner class ChannelAdapter(
        private var list: List<IptvChannel>,
        private val onChannelClick: (IptvChannel) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

        inner class ChannelViewHolder(val itemBinding: ItemIptvChannelBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
            val itemBinding = ItemIptvChannelBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ChannelViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
            val channel = list[position]
            holder.itemBinding.textViewChannelName.text = channel.name
            holder.itemBinding.textViewChannelGroup.text = if (channel.category.isEmpty()) "General" else channel.category
            
            loadLogo(channel.logoUrl, holder.itemBinding.imageViewChannelLogo)

            holder.itemBinding.root.setOnClickListener {
                onChannelClick(channel)
            }
        }

        override fun getItemCount(): Int = list.size

        fun updateList(newList: List<IptvChannel>) {
            list = newList
            notifyDataSetChanged()
        }
    }

    inner class CategoryAdapter(
        private var list: List<XtreamCategory>,
        private val onCategoryClick: (XtreamCategory) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder>() {

        inner class CategoryViewHolder(val itemBinding: ItemIptvCategoryBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
            val itemBinding = ItemIptvCategoryBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return CategoryViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
            val category = list[position]
            holder.itemBinding.textViewCategoryName.text = category.categoryName
            holder.itemBinding.textViewCategoryCount.text = ""

            // Set icon based on content type
            val iconRes = when (currentContentType) {
                XtreamContentType.LIVE -> R.drawable.ic_live_tv_24px
                XtreamContentType.VOD -> R.drawable.ic_movie_24px
                XtreamContentType.SERIES -> R.drawable.ic_series_24px
            }
            holder.itemBinding.imageViewCategoryIcon.setImageResource(iconRes)

            holder.itemBinding.root.setOnClickListener {
                onCategoryClick(category)
            }
        }

        override fun getItemCount(): Int = list.size

        fun updateList(newList: List<XtreamCategory>) {
            list = newList
            notifyDataSetChanged()
        }
    }

    inner class EpisodeAdapter(
        private var list: List<XtreamEpisode>,
        private val onEpisodeClick: (XtreamEpisode) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.EpisodeViewHolder>() {

        inner class EpisodeViewHolder(val itemBinding: ItemIptvChannelBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
            val itemBinding = ItemIptvChannelBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return EpisodeViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
            val episode = list[position]
            val displayTitle = episode.title.ifEmpty { "Episode ${episode.episodeNum}" }
            holder.itemBinding.textViewChannelName.text = displayTitle
            holder.itemBinding.textViewChannelGroup.text = buildString {
                append("E${episode.episodeNum}")
                if (episode.duration.isNotEmpty()) {
                    append(" • ${episode.duration}")
                }
            }

            loadLogo(episode.coverBig, holder.itemBinding.imageViewChannelLogo)

            holder.itemBinding.root.setOnClickListener {
                onEpisodeClick(episode)
            }
        }

        override fun getItemCount(): Int = list.size

        fun updateList(newList: List<XtreamEpisode>) {
            list = newList
            notifyDataSetChanged()
        }
    }
}
