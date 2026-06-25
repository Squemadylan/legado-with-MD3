package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.config.readConfig.ReadConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.GsonBuilder
import android.util.Base64
import java.util.UUID
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {

    companion object {
        private const val DOUBAO_TTS_URL = "https://openspeech.bytedance.com/api/v1/tts"
        private const val MIMO_TTS_URL = "https://api.xiaomimimo.com/v1/chat/completions"
    }

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }

    // 改为外部存储
    private val ttsFolderPath: String by lazy {
        val baseDir = externalCacheDir ?: cacheDir
        baseDir.absolutePath + File.separator + "httpTTS" + File.separator
    }

    private val cache by lazy {
        val baseDir = externalCacheDir ?: cacheDir
        SimpleCache(
            File(baseDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var playIndexJob: Job? = null
    private var downloadErrorNo: Int = 0
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            removeCacheFile()
        }
    }

    override fun play() {
        pageChanged = false
        exoPlayer.stop()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            if (ReadConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    override fun playStop() {
        exoPlayer.stop()
        playIndexJob?.cancel()
    }

    private fun updateNextPos() {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            nextChapter()
        }
    }

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                
                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    // 计算文件名时，会自动调用修正后的 md5SpeakFileName
                    val fileName = md5SpeakFileName(text)
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
                        createSilentSound(fileName)
                    } else if (!hasSpeakFile(fileName)) {
                        runCatching {
                            val inputStream = getSpeakStream(httpTts, speakText)
                            if (inputStream != null) {
                                createSpeakFile(fileName, inputStream)
                            } else {
                                createSilentSound(fileName)
                            }
                        }.onFailure {
                            when (it) {
                                is CancellationException -> Unit
                                else -> pauseReadAloud()
                            }
                            return@execute
                        }
                    }
                    val file = getSpeakFileAsMd5(fileName)
                    val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                    launch(Main) {
                        exoPlayer.addMediaItem(mediaItem)
                    }
                }
                preDownloadAudios(httpTts)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    // 辅助方法：确保能读到文件
    private fun getChapterContent(book: Book, chapter: BookChapter): String? {
        return BookHelp.getContent(book, chapter)
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS) {
        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        val limit = AppConfig.audioPreDownloadNum
        
        try {
            for (i in 1..limit) {
                currentCoroutineContext().ensureActive()
                
                val targetIndex = currentIdx + i
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, targetIndex) ?: break
                
                // 1. 获取内容
                val contentString = getChapterContent(book, chapter)
                if (contentString.isNullOrEmpty()) continue // 内容没下载，跳过

                val contentList = contentString.split("\n").filter { it.isNotEmpty() }

                contentList.forEach { content ->
                    currentCoroutineContext().ensureActive()
                    
                    // 2. 生成文件名：必须用 chapter.title (数据库原始标题)
                    val titleMd5 = MD5Utils.md5Encode16(chapter.title)
                    val extraKey = when {
                        ReadAloud.httpTTS?.ttsType == "doubao" -> "-voice=${ReadAloud.httpTTS?.doubaoVoiceType}"
                        ReadAloud.httpTTS?.ttsType == "mimo" -> "-voice=${ReadAloud.httpTTS?.mimoVoice}"
                        else -> ""
                    }
                    val contentMd5 = MD5Utils.md5Encode16("${ReadAloud.httpTTS?.url}-|-$speechRate${extraKey}-|-$content")
                    val fileName = "${titleMd5}_${contentMd5}"
                    
                    val speakText = content.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        createSilentSound(fileName)
                    } else if (!hasSpeakFile(fileName)) {
                        // 3. 文件不存在才下载
                        runCatching {
                            val inputStream = getSpeakStream(httpTts, speakText)
                            if (inputStream != null) {
                                createSpeakFile(fileName, inputStream)
                            } else {
                                createSilentSound(fileName)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.put("听书预下载异常: ${e.localizedMessage}", e)
        }
    }

    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val downloaderChannel = Channel<Downloader>()
                launch {
                    for (downloader in downloaderChannel) {
                        downloader.download(null)
                    }
                }
                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$speakText")
                    }
                    val fileName = md5SpeakFileName(text)
                    val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                    val downloader = createDownloader(dataSourceFactory, fileName)
                    downloaderChannel.send(downloader)
                    val mediaSource = createMediaSource(dataSourceFactory, fileName)
                    launch(Main) {
                        exoPlayer.addMediaSource(mediaSource)
                    }
                }
                preDownloadAudiosStream(httpTts, downloaderChannel)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS,
        downloaderChannel: Channel<Downloader>
    ) {
        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        val limit = AppConfig.audioPreDownloadNum
        
        try {
            for (i in 1..limit) {
                currentCoroutineContext().ensureActive()
                val targetIndex = currentIdx + i
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, targetIndex) ?: break
                
                val contentString = getChapterContent(book, chapter)
                if (contentString.isNullOrEmpty()) continue

                val contentList = contentString.split("\n").filter { it.isNotEmpty() }
                
                contentList.forEach { content ->
                    currentCoroutineContext().ensureActive()
                    // 同样使用数据库标题，保持一致
                    val titleMd5 = MD5Utils.md5Encode16(chapter.title)
                    val extraKey = when {
                        ReadAloud.httpTTS?.ttsType == "doubao" -> "-voice=${ReadAloud.httpTTS?.doubaoVoiceType}"
                        ReadAloud.httpTTS?.ttsType == "mimo" -> "-voice=${ReadAloud.httpTTS?.mimoVoice}"
                        else -> ""
                    }
                    val contentMd5 = MD5Utils.md5Encode16("${ReadAloud.httpTTS?.url}-|-$speechRate${extraKey}-|-$content")
                    val fileName = "${titleMd5}_${contentMd5}"
                    
                    val speakText = content.replace(AppPattern.notReadAloudRegex, "")
                    val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                    val downloader = createDownloader(dataSourceFactory, fileName)
                    downloaderChannel.send(downloader)
                }
            }
        } catch (e: Exception) {
            AppLog.put("听书流式预下载异常: ${e.localizedMessage}", e)
        }
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            getSpeakStream(httpTts, speakText)
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException,
                            is CancellationException -> Unit

                            else -> pauseReadAloud()
                        }
                    }.getOrThrow()
                } ?: resources.openRawResource(R.raw.silent_sound)
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = fileName.toUri()
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    private fun createMediaSource(factory: DataSource.Factory, fileName: String): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(MediaItem.fromUri(fileName))
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String
    ): InputStream? {
        // 豆包 TTS 走专用路径
        if (httpTts.ttsType == "doubao") {
            return getDoubaoSpeakStream(httpTts, speakText)
        }
        // MiMo TTS 走专用路径
        if (httpTts.ttsType == "mimo") {
            return getMimoSpeakStream(httpTts, speakText)
        }
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = speechRate,
                    source = httpTts,
                    readTimeout = 300 * 1000L,
                    coroutineContext = currentCoroutineContext()
                )
                var response = analyzeUrl.getResponseAwait()
                currentCoroutineContext().ensureActive()
                val checkJs = httpTts.loginCheckJs
                if (checkJs?.isNotBlank() == true) {
                    response = analyzeUrl.evalJS(checkJs, response) as Response
                }
                response.headers["Content-Type"]?.let { contentType ->
                    val contentType = contentType.substringBefore(";")
                    val ct = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        throw NoStackTraceException(response.body.string())
                    } else if (ct?.isNotBlank() == true) {
                        if (!contentType.matches(ct.toRegex())) {
                            throw NoStackTraceException(
                                "TTS服务器返回错误：" + response.body.string()
                            )
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                response.body.byteStream().let { stream ->
                    downloadErrorNo = 0
                    return stream
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        downloadErrorNo++
                        if (downloadErrorNo > 5) {
                            val msg = "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            throw e
                        }
                    }

                    else -> {
                        downloadErrorNo++
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        AppLog.put(msg, e)
                        e.printOnDebug()
                        if (downloadErrorNo > 5) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.put(msg1, e, true)
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText")
                            break
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * 豆包 TTS 专用请求：发送 JSON，解析 Base64 音频响应
     * 参考：https://www.volcengine.com/docs/6561/79820
     */
    private fun getDoubaoSpeakStream(
        httpTts: HttpTTS,
        speakText: String
    ): InputStream? {
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val appId = httpTts.doubaoAppId
        val accessToken = httpTts.doubaoAccessToken
        if (appId.isNullOrBlank() || accessToken.isNullOrBlank()) {
            AppLog.put("豆包 TTS 配置不完整：缺少 AppID 或 AccessToken")
            return null
        }
        // 文本截断（豆包 API 单次最大约 500 字符）
        val truncatedText = speakText.take(480)
        if (truncatedText.isBlank()) return null
        // 构造请求体
        val requestBody = mapOf(
            "app" to mapOf(
                "appid" to appId,
                "token" to accessToken,
                "cluster" to "volcano_tts"
            ),
            "user" to mapOf(
                "uid" to "legado_${android.os.Build.FINGERPRINT.take(16)}"
            ),
            "audio" to mapOf(
                "voice_type" to httpTts.doubaoVoiceType,
                "encoding" to "mp3",
                "rate" to 24000,
                "speed_ratio" to httpTts.doubaoSpeedRatio,
                "volume_ratio" to httpTts.doubaoVolumeRatio,
                "pitch_ratio" to httpTts.doubaoPitchRatio,
                "emotion" to httpTts.doubaoEmotion,
                "language" to httpTts.doubaoLanguage
            ),
            "request" to mapOf(
                "reqid" to UUID.randomUUID().toString(),
                "text" to truncatedText,
                "text_type" to "plain",
                "operation" to "query"
            )
        )
        val jsonBody = gson.toJson(requestBody)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(DOUBAO_TTS_URL)
            .addHeader("Authorization", "Bearer;$accessToken")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody(mediaType))
            .build()
        return try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLog.put("豆包 TTS HTTP 错误: ${response.code}")
                return null
            }
            val bodyStr = response.body.string()
            val responseJson = gson.fromJson(bodyStr, Map::class.java)
            val code = (responseJson["code"] as? Number)?.toInt()
            if (code != 3000) {
                val msg = responseJson["message"] ?: "unknown"
                AppLog.put("豆包 TTS 业务错误: code=$code, message=$msg")
                return null
            }
            val b64Data = responseJson["data"] as? String
            if (b64Data.isNullOrBlank()) {
                AppLog.put("豆包 TTS 响应中 data 字段为空")
                return null
            }
            val audioBytes = Base64.decode(b64Data, Base64.NO_WRAP)
            downloadErrorNo = 0
            audioBytes.inputStream()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("豆包 TTS 请求异常: ${e.localizedMessage}", e)
            null
        }
    }

    /**
     * MiMo TTS 专用请求：OpenAI chat completion 格式，解析 Base64 音频响应
     * 参考：https://mimo.mi.com/docs/zh-CN/quick-start/usage-guide/audio/speech-synthesis-v2.5
     */
    private fun getMimoSpeakStream(
        httpTts: HttpTTS,
        speakText: String
    ): InputStream? {
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val apiKey = httpTts.mimoApiKey
        if (apiKey.isNullOrBlank()) {
            AppLog.put("MiMo TTS 配置不完整：缺少 API Key")
            return null
        }
        val truncatedText = speakText.take(2000)
        if (truncatedText.isBlank()) return null
        // 构造 messages
        val messages = mutableListOf<Map<String, String>>()
        // user message: 风格指令（可选）
        val style = httpTts.mimoStyle
        if (!style.isNullOrBlank()) {
            messages.add(mapOf("role" to "user", "content" to style))
        } else {
            messages.add(mapOf("role" to "user", "content" to ""))
        }
        // assistant message: 要合成的文本
        messages.add(mapOf("role" to "assistant", "content" to truncatedText))
        // 构造 voice
        val voice = if (httpTts.mimoModel == "mimo-v2.5-tts-voicedesign"
            && !httpTts.mimoVoiceDesign.isNullOrBlank()) {
            httpTts.mimoVoiceDesign
        } else {
            httpTts.mimoVoice
        }
        val requestBody = mapOf(
            "model" to httpTts.mimoModel,
            "messages" to messages,
            "audio" to mapOf(
                "format" to "pcm16",
                "voice" to voice
            )
        )
        val jsonBody = gson.toJson(requestBody)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(MIMO_TTS_URL)
            .addHeader("api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody(mediaType))
            .build()
        return try {
            AppLog.put("MiMo TTS 请求: model=${httpTts.mimoModel}, voice=$voice, text=${truncatedText.take(50)}...")
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = try { response.body.string().take(500) } catch (_: Exception) { "" }
                AppLog.put("MiMo TTS HTTP 错误: ${response.code}\n$errBody")
                return null
            }
            val bodyStr = response.body.string()
            AppLog.put("MiMo TTS 响应 (${bodyStr.length} chars): ${bodyStr.take(500)}")
            val responseJson = gson.fromJson(bodyStr, Map::class.java)
            // 解析 choices[0].message.audio.data
            val choices = responseJson["choices"] as? List<*> ?: run {
                AppLog.put("MiMo TTS 响应中无 choices 字段")
                return null
            }
            val firstChoice = choices.firstOrNull() as? Map<*, *> ?: run {
                AppLog.put("MiMo TTS choices 为空")
                return null
            }
            val message = firstChoice["message"] as? Map<*, *> ?: run {
                AppLog.put("MiMo TTS 响应中无 message 字段")
                return null
            }
            val audio = message["audio"] as? Map<*, *> ?: run {
                AppLog.put("MiMo TTS 响应中无 audio 字段")
                return null
            }
            val b64Data = audio["data"] as? String
            if (b64Data.isNullOrBlank()) {
                AppLog.put("MiMo TTS 响应中 audio.data 为空")
                return null
            }
            val audioBytes = Base64.decode(b64Data, Base64.NO_WRAP)
            // pcm16 格式需要添加 WAV 头才能被 ExoPlayer 播放
            val wavBytes = addWavHeader(audioBytes, 24000, 1, 16)
            AppLog.put("MiMo TTS 音频数据: ${audioBytes.size} PCM → ${wavBytes.size} WAV")
            downloadErrorNo = 0
            wavBytes.inputStream()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("MiMo TTS 请求异常: ${e.localizedMessage}", e)
            null
        }
    }

    /**
     * 为 PCM 数据添加 WAV 头
     */
    private fun addWavHeader(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val header = ByteArray(44)
        // RIFF header
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        val fileSize = 36 + dataSize
        header[4] = (fileSize and 0xFF).toByte()
        header[5] = (fileSize shr 8 and 0xFF).toByte()
        header[6] = (fileSize shr 16 and 0xFF).toByte()
        header[7] = (fileSize shr 24 and 0xFF).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        // fmt chunk
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // chunk size
        header[20] = 1; header[21] = 0 // PCM format
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xFF).toByte()
        header[25] = (sampleRate shr 8 and 0xFF).toByte()
        header[26] = (sampleRate shr 16 and 0xFF).toByte()
        header[27] = (sampleRate shr 24 and 0xFF).toByte()
        header[28] = (byteRate and 0xFF).toByte()
        header[29] = (byteRate shr 8 and 0xFF).toByte()
        header[30] = (byteRate shr 16 and 0xFF).toByte()
        header[31] = (byteRate shr 24 and 0xFF).toByte()
        header[32] = blockAlign.toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        // data chunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (dataSize and 0xFF).toByte()
        header[41] = (dataSize shr 8 and 0xFF).toByte()
        header[42] = (dataSize shr 16 and 0xFF).toByte()
        header[43] = (dataSize shr 24 and 0xFF).toByte()
        return header + pcmData
    }

    /**
     * 生成音频文件名
     */
    private fun md5SpeakFileName(content: String, textChapter: TextChapter? = this.textChapter): String {
        val titleToUse = textChapter?.chapter?.title ?: ""
        val httpTts = ReadAloud.httpTTS
        val extraKey = when {
            httpTts?.ttsType == "doubao" -> "-voice=${httpTts.doubaoVoiceType}"
            httpTts?.ttsType == "mimo" -> "-voice=${httpTts.mimoVoice}"
            else -> ""
        }
        return MD5Utils.md5Encode16(titleToUse) + "_" +
                MD5Utils.md5Encode16("${ReadAloud.httpTTS?.url}-|-$speechRate${extraKey}-|-$content")
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
    }

    private fun hasSpeakFile(name: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$name.mp3")
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String, inputStream: InputStream) {
        FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3").outputStream().use { out ->
            inputStream.use {
                it.copyTo(out)
            }
        }
    }

    /**
     * 移除缓存文件
     * 如果时间设置为0，则不再保护当前章节，退出即全删。
     */
    private fun removeCacheFile() {
        val keepTime = AppConfig.audioCacheCleanTime
        // 只有当时间大于0时，才需要保护当前章节。如果为0，说明用户想彻底不留缓存。
        val protectCurrentChapter = keepTime > 0
        val titleMd5 = if (protectCurrentChapter) MD5Utils.md5Encode16(this.textChapter?.chapter?.title ?: "") else ""

        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
            val isSilentSound = it.length() == 2160L

            // 判断逻辑：
            // 1. 如果是无声文件 -> 删
            // 2. 如果保留时间设为0 -> 删 (不管是不是当前章节)
            // 3. 如果保留时间>0 -> 保护当前章节，且只删过期的
            val shouldDelete = if (keepTime == 0L) {
                // 模式：即听即焚 (保留时间0)
                true
            } else {
                // 模式：保留一段时间
                // 条件：(不是当前章节) 且 (时间过期了)
                !it.name.startsWith(titleMd5) && (System.currentTimeMillis() - it.lastModified() > keepTime)
            }

            if (shouldDelete || isSilentSound) {
                FileUtils.delete(it.absolutePath)
            }
        }
    }


    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = contentList[nowSpeak].length
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..contentList[nowSpeak].length) {
                if (pageIndex + 1 < textChapter.pageSize
                    && readAloudNumber + i > textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + i.toInt())
                }
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        if (ReadConfig.streamReadAloudAudio) {
            downloadAndPlayAudiosStream()
        } else {
            downloadAndPlayAudios()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                // 结束
                playErrorNo = 0
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        updateNextPos()
        upPlayPos()
        upMediaMetadata(showContent = true)
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.put("朗读错误\n${contentList[nowSpeak]}", error)
        deleteCurrentSpeakFile()
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    private fun deleteCurrentSpeakFile() {
        if (ReadConfig.streamReadAloudAudio) {
            return
        }
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

}
