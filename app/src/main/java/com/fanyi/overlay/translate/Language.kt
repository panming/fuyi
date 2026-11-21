package com.fanyi.overlay.translate

/**
 * Chinese speech is shown as-is. Mandarin, Cantonese and other Chinese dialects
 * count as Chinese. Unknown text is translated unless the characters are clearly Han.
 */
/**
 * Maps an ASR language tag onto a Doubao machine-translation source code.
 * Unknown tags return null so the request omits source_language and the service auto-detects.
 * Official simplified Chinese code is zh.
 */
fun doubaoMtSourceLanguage(tag: String?): String? {
    val raw = tag?.trim()?.lowercase().orEmpty()
    if (raw.isEmpty() || raw == "other_langs" || raw == "others") return null
    if (raw == "zh-hant") return "zh-Hant"
    val direct = setOf(
        "zh", "en", "ja", "ko", "fr", "de", "es", "pt", "ru", "ar", "it", "nl",
        "pl", "ro", "sv", "da", "nb", "fi", "hu", "cs", "hr", "el", "he", "tr",
        "uk", "th", "vi", "id", "ms", "tl", "hi",
    )
    if (raw in direct) return raw
    return when {
        raw.contains("mand") || raw.contains("yue") || raw.contains("dia_") || raw.startsWith("zh") -> "zh"
        raw.contains("speech_ja") || raw.contains("singing_ja") -> "ja"
        raw.contains("speech_ko") || raw.contains("singing_ko") -> "ko"
        raw.contains("speech_th") || raw.contains("singing_th") -> "th"
        raw.contains("speech_hi") || raw.contains("singing_hi") -> "hi"
        raw.contains("speech_en") || raw.contains("singing_en") || raw.contains("en-us") -> "en"
        else -> null
    }
}

fun shouldSkipTranslation(languageTag: String?, text: String): Boolean {
    val tag = languageTag?.trim()?.lowercase().orEmpty()
    if (tag.isNotEmpty()) {
        val foreign = tag.contains("speech_en") ||
            tag.contains("singing_en") ||
            tag.contains("en-us") ||
            tag.contains("ja") ||
            tag.contains("ko") ||
            tag.contains("other_langs") ||
            tag == "en" ||
            tag == "ja" ||
            tag == "ko" ||
            tag == "others"
        if (foreign) return false
        val chinese = tag.contains("mand") ||
            tag.contains("yue") ||
            tag.contains("dia_") ||
            tag.startsWith("zh") ||
            tag == "cn" ||
            tag.contains("chinese")
        if (chinese) return true
    }
    if (text.any { it in '\u3040'..'\u30ff' }) return false
    if (text.any { it in '\uac00'..'\ud7af' }) return false
    val han = text.count { it in '\u4e00'..'\u9fff' }
    val letters = text.count { it.isLetter() }
    if (letters == 0) return true
    return han.toFloat() / letters >= 0.65f
}

fun friendlyFailure(error: Throwable): String {
    val raw = error.message?.trim().orEmpty()
    val lower = raw.lowercase()
    return when {
        lower.contains("unable to resolve host") || lower.contains("unknownhost") ->
            "连不上服务器。检查手机网络后再试。"
        lower.contains("timeout") || lower.contains("timed out") ->
            "连接超时。可以再点一次开始。"
        lower.contains("403") || lower.contains("401") || lower.contains("not granted") ||
            lower.contains("access denied") || lower.contains("authfailure") || lower.contains("signature") ->
            "鉴权失败。请核对该服务商要求的密钥，应用不会改用其它服务商重试。"
        raw.contains("配额") || raw.contains("欠费") || raw.contains("资源包") ||
            lower.contains("quota") || lower.contains("insufficientbalance") ->
            "配额或余额不足。请到当前服务商控制台查看，应用不会把内容改发给别的服务商。"
        raw.isBlank() -> "出错了：${error.javaClass.simpleName}"
        else -> raw.take(280)
    }
}
