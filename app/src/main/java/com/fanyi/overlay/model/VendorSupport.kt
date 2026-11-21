package com.fanyi.overlay.model

/**
 * Language and credential checks. A locked language is never rewritten into
 * another language; unsupported combinations return a message instead.
 */
fun asrBlockMessage(settings: AppSettings): String? {
    when (settings.asrProvider) {
        Provider.OPENAI ->
            return "OpenAI 兼容接口只用于文本翻译，不能当作流式语音识别。请另选识别服务商。"
        Provider.VOLCENGINE -> {
            if (settings.volcApiKey.isBlank() &&
                (settings.volcAppId.isBlank() || settings.volcAccessToken.isBlank())
            ) {
                return "火山引擎识别需要豆包语音 API Key，或旧版 App ID 与 Access Token。"
            }
            val endpoint = settings.volcEndpoint.trim()
            if (endpoint.isNotEmpty() && !endpoint.startsWith("wss://")) {
                return "火山引擎识别地址必须以 wss:// 开头。留空则按来源语言选择官方地址。"
            }
        }
        Provider.ALIYUN -> {
            if (settings.aliApiKey.isBlank()) return "阿里云识别需要百炼 DashScope API Key。"
            val workspace = settings.aliWorkspace.trim()
            if (workspace.isNotEmpty() && !workspace.matches(Regex("[A-Za-z0-9_-]{1,80}"))) {
                return "阿里云业务空间 ID 只能包含字母、数字、下划线和短横线。"
            }
            aliyunAsrHint(settings.sourceLanguage)?.let { return it }
        }
        Provider.TENCENT -> {
            if (!settings.tencentAppId.trim().matches(Regex("\\d{4,20}"))) {
                return "腾讯云识别需要账号 APPID（纯数字，在账号中心查看）。"
            }
            if (settings.tencentSecretId.isBlank() || settings.tencentSecretKey.isBlank()) {
                return "腾讯云识别需要 SecretId 和 SecretKey（API 密钥管理，不是豆包 API Key）。"
            }
            tencentEngineFor(settings).second?.let { return it }
        }
    }
    return null
}

fun mtBlockMessage(settings: AppSettings): String? {
    when (settings.mtProvider) {
        Provider.VOLCENGINE -> {
            val ready = volcTranslationApiKey(settings).isNotEmpty() ||
                (settings.volcAppId.isNotBlank() && settings.volcAccessToken.isNotBlank())
            if (!ready) return "火山引擎翻译需要豆包语音 API Key，或旧版 App ID 与 Access Token。"
            if (settings.sourceLanguage != SourceLanguages.AUTO &&
                SourceLanguages.mtCode(settings.sourceLanguage) == null
            ) {
                return "豆包机器翻译没有「${SourceLanguages.labelOf(settings.sourceLanguage)}」的源语言代码。请改来源语言，或换一个支持它的翻译服务。不会改成其它语言。"
            }
        }
        Provider.ALIYUN -> {
            if (settings.aliAk.isBlank() || settings.aliSk.isBlank()) {
                return "阿里云翻译需要 RAM AccessKey ID 和 AccessKey Secret。"
            }
            aliyunMtCode(settings.sourceLanguage)?.let { return null }
            if (settings.sourceLanguage == SourceLanguages.AUTO) return null
            return "阿里云文本翻译未配置「${SourceLanguages.labelOf(settings.sourceLanguage)}」的语言代码。请改来源语言或翻译服务。不会改成中文或英语。"
        }
        Provider.TENCENT -> return tencentTranslationBlock(settings)
        Provider.OPENAI -> {
            val url = settings.openaiBaseUrl.trim()
            if (!url.startsWith("https://")) return "OpenAI 兼容翻译需要以 https:// 开头的地址。"
            if (settings.openaiModel.isBlank()) return "OpenAI 兼容翻译需要模型名，例如 gpt-4o-mini。"
            if (settings.openaiApiKey.isBlank()) return "OpenAI 兼容翻译需要 API Key。音频不会发到这个地址。"
        }
    }
    return null
}

/** First is the engine id when usable. Second is a user-facing error and then the id is null. */
fun tencentEngineFor(settings: AppSettings): Pair<String?, String?> {
    val override = settings.tencentEngine.trim()
    if (override.isNotEmpty()) {
        if (override !in TENCENT_ENGINES) {
            return null to "腾讯云引擎「$override」不在当前官方 engine_model_type 列表中。请留空并锁定来源语言，或填写文档中的引擎名。"
        }
        return override to null
    }
    if (settings.sourceLanguage == SourceLanguages.AUTO) {
        return null to "腾讯云没有覆盖全部语种的自动识别，因此不会把「自动识别」改成普通话。请锁定来源语言，或在腾讯云引擎中填写 16k_zh_en（中英及方言）或 16k_multi_lang（15 个外语，不含中文）。"
    }
    val engine = TENCENT_ENGINE_BY_LANGUAGE[settings.sourceLanguage]
        ?: return null to "腾讯云实时识别文档没有与「${SourceLanguages.labelOf(settings.sourceLanguage)}」对应的引擎。请改来源语言或识别服务。不会改用其它语种引擎。"
    return engine to null
}

fun tencentTranslationBlock(settings: AppSettings): String? {
    if (settings.tencentMtApiKey.isNotBlank()) return tokenHubProblem(settings.sourceLanguage)
    if (settings.tencentSecretId.isBlank() || settings.tencentSecretKey.isBlank()) {
        return "腾讯云翻译需要二选一：TokenHub API Key（当前混元翻译，地址 tokenhub.tencentmaas.com），或仍可调用旧接口的 SecretId 与 SecretKey。不会改用其它服务商。"
    }
    return tencentMtProblem(settings.sourceLanguage)
}

fun tokenHubProblem(language: String): String? {
    if (language == SourceLanguages.AUTO) return null
    if (language in TOKENHUB_SOURCE) return null
    return "混元翻译语言表没有「${SourceLanguages.labelOf(language)}」。请改来源语言或翻译服务。不会改成中文或英语。"
}

fun tokenHubSourceCode(language: String): String? = TOKENHUB_SOURCE[language]

fun tencentMtProblem(language: String): String? {
    if (language == SourceLanguages.AUTO) {
        return "腾讯云文本翻译当前要求填写 Source，文档语言表没有 auto。请锁定一种可以译成简体中文的语言，或把翻译改成火山引擎。不会擅自指定英语或中文。"
    }
    if (language == "hi-IN" || language == "hi") {
        return "腾讯云文本翻译里印地语只能译成英语，不能译成简体中文。"
    }
    val code = TENCENT_MT_PUBLIC[language]
        ?: return "腾讯云文本翻译不能把「${SourceLanguages.labelOf(language)}」译成简体中文。请改来源语言或翻译服务。"
    if (code == "hi") {
        return "腾讯云文本翻译里印地语只能译成英语，不能译成简体中文。"
    }
    return null
}

fun aliyunAsrHint(language: String): String? {
    if (language == SourceLanguages.AUTO) return null
    if (language in ALIYUN_ASR_HINTS) return null
    return "阿里云 paraformer-realtime-v2 的 language_hints 不含「${SourceLanguages.labelOf(language)}」。请改来源语言或识别服务。不会去掉你的选择改成中文。"
}

fun aliyunAsrHintCode(language: String): String? = ALIYUN_ASR_HINTS[language]

fun aliyunMtCode(language: String): String? {
    if (language == SourceLanguages.AUTO) return "auto"
    return ALIYUN_MT[language]
}

private val TENCENT_ENGINES = setOf(
    "Hy-ASR-3.0-preview",
    "16k_zh_en",
    "16k_multi_lang",
    "16k_zh",
    "16k_zh-TW",
    "16k_yue",
    "16k_en",
    "16k_en_large",
    "16k_ko",
    "16k_ja",
    "16k_th",
    "16k_id",
    "16k_vi",
    "16k_ms",
    "16k_fil",
    "16k_pt",
    "16k_tr",
    "16k_ar",
    "16k_es",
    "16k_hi",
    "16k_fr",
    "16k_de",
)

private val TENCENT_ENGINE_BY_LANGUAGE = mapOf(
    "zh-CN" to "16k_zh",
    "en-US" to "16k_en",
    "ja-JP" to "16k_ja",
    "ko-KR" to "16k_ko",
    "yue-CN" to "16k_yue",
    "es-MX" to "16k_es",
    "fr-FR" to "16k_fr",
    "de-DE" to "16k_de",
    "pt-BR" to "16k_pt",
    "vi-VN" to "16k_vi",
    "th-TH" to "16k_th",
    "id-ID" to "16k_id",
    "ms-MY" to "16k_ms",
    "fil-PH" to "16k_fil",
    "ar-SA" to "16k_ar",
    "tr-TR" to "16k_tr",
    "hi-IN" to "16k_hi",
)

internal val TENCENT_MT_PUBLIC = mapOf(
    "zh-CN" to "zh",
    "en-US" to "en",
    "ja-JP" to "ja",
    "ko-KR" to "ko",
    "es-MX" to "es",
    "fr-FR" to "fr",
    "de-DE" to "de",
    "ru-RU" to "ru",
    "pt-BR" to "pt",
    "it-IT" to "it",
    "vi-VN" to "vi",
    "th-TH" to "th",
    "id-ID" to "id",
    "ms-MY" to "ms",
    "ar-SA" to "ar",
    "tr-TR" to "tr",
    "hi-IN" to "hi",
)

internal val TOKENHUB_SOURCE = mapOf(
    "zh-CN" to "zh",
    "en-US" to "en",
    "ja-JP" to "ja",
    "ko-KR" to "ko",
    "yue-CN" to "yue",
    "es-MX" to "es",
    "fr-FR" to "fr",
    "de-DE" to "de",
    "ru-RU" to "ru",
    "pt-BR" to "pt",
    "it-IT" to "it",
    "vi-VN" to "vi",
    "th-TH" to "th",
    "id-ID" to "id",
    "ms-MY" to "ms",
    "fil-PH" to "fil",
    "ar-SA" to "ar",
    "tr-TR" to "tr",
    "pl-PL" to "pl",
    "nl-NL" to "nl",
    "uk-UA" to "uk",
    "bn-BD" to "bn",
)

private val ALIYUN_ASR_HINTS = mapOf(
    "zh-CN" to "zh",
    "en-US" to "en",
    "ja-JP" to "ja",
    "yue-CN" to "yue",
    "ko-KR" to "ko",
    "de-DE" to "de",
    "fr-FR" to "fr",
    "ru-RU" to "ru",
)

private val ALIYUN_MT = mapOf(
    "zh-CN" to "zh",
    "en-US" to "en",
    "ja-JP" to "ja",
    "ko-KR" to "ko",
    "es-MX" to "es",
    "fr-FR" to "fr",
    "de-DE" to "de",
    "ru-RU" to "ru",
    "pt-BR" to "pt",
    "it-IT" to "it",
    "vi-VN" to "vi",
    "th-TH" to "th",
    "id-ID" to "id",
    "ms-MY" to "ms",
    "ar-SA" to "ar",
    "tr-TR" to "tr",
)

fun parseStoredVendor(raw: String?): Provider = when (raw) {
    "aliyun" -> Provider.ALIYUN
    "tencent" -> Provider.TENCENT
    "openai" -> Provider.OPENAI
    else -> Provider.VOLCENGINE
}

fun vendorWire(provider: Provider): String = when (provider) {
    Provider.VOLCENGINE -> "volcengine"
    Provider.ALIYUN -> "aliyun"
    Provider.TENCENT -> "tencent"
    Provider.OPENAI -> "openai"
}
