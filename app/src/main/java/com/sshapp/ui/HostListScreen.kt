package com.sshapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sshapp.AppViewModel
import com.sshapp.Screen
import com.sshapp.SessionController
import com.sshapp.data.AuthType
import com.sshapp.data.Credentials
import com.sshapp.data.Host
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(vm: AppViewModel) {
    val hosts by vm.hosts.hosts.collectAsState()
    var askPasswordFor by remember { mutableStateOf<Host?>(null) }
    var confirmDelete by remember { mutableStateOf<Host?>(null) }

    fun connect(host: Host) {
        val creds = vm.credentialsFor(host.id)
        if (host.authType == AuthType.PASSWORD && creds.password.isNullOrEmpty()) askPasswordFor = host
        else vm.connect(host, creds)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SSH Deck") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.screen = Screen.EditHost(null) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add server") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (vm.sessions.isNotEmpty()) item {
                Text(
                    if (vm.sessions.size == 1) "Open session" else "Open sessions (${vm.sessions.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            items(vm.sessions, key = { "session:" + it.id }) { session ->
                ActiveSessionCard(session, onResume = { vm.open(session) }, onClose = { vm.disconnect(session); vm.screen = Screen.HostList })
            }
            if (vm.sessions.isNotEmpty() && hosts.isNotEmpty()) item {
                Text(
                    "Servers · tap to open a new session",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
            if (hosts.isEmpty()) item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 80.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.Dns, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.padding(8.dp))
                    Text("No servers yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Add your Ubuntu server's address and login to get a terminal, a file browser and command suggestions.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(hosts.sortedByDescending { it.lastConnected }, key = { it.id }) { host ->
                HostCard(
                    host,
                    onConnect = { connect(host) },
                    onEdit = { vm.screen = Screen.EditHost(host.id) },
                    onDuplicate = {
                        vm.saveHost(host.copy(id = java.util.UUID.randomUUID().toString(), name = host.name + " copy", lastConnected = 0), vm.credentialsFor(host.id))
                    },
                    onDelete = { confirmDelete = host },
                )
            }
        }
    }

    askPasswordFor?.let { host ->
        PasswordDialog(
            host,
            onDismiss = { askPasswordFor = null },
            onConnect = { password, remember ->
                askPasswordFor = null
                val creds = vm.credentialsFor(host.id).copy(password = password)
                if (remember) vm.saveHost(host, creds)
                vm.connect(host, creds)
            },
        )
    }
    confirmDelete?.let { host ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${host.label}?") },
            text = { Text("The saved login for this server will be removed from this device.") },
            confirmButton = { TextButton(onClick = { vm.deleteHost(host); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActiveSessionCard(session: SessionController, onResume: () -> Unit, onClose: () -> Unit) {
    val state by session.state.collectAsState()
    Card(onClick = onResume, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Terminal, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(session.title, fontWeight = FontWeight.SemiBold)
                Text("${session.host.username}@${session.host.hostname} · ${state.label()}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onResume) { Text("Resume") }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Disconnect") }
        }
    }
}

@Composable
private fun HostCard(host: Host, onConnect: () -> Unit, onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onConnect, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (host.authType == AuthType.KEY) Icons.Default.Key else Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(host.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${host.username}@${host.hostname}" + if (host.port != 22) ":${host.port}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (host.lastConnected > 0) Text(
                    "Last used " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(host.lastConnected)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun PasswordDialog(host: Host, onDismiss: () -> Unit, onConnect: (String, Boolean) -> Unit) {
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password for ${host.username}@${host.hostname}") },
        text = {
            Column {
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().clickable { remember = !remember }.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(remember, { remember = it })
                    Text("Remember on this device (encrypted)")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConnect(password, remember) }, enabled = password.isNotEmpty()) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
