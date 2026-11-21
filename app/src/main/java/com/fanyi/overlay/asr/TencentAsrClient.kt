package com.fanyi.overlay.asr

import com.fanyi.overlay.model.AppSettings
import com.fanyi.overlay.model.tencentEngineFor
import com.fanyi.overlay.net.Net
import com.fanyi.overlay.translate.friendlyFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.UUID
import kotlin.random.Random

/**
 * Tencent Cloud realtime ASR, wss://asr.cloud.tencent.com/asr/v2/{AppId}.
 * Audio frames are binary PCM. The end frame is the text message {"type":"end"}.
 */
internal class TencentAsrClient(
    private val settings: AppSettings,
    private val events: AsrEvents,
) : StreamingAsr {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val ready = CompletableDeferred<Unit>()
    private val mutex = Mutex()
    private var socket: WebSocket? = null
    private var started = false

    override suspend fun start() {
        val engine = tencentEngineFor(settings).first
            ?: error(tencentEngineFor(settings).second ?: "腾讯云识别引擎不可用")
        val voiceId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis() / 1000
        val url = TencentAsrAuth.signedUrl(
            appId = settings.tencentAppId.trim(),
            secretId = settings.tencentSecretId.trim(),
            secretKey = settings.tencentSecretKey.trim(),
            engine = engine,
            voiceId = voiceId,
            timestampSeconds = now,
            nonce = Random.nextInt(1, 1_000_000_000),
        )
        val request = Request.Builder().url(url).build()
        socket = Net.websocket.newWebSocket(request, listener(engine))
        withTimeout(12_000) { ready.await() }
    }

    override suspend fun sendPcm(frame: ByteArray) {
        mutex.withLock {
            if (!started || frame.isEmpty()) return
            socket?.send(frame.toByteString())
        }
    }

    override suspend fun finish() {
        mutex.withLock {
            started = false
            socket?.send("""{"type":"end"}""")
        }
        kotlinx.coroutines.delay(400)
        socket?.close(1000, "stop")
        socket = null
    }

    private fun listener(engine: String): WebSocketListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val root = try {
                json.parseToJsonElement(text).asObject()
            } catch (_: Throwable) {
                null
            } ?: return
            val code = root.child("code").intOrNull()
            if (code != null && code != 0) {
                val message = root.child("message").stringOrNull() ?: "腾讯云识别失败"
                fail("$message（$code）")
                return
            }
            if (!started && code == 0) {
                started = true
                events.onReady("腾讯云识别已连接 · $engine")
                if (!ready.isCompleted) ready.complete(Unit)
            }
            val result = root.child("result").asObject() ?: return
            val spoken = result.child("voice_text_str").stringOrNull()?.trim().orEmpty()
            if (spoken.isEmpty()) return
            val language = result.child("language").stringOrNull()
            when (result.child("slice_type").intOrNull()) {
                2 -> events.onFinal(spoken, language)
                else -> events.onPartial(spoken, language)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val http = response?.code?.let { " HTTP $it" }.orEmpty()
            fail(friendlyFailure(t) + http)
        }
    }

    private fun fail(message: String) {
        if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(message))
        events.onError(message)
    }
}
