package com.example.ceranawatch

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads and writes simple WAV files: 16-bit, mono, no compression.
 * This file has no Android code in it, so it can be tested on any computer.
 */
object WavIO {

    /** Saves the samples as a 16-bit mono WAV file. */
    fun write(file: File, samples: ShortArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        val bb = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataBytes)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)                    // size of the format block
        bb.putShort(1.toShort())         // 1 = plain PCM
        bb.putShort(1.toShort())         // 1 channel (mono)
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * 2)        // bytes per second
        bb.putShort(2.toShort())         // bytes per sample
        bb.putShort(16.toShort())        // bits per sample
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataBytes)
        for (s in samples) bb.putShort(s)
        file.parentFile?.mkdirs()
        file.outputStream().use { it.write(bb.array()) }
    }

    /** Reads a 16-bit mono WAV file. Returns the samples and the sample rate. */
    fun read(file: File): Pair<ShortArray, Int> {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "File is too small to be a WAV file" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "Not a WAV file" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not a WAV file" }
        var pos = 12
        var sampleRate = 0
        var channels = 1
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                channels = bb.getShort(body + 2).toInt()
                sampleRate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
            } else if (id == "data") {
                require(bits == 16 && channels == 1) { "Only 16-bit mono WAV is supported" }
                val n = minOf(size, bytes.size - body) / 2
                val out = ShortArray(n)
                for (i in 0 until n) out[i] = bb.getShort(body + 2 * i)
                return Pair(out, sampleRate)
            }
            pos = body + size + (size and 1)   // blocks are padded to an even size
        }
        throw IllegalArgumentException("No sound data found in the WAV file")
    }

    /** Simple facts about a recording, used on the screen after recording. */
    data class Stats(val seconds: Double, val peakDb: Double, val rmsDb: Double, val clippedPercent: Double)

    fun stats(samples: ShortArray, sampleRate: Int): Stats {
        if (samples.isEmpty()) return Stats(0.0, -120.0, -120.0, 0.0)
        var peak = 0
        var sumSq = 0.0
        var clipped = 0
        for (s in samples) {
            val a = kotlin.math.abs(s.toInt())
            if (a > peak) peak = a
            if (a >= 32700) clipped++
            val v = s / 32768.0
            sumSq += v * v
        }
        val rms = kotlin.math.sqrt(sumSq / samples.size)
        return Stats(
            seconds = samples.size.toDouble() / sampleRate,
            peakDb = toDb(peak / 32768.0),
            rmsDb = toDb(rms),
            clippedPercent = 100.0 * clipped / samples.size
        )
    }

    /** Turns a level between 0 and 1 into decibels. 0 dB is the loudest possible. */
    fun toDb(level: Double): Double =
        if (level <= 1e-6) -120.0 else 20.0 * kotlin.math.log10(level)
}
