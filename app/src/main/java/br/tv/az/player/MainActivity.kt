package br.tv.az.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.content.Intent
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewCompat
import androidx.webkit.WebSettingsCompat
import android.widget.ProgressBar
import android.widget.TextView
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsServiceConnection
// Removido imports do GeckoView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TVVideoPlaylist"
        private const val API_URL = "https://az.tv.br/json/videos.json" // Substitua pela sua URL
        private const val CACHE_SIZE = 500L * 1024L * 1024L // 500MB de cache
        private const val CONTROLS_TIMEOUT = 3000L // 3 segundos
//        private const val UPDATE_CHECK_INTERVAL = 5 * 60 * 1000L // 5 minutos
        private const val UPDATE_CHECK_INTERVAL = 1 * 60 * 1000L // 5 minutos
    }

    private lateinit var playerView: PlayerView
    private lateinit var webView: WebView
    private lateinit var imageView: ImageView
    private lateinit var player: ExoPlayer
    private lateinit var loadingView: View
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var overlayContainer: ConstraintLayout
    private lateinit var videoTitle: TextView
    private lateinit var videoCounter: TextView
    private lateinit var downloadStatus: TextView
    private lateinit var controlsInfo: TextView
    private lateinit var versionNumber: TextView

    private var videoList = mutableListOf<Video>()
    private var currentVideoIndex = 0
    private var isTransitioning = false
    private var consecutiveErrors = 0
    private val handler = Handler(Looper.getMainLooper())
    private var controlsRunnable: Runnable? = null
    private var htmlTimerRunnable: Runnable? = null
    private var imageTimerRunnable: Runnable? = null
    private var updateCheckRunnable: Runnable? = null
    private var currentContentType = "video" // "video" ou "html"
    private var currentHtmlTime: Int? = null // tempo em segundos para a página HTML atual
    private var lastJsonHash = ""
    private var customTabsServiceConnection: CustomTabsServiceConnection? = null
    private var customTabsIntent: CustomTabsIntent? = null

    private lateinit var cache: SimpleCache
    private lateinit var cacheDataSourceFactory: DataSource.Factory

    data class Video(
        val id: String,
        val url: String,
        val title: String,
        val type: String, // "video", "html" ou "image"
        val time: Int? = null, // tempo em segundos para páginas HTML e imagens
        var localPath: String? = null,
        var isDownloaded: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Manter tela ligada
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initializeViews()
        initializeCache()
        initializePlayer()

        // Carregar vídeos
        lifecycleScope.launch {
            loadVideos()
        }

        // Iniciar verificação periódica de atualizações
        startPeriodicUpdateCheck()

        // Inicializar Custom Chrome Tabs
        setupCustomTabs()

        // GeckoView removido por problemas de dependência
    }

    private fun initializeViews() {
        playerView = findViewById(R.id.playerView)
        webView = findViewById(R.id.webView)
        imageView = findViewById(R.id.imageView)
        loadingView = findViewById(R.id.loadingView)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        overlayContainer = findViewById(R.id.overlayContainer)
        videoTitle = findViewById(R.id.videoTitle)
        videoCounter = findViewById(R.id.videoCounter)
        downloadStatus = findViewById(R.id.downloadStatus)
        controlsInfo = findViewById(R.id.controlsInfo)
        versionNumber = findViewById(R.id.versionNumber)

        // Definir versão
        versionNumber.text = "v1.2"

        // Configurar player view para TV
        playerView.useController = false
        playerView.keepScreenOn = true

        // Configurar WebView com recursos modernos do Chromium
        setupModernWebView()

        // Esconder overlay inicialmente
        overlayContainer.visibility = View.GONE
    }

    private fun initializeCache() {
        val cacheDir = File(cacheDir, "video_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE)
        cache = SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(this))

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS)
            .setReadTimeoutMs(DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS)
            .setAllowCrossProtocolRedirects(true)

        cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(this, httpDataSourceFactory))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }


    private fun initializePlayer() {
        player = ExoPlayer.Builder(this)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        15000,  // Min buffer
                        50000,  // Max buffer
                        2500,   // Buffer for playback
                        5000    // Buffer for playback after rebuffer
                    )
                    .build()
            )
            .build()

        playerView.player = player

        // Listener para eventos do player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val currentVideo = if (videoList.isNotEmpty()) videoList[currentVideoIndex].title else "Unknown"
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        Log.d(TAG, "Video ended: $currentVideo")
                        playNextContent()
                    }
                    Player.STATE_READY -> {
                        Log.d(TAG, "Video ready to play: $currentVideo")
                        isTransitioning = false
                        consecutiveErrors = 0 // Reset error counter on successful load

                        // Mostrar PlayerView apenas quando vídeo estiver pronto
                        if (currentContentType == "video") {
                            Log.d(TAG, "Showing PlayerView - video is ready")
                            playerView.visibility = View.VISIBLE
                        }

                        // Garantir que o vídeo comece a tocar
                        if (!player.isPlaying && player.playWhenReady) {
                            Log.d(TAG, "Starting playback for: $currentVideo")
                            player.play()
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        Log.d(TAG, "Buffering: $currentVideo")
                    }
                    Player.STATE_IDLE -> {
                        Log.d(TAG, "Player idle: $currentVideo")
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val currentVideo = if (videoList.isNotEmpty()) videoList[currentVideoIndex].title else "Unknown"
                Log.e(TAG, "Player error on $currentVideo: ${error.message}")
                Log.e(TAG, "Error details: ${error.cause}")

                consecutiveErrors++
                Log.w(TAG, "Consecutive errors: $consecutiveErrors")

                // Resetar isTransitioning para permitir transição
                isTransitioning = false

                // Se há muitos erros consecutivos, parar para evitar loop infinito
                if (consecutiveErrors >= videoList.size) {
                    Log.e(TAG, "Too many consecutive errors, stopping playback")
                    showError("Erro: Todos os vídeos falharam ao carregar")
                    return
                }

                // Tentar próximo conteúdo em caso de erro
                Log.d(TAG, "Skipping problematic content and moving to next")
                handler.postDelayed({ playNextContent() }, 1000)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val currentVideo = if (videoList.isNotEmpty()) videoList[currentVideoIndex].title else "Unknown"
                Log.d(TAG, "isPlayingChanged: $isPlaying for video: $currentVideo")
            }
        })

        player.playWhenReady = true
        player.repeatMode = Player.REPEAT_MODE_OFF
    }

    private suspend fun loadVideos() {
        withContext(Dispatchers.Main) {
            showLoading("Carregando lista de vídeos...")
        }

        try {
            val response = withContext(Dispatchers.IO) {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(API_URL)
                    .build()

                client.newCall(request).execute()
            }

            if (response.isSuccessful) {
                val jsonString = response.body?.string() ?: "[]"
                val videosArray = org.json.JSONArray(jsonString)

                videoList.clear()
                for (i in 0 until videosArray.length()) {
                    val videoJson = videosArray.getJSONObject(i)
                    videoList.add(
                        Video(
                            id = videoJson.getString("id"),
                            url = videoJson.getString("url"),
                            title = videoJson.optString("title", "Item ${i + 1}"),
                            type = videoJson.optString("type", "video"),
                            time = if (videoJson.has("time")) videoJson.getInt("time") else null
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    if (videoList.isNotEmpty()) {
                        Log.d(TAG, "Loaded ${videoList.size} items:")
                        videoList.forEachIndexed { index, video ->
                            Log.d(TAG, "Item $index: ${video.title} - ${video.type} - ${video.url}")
                        }

                        // Calcular hash do JSON para comparação futura
                        lastJsonHash = calculateJsonHash(jsonString)

                        hideLoading()
                        playContent(0)
                    } else {
                        showError("Nenhum conteúdo encontrado")
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    showError("Erro ao carregar vídeos: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading videos", e)
            withContext(Dispatchers.Main) {
                showError("Erro: ${e.message}")
            }
        }
    }

    private fun playContent(index: Int) {
        if (index < 0 || index >= videoList.size) return

        currentVideoIndex = index
        val content = videoList[index]
        currentContentType = content.type

        Log.d(TAG, "Playing content #${index + 1}/${videoList.size}: ${content.title} (${content.type})")
        Log.d(TAG, "Content URL: ${content.url}")

        if (content.time != null) {
            Log.d(TAG, "Content time: ${content.time} seconds")
        }

        when (content.type) {
            "video" -> playVideo(content)
            "html" -> playHtml(content)
            "image" -> playImage(content)
            else -> {
                Log.e(TAG, "Unknown content type: ${content.type}")
                playNextContent()
            }
        }

        // Atualizar UI
        updateContentInfo()

        // Resetar flag de transição após carregar conteúdo
        isTransitioning = false
        Log.d(TAG, "Content loaded, isTransitioning reset to false")
    }

    private fun setupModernWebView() {
        try {
            Log.d(TAG, "Setting up modern Chromium WebView")

            // Verificar recursos disponíveis do WebView
            val webViewVersion = WebViewCompat.getCurrentWebViewPackage(this)?.versionName
            Log.d(TAG, "WebView version: $webViewVersion")

            // Configurações básicas otimizadas (baseado em SuperWebView)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true

                // Configurações para forçar layout DESKTOP
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(false)  // Desabilitar zoom para TV (como SuperWebView)
                builtInZoomControls = false
                displayZoomControls = false

                // Layout algorithm para desktop
                layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL

                // Texto menor para simular desktop
                textZoom = 85

                // Layout será controlado por CSS injection

                // User agent DESKTOP moderno para forçar layout desktop (atualizado)
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

                // Configurações de cache e rede
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                // Recursos modernos e otimizações de browser
                mediaPlaybackRequiresUserGesture = false

                // Configurações adicionais para melhor renderização (baseado em melhores práticas)
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowUniversalAccessFromFileURLs = false
                allowFileAccessFromFileURLs = false
            }

//            // Configurações avançadas do Chromium (baseado em SuperWebView)
//            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
//                WebSettingsCompat.setForceDark(webView.settings, WebSettingsCompat.FORCE_DARK_OFF)
//                Log.d(TAG, "Force dark mode disabled for better compatibility")
//            }
//
//            // Habilitar dark mode algorítmico para Android 13+ (como SuperWebView)
//            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
//                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
//                Log.d(TAG, "Algorithmic darkening enabled for modern Android versions")
//            }

            // Aceleração de hardware
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // Definir escala inicial para desktop (60% para melhor visualização)
            webView.setInitialScale(65)

            // WebViewClient moderno
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Modern WebView page finished: $url")

                    // Só mostrar WebView se não for about:blank (limpeza)
                    if (url != null && url != "about:blank" && currentContentType == "html") {
                        Log.d(TAG, "Showing WebView after page loaded")
                        webView.visibility = View.VISIBLE
                    }

                    // Injeção de CSS para forçar layout DESKTOP
                    val css = """
                        javascript:(function() {
                            try {
                            // Remover meta viewport mobile se existir
                            var viewport = document.querySelector('meta[name="viewport"]');
                            if (viewport) {
                                viewport.remove();
                            }

                            // Forçar viewport desktop
                            var desktopViewport = document.createElement('meta');
                            desktopViewport.name = 'viewport';
                            desktopViewport.content = 'width=1400, initial-scale=0.65, user-scalable=yes';
                            document.head.appendChild(desktopViewport);

                            // Forçar carregamento de Google Fonts
                            if (!document.querySelector('link[href*="fonts.googleapis.com"]')) {
                                var link = document.createElement('link');
                                link.rel = 'stylesheet';
                                link.href = 'https://fonts.googleapis.com/css2?family=Open+Sans:wght@300;400;600;700&display=swap';
                                link.crossOrigin = 'anonymous';
                                document.head.appendChild(link);
                            }

                            // Aplicar estilos para layout desktop
                            setTimeout(function() {
                                var style = document.createElement('style');
                                style.innerHTML = \`
                                    /* Forçar layout desktop */
                                    html, body {
                                        min-width: 1400px !important;
                                        width: 1400px !important;
                                        font-family: 'Open Sans', Arial, Helvetica, sans-serif !important;
                                        -webkit-font-smoothing: antialiased !important;
                                        -moz-osx-font-smoothing: grayscale !important;
                                        overflow-x: hidden !important;
                                        zoom: 1.0 !important;
                                        transform: none !important;
                                        margin: 0 !important;
                                        padding: 0 !important;
                                    }

                                    /* Remover media queries mobile */
                                    @media (max-width: 768px) {
                                        body { min-width: 1400px !important; width: 1400px !important; }
                                    }
                                    @media (max-width: 1024px) {
                                        body { min-width: 1400px !important; width: 1400px !important; }
                                    }

                                    /* Forçar containers largos */
                                    .container, .main-content, .content, .wrapper, .row, .col, .column {
                                        min-width: 1400px !important;
                                        width: 1400px !important;
                                        max-width: none !important;
                                        margin: 0 auto !important;
                                    }

                                    /* Garantir que tabelas e elementos relacionados apareçam */
                                    table, .table, .info-table, tbody, thead, tr, td, th {
                                        display: table !important;
                                        visibility: visible !important;
                                        opacity: 1 !important;
                                        width: auto !important;
                                        border-collapse: collapse !important;
                                        height: auto !important;
                                        max-height: none !important;
                                    }

                                    tbody { display: table-row-group !important; }
                                    thead { display: table-header-group !important; }
                                    tr { display: table-row !important; }
                                    td, th { display: table-cell !important; padding: 8px !important; }

                                    /* Garantir que divs com conteúdo apareçam */
                                    div[class*="result"], div[class*="table"], div[class*="content"],
                                    div[class*="info"], div[class*="data"], .resultados {
                                        display: block !important;
                                        visibility: visible !important;
                                        opacity: 1 !important;
                                        height: auto !important;
                                        max-height: none !important;
                                        overflow: visible !important;
                                    }

                                    /* Garantir que imagens se ajustem */
                                    img {
                                        max-width: 100% !important;
                                        height: auto !important;
                                        display: inline-block !important;
                                    }

                                    /* Forçar exibição de elementos de texto */
                                    p, h1, h2, h3, h4, h5, h6, div {
                                        display: block !important;
                                        visibility: visible !important;
                                        opacity: 1 !important;
                                    }

                                    /* Especificamente para elementos inline */
                                    span, em, strong, b, i, a {
                                        display: inline !important;
                                        visibility: visible !important;
                                        opacity: 1 !important;
                                    }

                                    /* Remover limitações de altura e overflow */
                                    * {
                                        max-height: none !important;
                                        overflow: visible !important;
                                        pointer-events: auto !important;
                                    }
                                \`;
                                document.head.appendChild(style);
                                console.log('Desktop layout styles applied');

                                // Aguardar um pouco mais e forçar visibilidade com mais inteligência
                                setTimeout(function() {
                                    // Forçar todos os elementos a serem visíveis
                                    var elements = document.querySelectorAll('*');
                                    for (var i = 0; i < elements.length; i++) {
                                        var el = elements[i];
                                        var computedStyle = window.getComputedStyle(el);

                                        // Forçar display baseado no tipo de elemento
                                        if (computedStyle.display === 'none' || el.style.display === 'none') {
                                            if (el.tagName === 'TABLE') {
                                                el.style.display = 'table';
                                            } else if (el.tagName === 'TBODY') {
                                                el.style.display = 'table-row-group';
                                            } else if (el.tagName === 'THEAD') {
                                                el.style.display = 'table-header-group';
                                            } else if (el.tagName === 'TR') {
                                                el.style.display = 'table-row';
                                            } else if (el.tagName === 'TD' || el.tagName === 'TH') {
                                                el.style.display = 'table-cell';
                                            } else if (el.tagName === 'DIV' || el.tagName === 'P' || el.tagName === 'H1' || el.tagName === 'H2' || el.tagName === 'H3') {
                                                el.style.display = 'block';
                                            } else if (el.tagName === 'SPAN' || el.tagName === 'A' || el.tagName === 'EM' || el.tagName === 'STRONG') {
                                                el.style.display = 'inline';
                                            } else {
                                                el.style.display = '';
                                            }
                                        }

                                        // Forçar visibilidade
                                        if (computedStyle.visibility === 'hidden' || el.style.visibility === 'hidden') {
                                            el.style.visibility = 'visible';
                                        }

                                        // Forçar opacidade
                                        if (computedStyle.opacity === '0' || el.style.opacity === '0') {
                                            el.style.opacity = '1';
                                        }

                                        // Remover limitações de altura
                                        if (el.style.maxHeight && el.style.maxHeight !== 'none') {
                                            el.style.maxHeight = 'none';
                                        }
                                    }

                                    // Forçar especificamente elementos que podem conter tabelas
                                    var containers = document.querySelectorAll('div, section, article, main');
                                    for (var j = 0; j < containers.length; j++) {
                                        var container = containers[j];
                                        container.style.height = 'auto';
                                        container.style.maxHeight = 'none';
                                        container.style.overflow = 'visible';
                                    }

                                    console.log('Forced all elements to be visible with proper display types');
                                }, 1500);
                            }, 500);

                            } catch (e) {
                                console.log('CSS injection error: ' + e.message);
                            }
                        })();
                    """.trimIndent()

                    view?.evaluateJavascript(css, null)

                    // Configurar timer HTML se especificado e se estamos exibindo HTML
                    if (currentContentType == "html" && currentHtmlTime != null) {
                        val timeInSeconds = currentHtmlTime!!
                        Log.d(TAG, "Starting HTML timer for $timeInSeconds seconds")
                        htmlTimerRunnable?.let { handler.removeCallbacks(it) }
                        htmlTimerRunnable = Runnable {
                            Log.d(TAG, "HTML timer expired, moving to next content")
                            playNextContent()
                        }
                        handler.postDelayed(htmlTimerRunnable!!, timeInSeconds * 1000L)
                    } else if (currentContentType == "html") {
                        // Se não há tempo especificado, usar timeout padrão de segurança
                        Log.d(TAG, "No time specified for HTML, using default 10 seconds")
                        htmlTimerRunnable?.let { handler.removeCallbacks(it) }
                        htmlTimerRunnable = Runnable {
                            Log.d(TAG, "HTML safety timer expired, moving to next content")
                            playNextContent()
                        }
                        handler.postDelayed(htmlTimerRunnable!!, 10000L) // 10 segundos padrão
                    }
                }

                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    Log.e(TAG, "Modern WebView error: $errorCode - $description for URL: $failingUrl")
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d(TAG, "Modern WebView started loading: $url")
                }
            }

            // WebChromeClient para capturar erros de JavaScript
            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let { msg ->
                        val level = when (msg.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                            ConsoleMessage.MessageLevel.WARNING -> "WARNING"
                            ConsoleMessage.MessageLevel.LOG -> "LOG"
                            ConsoleMessage.MessageLevel.DEBUG -> "DEBUG"
                            ConsoleMessage.MessageLevel.TIP -> "TIP"
                            else -> "UNKNOWN"
                        }
                        Log.d(TAG, "WebView Console [$level]: ${msg.message()} at ${msg.sourceId()}:${msg.lineNumber()}")

                        // Se for erro de JavaScript crítico, continuar mesmo assim
                        if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            Log.w(TAG, "JavaScript error detected but continuing playback")
                        }
                    }
                    return true
                }
            }

            Log.d(TAG, "Modern WebView setup completed")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up modern WebView: ${e.message}")
        }
    }

    private fun playImage(imageContent: Video) {
        try {
            Log.d(TAG, "Loading image: ${imageContent.title}")

            // PRIMEIRO: Esconder todos os componentes (mantém imagem escondida até carregar)
            webView.visibility = View.GONE
            playerView.visibility = View.GONE
            imageView.visibility = View.GONE

            // Limpar WebView para evitar flash
            webView.loadUrl("about:blank")

            // NÃO mostrar ImageView ainda - só quando imagem estiver carregada

            // Parar reprodução de vídeo se estiver ativa
            player.stop()
            player.clearMediaItems()

            // Limpar timer HTML
            currentHtmlTime = null

            // Carregar imagem da URL e configurar timer após carregamento
            val displayTime = (imageContent.time ?: 10) * 1000L // default 10 segundos se não especificado
            Log.d(TAG, "Image will be displayed for ${displayTime / 1000} seconds")
            loadImageFromUrl(imageContent.url, displayTime)

            Log.d(TAG, "Image loaded successfully: ${imageContent.title}")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading image ${imageContent.title}: ${e.message}")
            // Tentar próximo conteúdo se houver erro
            handler.postDelayed({ playNextContent() }, 2000)
        }
    }

    private fun loadImageFromUrl(url: String, displayTime: Long) {
        try {
            // Usar OkHttp para carregar a imagem em background
            Thread {
                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()

                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            response.body?.byteStream()?.let { inputStream ->
                                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)

                                // Voltar para a thread principal para atualizar a UI
                                runOnUiThread {
                                    if (bitmap != null) {
                                        imageView.setImageBitmap(bitmap)
                                        Log.d(TAG, "Image bitmap set successfully from URL: $url")

                                        // Mostrar ImageView apenas quando imagem estiver carregada
                                        Log.d(TAG, "Showing ImageView - image is loaded")
                                        imageView.visibility = View.VISIBLE

                                        // Configurar timer para próximo conteúdo após imagem carregada
                                        Log.d(TAG, "Starting timer for ${displayTime / 1000} seconds")
                                        imageTimerRunnable?.let { handler.removeCallbacks(it) }
                                        imageTimerRunnable = Runnable {
                                            Log.d(TAG, "Image timer expired, moving to next content")
                                            playNextContent()
                                        }
                                        handler.postDelayed(imageTimerRunnable!!, displayTime)
                                    } else {
                                        Log.e(TAG, "Failed to decode bitmap from URL: $url")
                                        playNextContent()
                                    }
                                }
                            } ?: run {
                                Log.e(TAG, "Response body is null for URL: $url")
                                runOnUiThread { playNextContent() }
                            }
                        } else {
                            Log.e(TAG, "HTTP error ${response.code} loading image from URL: $url")
                            runOnUiThread { playNextContent() }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading image from URL: ${e.message}")
                    runOnUiThread {
                        // Em caso de erro, carregar próximo conteúdo
                        playNextContent()
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting image loading thread: ${e.message}")
            playNextContent()
        }
    }

    private fun playVideo(video: Video) {
        try {
            // PRIMEIRO: Esconder todos os componentes (mantém player escondido até estar pronto)
            webView.visibility = View.GONE
            playerView.visibility = View.GONE
            imageView.visibility = View.GONE

            // Limpar WebView para evitar flash
            webView.loadUrl("about:blank")

            // NÃO mostrar player ainda - só quando vídeo estiver pronto

            // Parar reprodução atual
            player.stop()
            player.clearMediaItems()

            // Limpar timer HTML
            currentHtmlTime = null

            // Criar media source com cache
            val mediaSource: MediaSource = ProgressiveMediaSource.Factory(cacheDataSourceFactory)
                .createMediaSource(MediaItem.fromUri(android.net.Uri.parse(video.url)))

            // Preparar e tocar
            player.setMediaSource(mediaSource)
            player.playWhenReady = true
            player.prepare()

            Log.d(TAG, "Media source set for: ${video.title}")

        } catch (e: Exception) {
            Log.e(TAG, "Error playing video ${video.title}: ${e.message}")
            // Tentar próximo conteúdo se houver erro
            handler.postDelayed({ playNextContent() }, 2000)
        }
    }

    private fun playHtml(htmlContent: Video) {
        try {
            Log.d(TAG, "Loading HTML with modern Chromium WebView: ${htmlContent.title}")

            // PRIMEIRO: Esconder todos os componentes e carregar nova URL imediatamente
            webView.visibility = View.GONE
            playerView.visibility = View.GONE
            imageView.visibility = View.GONE

            player.stop()
            player.clearMediaItems()

            // Carregar nova URL IMEDIATAMENTE - sem esperar limpeza
            val headers = mutableMapOf<String, String>()
            headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
            headers["Accept-Language"] = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
            headers["Accept-Encoding"] = "gzip, deflate, br"
            headers["Cache-Control"] = "no-cache"
            headers["Pragma"] = "no-cache"
            headers["Sec-Fetch-Dest"] = "document"
            headers["Sec-Fetch-Mode"] = "navigate"
            headers["Sec-Fetch-Site"] = "none"
            headers["Sec-Fetch-User"] = "?1"
            headers["Upgrade-Insecure-Requests"] = "1"

            // Armazenar o tempo para configurar timer após página carregar
            currentHtmlTime = htmlContent.time

            // Carregar nova URL imediatamente
            webView.loadUrl(htmlContent.url, headers)
            Log.d(TAG, "WebView URL loaded, waiting for onPageFinished to show")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading HTML ${htmlContent.title}: ${e.message}")
            handler.postDelayed({ playNextContent() }, 2000)
        }
    }

    private fun setupCustomTabs() {
        // Placeholder para futuras melhorias
        Log.d(TAG, "Custom tabs setup - placeholder")
    }

    private fun openWithExternalBrowser(url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Verificar se há um navegador disponível
            val resolveInfo = packageManager.queryIntentActivities(intent, 0)
            if (resolveInfo.isNotEmpty()) {
                startActivity(intent)
                Log.d(TAG, "Opened URL with external browser: $url")
                return true
            } else {
                Log.w(TAG, "No browser app found to open URL: $url")
                return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open with external browser: ${e.message}")
            false
        }
    }

    private fun playNextContent() {
        if (isTransitioning) {
            Log.d(TAG, "Already transitioning, skipping playNextContent")
            return
        }
        isTransitioning = true

        // Cancelar timers se estiverem rodando
        htmlTimerRunnable?.let { handler.removeCallbacks(it) }
        imageTimerRunnable?.let { handler.removeCallbacks(it) }

        val nextIndex = if (currentVideoIndex < videoList.size - 1) {
            currentVideoIndex + 1
        } else {
            0 // Loop para o início
        }

        Log.d(TAG, "Moving to next content: $currentVideoIndex -> $nextIndex (total items: ${videoList.size})")

        if (nextIndex == 0) {
            Log.d(TAG, "LOOPING: Reached end of playlist, returning to first item")
        }
        playContent(nextIndex)

        // Timeout para resetar isTransitioning em caso de problema
        handler.postDelayed({
            if (isTransitioning) {
                Log.w(TAG, "Transition timeout, resetting isTransitioning flag")
                isTransitioning = false
            }
        }, 10000) // 10 segundos
    }

    private fun playPreviousContent() {
        if (isTransitioning) {
            Log.d(TAG, "Already transitioning, skipping playPreviousContent")
            return
        }
        isTransitioning = true

        // Cancelar timers se estiverem rodando
        htmlTimerRunnable?.let { handler.removeCallbacks(it) }
        imageTimerRunnable?.let { handler.removeCallbacks(it) }

        val prevIndex = if (currentVideoIndex > 0) {
            currentVideoIndex - 1
        } else {
            videoList.size - 1 // Vai para o último
        }

        Log.d(TAG, "Moving to previous content: $currentVideoIndex -> $prevIndex")
        playContent(prevIndex)

        // Timeout para resetar isTransitioning em caso de problema
        handler.postDelayed({
            if (isTransitioning) {
                Log.w(TAG, "Transition timeout, resetting isTransitioning flag")
                isTransitioning = false
            }
        }, 10000) // 10 segundos
    }

    private fun togglePlayPause() {
        if (currentContentType == "video") {
            val currentContent = if (videoList.isNotEmpty()) videoList[currentVideoIndex].title else "Unknown"
            if (player.isPlaying) {
                Log.d(TAG, "Pausing video: $currentContent")
                player.pause()
            } else {
                Log.d(TAG, "Resuming video: $currentContent")
                player.play()
            }
            showControlsTemporarily()
        } else {
            // Para conteúdo HTML, apenas mostrar controles
            showControlsTemporarily()
        }
    }

    private fun updateContentInfo() {
        if (videoList.isNotEmpty()) {
            val content = videoList[currentVideoIndex]
            videoTitle.text = content.title
            videoCounter.text = "${currentVideoIndex + 1} / ${videoList.size}"

            when (content.type) {
                "video" -> downloadStatus.text = "🎥 Vídeo"
                "html" -> {
                    val timeText = content.time?.let { " (${it}s)" } ?: ""
                    downloadStatus.text = "🌐 HTML$timeText"
                }
                else -> downloadStatus.text = "📄 ${content.type}"
            }
        }
    }

    private fun showControlsTemporarily() {
        overlayContainer.visibility = View.VISIBLE
        updateContentInfo()

        // Cancelar timeout anterior
        controlsRunnable?.let { handler.removeCallbacks(it) }

        // Criar novo timeout
        controlsRunnable = Runnable {
            overlayContainer.visibility = View.GONE
        }
        handler.postDelayed(controlsRunnable!!, CONTROLS_TIMEOUT)
    }

    private fun showLoading(message: String) {
        loadingView.visibility = View.VISIBLE
        statusText.text = message
        playerView.visibility = View.GONE
    }

    private fun hideLoading() {
        loadingView.visibility = View.GONE
        playerView.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        loadingView.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        statusText.text = message
        playerView.visibility = View.GONE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_SPACE -> {
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                playPreviousContent()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playNextContent()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showControlsTemporarily()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }


    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun startPeriodicUpdateCheck() {
        updateCheckRunnable = Runnable {
            lifecycleScope.launch {
                checkForUpdates()
            }
            // Reagendar próxima verificação
            updateCheckRunnable?.let { handler.postDelayed(it, UPDATE_CHECK_INTERVAL) }
        }
        handler.postDelayed(updateCheckRunnable!!, UPDATE_CHECK_INTERVAL)
        Log.d(TAG, "Periodic update check started - checking every ${UPDATE_CHECK_INTERVAL / 1000 / 60} minutes")
    }

    private suspend fun checkForUpdates() {
        try {
            Log.d(TAG, "Checking for playlist updates...")

            val response = withContext(Dispatchers.IO) {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(API_URL)
                    .build()

                client.newCall(request).execute()
            }

            if (response.isSuccessful) {
                val jsonString = response.body?.string() ?: "[]"
                val newJsonHash = calculateJsonHash(jsonString)

                if (newJsonHash != lastJsonHash) {
                    Log.d(TAG, "Playlist changed detected! Reloading content...")

                    withContext(Dispatchers.Main) {
                        // Salvar posição atual se possível
                        val currentPosition = currentVideoIndex
                        val wasPlaying = if (currentContentType == "video") player.isPlaying else false

                        // Recarregar playlist
                        loadVideosFromJson(jsonString)

                        // Tentar manter posição similar ou ir para o início
                        val newIndex = if (currentPosition < videoList.size) currentPosition else 0
                        playContent(newIndex)

                        // Restaurar estado de reprodução se era vídeo
                        if (currentContentType == "video" && wasPlaying) {
                            player.play()
                        }
                    }
                } else {
                    Log.d(TAG, "No changes detected in playlist")
                }
            } else {
                Log.w(TAG, "Failed to check for updates: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
        }
    }

    private fun loadVideosFromJson(jsonString: String) {
        try {
            val videosArray = org.json.JSONArray(jsonString)
            val newVideoList = mutableListOf<Video>()

            for (i in 0 until videosArray.length()) {
                val videoJson = videosArray.getJSONObject(i)
                newVideoList.add(
                    Video(
                        id = videoJson.getString("id"),
                        url = videoJson.getString("url"),
                        title = videoJson.optString("title", "Item ${i + 1}"),
                        type = videoJson.optString("type", "video"),
                        time = if (videoJson.has("time")) videoJson.getInt("time") else null
                    )
                )
            }

            // Atualizar lista e hash
            videoList.clear()
            videoList.addAll(newVideoList)
            lastJsonHash = calculateJsonHash(jsonString)

            Log.d(TAG, "Updated playlist with ${videoList.size} items")
            videoList.forEachIndexed { index, video ->
                Log.d(TAG, "Item $index: ${video.title} - ${video.type} - ${video.url}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing updated JSON", e)
        }
    }

    private fun calculateJsonHash(jsonString: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(jsonString.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating hash", e)
            jsonString.hashCode().toString()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        cache.release()

        // Limpar todos os handlers
        controlsRunnable?.let { handler.removeCallbacks(it) }
        htmlTimerRunnable?.let { handler.removeCallbacks(it) }
        imageTimerRunnable?.let { handler.removeCallbacks(it) }
        updateCheckRunnable?.let { handler.removeCallbacks(it) }

        // Cleanup WebView
        try {
            webView.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up WebView: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        player.pause()
        // Pausar verificações de atualização para economizar bateria
        updateCheckRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onResume() {
        super.onResume()
        if (::player.isInitialized) {
            player.play()
        }
        // Retomar verificações de atualização
        startPeriodicUpdateCheck()
    }
}