package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CyberGuardFilterManager
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

    @Test
    fun `cyberguard filter manager tracks blocked domains and 24h count`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        CyberGuardFilterManager.init(context)

        // Verify Telegram ads domain is strictly blocked
        assertTrue(CyberGuardFilterManager.isDomainBlocked("ads.telegram.org"))
        assertTrue(CyberGuardFilterManager.isDomainBlocked("https://ads.telegram.org/channel"))

        // Verify YouTube ad domains from user prompt & reference
        assertTrue(CyberGuardFilterManager.isDomainBlocked("ads.youtube.com"))
        assertTrue(CyberGuardFilterManager.isDomainBlocked("s0.2mdn.net"))
        assertTrue(CyberGuardFilterManager.isDomainBlocked("s1.2mdn.net"))
        assertTrue(CyberGuardFilterManager.isDomainBlocked("pagead2.googlesyndication.com"))
        assertTrue(CyberGuardFilterManager.isDomainBlocked("googleads.g.doubleclick.net"))
        assertTrue(CyberGuardFilterManager.isDomainBlocked("pubads.g.doubleclick.net"))

        // Verify YouTube video stream CDN ad pattern rx---sn-xxxxx.googlevideo.com
        assertTrue(CyberGuardFilterManager.isDomainBlocked("r1---sn-4g5ednss.googlevideo.com"))
        assertTrue(CyberGuardFilterManager.isDomainBlocked("r5.sn-ab5sznz.googlevideo.com"))

        // Verify base adguard and tracker domains
        assertTrue(CyberGuardFilterManager.isDomainBlocked("google-analytics.com"))
        assertTrue(CyberGuardFilterManager.isDomainBlocked("ad.intl.xiaomi.com"))
        assertEquals(12, CyberGuardFilterManager.FILTER_MODULES.size)

        val before = CyberGuardFilterManager.updateCount24h()
        CyberGuardFilterManager.recordBlockedThreat(context, "test-malicious-tracker.com", "Test")
        assertEquals(before + 1, CyberGuardFilterManager.updateCount24h())
    }
}
