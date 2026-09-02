package com.themeslover.ytdapp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.themeslover.ytdapp.download.DownloadWorker
import com.themeslover.ytdapp.download.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestUsefulPermissions()
        setContent { AhDownloaderApp() }
    }

    private fun requestUsefulPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 1001)
    }

    private fun createDestination(title: String, kind: MediaKind, extension: String?): Uri? {
        val safe = title.replace(Regex("[^A-Za-z0-9._-]"), "_").take(90).ifBlank { "download" }
        val ext = extension?.lowercase()?.let { if (it.startsWith(".")) it else ".${it}" }
            ?: if (kind == MediaKind.AUDIO) ".m4a" else ".mp4"
        val display = if (safe.endsWith(ext, true)) safe else safe + ext
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
        var apiUrl by rememberSaveable {
            mutableStateOf(prefs.getString("api_url", "http://10.0.2.2:8000") ?: "http://10.0.2.2:8000")
        }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var query by rememberSaveable { mutableStateOf("") }
        var sourceUrl by rememberSaveable { mutableStateOf("") }
        var playlistUrl by rememberSaveable { mutableStateOf("") }
        var kind by rememberSaveable { mutableStateOf(MediaKind.VIDEO) }
        var quality by rememberSaveable { mutableStateOf("best") }
        var searching by remember { mutableStateOf(false) }
        var playlistLoading by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Ready") }
        var results by remember { mutableStateOf(emptyList<ApiClient.SearchItem>()) }
        var playingId by rememberSaveable { mutableStateOf<String?>(null) }
        var playingTitle by rememberSaveable { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        val api = remember { ApiClient() }
        val workManager = remember { WorkManager.getInstance(context) }
        val works = workManager.getWorkInfosByTagFlow("ytd-download").collectAsState(initial = emptyList()).value

        fun saveApi() {
            apiUrl = apiUrl.trim().removeSuffix("/")
            prefs.edit { putString("api_url", apiUrl) }
        }

        fun enqueue(url: String, title: String, extension: String?) {
            val output = createDestination(title, kind, extension)
            if (output == null) {
                status = "Could not create Downloads destination"
                return
            }
            val id = UUID.randomUUID().toString()
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(
                    workDataOf(
                        DownloadWorker.KEY_ID to id,
                        DownloadWorker.KEY_SOURCE to url,
                        DownloadWorker.KEY_TITLE to title,
                        DownloadWorker.KEY_OUTPUT to output.toString(),
                        DownloadWorker.KEY_KIND to kind.name,
                        DownloadWorker.KEY_QUALITY to quality
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("ytd-download")
                .build()
            workManager.enqueueUniqueWork("download-$id", ExistingWorkPolicy.KEEP, request)
        }

        fun retry(info: WorkInfo) {
            val data = info.progress
            val source = data.getString(DownloadWorker.KEY_SOURCE)
                ?: info.outputData.getString(DownloadWorker.KEY_SOURCE)
            if (source.isNullOrBlank()) {
                status = "Retry metadata is unavailable for this old download"
                return
            }
            val title = data.getString(DownloadWorker.KEY_TITLE)
                ?: info.outputData.getString(DownloadWorker.KEY_TITLE)
                ?: "Download"
            val output = data.getString(DownloadWorker.KEY_OUTPUT)
                ?: info.outputData.getString(DownloadWorker.KEY_OUTPUT)
            if (output.isNullOrBlank()) {
                status = "Retry destination is unavailable"
                return
            }
            val savedKind = data.getString(DownloadWorker.KEY_KIND)
                ?: info.outputData.getString(DownloadWorker.KEY_KIND)
                ?: kind.name
            val savedQuality = data.getString(DownloadWorker.KEY_QUALITY)
                ?: info.outputData.getString(DownloadWorker.KEY_QUALITY)
                ?: "best"
            val id = UUID.randomUUID().toString()
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(
                    workDataOf(
                        DownloadWorker.KEY_ID to id,
                        DownloadWorker.KEY_SOURCE to source,
                        DownloadWorker.KEY_TITLE to title,
                        DownloadWorker.KEY_OUTPUT to output,
                        DownloadWorker.KEY_KIND to savedKind,
                        DownloadWorker.KEY_QUALITY to savedQuality
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("ytd-download")
                .build()
            workManager.enqueueUniqueWork("download-$id", ExistingWorkPolicy.KEEP, request)
            status = "Retry queued"
        }

        fun download(url: String, title: String = "Download") {
            if (url.isBlank()) return
            saveApi()
            status = "Preparing download..."
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { api.resolve(apiUrl, url.trim(), kind.name, quality) }
                }.onSuccess { f ->
                    enqueue(f.url, title, f.ext)
                    status = "Queued: $title"
                    tab = 2
                }.onFailure {
                    status = "Download failed: ${it.message ?: "server error"}"
                }
            }
        }

        fun search() {
            if (query.isBlank()) return
            saveApi()
            searching = true
            status = "Searching YouTube..."
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { api.search(apiUrl, query.trim()) }
                }.onSuccess {
                    results = it
                    status = if (it.isEmpty()) "No results found" else "Found ${it.size} videos"
                }.onFailure {
                    results = emptyList()
                    status = "Search failed: ${it.message ?: "server error"}"
                }
                searching = false
            }
        }

        fun playlist() {
            if (playlistUrl.isBlank()) return
            saveApi()
            playlistLoading = true
            status = "Reading playlist..."
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { api.playlist(apiUrl, playlistUrl.trim()) }
                }.onSuccess { items ->
                    val limited = items.take(200)
                    var queued = 0
                    for (item in limited) {
                        runCatching {
                            withContext(Dispatchers.IO) { api.resolve(apiUrl, item.url, kind.name, quality) }
                        }.onSuccess { f ->
                            enqueue(f.url, item.title, f.ext)
                            queued++
                        }
                    }
                    status = if (items.isEmpty()) "Playlist is empty" else "Queued $queued of ${limited.size} videos"
                    if (queued > 0) tab = 2
                }.onFailure {
                    status = "Playlist failed: ${it.message ?: "server error"}"
                }
                playlistLoading = false
            }
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
                    navigationIcon = {
                        if (playingId != null) {
                            IconButton(onClick = { playingId = null }) {
                                Icon(Icons.Default.ArrowBack, "Back")
                            }
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    listOf(
                        "Home" to Icons.Default.Home,
                        "Search" to Icons.Default.Search,
                        "Downloads" to Icons.Default.Download,
                        "Settings" to Icons.Default.Settings
                    ).forEachIndexed { i, item ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(item.second, item.first) },
                            label = { Text(item.first) }
                        )
                    }
                }
            }
        ) { padding ->
            if (playingId != null) {
                PlayerScreen(playingId!!, playingTitle, Modifier.padding(padding))
            } else {
                when (tab) {
                    0 -> HomeScreen(
                        query, { query = it }, searching, { search() }, results,
                        { item -> playingId = extractVideoId(item.url); playingTitle = item.title },
                        { item -> download(item.url, item.title) }, sourceUrl, { sourceUrl = it },
                        kind, { kind = it }, quality, { quality = it }, status,
                        { download(sourceUrl) }, Modifier.padding(padding)
                    )
                    1 -> SearchScreen(
                        query, { query = it }, searching, { search() }, results,
                        { item -> playingId = extractVideoId(item.url); playingTitle = item.title },
                        { item -> download(item.url, item.title) }, Modifier.padding(padding)
                    )
                    2 -> DownloadsScreen(
                        works, playlistUrl, { playlistUrl = it }, playlistLoading, { playlist() }, status,
                        { info -> workManager.cancelWorkById(info.id); status = "Download cancelled" },
                        { info -> retry(info) },
                        { info -> workManager.cancelWorkById(info.id); status = "Download removed" },
                        Modifier.padding(padding)
                    )
                    else -> SettingsScreen(apiUrl, { apiUrl = it }, { saveApi() }, status, Modifier.padding(padding))
                }
            }
        }
    }

    @Composable
    private fun HomeScreen(
        query: String, onQuery: (String) -> Unit, searching: Boolean, onSearch: () -> Unit,
        results: List<ApiClient.SearchItem>, onPlay: (ApiClient.SearchItem) -> Unit,
        onDownload: (ApiClient.SearchItem) -> Unit, source: String, onSource: (String) -> Unit,
        kind: MediaKind, onKind: (MediaKind) -> Unit, quality: String, onQuality: (String) -> Unit,
        status: String, onDownloadUrl: () -> Unit, modifier: Modifier
    ) {
        LazyColumn(
            modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Search, watch and download", style = MaterialTheme.typography.headlineSmall)
                Text("Search YouTube or paste a media URL.")
            }
            item { SearchBar(query, onQuery, searching, onSearch) }
            items(results.take(8), key = { it.url }) { ResultCard(it, onPlay, onDownload) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Download a link", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            source, onSource, Modifier.fillMaxWidth(),
                            label = { Text("YouTube or direct media URL") }, singleLine = true
                        )
                        OptionButtons(kind, onKind, quality, onQuality)
                        Button(
                            onClick = onDownloadUrl,
                            enabled = source.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, null)
                            Spacer(Modifier.size(6.dp))
                            Text("Download")
                        }
                    }
                }
            }
            item { AssistChip(onClick = {}, label = { Text(status) }) }
        }
    }

    @Composable
    private fun SearchScreen(
        query: String, onQuery: (String) -> Unit, searching: Boolean, onSearch: () -> Unit,
        results: List<ApiClient.SearchItem>, onPlay: (ApiClient.SearchItem) -> Unit,
        onDownload: (ApiClient.SearchItem) -> Unit, modifier: Modifier
    ) {
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("YouTube Search", style = MaterialTheme.typography.headlineSmall)
            SearchBar(query, onQuery, searching, onSearch)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.url }) { ResultCard(it, onPlay, onDownload) }
            }
        }
    }

    @Composable
    private fun SearchBar(query: String, onQuery: (String) -> Unit, searching: Boolean, onSearch: () -> Unit) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                query, onQuery, Modifier.weight(1f),
                label = { Text("Search YouTube") }, singleLine = true
            )
            Button(onClick = onSearch, enabled = query.isNotBlank() && !searching) {
                if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Search, "Search")
            }
        }
    }

    @Composable
    private fun ResultCard(
        item: ApiClient.SearchItem,
        onPlay: (ApiClient.SearchItem) -> Unit,
        onDownload: (ApiClient.SearchItem) -> Unit
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.channel?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                item.duration?.let { Text(formatDuration(it), style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPlay(item) }) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.size(4.dp))
                        Text("Play")
                    }
                    Button(onClick = { onDownload(item) }) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.size(4.dp))
                        Text("Download")
                    }
                }
            }
        }
    }

    @Composable
    private fun OptionButtons(
        kind: MediaKind, onKind: (MediaKind) -> Unit,
        quality: String, onQuality: (String) -> Unit
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onKind(if (kind == MediaKind.VIDEO) MediaKind.AUDIO else MediaKind.VIDEO) },
                Modifier.weight(1f)
            ) { Text(kind.name) }
            OutlinedButton(onClick = { onQuality(nextQuality(quality)) }, Modifier.weight(1f)) {
                Text("Quality: $quality")
            }
        }
    }

    private fun nextQuality(value: String): String = when (value) {
        "best" -> "1080"
        "1080" -> "720"
        "720" -> "480"
        "480" -> "360"
        else -> "best"
    }

    @Composable
    private fun DownloadsScreen(
        works: List<WorkInfo>, playlistUrl: String, onPlaylist: (String) -> Unit,
        loading: Boolean, onDownload: () -> Unit, status: String,
        onCancel: (WorkInfo) -> Unit, onRetry: (WorkInfo) -> Unit,
        onRemove: (WorkInfo) -> Unit, modifier: Modifier
    ) {
        val active = works.count { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        val completed = works.count { it.state == WorkInfo.State.SUCCEEDED }
        val failed = works.count { it.state == WorkInfo.State.FAILED }
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Downloads", style = MaterialTheme.typography.headlineSmall)
            Text("Active: $active   Completed: $completed   Failed: $failed")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Queue playlist", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        playlistUrl, onPlaylist, Modifier.fillMaxWidth(),
                        label = { Text("Playlist URL") }, singleLine = true
                    )
                    Button(onClick = onDownload, enabled = playlistUrl.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth()) {
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text("Queue playlist")
                    }
                }
            }
            AssistChip(onClick = {}, label = { Text(status) })
            if (works.isEmpty()) {
                Text("No downloads yet.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(works, key = { it.id.toString() }) { info ->
                        DownloadWorkCard(info, onCancel, onRetry, onRemove)
                    }
                }
            }
        }
    }

    @Composable
    private fun DownloadWorkCard(
        info: WorkInfo,
        onCancel: (WorkInfo) -> Unit,
        onRetry: (WorkInfo) -> Unit,
        onRemove: (WorkInfo) -> Unit
    ) {
        val progress = info.progress
        val title = progress.getString(DownloadWorker.KEY_TITLE)
            ?: info.outputData.getString(DownloadWorker.KEY_TITLE)
            ?: "Download"
        val percent = progress.getInt("percent", 0).coerceIn(0, 100)
        val error = info.outputData.getString("error")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(info.state.name)
                if (info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(if (percent > 0) "$percent%" else "Queued")
                    OutlinedButton(onClick = { onCancel(info) }) { Text("Cancel") }
                } else if (info.state == WorkInfo.State.FAILED) {
                    Text(error ?: "Download failed", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onRetry(info) }) { Text("Retry") }
                        OutlinedButton(onClick = { onRemove(info) }) { Text("Remove") }
                    }
                } else if (info.state == WorkInfo.State.SUCCEEDED) {
                    Text("Saved successfully")
                    OutlinedButton(onClick = { onRemove(info) }) { Text("Remove") }
                } else {
                    OutlinedButton(onClick = { onRemove(info) }) { Text("Remove") }
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen(
        apiUrl: String, onApiUrl: (String) -> Unit,
        onSave: () -> Unit, status: String, modifier: Modifier
    ) {
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                apiUrl, onApiUrl, Modifier.fillMaxWidth(),
                label = { Text("API server URL") }, singleLine = true
            )
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            Text("The Android app connects to the local AH Downloader API.")
            AssistChip(onClick = {}, label = { Text(status) })
        }
    }

    @Composable
    private fun PlayerScreen(videoId: String, title: String, modifier: Modifier) {
        Column(modifier.fillMaxSize()) {
            Text(title, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        loadUrl("https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0")
                    }
                }
            )
        }
    }

    private fun extractVideoId(url: String): String {
        val value = url.trim()
        Regex("(?:v=|youtu\\.be/|youtube\\.com/embed/)([A-Za-z0-9_-]{6,})").find(value)?.groupValues?.get(1)?.let { return it }
        return value.substringAfterLast('/').substringBefore('?').substringBefore('&')
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
