package com.sshapp.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.UserInfo
import com.sshapp.data.AuthType
import com.sshapp.data.Credentials
import com.sshapp.data.Host
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

data class RemoteFile(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val isLink: Boolean,
    val size: Long,
    val modified: Long,
    val permissions: String,
)

data class ExecResult(val output: String, val exitCode: Int)

/**
 * One SSH session to a host, with an interactive shell channel and a lazily opened SFTP channel.
 * All blocking calls are moved to [Dispatchers.IO].
 */
class SshConnection(
    private val host: Host,
    private val credentials: Credentials,
    private val knownHosts: File,
    /** Asked on the connecting thread when the host key is unknown or changed; return true to trust it. */
    private val confirmHostKey: suspend (message: String) -> Boolean,
) {
    private var session: Session? = null
    private var shell: ChannelShell? = null
    private var shellOut: OutputStream? = null
    private var sftp: ChannelSftp? = null
    private val sftpLock = Mutex()

    val isConnected: Boolean get() = session?.isConnected == true

    suspend fun connect() = withContext(Dispatchers.IO) {
        configureCrypto()
        if (!knownHosts.exists()) knownHosts.createNewFile()
        val jsch = JSch()
        jsch.setKnownHosts(knownHosts.absolutePath)
        if (host.authType == AuthType.KEY && !credentials.privateKey.isNullOrBlank()) {
            jsch.addIdentity(
                "key",
                credentials.privateKey.trim().toByteArray() + '\n'.code.toByte(),
                null,
                credentials.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(),
            )
        }
        val s = jsch.getSession(host.username, host.hostname, host.port)
        credentials.password?.takeIf { it.isNotEmpty() }?.let { s.setPassword(it.toByteArray()) }
        s.setConfig("StrictHostKeyChecking", "ask")
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        s.userInfo = HostKeyPrompter(confirmHostKey)
        s.setServerAliveInterval(30_000)
        s.setServerAliveCountMax(3)
        s.connect(20_000)
        session = s
    }

    /** Opens the interactive shell; [onOutput] is called from a background reader thread until the channel closes. */
    fun openShell(cols: Int, rows: Int, onOutput: (CharArray, Int) -> Unit, onClosed: () -> Unit) {
        val s = session ?: error("Not connected")
        val ch = s.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color", cols, rows, cols * 8, rows * 16)
        ch.setEnv("LANG", "C.UTF-8")
        val input = ch.inputStream
        shellOut = ch.outputStream
        ch.connect(15_000)
        shell = ch
        Thread({
            val reader = InputStreamReader(input, Charsets.UTF_8)
            val buf = CharArray(8192)
            try {
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    onOutput(buf, n)
                }
            } catch (_: Exception) {
            } finally {
                onClosed()
            }
        }, "ssh-shell-reader").apply { isDaemon = true }.start()
    }

    fun write(bytes: ByteArray) {
        val out = shellOut ?: return
        try {
            out.write(bytes)
            out.flush()
        } catch (_: Exception) {
        }
    }

    fun resize(cols: Int, rows: Int) {
        runCatching { shell?.setPtySize(cols, rows, cols * 8, rows * 16) }
    }

    /** Runs a non-interactive command on a separate channel and returns stdout+stderr. */
    suspend fun exec(command: String, timeoutMs: Long = 20_000): ExecResult = withContext(Dispatchers.IO) {
        val s = session ?: error("Not connected")
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val out = ByteArrayOutputStream()
        ch.outputStream = out
        ch.setErrStream(out, true)
        ch.connect(10_000)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!ch.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val code = ch.exitStatus
        ch.disconnect()
        ExecResult(out.toString(Charsets.UTF_8.name()), code)
    }

    private suspend fun <T> withSftp(block: (ChannelSftp) -> T): T = withContext(Dispatchers.IO) {
        sftpLock.withLock {
            val ch = sftp?.takeIf { it.isConnected } ?: run {
                val s = session ?: error("Not connected")
                (s.openChannel("sftp") as ChannelSftp).also { it.connect(15_000); sftp = it }
            }
            block(ch)
        }
    }

    suspend fun home(): String = withSftp { it.home }

    suspend fun list(dir: String): List<RemoteFile> = withSftp { ch ->
        @Suppress("UNCHECKED_CAST")
        val entries = ch.ls(dir) as java.util.Vector<ChannelSftp.LsEntry>
        entries.filter { it.filename != "." && it.filename != ".." }.map { e ->
            val path = joinPath(dir, e.filename)
            var attrs: SftpATTRS = e.attrs
            val isLink = attrs.isLink
            if (isLink) runCatching { attrs = ch.stat(path) }
            RemoteFile(
                path = path,
                name = e.filename,
                isDir = attrs.isDir,
                isLink = isLink,
                size = attrs.size,
                modified = attrs.mTime.toLong() * 1000,
                permissions = attrs.permissionsString,
            )
        }.sortedWith(compareBy<RemoteFile>({ !it.isDir }, { it.name.lowercase() }))
    }

    suspend fun readFile(path: String, maxBytes: Int): ByteArray = withSftp { ch ->
        ch.get(path).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (out.size() < maxBytes) {
                val n = input.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    suspend fun writeFile(path: String, data: ByteArray) = withSftp { ch ->
        ch.put(data.inputStream(), path, ChannelSftp.OVERWRITE)
    }

    suspend fun download(path: String, target: OutputStream) = withSftp { ch -> ch.get(path, target) }

    suspend fun upload(source: InputStream, path: String) = withSftp { ch -> ch.put(source, path, ChannelSftp.OVERWRITE) }

    suspend fun mkdir(path: String) = withSftp { it.mkdir(path) }

    suspend fun rename(from: String, to: String) = withSftp { it.rename(from, to) }

    suspend fun delete(file: RemoteFile) {
        if (file.isDir && !file.isLink) {
            val r = exec("rm -rf -- ${shellQuote(file.path)}")
            if (r.exitCode != 0) error(r.output.ifBlank { "rm failed with code ${r.exitCode}" })
        } else {
            withSftp { it.rm(file.path) }
        }
    }

    fun disconnect() {
        runCatching { sftp?.disconnect() }
        runCatching { shell?.disconnect() }
        runCatching { session?.disconnect() }
        sftp = null
        shell = null
        session = null
    }

    private class HostKeyPrompter(private val confirm: suspend (String) -> Boolean) : UserInfo {
        override fun promptYesNo(message: String): Boolean = runBlocking { confirm(message) }
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?) = false
        override fun promptPassphrase(message: String?) = false
        override fun showMessage(message: String?) {}
    }

    companion object {
        @Volatile private var cryptoConfigured = false

        /**
         * Android does not ship the JDK 11+/15+ XDH and EdDSA providers that JSch's defaults use,
         * so route curve25519, ed25519 and ML-KEM through Bouncy Castle.
         */
        private fun configureCrypto() {
            if (cryptoConfigured) return
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
            JSch.setConfig("mlkem768", "com.jcraft.jsch.bc.MLKEM768")
            JSch.setConfig("mlkem1024", "com.jcraft.jsch.bc.MLKEM1024")
            cryptoConfigured = true
        }

        fun joinPath(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

        fun parentOf(path: String): String {
            val trimmed = path.trimEnd('/')
            val i = trimmed.lastIndexOf('/')
            return if (i <= 0) "/" else trimmed.substring(0, i)
        }

        fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
