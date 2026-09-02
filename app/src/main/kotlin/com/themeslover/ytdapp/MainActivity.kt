package com.themeslover.ytdapp

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var url by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ApiClient.SearchItem>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var audio by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf("best") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val api = remember { ApiClient() }
    val wm = remember { WorkManager.getInstance(context) }
    val works by wm.getWorkInfosByTagFlow("ytd-download").collectAsState(initial = emptyList())

    fun queue(title: String, source: String) {
        if (source.isBlank()) return
        val kind = if (audio) MediaKind.AUDIO else MediaKind.VIDEO
        val destination = createDestination(context, title, audio)
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(
                DownloadWorker.KEY_ID to UUID.randomUUID().toString(),
                DownloadWorker.KEY_SOURCE to source,
                DownloadWorker.KEY_TITLE to title.ifBlank { "AH Downloader" },
                DownloadWorker.KEY_OUTPUT to destination.toString(),
                DownloadWorker.KEY_KIND to kind.name,
                DownloadWorker.KEY_QUALITY to quality
            ))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("ytd-download")
            .build()
        wm.enqueueUniqueWork("download-${request.id}", ExistingWorkPolicy.KEEP, request)
        message = "Added to download queue"
        tab = 2
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("AH Downloader") }) },
        bottomBar = {
            NavigationBar {
                listOf("Home", "Search", "Downloads", "Library", "Settings").forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(if (index == 0) Icons.Default.Home else if (index == 1) Icons.Default.Search else if (index == 2) Icons.Default.Download else if (index == 3) Icons.Default.Folder else Icons.Default.Settings, label) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        when (tab) {
            0 -> HomeScreen(
                modifier = Modifier.padding(padding), url = url, onUrl = { url = it }, audio = audio,
                onAudio = { audio = it }, quality = quality, onQuality = { quality = it },
                onDownload = { queue("AH Downloader", url.trim()) }, onSearch = { tab = 1 }, message = message
            )
            1 -> SearchScreen(
                modifier = Modifier.padding(padding), query = query, onQuery = { query = it }, results = results,
                busy = busy, onSearch = {
                    if (query.isNotBlank()) scope.launch {
                        busy = true; message = "Searching…"
                        results = withContext(Dispatchers.IO) { api.search("http://10.0.2.2:8000", query.trim(), 30) }
                        busy = false; message = if (results.isEmpty()) "No results" else "${results.size} results"
                    }
                }, onDownload = { item -> queue(item.title, item.url) }, onPlay = { openBrowser(context, it.url) }, message = message
            )
            2 -> DownloadsScreen(modifier = Modifier.padding(padding), works = works, wm = wm)
            3 -> LibraryScreen(modifier = Modifier.padding(padding), onOpenDownloads = { tab = 2 })
            else -> SettingsScreen(modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier, url: String, onUrl: (String) -> Unit, audio: Boolean, onAudio: (Boolean) -> Unit, quality: String, onQuality: (String) -> Unit, onDownload: () -> Unit, onSearch: () -> Unit, message: String) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Fast. Private. No PC required.", style = MaterialTheme.typography.headlineSmall)
        Text("Paste a supported public media URL. AH Downloader runs the download engine on your Android device.")
        OutlinedTextField(url, onUrl, Modifier.fillMaxWidth(), label = { Text("Video or playlist URL") }, leadingIcon = { Icon(Icons.Default.Link, null) }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onAudio(false) }) { Text(if (!audio) "✓ Video" else "Video") }
            OutlinedButton(onClick = { onAudio(true) }) { Text(if (audio) "✓ Audio" else "Audio") }
            OutlinedButton(onClick = { onQuality(nextQuality(quality)) }) { Text(qualityLabel(quality)) }
        }
        Button(onClick = onDownload, enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Download, null); Spacer(Modifier.height(1.dp)); Text("  Download")
        }
        OutlinedButton(onClick = onSearch, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Search, null); Text("  Search videos") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SearchScreen(modifier: Modifier, query: String, onQuery: (String) -> Unit, results: List<ApiClient.SearchItem>, busy: Boolean, onSearch: () -> Unit, onDownload: (ApiClient.SearchItem) -> Unit, onPlay: (ApiClient.SearchItem) -> Unit, message: String) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, onQuery, Modifier.weight(1f), label = { Text("Search") }, singleLine = true)
            IconButton(onClick = onSearch, enabled = !busy) { Icon(Icons.Default.Search, "Search") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        if (message.isNotBlank()) Text(message, Modifier.padding(vertical = 6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(item.channel, item.duration?.let(::formatDuration)).joinToString(" • "))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onPlay(item) }) { Icon(Icons.Default.PlayArrow, null); Text(" Play") }
                            Button(onClick = { onDownload(item) }) { Icon(Icons.Default.Download, null); Text(" Download") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsScreen(modifier: Modifier, works: List<WorkInfo>, wm: WorkManager) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Downloads", style = MaterialTheme.typography.headlineSmall) }
        items(works) { info ->
            val percent = info.progress.getInt("percent", 0).coerceIn(0, 100)
            val title = info.progress.getString(DownloadWorker.KEY_TITLE) ?: info.outputData.getString(DownloadWorker.KEY_TITLE) ?: "Download"
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(info.state.name)
                    LinearProgressIndicator(progress = { percent / 100f }, Modifier.fillMaxWidth())
                    if (info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED) {
                        OutlinedButton(onClick = { wm.cancelWorkById(info.id) }) { Text("Cancel") }
                    }
                    if (info.state == WorkInfo.State.FAILED) {
                        Text(info.outputData.getString("error") ?: "Download failed")
                        OutlinedButton(onClick = { wm.enqueue(info.id) }) { Text("Retry") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(modifier: Modifier, onOpenDownloads: () -> Unit) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Your Library", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Icon(Icons.Default.Folder, null); Text("AH Downloader files are saved in Android Downloads / AH Downloader.") } }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Icon(Icons.Default.MusicNote, null); Text("Audio downloads"); Text("Open Downloads to manage your queue and completed jobs.") } }
        Button(onClick = onOpenDownloads) { Text("Open Download Manager") }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Downloader engine: On-device yt-dlp + FFmpeg")
        Text("Server: Optional fallback only")
        Text("Quality: configurable per download")
        Text("Network: downloads require an active connection")
        Text("Access-control protected media is not bypassed.")
    }
}

private fun createDestination(context: android.content.Context, title: String, audio: Boolean): Uri {
    val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(90).ifBlank { "download" }
    val ext = if (audio) "m4a" else "mp4"
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$safe.$ext")
            put(MediaStore.Downloads.MIME_TYPE, if (audio) "audio/mp4" else "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/AH Downloader")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Unable to create Downloads file")
    }
    return Uri.fromFile(java.io.File(context.getExternalFilesDir(null), "$safe.$ext"))
}

private fun nextQuality(current: String): String = when (current) { "best" -> "1080"; "1080" -> "720"; "720" -> "480"; "480" -> "360"; else -> "best" }
private fun qualityLabel(q: String): String = if (q == "best") "Best" else "${q}p"
private fun formatDuration(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)
private fun openBrowser(context: android.content.Context, url: String) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
