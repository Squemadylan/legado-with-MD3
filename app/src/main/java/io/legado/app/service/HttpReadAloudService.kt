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
import io.legado.app.help.tts.HttpTtsSynthesizer
import io.legado.app.help.tts.TtsAudioCache
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.TtsAudioCacheModel
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
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
import splitties.init.appCtx
import java.io.File
import java.io.InputStream

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build().also { player ->
            // 关闭跳过静音，避免把段首弱音/气声裁掉
            player.skipSilenceEnabled = false
        }
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
        // 清掉旧版拼接静音临时文件，避免误播无声 lead_*.mp3
        runCatching {
            File(ttsFolderPath).listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith("lead_")) f.delete()
            }
        }
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
        // 与系统 TTS 一致：跳过纯标点/空白段（如「……」），避免短静音播完清空队列后卡死
        do {
            if (nowSpeak !in contentList.indices) {
                nextChapter()
                return
            }
            readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
            paragraphStartPos = 0
            if (nowSpeak < contentList.lastIndex) {
                nowSpeak++
            } else {
                nextChapter()
                return
            }
        } while (isUnreadableParagraph(contentList[nowSpeak]))
    }

    /** 「……」等纯标点/空白段：不应合成也不应进播放列表 */
    private fun isUnreadableParagraph(text: String): Boolean {
        if (text.isEmpty()) return true
        if (text.matches(AppPattern.notReadAloudRegex)) return true
        return text.replace(AppPattern.notReadAloudRegex, "").isEmpty()
    }

    /** 从当前位置跳过不可朗读段；若本章已无可读内容则进入下一章 */
    private fun skipUnreadableParagraphsOrNextChapter(): Boolean {
        while (nowSpeak in contentList.indices && isUnreadableParagraph(contentList[nowSpeak])) {
            readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
            paragraphStartPos = 0
            nowSpeak++
        }
        if (nowSpeak !in contentList.indices) {
            nextChapter()
            return false
        }
        return true
    }

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val book = ReadBook.book
                val chapterIndex = textChapter?.chapter?.index ?: ReadBook.durChapterIndex
                val chapterTitle = textChapter?.chapter?.title ?: ""
                val cacheRunningAtStart = TtsAudioCacheModel.isRun
                if (!skipUnreadableParagraphsOrNextChapter()) {
                    return@withLock
                }

                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    // 纯标点段不加入播放列表（否则极短静音播完会 STATE_ENDED 卡死）
                    if (isUnreadableParagraph(content)) return@forEachIndexed
                    var text = content
                    val midParagraph = paragraphStartPos > 0 && index == nowSpeak
                    if (midParagraph) {
                        text = text.substring(paragraphStartPos)
                    }
                    val file = resolveSpeakAudioFile(
                        httpTts = httpTts,
                        book = book,
                        chapterIndex = chapterIndex,
                        chapterTitle = chapterTitle,
                        paragraphIndex = index,
                        text = text,
                        midParagraph = midParagraph,
                    ) ?: return@execute
                    launch(Main) {
                        addSpeakFileToPlayer(file)
                    }
                }
                // 批量缓存进行中时不做预下载合成，避免抢 API、干扰缓存进度
                if (!cacheRunningAtStart && !TtsAudioCacheModel.isRun) {
                    preDownloadAudios(httpTts)
                }
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    /**
     * 解析本段朗读音频。
     * 批量缓存进行中：只读 Download/Yuedu 本地文件（缺则等待缓存写出），绝不发起合成。
     */
    private suspend fun resolveSpeakAudioFile(
        httpTts: HttpTTS,
        book: Book?,
        chapterIndex: Int,
        chapterTitle: String,
        paragraphIndex: Int,
        text: String,
        midParagraph: Boolean,
    ): File? {
        val fileName = md5SpeakFileName(text)
        val speakText = text.replace(AppPattern.notReadAloudRegex, "")

        if (book != null) {
            // 缓存任务进行中：整段读本地（含段中起读），等待落盘，绝不合成以免抢 API
            if (TtsAudioCacheModel.isRun) {
                awaitCachedParagraphFile(book, chapterIndex, chapterTitle, paragraphIndex)?.let {
                    return it
                }
                if (TtsAudioCacheModel.isRun) {
                    AppLog.put(
                        "缓存进行中且本段暂无本地音频，使用静音占位（不合成）: " +
                                "$chapterTitle#$paragraphIndex"
                    )
                    createSilentSound(fileName)
                    return getSpeakFileAsMd5(fileName)
                }
                // 缓存刚结束仍无文件则继续走下方合成
            } else if (!midParagraph) {
                TtsAudioCache.findParagraphFile(book, chapterIndex, chapterTitle, paragraphIndex)
                    ?.let { return it }
            }
        }

        val canUsePersist = !midParagraph && book != null
        if (speakText.isEmpty()) {
            AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
            createSilentSound(fileName)
            return getSpeakFileAsMd5(fileName)
        }
        if (!hasSpeakFile(fileName)) {
            runCatching {
                val result = HttpTtsSynthesizer.synthesize(httpTts, speakText, speechRate)
                if (result != null) {
                    createSpeakFile(fileName).writeBytes(result.bytes)
                    if (canUsePersist) {
                        persistParagraphAudio(
                            book!!, chapterIndex, chapterTitle, paragraphIndex,
                            result.bytes, result.extension, httpTts.name
                        )
                    }
                } else {
                    createSilentSound(fileName)
                }
            }.onFailure {
                when (it) {
                    is CancellationException -> Unit
                    else -> pauseReadAloud()
                }
                return null
            }
        } else if (canUsePersist) {
            val tmp = getSpeakFileAsMd5(fileName)
            if (tmp.exists()) {
                val ext = tmp.extension.ifBlank { "mp3" }
                persistParagraphAudio(
                    book!!, chapterIndex, chapterTitle, paragraphIndex,
                    tmp.readBytes(), ext, httpTts.name
                )
            }
        }
        return getSpeakFileAsMd5(fileName)
    }

    /**
     * 等待批量缓存服务写出段落文件。
     * 缓存结束后或超过 [maxWaitMs] 仍没有则返回 null（避免卡死朗读）。
     */
    private suspend fun awaitCachedParagraphFile(
        book: Book,
        chapterIndex: Int,
        title: String,
        paragraphIndex: Int,
        maxWaitMs: Long = 180_000L,
    ): File? {
        TtsAudioCache.findParagraphFile(book, chapterIndex, title, paragraphIndex)?.let {
            return it
        }
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (TtsAudioCacheModel.isRun && System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            TtsAudioCache.findParagraphFile(book, chapterIndex, title, paragraphIndex)?.let {
                return it
            }
            delay(300)
        }
        return TtsAudioCache.findParagraphFile(book, chapterIndex, title, paragraphIndex)
    }

    private fun persistParagraphAudio(
        book: Book,
        chapterIndex: Int,
        title: String,
        paragraphIndex: Int,
        bytes: ByteArray,
        extension: String,
        engineName: String,
    ) {
        if (bytes.isEmpty()) return
        TtsAudioCache.writeParagraph(
            book = book,
            chapterIndex = chapterIndex,
            title = title,
            paragraphIndex = paragraphIndex,
            bytes = bytes,
            extension = extension,
            engineName = engineName,
        )
    }

    // 辅助方法：确保能读到文件
    private fun getChapterContent(book: Book, chapter: BookChapter): String? {
        return BookHelp.getContent(book, chapter)
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS) {
        if (TtsAudioCacheModel.isRun) return
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

                // 与朗读 contentList 一致：含标题为第 0 段，避免持久缓存索引偏一
                val contentList = TtsAudioCache.paragraphsForCache(book, chapter, contentString)

                contentList.forEachIndexed { pIndex, content ->
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
                        runCatching {
                            val result = HttpTtsSynthesizer.synthesize(httpTts, speakText, speechRate)
                            if (result != null) {
                                createSpeakFile(fileName).writeBytes(result.bytes)
                                persistParagraphAudio(
                                    book, targetIndex, chapter.title, pIndex,
                                    result.bytes, result.extension, httpTts.name
                                )
                            } else {
                                createSilentSound(fileName)
                            }
                        }
                    } else {
                        val tmp = getSpeakFileAsMd5(fileName)
                        if (tmp.exists() &&
                            TtsAudioCache.findParagraphFile(book, targetIndex, chapter.title, pIndex) == null
                        ) {
                            persistParagraphAudio(
                                book, targetIndex, chapter.title, pIndex,
                                tmp.readBytes(), tmp.extension.ifBlank { "mp3" }, httpTts.name
                            )
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
                val book = ReadBook.book
                val chapterIndex = textChapter?.chapter?.index ?: ReadBook.durChapterIndex
                val chapterTitle = textChapter?.chapter?.title ?: ""
                val cacheRunningAtStart = TtsAudioCacheModel.isRun
                if (!skipUnreadableParagraphsOrNextChapter()) {
                    return@withLock
                }
                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    if (isUnreadableParagraph(content)) return@forEachIndexed
                    var text = content
                    val midParagraph = paragraphStartPos > 0 && index == nowSpeak
                    if (midParagraph) {
                        text = text.substring(paragraphStartPos)
                    }
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    // 批量缓存进行中：只读本地文件，等待落盘，不走在线合成
                    if (TtsAudioCacheModel.isRun && book != null) {
                        val persist = awaitCachedParagraphFile(
                            book, chapterIndex, chapterTitle, index
                        )
                        if (persist != null) {
                            launch(Main) { addSpeakFileToPlayer(persist) }
                            return@forEachIndexed
                        }
                        if (TtsAudioCacheModel.isRun) {
                            val fileName = md5SpeakFileName(text)
                            createSilentSound(fileName)
                            launch(Main) { addSpeakFileToPlayer(getSpeakFileAsMd5(fileName)) }
                            return@forEachIndexed
                        }
                    }
                    val canUsePersist = !midParagraph && book != null
                    val persistFile = if (canUsePersist) {
                        TtsAudioCache.findParagraphFile(book!!, chapterIndex, chapterTitle, index)
                    } else null
                    if (persistFile != null) {
                        launch(Main) {
                            addSpeakFileToPlayer(persistFile)
                        }
                    } else {
                        val fileName = md5SpeakFileName(text)
                        val dataSourceFactory = createDataSourceFactory(
                            httpTts, speakText, book, chapterIndex, chapterTitle, index, canUsePersist
                        )
                        val downloader = createDownloader(dataSourceFactory, fileName)
                        downloaderChannel.send(downloader)
                        val mediaSource = createMediaSource(dataSourceFactory, fileName)
                        launch(Main) {
                            // 流式路径保持单 MediaSource，避免拼接静音源导致段落索引错位
                            exoPlayer.addMediaSource(mediaSource)
                        }
                    }
                }
                if (!cacheRunningAtStart && !TtsAudioCacheModel.isRun) {
                    preDownloadAudiosStream(httpTts, downloaderChannel)
                }
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS,
        downloaderChannel: Channel<Downloader>
    ) {
        if (TtsAudioCacheModel.isRun) return
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
        speakText: String,
        book: Book? = null,
        chapterIndex: Int = -1,
        chapterTitle: String = "",
        paragraphIndex: Int = -1,
        persist: Boolean = false,
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            getSpeakStream(
                                httpTts, speakText, book, chapterIndex,
                                chapterTitle, paragraphIndex, persist
                            )
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

    /** 将本地段音频加入播放列表（直接播原文件，不做拼接，避免损坏 MP3 导致无声）。 */
    private fun addSpeakFileToPlayer(file: File) {
        exoPlayer.addMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String,
        book: Book? = null,
        chapterIndex: Int = -1,
        chapterTitle: String = "",
        paragraphIndex: Int = -1,
        persist: Boolean = false,
    ): InputStream? {
        val result = HttpTtsSynthesizer.synthesize(httpTts, speakText, speechRate) ?: return null
        downloadErrorNo = 0
        if (persist && book != null && chapterIndex >= 0 && paragraphIndex >= 0) {
            persistParagraphAudio(
                book, chapterIndex, chapterTitle, paragraphIndex,
                result.bytes, result.extension, httpTts.name
            )
        }
        return result.bytes.inputStream()
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
            val isLeadPad = it.name.startsWith("lead_")

            // 判断逻辑：
            // 1. 如果是无声文件 / 旧版段首垫音临时文件 -> 删
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

            if (shouldDelete || isSilentSound || isLeadPad) {
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
                // 列表播完：仅当本章后面还有可读段落时才推进并重拉（防止短音频抢跑卡死）；
                // 否则走 updateNextPos→下一章，避免与 nextChapter 重复推进标红。
                playErrorNo = 0
                val hasMoreReadable = contentList.withIndex().any { (index, text) ->
                    index > nowSpeak && !isUnreadableParagraph(text)
                }
                val chapter = textChapter
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                if (hasMoreReadable &&
                    !pause &&
                    chapter != null &&
                    chapter == textChapter &&
                    nowSpeak in contentList.indices
                ) {
                    if (ReadConfig.streamReadAloudAudio) {
                        downloadAndPlayAudiosStream()
                    } else {
                        downloadAndPlayAudios()
                    }
                }
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
        // 只在自动切到下一段时推进标红；SEEK/PLAYLIST_CHANGED 等不推进，避免错位
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
        playErrorNo = 0
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
