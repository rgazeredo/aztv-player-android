package br.tv.az.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler

@UnstableApi
class VideoDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DOWNLOAD_NOTIFICATION_CHANNEL_ID,
    R.string.download_notification_channel_name,
    R.string.download_notification_channel_description
) {

    companion object {
        private const val TAG = "VideoDownloadService"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "video_downloads"
        private const val JOB_ID = 1000
    }

    override fun getDownloadManager(): DownloadManager {
        return VideoDownloadManager.getInstance(this).downloadManager
    }

    override fun getScheduler(): Scheduler? {
        // Não usar scheduler para Android TV (sempre conectado à energia)
        return null
    }

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        return createDownloadNotification(downloads)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VideoDownloadService created")
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "VideoDownloadService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (notificationManager.getNotificationChannel(DOWNLOAD_NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    DOWNLOAD_NOTIFICATION_CHANNEL_ID,
                    getString(R.string.download_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.download_notification_channel_description)
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                }

                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Download notification channel created")
            }
        }
    }

    private fun createDownloadNotification(downloads: List<Download>): Notification {
        val downloadingCount = downloads.count { it.state == Download.STATE_DOWNLOADING }
        val completedCount = downloads.count { it.state == Download.STATE_COMPLETED }
        val failedCount = downloads.count { it.state == Download.STATE_FAILED }

        val title = when {
            downloadingCount > 0 -> "Baixando vídeos ($downloadingCount)"
            completedCount > 0 -> "Downloads completos ($completedCount)"
            else -> "Gerenciando downloads"
        }

        val text = when {
            downloadingCount > 0 -> {
                val totalProgress = downloads
                    .filter { it.state == Download.STATE_DOWNLOADING }
                    .map { if (it.contentLength > 0) it.bytesDownloaded.toFloat() / it.contentLength else 0f }
                    .average()
                    .takeIf { !it.isNaN() } ?: 0.0

                "Progresso: ${(totalProgress * 100).toInt()}%"
            }
            failedCount > 0 -> "Alguns downloads falharam ($failedCount)"
            else -> "Todos os downloads estão atualizados"
        }

        return NotificationCompat.Builder(this, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(downloadingCount > 0)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }
}