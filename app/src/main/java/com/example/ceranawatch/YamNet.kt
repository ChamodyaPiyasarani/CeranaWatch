package com.example.ceranawatch

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YAMNet, Google's sound recogniser (Apache License 2.0), run on the phone with TensorFlow Lite.
 * It hears 0.975 seconds of sound (15600 samples at 16 kHz) and gives a score for each of 521 sound types.
 * The model file is app/src/main/assets/yamnet.tflite. Everything runs on the phone, without internet.
 */
class YamNet private constructor(private val interpreter: Interpreter) {

    companion object {
        private const val ASSET = "yamnet.tflite"

        @Volatile
        private var shared: YamNet? = null

        @Volatile
        var loadError: String = ""
            private set

        /** The one YAMNet of the app, loaded the first time. Null when it cannot be loaded (then the app uses the old rule only). */
        fun get(context: Context): YamNet? {
            shared?.let { return it }
            synchronized(this) {
                shared?.let { return it }
                return try {
                    val bytes = context.applicationContext.assets.open(ASSET).use { it.readBytes() }
                    val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                    buffer.put(bytes)
                    buffer.rewind()
                    val options = Interpreter.Options()
                    options.setNumThreads(2)
                    val y = YamNet(Interpreter(buffer, options))
                    y.scores(FloatArray(BeeGate.WINDOW))     // one test run, so a broken model is found now and not while listening
                    shared = y
                    loadError = ""
                    y
                } catch (e: Throwable) {
                    loadError = e.message ?: e.toString()
                    null
                }
            }
        }
    }

    /** 521 scores (0 to 1) for one window of 15600 samples. */
    @Synchronized
    fun scores(window: FloatArray): FloatArray {
        val out = Array(1) { FloatArray(BeeGate.CLASSES) }
        interpreter.run(window, out)
        return out[0]
    }
}
