package io.legado.app.help.tts

import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.putPrefString
import splitties.init.appCtx
import java.io.File

/**
 * 将全局 TTS 引擎选择镜像到 Download/Yuedu，卸载重装后仍可恢复。
 * 应用内仍以 [AppConfig.ttsEngine] / SharedPreferences 为准。
 */
object TtsEngineStore {

    private const val FILE_NAME = "ttsEngine.json"

    data class Snapshot(
        val engine: String? = null,
        val ttsType: String? = null,
        val name: String? = null,
        val httpTts: HttpTTS? = null,
    )

    private fun storeFile(): File {
        return File(TtsAudioCache.yueduRoot(), FILE_NAME)
    }

    /** 选择引擎时写入外部镜像（含 HttpTTS 配置，便于重装后恢复 MiMo/豆包密钥）。 */
    fun persist(engine: String?) {
        try {
            val root = TtsAudioCache.yueduRoot()
            root.mkdirs()
            val file = storeFile()
            if (engine.isNullOrBlank()) {
                if (file.isFile) file.delete()
                return
            }
            var httpTts: HttpTTS? = null
            var ttsType: String? = null
            var name: String? = null
            if (StringUtils.isNumeric(engine)) {
                httpTts = appDb.httpTTSDao.get(engine.toLong())
                ttsType = httpTts?.ttsType
                name = httpTts?.name
            }
            val json = GSON.toJson(
                Snapshot(
                    engine = engine,
                    ttsType = ttsType,
                    name = name,
                    httpTts = httpTts,
                )
            )
            file.writeText(json)
        } catch (_: Exception) {
        }
    }

    fun load(): Snapshot? {
        return try {
            val file = storeFile()
            if (!file.isFile) return null
            GSON.fromJsonObject<Snapshot>(file.readText()).getOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 在默认 HttpTTS 导入完成后调用：
     * - 把外部镜像里的引擎配置/密钥写回数据库
     * - 若应用内偏好为空或失效，则恢复所选引擎
     */
    fun restoreIfNeeded() {
        try {
            val snap = load()
            // 外部有完整 HttpTTS 时写回（覆盖默认导入清空的密钥）
            snap?.httpTts?.let { appDb.httpTTSDao.insert(it) }

            val current = AppConfig.ttsEngine
            if (!current.isNullOrBlank() && isEngineValid(current)) {
                // 覆盖安装偏好仍在：若尚无外部镜像则补写一份
                if (snap == null) persist(current)
                return
            }
            val engine = resolveEngine(snap ?: return) ?: return
            // 直接写 SP，避免 setter 在密钥尚未对齐时覆盖镜像
            appCtx.putPrefString(
                io.legado.app.constant.PreferKey.ttsEngine,
                engine
            )
            ReadAloud.upReadAloudClass()
        } catch (_: Exception) {
        }
    }

    private fun isEngineValid(engine: String): Boolean {
        if (!StringUtils.isNumeric(engine)) return true
        return appDb.httpTTSDao.get(engine.toLong()) != null
    }

    private fun resolveEngine(snap: Snapshot): String? {
        snap.httpTts?.let { saved ->
            appDb.httpTTSDao.insert(saved)
            return saved.id.toString()
        }
        val preferred = snap.engine
        if (!preferred.isNullOrBlank() && isEngineValid(preferred)) {
            return preferred
        }
        val type = snap.ttsType?.takeIf { it.isNotBlank() }
        if (type != null) {
            appDb.httpTTSDao.all.firstOrNull { it.ttsType == type }?.let {
                return it.id.toString()
            }
        }
        val name = snap.name?.takeIf { it.isNotBlank() }
        if (name != null) {
            appDb.httpTTSDao.all.firstOrNull { it.name == name }?.let {
                return it.id.toString()
            }
        }
        return preferred?.takeIf { !StringUtils.isNumeric(it) }
    }
}
