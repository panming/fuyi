package com.fanyi.overlay.translate

import com.fanyi.overlay.asr.asObject
import com.fanyi.overlay.asr.child
import com.fanyi.overlay.asr.stringOrNull
import com.fanyi.overlay.model.AppSettings
import com.fanyi.overlay.model.TranslationResult
import com.fanyi.overlay.model.Provider
import com.fanyi.overlay.model.SourceLanguages
import com.fanyi.overlay.model.TENCENT_MT_PUBLIC
import com.fanyi.overlay.model.aliyunMtCode
import com.fanyi.overlay.model.mtBlockMessage
import com.fanyi.overlay.model.tokenHubProblem
import com.fanyi.overlay.model.tokenHubSourceCode
import com.fanyi.overlay.net.Net
import com.fanyi.overlay.net.executeCancellable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal fun openaiChatUrl(base: String): String {
    val trimmed = base.trim().trimEnd('/')
    require(trimmed.startsWith("https://")) { "地址必须以 https:// 开头" }
    return when {
        trimmed.endsWith("/chat/completions") -> trimmed
        trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
        else -> "$trimmed/v1/chat/completions"
    }
}

/**
 * Text-only adapter for servers that speak the OpenAI chat completions protocol.
 * Audio is never sent here. A custom host receives text only after the user selects this translator.
 */
internal class OpenAiTranslator(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
    private val sourceLanguage: String,
) : TextTranslator {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun translate(text: String, sourceLanguage: String?): TranslationResult {
        val locked = this.sourceLanguage.takeIf { it.isNotBlank() && it != SourceLanguages.AUTO }
        val system = buildString {
            append("Translate the user message into Simplified Chinese. Reply with the translation only.")
            if (locked != null) append(" The speaker chose source language $locked. Do not switch to another language.")
        }
        val payload = buildJsonObject {
            put("model", model.trim())
            put("temperature", 0)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", text.take(4_000))
                })
            }
        }.toString().toByteArray(Charsets.UTF_8)
        val request = Request.Builder()
            .url(openaiChatUrl(baseUrl))
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return Net.http.executeCancellable(request).use { response ->
            val raw = response.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(raw).asObject() }.getOrNull()
            if (!response.isSuccessful) {
                val message = root?.child("error").asObject()?.child("message").stringOrNull()
                    ?: raw.take(180)
                error("兼容翻译 HTTP ${response.code} $message")
            }
            val content = root?.child("choices")
                ?.let { element -> (element as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.asObject() }
                ?.child("message").asObject()
                ?.child("content").stringOrNull()?.trim().orEmpty()
            if (content.isEmpty()) error("兼容翻译没有返回文本")
            TranslationResult(content, sourceLanguage ?: locked)
        }
    }
}

internal const val TOKENHUB_TRANSLATE = "https://tokenhub.tencentmaas.com/v1/api/translations"

internal fun tokenHubBody(text: String, sourceCode: String?, model: String): String {
    return buildJsonObject {
        put("model", model.ifBlank { "hy-mt2-plus" })
        put("text", text.take(4_000))
        put("target", "zh")
        if (!sourceCode.isNullOrBlank()) put("source", sourceCode)
    }.toString()
}

internal fun chatCompletionText(raw: String): String? {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val root = runCatching { json.parseToJsonElement(raw).asObject() }.getOrNull() ?: return null
    return root.child("choices")
        ?.let { element -> (element as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.asObject() }
        ?.child("message").asObject()
        ?.child("content").stringOrNull()?.trim()
}

/**
 * Current Tencent translation product: TokenHub Hy-MT2.
 * https://cloud.tencent.com/document/product/1823/132252
 * Text is sent only to the official translations host after the user saves a TokenHub key.
 */
internal class TencentTokenHubTranslator(
    private val apiKey: String,
    private val model: String,
    private val sourceLanguage: String,
) : TextTranslator {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun translate(text: String, sourceLanguage: String?): TranslationResult {
        val source = tokenHubSourceCode(this.sourceLanguage)
        if (this.sourceLanguage != SourceLanguages.AUTO && source == null) {
            error(tokenHubProblem(this.sourceLanguage) ?: "混元翻译不支持当前来源语言")
        }
        val payload = tokenHubBody(text, source, model.trim()).toByteArray(Charsets.UTF_8)
        val request = Request.Builder()
            .url(TOKENHUB_TRANSLATE)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return Net.http.executeCancellable(request).use { response ->
            val raw = response.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(raw).asObject() }.getOrNull()
            if (!response.isSuccessful) {
                val message = root?.child("error").asObject()?.child("message").stringOrNull() ?: raw.take(180)
                error("混元翻译 HTTP ${response.code} $message")
            }
            val content = chatCompletionText(raw).orEmpty()
            if (content.isEmpty()) error("混元翻译没有返回译文")
            TranslationResult(content, root?.child("source").stringOrNull() ?: source)
        }
    }
}

internal class TencentTranslator(
    private val secretId: String,
    private val secretKey: String,
    private val sourceLanguage: String,
) : TextTranslator {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun translate(text: String, sourceLanguage: String?): TranslationResult {
        val source = TENCENT_MT_PUBLIC[this.sourceLanguage]
            ?: error("腾讯云翻译不能使用当前来源语言")
        val payload = buildJsonObject {
            put("SourceText", text.take(5_000))
            put("Source", source)
            put("Target", "zh")
            put("ProjectId", 0)
        }.toString().toByteArray(Charsets.UTF_8)
        val timestamp = System.currentTimeMillis() / 1000
        val authorization = TencentTc3.authorization(
            secretId = secretId.trim(),
            secretKey = secretKey.trim(),
            host = "tmt.tencentcloudapi.com",
            service = "tmt",
            action = "TextTranslate",
            timestampSeconds = timestamp,
            payload = payload,
        )
        val request = Request.Builder()
            .url("https://tmt.tencentcloudapi.com/")
            .header("Content-Type", "application/json")
            .header("Host", "tmt.tencentcloudapi.com")
            .header("X-TC-Action", "TextTranslate")
            .header("X-TC-Version", "2018-03-21")
            .header("X-TC-Timestamp", timestamp.toString())
            .header("X-TC-Region", "ap-beijing")
            .header("Authorization", authorization)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return Net.http.executeCancellable(request).use { response ->
            val raw = response.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(raw).asObject() }.getOrNull()
            val body = root?.child("Response").asObject()
            val errorNode = body?.child("Error").asObject()
            if (errorNode != null) {
                val code = errorNode.child("Code").stringOrNull().orEmpty()
                val message = errorNode.child("Message").stringOrNull() ?: "腾讯云翻译失败"
                val requestId = body?.child("RequestId").stringOrNull()?.let { " RequestId=$it" }.orEmpty()
                error("$message（$code）$requestId")
            }
            if (!response.isSuccessful) error("腾讯云翻译 HTTP ${response.code} ${raw.take(160)}")
            val translated = body?.child("TargetText").stringOrNull()?.trim().orEmpty()
            if (translated.isEmpty()) error("腾讯云翻译没有返回译文")
            TranslationResult(translated, body?.child("Source").stringOrNull() ?: sourceLanguage ?: source)
        }
    }
}

internal fun tencentMtSourceCode(language: String): String? = TENCENT_MT_PUBLIC[language]

internal fun textTranslator(settings: AppSettings): TextTranslator? {
    if (mtBlockMessage(settings) != null) return null
    return when (settings.mtProvider) {
        Provider.VOLCENGINE -> volcengineTranslator(settings)
        Provider.ALIYUN -> AliyunTranslator(
            settings.aliAk,
            settings.aliSk,
            aliyunMtCode(settings.sourceLanguage) ?: "auto",
        )
        Provider.TENCENT -> if (settings.tencentMtApiKey.isNotBlank()) {
            TencentTokenHubTranslator(
                settings.tencentMtApiKey,
                settings.tencentMtModel,
                settings.sourceLanguage,
            )
        } else {
            TencentTranslator(
                settings.tencentSecretId,
                settings.tencentSecretKey,
                settings.sourceLanguage,
            )
        }
        Provider.OPENAI -> OpenAiTranslator(
            settings.openaiBaseUrl,
            settings.openaiModel,
            settings.openaiApiKey,
            settings.sourceLanguage,
        )
    }
}
