package com.themeslover.ytdapp

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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
        val display = if (safe.endsWith(extension)) safe else safe + extension
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
    @androidx.compose.runtime.Composable
    private fun YtdAppScreen() {
        var url by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("download") }
        var kind by remember { mutableStateOf(MediaKind.VIDEO) }
        var quality by remember { mutableStateOf("best") }
        var expanded by remember { mutableStateOf(false) }
        var refresh by remember { mutableStateOf(0) }
        val workManager = remember { WorkManager.getInstance(this@MainActivity) }
        val works = workManager.getWorkInfosByTagFlow("ytd-download").collectAsState(initial = emptyList()).value

        Scaffold(topBar = { TopAppBar(title = { Text("YTD App") }, scrollBehavior = null) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Downloads work without an account. Sign-in is not required to start a download.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Direct media URL") }, singleLine = true)
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("File name") }, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
                        OutlinedTextField(kind.name, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("Type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
                        ExposedDropdownMenu(expanded, { expanded = false }) {
                            MediaKind.entries.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { kind = item; expanded = false }) }
                        }
                    }
                    OutlinedTextField(quality, { quality = it }, Modifier.weight(1f), label = { Text("Quality") }, singleLine = true)
                }
                Button(onClick = {
                    val source = url.trim()
                    if (source.isBlank() || !source.startsWith("http")) return@Button
                    val output = createDestination(title, kind) ?: return@Button
                    val id = UUID.randomUUID().toString()
                    val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                        .setInputData(workDataOf(
                            DownloadWorker.KEY_ID to id,
                            DownloadWorker.KEY_SOURCE to source,
                            DownloadWorker.KEY_TITLE to title,
                            DownloadWorker.KEY_OUTPUT to output.toString(),
                            DownloadWorker.KEY_KIND to kind.name,
                            DownloadWorker.KEY_QUALITY to quality
                        ))
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .addTag("ytd-download")
                        .build()
                    workManager.enqueueUniqueWork("download-$id", ExistingWorkPolicy.KEEP, request)
                    url = ""
                    refresh++
                }, Modifier.fillMaxWidth()) { Text("Start Download") }

                Text("Download history", style = MaterialTheme.typography.titleMedium)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(works, key = { it.id }) { info ->
                        val percent = info.progress.getInt("percent", 0)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(info.outputData.getString("outputUri") ?: info.id.toString())
                                Text(info.state.name + if (percent > 0) " • $percent%" else "")
                            }
                        }
                    }
                }
            }
        }
    }
}
