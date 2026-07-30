package io.legado.app.help.tts

import android.os.Environment
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.ContentProcessor
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File

/**
 * 持久 TTS 音频缓存：Download/Yuedu/{书名}TTS/{章目录}/{段序号}.{ext}
 * 与临时目录 externalCacheDir/httpTTS 分离。
 */
object TtsAudioCache {

    data class Meta(
        val bookUrl: String = "",
        val bookName: String = "",
        val engineName: String = "",
        val updatedAt: Long = 0L,
    )

    fun downloadsRoot(): File {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    /** Download/Yuedu */
    fun yueduRoot(): File {
        return File(downloadsRoot(), "Yuedu")
    }

    fun bookFolderName(bookName: String): String {
        val safe = bookName.replace(AppPattern.fileNameRegex2, "_").trim()
            .ifBlank { "book" }
        return "${safe}TTS"
    }

    fun bookRoot(book: Book): File {
        return File(yueduRoot(), bookFolderName(book.name))
    }

    fun bookRoot(bookName: String): File {
        return File(yueduRoot(), bookFolderName(bookName))
    }

    fun chapterDirName(chapterIndex: Int, title: String): String {
        val safeTitle = title.replace(AppPattern.fileNameRegex2, "_")
            .trim()
            .take(40)
            .ifBlank { "chapter" }
        return "%05d_%s".format(chapterIndex + 1, safeTitle)
    }

    fun chapterDir(book: Book, chapterIndex: Int, title: String): File {
        return File(bookRoot(book), chapterDirName(chapterIndex, title))
    }

    fun paragraphBaseName(paragraphIndex: Int): String {
        return "%04d".format(paragraphIndex)
    }

    /**
     * 与朗读 [contentList] 对齐的段落列表：必须包含章节标题作为第 0 段。
     * 朗读侧来自 TextChapter.getNeedReadAloud（含标题行）；若缓存省略标题会导致整章索引偏一。
     */
    fun paragraphsForCache(book: Book, chapter: BookChapter, rawContent: String): List<String> {
        if (rawContent.isBlank()) return emptyList()
        val processed = try {
            ContentProcessor.get(book).getContent(
                book = book,
                chapter = chapter,
                content = rawContent,
                includeTitle = true,
            ).textList.joinToString("\n")
        } catch (_: Exception) {
            rawContent
        }
        return processed.split("\n").filter { it.isNotEmpty() }
    }

    /** 查找已存在的段文件（mp3 / wav） */
    fun findParagraphFile(
        book: Book,
        chapterIndex: Int,
        title: String,
        paragraphIndex: Int,
    ): File? {
        val dir = chapterDir(book, chapterIndex, title)
        val base = paragraphBaseName(paragraphIndex)
        val mp3 = File(dir, "$base.mp3")
        if (mp3.isFile && mp3.length() > 0L) return mp3
        val wav = File(dir, "$base.wav")
        if (wav.isFile && wav.length() > 0L) return wav
        return null
    }

    fun paragraphFile(
        book: Book,
        chapterIndex: Int,
        title: String,
        paragraphIndex: Int,
        extension: String,
    ): File {
        val dir = chapterDir(book, chapterIndex, title)
        return File(dir, "${paragraphBaseName(paragraphIndex)}.${extension.removePrefix(".")}")
    }

    /** 按用户要求不做空间预检，始终允许写入。 */
    fun hasEnoughSpace(neededBytes: Long = 0L): Boolean = true

    /**
     * 写入段音频（覆盖同位置）。写入失败返回 false（不做空间预检）。
     */
    fun writeParagraph(
        book: Book,
        chapterIndex: Int,
        title: String,
        paragraphIndex: Int,
        bytes: ByteArray,
        extension: String,
        engineName: String = "",
    ): Boolean {
        if (bytes.isEmpty()) return false
        return try {
            val file = paragraphFile(book, chapterIndex, title, paragraphIndex, extension)
            file.parentFile?.mkdirs()
            // 覆盖：先删掉另一扩展名的旧文件
            findParagraphFile(book, chapterIndex, title, paragraphIndex)?.let { old ->
                if (old.absolutePath != file.absolutePath) {
                    old.delete()
                }
            }
            file.writeBytes(bytes)
            updateMeta(book, engineName)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun updateMeta(book: Book, engineName: String = "") {
        try {
            val root = bookRoot(book)
            root.mkdirs()
            val metaFile = File(root, "meta.json")
            val meta = Meta(
                bookUrl = book.bookUrl,
                bookName = book.name,
                engineName = engineName,
                updatedAt = System.currentTimeMillis(),
            )
            metaFile.writeText(GSON.toJson(meta))
        } catch (_: Exception) {
        }
    }

    fun readMeta(book: Book): Meta? {
        return try {
            val metaFile = File(bookRoot(book), "meta.json")
            if (!metaFile.isFile) return null
            GSON.fromJsonObject<Meta>(metaFile.readText()).getOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
