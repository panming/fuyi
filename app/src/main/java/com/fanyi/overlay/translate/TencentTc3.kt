package com.fanyi.overlay.translate

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tencent Cloud API signature v3 (TC3-HMAC-SHA256) for TextTranslate.
 * https://cloud.tencent.com/document/api/551/30636
 */
internal object TencentTc3 {
    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    fun authorization(
        secretId: String,
        secretKey: String,
        host: String,
        service: String,
        action: String,
        timestampSeconds: Long,
        payload: ByteArray,
        contentType: String = "application/json",
    ): String {
        val date = dayFormat.format(Instant.ofEpochSecond(timestampSeconds))
        val canonicalHeaders = "content-type:$contentType\nhost:$host\nx-tc-action:${action.lowercase()}\n"
        val signedHeaders = "content-type;host;x-tc-action"
        val canonical = "POST\n/\n\n$canonicalHeaders\n$signedHeaders\n${sha256Hex(payload)}"
        val scope = "$date/$service/tc3_request"
        val stringToSign = "TC3-HMAC-SHA256\n$timestampSeconds\n$scope\n${sha256Hex(canonical.toByteArray(Charsets.UTF_8))}"
        val signing = hmac(hmac(hmac("TC3$secretKey".toByteArray(Charsets.UTF_8), date), service), "tc3_request")
        val signature = hex(hmac(signing, stringToSign))
        return "TC3-HMAC-SHA256 Credential=$secretId/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

    private fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }
}
