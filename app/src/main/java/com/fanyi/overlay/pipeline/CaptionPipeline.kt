package com.fanyi.overlay.pipeline

import com.fanyi.overlay.asr.AliyunAsrClient
import com.fanyi.overlay.asr.AsrEvents
import com.fanyi.overlay.asr.StreamingAsr
import com.fanyi.overlay.asr.TencentAsrClient
import com.fanyi.overlay.asr.VolcengineAsrClient
import com.fanyi.overlay.data.SessionBus
import com.fanyi.overlay.model.AppSettings
import com.fanyi.overlay.model.CaptionLine
import com.fanyi.overlay.model.LinePhase
import com.fanyi.overlay.model.PartialCaption
import com.fanyi.overlay.model.Provider
import com.fanyi.overlay.model.RunPhase
import com.fanyi.overlay.service.CaptureService
import com.fanyi.overlay.translate.TextTranslator
import com.fanyi.overlay.translate.clauseDebounceMs
import com.fanyi.overlay.model.SourceLanguages
import com.fanyi.overlay.model.asrBlockMessage
import com.fanyi.overlay.model.mtBlockMessage
import com.fanyi.overlay.translate.doubaoMtSourceLanguage
import com.fanyi.overlay.translate.friendlyFailure
import com.fanyi.overlay.translate.grewEnough
import com.fanyi.overlay.translate.shouldSkipTranslation
import com.fanyi.overlay.translate.textTranslator
import com.fanyi.overlay.translate.worthTranslating
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Original text is published immediately. A partial is translated after a short
 * debounce, and a newer partial for the same phrase supersedes the older call.
 * Final ASR text replaces that provisional Chinese without a "正在翻译" gap.
 */
internal class CaptionPipeline(
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) : AsrEvents {
    private val pipelineScope = scope + SupervisorJob(scope.coroutineContext[Job])
    private val translator: TextTranslator? = textTranslator(settings)
    private val ids = AtomicLong()
    private val segment = AtomicInteger(0)
    private var debounceJob: Job? = null
    private var inflight: Job? = null
    private var inflightText: String = ""
    private var followUp: String? = null
    private var deliverLine: Long? = null
    private var lastSource: String = ""
    private var lastChinese: String? = null

    fun close() {
        debounceJob?.cancel()
        inflight?.cancel()
        (pipelineScope.coroutineContext[Job])?.cancel()
    }

    val asr: StreamingAsr = when (settings.asrProvider) {
        Provider.VOLCENGINE -> VolcengineAsrClient(settings, this)
        Provider.ALIYUN -> AliyunAsrClient(settings, this)
        Provider.TENCENT -> TencentAsrClient(settings, this)
        Provider.OPENAI -> object : StreamingAsr {
            override suspend fun start() {
                error(asrBlockMessage(settings) ?: "OpenAI 兼容接口不能做流式识别")
            }
            override suspend fun sendPcm(frame: ByteArray) = Unit
            override suspend fun finish() = Unit
        }
    }

    override fun onReady(detail: String) {
        SessionBus.detailValue = detail
    }

    override fun onPartial(text: String, language: String?) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return
        if (treatAsChinese(language, spoken)) {
            debounceJob?.cancel()
            SessionBus.partialValue = PartialCaption(spoken, language, spoken)
            return
        }
        SessionBus.partialValue = PartialCaption(spoken, language, cachedChinese(spoken))
        if (!worthTranslating(spoken)) return
        if (spoken == lastSource && lastChinese != null) return
        debounceJob?.cancel()
        val wait = clauseDebounceMs(spoken)
        val ticket = segment.get()
        debounceJob = pipelineScope.launch {
            delay(wait)
            if (segment.get() != ticket) return@launch
            if (SessionBus.partialValue?.text != spoken) return@launch
            dispatchPartial(spoken, language, ticket)
        }
    }

    override fun onFinal(text: String, language: String?) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return
        debounceJob?.cancel()
        segment.incrementAndGet()
        deliverLine = null
        SessionBus.partialValue = null
        val id = ids.incrementAndGet()
        if (treatAsChinese(language, spoken)) {
            inflight?.cancel()
            followUp = null
            lastSource = spoken
            lastChinese = spoken
            SessionBus.appendLine(CaptionLine(id, spoken, spoken, LinePhase.SKIPPED, language))
            return
        }
        val cached = cachedChinese(spoken)
        if (spoken == lastSource && cached != null) {
            if (inflightText != spoken) inflight?.cancel()
            SessionBus.appendLine(CaptionLine(id, spoken, cached, LinePhase.DONE, language))
            return
        }
        SessionBus.appendLine(
            CaptionLine(
                id = id,
                original = spoken,
                translation = cached,
                phase = if (cached != null) LinePhase.DONE else LinePhase.FINAL,
                language = language,
            ),
        )
        if (inflight?.isActive == true && inflightText == spoken) {
            deliverLine = id
            return
        }
        inflight?.cancel()
        followUp = null
        deliverLine = null
        startRequest(spoken, language, lineId = id)
    }

    override fun onError(message: String) {
        SessionBus.errorValue = message
        SessionBus.phaseValue = RunPhase.ERROR
        SessionBus.detailValue = message
        CaptureService.requestStop()
    }

    private fun dispatchPartial(spoken: String, language: String?, ticket: Int) {
        if (segment.get() != ticket || spoken == lastSource) return
        if (!grewEnough(lastSource, spoken) && lastSource.isNotEmpty()) return
        val running = inflight
        if (running?.isActive == true) {
            if (spoken.startsWith(inflightText)) {
                if (grewEnough(inflightText, spoken)) followUp = spoken
                return
            }
            running.cancel()
            followUp = null
        }
        startRequest(spoken, language, lineId = null)
    }

    private fun startRequest(spoken: String, language: String?, lineId: Long?) {
        val blocked = mtBlockMessage(settings)
        val engine = translator
        if (blocked != null || engine == null) {
            val hint = blocked ?: "当前翻译服务还缺密钥，这一句只保留原文。"
            SessionBus.detailValue = hint
            if (lineId != null) {
                SessionBus.updateLine(lineId) { it.copy(phase = LinePhase.FAILED, error = hint) }
            }
            return
        }
        val ticket = segment.get()
        inflightText = spoken
        inflight = pipelineScope.launch {
            try {
                val result = engine.translate(spoken, sourceForMt(language))
                ensureActive()
                val chinese = if (shouldSkipTranslation(result.detectedSource, spoken)) {
                    spoken
                } else {
                    result.translated
                }
                lastSource = spoken
                lastChinese = chinese
                val targetLine = lineId ?: deliverLine?.takeIf { inflightText == spoken }
                if (targetLine != null) {
                    deliverLine = null
                    val skipped = chinese == spoken && shouldSkipTranslation(result.detectedSource ?: language, spoken)
                    SessionBus.updateLine(targetLine) {
                        it.copy(
                            translation = chinese,
                            phase = if (skipped) LinePhase.SKIPPED else LinePhase.DONE,
                            language = result.detectedSource ?: it.language,
                        )
                    }
                }
                if (lineId == null && segment.get() == ticket) {
                    val now = SessionBus.partialValue
                    if (now != null && (now.text == spoken || now.text.startsWith(spoken))) {
                        SessionBus.partialValue = now.copy(
                            translation = chinese,
                            language = result.detectedSource ?: now.language,
                        )
                    }
                    val next = followUp
                    followUp = null
                    if (next != null && next != spoken && SessionBus.partialValue?.text == next) {
                        dispatchPartial(next, SessionBus.partialValue?.language, ticket)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (lineId != null) {
                    val existing = SessionBus.lines.value.firstOrNull { it.id == lineId }?.translation
                    if (existing.isNullOrBlank()) {
                        SessionBus.updateLine(lineId) {
                            it.copy(phase = LinePhase.FAILED, error = friendlyFailure(error))
                        }
                    }
                }
            }
        }
    }

    private fun treatAsChinese(language: String?, text: String): Boolean {
        val locked = settings.sourceLanguage
        if (locked == "zh-CN" || locked == "yue-CN") return true
        if (locked.isNotBlank() && locked != SourceLanguages.AUTO) return false
        return shouldSkipTranslation(language, text)
    }

    private fun sourceForMt(asrLanguage: String?): String? {
        if (settings.mtProvider != Provider.VOLCENGINE) return null
        val locked = settings.sourceLanguage
        if (locked.isNotBlank() && locked != SourceLanguages.AUTO) {
            return SourceLanguages.mtCode(locked)
        }
        return doubaoMtSourceLanguage(asrLanguage)
    }

    private fun cachedChinese(spoken: String): String? {
        val source = lastSource
        val chinese = lastChinese ?: return null
        if (source.isEmpty()) return null
        return if (spoken == source || spoken.startsWith(source)) chinese else null
    }
}
