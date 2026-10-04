package com.sshapp.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sshapp.AppViewModel
import com.sshapp.Screen
import com.sshapp.data.AuthType
import com.sshapp.data.Credentials
import com.sshapp.data.Host

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(vm: AppViewModel, hostId: String?) {
    val existing = remember(hostId) { hostId?.let { vm.hosts.get(it) } }
    val existingCreds = remember(hostId) { hostId?.let { vm.credentialsFor(it) } ?: Credentials() }
    val context = LocalContext.current

    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var hostname by rememberSaveable { mutableStateOf(existing?.hostname ?: "") }
    var port by rememberSaveable { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by rememberSaveable { mutableStateOf(existing?.username ?: "ubuntu") }
    var authType by rememberSaveable { mutableStateOf(existing?.authType ?: AuthType.PASSWORD) }
    var password by rememberSaveable { mutableStateOf(existingCreds.password ?: "") }
    var privateKey by rememberSaveable { mutableStateOf(existingCreds.privateKey ?: "") }
    var passphrase by rememberSaveable { mutableStateOf(existingCreds.passphrase ?: "") }
    var keyError by remember { mutableStateOf<String?>(null) }

    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(64 * 1024).toByteArray().decodeToString() }
        }.getOrNull()
        if (text != null && text.contains("PRIVATE KEY")) { privateKey = text.trim(); keyError = null }
        else keyError = "That file doesn't look like a private key."
    }

    val portNum = port.toIntOrNull()
    val valid = hostname.isNotBlank() && username.isNotBlank() && portNum != null && portNum in 1..65535 &&
        (authType == AuthType.PASSWORD || privateKey.contains("PRIVATE KEY"))

    fun save() {
        val host = (existing ?: Host(name = "", hostname = "", username = "")).copy(
            name = name.trim(), hostname = hostname.trim(), port = portNum ?: 22,
            username = username.trim(), authType = authType,
        )
        val creds = when (authType) {
            AuthType.PASSWORD -> Credentials(password = password.ifEmpty { null })
            AuthType.KEY -> Credentials(privateKey = privateKey.trim(), passphrase = passphrase.ifEmpty { null })
        }
        vm.saveHost(host, creds)
        vm.screen = Screen.HostList
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add server" else "Edit server") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.HostList }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, placeholder = { Text("Home server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    hostname, { hostname = it.trim() }, label = { Text("Host or IP") }, singleLine = true,
                    placeholder = { Text("192.168.1.10") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                    isError = portNum == null || portNum !in 1..65535,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.4f),
                )
            }
            OutlinedTextField(username, { username = it.trim() }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Authentication", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AuthType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = authType == t,
                        onClick = { authType = t },
                        shape = SegmentedButtonDefaults.itemShape(i, AuthType.entries.size),
                    ) { Text(if (t == AuthType.PASSWORD) "Password" else "Private key") }
                }
            }

            when (authType) {
                AuthType.PASSWORD -> {
                    OutlinedTextField(
                        password, { password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = { Text("Leave empty to be asked each time you connect. Saved passwords are encrypted with the Android Keystore.") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AuthType.KEY -> {
                    OutlinedButton(onClick = { importKey.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FileOpen, null)
                        Text("  Import key file")
                    }
                    OutlinedTextField(
                        privateKey, { privateKey = it; keyError = null },
                        label = { Text("Private key (OpenSSH or PEM)") },
                        placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----\n…") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        isError = keyError != null,
                        supportingText = { Text(keyError ?: "Ed25519, ECDSA and RSA keys are supported.") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp),
                    )
                    OutlinedTextField(
                        passphrase, { passphrase = it }, label = { Text("Key passphrase (if any)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Button(onClick = ::save, enabled = valid, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save") }
        }
    }
}
