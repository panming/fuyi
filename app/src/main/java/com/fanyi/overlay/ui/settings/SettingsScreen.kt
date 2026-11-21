package com.fanyi.overlay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fanyi.overlay.FanyiApp
import com.fanyi.overlay.model.AppSettings
import com.fanyi.overlay.model.Endpoints
import com.fanyi.overlay.model.Provider
import com.fanyi.overlay.model.SourceLanguages
import com.fanyi.overlay.model.asrBlockMessage
import com.fanyi.overlay.model.mtBlockMessage
import com.fanyi.overlay.translate.friendlyFailure
import com.fanyi.overlay.translate.textTranslator
import com.fanyi.overlay.ui.theme.Amber
import com.fanyi.overlay.ui.theme.Celadon
import com.fanyi.overlay.ui.theme.Danger
import com.fanyi.overlay.ui.theme.Mist
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FanyiApp
    val saved by app.settings.settings.collectAsState()
    var draft by remember(saved) { mutableStateOf(saved) }
    var reveal by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var languageMenu by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val mask = if (reveal) VisualTransformation.None else PasswordVisualTransformation()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("设置", style = MaterialTheme.typography.headlineMedium, color = Amber)
        Text(
            "密钥只保存在本机加密配置里，不会写进安装包或日志。手机上的长期密钥并不能真正保密，适合自己使用；对外提供服务时应改由你自己的后端签发短时令牌。",
            color = Mist.copy(alpha = 0.75f),
        )
        Text("识别服务商", style = MaterialTheme.typography.titleMedium)
        VendorChips(
            selected = draft.asrProvider,
            includeOpenAi = false,
            onSelect = { draft = draft.copy(asrProvider = it) },
        )
        AsrFields(draft, mask, advanced) { draft = it }
        if (draft.asrProvider == Provider.VOLCENGINE) {
            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "收起高级项" else "旧版密钥和接口地址")
            }
        }
        asrBlockMessage(draft)?.let { Text(it, color = Danger) }
        Text("翻译服务商", style = MaterialTheme.typography.titleMedium)
        Text(
            "可以和识别不是同一家。只有你选中的翻译服务会收到文本；不会在失败后偷偷改发给另一家。OpenAI 兼容只发文字，不发音频，而且只保证 chat completions 这一段协议。",
            color = Mist.copy(alpha = 0.75f),
        )
        VendorChips(
            selected = draft.mtProvider,
            includeOpenAi = true,
            onSelect = { draft = draft.copy(mtProvider = it) },
        )
        MtFields(draft, mask) { draft = it }
        mtBlockMessage(draft)?.let { Text(it, color = Danger) }
        Text("来源语言", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { languageMenu = true }, modifier = Modifier.fillMaxWidth()) {
            Text(SourceLanguages.labelOf(draft.sourceLanguage))
        }
        Text(languageHint(draft), color = Mist.copy(alpha = 0.75f))
        if (languageMenu) {
            AlertDialog(
                onDismissRequest = { languageMenu = false },
                confirmButton = {
                    TextButton(onClick = { languageMenu = false }) { Text("关闭") }
                },
                title = { Text("来源语言") },
                text = {
                    Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        SourceLanguages.all.forEach { language ->
                            TextButton(
                                onClick = {
                                    draft = draft.copy(sourceLanguage = language.code)
                                    languageMenu = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (draft.sourceLanguage == language.code) "${language.label}  ·  当前" else language.label,
                                    color = if (draft.sourceLanguage == language.code) Celadon else Mist,
                                )
                            }
                        }
                    }
                },
            )
        }
        TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "隐藏密钥" else "显示密钥") }
        Text("悬浮层不透明度 ${"%.0f".format(draft.overlayAlpha * 100)}%")
        Slider(
            value = draft.overlayAlpha,
            onValueChange = { draft = draft.copy(overlayAlpha = it) },
            valueRange = 0.45f..1f,
        )
        Text("字号 ${draft.overlayTextSp.toInt()} sp")
        Slider(
            value = draft.overlayTextSp,
            onValueChange = { draft = draft.copy(overlayTextSp = it) },
            valueRange = 12f..22f,
        )
        Button(
            onClick = { app.settings.update { draft } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存") }
        OutlinedButton(
            onClick = {
                app.settings.update { draft }
                testing = true
                testMessage = "正在翻译 Hello …"
                scope.launch {
                    testMessage = try {
                        val translator = textTranslator(draft)
                            ?: error(mtBlockMessage(draft) ?: "翻译还没配置好")
                        val result = translator.translate("Hello")
                        "翻译成功：${result.translated}"
                    } catch (error: Throwable) {
                        friendlyFailure(error)
                    }
                    testing = false
                }
            },
            enabled = !testing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (testing) "测试中" else "测试翻译一句 Hello") }
        testMessage?.let {
            Text(it, color = if (it.startsWith("翻译成功")) Celadon else Danger)
        }
    }
}

@Composable
private fun VendorChips(
    selected: Provider,
    includeOpenAi: Boolean,
    onSelect: (Provider) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == Provider.VOLCENGINE,
            onClick = { onSelect(Provider.VOLCENGINE) },
            label = { Text("火山") },
        )
        FilterChip(
            selected = selected == Provider.ALIYUN,
            onClick = { onSelect(Provider.ALIYUN) },
            label = { Text("阿里") },
        )
        FilterChip(
            selected = selected == Provider.TENCENT,
            onClick = { onSelect(Provider.TENCENT) },
            label = { Text("腾讯") },
        )
    }
    if (includeOpenAi) {
        FilterChip(
            selected = selected == Provider.OPENAI,
            onClick = { onSelect(Provider.OPENAI) },
            label = { Text("OpenAI 兼容") },
        )
    }
}

@Composable
private fun AsrFields(
    draft: AppSettings,
    mask: VisualTransformation,
    advanced: Boolean,
    onChange: (AppSettings) -> Unit,
) {
    when (draft.asrProvider) {
        Provider.VOLCENGINE -> {
            Text("豆包流式语音识别 2.0。中文和英语走低延迟地址，自动识别和其它锁定语种走句末地址。")
            SecretField("豆包语音 API Key", draft.volcApiKey, mask) { onChange(draft.copy(volcApiKey = it)) }
            if (advanced) {
                SecretField("旧版 App ID（可空）", draft.volcAppId, mask) { onChange(draft.copy(volcAppId = it)) }
                SecretField("旧版 Access Token（可空）", draft.volcAccessToken, mask) {
                    onChange(draft.copy(volcAccessToken = it))
                }
                SecretField("Resource ID（可空）", draft.volcResourceId, VisualTransformation.None) {
                    onChange(draft.copy(volcResourceId = it))
                }
                SecretField("WebSocket 地址（可空，wss://）", draft.volcEndpoint, VisualTransformation.None) {
                    onChange(draft.copy(volcEndpoint = it))
                }
                Text(
                    "当前会连接 ${Endpoints.volcAsrUrl(draft)} ，资源 ${Endpoints.volcResource(draft)}。自定义地址只有在你填写并保存后才会收到音频，费用由该地址所属账号承担。",
                    color = Celadon,
                )
            }
        }
        Provider.ALIYUN -> {
            Text("paraformer-realtime-v2。业务空间可空。锁定语种只在官方 language_hints 里有对应项时才会发送。")
            SecretField("DashScope API Key", draft.aliApiKey, mask) { onChange(draft.copy(aliApiKey = it)) }
            SecretField("业务空间 ID（可空）", draft.aliWorkspace, VisualTransformation.None) {
                onChange(draft.copy(aliWorkspace = it))
            }
        }
        Provider.TENCENT -> {
            Text("实时语音识别 WebSocket。需要 APPID、SecretId、SecretKey。引擎留空时按锁定语言选择官方 engine_model_type；自动识别不会被改成普通话。")
            SecretField("APPID", draft.tencentAppId, VisualTransformation.None) { onChange(draft.copy(tencentAppId = it)) }
            SecretField("SecretId", draft.tencentSecretId, mask) { onChange(draft.copy(tencentSecretId = it)) }
            SecretField("SecretKey", draft.tencentSecretKey, mask) { onChange(draft.copy(tencentSecretKey = it)) }
            SecretField("引擎（可空，如 16k_zh_en）", draft.tencentEngine, VisualTransformation.None) {
                onChange(draft.copy(tencentEngine = it))
            }
        }
        Provider.OPENAI -> Text("兼容接口不能做流式识别。")
    }
}

@Composable
private fun MtFields(
    draft: AppSettings,
    mask: VisualTransformation,
    onChange: (AppSettings) -> Unit,
) {
    when (draft.mtProvider) {
        Provider.VOLCENGINE -> {
            Text("豆包机器翻译，目标简体中文。翻译 Key 留空就用识别那把 API Key。")
            SecretField("豆包语音 API Key", draft.volcApiKey, mask) { onChange(draft.copy(volcApiKey = it)) }
            SecretField("翻译 API Key（可空）", draft.volcMtApiKey, mask) { onChange(draft.copy(volcMtApiKey = it)) }
        }
        Provider.ALIYUN -> {
            Text("TranslateGeneral，杭州节点，目标 zh。这是 RAM AccessKey，不是百炼 API Key。")
            SecretField("AccessKey ID", draft.aliAk, mask) { onChange(draft.copy(aliAk = it)) }
            SecretField("AccessKey Secret", draft.aliSk, mask) { onChange(draft.copy(aliSk = it)) }
        }
        Provider.TENCENT -> {
            Text("优先使用混元翻译 TokenHub：填写 API Key 后，文本只发到 tokenhub.tencentmaas.com 的官方 translations 接口，目标简体中文，自动识别会省略 source。不填这把 Key 时才用旧的 TextTranslate（SecretId / SecretKey）。产品说明写明旧接口将下线，且不再向新用户开放。")
            SecretField("TokenHub API Key（推荐）", draft.tencentMtApiKey, mask) {
                onChange(draft.copy(tencentMtApiKey = it))
            }
            SecretField("模型（可空，默认 hy-mt2-plus）", draft.tencentMtModel, VisualTransformation.None) {
                onChange(draft.copy(tencentMtModel = it))
            }
            SecretField("旧接口 SecretId", draft.tencentSecretId, mask) { onChange(draft.copy(tencentSecretId = it)) }
            SecretField("旧接口 SecretKey", draft.tencentSecretKey, mask) { onChange(draft.copy(tencentSecretKey = it)) }
        }
        Provider.OPENAI -> {
            Text("只向你填写的 https 地址发送待译文本，走 chat completions。费用和数据都落在那个地址，不发送音频。")
            SecretField("地址（https://）", draft.openaiBaseUrl, VisualTransformation.None) {
                onChange(draft.copy(openaiBaseUrl = it))
            }
            SecretField("模型名", draft.openaiModel, VisualTransformation.None) { onChange(draft.copy(openaiModel = it)) }
            SecretField("API Key", draft.openaiApiKey, mask) { onChange(draft.copy(openaiApiKey = it)) }
        }
    }
}

private fun languageHint(draft: AppSettings): String = when (draft.asrProvider) {
    Provider.VOLCENGINE ->
        "火山引擎：自动识别和中英以外的锁定语种等一句说完再出字；指定中文或英语仍走低延迟。目标始终是简体中文。"
    Provider.ALIYUN ->
        "阿里云：自动识别不传 language_hints。锁定语种若不在 zh、en、ja、yue、ko、de、fr、ru 中会直接报错，不会改成中文。"
    Provider.TENCENT ->
        "腾讯云：请锁定语言，或填写官方引擎。16k_multi_lang 不含中文。Hy-ASR-3.0-preview 单次大约 5 分钟。翻译侧不能把「自动识别」改成某个源语言。"
    Provider.OPENAI -> "请把识别改成火山、阿里或腾讯。兼容接口只翻译文字。"
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    visual: VisualTransformation,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = visual,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
