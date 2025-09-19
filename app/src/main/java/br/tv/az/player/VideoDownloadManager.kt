package br.tv.az.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object VideoDownloadManager {
    private const val DOWNLOAD_CACHE_SIZE = 1024L * 1024L * 1024L // 1GB para downloads

    @Volatile
    private var downloadCache: SimpleCache? = null

    @Synchronized
    fun getInstance(context: Context): SimpleCache {
        if (downloadCache == null) {
            val downloadDirectory = File(context.getExternalFilesDir(null), "offline_videos")
            downloadCache = SimpleCache(
                downloadDirectory,
                NoOpCacheEvictor(), // Não remove downloads automaticamente
                StandaloneDatabaseProvider(context)
            )
        }
        return downloadCache!!
    }

    fun releaseManager() {
        downloadCache?.release()
        downloadCache = null
    }
}