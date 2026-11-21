package com.fanyi.overlay

import com.fanyi.overlay.asr.SaucProtocol
import com.fanyi.overlay.asr.TencentAsrAuth
import com.fanyi.overlay.data.SettingsStore
import com.fanyi.overlay.model.AppSettings
import com.fanyi.overlay.model.CaptionLine
import com.fanyi.overlay.model.Provider
import com.fanyi.overlay.model.asrBlockMessage
import com.fanyi.overlay.model.mtBlockMessage
import com.fanyi.overlay.model.tencentEngineFor
import com.fanyi.overlay.translate.TencentTc3
import com.fanyi.overlay.translate.chatCompletionText
import com.fanyi.overlay.translate.openaiChatUrl
import com.fanyi.overlay.translate.tokenHubBody
import com.fanyi.overlay.model.LinePhase
import com.fanyi.overlay.model.PartialCaption
import com.fanyi.overlay.model.SourceLanguages
import com.fanyi.overlay.overlay.collapsedPairs
import com.fanyi.overlay.overlay.overlayPairs
import com.fanyi.overlay.audio.PcmResampler
import com.fanyi.overlay.translate.AliyunSigner
import com.fanyi.overlay.translate.VolcengineSigner
import com.fanyi.overlay.translate.clauseDebounceMs
import com.fanyi.overlay.translate.doubaoMtSourceLanguage
import com.fanyi.overlay.translate.grewEnough
import com.fanyi.overlay.translate.shouldSkipTranslation
import com.fanyi.overlay.translate.worthTranslating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CoreLogicTest {
    @Test
    fun chineseSpeechSkipsTranslation() {
        assertTrue(shouldSkipTranslation("speech_mand", "hello"))
        assertTrue(shouldSkipTranslation("speech_dia_cant", "你好"))
        assertTrue(shouldSkipTranslation(null, "今天天气不错"))
        assertFalse(shouldSkipTranslation("speech_en", "你好"))
        assertFalse(shouldSkipTranslation(null, "Hello there"))
        assertFalse(shouldSkipTranslation(null, "こんにちは"))
        assertFalse(shouldSkipTranslation("speech_ja", "東京"))
        assertEquals("en", doubaoMtSourceLanguage("speech_en"))
        assertEquals("ja", doubaoMtSourceLanguage("speech_ja"))
        assertEquals("zh", doubaoMtSourceLanguage("speech_mand"))
        assertEquals(null, doubaoMtSourceLanguage("other_langs"))
        assertEquals(null, doubaoMtSourceLanguage(null))
        assertFalse(worthTranslating("Hi"))
        assertTrue(worthTranslating("你好"))
        assertTrue(worthTranslating("Hello there"))
        assertEquals(180L, clauseDebounceMs("Hello,"))
        assertEquals(320L, clauseDebounceMs("Hello"))
        assertTrue(grewEnough("hello", "hello!"))
        assertTrue(grewEnough("hello", "hello world"))
        assertTrue(grewEnough("hello", "hello."))
        assertFalse(grewEnough("hello", "hello"))
        assertTrue(SourceLanguages.prefersLowLatency("zh-CN"))
        assertTrue(SourceLanguages.prefersLowLatency("en-US"))
        assertFalse(SourceLanguages.prefersLowLatency("auto"))
        assertFalse(SourceLanguages.prefersLowLatency("ja-JP"))
        assertEquals("ja", SourceLanguages.mtCode("ja-JP"))
        assertEquals(null, SourceLanguages.mtCode("auto"))
    }

    @Test
    fun overlayKeepsPreviousPairAboveTheLivePair() {
        val lines = listOf(
            CaptionLine(1, "one", "一", LinePhase.DONE),
            CaptionLine(2, "two", "二", LinePhase.DONE),
        )
        val partial = PartialCaption("three", translation = "三")
        val pairs = overlayPairs(lines, partial)
        assertEquals(listOf("one", "two", "three"), pairs.map { it.original })
        val collapsed = collapsedPairs(pairs)
        assertEquals(listOf("two", "three"), collapsed.map { it.original })
        assertEquals(listOf("二", "三"), collapsed.map { it.translation })
        assertEquals(4, collapsed.size * 2)
    }

    @Test
    fun resamples48kWithBoxFilter() {
        val resampler = PcmResampler(inputRate = 48_000, channels = 1)
        val input = shortArrayOf(0, 0, 0, 300, 300, 300)
        val pcm = resampler.resample(input, input.size)
        assertEquals(4, pcm.size)
        assertEquals(0, readLe(pcm, 0))
        assertEquals(300, readLe(pcm, 2))
    }

    @Test
    fun keeps16kMono() {
        val resampler = PcmResampler(inputRate = 16_000, channels = 1)
        val pcm = resampler.resample(shortArrayOf(1, 2, -1), 3)
        assertEquals(1, readLe(pcm, 0))
        assertEquals(2, readLe(pcm, 2))
        assertEquals(-1, readLe(pcm, 4))
    }

    @Test
    fun downmixesStereo() {
        val resampler = PcmResampler(inputRate = 16_000, channels = 2)
        val pcm = resampler.resample(shortArrayOf(10, 30), 2)
        assertEquals(20, readLe(pcm, 0))
    }

    @Test
    fun saucFrameRoundTrip() {
        val json = """{"request":{"model_name":"bigmodel"}}""".toByteArray()
        val frame = SaucProtocol.fullClient(json, sequence = 1)
        assertEquals(0x11, frame[0].toInt() and 0xFF)
        assertEquals(0x11, frame[1].toInt() and 0xFF)
        assertEquals(0x11, frame[2].toInt() and 0xFF)
        val parsed = SaucProtocol.parse(frame)
        assertEquals(json.decodeToString(), parsed.payload.decodeToString())
        val last = SaucProtocol.audio(ByteArray(0), sequence = 3, last = true)
        val audio = SaucProtocol.parse(last)
        assertTrue(audio.isFinalPacket)
        assertEquals(-3, audio.sequence)
    }

    @Test
    fun volcengineSignatureMatchesReference() {
        val body = """{"TargetLanguage":"zh","TextList":["Hello"]}""".toByteArray()
        val signed = VolcengineSigner.signTranslate(
            accessKeyId = "AKIDEXAMPLE",
            secret = "secret",
            body = body,
            now = Instant.parse("2026-11-19T03:00:00Z"),
        )
        assertEquals("https://translate.volcengineapi.com/?Action=TranslateText&Version=2020-06-01", signed.url)
        assertEquals(
            "HMAC-SHA256 Credential=AKIDEXAMPLE/20261119/cn-north-1/translate/request, SignedHeaders=content-type;host;x-content-sha256;x-date, Signature=ddf66cfa03ce425837bc909aa087a4628d068f9bd8299f7a9ec20a048cb7c627",
            signed.headers.getValue("Authorization"),
        )
    }

    @Test
    fun aliyunPercentEncodingUsesRfc3986() {
        assertEquals("a%20b", AliyunSigner.percent("a b"))
        assertEquals("~", AliyunSigner.percent("~"))
        assertEquals("%2A", AliyunSigner.percent("*"))
        val form = AliyunSigner.translateForm(
            accessKeyId = "ak",
            accessKeySecret = "sk",
            text = "Hello",
            now = Instant.parse("2026-11-19T03:00:00Z"),
            nonce = "nonce-1",
        )
        assertTrue(form.contains("Action=TranslateGeneral"))
        assertTrue(form.contains("SourceLanguage=auto"))
        assertTrue(form.contains("TargetLanguage=zh"))
        assertTrue(form.contains("Signature="))
        assertTrue(form.contains("Hello"))
    }

    @Test
    fun tencentAsrSignatureMatchesReference() {
        val url = TencentAsrAuth.signedUrl(
            appId = "123456",
            secretId = "AKIDexample",
            secretKey = "secretkey",
            engine = "16k_zh",
            voiceId = "voice-1",
            timestampSeconds = 1_600_000_000,
            nonce = 7,
            expiredSeconds = 1_700_000_000,
        )
        assertEquals(
            "wss://asr.cloud.tencent.com/asr/v2/123456?engine_model_type=16k_zh&expired=1700000000&needvad=1&nonce=7&secretid=AKIDexample&timestamp=1600000000&vad_silence_time=500&voice_format=1&voice_id=voice-1&signature=Fkal9pYlkImnnKnlpgC728bCLcY%3D",
            url,
        )
    }

    @Test
    fun tencentTc3AuthorizationMatchesReference() {
        val body = """{"ProjectId":0,"Source":"en","SourceText":"Hello","Target":"zh"}""".toByteArray()
        val authorization = TencentTc3.authorization(
            secretId = "AKIDexample",
            secretKey = "secretkey",
            host = "tmt.tencentcloudapi.com",
            service = "tmt",
            action = "TextTranslate",
            timestampSeconds = 1_600_000_000,
            payload = body,
        )
        assertEquals(
            "TC3-HMAC-SHA256 Credential=AKIDexample/2020-09-13/tmt/tc3_request, SignedHeaders=content-type;host;x-tc-action, Signature=f543553c4c2da6e33050e828642c68adecd8dc3f3ad8c957706083530e0b4990",
            authorization,
        )
    }

    @Test
    fun legacyProviderMigratesOntoBothVendors() {
        assertEquals(Provider.ALIYUN, SettingsStore.storedVendor(null, "aliyun"))
        assertEquals(Provider.VOLCENGINE, SettingsStore.storedVendor(null, "volcengine"))
        assertEquals(Provider.TENCENT, SettingsStore.storedVendor("tencent", "aliyun"))
        assertEquals(Provider.OPENAI, SettingsStore.storedVendor("openai", null))
    }

    @Test
    fun languageChoiceIsNotSilentlyRewritten() {
        val tencent = AppSettings(
            asrProvider = Provider.TENCENT,
            mtProvider = Provider.TENCENT,
            tencentAppId = "12345678",
            tencentSecretId = "id",
            tencentSecretKey = "key",
            sourceLanguage = SourceLanguages.AUTO,
        )
        assertTrue(asrBlockMessage(tencent)!!.contains("不会把"))
        assertTrue(mtBlockMessage(tencent)!!.contains("不会擅自"))
        assertEquals("16k_ja", tencentEngineFor(tencent.copy(sourceLanguage = "ja-JP")).first)
        assertTrue(asrBlockMessage(tencent.copy(sourceLanguage = "ru-RU"))!!.contains("没有与"))
        assertTrue(mtBlockMessage(tencent.copy(sourceLanguage = "yue-CN"))!!.contains("不能把"))
        val aliyun = AppSettings(
            asrProvider = Provider.ALIYUN,
            aliApiKey = "dashscope",
            sourceLanguage = "es-MX",
        )
        assertTrue(asrBlockMessage(aliyun)!!.contains("language_hints"))
        val volc = AppSettings(
            mtProvider = Provider.VOLCENGINE,
            volcApiKey = "key",
            sourceLanguage = "bn-BD",
        )
        assertTrue(mtBlockMessage(volc)!!.contains("不会改成"))
        assertEquals(
            "https://example.com/v1/chat/completions",
            openaiChatUrl("https://example.com/v1/"),
        )
        assertEquals(
            "https://example.com/chat/completions",
            openaiChatUrl("https://example.com/chat/completions"),
        )
        val hub = tencent.copy(tencentMtApiKey = "hub-key")
        assertEquals(null, mtBlockMessage(hub))
        assertTrue(mtBlockMessage(hub.copy(sourceLanguage = "el-GR"))!!.contains("混元"))
        assertEquals(null, mtBlockMessage(hub.copy(sourceLanguage = "yue-CN")))
        val autoBody = tokenHubBody("Hello", null, "")
        assertTrue(autoBody.contains("\"target\":\"zh\""))
        assertFalse(autoBody.contains("source"))
        assertTrue(tokenHubBody("Hello", "en", "hy-mt2-pro").contains("\"model\":\"hy-mt2-pro\""))
        assertEquals("你好", chatCompletionText("""{"choices":[{"message":{"content":"你好"}}]}"""))
    }

    private fun readLe(pcm: ByteArray, offset: Int): Int {
        val value = (pcm[offset].toInt() and 0xFF) or (pcm[offset + 1].toInt() shl 8)
        return value.toShort().toInt()
    }
}
