package com.themeslover.ytdapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { YtdApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun YtdApp() {
    var query by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var format by remember { mutableStateOf("Best available") }
    var expanded by remember { mutableStateOf(false) }
    var queued by remember { mutableStateOf(0) }

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("YTD App") }) }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { IconButton(onClick = { /* wired to SearchRepository in next layer */ }) { Icon(Icons.Default.Search, null) } }
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Paste video / audio / playlist URL") }
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Download options", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { expanded = true }) { Text(format) }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                listOf("Best available", "1080p", "720p", "480p", "360p", "Audio only").forEach { option ->
                                    DropdownMenuItem(text = { Text(option) }, onClick = { format = option; expanded = false })
                                }
                            }
                            Button(onClick = { if (url.isNotBlank()) queued++ }) {
                                Icon(Icons.Default.Download, null)
                                Text(" Queue")
                            }
                        }
                    }
                }

                Text("Queue: $queued item(s)", style = MaterialTheme.typography.titleMedium)
                if (queued > 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                Text(
                    "Designed for public or user-authorized media. Age-gated, DRM-protected, private, or otherwise access-controlled media is not bypassed.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
