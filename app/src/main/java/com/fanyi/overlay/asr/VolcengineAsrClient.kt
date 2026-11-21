package com.fanyi.overlay.asr

import com.fanyi.overlay.model.AppSettings
import com.fanyi.overlay.model.Endpoints
import com.fanyi.overlay.model.SourceLanguages
import com.fanyi.overlay.net.Net
import com.fanyi.overlay.translate.friendlyFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Doubao streaming ASR 2.0.
 * Product name on the console is 豆包流式语音识别模型2.0.
 * Wire resource id for hourly billing is volc.seedasr.sauc.duration.
 * Request model_name stays "bigmodel". Realtime endpoint is bigmodel_async.
 * Sentence mode (more languages) is bigmodel_nostream with enable_auto_lang.
 *
 * Docs:
 * https://docs.volcengine.com/docs/doubao-voice/bidirectional-streaming-automatic-speech-recognition-websocket
 * https://docs.volcengine.com/docs/doubao-voice/large-model-streaming-automatic-speech-recognition-api-1
 */
internal class VolcengineAsrClient(
    private val settings: AppSettings,
    private val events: AsrEvents,
) : StreamingAsr {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val ready = CompletableDeferred<Unit>()
    private val mutex = Mutex()
    private val sequence = AtomicInteger(1)
    private val seenFinals = HashSet<String>()
    private var socket: WebSocket? = null
    private var finished = false
    private var logId: String? = null

    override suspend fun start() {
        val url = Endpoints.volcAsrUrl(settings)
        val request = Request.Builder()
            .url(url)
            .headers(authHeaders())
            .build()
        socket = Net.websocket.newWebSocket(request, listener(url))
        withTimeout(12_000) { ready.await() }
    }

    override suspend fun sendPcm(frame: ByteArray) {
        mutex.withLock {
            if (finished || frame.isEmpty()) return
            val seq = sequence.incrementAndGet()
            socket?.send(SaucProtocol.audio(frame, seq, last = false).toByteString())
        }
    }

    override suspend fun finish() {
        mutex.withLock {
            if (finished) return
            finished = true
            val seq = sequence.incrementAndGet()
            socket?.send(SaucProtocol.audio(ByteArray(0), seq, last = true).toByteString())
        }
        kotlinx.coroutines.delay(700)
        socket?.close(1000, "stop")
        socket = null
    }

    private fun authHeaders(): Headers {
        val builder = Headers.Builder()
        val apiKey = settings.volcApiKey.trim()
        if (apiKey.isNotEmpty()) {
            builder.add("X-Api-Key", apiKey)
        } else {
            val appId = settings.volcAppId.trim()
            builder.add("X-Api-App-Key", appId)
            builder.add("X-Api-App-Id", appId)
            builder.add("X-Api-Access-Key", settings.volcAccessToken.trim())
        }
        builder.add("X-Api-Resource-Id", Endpoints.volcResource(settings))
        builder.add("X-Api-Connect-Id", UUID.randomUUID().toString())
        builder.add("X-Api-Request-Id", UUID.randomUUID().toString())
        return builder.build()
    }

    private fun requestBody(): ByteArray {
        val language = settings.sourceLanguage.ifBlank { SourceLanguages.AUTO }
        val realtime = Endpoints.volcAsrUrl(settings).contains("bigmodel_async")
        val request = buildJsonObject {
            put("model_name", "bigmodel")
            put("enable_itn", true)
            put("enable_punc", true)
            put("enable_ddc", false)
            put("show_utterances", true)
            put("result_type", "single")
            put("end_window_size", 300)
            put("force_to_speech_time", 400)
            put("enable_nonstream", false)
            if (realtime) {
                put("enable_lid", true)
            } else if (language == SourceLanguages.AUTO) {
                put("enable_auto_lang", true)
            }
        }
        val root = buildJsonObject {
            put("user", buildJsonObject {
                put("uid", "fanyi-overlay")
                put("platform", "Android")
                put("app_version", "0.1.0")
            })
            put("audio", buildJsonObject {
                put("format", "pcm")
                put("codec", "raw")
                put("rate", 16000)
                put("bits", 16)
                put("channel", 1)
                if (!realtime && language != SourceLanguages.AUTO) put("language", language)
            })
            put("request", request)
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun listener(url: String): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            logId = response.header("X-Tt-Logid")
            val sent = webSocket.send(SaucProtocol.fullClient(requestBody(), sequence = 1).toByteString())
            if (!sent) {
                fail("识别连接已打开，但初始化请求没有发出去。")
                return
            }
            val mode = if (url.contains("nostream")) "多语种句末" else "实时"
            val suffix = logId?.let { " · $it" }.orEmpty()
            events.onReady("$mode 识别已连接$suffix")
            if (!ready.isCompleted) ready.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            handleFrame(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleJson(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val http = response?.code?.let { " HTTP $it" }.orEmpty()
            val body = response?.body?.string()?.take(180).orEmpty()
            val log = logId?.let { " logid=$it" }.orEmpty()
            fail(friendlyFailure(t) + http + log + if (body.isBlank()) "" else " $body")
        }
    }

    private fun handleFrame(bytes: ByteArray) {
        val frame = try {
            SaucProtocol.parse(bytes)
        } catch (t: Throwable) {
            fail("无法解析识别结果：${t.message}")
            return
        }
        if (frame.messageType == SaucProtocol.ERROR) {
            val message = frame.payload.toString(Charsets.UTF_8).ifBlank { "识别服务返回错误" }
            fail("$message（${frame.errorCode ?: "?"}）" + logSuffix())
            return
        }
        if (frame.payload.isEmpty()) return
        handleJson(frame.payload.toString(Charsets.UTF_8))
    }

    private fun handleJson(text: String) {
        val root = try {
            json.parseToJsonElement(text).asObject()
        } catch (_: Throwable) {
            null
        } ?: return
        val code = root.child("code").intOrNull()
        if (code != null && code != 0 && code != 20000000) {
            val message = root.child("message").stringOrNull() ?: "识别错误"
            fail("$message（$code）" + logSuffix())
            return
        }
        val result = root.child("result").asObject() ?: return
        val utterances = result.child("utterances").asArray()
        if (utterances != null && utterances.isNotEmpty()) {
            var partialText: String? = null
            var partialLang: String? = null
            for (item in utterances) {
                val utterance = item.asObject() ?: continue
                val spoken = utterance.child("text").stringOrNull()?.trim().orEmpty()
                if (spoken.isEmpty()) continue
                val definite = utterance.child("definite").boolOrNull() == true
                val language = languageOf(utterance)
                if (definite) {
                    val start = utterance.child("start_time").longOrNull() ?: 0L
                    if (seenFinals.add("$start|$spoken")) events.onFinal(spoken, language)
                } else {
                    partialText = spoken
                    partialLang = language
                }
            }
            if (partialText != null) events.onPartial(partialText, partialLang)
            return
        }
        val whole = result.child("text").stringOrNull()?.trim().orEmpty()
        if (whole.isNotEmpty()) events.onPartial(whole, null)
    }

    private fun languageOf(utterance: kotlinx.serialization.json.JsonObject): String? {
        val additions = utterance.child("additions")
        val objectForm = when (additions) {
            is kotlinx.serialization.json.JsonObject -> additions
            is kotlinx.serialization.json.JsonPrimitive -> {
                val raw = additions.stringOrNull()
                if (raw.isNullOrBlank()) null
                else runCatching { json.parseToJsonElement(raw).asObject() }.getOrNull()
            }
            else -> null
        }
        return objectForm?.child("lid_lang").stringOrNull()
            ?: objectForm?.child("language").stringOrNull()
            ?: utterance.child("language").stringOrNull()
    }

    private fun logSuffix(): String = logId?.let { " logid=$it" }.orEmpty()

    private fun fail(message: String) {
        if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(message))
        events.onError(message)
    }
}
