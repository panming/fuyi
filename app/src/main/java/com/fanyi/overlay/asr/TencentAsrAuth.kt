package com.fanyi.overlay.asr

import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tencent realtime ASR WebSocket signature.
 * https://cloud.tencent.com/document/product/1093/48982
 * The signed string is the host and path plus sorted query, without wss://.
 */
internal object TencentAsrAuth {
    fun signedUrl(
        appId: String,
        secretId: String,
        secretKey: String,
        engine: String,
        voiceId: String,
        timestampSeconds: Long,
        nonce: Int,
        expiredSeconds: Long = timestampSeconds + 86_400,
    ): String {
        val params = linkedMapOf(
            "engine_model_type" to engine,
            "expired" to expiredSeconds.toString(),
            "needvad" to "1",
            "nonce" to nonce.toString(),
            "secretid" to secretId,
            "timestamp" to timestampSeconds.toString(),
            "voice_format" to "1",
            "voice_id" to voiceId,
        )
        if (engine in VAD_SILENCE_ENGINES) {
            params["vad_silence_time"] = "500"
        }
        val sorted = params.toSortedMap()
        val query = sorted.entries.joinToString("&") { (key, value) -> "$key=$value" }
        val plain = "asr.cloud.tencent.com/asr/v2/$appId?$query"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(plain.toByteArray(Charsets.UTF_8)))
        val encoded = URLEncoder.encode(signature, "UTF-8")
        return "wss://$plain&signature=$encoded"
    }

    private val VAD_SILENCE_ENGINES = setOf(
        "16k_zh",
        "16k_zh_en",
        "16k_en",
        "16k_en_large",
    )
}
