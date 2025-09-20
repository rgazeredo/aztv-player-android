package br.tv.az.player

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

@UnstableApi
class VideoDownloadManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VideoDownloadManager"
        private const val OFFLINE_STORAGE_LIMIT = 1024L * 1024L * 1024L // 1GB
        private const val DOWNLOAD_CACHE_DIR = "offline_videos"

        @Volatile
        private var INSTANCE: VideoDownloadManager? = null

        fun getInstance(context: Context): VideoDownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VideoDownloadManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val downloadCache: SimpleCache
    val downloadManager: DownloadManager
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // Estado dos downloads
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress

    private val _downloadedVideos = MutableStateFlow<Set<String>>(emptySet())
    val downloadedVideos: StateFlow<Set<String>> = _downloadedVideos

    init {
        Log.d(TAG, "Initializing VideoDownloadManager")

        // Configurar cache para downloads offline
        val downloadCacheDir = File(context.getExternalFilesDir(null), DOWNLOAD_CACHE_DIR)
        val evictor = NoOpCacheEvictor() // Não remove downloads automaticamente
        downloadCache = SimpleCache(downloadCacheDir, evictor, StandaloneDatabaseProvider(context))

        // Configurar DownloadManager do ExoPlayer
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)

        downloadManager = DownloadManager(
            context,
            StandaloneDatabaseProvider(context),
            downloadCache,
            httpDataSourceFactory,
            Runnable::run
        ).apply {
            maxParallelDownloads = 2
            addListener(DownloadManagerListener())
            resumeDownloads()
        }

        // Carregar estado inicial dos downloads
        loadDownloadedVideos()

        Log.d(TAG, "VideoDownloadManager initialized successfully")
    }

    private inner class DownloadManagerListener : DownloadManager.Listener {
        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
            Log.d(TAG, "Download changed: ${download.request.id} - State: ${download.state}")

            when (download.state) {
                Download.STATE_DOWNLOADING -> {
                    val progress = if (download.bytesDownloaded > 0 && download.contentLength > 0) {
                        (download.bytesDownloaded.toFloat() / download.contentLength.toFloat())
                    } else {
                        0f
                    }
                    updateDownloadProgress(download.request.id, progress)
                    Log.d(TAG, "Download progress for ${download.request.id}: ${(progress * 100).toInt()}%")
                }
                Download.STATE_COMPLETED -> {
                    updateDownloadProgress(download.request.id, 1.0f)
                    markVideoAsDownloaded(download.request.id)
                    Log.d(TAG, "Download completed: ${download.request.id}")
                }
                Download.STATE_FAILED -> {
                    finalException?.let { Log.e(TAG, "Download failed: ${download.request.id}", it) }
                    updateDownloadProgress(download.request.id, 0f)
                }
                Download.STATE_STOPPED -> {
                    Log.d(TAG, "Download stopped: ${download.request.id}")
                }
            }
        }
    }

    fun startVideoDownload(video: MainActivity.Video) {
        if (video.type != "video") {
            Log.d(TAG, "Skipping non-video content for download: ${video.title}")
            return
        }

        if (isVideoDownloaded(video.id)) {
            Log.d(TAG, "Video already downloaded: ${video.title}")
            return
        }

        Log.d(TAG, "Starting download for video: ${video.title}")

        coroutineScope.launch {
            try {
                // Verificar espaço disponível
                if (!hasEnoughSpace()) {
                    Log.w(TAG, "Not enough storage space for download: ${video.title}")
                    cleanupOldDownloads()
                    if (!hasEnoughSpace()) {
                        Log.e(TAG, "Still not enough space after cleanup for: ${video.title}")
                        return@launch
                    }
                }

                // Criar request de download do ExoPlayer
                val downloadRequest = DownloadRequest.Builder(video.id, android.net.Uri.parse(video.url))
                    .build()

                // Iniciar download via DownloadService
                withContext(Dispatchers.Main) {
                    DownloadService.sendAddDownload(
                        context,
                        VideoDownloadService::class.java,
                        downloadRequest,
                        false
                    )
                }

                Log.d(TAG, "Download request sent for: ${video.title}")

            } catch (e: Exception) {
                Log.e(TAG, "Error starting download for ${video.title}: ${e.message}", e)
            }
        }
    }

    fun downloadNonVideoContent(video: MainActivity.Video) {
        if (video.type == "video") return

        Log.d(TAG, "Downloading non-video content: ${video.title} (${video.type})")

        coroutineScope.launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(video.url)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val contentFile = getOfflineContentFile(video.id, video.type)
                        Log.d(TAG, "💾 Saving content to: ${contentFile.absolutePath}")
                        contentFile.parentFile?.mkdirs()

                        response.body?.byteStream()?.use { inputStream ->
                            FileOutputStream(contentFile).use { outputStream ->
                                val bytesWritten = inputStream.copyTo(outputStream)
                                Log.d(TAG, "📝 Wrote $bytesWritten bytes to ${contentFile.name}")
                            }
                        }

                        Log.d(TAG, "✅ File exists after write: ${contentFile.exists()}, size: ${contentFile.length()}")
                        markVideoAsDownloaded(video.id)
                        Log.d(TAG, "Non-video content downloaded: ${video.title}")
                    } else {
                        Log.e(TAG, "Failed to download content: ${video.title} - ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading content ${video.title}: ${e.message}", e)
            }
        }
    }

    fun isVideoDownloaded(videoId: String): Boolean {
        // Para vídeos, verificar no DownloadManager
        val download = downloadManager.downloadIndex.getDownload(videoId)
        if (download != null && download.state == Download.STATE_COMPLETED) {
            return true
        }

        // Para outros conteúdos, verificar arquivo
        return _downloadedVideos.value.contains(videoId)
    }

    fun getOfflineVideoUri(videoId: String): android.net.Uri? {
        return try {
            // Primeiro, verificar se há download completo via DownloadManager
            val download = downloadManager.downloadIndex.getDownload(videoId)
            if (download != null && download.state == Download.STATE_COMPLETED) {
                Log.d(TAG, "Found completed download for $videoId")
                return download.request.uri
            }

            // Se não encontrou pelo DownloadManager, verificar arquivos de cache simples
            val downloadFile = File(context.getExternalFilesDir(DOWNLOAD_CACHE_DIR), "$videoId.mp4")
            if (downloadFile.exists()) {
                Log.d(TAG, "Found simple cache file for $videoId")
                return android.net.Uri.fromFile(downloadFile)
            }

            // Para debug: listar o que existe no diretório
            val downloadDir = File(context.getExternalFilesDir(null), DOWNLOAD_CACHE_DIR)
            if (downloadDir.exists()) {
                Log.d(TAG, "Cache directory contents for $videoId:")
                downloadDir.listFiles()?.forEach { file ->
                    Log.d(TAG, "  Found: ${file.name} (${if (file.isDirectory) "dir" else "file"})")
                    if (file.isDirectory) {
                        file.listFiles()?.forEach { subFile ->
                            Log.d(TAG, "    Subfile: ${subFile.name}")
                        }
                    }
                }
            }

            Log.w(TAG, "No offline video found for $videoId")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "Error getting offline video URI for $videoId: ${e.message}")
            null
        }
    }

    fun getOfflineContentFile(videoId: String, contentType: String): File {
        val extension = when (contentType) {
            "html" -> "html"
            "image" -> "jpg"
            else -> "dat"
        }
        return File(context.getExternalFilesDir(DOWNLOAD_CACHE_DIR), "$videoId.$extension")
    }

    fun removeDownload(videoId: String) {
        Log.d(TAG, "Removing download: $videoId")

        coroutineScope.launch {
            try {
                // Remover do DownloadManager
                withContext(Dispatchers.Main) {
                    DownloadService.sendRemoveDownload(
                        context,
                        VideoDownloadService::class.java,
                        videoId,
                        false
                    )
                }

                // Remover arquivo de conteúdo não-vídeo se existir
                val contentFile = getOfflineContentFile(videoId, "")
                if (contentFile.exists()) {
                    contentFile.delete()
                }

                // Atualizar estado
                val currentDownloaded = _downloadedVideos.value.toMutableSet()
                currentDownloaded.remove(videoId)
                _downloadedVideos.value = currentDownloaded

                Log.d(TAG, "Download removed: $videoId")

            } catch (e: Exception) {
                Log.e(TAG, "Error removing download $videoId: ${e.message}", e)
            }
        }
    }

    fun getDownloadProgress(videoId: String): Float {
        return _downloadProgress.value[videoId] ?: 0f
    }

    fun getTotalDownloadedSize(): Long {
        var totalSize = 0L

        try {
            // Contar tamanho aproximado baseado no diretório de cache
            val downloadDir = File(context.getExternalFilesDir(null), DOWNLOAD_CACHE_DIR)
            if (downloadDir.exists()) {
                downloadDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        totalSize += file.length()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating download size: ${e.message}")
        }

        return totalSize
    }

    private fun hasEnoughSpace(): Boolean {
        val currentSize = getTotalDownloadedSize()
        val available = OFFLINE_STORAGE_LIMIT - currentSize
        val minimumRequired = 100L * 1024L * 1024L // 100MB mínimo

        return available > minimumRequired
    }

    private fun cleanupOldDownloads() {
        Log.d(TAG, "Starting cleanup of old downloads")

        coroutineScope.launch {
            try {
                // Limpeza simples baseada em arquivos antigos
                val downloadDir = File(context.getExternalFilesDir(null), DOWNLOAD_CACHE_DIR)
                if (downloadDir.exists()) {
                    val files = downloadDir.listFiles()?.filter { it.isFile } ?: emptyList()
                    val filesToDelete = files.sortedBy { it.lastModified() }
                        .take(files.size / 4) // Remove 25% dos arquivos mais antigos

                    filesToDelete.forEach { file ->
                        try {
                            val videoId = file.nameWithoutExtension
                            removeDownload(videoId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error removing file ${file.name}: ${e.message}")
                        }
                    }
                }

                Log.d(TAG, "Cleanup completed")

            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup: ${e.message}", e)
            }
        }
    }

    private fun updateDownloadProgress(videoId: String, progress: Float) {
        val currentProgress = _downloadProgress.value.toMutableMap()
        currentProgress[videoId] = progress
        _downloadProgress.value = currentProgress
    }

    private fun markVideoAsDownloaded(videoId: String) {
        val currentDownloaded = _downloadedVideos.value.toMutableSet()
        currentDownloaded.add(videoId)
        _downloadedVideos.value = currentDownloaded
    }

    private fun loadDownloadedVideos() {
        coroutineScope.launch {
            try {
                val downloadedSet = mutableSetOf<String>()

                // Carregar conteúdos baixados do diretório
                val downloadDir = File(context.getExternalFilesDir(null), DOWNLOAD_CACHE_DIR)
                if (downloadDir.exists()) {
                    Log.d(TAG, "Scanning download directory: ${downloadDir.absolutePath}")

                    // Função recursiva para escanear todos os arquivos e subdiretórios
                    fun scanDirectory(dir: File, level: String = "") {
                        dir.listFiles()?.forEach { file ->
                            Log.d(TAG, "${level}Found: ${file.name} (${if (file.isFile) "file" else "dir"}) - size: ${if (file.isFile) file.length() else "N/A"}")

                            if (file.isFile) {
                                // Verificar se é um arquivo de conteúdo válido
                                val fileName = file.name
                                val nameWithoutExt = file.nameWithoutExtension

                                // Pular arquivos de sistema
                                if (fileName.endsWith(".db") || fileName.endsWith(".uid") || fileName.endsWith(".tmp")) {
                                    Log.d(TAG, "${level}  Skipping system file: $fileName")
                                    return@forEach
                                }

                                // Verificar se é um arquivo de conteúdo
                                if (fileName.endsWith(".html") || fileName.endsWith(".jpg") ||
                                    fileName.endsWith(".jpeg") || fileName.endsWith(".png") ||
                                    fileName.endsWith(".mp4") || fileName.endsWith(".dat")) {

                                    downloadedSet.add(nameWithoutExt)
                                    Log.d(TAG, "${level}  ✅ Added to downloaded set: '$nameWithoutExt' (from file: $fileName)")
                                } else {
                                    Log.d(TAG, "${level}  ❓ Unknown file type: $fileName")
                                }
                            } else if (file.isDirectory) {
                                Log.d(TAG, "${level}  📁 Scanning subdirectory: ${file.name}")
                                scanDirectory(file, level + "    ")
                            }
                        }
                    }

                    scanDirectory(downloadDir)

                    // Verificar também arquivos específicos esperados diretamente
                    Log.d(TAG, "🔍 Checking for specific content files...")
                    val knownContentTypes = listOf(
                        "megasena" to listOf("html"),
                        "UOL" to listOf("html"),
                        "Image 1" to listOf("jpg", "jpeg", "png"),
                        "Image 2" to listOf("jpg", "jpeg", "png")
                    )

                    knownContentTypes.forEach { (id, extensions) ->
                        extensions.forEach { ext ->
                            val file = File(downloadDir, "$id.$ext")
                            Log.d(TAG, "  Checking: ${file.absolutePath} - exists: ${file.exists()}")
                            if (file.exists()) {
                                downloadedSet.add(id)
                                Log.d(TAG, "  ✅ Added to downloaded set: '$id' (explicit check: $id.$ext)")
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "Download directory does not exist: ${downloadDir.absolutePath}")
                }

                _downloadedVideos.value = downloadedSet
                Log.d(TAG, "🎯 Final result: Loaded ${downloadedSet.size} downloaded items: $downloadedSet")

            } catch (e: Exception) {
                Log.e(TAG, "Error loading downloaded videos: ${e.message}", e)
            }
        }
    }

    fun release() {
        Log.d(TAG, "Releasing VideoDownloadManager")
        try {
            downloadManager.release()
            downloadCache.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VideoDownloadManager: ${e.message}")
        }
    }
}