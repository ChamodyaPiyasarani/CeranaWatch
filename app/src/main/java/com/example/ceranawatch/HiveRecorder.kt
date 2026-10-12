package com.example.ceranawatch

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlin.math.sqrt

/**
 * Records hive sound at 16 kHz, mono, 16-bit.
 * It asks the phone for RAW sound (no automatic volume control, no noise removal),
 * because those phone features change the sound the model listens to.
 */
class HiveRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
    }

    @Volatile
    private var stopRequested = false

    /** Which microphone mode the phone really gave us. Shown on screen and saved later. */
    @Volatile
    var sourceUsed: String = "none"
        private set

    fun requestStop() {
        stopRequested = true
    }

    /**
     * Records for the given number of seconds. This call blocks, so run it on a background thread.
     * onProgress is called about 10 times a second with (seconds recorded, loudness from 0 to 1).
     */
    @SuppressLint("MissingPermission")
    fun record(seconds: Int, onProgress: (Int, Float) -> Unit): ShortArray {
        stopRequested = false
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) throw IllegalStateException("This phone cannot record at 16 kHz.")
        val bufferBytes = maxOf(minBuf, SAMPLE_RATE * 2)   // room for 1 second of sound
        val recorder = openRecorder(bufferBytes)

        val total = seconds * SAMPLE_RATE
        val out = ShortArray(total)
        val chunk = ShortArray(SAMPLE_RATE / 10)           // 0.1 second at a time
        var written = 0
        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("The microphone is busy. Close other apps that record sound.")
            }
            while (written < total && !stopRequested) {
                val want = minOf(chunk.size, total - written)
                val n = recorder.read(chunk, 0, want)
                if (n < 0) throw IllegalStateException("Microphone error (code $n).")
                if (n == 0) continue
                System.arraycopy(chunk, 0, out, written, n)
                written += n
                var sumSq = 0.0
                for (i in 0 until n) {
                    val v = chunk[i] / 32768.0
                    sumSq += v * v
                }
                onProgress(written / SAMPLE_RATE, sqrt(sumSq / n).toFloat())
            }
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            recorder.release()
        }
        return if (written == total) out else out.copyOf(written)
    }

    /**
     * Listens for at most maxSeconds and hands the sound to onChunk about 10 times a second:
     * (the samples, how many of them, seconds listened so far, loudness from 0 to 1).
     * Stops early when onChunk returns true or when requestStop() is called.
     * This call blocks, so run it on a background thread.
     */
    @SuppressLint("MissingPermission")
    fun listen(maxSeconds: Int, onChunk: (ShortArray, Int, Int, Float) -> Boolean) {
        stopRequested = false
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) throw IllegalStateException("This phone cannot record at 16 kHz.")
        val recorder = openRecorder(maxOf(minBuf, SAMPLE_RATE * 4))   // room for 2 seconds of sound
        val total = maxSeconds.toLong() * SAMPLE_RATE
        val chunk = ShortArray(SAMPLE_RATE / 10)
        var heard = 0L
        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("The microphone is busy. Close other apps that record sound.")
            }
            while (heard < total && !stopRequested) {
                val want = minOf(chunk.size.toLong(), total - heard).toInt()
                val n = recorder.read(chunk, 0, want)
                if (n < 0) throw IllegalStateException("Microphone error (code $n).")
                if (n == 0) continue
                heard += n
                var sumSq = 0.0
                for (i in 0 until n) {
                    val v = chunk[i] / 32768.0
                    sumSq += v * v
                }
                if (onChunk(chunk, n, (heard / SAMPLE_RATE).toInt(), sqrt(sumSq / n).toFloat())) break
            }
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            recorder.release()
        }
    }

    /** Tries the raw microphone first, then two fall-backs. */
    @SuppressLint("MissingPermission")
    private fun openRecorder(bufferBytes: Int): AudioRecord {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val rawSupported =
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

        val choices = ArrayList<Pair<Int, String>>()
        if (rawSupported) choices.add(Pair(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED"))
        choices.add(Pair(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"))
        choices.add(Pair(MediaRecorder.AudioSource.MIC, "MIC"))

        for ((source, name) in choices) {
            try {
                val r = AudioRecord(
                    source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferBytes
                )
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    sourceUsed = name
                    return r
                }
                r.release()
            } catch (_: Exception) {
                // try the next choice
            }
        }
        throw IllegalStateException("Could not open the microphone.")
    }

    /** The private folder where the app keeps its recordings. */
    fun recordingsDir(): File {
        val dir = File(context.filesDir, "recordings")
        dir.mkdirs()
        return dir
    }

    /**
     * Puts a copy of the file where you can see it with the phone's Files app
     * or copy it to a laptop. Returns the place as text for the screen.
     */
    fun exportCopy(file: File): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues()
                values.put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                values.put(
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MUSIC + "/CeranaWatch"
                )
                values.put(MediaStore.Audio.Media.IS_PENDING, 1)
                val collection =
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = resolver.insert(collection, values)
                    ?: return "Could not make a copy in the Music folder"
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                val done = ContentValues()
                done.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, done, null, null)
                "Music/CeranaWatch/" + file.name
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    ?: return "No shared storage on this phone"
                val copy = File(dir, file.name)
                file.copyTo(copy, overwrite = true)
                copy.absolutePath
            }
        } catch (e: Exception) {
            "Copy failed: " + (e.message ?: "unknown error")
        }
    }
}
