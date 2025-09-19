package br.tv.az.player

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class VideoDownloadService : Service() {

    companion object {
        private const val TAG = "VideoDownloadService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Download service started")
        // Implementação simplificada para cache persistente
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Download service destroyed")
    }
}