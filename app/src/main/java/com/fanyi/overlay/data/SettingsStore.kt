package com.fanyi.overlay.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fanyi.overlay.model.AppSettings
import com.fanyi.overlay.model.AsrMode
import com.fanyi.overlay.model.Provider
import com.fanyi.overlay.model.SourceLanguages
import com.fanyi.overlay.model.parseStoredVendor
import com.fanyi.overlay.model.vendorWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Logical config, stored encrypted. Nothing here is a secret in source control.
 *
 * asr_provider / mt_provider: volcengine | aliyun | tencent | openai
 * provider: 旧字段。没有新字段时，识别和翻译都沿用它，已保存的密钥不会被清空。
 * volcengine.apiKey              豆包语音 API Key。识别和机器翻译默认共用，请求头 X-Api-Key
 * volcengine.mtApiKey            可选。翻译专用 API Key；留空则用 apiKey
 * volcengine.appId + accessToken 仅旧版控制台
 * volcengine.resourceId          默认 volc.seedasr.sauc.duration（流式语音识别 2.0 小时版）
 * volcengine.endpoint            空则按模式选择 bigmodel_async 或 bigmodel_nostream
 * translation                    POST /api/v3/machine_translation/matx_translate
 *                                X-Api-Resource-Id: volc.speech.mt ，target_language: zh
 * aliyun.dashscopeApiKey         百炼 API Key，Authorization: Bearer
 * aliyun.workspaceId             可选，改走 {id}.cn-beijing.maas.aliyuncs.com
 * aliyun.accessKeyId/secret      机器翻译 TranslateGeneral 的 RAM 密钥
 */
class SettingsStore private constructor(
    private val prefs: SharedPreferences?,
) {
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val persisted: Boolean = prefs != null

    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_settings.value)
        write(next)
        _settings.value = next
    }

    private fun read(): AppSettings {
        val p = prefs ?: return AppSettings()
        val legacy = p.getString(KEY_PROVIDER, null)
        return AppSettings(
            asrProvider = storedVendor(p.getString(KEY_ASR, null), legacy),
            mtProvider = storedVendor(p.getString(KEY_MT, null), legacy),
            volcApiKey = p.getString(KEY_VOLC_API, "").orEmpty(),
            volcMtApiKey = p.getString(KEY_VOLC_MT_API, "").orEmpty(),
            volcAppId = p.getString(KEY_VOLC_APP, "").orEmpty(),
            volcAccessToken = p.getString(KEY_VOLC_TOKEN, "").orEmpty(),
            volcResourceId = p.getString(KEY_VOLC_RESOURCE, "").orEmpty(),
            volcEndpoint = p.getString(KEY_VOLC_ENDPOINT, "").orEmpty(),
            volcMode = if (p.getString(KEY_VOLC_MODE, "realtime") == "multilang") AsrMode.MULTILANG else AsrMode.REALTIME,
            volcAk = p.getString(KEY_VOLC_AK, "").orEmpty(),
            volcSk = p.getString(KEY_VOLC_SK, "").orEmpty(),
            aliApiKey = p.getString(KEY_ALI_API, "").orEmpty(),
            aliWorkspace = p.getString(KEY_ALI_WORKSPACE, "").orEmpty(),
            aliAk = p.getString(KEY_ALI_AK, "").orEmpty(),
            aliSk = p.getString(KEY_ALI_SK, "").orEmpty(),
            tencentAppId = p.getString(KEY_TENCENT_APP, "").orEmpty(),
            tencentSecretId = p.getString(KEY_TENCENT_ID, "").orEmpty(),
            tencentSecretKey = p.getString(KEY_TENCENT_KEY, "").orEmpty(),
            tencentEngine = p.getString(KEY_TENCENT_ENGINE, "").orEmpty(),
            tencentMtApiKey = p.getString(KEY_TENCENT_MT_KEY, "").orEmpty(),
            tencentMtModel = p.getString(KEY_TENCENT_MT_MODEL, "").orEmpty(),
            openaiBaseUrl = p.getString(KEY_OPENAI_URL, "").orEmpty(),
            openaiModel = p.getString(KEY_OPENAI_MODEL, "").orEmpty(),
            openaiApiKey = p.getString(KEY_OPENAI_KEY, "").orEmpty(),
            overlayAlpha = p.getFloat(KEY_ALPHA, 0.88f),
            overlayTextSp = p.getFloat(KEY_TEXT, 15f),
            setupDone = p.getBoolean(KEY_SETUP, false),
            overlayX = p.getInt(KEY_X, -1),
            overlayY = p.getInt(KEY_Y, -1),
            sourceLanguage = p.getString(KEY_SOURCE_LANG, SourceLanguages.AUTO).orEmpty()
                .ifBlank { SourceLanguages.AUTO },
        )
    }

    private fun write(value: AppSettings) {
        val p = prefs ?: return
        p.edit()
            .putString(KEY_PROVIDER, vendorWire(value.asrProvider))
            .putString(KEY_ASR, vendorWire(value.asrProvider))
            .putString(KEY_MT, vendorWire(value.mtProvider))
            .putString(KEY_VOLC_API, value.volcApiKey.trim())
            .putString(KEY_VOLC_MT_API, value.volcMtApiKey.trim())
            .putString(KEY_VOLC_APP, value.volcAppId.trim())
            .putString(KEY_VOLC_TOKEN, value.volcAccessToken.trim())
            .putString(KEY_VOLC_RESOURCE, value.volcResourceId.trim())
            .putString(KEY_VOLC_ENDPOINT, value.volcEndpoint.trim())
            .putString(KEY_VOLC_MODE, if (value.volcMode == AsrMode.MULTILANG) "multilang" else "realtime")
            .putString(KEY_VOLC_AK, value.volcAk.trim())
            .putString(KEY_VOLC_SK, value.volcSk.trim())
            .putString(KEY_ALI_API, value.aliApiKey.trim())
            .putString(KEY_ALI_WORKSPACE, value.aliWorkspace.trim())
            .putString(KEY_ALI_AK, value.aliAk.trim())
            .putString(KEY_ALI_SK, value.aliSk.trim())
            .putString(KEY_TENCENT_APP, value.tencentAppId.trim())
            .putString(KEY_TENCENT_ID, value.tencentSecretId.trim())
            .putString(KEY_TENCENT_KEY, value.tencentSecretKey.trim())
            .putString(KEY_TENCENT_ENGINE, value.tencentEngine.trim())
            .putString(KEY_TENCENT_MT_KEY, value.tencentMtApiKey.trim())
            .putString(KEY_TENCENT_MT_MODEL, value.tencentMtModel.trim())
            .putString(KEY_OPENAI_URL, value.openaiBaseUrl.trim())
            .putString(KEY_OPENAI_MODEL, value.openaiModel.trim())
            .putString(KEY_OPENAI_KEY, value.openaiApiKey.trim())
            .putFloat(KEY_ALPHA, value.overlayAlpha.coerceIn(0.45f, 1f))
            .putFloat(KEY_TEXT, value.overlayTextSp.coerceIn(12f, 24f))
            .putBoolean(KEY_SETUP, value.setupDone)
            .putInt(KEY_X, value.overlayX)
            .putInt(KEY_Y, value.overlayY)
            .putString(KEY_SOURCE_LANG, value.sourceLanguage.ifBlank { SourceLanguages.AUTO })
            .apply()
    }

    companion object {
        private const val FILE = "fanyi_secrets"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_ASR = "asr_provider"
        private const val KEY_MT = "mt_provider"
        private const val KEY_VOLC_API = "volc_api_key"
        private const val KEY_VOLC_MT_API = "volc_mt_api_key"
        private const val KEY_VOLC_APP = "volc_app_id"
        private const val KEY_VOLC_TOKEN = "volc_access_token"
        private const val KEY_VOLC_RESOURCE = "volc_resource_id"
        private const val KEY_VOLC_ENDPOINT = "volc_endpoint"
        private const val KEY_VOLC_MODE = "volc_mode"
        private const val KEY_VOLC_AK = "volc_ak"
        private const val KEY_VOLC_SK = "volc_sk"
        private const val KEY_ALI_API = "ali_api_key"
        private const val KEY_ALI_WORKSPACE = "ali_workspace"
        private const val KEY_ALI_AK = "ali_ak"
        private const val KEY_ALI_SK = "ali_sk"
        private const val KEY_ALPHA = "overlay_alpha"
        private const val KEY_TEXT = "overlay_text_sp"
        private const val KEY_SETUP = "setup_done"
        private const val KEY_X = "overlay_x"
        private const val KEY_Y = "overlay_y"
        private const val KEY_SOURCE_LANG = "source_language"
        private const val KEY_TENCENT_APP = "tencent_app_id"
        private const val KEY_TENCENT_ID = "tencent_secret_id"
        private const val KEY_TENCENT_KEY = "tencent_secret_key"
        private const val KEY_TENCENT_ENGINE = "tencent_engine"
        private const val KEY_TENCENT_MT_KEY = "tencent_mt_api_key"
        private const val KEY_TENCENT_MT_MODEL = "tencent_mt_model"
        private const val KEY_OPENAI_URL = "openai_base_url"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_OPENAI_KEY = "openai_api_key"

        internal fun storedVendor(stored: String?, legacy: String?): Provider {
            val raw = stored?.takeIf { it.isNotBlank() } ?: legacy
            return parseStoredVendor(raw)
        }

        fun open(context: Context): SettingsStore {
            val app = context.applicationContext
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            @Suppress("DEPRECATION")
            val prefs = EncryptedSharedPreferences.create(
                app,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return SettingsStore(prefs)
        }

        fun memoryOnly(): SettingsStore = SettingsStore(null)
    }
}
