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
    data object Session : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val hosts = HostRepository(app)
    private val secrets = SecretStore(app)

    /** The live session is owned by [SessionHolder] so it survives this ViewModel and the Activity. */
    val session: SessionController? get() = SessionHolder.session

    var screen by mutableStateOf<Screen>(if (SessionHolder.session != null) Screen.Session else Screen.HostList)

    fun credentialsFor(hostId: String) = secrets.credentials(hostId)

    fun saveHost(host: Host, credentials: Credentials) {
        hosts.upsert(host)
        secrets.saveCredentials(host.id, credentials)
    }

    fun deleteHost(host: Host) {
        hosts.delete(host.id)
        secrets.deleteCredentials(host.id)
    }

    /** Opens a session. [credentials] may contain a password typed just now that isn't saved. */
    fun connect(host: Host, credentials: Credentials) {
        hosts.markConnected(host.id)
        SessionHolder.start(getApplication(), host, credentials)
        screen = Screen.Session
    }

    fun disconnect() {
        SessionHolder.stop(getApplication())
        screen = Screen.HostList
    }
}
