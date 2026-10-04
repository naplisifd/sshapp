package com.sshapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sshapp.SessionController
import com.sshapp.data.CommandCatalog
import com.sshapp.data.Recommendation
import com.sshapp.data.Severity
import com.sshapp.data.Suggester

@Composable
fun CommandsTab(session: SessionController, onRun: (String) -> Unit, onEdit: (String) -> Unit) {
    val info by session.serverInfo.collectAsState()
    val loading by session.insightsLoading.collectAsState()
    val historyEntries by session.history.entries.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val frequent = remember(historyEntries) { session.history.frequent(8) }

    /** Commands with placeholders go to the input box to be filled in; others run directly. */
    fun use(cmd: String) = if (com.sshapp.data.CatalogCommand.PLACEHOLDER.containsMatchIn(cmd)) onEdit(cmd) else onRun(cmd)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Search commands, e.g. \"disk\" or \"restart\"") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (query.isNotBlank()) {
            val results = Suggester.suggest(query, historyEntries, 40)
            if (results.isEmpty()) item { Text("No matching commands.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(results, key = { "s:" + it.command }) { s ->
                CommandRow(s.command, s.description, s.timesUsed, onRun = { use(s.command) }, onEdit = { onEdit(s.command) })
            }
            return@LazyColumn
        }

        item {
            ServerCard(info?.os, info?.kernel, info?.uptime, loading, onRefresh = session::refreshInsights)
        }

        val recs = info?.recommendations.orEmpty()
        if (recs.isNotEmpty()) {
            item { SectionTitle("Recommended for this server") }
            items(recs, key = { "r:" + it.title }) { RecommendationCard(it, onRun = { onRun(it.command) }, onEdit = { onEdit(it.command) }) }
        }

        if (frequent.isNotEmpty()) {
            item { SectionTitle("Your frequent commands") }
            items(frequent, key = { "h:" + it.command }) { h ->
                val desc = CommandCatalog.all.firstOrNull { it.command == h.command }?.description
                CommandRow(
                    h.command, desc, h.count,
                    onRun = { use(h.command) },
                    onEdit = { onEdit(h.command) },
                    onLongClick = { session.history.remove(h.command) },
                )
            }
        }

        item { SectionTitle("Ubuntu server commands") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(category == null, { category = null }, label = { Text("All") }) }
                items(CommandCatalog.categories) { c ->
                    FilterChip(category == c, { category = if (category == c) null else c }, label = { Text(c) })
                }
            }
        }
        val catalog = CommandCatalog.all.filter { category == null || it.category == category }
        items(catalog, key = { "c:" + it.command }) { c ->
            val used = historyEntries.firstOrNull { it.command == c.command }?.count ?: 0
            CommandRow(c.command, c.description, used, onRun = { use(c.command) }, onEdit = { onEdit(c.command) })
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun ServerCard(os: String?, kernel: String?, uptime: String?, loading: Boolean, onRefresh: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(os ?: if (loading) "Checking server…" else "Server status", fontWeight = FontWeight.SemiBold)
                listOfNotNull(kernel?.let { "Kernel $it" }, uptime).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (loading) CircularProgressIndicator(Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp)
            IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Re-check server") }
        }
    }
}

@Composable
private fun RecommendationCard(r: Recommendation, onRun: () -> Unit, onEdit: () -> Unit) {
    val (icon, tint) = when (r.severity) {
        Severity.CRITICAL -> Icons.Default.Error to MaterialTheme.colorScheme.error
        Severity.WARN -> Icons.Default.Warning to Color(0xFFD29922)
        Severity.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.secondary
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(r.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.weight(1f)) {
                    Text(
                        r.command, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit before running") }
                FilledTonalButton(onClick = onRun, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Text("Run")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommandRow(
    command: String,
    description: String?,
    timesUsed: Int,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val needsInput = com.sshapp.data.CatalogCommand.PLACEHOLDER.containsMatchIn(command)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onRun, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(command, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(description, if (timesUsed > 0) "used $timesUsed×" else null)
                if (meta.isNotEmpty()) Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = if (needsInput) onEdit else onRun) {
                Icon(
                    if (needsInput) Icons.Default.Edit else Icons.Default.PlayArrow,
                    if (needsInput) "Fill in and run" else "Run",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (!needsInput) IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, "Edit before running", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
