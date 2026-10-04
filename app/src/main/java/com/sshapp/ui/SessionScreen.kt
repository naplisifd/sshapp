package com.sshapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import com.sshapp.ConnectionState
import com.sshapp.SessionController

enum class SessionTab(val title: String) { TERMINAL("Terminal"), FILES("Files"), COMMANDS("Commands") }

fun ConnectionState.label() = when (this) {
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Connected -> "Connected"
    ConnectionState.Reconnecting -> "Reconnecting…"
    ConnectionState.Closed -> "Disconnected"
    is ConnectionState.Failed -> "Failed"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(session: SessionController, onBackToHosts: () -> Unit, onDisconnect: () -> Unit) {
    val state by session.state.collectAsState()
    val hostKey by session.hostKeyQuestion.collectAsState()
    var tab by rememberSaveable { mutableStateOf(SessionTab.TERMINAL) }
    var fontSize by rememberSaveable { mutableFloatStateOf(13f) }
    var menu by remember { mutableStateOf(false) }
    var pendingInput by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    LaunchedEffect(session) { session.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler { if (tab != SessionTab.TERMINAL) tab = SessionTab.TERMINAL else onBackToHosts() }

    val runInTerminal: (String) -> Unit = { cmd ->
        session.runCommand(cmd)
        tab = SessionTab.TERMINAL
    }
    val editInTerminal: (String) -> Unit = { cmd ->
        pendingInput = cmd
        tab = SessionTab.TERMINAL
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(session.host.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            state.label(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state == ConnectionState.Connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBackToHosts) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Servers") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Larger text") }, onClick = { fontSize = (fontSize + 1).coerceAtMost(24f) })
                            DropdownMenuItem(text = { Text("Smaller text") }, onClick = { fontSize = (fontSize - 1).coerceAtLeast(7f) })
                            if (state == ConnectionState.Closed || state is ConnectionState.Failed) {
                                DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; session.connect() })
                            }
                            DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; onDisconnect() })
                        }
                    }
                },
            )
        },
        bottomBar = {
            // Hide tabs while typing so the terminal keeps as much room as possible.
            if (!imeVisible) NavigationBar {
                SessionTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    SessionTab.TERMINAL -> Icons.Default.Terminal
                                    SessionTab.FILES -> Icons.Default.Folder
                                    SessionTab.COMMANDS -> Icons.Default.Lightbulb
                                },
                                null,
                            )
                        },
                        label = { Text(t.title) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (state) {
                ConnectionState.Connected, ConnectionState.Closed, ConnectionState.Reconnecting -> when (tab) {
                    SessionTab.TERMINAL -> TerminalTab(
                        session, fontSize,
                        pendingInput = pendingInput,
                        onPendingConsumed = { pendingInput = null },
                        disconnected = state == ConnectionState.Closed,
                        reconnecting = state == ConnectionState.Reconnecting,
                        onReconnect = session::connect,
                    )
                    SessionTab.FILES -> FilesTab(session, onRunInTerminal = runInTerminal)
                    SessionTab.COMMANDS -> CommandsTab(session, onRun = runInTerminal, onEdit = editInTerminal)
                }
                ConnectionState.Connecting -> CenteredStatus {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Connecting to ${session.host.hostname}…")
                }
                is ConnectionState.Failed -> CenteredStatus {
                    Icon(Icons.Default.Warning, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Text("Couldn't connect", style = MaterialTheme.typography.titleMedium)
                    Text(
                        (state as ConnectionState.Failed).message,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onDisconnect) { Text("Back") }
                        Button(onClick = session::connect) { Text("Retry") }
                    }
                }
            }
        }
    }

    hostKey?.let { q ->
        val changed = q.message.contains("WARNING", ignoreCase = false) || q.message.contains("changed", ignoreCase = true)
        AlertDialog(
            onDismissRequest = {},
            icon = if (changed) ({ Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) }) else null,
            title = { Text(if (changed) "Host key changed!" else "Trust this server?") },
            text = {
                Column {
                    if (changed) Text(
                        "The server's identity is different from last time. This can mean the server was reinstalled, or that someone is intercepting the connection.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(q.message, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { q.answer.complete(true) }) { Text(if (changed) "Replace key" else "Trust") } },
            dismissButton = { TextButton(onClick = { q.answer.complete(false) }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CenteredStatus(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}
