package com.fanyi.overlay.asr

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Volcengine SAUC binary WebSocket frames.
 * Header is 4 bytes. A sequence int32 follows when the low flag bit is set.
 * Payload size is uint32 big-endian. Audio and JSON bodies are gzip.
 */
internal object SaucProtocol {
    const val FULL_SERVER = 0b1001
    const val ERROR = 0b1111

    private const val VERSION = 0b0001
    private const val HEADER_UNITS = 0b0001
    private const val FULL_CLIENT = 0b0001
    private const val AUDIO_ONLY = 0b0010
    private const val JSON = 0b0001
    private const val RAW = 0b0000
    private const val GZIP = 0b0001

    fun fullClient(json: ByteArray, sequence: Int = 1): ByteArray {
        return frame(FULL_CLIENT, flags = 0b0001, serial = JSON, compression = GZIP, sequence = sequence, payload = gzip(json))
    }

    fun audio(pcm: ByteArray, sequence: Int, last: Boolean): ByteArray {
        val flags = if (last) 0b0011 else 0b0001
        val seq = if (last) -sequence else sequence
        return frame(AUDIO_ONLY, flags, RAW, GZIP, seq, gzip(pcm))
    }

    fun parse(data: ByteArray): ServerFrame {
        require(data.size >= 8) { "识别包太短" }
        val headerBytes = (data[0].toInt() and 0x0F) * 4
        require(headerBytes >= 4 && data.size >= headerBytes + 4) { "识别包头无效" }
        val type = (data[1].toInt() and 0xF0) ushr 4
        val flags = data[1].toInt() and 0x0F
        val compression = data[2].toInt() and 0x0F
        var offset = headerBytes
        if (type == ERROR) {
            val code = readUInt(data, offset)
            offset += 4
            val size = readUInt(data, offset)
            offset += 4
            val payload = slice(data, offset, size)
            return ServerFrame(
                messageType = type,
                flags = flags,
                sequence = null,
                payload = if (compression == GZIP) ungzip(payload) else payload,
                errorCode = code.toLong(),
            )
        }
        var sequence: Int? = null
        if (flags and 0b0001 != 0) {
            sequence = readInt(data, offset)
            offset += 4
        }
        val size = readUInt(data, offset)
        offset += 4
        val payload = slice(data, offset, size)
        return ServerFrame(
            messageType = type,
            flags = flags,
            sequence = sequence,
            payload = if (compression == GZIP && payload.isNotEmpty()) ungzip(payload) else payload,
            errorCode = null,
        )
    }

    private fun frame(
        type: Int,
        flags: Int,
        serial: Int,
        compression: Int,
        sequence: Int?,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 16)
        out.write((VERSION shl 4) or HEADER_UNITS)
        out.write((type shl 4) or flags)
        out.write((serial shl 4) or compression)
        out.write(0)
        if (flags and 0b0001 != 0 && sequence != null) writeInt(out, sequence)
        writeInt(out, payload.size)
        out.write(payload)
        return out.toByteArray()
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun readInt(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF shl 24) or
            (data[offset + 1].toInt() and 0xFF shl 16) or
            (data[offset + 2].toInt() and 0xFF shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun readUInt(data: ByteArray, offset: Int): Int = readInt(data, offset)

    private fun slice(data: ByteArray, offset: Int, size: Int): ByteArray {
        val end = (offset + size).coerceAtMost(data.size)
        if (offset >= data.size || end <= offset) return ByteArray(0)
        return data.copyOfRange(offset, end)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun ungzip(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        return try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } catch (_: Exception) {
            bytes
        }
    }
}

internal data class ServerFrame(
    val messageType: Int,
    val flags: Int,
    val sequence: Int?,
    val payload: ByteArray,
    val errorCode: Long?,
) {
    val isFinalPacket: Boolean get() = flags and 0b0010 != 0
}
