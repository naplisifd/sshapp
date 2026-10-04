package com.sshapp

import android.content.Context
import com.sshapp.data.CommandHistory
import com.sshapp.data.Credentials
import com.sshapp.data.Host
import com.sshapp.data.ServerInfo
import com.sshapp.data.ServerInsights
import com.sshapp.ssh.RemoteFile
import com.sshapp.ssh.SshConnection
import com.sshapp.terminal.TerminalEmulator
import com.sshapp.terminal.TerminalRenderer
import com.sshapp.terminal.TerminalSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    /** The network dropped; retrying in the background while the terminal stays visible. */
    data object Reconnecting : ConnectionState
    data class Failed(val message: String) : ConnectionState
    data object Closed : ConnectionState
}

data class HostKeyQuestion(val message: String, val answer: CompletableDeferred<Boolean>)

/** State of the SFTP folder tree. */
data class FileTreeState(
    val root: String = "",
    val children: Map<String, List<RemoteFile>> = emptyMap(),
    val expanded: Set<String> = emptySet(),
    val loading: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val showHidden: Boolean = false,
)

data class TreeRow(val file: RemoteFile, val depth: Int, val expanded: Boolean, val loading: Boolean, val error: String?)

/**
 * Everything belonging to one live SSH session: connection, terminal emulator, command history,
 * server insights and the SFTP tree. Lives in [AppViewModel] so it survives configuration changes.
 */
class SessionController(
    context: Context,
    val host: Host,
    credentials: Credentials,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContext = context.applicationContext

    val history = CommandHistory(appContext, host.id)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _hostKeyQuestion = MutableStateFlow<HostKeyQuestion?>(null)
    val hostKeyQuestion: StateFlow<HostKeyQuestion?> = _hostKeyQuestion.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val connection = SshConnection(
        host, credentials, File(appContext.filesDir, "known_hosts"),
    ) { message ->
        val q = HostKeyQuestion(message, CompletableDeferred())
        _hostKeyQuestion.value = q
        try { q.answer.await() } finally { _hostKeyQuestion.value = null }
    }

    // region terminal

    private val emulator = TerminalEmulator(80, 24) { reply -> sendRaw(reply) }
    private val renderer = TerminalRenderer()
    private val _terminal = MutableStateFlow(TerminalSnapshot.EMPTY)
    val terminal: StateFlow<TerminalSnapshot> = _terminal.asStateFlow()
    private val _appCursorKeys = MutableStateFlow(false)
    val appCursorKeys: StateFlow<Boolean> = _appCursorKeys.asStateFlow()
    private val _passwordPrompt = MutableStateFlow(false)
    /** True when the shell appears to be asking for a password; input is masked and not saved to history. */
    val passwordPrompt: StateFlow<Boolean> = _passwordPrompt.asStateFlow()
    private var shellOpen = false
    private var everConnected = false
    private var reconnectJob: Job? = null

    // endregion

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()
    private val _insightsLoading = MutableStateFlow(false)
    val insightsLoading: StateFlow<Boolean> = _insightsLoading.asStateFlow()

    var homeDir = "/"
        private set

    private val _tree = MutableStateFlow(FileTreeState())
    val tree: StateFlow<FileTreeState> = _tree.asStateFlow()

    init {
        connect()
        scope.launch { renderLoop() }
    }

    /** Manual connect/reconnect. Cancels any automatic retry in progress. */
    fun connect() {
        reconnectJob?.cancel()
        reconnectJob = null
        scope.launch {
            if (!tryConnect()) {
                if (!everConnected) return@launch
                _state.value = ConnectionState.Closed
            }
        }
    }

    /** Returns true on success. On failure before the first successful connect, state becomes [ConnectionState.Failed]. */
    private suspend fun tryConnect(): Boolean {
        _state.value = if (everConnected) ConnectionState.Reconnecting else ConnectionState.Connecting
        try {
            connection.disconnect()
            connection.connect()
            withContext(Dispatchers.IO) {
                connection.openShell(
                    emulator.cols, emulator.rows,
                    onOutput = { buf, n -> synchronized(emulator) { emulator.feed(buf, n) } },
                    onClosed = { scope.launch { onShellClosed() } },
                )
            }
            shellOpen = true
            if (everConnected) note("reconnected")
            everConnected = true
            _state.value = ConnectionState.Connected
            refreshInsights()
            homeDir = runCatching { connection.home() }.getOrDefault("/")
            if (_tree.value.root.isEmpty()) openFolder(homeDir)
            else refreshTree()
            return true
        } catch (e: Exception) {
            connection.disconnect()
            lastError = describe(e)
            if (!everConnected) _state.value = ConnectionState.Failed(lastError!!)
            return false
        }
    }

    private var lastError: String? = null

    private suspend fun onShellClosed() {
        shellOpen = false
        if (_state.value != ConnectionState.Connected) return
        // If the SSH session itself is still up, the user ended the shell (`exit`): don't reconnect.
        // If the session is gone too, the network dropped: reconnect automatically.
        delay(300)
        val networkDrop = !connection.isConnected
        connection.disconnect()
        if (!networkDrop) {
            _state.value = ConnectionState.Closed
            return
        }
        note("connection lost")
        reconnectJob = scope.launch {
            for ((attempt, wait) in RETRY_DELAYS_MS.withIndex()) {
                if (tryConnect()) return@launch
                _messages.tryEmit("Reconnect attempt ${attempt + 1} failed: $lastError")
                delay(wait)
            }
            if (!tryConnect()) _state.value = ConnectionState.Closed
        }
    }

    /** Writes a dim status line into the terminal, e.g. "[connection lost]". */
    private fun note(text: String) = synchronized(emulator) {
        emulator.feed("\r\n\u001b[2;33m[$text]\u001b[0m\r\n")
    }

    /** Coalesces emulator updates into at most ~30 snapshots per second. */
    private suspend fun renderLoop() {
        var lastVersion = -1L
        while (true) {
            val snap = withContext(Dispatchers.Default) {
                synchronized(emulator) {
                    if (emulator.version == lastVersion) null
                    else {
                        lastVersion = emulator.version
                        _appCursorKeys.value = emulator.appCursorKeys
                        _passwordPrompt.value = PASSWORD_PROMPT.containsMatchIn(emulator.cursorLineText())
                        renderer.snapshot(emulator)
                    }
                }
            }
            if (snap != null) _terminal.value = snap
            delay(33)
        }
    }

    fun resizeTerminal(cols: Int, rows: Int) {
        val changed = synchronized(emulator) {
            if (cols == emulator.cols && rows == emulator.rows) false
            else { emulator.resize(cols, rows); true }
        }
        if (changed && shellOpen) scope.launch(Dispatchers.IO) { connection.resize(cols, rows) }
    }

    fun sendRaw(text: String) {
        if (!shellOpen) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        scope.launch(Dispatchers.IO) { connection.write(bytes) }
    }

    /** Sends a full command line from the input box, recording it for suggestions. */
    fun sendCommand(command: String) {
        val secret = _passwordPrompt.value
        val inApp = synchronized(emulator) { emulator.usingAlt }
        if (!secret && !inApp && command.isNotBlank()) history.record(command)
        sendRaw(command + "\r")
    }

    /** Runs a suggested command: interrupts nothing, just types it at the prompt and presses enter. */
    fun runCommand(command: String) = sendCommand(command)

    // region insights

    fun refreshInsights() {
        if (_insightsLoading.value) return
        _insightsLoading.value = true
        scope.launch {
            try {
                val r = connection.exec(ServerInsights.PROBE_SCRIPT)
                _serverInfo.value = ServerInsights.parse(r.output)
            } catch (e: Exception) {
                _messages.tryEmit("Couldn't read server status: ${describe(e)}")
            } finally {
                _insightsLoading.value = false
            }
        }
    }

    // endregion

    // region files

    fun openFolder(path: String) {
        _tree.update { it.copy(root = path, expanded = emptySet(), errors = emptyMap()) }
        loadChildren(path)
    }

    fun toggleHidden() = _tree.update { it.copy(showHidden = !it.showHidden) }

    fun toggleExpanded(dir: RemoteFile) {
        val t = _tree.value
        if (dir.path in t.expanded) {
            _tree.update { it.copy(expanded = it.expanded - dir.path) }
        } else {
            _tree.update { it.copy(expanded = it.expanded + dir.path) }
            if (dir.path !in t.children) loadChildren(dir.path)
        }
    }

    fun refreshTree() {
        val t = _tree.value
        val toReload = listOf(t.root) + t.expanded.toList()
        _tree.update { it.copy(children = it.children.filterKeys { k -> k in toReload }) }
        toReload.forEach { loadChildren(it) }
    }

    private fun loadChildren(dir: String) {
        _tree.update { it.copy(loading = it.loading + dir, errors = it.errors - dir) }
        scope.launch {
            try {
                val list = connection.list(dir)
                _tree.update { it.copy(children = it.children + (dir to list), loading = it.loading - dir) }
            } catch (e: Exception) {
                _tree.update { it.copy(loading = it.loading - dir, errors = it.errors + (dir to describe(e))) }
            }
        }
    }

    fun visibleRows(t: FileTreeState): List<TreeRow> {
        val rows = ArrayList<TreeRow>()
        fun walk(dir: String, depth: Int) {
            val kids = t.children[dir] ?: return
            for (f in kids) {
                if (!t.showHidden && f.name.startsWith(".")) continue
                val expanded = f.isDir && f.path in t.expanded
                rows += TreeRow(f, depth, expanded, f.path in t.loading, t.errors[f.path])
                if (expanded) walk(f.path, depth + 1)
            }
        }
        walk(t.root, 0)
        return rows
    }

    private fun reloadParentOf(path: String) = loadChildren(SshConnection.parentOf(path))

    private fun fileOp(success: String, reload: String, op: suspend () -> Unit) {
        scope.launch {
            try {
                op()
                _messages.tryEmit(success)
            } catch (e: Exception) {
                _messages.tryEmit(describe(e))
            }
            loadChildren(reload)
        }
    }

    fun createFolder(parent: String, name: String) =
        fileOp("Created $name", parent) { connection.mkdir(SshConnection.joinPath(parent, name)) }

    fun rename(file: RemoteFile, newName: String) {
        val parent = SshConnection.parentOf(file.path)
        fileOp("Renamed to $newName", parent) { connection.rename(file.path, SshConnection.joinPath(parent, newName)) }
    }

    fun delete(file: RemoteFile) {
        _tree.update { t -> t.copy(expanded = t.expanded.filterNot { it == file.path || it.startsWith(file.path + "/") }.toSet()) }
        fileOp("Deleted ${file.name}", SshConnection.parentOf(file.path)) { connection.delete(file) }
    }

    fun upload(parent: String, name: String, open: () -> InputStream?) =
        fileOp("Uploaded $name", parent) {
            withContext(Dispatchers.IO) {
                (open() ?: error("Can't read the selected file")).use { connection.upload(it, SshConnection.joinPath(parent, name)) }
            }
        }

    fun download(file: RemoteFile, open: () -> OutputStream?) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    (open() ?: error("Can't write the destination")).use { connection.download(file.path, it) }
                }
                _messages.tryEmit("Downloaded ${file.name}")
            } catch (e: Exception) {
                _messages.tryEmit(describe(e))
            }
        }
    }

    suspend fun readText(file: RemoteFile): Result<Pair<String, Boolean>> = runCatching {
        val bytes = connection.readFile(file.path, MAX_PREVIEW_BYTES + 1)
        val truncated = bytes.size > MAX_PREVIEW_BYTES
        val data = if (truncated) bytes.copyOf(MAX_PREVIEW_BYTES) else bytes
        if (data.take(8000).any { it == 0.toByte() }) error("This looks like a binary file. Download it instead.")
        String(data, Charsets.UTF_8) to truncated
    }

    suspend fun writeText(file: RemoteFile, text: String): Result<Unit> = runCatching {
        connection.writeFile(file.path, text.toByteArray(Charsets.UTF_8))
        reloadParentOf(file.path)
    }

    // endregion

    fun emitMessage(text: String) { _messages.tryEmit(text) }

    fun close() {
        reconnectJob?.cancel()
        scope.cancel()
        Thread { connection.disconnect() }.start()
    }

    companion object {
        const val MAX_PREVIEW_BYTES = 512 * 1024
        private val RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L)
        private val PASSWORD_PROMPT = Regex("(password|passphrase)[^:\n]*:\\s*$", RegexOption.IGNORE_CASE)

        fun describe(e: Throwable): String {
            val msg = e.message ?: e.javaClass.simpleName
            return when {
                msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) ->
                    "Authentication failed. Check the username, password or key."
                msg.contains("UnknownHost", true) || e is java.net.UnknownHostException -> "Unknown host: ${e.message}"
                msg.contains("timeout", true) -> "Connection timed out."
                msg.contains("Connection refused", true) -> "Connection refused. Is SSH running on that port?"
                msg.contains("reject HostKey", true) -> "Host key was not trusted, connection cancelled."
                msg.contains("invalid privatekey", true) -> "Couldn't read the private key (wrong format or passphrase)."
                msg.contains("Permission denied", true) -> "Permission denied."
                msg.contains("No such file", true) -> "No such file or directory."
                else -> msg
            }
        }
    }
}
