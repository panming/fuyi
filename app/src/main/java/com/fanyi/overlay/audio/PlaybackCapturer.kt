package com.fanyi.overlay.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlin.math.sqrt

/**
 * Captures other apps' playback with AudioPlaybackCapture.
 * Voice-call usages are intentionally not matched.
 * The recorder is never given a microphone audio source.
 */
class PlaybackCapturer(
    private val projection: MediaProjection,
    private val onPcm: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        val opened = openRecord()
        val audio = opened.record
        record = audio
        val resampler = PcmResampler(opened.sampleRate, opened.channels)
        audio.startRecording()
        running = true
        thread = Thread({
            val frameSamples = opened.sampleRate / 5 * opened.channels
            val buffer = ShortArray(frameSamples.coerceAtLeast(opened.minBuffer / 2))
            val pending = ArrayList<Byte>(16_000)
            while (running) {
                val count = audio.read(buffer, 0, buffer.size)
                if (count < 0) {
                    if (running) onError("内录中断（$count）。可以再点一次开始。")
                    break
                }
                if (count == 0) continue
                onLevel(rms(buffer, count))
                val pcm = resampler.resample(buffer, count)
                for (byte in pcm) pending.add(byte)
                while (pending.size >= CHUNK_BYTES) {
                    val frame = ByteArray(CHUNK_BYTES)
                    for (i in 0 until CHUNK_BYTES) frame[i] = pending[i]
                    pending.subList(0, CHUNK_BYTES).clear()
                    onPcm(frame)
                }
            }
        }, "fanyi-audio")
        thread?.start()
    }

    fun stop() {
        running = false
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        thread?.join(800)
        thread = null
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
    }

    private fun openRecord(): Opened {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection).apply {
            USAGES.forEach { addMatchingUsage(it) }
        }.build()
        var last = "没有可用的内录格式"
        for (rate in intArrayOf(48_000, 44_100, 16_000)) {
            for (mask in intArrayOf(AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO)) {
                val min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .build()
                val candidate = try {
                    AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(min * 2)
                        .setAudioPlaybackCaptureConfig(capture)
                        .build()
                } catch (error: Exception) {
                    last = error.message ?: error.javaClass.simpleName
                    continue
                }
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    val channels = if (mask == AudioFormat.CHANNEL_IN_MONO) 1 else 2
                    return Opened(candidate, rate, channels, min)
                }
                candidate.release()
            }
        }
        error(last)
    }

    private fun rms(buffer: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val sample = buffer[i] / 32768.0
            sum += sample * sample
        }
        return sqrt(sum / count).toFloat().coerceIn(0f, 1f)
    }

    private class Opened(
        val record: AudioRecord,
        val sampleRate: Int,
        val channels: Int,
        val minBuffer: Int,
    )

    private companion object {
        const val CHUNK_BYTES = 6_400
        val USAGES = intArrayOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
        )
    }
}
