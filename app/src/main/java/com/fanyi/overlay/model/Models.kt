package com.fanyi.overlay.model

enum class Provider { VOLCENGINE, ALIYUN, TENCENT, OPENAI }

enum class AsrMode { REALTIME, MULTILANG }

enum class RunPhase { IDLE, STARTING, LISTENING, PAUSED, STOPPING, ERROR }

enum class LinePhase { FINAL, TRANSLATING, DONE, SKIPPED, FAILED }

data class AppSettings(
    val asrProvider: Provider = Provider.VOLCENGINE,
    val mtProvider: Provider = Provider.VOLCENGINE,
    val tencentAppId: String = "",
    val tencentSecretId: String = "",
    val tencentSecretKey: String = "",
    val tencentEngine: String = "",
    val tencentMtApiKey: String = "",
    val tencentMtModel: String = "",
    val openaiBaseUrl: String = "",
    val openaiModel: String = "",
    val openaiApiKey: String = "",
    val volcApiKey: String = "",
    val volcMtApiKey: String = "",
    val volcAppId: String = "",
    val volcAccessToken: String = "",
    val volcResourceId: String = "",
    val volcEndpoint: String = "",
    val volcMode: AsrMode = AsrMode.REALTIME,
    val volcAk: String = "",
    val volcSk: String = "",
    val aliApiKey: String = "",
    val aliWorkspace: String = "",
    val aliAk: String = "",
    val aliSk: String = "",
    val overlayAlpha: Float = 0.88f,
    val overlayTextSp: Float = 15f,
    val setupDone: Boolean = false,
    val overlayX: Int = -1,
    val overlayY: Int = -1,
    val sourceLanguage: String = SourceLanguages.AUTO,
)

data class CaptionLine(
    val id: Long,
    val original: String,
    val translation: String? = null,
    val phase: LinePhase,
    val language: String? = null,
    val error: String? = null,
)

data class PartialCaption(
    val text: String,
    val language: String? = null,
    val translation: String? = null,
)

data class TranslationResult(
    val translated: String,
    val detectedSource: String?,
)

object Endpoints {
    const val VOLC_ASYNC = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    const val VOLC_NOSTREAM = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream"
    const val VOLC_RESOURCE = "volc.seedasr.sauc.duration"
    const val VOLC_MT = "https://openspeech.bytedance.com/api/v3/machine_translation/matx_translate"
    const val VOLC_MT_RESOURCE = "volc.speech.mt"
    const val ALI_WS = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
    const val ALI_MT = "https://mt.cn-hangzhou.aliyuncs.com/"

    fun volcAsrUrl(settings: AppSettings): String {
        val custom = settings.volcEndpoint.trim()
        if (custom.isNotEmpty()) return custom
        val language = settings.sourceLanguage.ifBlank { SourceLanguages.AUTO }
        return if (SourceLanguages.prefersLowLatency(language)) VOLC_ASYNC else VOLC_NOSTREAM
    }

    fun volcResource(settings: AppSettings): String {
        val custom = settings.volcResourceId.trim()
        return custom.ifEmpty { VOLC_RESOURCE }
    }

    fun aliAsrUrl(settings: AppSettings): String {
        val workspace = settings.aliWorkspace.trim()
        if (workspace.isEmpty()) return ALI_WS
        return "wss://$workspace.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference"
    }
}

fun volcTranslationApiKey(settings: AppSettings): String {
    return settings.volcMtApiKey.trim().ifEmpty { settings.volcApiKey.trim() }
}

fun asrMissingMessage(settings: AppSettings): String? = asrBlockMessage(settings)

fun translationConfigured(settings: AppSettings): Boolean = mtBlockMessage(settings) == null

fun vendorLabel(provider: Provider): String = when (provider) {
    Provider.VOLCENGINE -> "火山引擎"
    Provider.ALIYUN -> "阿里云"
    Provider.TENCENT -> "腾讯云"
    Provider.OPENAI -> "OpenAI 兼容"
}

fun recognitionFingerprint(settings: AppSettings): String {
    return listOf(
        settings.asrProvider.name,
        settings.sourceLanguage,
        settings.volcEndpoint.trim(),
        settings.volcResourceId.trim(),
        settings.tencentEngine.trim(),
        settings.aliWorkspace.trim(),
    ).joinToString("|")
}
