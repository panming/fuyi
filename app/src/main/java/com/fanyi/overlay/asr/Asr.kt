package com.fanyi.overlay.asr

internal interface AsrEvents {
    fun onReady(detail: String)
    fun onPartial(text: String, language: String?)
    fun onFinal(text: String, language: String?)
    fun onError(message: String)
}

internal interface StreamingAsr {
    suspend fun start()
    suspend fun sendPcm(frame: ByteArray)
    suspend fun finish()
}
