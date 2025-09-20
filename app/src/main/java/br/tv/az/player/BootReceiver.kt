package br.tv.az.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Handler
import android.os.Looper

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "🚀 Boot receiver triggered: ${intent.action}")
        Log.d(TAG, "Intent extras: ${intent.extras}")
        Log.d(TAG, "Package: ${context.packageName}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {

                Log.d(TAG, "✅ Device boot/package event detected, starting AZTVPlayer in 3 seconds...")

                // Aguardar um pouco para o sistema estabilizar
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        Log.d(TAG, "🎬 Starting MainActivity...")

                        val launchIntent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                        }

                        context.startActivity(launchIntent)
                        Log.d(TAG, "✅ AZTVPlayer started successfully")

                        // Também iniciar o KioskService para manter o app rodando
                        val kioskServiceIntent = Intent(context, KioskService::class.java)
                        context.startService(kioskServiceIntent)
                        Log.d(TAG, "🔧 KioskService started")

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error starting AZTVPlayer on boot: ${e.message}", e)

                        // Tentar novamente em 5 segundos
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                Log.d(TAG, "🔄 Retry attempt...")
                                val retryIntent = Intent(context, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(retryIntent)
                                Log.d(TAG, "✅ AZTVPlayer started on retry")
                            } catch (e2: Exception) {
                                Log.e(TAG, "❌ Retry failed: ${e2.message}", e2)
                            }
                        }, 5000)
                    }
                }, 3000)
            }
            else -> {
                Log.d(TAG, "🤷 Unknown action: ${intent.action}")
            }
        }
    }
}