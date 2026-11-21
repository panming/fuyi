package com.fanyi.overlay.audio

/**
 * Downmixes to mono and resamples to 16 kHz little-endian PCM.
 * Integer ratios use a box filter. Other ratios use linear interpolation.
 */
class PcmResampler(
    private val inputRate: Int,
    private val channels: Int,
    private val outputRate: Int = 16_000,
) {
    private val pending = ArrayList<Short>(8_192)

    fun resample(interleaved: ShortArray, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        appendMono(interleaved, length)
        return when {
            inputRate == outputRate -> drainAll()
            inputRate % outputRate == 0 -> drainBox(inputRate / outputRate)
            else -> drainLinear()
        }
    }

    private fun appendMono(input: ShortArray, length: Int) {
        if (channels <= 1) {
            for (i in 0 until length) pending.add(input[i])
            return
        }
        var i = 0
        while (i + channels <= length) {
            var sum = 0
            for (c in 0 until channels) sum += input[i + c].toInt()
            pending.add((sum / channels).toShort())
            i += channels
        }
    }

    private fun drainAll(): ByteArray {
        val out = shortsToLe(pending)
        pending.clear()
        return out
    }

    private fun drainBox(factor: Int): ByteArray {
        val count = pending.size / factor
        if (count == 0) return ByteArray(0)
        val samples = ShortArray(count)
        var src = 0
        for (i in 0 until count) {
            var sum = 0
            for (k in 0 until factor) sum += pending[src + k].toInt()
            samples[i] = (sum / factor).toShort()
            src += factor
        }
        drop(src)
        return shortsToLe(samples)
    }

    private fun drainLinear(): ByteArray {
        if (pending.size < 2) return ByteArray(0)
        val step = inputRate.toDouble() / outputRate.toDouble()
        val samples = ArrayList<Short>()
        var pos = 0.0
        while (pos + 1.0 < pending.size) {
            val i0 = pos.toInt()
            val frac = pos - i0
            val s0 = pending[i0].toInt()
            val s1 = pending[i0 + 1].toInt()
            val mixed = s0 + ((s1 - s0) * frac)
            samples.add(mixed.toInt().coerceIn(-32768, 32767).toShort())
            pos += step
        }
        val dropCount = pos.toInt()
        drop(dropCount)
        return shortsToLe(samples)
    }

    private fun drop(count: Int) {
        if (count <= 0) return
        if (count >= pending.size) {
            pending.clear()
            return
        }
        pending.subList(0, count).clear()
    }

    private fun shortsToLe(samples: List<Short>): ByteArray {
        val out = ByteArray(samples.size * 2)
        var o = 0
        for (sample in samples) {
            val v = sample.toInt()
            out[o++] = (v and 0xFF).toByte()
            out[o++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun shortsToLe(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var o = 0
        for (sample in samples) {
            val v = sample.toInt()
            out[o++] = (v and 0xFF).toByte()
            out[o++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
