package io.legado.app.help.tts

import android.util.Base64
import com.google.gson.GsonBuilder
import com.script.ScriptException
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.mozilla.javascript.WrappedException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * HTTP / 豆包 / MiMo TTS 合成，供朗读服务与章节音频缓存服务共用。
 */
object HttpTtsSynthesizer {

    private const val DOUBAO_TTS_URL = "https://openspeech.bytedance.com/api/v1/tts"
    private const val MIMO_TTS_URL = "https://api.xiaomimimo.com/v1/chat/completions"

    data class Result(
        val bytes: ByteArray,
        val extension: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false
            return extension == other.extension && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + extension.hashCode()
    }

    /**
     * @param speechRate 仅通用 HTTP TTS 使用（与朗读服务 speechRate 一致）
     */
    suspend fun synthesize(
        httpTts: HttpTTS,
        speakText: String,
        speechRate: Int,
    ): Result? {
        if (speakText.isBlank()) return null
        return when (httpTts.ttsType) {
            "doubao" -> synthesizeDoubao(httpTts, speakText)
            "mimo" -> synthesizeMimo(httpTts, speakText)
            else -> synthesizeHttp(httpTts, speakText, speechRate)
        }
    }

    private suspend fun synthesizeHttp(
        httpTts: HttpTTS,
        speakText: String,
        speechRate: Int,
    ): Result? {
        var errorNo = 0
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
                response.headers["Content-Type"]?.let { rawType ->
                    val contentType = rawType.substringBefore(";")
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
                val bytes = response.body.bytes()
                if (bytes.isEmpty()) return null
                val ext = when {
                    httpTts.contentType?.contains("wav", true) == true -> "wav"
                    httpTts.contentType?.contains("mpeg", true) == true -> "mp3"
                    httpTts.contentType?.contains("mp3", true) == true -> "mp3"
                    else -> "mp3"
                }
                return Result(bytes, ext)
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        errorNo++
                        if (errorNo > 5) {
                            AppLog.put("tts超时或连接错误超过5次\n${e.localizedMessage}", e, true)
                            throw e
                        }
                    }

                    else -> {
                        errorNo++
                        AppLog.put("tts下载错误\n${e.localizedMessage}", e)
                        e.printOnDebug()
                        if (errorNo > 5) {
                            AppLog.put("TTS服务器连续5次错误", e, true)
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错\n朗读文本：$speakText")
                            return null
                        }
                    }
                }
            }
        }
    }

    private fun synthesizeDoubao(httpTts: HttpTTS, speakText: String): Result? {
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val appId = httpTts.doubaoAppId
        val accessToken = httpTts.doubaoAccessToken
        if (appId.isNullOrBlank() || accessToken.isNullOrBlank()) {
            AppLog.put("豆包 TTS 配置不完整：缺少 AppID 或 AccessToken")
            return null
        }
        val truncatedText = speakText.take(480)
        if (truncatedText.isBlank()) return null
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
            Result(Base64.decode(b64Data, Base64.NO_WRAP), "mp3")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("豆包 TTS 请求异常: ${e.localizedMessage}", e)
            null
        }
    }

    private fun synthesizeMimo(httpTts: HttpTTS, speakText: String): Result? {
        if (httpTts.mimoApiKey.isNullOrBlank()) {
            AppLog.put("MiMo TTS 配置不完整：缺少 API Key")
            return null
        }
        val truncatedText = speakText.take(2000)
        if (truncatedText.isBlank()) return null
        val messages = mutableListOf<Map<String, String>>()
        val style = httpTts.mimoStyle
        if (!style.isNullOrBlank()) {
            messages.add(mapOf("role" to "user", "content" to style))
        } else {
            messages.add(mapOf("role" to "user", "content" to ""))
        }
        messages.add(mapOf("role" to "assistant", "content" to truncatedText))
        val voice = if (httpTts.mimoModel == "mimo-v2.5-tts-voicedesign"
            && !httpTts.mimoVoiceDesign.isNullOrBlank()
        ) {
            httpTts.mimoVoiceDesign
        } else {
            httpTts.mimoVoice
        }
        // 非流式优先要 mp3（体积约为 pcm16/wav 的 1/8~1/15）；失败再回退 pcm16→wav
        return synthesizeMimoWithFormat(httpTts, messages, voice, truncatedText, "mp3")
            ?: synthesizeMimoWithFormat(httpTts, messages, voice, truncatedText, "pcm16")
    }

    private fun synthesizeMimoWithFormat(
        httpTts: HttpTTS,
        messages: List<Map<String, String>>,
        voice: String?,
        truncatedText: String,
        format: String,
    ): Result? {
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val apiKey = httpTts.mimoApiKey ?: return null
        val requestBody = mapOf(
            "model" to httpTts.mimoModel,
            "messages" to messages,
            "audio" to mapOf(
                "format" to format,
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
            AppLog.put(
                "MiMo TTS 请求: model=${httpTts.mimoModel}, voice=$voice, format=$format, text=${truncatedText.take(50)}..."
            )
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = try {
                    response.body.string().take(500)
                } catch (_: Exception) {
                    ""
                }
                AppLog.put("MiMo TTS HTTP 错误($format): ${response.code}\n$errBody")
                return null
            }
            val bodyStr = response.body.string()
            AppLog.put("MiMo TTS 响应($format, ${bodyStr.length} chars): ${bodyStr.take(300)}")
            val responseJson = gson.fromJson(bodyStr, Map::class.java)
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
            when (format) {
                "mp3" -> {
                    AppLog.put("MiMo TTS 音频数据: ${audioBytes.size} bytes (mp3)")
                    Result(audioBytes, "mp3")
                }
                else -> {
                    val wavBytes = addWavHeader(audioBytes, 24000, 1, 16)
                    AppLog.put("MiMo TTS 音频数据: ${audioBytes.size} PCM → ${wavBytes.size} WAV")
                    Result(wavBytes, "wav")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("MiMo TTS 请求异常($format): ${e.localizedMessage}", e)
            null
        }
    }

    fun addWavHeader(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        val fileSize = 36 + dataSize
        header[4] = (fileSize and 0xFF).toByte()
        header[5] = (fileSize shr 8 and 0xFF).toByte()
        header[6] = (fileSize shr 16 and 0xFF).toByte()
        header[7] = (fileSize shr 24 and 0xFF).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
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
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (dataSize and 0xFF).toByte()
        header[41] = (dataSize shr 8 and 0xFF).toByte()
        header[42] = (dataSize shr 16 and 0xFF).toByte()
        header[43] = (dataSize shr 24 and 0xFF).toByte()
        return header + pcmData
    }
}
