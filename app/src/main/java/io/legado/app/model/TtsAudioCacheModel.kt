package io.legado.app.model

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.legado.app.constant.IntentAction
import io.legado.app.service.TtsAudioCacheService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TTS 章节音频缓存任务状态（UI / 通知共用）
 */
object TtsAudioCacheModel {

    data class Progress(
        val running: Boolean = false,
        val bookName: String = "",
        val doneCount: Int = 0,
        val totalCount: Int = 0,
        val chapterIndex: Int = 0,
        val chapterTotal: Int = 0,
        val paragraphIndex: Int = 0,
        val paragraphTotal: Int = 0,
        val summary: String = "",
        val done: Boolean = false,
        val cancelled: Boolean = false,
        val error: String? = null,
    )

    private val _progress = MutableStateFlow(Progress())
    val progressFlow: StateFlow<Progress> = _progress.asStateFlow()

    /** 并发合成线程数 */
    const val CONCURRENCY = 4

    @Volatile
    var isRun = false
        private set

    fun updateProgress(progress: Progress) {
        _progress.value = progress
    }

    fun markRunning(running: Boolean) {
        isRun = running
        if (!running) {
            val cur = _progress.value
            if (cur.running) {
                _progress.value = cur.copy(running = false)
            }
        }
    }

    fun start(
        context: Context,
        bookUrl: String,
        indices: IntArray,
    ) {
        if (indices.isEmpty()) return
        val intent = Intent(context, TtsAudioCacheService::class.java).apply {
            action = IntentAction.start
            putExtra("bookUrl", bookUrl)
            putExtra("indices", indices.sorted().toIntArray())
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /** 兼容旧的起止范围调用 */
    fun start(
        context: Context,
        bookUrl: String,
        startIndex: Int,
        endIndex: Int,
    ) {
        if (startIndex < 0 || endIndex < startIndex) return
        start(context, bookUrl, (startIndex..endIndex).toList().toIntArray())
    }

    fun stop(context: Context) {
        val intent = Intent(context, TtsAudioCacheService::class.java).apply {
            action = IntentAction.stop
        }
        context.startService(intent)
    }
}
