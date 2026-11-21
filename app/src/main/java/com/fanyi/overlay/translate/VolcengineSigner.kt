package com.fanyi.overlay.translate

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Volcengine OpenAPI HMAC-SHA256.
 * Host, region and service match the machine-translation TranslateText API.
 * The signing key is derived from the raw secret, not from a hex string.
 */
internal object VolcengineSigner {
    private val dateTime: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun signTranslate(accessKeyId: String, secret: String, body: ByteArray, now: Instant = Instant.now()): SignedCall {
        val instant = now.truncatedTo(ChronoUnit.SECONDS)
        val amzDate = dateTime.format(instant)
        val day = amzDate.substring(0, 8)
        val host = "translate.volcengineapi.com"
        val region = "cn-north-1"
        val service = "translate"
        val query = "Action=TranslateText&Version=2020-06-01"
        val bodyHash = sha256Hex(body)
        val signedHeaders = "content-type;host;x-content-sha256;x-date"
        val canonicalHeaders = buildString {
            append("content-type:application/json\n")
            append("host:").append(host).append('\n')
            append("x-content-sha256:").append(bodyHash).append('\n')
            append("x-date:").append(amzDate).append('\n')
        }
        val canonical = "POST\n/\n$query\n$canonicalHeaders\n$signedHeaders\n$bodyHash"
        val scope = "$day/$region/$service/request"
        val stringToSign = "HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonical.toByteArray(Charsets.UTF_8))}"
        val signingKey = derive(secret, day, region, service)
        val signature = hex(hmac(signingKey, stringToSign))
        val authorization = "HMAC-SHA256 Credential=$accessKeyId/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
        return SignedCall(
            url = "https://$host/?$query",
            body = body,
            headers = linkedMapOf(
                "Host" to host,
                "Content-Type" to "application/json",
                "X-Content-Sha256" to bodyHash,
                "X-Date" to amzDate,
                "Authorization" to authorization,
            ),
        )
    }

    private fun derive(secret: String, day: String, region: String, service: String): ByteArray {
        val kDate = hmac(secret.toByteArray(Charsets.UTF_8), day)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        return hmac(kService, "request")
    }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

    private fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }
}

internal data class SignedCall(
    val url: String,
    val body: ByteArray,
    val headers: Map<String, String>,
)
