package com.themeslover.ytdapp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.themeslover.ytdapp.download.DownloadWorker
import com.themeslover.ytdapp.download.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestUsefulPermissions()
        setContent { AhDownloaderApp() }
    }

    private fun requestUsefulPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 1001)
    }

    private fun createDestination(title: String, kind: MediaKind, extension: String?): Uri? {
        val safe = title.replace(Regex("[^A-Za-z0-9._-]"), "_").take(90).ifBlank { "download" }
        val ext = extension?.lowercase()?.let { if (it.startsWith(".")) it else ".${it}" }
            ?: if (kind == MediaKind.AUDIO) ".m4a" else ".mp4"
        val display = if (safe.endsWith(ext, ignoreCase = true)) safe else safe + ext
        val mime = when (ext) {
            ".m4a" -> "audio/mp4"
            ".webm" -> if (kind == MediaKind.AUDIO) "audio/webm" else "video/webm"
            ".mp3" -> "audio/mpeg"
            else -> if (kind == MediaKind.AUDIO) "audio/*" else "video/mp4"
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, display)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AH Downloader")
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            Uri.fromFile(File(dir, display))
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AhDownloaderApp() {
        val context = LocalContext.current
        val prefs = remember { getPreferences(MODE_PRIVATE) }
        var apiUrl by rememberSaveable { mutableStateOf(prefs.getString("api_url", "http://10.0.2.2:8000") ?: "http://10.0.2.2:8000") }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var query by rememberSaveable { mutableStateOf("") }
        var sourceUrl by rememberSaveable { mutableStateOf("") }
        var playlistUrl by rememberSaveable { mutableStateOf("") }
        var kind by rememberSaveable { mutableStateOf(MediaKind.VIDEO) }
        var quality by rememberSaveable { mutableStateOf("best") }
        var qualityExpanded by remember { mutableStateOf(false) }
        var typeExpanded by remember { mutableStateOf(false) }
        var searching by remember { mutableStateOf(false) }
        var playlistLoading by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Ready") }
        var results by remember { mutableStateOf(emptyList<ApiClient.SearchItem>()) }
        var playlistItems by remember { mutableStateOf(emptyList<ApiClient.PlaylistItem>()) }
        var playingUrl by rememberSaveable { mutableStateOf<String?>(null) }
        var playingTitle by rememberSaveable { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        val api = remember { ApiClient() }
        val workManager = remember { WorkManager.getInstance(context) }
        val works = workManager.getWorkInfosByTagFlow("ytd-download").collectAsState(initial = emptyList()).value

        fun saveApiUrl() {
            prefs.edit().putString("api_url", apiUrl.trim().removeSuffix("/")).apply()
            apiUrl = apiUrl.trim().removeSuffix("/")
        }

        fun enqueueDirect(url: String, title: String, extension: String? = null) {
            val output = createDestination(title, kind, extension)
            if (output == null) {
                status = "Could not create the Downloads destination"
                return
            }
            val id = UUID.randomUUID().toString()
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(
                    DownloadWorker.KEY_ID to id,
                    DownloadWorker.KEY_SOURCE to url,
                    DownloadWorker.KEY_TITLE to title,
                    DownloadWorker.KEY_OUTPUT to output.toString(),
                    DownloadWorker.KEY_KIND to kind.name,
                    DownloadWorker.KEY_QUALITY to quality
                ))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("ytd-download")
                .build()
            workManager.enqueueUniqueWork("download-$id", ExistingWorkPolicy.KEEP, request)
        }

        fun resolveAndDownload(url: String, fallbackTitle: String = "Download") {
            status = "Preparing download..."
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { api.resolve(apiUrl, url, kind.name, quality) } }
                    .onSuccess { format ->
                        enqueueDirect(format.url, fallbackTitle, format.ext)
                        status = "Queued: $fallbackTitle"
                        tab = 2
                    }
                    .onFailure { status = "Download failed: ${it.message ?: "server error"}" }
            }
        }

        fun searchYouTube() {
            saveApiUrl()
            if (query.isBlank()) return
            searching = true
            status = "Searching YouTube..."
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { api.search(apiUrl, query.trim()) } }
                    .onSuccess {
                        results = it
                        status = if (it.isEmpty()) "No results found" else "Found ${it.size} videos"
                    }
                    .onFailure {
                        results = emptyList()
                        status = "Search failed: ${it.message ?: "server error"}"
                    }
                searching = false
            }
        }

        fun downloadPlaylist() {
            saveApiUrl()
            if (playlistUrl.isBlank()) return
            playlistLoading = true
            status = "Reading playlist..."
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { api.playlist(apiUrl, playlistUrl.trim()) } }
                    .onSuccess { items ->
                        playlistItems = items
                        if (items.isEmpty()) {
                            status = "Playlist is empty"
                        } else {
                            status = "Found ${items.size} playlist videos. Queuing..."
                            var queued = 0
                            items.take(200).forEach { item ->
                                runCatching {
                                    withContext(Dispatchers.IO) { api.resolve(apiUrl, item.url, kind.name, quality) }
                                }.onSuccess { format ->
                                    enqueueDirect(format.url, item.title, format.ext)
                                    queued++
                                }
                            }
                            status = "Queued $queued of ${minOf(items.size, 200)} videos"
                            tab = 2
                        }
                    }
                    .onFailure { status = "Playlist failed: ${it.message ?: "server error"}" }
                playlistLoading = false
            }
        }

        fun play(item: ApiClient.SearchItem) {
            val id = extractVideoId(item.url)
            if (id == null) {
                status = "This result cannot be embedded"
                return
            }
            playingTitle = item.title
            playingUrl = id
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Download, null, Modifier.size(28.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("AH Downloader")
                        }
                    },
                    navigationIcon = if (playingUrl != null) {
                        { IconButton(onClick = { playingUrl = null }) { Icon(Icons.Default.ArrowBack, "Back") } }
                    } else null
                )
            },
            bottomBar = {
                NavigationBar {
                    listOf(
                        "Home" to Icons.Default.Home,
                        "Search" to Icons.Default.Search,
                        "Downloads" to Icons.Default.Download,
                        "Settings" to Icons.Default.Settings
                    ).forEachIndexed { index, pair ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(pair.second, pair.first) },
                            label = { Text(pair.first) }
                        )
                    }
                }
            }
        ) { padding ->
            if (playingUrl != null) {
                PlayerScreen(playingUrl!!, playingTitle, Modifier.padding(padding))
            } else when (tab) {
                0 -> HomeScreen(
                    query = query,
                    onQueryChange = { query = it },
                    searching = searching,
                    onSearch = { searchYouTube() },
                    results = results,
                    onPlay = { play(it) },
                    onDownload = { resolveAndDownload(it.url, it.title) },
                    sourceUrl = sourceUrl,
                    onSourceChange = { sourceUrl = it },
                    kind = kind,
                    onKindChange = { kind = it },
                    quality = quality,
                    onQualityChange = { quality = it },
                    typeExpanded = typeExpanded,
                    onTypeExpanded = { typeExpanded = it },
                    qualityExpanded = qualityExpanded,
                    onQualityExpanded = { qualityExpanded = it },
                    onDownloadUrl = { resolveAndDownload(sourceUrl, "Download") },
                    status = status,
                    modifier = Modifier.padding(padding)
                )
                1 -> SearchScreen(
                    query = query,
                    onQueryChange = { query = it },
                    searching = searching,
                    onSearch = { searchYouTube() },
                    results = results,
                    onPlay = { play(it) },
                    onDownload = { resolveAndDownload(it.url, it.title) },
                    modifier = Modifier.padding(padding)
                )
                2 -> DownloadsScreen(works, playlistUrl, { playlistUrl = it }, playlistLoading, { downloadPlaylist() }, status, Modifier.padding(padding))
                else -> SettingsScreen(apiUrl, { apiUrl = it }, { saveApiUrl() }, status, Modifier.padding(padding))
            }
        }
    }

    @Composable
    private fun HomeScreen(
        query: String,
        onQueryChange: (String) -> Unit,
        searching: Boolean,
        onSearch: () -> Unit,
        results: List<ApiClient.SearchItem>,
        onPlay: (ApiClient.SearchItem) -> Unit,
        onDownload: (ApiClient.SearchItem) -> Unit,
        sourceUrl: String,
        onSourceChange: (String) -> Unit,
        kind: MediaKind,
        onKindChange: (MediaKind) -> Unit,
        quality: String,
        onQualityChange: (String) -> Unit,
        typeExpanded: Boolean,
        onTypeExpanded: (Boolean) -> Unit,
        qualityExpanded: Boolean,
        onQualityExpanded: (Boolean) -> Unit,
        onDownloadUrl: () -> Unit,
        status: String,
        modifier: Modifier = Modifier
    ) {
        LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)) {
            item {
                Text("Search, watch and download", style = MaterialTheme.typography.headlineSmall)
                Text("YouTube search is built in. Pick a result to play it here or queue it for download.", style = MaterialTheme.typography.bodyMedium)
            }
            item {
                SearchBar(query, onQueryChange, searching, onSearch)
            }
            if (results.isNotEmpty()) {
                item { Text("Results", style = MaterialTheme.typography.titleLarge) }
                items(results.take(8), key = { it.url }) { item -> ResultCard(item, onPlay, onDownload) }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Download a YouTube link", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(sourceUrl, onSourceChange, Modifier.fillMaxWidth(), label = { Text("YouTube or direct media URL") }, singleLine = true)
                        DownloadOptions(kind, onKindChange, quality, onQualityChange, typeExpanded, onTypeExpanded, qualityExpanded, onQualityExpanded)
                        Button(onClick = onDownloadUrl, enabled = sourceUrl.isNotBlank(), Modifier.fillMaxWidth()) { Icon(Icons.Default.Download, null); Spacer(Modifier.size(6.dp)); Text("Download") }
                    }
                }
            }
            item {
                AssistChip(onClick = {}, label = { Text(status) }, leadingIcon = { Icon(Icons.Default.LibraryMusic, null) })
            }
        }
    }

    @Composable
    private fun SearchScreen(query: String, onQueryChange: (String) -> Unit, searching: Boolean, onSearch: () -> Unit, results: List<ApiClient.SearchItem>, onPlay: (ApiClient.SearchItem) -> Unit, onDownload: (ApiClient.SearchItem) -> Unit, modifier: Modifier = Modifier) {
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("YouTube Search", style = MaterialTheme.typography.headlineSmall)
            SearchBar(query, onQueryChange, searching, onSearch)
            if (results.isEmpty() && !searching) Text("Search for a video, channel topic or song.", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.url }) { item -> ResultCard(item, onPlay, onDownload) }
            }
        }
    }

    @Composable
    private fun SearchBar(query: String, onQueryChange: (String) -> Unit, searching: Boolean, onSearch: () -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, onQueryChange, Modifier.weight(1f), label = { Text("Search YouTube") }, singleLine = true)
            Button(onClick = onSearch, enabled = query.isNotBlank() && !searching) {
                if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, "Search")
            }
        }
    }

    @Composable
    private fun ResultCard(item: ApiClient.SearchItem, onPlay: (ApiClient.SearchItem) -> Unit, onDownload: (ApiClient.SearchItem) -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.duration?.let { Text(formatDuration(it), style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPlay(item) }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.size(4.dp)); Text("Play") }
                    Button(onClick = { onDownload(item) }) { Icon(Icons.Default.Download, null); Spacer(Modifier.size(4.dp)); Text("Download") }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DownloadOptions(kind: MediaKind, onKindChange: (MediaKind) -> Unit, quality: String, onQualityChange: (String) -> Unit, typeExpanded: Boolean, onTypeExpanded: (Boolean) -> Unit, qualityExpanded: Boolean, onQualityExpanded: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposedDropdownMenuBox(typeExpanded, { onTypeExpanded(!typeExpanded) }, Modifier.weight(1f)) {
                OutlinedTextField(kind.name, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) })
                ExposedDropdownMenu(typeExpanded, { onTypeExpanded(false) }) {
                    MediaKind.entries.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { onKindChange(item); onTypeExpanded(false) }) }
                }
            }
            ExposedDropdownMenuBox(qualityExpanded, { onQualityExpanded(!qualityExpanded) }, Modifier.weight(1f)) {
                OutlinedTextField(quality, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Quality") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(qualityExpanded) })
                ExposedDropdownMenu(qualityExpanded, { onQualityExpanded(false) }) {
                    listOf("best", "360", "480", "720", "1080").forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { onQualityChange(item); onQualityExpanded(false) }) }
                }
            }
        }
    }

    @Composable
    private fun DownloadsScreen(works: List<androidx.work.WorkInfo>, playlistUrl: String, onPlaylistUrl: (String) -> Unit, loading: Boolean, onDownloadPlaylist: () -> Unit, status: String, modifier: Modifier = Modifier) {
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Downloads & Playlists", style = MaterialTheme.typography.headlineSmall)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bulk playlist download", style = MaterialTheme.typography.titleMedium)
                    Text("Paste a public YouTube playlist URL. Up to 200 entries are queued separately.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(playlistUrl, onPlaylistUrl, Modifier.fillMaxWidth(), label = { Text("Playlist URL") }, singleLine = true)
                    Button(onClick = onDownloadPlaylist, enabled = playlistUrl.isNotBlank() && !loading, Modifier.fillMaxWidth()) {
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Download, null)
                        Spacer(Modifier.size(6.dp)); Text(if (loading) "Preparing..." else "Download Playlist")
                    }
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            Text("Queue", style = MaterialTheme.typography.titleLarge)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(works, key = { _, info -> info.id }) { _, info ->
                    val percent = info.progress.getInt("percent", 0)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(info.outputData.getString("outputUri") ?: info.id.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(info.state.name + if (percent > 0) " • $percent%" else "")
                            info.outputData.getString("error")?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen(apiUrl: String, onApiUrl: (String) -> Unit, onSave: () -> Unit, status: String, modifier: Modifier = Modifier) {
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(apiUrl, onApiUrl, Modifier.fillMaxWidth(), label = { Text("API server URL") }, singleLine = true)
            Button(onClick = onSave, Modifier.fillMaxWidth()) { Text("Save server") }
            Text("Emulator: http://10.0.2.2:8000", style = MaterialTheme.typography.bodySmall)
            Text("Physical phone: use your PC's LAN address, for example http://192.168.1.10:8000.", style = MaterialTheme.typography.bodySmall)
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun PlayerScreen(videoId: String, title: String, modifier: Modifier = Modifier) {
        Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Card(Modifier.fillMaxWidth().height(230.dp), shape = RoundedCornerShape(12.dp)) {
                AndroidWebView(videoId)
            }
            Text("Playback uses YouTube's embedded player. Videos that do not allow embedding may not play here.", style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun AndroidWebView(videoId: String) {
        val url = "https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0"
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    loadUrl(url)
                }
            },
            update = { it.loadUrl(url) }
        )
    }

    private fun extractVideoId(url: String): String? = runCatching {
        val uri = Uri.parse(url)
        when {
            uri.host.equals("youtu.be", true) -> uri.pathSegments.firstOrNull()
            uri.getQueryParameter("v") != null -> uri.getQueryParameter("v")
            uri.pathSegments.firstOrNull() == "shorts" -> uri.pathSegments.getOrNull(1)
            uri.pathSegments.firstOrNull() == "embed" -> uri.pathSegments.getOrNull(1)
            else -> null
        }
    }.getOrNull()?.takeIf { it.length in 8..20 }

    private fun formatDuration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
