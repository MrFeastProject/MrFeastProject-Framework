package com.example

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.service.NetworkMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        hideLauncherIconAndFinish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start background network monitoring service immediately
        NetworkMonitorService.start(applicationContext)

        // Check notification permission on Android 13+ (API 33+) so alert notifications can be shown
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            hideLauncherIconAndFinish()
        }
    }

    /**
     * Automatically hides the launcher icon from the home screen / app drawer
     * using Dispatchers.IO to never block the main thread, and immediately finishes.
     */
    private fun hideLauncherIconAndFinish() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pm = packageManager
                val componentName = ComponentName(this@MainActivity, MainActivity::class.java)
                pm.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    applicationContext,
                    "MrFeastProject Framework активен в фоне. Иконка скрыта.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }
}
