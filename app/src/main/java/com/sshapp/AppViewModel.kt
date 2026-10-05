package com.sshapp

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.sshapp.data.Credentials
import com.sshapp.data.Host
import com.sshapp.data.HostRepository
import com.sshapp.data.SecretStore

sealed interface Screen {
    data object HostList : Screen
    data class EditHost(val hostId: String?) : Screen
    data class Session(val sessionId: String) : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val hosts = HostRepository(app)
    private val secrets = SecretStore(app)

    /** Live sessions are owned by [SessionHolder] so they survive this ViewModel and the Activity. */
    val sessions: List<SessionController> get() = SessionHolder.sessions

    var screen by mutableStateOf<Screen>(SessionHolder.sessions.lastOrNull()?.let { Screen.Session(it.id) } ?: Screen.HostList)

    fun credentialsFor(hostId: String) = secrets.credentials(hostId)

    fun saveHost(host: Host, credentials: Credentials) {
        hosts.upsert(host)
        secrets.saveCredentials(host.id, credentials)
    }

    fun deleteHost(host: Host) {
        hosts.delete(host.id)
        secrets.deleteCredentials(host.id)
    }

    /** Opens a new session alongside any already open. [credentials] may contain a password typed just now that isn't saved. */
    fun connect(host: Host, credentials: Credentials) {
        hosts.markConnected(host.id)
        val s = SessionHolder.start(getApplication(), host, credentials)
        screen = Screen.Session(s.id)
    }

    /** Opens another session to the same server as [session]. */
    fun duplicate(session: SessionController) = connect(session.host, session.credentials)

    fun open(session: SessionController) {
        screen = Screen.Session(session.id)
    }

    /** Closes [session] and shows a neighbouring one, or the server list if it was the last. */
    fun disconnect(session: SessionController) {
        val i = SessionHolder.sessions.indexOf(session)
        SessionHolder.stop(getApplication(), session)
        val next = SessionHolder.sessions.getOrNull(i.coerceAtMost(SessionHolder.sessions.size - 1))
        screen = next?.let { Screen.Session(it.id) } ?: Screen.HostList
    }
}
