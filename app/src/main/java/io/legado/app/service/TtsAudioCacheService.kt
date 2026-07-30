package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.HttpTtsSynthesizer
import io.legado.app.help.tts.TtsAudioCache
import io.legado.app.model.ReadAloud
import io.legado.app.model.TtsAudioCacheModel
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import splitties.systemservices.notificationManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * 按章节批量缓存 HTTP/豆包/MiMo TTS 音频到 Download/Yuedu/{书名}TTS/
 * 段级并发合成（默认 4）。
 */
class TtsAudioCacheService : BaseService() {

    companion object {
        const val notificationId = NotificationId.TtsAudioCacheService
    }

    private var cacheJob: Job? = null
    private var notificationContent = "准备缓存音频…"
    private val progressMutex = Mutex()

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.tts_audio_cache))
            .setContentIntent(
                activityPendingIntent<MainActivity>("ttsAudioCache")
            )
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<TtsAudioCacheService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    private data class ParaTask(
        val chapterIndex: Int,
        val chapterTitle: String,
        val paragraphIndex: Int,
        val speakText: String,
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> {
                val bookUrl = intent.getStringExtra("bookUrl")
                val indices = intent.getIntArrayExtra("indices")
                    ?: run {
                        val start = intent.getIntExtra("start", -1)
                        val end = intent.getIntExtra("end", -1)
                        if (start >= 0 && end >= start) {
                            (start..end).toList().toIntArray()
                        } else null
                    }
                if (bookUrl.isNullOrBlank() || indices == null || indices.isEmpty()) {
                    stopSelf()
                } else {
                    startCache(bookUrl, indices)
                }
            }

            IntentAction.stop -> {
                cacheJob?.cancel()
                TtsAudioCacheModel.updateProgress(
                    TtsAudioCacheModel.progressFlow.value.copy(
                        running = false,
                        cancelled = true,
                        summary = getString(R.string.tts_audio_cache_cancelled),
                    )
                )
                toastOnUi(R.string.tts_audio_cache_cancelled)
                stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationContent)
        startForeground(notificationId, notificationBuilder.build())
    }

    private fun upNotification(done: Int, total: Int) {
        notificationBuilder.setContentText(notificationContent)
        if (total > 0) {
            notificationBuilder.setProgress(total, done.coerceAtMost(total), false)
        } else {
            notificationBuilder.setProgress(0, 0, true)
        }
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    private fun startCache(bookUrl: String, indices: IntArray) {
        cacheJob?.cancel()
        cacheJob = lifecycleScope.launch(Dispatchers.IO) {
            TtsAudioCacheModel.markRunning(true)
            try {
                val book = appDb.bookDao.getBook(bookUrl)
                if (book == null) {
                    finishWithError("书籍不存在")
                    return@launch
                }
                val httpTts = ReadAloud.httpTTS
                    ?: appDb.httpTTSDao.get(ReadAloud.ttsEngine?.toLongOrNull() ?: -1L)
                if (httpTts == null) {
                    finishWithError(getString(R.string.tts_audio_cache_need_http))
                    return@launch
                }

                val speechRate = AppConfig.speechRatePlay + 5
                val chapterIndices = indices.distinct().sorted()
                notificationContent = "${book.name} · 准备 ${chapterIndices.size} 章…"
                withContext(Dispatchers.Main) { upNotification(0, 0) }

                // 先收集全部段任务
                val tasks = mutableListOf<ParaTask>()
                for (chapterIndex in chapterIndices) {
                    ensureActive()
                    val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                        ?: continue
                    val paragraphs = loadParagraphs(book, chapter) ?: continue
                    paragraphs.forEachIndexed { pIndex, content ->
                        val speakText = content.replace(AppPattern.notReadAloudRegex, "")
                        if (speakText.isNotEmpty()) {
                            tasks += ParaTask(chapterIndex, chapter.title, pIndex, speakText)
                        }
                    }
                }

                if (tasks.isEmpty()) {
                    finishWithError("没有可缓存的段落（请先缓存章节正文）")
                    return@launch
                }

                val total = tasks.size
                val doneCount = AtomicInteger(0)
                val concurrency = TtsAudioCacheModel.CONCURRENCY.coerceIn(1, 8)
                val semaphore = Semaphore(concurrency)

                TtsAudioCacheModel.updateProgress(
                    TtsAudioCacheModel.Progress(
                        running = true,
                        bookName = book.name,
                        doneCount = 0,
                        totalCount = total,
                        chapterTotal = chapterIndices.size,
                        summary = "${book.name} · 0/$total · ${concurrency}路并发",
                    )
                )
                notificationContent = "${book.name} · 0/$total"
                withContext(Dispatchers.Main) { upNotification(0, total) }

                coroutineScope {
                    tasks.map { task ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                ensureActive()
                                try {
                                    // 已有本地文件则跳过合成，便于缓存期间直接朗读已缓存段
                                    val existing = TtsAudioCache.findParagraphFile(
                                        book,
                                        task.chapterIndex,
                                        task.chapterTitle,
                                        task.paragraphIndex,
                                    )
                                    if (existing == null) {
                                        val result = HttpTtsSynthesizer.synthesize(
                                            httpTts, task.speakText, speechRate
                                        )
                                        if (result != null) {
                                            val ok = TtsAudioCache.writeParagraph(
                                                book = book,
                                                chapterIndex = task.chapterIndex,
                                                title = task.chapterTitle,
                                                paragraphIndex = task.paragraphIndex,
                                                bytes = result.bytes,
                                                extension = result.extension,
                                                engineName = httpTts.name,
                                            )
                                            if (!ok) {
                                                AppLog.put(
                                                    "TTS音频缓存写入失败[${task.chapterTitle}#${task.paragraphIndex}]"
                                                )
                                            }
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    AppLog.put(
                                        "TTS音频缓存合成失败[${task.chapterTitle}#${task.paragraphIndex}]: ${e.localizedMessage}",
                                        e
                                    )
                                }
                                val done = doneCount.incrementAndGet()
                                if (done % 2 == 0 || done == total) {
                                    progressMutex.withLock {
                                        val summary =
                                            "${book.name} · $done/$total · ${task.chapterTitle}"
                                        notificationContent = summary
                                        TtsAudioCacheModel.updateProgress(
                                            TtsAudioCacheModel.Progress(
                                                running = true,
                                                bookName = book.name,
                                                doneCount = done,
                                                totalCount = total,
                                                chapterTotal = chapterIndices.size,
                                                summary = summary,
                                            )
                                        )
                                    }
                                    withContext(Dispatchers.Main) {
                                        upNotification(done, total)
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }

                if (!isActive) return@launch
                TtsAudioCacheModel.updateProgress(
                    TtsAudioCacheModel.Progress(
                        running = false,
                        bookName = book.name,
                        doneCount = total,
                        totalCount = total,
                        chapterTotal = chapterIndices.size,
                        done = true,
                        summary = getString(R.string.tts_audio_cache_done),
                    )
                )
                withContext(Dispatchers.Main) {
                    toastOnUi(R.string.tts_audio_cache_done)
                }
            } catch (e: CancellationException) {
                // cancelled
            } catch (e: Exception) {
                AppLog.put("TTS音频缓存异常: ${e.localizedMessage}", e)
                finishWithError(e.localizedMessage ?: "缓存失败")
            } finally {
                TtsAudioCacheModel.markRunning(false)
                stopSelf()
            }
        }
    }

    private suspend fun loadParagraphs(book: Book, chapter: BookChapter): List<String>? {
        val rawContent = loadChapterContent(book, chapter)
        if (rawContent.isNullOrBlank()) {
            AppLog.put("TTS音频缓存跳过空章节: ${chapter.title}")
            return null
        }
        val paragraphs = TtsAudioCache.paragraphsForCache(book, chapter, rawContent)
        if (paragraphs.isEmpty()) {
            AppLog.put("TTS音频缓存跳过空章节: ${chapter.title}")
            return null
        }
        return paragraphs
    }

    private suspend fun loadChapterContent(book: Book, chapter: BookChapter): String? {
        BookHelp.getContent(book, chapter)?.let { return it }
        if (book.isLocal) return null
        val source = book.getBookSource() ?: return null
        return try {
            WebBook.getContentAwait(
                bookSource = source,
                book = book,
                bookChapter = chapter,
                nextChapterUrl = null,
                needSave = true,
            )
        } catch (e: Exception) {
            AppLog.put("TTS音频缓存获取正文失败: ${chapter.title}\n${e.localizedMessage}", e)
            null
        }
    }

    private suspend fun finishWithError(message: String) {
        TtsAudioCacheModel.updateProgress(
            TtsAudioCacheModel.Progress(
                running = false,
                error = message,
                summary = message,
            )
        )
        withContext(Dispatchers.Main) {
            toastOnUi(message)
        }
    }

    override fun onDestroy() {
        cacheJob?.cancel()
        TtsAudioCacheModel.markRunning(false)
        super.onDestroy()
    }
}
