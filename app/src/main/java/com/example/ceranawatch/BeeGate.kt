package com.example.ceranawatch

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The bee-sound gate: decides if a 3-second clip is bee sound only, or has other sounds in it
 * (talking, vehicles, wind, birds, animals, machines...).
 *
 * It uses YAMNet, Google's sound recogniser (521 sound types), on five 0.975-second windows of the clip,
 * together with 46 simple sound measures and the old speech share, and a small model trained on our
 * 70 hive recordings against 57 kinds of other sound (see BeeGateData).
 * A clip is kept only when it passes this gate AND the old clean-sound rule (checked in CleanCollector).
 *
 * No Android code here: YAMNet itself is given as a function, so this file is tested on a computer.
 */
object BeeGate {
    const val WINDOW = 15600                         // YAMNet input: 0.975 s at 16 kHz
    val STARTS = intArrayOf(0, 8100, 16200, 24300, 32400)
    const val CLASSES = 521
    private const val TARGET_RMS = 0.05              // every clip is heard by YAMNet at the same loudness
    private const val EPS = 1e-10

    /** The five windows YAMNet hears, taken from the clip after setting it to one loudness. */
    fun windows(clip: FloatArray): List<FloatArray> {
        var sumSq = 0.0
        for (v in clip) sumSq += v.toDouble() * v
        val gain = TARGET_RMS / (sqrt(sumSq / clip.size) + 1e-9)
        return STARTS.map { s ->
            FloatArray(WINDOW) { i ->
                val v = (clip[s + i] * gain).toFloat()     // same order of rounding as in training
                v.coerceIn(-1f, 1f)
            }
        }
    }

    /** The 46 simple sound measures (same as feats.py in training). */
    fun soundMeasures(clip: FloatArray): DoubleArray {
        val p = Dsp.powerFrames(clip, 1024, 512)
        val frames = p.size
        val bands = BeeGateData.BAND_FIRST.size
        val e = Array(frames) { t ->
            DoubleArray(bands) { b ->
                var s = 0.0
                for (k in BeeGateData.BAND_FIRST[b]..BeeGateData.BAND_LAST[b]) s += p[t][k]
                s + EPS
            }
        }
        val out = DoubleArray(46)
        // band shape (loudness removed) and how much each band moves over time
        val meanDb = DoubleArray(bands) { b -> var s = 0.0; for (t in 0 until frames) s += e[t][b]; 10 * log10(s / frames) }
        val avg = meanDb.average()
        for (b in 0 until bands) out[b] = meanDb[b] - avg
        for (b in 0 until bands) {
            var m = 0.0
            for (t in 0 until frames) m += 10 * log10(e[t][b])
            m /= frames
            var v = 0.0
            for (t in 0 until frames) { val d = 10 * log10(e[t][b]) - m; v += d * d }
            out[20 + b] = sqrt(v / frames)
        }
        // overall loudness: spread, share of dips more than 6 dB below the median, change from frame to frame
        val tot = DoubleArray(frames) { t -> var s = 0.0; for (b in 0 until bands) s += e[t][b]; 10 * log10(s) }
        val totMean = tot.average()
        var tv = 0.0
        for (x in tot) tv += (x - totMean) * (x - totMean)
        out[40] = sqrt(tv / frames)
        val med = median(tot)
        out[41] = tot.count { it < med - 6 }.toDouble() / frames
        var dsum = 0.0
        for (t in 1 until frames) dsum += abs(tot[t] - tot[t - 1])
        out[42] = if (frames > 1) dsum / (frames - 1) else 0.0
        // flatness 100-1000 Hz and 1-4 kHz
        out[43] = flatness(p, 100.0, 1000.0)
        out[44] = flatness(p, 1000.0, 4000.0)
        // spectral flux of the normalised spectrum
        var flux = 0.0
        for (t in 1 until frames) {
            var s0 = 0.0; var s1 = 0.0
            for (k in p[t].indices) { s0 += p[t - 1][k]; s1 += p[t][k] }
            var f = 0.0
            for (k in p[t].indices) f += abs(p[t][k] / (s1 + EPS) - p[t - 1][k] / (s0 + EPS))
            flux += f
        }
        out[45] = if (frames > 1) flux / (frames - 1) else 0.0
        return out
    }

    private fun flatness(p: Array<DoubleArray>, lo: Double, hi: Double): Double {
        var first = -1
        var last = -1
        for (k in 0..512) {
            val f = k * Dsp.SR.toDouble() / 1024
            if (f >= lo && f < hi) { if (first < 0) first = k; last = k }
        }
        var sum = 0.0
        for (row in p) {
            var logSum = 0.0
            var lin = 0.0
            for (k in first..last) { val v = row[k] + EPS; logSum += ln(v); lin += v }
            val n = last - first + 1
            sum += exp(logSum / n) / (lin / n)
        }
        return sum / p.size
    }

    private fun median(x: DoubleArray): Double {
        val s = x.sortedArray()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

    /** All 1089 numbers of one clip, given YAMNet's scores for its five windows. */
    fun features(clip: FloatArray, scores: List<FloatArray>): DoubleArray {
        val out = DoubleArray(BeeGateData.FEATURES)
        for (c in 0 until CLASSES) {
            var sum = 0.0
            var top = Double.NEGATIVE_INFINITY
            for (s in scores) { val v = s[c].toDouble(); sum += v; if (v > top) top = v }
            out[c] = ln(sum / scores.size + 1e-4)
            out[CLASSES + c] = ln(top + 1e-4)
        }
        val m = soundMeasures(clip)
        System.arraycopy(m, 0, out, 2 * CLASSES, m.size)
        out[2 * CLASSES + m.size] = Dsp.speechShare(clip)
        return out
    }

    /** The gate's score: at least BeeGateData.LOGIT_THRESHOLD means bee sound. */
    fun score(features: DoubleArray): Double {
        val mean = BeeGateData.MEAN
        val scale = BeeGateData.SCALE
        val coef = BeeGateData.COEF
        var z = BeeGateData.INTERCEPT
        for (i in features.indices) z += coef[i] * (features[i] - mean[i]) / scale[i]
        return z
    }

    /**
     * True when the clip is bee sound only. `yamnet` turns one window into 521 scores
     * (on the phone this is the TensorFlow Lite model, see YamNet).
     */
    fun isBee(clip: FloatArray, yamnet: (FloatArray) -> FloatArray): Boolean {
        val scores = windows(clip).map { yamnet(it) }
        return score(features(clip, scores)) >= BeeGateData.LOGIT_THRESHOLD
    }
}
