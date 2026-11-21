package com.guilnunes.herald.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guilnunes.herald.data.SavedPage
import com.guilnunes.herald.player.Status

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val search by vm.search.collectAsStateWithLifecycle()
    val saved by vm.repo.saved.collectAsStateWithLifecycle()
    val player by vm.engine.state.collectAsStateWithLifecycle()
    var forget by remember { mutableStateOf<SavedPage?>(null) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Herald", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.go(Screen.SETUP) }) { Icon(Icons.Filled.Settings, "Settings") }
            }
            OutlinedTextField(
                value = search.query,
                onValueChange = vm::onQuery,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Search your Notion pages") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (search.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

            LazyColumn(Modifier.weight(1f)) {
                search.error?.let { err ->
                    item {
                        Column(Modifier.padding(20.dp)) {
                            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = vm::retrySearch) { Text("Try again") }
                        }
                    }
                }
                if (saved.isNotEmpty() && search.query.isBlank()) {
                    item { Header("Saved for offline") }
                    items(saved, key = { "s" + it.id }) { p ->
                        PageRow(
                            icon = p.icon, title = p.title, subtitle = if (p.percent >= 100) "Finished" else "${p.percent}% read",
                            percent = p.percent,
                            modifier = Modifier.combinedClickable(onClick = { vm.openPage(p.id) }, onLongClick = { forget = p }),
                        )
                    }
                    item { Header(if (search.results.isEmpty()) "" else "Recently edited in Notion") }
                }
                items(search.results, key = { "r" + it.id }) { p ->
                    PageRow(icon = p.icon, title = p.title, subtitle = p.lastEdited.take(10), percent = null,
                        modifier = Modifier.clickable { vm.openPage(p.id) })
                }
                if (!search.loading && search.error == null && search.results.isEmpty() && search.query.isNotBlank()) {
                    item { Text("No pages found. Is the page connected to Herald?", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium) }
                }
            }

            if (player.script != null) MiniPlayer(vm)
        }
    }

    forget?.let { p ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text("Remove offline copy?") },
            text = { Text("\"${p.title}\" will be removed from this phone. It stays in Notion.") },
            confirmButton = { TextButton(onClick = { vm.repo.forget(p.id); forget = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(t: String) {
    if (t.isNotEmpty()) Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun PageRow(icon: String?, title: String, subtitle: String, percent: Int?, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon ?: "📄", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (percent != null && percent in 1..99) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
    HorizontalDivider()
}

@Composable
private fun MiniPlayer(vm: MainViewModel) {
    val st by vm.engine.state.collectAsStateWithLifecycle()
    Surface(tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth().clickable { vm.go(Screen.READER) }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(st.script?.title ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(st.sectionTitle, "${st.percent}%").joinToString(" · "), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            IconButton(onClick = vm.engine::togglePlay, modifier = Modifier.size(56.dp)) {
                Icon(if (st.status == Status.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", Modifier.size(36.dp))
            }
        }
    }
}
