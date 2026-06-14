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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.kododake.aavideo.databinding.ActivityIptvBinding
import com.kododake.aavideo.databinding.DialogAddIptvBinding
import com.kododake.aavideo.databinding.ItemIptvChannelBinding
import com.kododake.aavideo.databinding.ItemIptvPlaylistBinding
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

    // Adapters
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var mainChannelAdapter: ChannelAdapter // State B Adapter
    private lateinit var channelAdapter: ChannelAdapter     // State C Sidebar Adapter

    // Sidebar state
    private var isSidebarOpen = false
    private var sidebarWidthPx = 0f

    // SharedPreferences constants
    private val PREFS_NAME = "iptv_prefs"
    private val KEY_PLAYLISTS = "playlists_json"

    // ImageLoader LruCache
    private val imageCache = LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt())
    private val imageLoaderExecutor = Executors.newFixedThreadPool(4)

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
                loadPlaylistChannels(playlist)
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

        // Add Playlist Button
        binding.buttonAddPlaylist.setOnClickListener {
            showAddPlaylistDialog()
        }

        // Back button in management view (State A to MainActivity)
        binding.buttonBackToMain.setOnClickListener {
            finish()
        }

        // Back button in channel selection (State B to State A)
        binding.buttonBackToPlaylists.setOnClickListener {
            binding.channelsViewContainer.visibility = View.GONE
            binding.playlistViewContainer.visibility = View.VISIBLE
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
                playlists.add(
                    IptvPlaylist(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        url = obj.getString("url")
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

        dialogBinding.buttonCancelAdd.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.buttonConfirmAdd.setOnClickListener {
            val name = dialogBinding.editTextPlaylistName.text.toString().trim()
            val url = dialogBinding.editTextPlaylistUrl.text.toString().trim()

            if (name.isEmpty() || url.isEmpty()) {
                Toast.makeText(this, "Por favor completa todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.dismiss()
            downloadAndAddPlaylist(name, url)
        }

        dialog.show()
    }

    private fun showDeletePlaylistDialog(playlist: IptvPlaylist) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.iptv_delete_confirm))
            .setMessage(getString(R.string.iptv_delete_confirm_msg))
            .setNegativeButton(getString(R.string.iptv_btn_cancel), null)
            .setPositiveButton(getString(R.string.bookmark_delete)) { _, _ ->
                val file = File(cacheDir, "iptv_playlist_${playlist.id}.m3u")
                if (file.exists()) {
                    file.delete()
                }
                playlists.remove(playlist)
                savePlaylists()
                Toast.makeText(this, "Lista eliminada", Toast.LENGTH_SHORT).show()
            }
            .show()
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
                        playlists.add(IptvPlaylist(playlistId, name, url))
                        savePlaylists()
                        Toast.makeText(this@IptvActivity, "Lista agregada con éxito (${testChannels.size} canales)", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun loadPlaylistChannels(playlist: IptvPlaylist) {
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
        binding.textViewPlayingChannel.text = channel.name
        binding.streamProgressBar.visibility = View.VISIBLE

        // Transition from Selection Dashboard (State B) to Player View (State C)
        binding.channelsViewContainer.visibility = View.GONE
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
        imageLoaderExecutor.shutdown()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.playerViewContainer.visibility == View.VISIBLE) {
            if (isSidebarOpen) {
                closeSidebar()
            } else {
                stopPlayback()
            }
        } else if (binding.channelsViewContainer.visibility == View.VISIBLE) {
            binding.channelsViewContainer.visibility = View.GONE
            binding.playlistViewContainer.visibility = View.VISIBLE
        } else {
            super.onBackPressed()
        }
    }

    // --- RECYCLERVIEW DATA TYPES & ADAPTERS ---

    data class IptvPlaylist(
        val id: String,
        val name: String,
        val url: String
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
            holder.itemBinding.textViewPlaylistUrl.text = playlist.url
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
}
