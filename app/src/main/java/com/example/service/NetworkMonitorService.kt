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
        const val SERVER_NAME = "MrFeastProject Canada #1"
        const val ALERT_MESSAGE = "Нету подключения к серверу MrFeastProject Canada #1"

        const val CHANNEL_SERVICE_ID = "mrfeast_framework_service"
        const val CHANNEL_ALERT_ID = "mrfeast_framework_alerts"

        const val NOTIFICATION_SERVICE_ID = 1001
        const val NOTIFICATION_ALERT_ID = 2002

        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_CHECK_NOW = "com.example.service.ACTION_CHECK_NOW"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, NetworkMonitorService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun checkNow(context: Context) {
            val intent = Intent(context, NetworkMonitorService::class.java).apply {
                action = ACTION_CHECK_NOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
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

        createNotificationChannels()
        startForeground(NOTIFICATION_SERVICE_ID, buildForegroundNotification())

        registerNetworkListener()
        NetworkStatusRepository.addLog("Служба MrFeastProject Framework запущена в фоновом режиме")

        // Initial check on Dispatchers.IO
        triggerCheck(reason = "Инициализация службы")
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

    private fun registerNetworkListener() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    serviceScope.launch {
                        NetworkStatusRepository.updateNetworkState(connected = true)
                        triggerCheck(reason = "Переключение сети (Сеть подключена)")
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
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            NetworkStatusRepository.addLog("Ошибка регистрации NetworkCallback: ${e.message}", isError = true)
        }
    }

    private fun checkIsNetworkConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun triggerCheck(reason: String) {
        serviceScope.launch {
            NetworkStatusRepository.addLog("Проверка связи [$reason]...")
            performServerCheck()
        }
    }

    /**
     * Executes HTTP check on Dispatchers.IO.
     * Does not block the main thread.
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

        try {
            val request = Request.Builder()
                .url(TARGET_URL)
                .header("User-Agent", "MrFeastProject-Framework/1.0 (Android; ConnectService)")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                if (response.isSuccessful || response.code in 200..399) {
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

    private fun onServerSuccess(responseCode: Int, latencyMs: Long) {
        NetworkStatusRepository.updateServerResult(
            reachable = true,
            responseCode = responseCode,
            latencyMs = latencyMs
        )

        // Cancel 5-second retry loop if active
        retryLoopJob?.cancel()
        retryLoopJob = null

        // Dismiss alert notification if it was showing
        if (isAlertActive.getAndSet(false)) {
            notificationManager.cancel(NOTIFICATION_ALERT_ID)
            NetworkStatusRepository.addLog("Соединение с $SERVER_NAME восстановлено. Уведомление снято.")
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

        // Show alert notification
        showAlertNotification()

        // Start 5-second check loop on Dispatchers.IO if not already running
        startRetryLoopIfNeeded()
    }

    private fun showAlertNotification() {
        isAlertActive.set(true)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertNotification = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ALERT_MESSAGE)
            .setContentText("Повторная проверка каждые 5 сек. URL: $TARGET_URL")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$ALERT_MESSAGE\nОжидаем восстановления связи.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ALERT_ID, alertNotification)
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
                // If performServerCheck succeeded, onServerSuccess cancelled retryLoopJob
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MrFeastProject Framework активен")
            .setContentText("Мониторинг сервера $SERVER_NAME")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                "MrFeastProject Фоновая служба",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Индикатор работы фоновой службы MrFeastProject Framework"
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ALERT_ID,
                "Оповещения сервера MrFeastProject",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Оповещения о потере связи с сервером MrFeastProject Canada #1"
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(alertChannel)
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
