package br.tv.az.player

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

class KioskService : Service() {

    companion object {
        private const val TAG = "KioskService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "kiosk_service_channel"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var checkRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🔧 KioskService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🚀 KioskService started as foreground service")

        startForeground(NOTIFICATION_ID, createNotification())
        startMainActivityChecker()

        // Restart service if killed
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kiosk Mode Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the TV app running in kiosk mode"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Log.d(TAG, "📺 Notification channel created")
        }
    }

    private fun createNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AZ TV Player - Modo Kiosk")
            .setContentText("Mantendo aplicativo sempre ativo")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun startMainActivityChecker() {
        Log.d(TAG, "🔧 Starting MainActivity checker...")

        checkRunnable = object : Runnable {
            override fun run() {
                try {
                    Log.d(TAG, "🔍 Checking if MainActivity is running...")

                    val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager

                    // Verificar processos em execução
                    val runningProcesses = activityManager.runningAppProcesses
                    var appIsRunning = false
                    var isInForeground = false

                    runningProcesses?.forEach { processInfo ->
                        if (processInfo.processName == packageName) {
                            appIsRunning = true
                            isInForeground = processInfo.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                            Log.d(TAG, "📱 App process found: ${processInfo.processName}, importance: ${processInfo.importance}, foreground: $isInForeground")
                        }
                    }

                    // Método alternativo - verificar tarefas recentes (requer API menor)
                    var hasRecentTask = false
                    try {
                        @Suppress("DEPRECATION")
                        val recentTasks = activityManager.getRunningTasks(1)
                        if (recentTasks.isNotEmpty()) {
                            val topTask = recentTasks[0]
                            hasRecentTask = topTask.topActivity?.packageName == packageName
                            Log.d(TAG, "🔝 Top task: ${topTask.topActivity?.packageName}, is our app: $hasRecentTask")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Could not check running tasks: ${e.message}")
                    }

                    val shouldRestart = !appIsRunning || (!isInForeground && !hasRecentTask)

                    if (shouldRestart) {
                        Log.d(TAG, "⚠️ MainActivity not active (running: $appIsRunning, foreground: $isInForeground, recentTask: $hasRecentTask)")
                        startMainActivity()
                    } else {
                        Log.d(TAG, "✅ MainActivity is active")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error checking MainActivity: ${e.message}", e)
                    // Em caso de erro, sempre tentar reiniciar
                    Log.d(TAG, "🔄 Error occurred, attempting to restart MainActivity...")
                    startMainActivity()
                }

                // Verificar novamente em 15 segundos (mais frequente)
                handler.postDelayed(this, 15000)
            }
        }

        // Iniciar verificação após 5 segundos para dar tempo do serviço inicializar
        handler.postDelayed(checkRunnable!!, 5000)
        Log.d(TAG, "⏰ MainActivity checker scheduled to start in 5 seconds")
    }

    private fun startMainActivity() {
        try {
            Log.d(TAG, "🎬 Starting MainActivity from service...")

            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
            }

            startActivity(launchIntent)
            Log.d(TAG, "✅ MainActivity started from service")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting MainActivity from service: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 KioskService destroyed")

        checkRunnable?.let { handler.removeCallbacks(it) }

        // Restart the service
        val restartIntent = Intent(this, KioskService::class.java)
        startService(restartIntent)
    }
}