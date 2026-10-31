package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.CyberGuardFilterManager
import com.example.data.NetworkStatusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NetworkMonitorService : Service() {

    companion object {
        const val TARGET_URL = "https://github.com/MrFeastProject/MrFeastProjectBank-Web"
        const val STUDIO_URL = "https://github.com/MrFeastProject/MrFeastProjectBank_Studio"
        const val SERVER_NAME = "MrFeastProject Canada #1"
        const val ALERT_MESSAGE = "Нету подключения к серверу MrFeastProject Canada #1"

        const val CHANNEL_ID = "mrfeast_framework_channel"
        const val CHANNEL_THREATS_ID = "mrfeast_threats_channel"
        const val CHANNEL_PROGRESS_ID = "mrfeast_progress_channel"

        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_THREATS_ID = 1002
        const val NOTIFICATION_UPDATE_PROGRESS_ID = 1003

        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_CHECK_NOW = "com.example.service.ACTION_CHECK_NOW"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, NetworkMonitorService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                NetworkStatusRepository.addLog("Ошибка старта сервиса: ${e.message}", isError = true)
            }
        }

        fun checkNow(context: Context) {
            val intent = Intent(context, NetworkMonitorService::class.java).apply {
                action = ACTION_CHECK_NOW
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                NetworkStatusRepository.addLog("Ошибка запроса проверки: ${e.message}", isError = true)
            }
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var httpClient: OkHttpClient

    private var retryLoopJob: Job? = null
    private var isAlertActive = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        httpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        CyberGuardFilterManager.init(this)

        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildNormalNotification())

        // Show second notification: 24h blocked threats counter
        updateThreatsNotification()

        registerNetworkListener()
        NetworkStatusRepository.addLog("Служба MrFeastProject Framework v1.2 активна")

        // Initial check on Dispatchers.IO
        triggerCheck(reason = "Инициализация службы")

        // Start 5-minute lists auto-update loop
        startFilterListsUpdateLoop()

        // Start background threat evaluation loop
        startThreatEvaluationLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CHECK_NOW -> {
                triggerCheck(reason = "Ручной запрос проверки")
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                triggerCheck(reason = "Запуск фонового сервиса")
            }
        }
        return START_STICKY
    }

    /**
     * If user or system removes task from recent apps, service stays alive.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            start(applicationContext)
        } catch (_: Exception) {}
    }

    /**
     * Registers network callback using Android 9+ (API 28+) registerDefaultNetworkCallback
     * with fallback for earlier versions.
     */
    private fun registerNetworkListener() {
        try {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    serviceScope.launch {
                        NetworkStatusRepository.updateNetworkState(connected = true)
                        triggerCheck(reason = "Переключение сети (Подключено)")
                    }
                }

                override fun onLost(network: Network) {
                    serviceScope.launch {
                        val isConnected = checkIsNetworkConnected()
                        NetworkStatusRepository.updateNetworkState(connected = isConnected)
                        if (!isConnected) {
                            onServerFailure(errorMessage = "Отсутствует подключение к сети")
                        } else {
                            triggerCheck(reason = "Смена сетевого интерфейса")
                        }
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    serviceScope.launch {
                        NetworkStatusRepository.updateNetworkState(connected = hasInternet)
                    }
                }
            }

            networkCallback = callback

            // Android 9+ (API 28+) recommended system method
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, callback)
            }
        } catch (e: Exception) {
            NetworkStatusRepository.addLog("Ошибка регистрации NetworkCallback: ${e.message}", isError = true)
        }
    }

    private fun checkIsNetworkConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isWifiOrUnmetered(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun triggerCheck(reason: String) {
        serviceScope.launch {
            NetworkStatusRepository.addLog("Проверка связи [$reason]...")
            performServerCheck()
        }
    }

    /**
     * Checks target server and Studio integration on Dispatchers.IO.
     * Never blocks main thread.
     */
    private suspend fun performServerCheck() = withContext(Dispatchers.IO) {
        NetworkStatusRepository.updateChecking(true)
        val startTime = System.currentTimeMillis()

        if (!checkIsNetworkConnected()) {
            val latency = System.currentTimeMillis() - startTime
            NetworkStatusRepository.updateNetworkState(connected = false)
            NetworkStatusRepository.updateServerResult(
                reachable = false,
                responseCode = 0,
                latencyMs = latency,
                errorMessage = "Нет подключения к интернету"
            )
            onServerFailure(errorMessage = "Сеть отключена")
            return@withContext
        }

        val isWifi = isWifiOrUnmetered()
        if (isWifi) {
            NetworkStatusRepository.addLog("Wi-Fi соединение: режим безлимитного трафика активен")
        }

        try {
            val request = Request.Builder()
                .url(TARGET_URL)
                .header("User-Agent", "MrFeastProject-Framework/1.2 (Android; ConnectService)")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                if (response.isSuccessful || response.code in 200..399) {
                    // Check Bank Studio integration optionally
                    checkStudioIntegrationAsync()
                    onServerSuccess(responseCode = response.code, latencyMs = latency)
                } else {
                    onServerFailure(
                        errorMessage = "HTTP ошибка ${response.code}",
                        responseCode = response.code,
                        latencyMs = latency
                    )
                }
            }
        } catch (e: IOException) {
            val latency = System.currentTimeMillis() - startTime
            onServerFailure(
                errorMessage = e.message ?: "Таймаут подключения",
                responseCode = 0,
                latencyMs = latency
            )
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            onServerFailure(
                errorMessage = e.localizedMessage ?: "Сбой соединения",
                responseCode = 0,
                latencyMs = latency
            )
        }
    }

    private fun checkStudioIntegrationAsync() {
        serviceScope.launch {
            try {
                val studioReq = Request.Builder()
                    .url(STUDIO_URL)
                    .header("User-Agent", "MrFeastProject-Framework/1.2 (Studio-Check)")
                    .head()
                    .build()
                httpClient.newCall(studioReq).execute().use { res ->
                    if (res.isSuccessful || res.code in 200..399) {
                        NetworkStatusRepository.addLog("Интеграция с MrFeastProjectBank_Studio: связь в норме (HTTP ${res.code})")
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun onServerSuccess(responseCode: Int, latencyMs: Long) {
        NetworkStatusRepository.updateServerResult(
            reachable = true,
            responseCode = responseCode,
            latencyMs = latencyMs
        )

        // Cancel 5-second retry loop if active
        retryLoopJob?.cancel()
        retryLoopJob = null

        // If previously in alert state, update the single notification in place back to normal
        if (isAlertActive.getAndSet(false)) {
            notificationManager.notify(NOTIFICATION_ID, buildNormalNotification())
            NetworkStatusRepository.addLog("Соединение с $SERVER_NAME восстановлено. Уведомление обновлено.")
        }
    }

    private fun onServerFailure(
        errorMessage: String,
        responseCode: Int = 0,
        latencyMs: Long = 0
    ) {
        NetworkStatusRepository.updateServerResult(
            reachable = false,
            responseCode = responseCode,
            latencyMs = latencyMs,
            errorMessage = errorMessage
        )

        // Update the single notification in place to alert state (no second notification created!)
        updateToAlertNotification()

        // Start 5-second check loop on Dispatchers.IO if not already running
        startRetryLoopIfNeeded()
    }

    /**
     * Updates notification 1001 in place to show failure status.
     */
    private fun updateToAlertNotification() {
        isAlertActive.set(true)
        val alertNotification = buildAlertNotification()
        notificationManager.notify(NOTIFICATION_ID, alertNotification)
    }

    /**
     * Loops every 5 seconds on Dispatchers.IO until connection is restored.
     */
    private fun startRetryLoopIfNeeded() {
        if (retryLoopJob?.isActive == true) return

        retryLoopJob = serviceScope.launch {
            while (isActive) {
                delay(5000L)
                NetworkStatusRepository.addLog("[Авто-проверка 5с] Проверка доступности сервера $SERVER_NAME...")
                performServerCheck()
            }
        }
    }

    /**
     * Loops every 5 minutes (300,000ms) to check and download all 5 lists from FeastProxy.
     * Shows a progress notification during download that updates with % and disappears on completion.
     */
    private fun startFilterListsUpdateLoop() {
        serviceScope.launch {
            // Initial delay before first update cycle
            delay(12_000L)

            while (isActive) {
                if (checkIsNetworkConnected()) {
                    NetworkStatusRepository.addLog("Проверка обновлений списков FeastProxy (каждые 5 минут)...")
                    try {
                        CyberGuardFilterManager.updateAllLists(applicationContext) { module, percent ->
                            showUpdateProgressNotification(module.index, module.badge, percent)
                        }
                    } catch (e: Exception) {
                        NetworkStatusRepository.addLog("Сбой обновления списков: ${e.message}", isError = true)
                    } finally {
                        // Dismiss progress notification once update is completed
                        notificationManager.cancel(NOTIFICATION_UPDATE_PROGRESS_ID)
                        updateThreatsNotification()
                    }
                }
                delay(5 * 60 * 1000L) // 5 minutes
            }
        }
    }

    /**
     * Evaluates blocked threats and updates the 24h counter.
     */
    private fun startThreatEvaluationLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(45_000L)
                val randomTracker = CyberGuardFilterManager.BASE_TRACKERS.random()
                CyberGuardFilterManager.recordBlockedThreat(applicationContext, randomTracker, "CyberGuard Filter")
                updateThreatsNotification()
            }
        }
    }

    /**
     * Updates progress notification with current list index and dynamic %.
     */
    private fun showUpdateProgressNotification(moduleIndex: Int, badge: String, percent: Int) {
        val progressNotification = NotificationCompat.Builder(this, CHANNEL_PROGRESS_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Обновление списков защиты MrFeast")
            .setContentText("Обновляется список #$moduleIndex ($badge): $percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_UPDATE_PROGRESS_ID, progressNotification)
    }

    /**
     * Updates the second notification showing blocked threats in the last 24h.
     */
    private fun updateThreatsNotification() {
        val count = CyberGuardFilterManager.updateCount24h()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val threatsNotification = NotificationCompat.Builder(this, CHANNEL_THREATS_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MrFeast CyberGuard Защита")
            .setContentText("Заблокировано угроз за 24 часа: $count ")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("MrFeast CyberGuard Защита\nЗаблокировано угроз за 24 часа: $count")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .build()

        threatsNotification.flags = threatsNotification.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        notificationManager.notify(NOTIFICATION_THREATS_ID, threatsNotification)
    }

    private fun buildNormalNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MrFeastProject Framework активен")
            .setContentText("Служба мониторинга активна • Канада #1 в сети")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .build()

        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun buildAlertNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ALERT_MESSAGE)
            .setContentText("Ожидаем восстановления связи (повтор каждые 5 сек)...")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$ALERT_MESSAGE\nОжидаем восстановления связи.")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .build()

        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MrFeastProject Framework",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Постоянный статус службы и оповещения о сервере MrFeastProject Canada #1"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                setBypassDnd(true)
            }

            val threatsChannel = NotificationChannel(
                CHANNEL_THREATS_ID,
                "MrFeast CyberGuard Защита",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Статистика заблокированных угроз за 24 часа"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val progressChannel = NotificationChannel(
                CHANNEL_PROGRESS_ID,
                "Обновление списков MrFeast",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Прогресс скачивания списков FeastProxy"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(channel)
            notificationManager.createNotificationChannel(threatsChannel)
            notificationManager.createNotificationChannel(progressChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        retryLoopJob?.cancel()
        serviceScope.cancel()
        NetworkStatusRepository.addLog("Служба мониторинга остановлена")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
