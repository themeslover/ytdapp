package com.themeslover.ytdapp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
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
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestUsefulPermissions()
        setContent { YtdAppScreen() }
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

    private fun createDestination(title: String, kind: MediaKind): Uri? {
        val safe = title.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "download" }
        val extension = if (kind == MediaKind.AUDIO) ".mp3" else ".mp4"
        val display = if (safe.endsWith(extension, ignoreCase = true)) safe else safe + extension
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, display)
                put(MediaStore.Downloads.MIME_TYPE, if (kind == MediaKind.AUDIO) "audio/mpeg" else "video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YTD App")
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
    private fun YtdAppScreen() {
        var apiUrl by remember { mutableStateOf("http://10.0.2.2:8000") }
        var query by remember { mutableStateOf("") }
        var directUrl by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("download") }
        var kind by remember { mutableStateOf(MediaKind.VIDEO) }
        var quality by remember { mutableStateOf("best") }
        var expanded by remember { mutableStateOf(false) }
        var searching by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Ready") }
        var results by remember { mutableStateOf(emptyList<ApiClient.SearchItem>()) }
        val scope = rememberCoroutineScope()
        val api = remember { ApiClient() }
        val workManager = remember { WorkManager.getInstance(this@MainActivity) }
        val works = workManager.getWorkInfosByTagFlow("ytd-download").collectAsState(initial = emptyList()).value

        fun startDownload(source: String, resolvedTitle: String = title) {
            val trimmed = source.trim()
            if (trimmed.isBlank() || !trimmed.startsWith("http")) {
                status = "Enter a valid media URL or select a search result"
                return
            }
            val output = createDestination(resolvedTitle, kind)
            if (output == null) {
                status = "Could not create the Downloads destination"
                return
            }
            val id = UUID.randomUUID().toString()
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(
                    DownloadWorker.KEY_ID to id,
                    DownloadWorker.KEY_SOURCE to trimmed,
                    DownloadWorker.KEY_TITLE to resolvedTitle,
                    DownloadWorker.KEY_OUTPUT to output.toString(),
                    DownloadWorker.KEY_KIND to kind.name,
                    DownloadWorker.KEY_QUALITY to quality
                ))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("ytd-download")
                .build()
            workManager.enqueueUniqueWork("download-$id", ExistingWorkPolicy.KEEP, request)
            status = "Download queued"
            directUrl = ""
        }

        Scaffold(topBar = { TopAppBar(title = { Text("YTD App") }) }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Search YouTube, resolve a compatible format, then download it.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(apiUrl, { apiUrl = it }, Modifier.fillMaxWidth(), label = { Text("API server URL") }, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        Modifier.weight(1f),
                        label = { Text("Search YouTube") },
                        singleLine = true
                    )
                    Button(enabled = !searching && query.isNotBlank(), onClick = {
                        searching = true
                        status = "Searching..."
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { api.search(apiUrl, query) } }
                                .onSuccess {
                                    results = it
                                    status = if (it.isEmpty()) "No results" else "Found ${it.size} results"
                                }
                                .onFailure {
                                    results = emptyList()
                                    status = "Search failed: ${it.message ?: "server error"}"
                                }
                            searching = false
                        }
                    }) { Text(if (searching) "..." else "Search") }
                }

                if (results.isNotEmpty()) {
                    Text("Search results", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(results) { item ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                                    Text(item.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    Button(onClick = {
                                        status = "Resolving ${item.title}..."
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) { api.resolve(apiUrl, item.url, kind.name, quality) }
                                            }.onSuccess { format ->
                                                title = item.title
                                                startDownload(format.url, item.title)
                                            }.onFailure {
                                                status = "Resolve failed: ${it.message ?: "server error"}"
                                            }
                                        }
                                    }) { Text("Download") }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(directUrl, { directUrl = it }, Modifier.fillMaxWidth(), label = { Text("Direct media URL (optional)") }, singleLine = true)
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("File name") }, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
                        OutlinedTextField(kind.name, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
                        ExposedDropdownMenu(expanded, { expanded = false }) {
                            MediaKind.entries.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { kind = item; expanded = false }) }
                        }
                    }
                    OutlinedTextField(quality, { quality = it }, Modifier.weight(1f), label = { Text("Quality (best/360/720)") }, singleLine = true)
                }
                Button(onClick = {
                    val source = directUrl.trim()
                    if (source.isBlank()) {
                        status = "For YouTube links, use Search or select a result"
                    } else {
                        startDownload(source)
                    }
                }, Modifier.fillMaxWidth()) { Text("Start Direct Download") }
                Text(status, style = MaterialTheme.typography.bodySmall)
                Text("Download history", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(works, key = { it.id }) { info ->
                        val percent = info.progress.getInt("percent", 0)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(info.outputData.getString("outputUri") ?: info.id.toString())
                                Text(info.state.name + if (percent > 0) " • $percent%" else "")
                                info.outputData.getString("error")?.let { Text(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}
