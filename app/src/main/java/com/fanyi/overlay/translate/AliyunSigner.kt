package com.fanyi.overlay.translate

import java.net.URLEncoder
import java.util.Base64
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Aliyun RPC signature version 1.0 for alimt TranslateGeneral (2018-10-12).
 */
internal object AliyunSigner {
    private val timestamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun translateForm(
        accessKeyId: String,
        accessKeySecret: String,
        text: String,
        sourceLanguage: String = "auto",
        now: Instant = Instant.now(),
        nonce: String = UUID.randomUUID().toString(),
    ): String {
        val params = TreeMap<String, String>()
        params["AccessKeyId"] = accessKeyId
        params["Action"] = "TranslateGeneral"
        params["Format"] = "JSON"
        params["FormatType"] = "text"
        params["RegionId"] = "cn-hangzhou"
        params["Scene"] = "general"
        params["SignatureMethod"] = "HMAC-SHA1"
        params["SignatureNonce"] = nonce
        params["SignatureVersion"] = "1.0"
        params["SourceLanguage"] = sourceLanguage
        params["SourceText"] = text
        params["TargetLanguage"] = "zh"
        params["Timestamp"] = timestamp.format(now.truncatedTo(ChronoUnit.SECONDS))
        params["Version"] = "2018-10-12"
        val canonical = params.entries.joinToString("&") { (key, value) ->
            "${percent(key)}=${percent(value)}"
        }
        val stringToSign = "POST&${percent("/")}&${percent(canonical)}"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec("$accessKeySecret&".toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signature = Base64.getEncoder().encodeToString(
            mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)),
        )
        return "$canonical&${percent("Signature")}=${percent(signature)}"
    }

    fun percent(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }
}
