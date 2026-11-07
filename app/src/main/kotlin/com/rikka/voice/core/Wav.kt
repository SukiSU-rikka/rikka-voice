package com.rikka.voice.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object Wav {
    fun encode(pcm: Pcm): ByteArray {
        val dataLen = pcm.samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataLen); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(pcm.sampleRate); buf.putInt(pcm.sampleRate * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(dataLen)
        for (s in pcm.samples) buf.putShort(s)
        return buf.array()
    }

    /** 解析常见 WAV：PCM 8/16/24/32 位、32 位浮点，多声道会混成单声道。 */
    fun decode(bytes: ByteArray): Pcm {
        require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "返回的不是 WAV 音频" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var format = 1; var channels = 1; var rate = 24000; var bits = 16
        var dataOff = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            var len = bb.getInt(pos + 4)
            val body = pos + 8
            if (len < 0 || body + len > bytes.size) len = bytes.size - body  // 流式 WAV 常把长度写成 0xFFFFFFFF
            when (id) {
                "fmt " -> {
                    format = bb.getShort(body).toInt() and 0xffff
                    channels = bb.getShort(body + 2).toInt().coerceAtLeast(1)
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                    if (format == 0xFFFE && len >= 26) format = bb.getShort(body + 24).toInt() and 0xffff
                }
                "data" -> { dataOff = body; dataLen = len; break }
            }
            pos = body + len + (len and 1)
        }
        require(dataOff >= 0) { "WAV 里没有音频数据" }
        val bytesPer = bits / 8
        val frames = dataLen / (bytesPer * channels)
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var acc = 0.0
            for (c in 0 until channels) {
                val o = dataOff + (f * channels + c) * bytesPer
                acc += when {
                    format == 3 && bits == 32 -> bb.getFloat(o).toDouble()
                    bits == 16 -> bb.getShort(o) / 32768.0
                    bits == 24 -> (((bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8) or (bytes[o + 2].toInt() shl 16))) / 8388608.0
                    bits == 32 -> bb.getInt(o) / 2147483648.0
                    bits == 8 -> ((bytes[o].toInt() and 0xff) - 128) / 128.0
                    else -> 0.0
                }
            }
            out[f] = toShort(acc / channels)
        }
        return Pcm(out, rate)
    }

    fun fromFloat(samples: FloatArray, rate: Int): Pcm = Pcm(ShortArray(samples.size) { toShort(samples[it].toDouble()) }, rate)

    fun scaleVolume(pcm: Pcm, volume: Float): Pcm {
        if (volume in 0.995f..1.005f) return pcm
        return Pcm(ShortArray(pcm.samples.size) { toShort(pcm.samples[it] / 32768.0 * volume) }, pcm.sampleRate)
    }

    fun concat(parts: List<Pcm>, gapMs: Int = 120): Pcm {
        require(parts.isNotEmpty())
        val rate = parts.first().sampleRate
        val gap = rate * gapMs / 1000
        val same = parts.map { if (it.sampleRate == rate) it.samples else resample(it, rate).samples }
        val arr = ShortArray(same.sumOf { it.size } + gap * (same.size - 1))
        var p = 0
        same.forEachIndexed { i, s ->
            System.arraycopy(s, 0, arr, p, s.size); p += s.size
            if (i < same.size - 1) p += gap
        }
        return Pcm(arr, rate)
    }

    /** 小端 16-bit 原始 PCM 字节（系统 TTS 回调用）。 */
    fun pcmBytes(pcm: Pcm): ByteArray {
        val b = ByteBuffer.allocate(pcm.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in pcm.samples) b.putShort(x)
        return b.array()
    }

    /** 简单线性插值重采样，只在拼接不同采样率的片段时使用。 */
    fun resample(pcm: Pcm, rate: Int): Pcm {
        if (pcm.sampleRate == rate) return pcm
        val ratio = pcm.sampleRate.toDouble() / rate
        val n = (pcm.samples.size / ratio).toInt()
        val src = pcm.samples
        return Pcm(ShortArray(n) {
            val x = it * ratio; val i = x.toInt(); val f = x - i
            val a = src[minOf(i, src.size - 1)]; val b = src[minOf(i + 1, src.size - 1)]
            (a + (b - a) * f).roundToInt().toShort()
        }, rate)
    }

    private fun toShort(v: Double): Short = (v.coerceIn(-1.0, 1.0) * 32767.0).roundToInt().toShort()
}
