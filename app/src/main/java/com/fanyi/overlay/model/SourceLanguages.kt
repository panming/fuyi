package com.fanyi.overlay.model

/**
 * Codes accepted by Doubao streaming ASR 2.0 on bigmodel_nostream
 * (enable_auto_lang or audio.language). zh-CN and en-US stay on the
 * low-latency bigmodel_async socket.
 */
data class SourceLanguage(val code: String, val label: String)

object SourceLanguages {
    const val AUTO = "auto"

    val all: List<SourceLanguage> = listOf(
        SourceLanguage(AUTO, "自动识别"),
        SourceLanguage("zh-CN", "中文"),
        SourceLanguage("en-US", "英语"),
        SourceLanguage("ja-JP", "日语"),
        SourceLanguage("ko-KR", "韩语"),
        SourceLanguage("es-MX", "西班牙语"),
        SourceLanguage("fr-FR", "法语"),
        SourceLanguage("de-DE", "德语"),
        SourceLanguage("ru-RU", "俄语"),
        SourceLanguage("yue-CN", "粤语"),
        SourceLanguage("pt-BR", "葡萄牙语"),
        SourceLanguage("it-IT", "意大利语"),
        SourceLanguage("vi-VN", "越南语"),
        SourceLanguage("th-TH", "泰语"),
        SourceLanguage("id-ID", "印尼语"),
        SourceLanguage("ms-MY", "马来语"),
        SourceLanguage("fil-PH", "菲律宾语"),
        SourceLanguage("ar-SA", "阿拉伯语"),
        SourceLanguage("tr-TR", "土耳其语"),
        SourceLanguage("pl-PL", "波兰语"),
        SourceLanguage("nl-NL", "荷兰语"),
        SourceLanguage("uk-UA", "乌克兰语"),
        SourceLanguage("ro-RO", "罗马尼亚语"),
        SourceLanguage("el-GR", "希腊语"),
        SourceLanguage("bn-BD", "孟加拉语"),
        SourceLanguage("ne-NP", "尼泊尔语"),
    )

    fun labelOf(code: String): String = all.firstOrNull { it.code == code }?.label ?: "自动识别"

    fun prefersLowLatency(code: String): Boolean = code == "zh-CN" || code == "en-US"

    /** Machine-translation source code. Null means omit the field and let the API detect. */
    fun mtCode(code: String): String? = when (code) {
        "zh-CN", "yue-CN" -> "zh"
        "en-US" -> "en"
        "ja-JP" -> "ja"
        "ko-KR" -> "ko"
        "es-MX" -> "es"
        "pt-BR" -> "pt"
        "de-DE" -> "de"
        "fr-FR" -> "fr"
        "ru-RU" -> "ru"
        "ar-SA" -> "ar"
        "it-IT" -> "it"
        "nl-NL" -> "nl"
        "pl-PL" -> "pl"
        "ro-RO" -> "ro"
        "th-TH" -> "th"
        "vi-VN" -> "vi"
        "id-ID" -> "id"
        "ms-MY" -> "ms"
        "fil-PH" -> "tl"
        "el-GR" -> "el"
        "tr-TR" -> "tr"
        "uk-UA" -> "uk"
        else -> null
    }
}
