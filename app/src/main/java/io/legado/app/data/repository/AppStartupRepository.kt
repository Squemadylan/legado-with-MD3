package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.domain.gateway.AppStartupGateway
import io.legado.app.help.DefaultData
import io.legado.app.help.tts.TtsEngineStore

class AppStartupRepository(
    private val appDatabase: AppDatabase
) : AppStartupGateway {

    override suspend fun deleteNotShelfBooks() {
        appDatabase.bookDao.deleteNotShelfBook()
    }

    override suspend fun ensureDefaultHttpTts() {
        if (appDatabase.httpTTSDao.count == 0) {
            appDatabase.httpTTSDao.insert(*DefaultData.httpTTS.toTypedArray())
        }
        // 默认引擎入库后再恢复外部镜像的 TTS 选择（如 MiMo）
        TtsEngineStore.restoreIfNeeded()
    }
}
