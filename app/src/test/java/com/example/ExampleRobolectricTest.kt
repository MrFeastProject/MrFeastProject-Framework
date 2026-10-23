package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.NetworkStatusRepository
import com.example.manager.LauncherIconManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("MrFeastProject Framework", appName)
    }

    @Test
    fun `network status repository updates state`() {
        NetworkStatusRepository.updateNetworkState(connected = true)
        assertTrue(NetworkStatusRepository.status.value.isNetworkConnected)

        NetworkStatusRepository.updateServerResult(
            reachable = true,
            responseCode = 200,
            latencyMs = 120
        )
        assertEquals(true, NetworkStatusRepository.status.value.isServerReachable)
        assertEquals(200, NetworkStatusRepository.status.value.responseCode)
    }

    @Test
    fun `launcher icon manager toggles state`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (hidden, msg) = LauncherIconManager.setLauncherIconHidden(context, true)
        assertNotNull(msg)
    }
}
