package com.fanyi.overlay.translate

import com.fanyi.overlay.asr.asArray
import com.fanyi.overlay.asr.asObject
import com.fanyi.overlay.asr.child
import com.fanyi.overlay.asr.intOrNull
import com.fanyi.overlay.asr.stringOrNull
import com.fanyi.overlay.model.AppSettings
import com.fanyi.overlay.model.Endpoints
import com.fanyi.overlay.model.TranslationResult
import com.fanyi.overlay.model.volcTranslationApiKey
import com.fanyi.overlay.net.Net
import com.fanyi.overlay.net.executeCancellable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

internal interface TextTranslator {
    suspend fun translate(text: String, sourceLanguage: String? = null): TranslationResult
}

internal fun volcengineTranslator(settings: AppSettings): VolcengineTranslator? {
    val apiKey = volcTranslationApiKey(settings)
    val legacy = settings.volcAppId.isNotBlank() && settings.volcAccessToken.isNotBlank()
    if (apiKey.isEmpty() && !legacy) return null
    return VolcengineTranslator(
        apiKey = apiKey,
        legacyAppId = settings.volcAppId,
        legacyAccessToken = settings.volcAccessToken,
    )
}

/**
 * Doubao speech machine translation.
 * POST https://openspeech.bytedance.com/api/v3/machine_translation/matx_translate
 * Resource id volc.speech.mt. Target zh is Simplified Chinese.
 * Omitting source_language asks the service to detect it.
 */
internal class VolcengineTranslator(
    private val apiKey: String,
    private val legacyAppId: String = "",
    private val legacyAccessToken: String = "",
) : TextTranslator {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun translate(text: String, sourceLanguage: String?): TranslationResult {
        val clipped = text.take(1_000)
        val payload = buildJsonObject {
            val source = sourceLanguage?.trim().orEmpty()
            if (source.isNotEmpty() && source != "zh") put("source_language", source)
            put("target_language", "zh")
            putJsonArray("text_list") { add(JsonPrimitive(clipped)) }
        }.toString().toByteArray(Charsets.UTF_8)
        val request = Request.Builder()
            .url(Endpoints.VOLC_MT)
            .header("Content-Type", "application/json")
            .header("X-Api-Resource-Id", Endpoints.VOLC_MT_RESOURCE)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .apply {
                val key = apiKey.trim()
                if (key.isNotEmpty()) {
                    header("X-Api-Key", key)
                } else {
                    header("X-Api-App-Key", legacyAppId.trim())
                    header("X-Api-Access-Key", legacyAccessToken.trim())
                }
            }
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return Net.http.executeCancellable(request).use { response ->
            val raw = response.body?.string().orEmpty()
            val logId = response.header("X-Tt-Logid")?.let { " logid=$it" }.orEmpty()
            if (!response.isSuccessful) {
                error("机器翻译 HTTP ${response.code} ${raw.take(180)}$logId")
            }
            val root = json.parseToJsonElement(raw).asObject() ?: error("机器翻译返回无法解析$logId")
            val code = root.child("code").intOrNull()
            if (code != null && code != 20000000) {
                val message = root.child("message").stringOrNull() ?: "机器翻译失败"
                error("$message（$code）$logId")
            }
            val first = root.child("data").asObject()
                ?.child("translation_list").asArray()
                ?.firstOrNull()?.asObject()
            val translated = first?.child("translation").stringOrNull()?.trim().orEmpty()
            if (translated.isEmpty()) error("机器翻译没有返回译文$logId")
            TranslationResult(
                translated = translated,
                detectedSource = first?.child("detected_source_language").stringOrNull() ?: sourceLanguage,
            )
        }
    }
}

internal class AliyunTranslator(
    private val accessKeyId: String,
    private val accessKeySecret: String,
    private val sourceLanguage: String = "auto",
) : TextTranslator {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun translate(text: String, sourceLanguage: String?): TranslationResult {
        val form = AliyunSigner.translateForm(
            accessKeyId.trim(),
            accessKeySecret.trim(),
            text.take(4_800),
            sourceLanguage = this.sourceLanguage,
        )
        val request = Request.Builder()
            .url("https://mt.cn-hangzhou.aliyuncs.com/")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(form.toByteArray(Charsets.UTF_8).toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        return Net.http.executeCancellable(request).use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("阿里云翻译 HTTP ${response.code} ${raw.take(220)}")
            }
            val root = json.parseToJsonElement(raw).asObject() ?: error("阿里云翻译返回无法解析")
            val code = root.child("Code").intOrNull() ?: root.child("Code").stringOrNull()?.toIntOrNull()
            if (code != null && code != 200) {
                error(root.child("Message").stringOrNull() ?: "阿里云翻译失败 $code")
            }
            val data = root.child("Data").asObject()
            val translated = data?.child("Translated").stringOrNull()?.trim().orEmpty()
            if (translated.isEmpty()) error("阿里云翻译没有返回译文")
            TranslationResult(
                translated = translated,
                detectedSource = data?.child("DetectedLanguage").stringOrNull(),
            )
        }
    }
}
