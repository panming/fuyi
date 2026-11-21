package com.fanyi.overlay.asr

import com.fanyi.overlay.model.AppSettings
import com.fanyi.overlay.model.Endpoints
import com.fanyi.overlay.model.aliyunAsrHintCode
import com.fanyi.overlay.net.Net
import com.fanyi.overlay.translate.friendlyFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.UUID

/**
 * DashScope paraformer-realtime-v2.
 * Default URL remains wss://dashscope.aliyuncs.com/api-ws/v1/inference.
 * When a workspace id is set, the Beijing workspace host is used instead.
 *
 * https://help.aliyun.com/zh/model-studio/websocket-for-paraformer-real-time-service
 */
internal class AliyunAsrClient(
    private val settings: AppSettings,
    private val events: AsrEvents,
) : StreamingAsr {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val ready = CompletableDeferred<Unit>()
    private val mutex = Mutex()
    private val taskId = UUID.randomUUID().toString()
    private var socket: WebSocket? = null
    private var finished = false
    @Volatile private var taskStarted = false

    override suspend fun start() {
        val url = Endpoints.aliAsrUrl(settings)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${settings.aliApiKey.trim()}")
            .addHeader("user-agent", "fanyi-overlay/1.0")
            .build()
        socket = Net.websocket.newWebSocket(request, listener())
        withTimeout(12_000) { ready.await() }
    }

    override suspend fun sendPcm(frame: ByteArray) {
        mutex.withLock {
            if (finished || !taskStarted || frame.isEmpty()) return
            socket?.send(frame.toByteString())
        }
    }

    override suspend fun finish() {
        mutex.withLock {
            if (finished) return
            finished = true
            socket?.send(finishTask())
        }
        kotlinx.coroutines.delay(700)
        socket?.close(1000, "stop")
        socket = null
    }

    private fun listener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(runTask())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handle(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val http = response?.code?.let { " HTTP $it" }.orEmpty()
            fail(friendlyFailure(t) + http)
        }
    }

    private fun handle(text: String) {
        val root = try {
            json.parseToJsonElement(text).asObject()
        } catch (_: Throwable) {
            null
        } ?: return
        val header = root.child("header").asObject() ?: return
        when (header.child("event").stringOrNull()) {
            "task-started" -> {
                taskStarted = true
                events.onReady("阿里云 Paraformer 已连接")
                if (!ready.isCompleted) ready.complete(Unit)
            }
            "result-generated" -> {
                val sentence = root.child("payload").asObject()
                    ?.child("output").asObject()
                    ?.child("sentence").asObject()
                    ?: return
                if (sentence.child("heartbeat").boolOrNull() == true) return
                val spoken = sentence.child("text").stringOrNull()?.trim().orEmpty()
                if (spoken.isEmpty()) return
                val language = sentence.child("language").stringOrNull()
                if (sentence.child("sentence_end").boolOrNull() == true) {
                    events.onFinal(spoken, language)
                } else {
                    events.onPartial(spoken, language)
                }
            }
            "task-failed" -> {
                val message = header.child("error_message").stringOrNull()
                    ?: root.child("payload").asObject()?.child("message").stringOrNull()
                    ?: "阿里云识别失败"
                val code = header.child("error_code").stringOrNull().orEmpty()
                fail(if (code.isBlank()) message else "$message（$code）")
            }
            "task-finished" -> Unit
        }
    }

    private fun runTask(): String {
        val root = buildJsonObject {
            put("header", buildJsonObject {
                put("action", "run-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", buildJsonObject {
                put("task_group", "audio")
                put("task", "asr")
                put("function", "recognition")
                put("model", "paraformer-realtime-v2")
                put("input", buildJsonObject {})
                put("parameters", buildJsonObject {
                    put("format", "pcm")
                    put("sample_rate", 16000)
                    put("disfluency_removal_enabled", false)
                    put("semantic_punctuation_enabled", false)
                    put("max_sentence_silence", 700)
                    put("punctuation_prediction_enabled", true)
                    put("heartbeat", true)
                    aliyunAsrHintCode(settings.sourceLanguage)?.let { hint ->
                        put("language_hints", buildJsonArray { add(JsonPrimitive(hint)) })
                    }
                })
            })
        }
        return root.toString()
    }

    private fun finishTask(): String {
        return buildJsonObject {
            put("header", buildJsonObject {
                put("action", "finish-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", buildJsonObject {
                put("input", buildJsonObject {})
            })
        }.toString()
    }

    private fun fail(message: String) {
        if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(message))
        events.onError(message)
    }
}
