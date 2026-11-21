# 浮译

在自己的手机上，把正在播放的视频、音乐或游戏声音听写成文字，并译成简体中文。字幕浮在其他应用上面，可以拖动。折叠时保留上一句和当前句，点字幕区域展开更早的句子。长句会换行；窗口过高时在字幕区域内滚动，最新一句留在底部。通知和快捷设置可以暂停或继续，暂停不会丢掉已经拿到的内录授权。

只翻译你自己正在播放、并且有权使用的内容。不要用它偷录别人的通话或聊天。应用不保存音频文件，也不上传画面。系统弹出的「正在捕获屏幕」是 Android 内录接口的正常提示。

本目录只包含主应用模块 `:app` 和把它编出来所需的 Gradle 文件。

## 识别和翻译可以分开选

两边各自校验自己的字段，可以混搭。旧版本只存了一个 `provider`。升级后如果还没有 `asr_provider` / `mt_provider`，识别和翻译都继续用那个旧值，已经保存的火山引擎 API Key 和阿里云配置不会被清空，也不用重填。

失败、超时、鉴权或配额问题会显示在主页和对应字幕行上。应用不会在失败后把音频或文本改发给另一家。

自定义地址只有在你填写并选中对应服务后才会收到数据，费用由那个地址所属账号承担。

手机里的长期密钥无法真正保密，只适合自己带着自己的钥匙使用。如果要做成对外的公共服务，应由你自己的后端签发短时令牌，不要把各家的长期 Secret 放进安装包。

## 服务与语言

目标语言始终是简体中文。来源语言由你选择。应用不会把你选的语言偷偷改成另一种。

| 能力 | 自动识别 | 锁定语言 | 说明 |
| --- | --- | --- | --- |
| 火山引擎识别 | 可以。走 `bigmodel_nostream`，`enable_auto_lang` | 中文、英语走低延迟 `bigmodel_async`。其余 23 个官方代码走句末接口并带 `language` | 不使用按分钟计费的「实时语音翻译」 |
| 火山引擎翻译 | 可以。不传 `source_language` | 有官方短代码才发送。孟加拉语、尼泊尔语没有短代码，会直接报错 | `target_language=zh` |
| 阿里云识别 | 可以。不传 `language_hints` | 仅 `zh` `en` `ja` `yue` `ko` `de` `fr` `ru`。其它锁定语言报错 | `paraformer-realtime-v2` |
| 阿里云翻译 | 可以。`SourceLanguage=auto` | 仅表内代码。粤语、菲律宾语、波兰语、荷兰语、乌克兰语、罗马尼亚语、希腊语、孟加拉语、尼泊尔语会报错 | `TranslateGeneral`，目标 `zh` |
| 腾讯云识别 | 不可以。不会改成普通话 | 留空引擎时按语言映射到官方 `engine_model_type`。没有对应引擎的语言报错。也可以自己填写文档中的引擎名 | 见下方引擎表 |
| 腾讯云混元翻译 | 可以。省略 `source` | 语言表里有代码才发送，含粤语、菲律宾语、孟加拉语。罗马尼亚语、希腊语、尼泊尔语报错 | 填写 TokenHub API Key 后使用 |
| 腾讯云旧文本翻译 | 不可以。文档没有 `auto` | 不能把粤语等未列出的语言译成简体中文 | 仅在不填 TokenHub Key、只填 SecretId / SecretKey 时使用 |
| OpenAI 兼容翻译 | 由你填写的模型处理，本应用不声称它支持自动识别 | 提示词会带上你锁定的来源语言 | 只发文字，不是流式识别 |

腾讯云识别在引擎留空时的映射：

| 来源语言 | 引擎 |
| --- | --- |
| 中文 | `16k_zh` |
| 英语 | `16k_en` |
| 日语 | `16k_ja` |
| 韩语 | `16k_ko` |
| 粤语 | `16k_yue` |
| 西班牙语 | `16k_es` |
| 法语 | `16k_fr` |
| 德语 | `16k_de` |
| 葡萄牙语 | `16k_pt` |
| 越南语 | `16k_vi` |
| 泰语 | `16k_th` |
| 印尼语 | `16k_id` |
| 马来语 | `16k_ms` |
| 菲律宾语 | `16k_fil` |
| 阿拉伯语 | `16k_ar` |
| 土耳其语 | `16k_tr` |

俄语、意大利语以及表外语言没有单独引擎。若你明确填写 `16k_zh_en`（中文、英语、粤语和多种方言）或 `16k_multi_lang`（15 个外语，不含中文）或 `Hy-ASR-3.0-preview`（中英和 20 种方言，预览，单次约 5 分钟，仅 16 kHz PCM），应用会按你填写的引擎连接，并在连接提示里写出引擎名。

## 火山引擎

文档：

- 实时识别：<https://docs.volcengine.com/docs/doubao-voice/bidirectional-streaming-automatic-speech-recognition-websocket>
- 协议字段：<https://docs.volcengine.com/docs/doubao-voice/large-model-streaming-automatic-speech-recognition-api-1>
- API Key：<https://docs.volcengine.com/docs/doubao-voice/api-key-usage>
- 豆包机器翻译：<https://docs.volcengine.com/docs/doubao-voice/machine-translation-large-model-api-access-documentation>

配置：

1. 开通「豆包流式语音识别模型 2.0」和「豆包机器翻译模型」。
2. 复制 API Key。识别和翻译可以共用；翻译要另一把钥匙时再填「翻译 API Key」。
3. 不要开通按分钟计费的「实时语音翻译」，也不需要 IAM 的 `TranslateText`。
4. 地址留空时：中文和英语用 `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`，其它来源语言用 `bigmodel_nostream`。Resource ID 留空则是 `volc.seedasr.sauc.duration`。请求里的 `model_name` 是 `bigmodel`。
5. 翻译地址固定为 `POST https://openspeech.bytedance.com/api/v3/machine_translation/matx_translate`，`X-Api-Resource-Id: volc.speech.mt`，`target_language=zh`。

旧版控制台仍可填 App ID 和 Access Token。自定义 WebSocket 必须以 `wss://` 开头，只有保存后才会收到音频。

## 阿里云

文档：

- 实时识别：<https://help.aliyun.com/zh/model-studio/websocket-for-paraformer-real-time-service>
- 文本翻译：<https://help.aliyun.com/zh/machine-translation/developer-reference/api-alimt-2018-10-12-translategeneral>

配置：

1. 百炼 API Key 用于识别，请求头 `Authorization: Bearer`。模型 `paraformer-realtime-v2`。
2. 业务空间可空。填写后地址变为 `wss://{空间}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference`。
3. 翻译使用 RAM AccessKey，不是百炼 Key。`POST https://mt.cn-hangzhou.aliyuncs.com/`，`TranslateGeneral`，版本 `2018-10-12`，目标 `zh`。

## 腾讯云

识别文档（2026-10-21 更新的实时语音识别 WebSocket）：<https://cloud.tencent.com/document/product/1093/48982>

1. 账号中心查看数字 APPID。API 密钥管理创建 SecretId 和 SecretKey。
2. 地址形如 `wss://asr.cloud.tencent.com/asr/v2/{APPID}`。除 `signature` 外的参数按字典序拼进签名原文，HMAC-SHA1 后 Base64，再对签名做 URL 编码。
3. 音频是 16 kHz、16 bit、单声道 PCM，`voice_format=1`。结束时发送文本 `{"type":"end"}`。
4. 结果里 `slice_type` 为 0 或 1 时当作未定稿，为 2 时当作一句结束。`code` 非 0 时显示服务端 `message`。

翻译，二选一，不会两路同时发送：

- 当前混元翻译：<https://cloud.tencent.com/document/product/1823/132252> 。填写 TokenHub API Key 后，文本发到 `https://tokenhub.tencentmaas.com/v1/api/translations`，`target=zh`。模型留空则是 `hy-mt2-plus`，也可以填 `hy-mt2-pro` 或 `hy-mt2-lite`。自动识别不传 `source`。
- 旧文本翻译：<https://cloud.tencent.com/document/api/551/15619> ，签名 <https://cloud.tencent.com/document/api/551/30636> 。`POST https://tmt.tencentcloudapi.com`，Action `TextTranslate`，Version `2018-03-21`，地域 `ap-beijing`。产品概述写明该接口将下线，且不再支持新用户：<https://cloud.tencent.com/document/product/551/7372> 。

腾讯云实时音视频的另一套地址 `wss://asr.cloud-rtc.com` 没有接入。

## OpenAI 兼容翻译

只实现 chat completions，不是语音识别。选中后才会把待译文本发到你填写的地址：

- 以 `https://` 开头。
- 已经以 `/chat/completions` 结尾则原样使用；以 `/v1` 结尾则补上 `/chat/completions`；否则补上 `/v1/chat/completions`。
- 请求头 `Authorization: Bearer`，正文含 `model`、`temperature=0` 和两条 message。译文取 `choices[0].message.content`。
- 不表示任意服务商都兼容这段协议。腾讯云 TokenHub 的对话式调用可以用基址 `https://tokenhub.tencentmaas.com/v1`。

## 数据经过哪里

1. 系统内录只采集媒体、游戏、未知、提示音和无障碍这些播放用途，不采集通话用途。音频在本机重采样为 16 kHz PCM。
2. PCM 只发给当前选中的识别服务。火山引擎的自定义 `wss://`、阿里云的业务空间域名，都只在你填写后使用。
3. 原文马上显示。短暂停顿后把文本发给当前选中的翻译服务。OpenAI 兼容和混元翻译都不接收音频。
4. 密钥写在本机 `EncryptedSharedPreferences`（文件名 `fanyi_secrets`）。日志、界面错误和本仓库示例里不放真实密钥。
5. 各家按自己的价目向你的账号计费。本应用不代付，也不查询余额。

## 自己加一个云服务商

主应用的适配器是普通 Kotlin 类型，没有单独的插件接口。

1. 在 `Provider` 里增加一个值，并在 `parseStoredVendor` / `vendorWire` 里接上稳定的存储字符串。
2. 识别：实现 `StreamingAsr`（`start`、`sendPcm`、`finish`），通过 `AsrEvents` 交回原文。在 `CaptionPipeline` 里按 `asrProvider` 创建它。
3. 翻译：实现 `TextTranslator.translate`，在 `textTranslator()` 里创建。取消必须走 `Net.http.executeCancellable`，这样上一句还在飞时可以被丢掉。
4. 在 `VendorSupport` 里写清缺哪些字段、哪些来源语言不能用。不能用时返回中文说明，不要改写用户选的语言，也不要在失败时调用另一家。
5. 设置页增加对应输入框。密钥字段沿用现有的加密存储，不要写进日志。
6. 用固定的签名向量或一段假 JSON 做单元测试。没有真实账号就不要发网络请求。

## 编译

需要 JDK 17 或能把 Kotlin 编到 JVM 17 的 JDK 21、Android SDK Platform 36、Build-Tools 36.0.0。`minSdk` 29，`targetSdk` 和 `compileSdk` 36，`applicationId` 是 `com.fanyi.overlay`。

```bash
export ANDROID_HOME="$HOME/android-sdk"
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

调试包在 `app/build/outputs/apk/debug/app-debug.apk`。`local.properties` 不要提交。

## 测试局限

这次交付时，`:app:testDebugUnitTest` 和 `:app:assembleDebug` 已通过。测试覆盖腾讯云识别 URL 签名、TextTranslate 的 TC3 签名、旧 `provider` 迁移、语言不被改写，以及混元翻译请求体和 chat completions 的解析。

没有火山引擎、阿里云或腾讯云的真实账号，因此没有做实账号端到端验证。不能把「编译和离线测试通过」说成「各家都已经实测可用」。

## 依赖和许可证

本压缩包故意不附带 `LICENSE`。项目许可证和版权人需要你自己选定，这里不代你选择，也不填写版权人。

依赖本身的许可证，供你挑选项目许可证时对照：

| 依赖 | 许可证 |
| --- | --- |
| Kotlin 2.2.21、kotlinx-coroutines 1.9.0、kotlinx-serialization 1.9.0 | Apache-2.0 |
| OkHttp 4.12.0 | Apache-2.0 |
| AndroidX Core、Activity、Lifecycle、Navigation、SavedState、Security Crypto | Apache-2.0 |
| Jetpack Compose BOM 2026.02.01、Material 3 | Apache-2.0 |
| Android Gradle Plugin 8.13.2 | Apache-2.0 |
| JUnit 4.13.2（仅测试） | EPL-2.0 |

Android SDK 的使用还要遵守你安装 SDK 时接受的 Android Software Development Kit License。
