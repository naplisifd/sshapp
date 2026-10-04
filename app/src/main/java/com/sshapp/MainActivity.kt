package com.sshapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.sshapp.ui.AppTheme
import com.sshapp.ui.HostEditScreen
import com.sshapp.ui.HostListScreen
import com.sshapp.ui.SessionScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The "connected" notification keeps the session alive in the background; ask to show it.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AppTheme {
                when (val s = vm.screen) {
                    Screen.HostList -> HostListScreen(vm)
                    is Screen.EditHost -> {
                        BackHandler { vm.screen = Screen.HostList }
                        HostEditScreen(vm, s.hostId)
                    }
                    Screen.Session -> {
                        val session = SessionHolder.session
                        if (session == null) LaunchedEffect(Unit) { vm.screen = Screen.HostList }
                        else SessionScreen(session, onBackToHosts = { vm.screen = Screen.HostList }, onDisconnect = vm::disconnect)
                    }
                }
            }
        }
    }
}
