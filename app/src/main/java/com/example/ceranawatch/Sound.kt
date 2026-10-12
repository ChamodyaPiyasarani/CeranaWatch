package com.example.ceranawatch

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Sound analysis. No Android code in this file, so the same code was run on a
 * computer and compared with the Python (Colab) numbers.
 *
 * Steps for one check:
 *   1. cut the sound into 3-second clips (the first 9 seconds are skipped)
 *   2. clean-sound rule: drop clips with too much speech-band sound (talking, birds, horns, rain)
 *   3. bee-band spectrum (60-1000 Hz) for each clean clip
 *   4. strength model votes on each clip; the result is "k of n clips agreed"
 *
 * While the phone listens, steps 1 and 2 run clip by clip (CleanCollector): clips with other
 * sounds are thrown away and the phone keeps listening until it has one minute of clean sound.
 */

/** Signal processing: FFT, clean-sound rule and bee-band spectrum. */
object Dsp {
    const val SR = 16000

    private val hannCache = HashMap<Int, DoubleArray>()

    /** Periodic Hann window, the same as the one used in training. */
    private fun hann(n: Int): DoubleArray =
        hannCache.getOrPut(n) { DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / n) } }

    /** In-place FFT. The length must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = i + k + len / 2
                    val xRe = re[b] * curRe - im[b] * curIm
                    val xIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - xRe
                    im[b] = im[a] - xIm
                    re[a] += xRe
                    im[a] += xIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Power spectrum of every frame. Frames start every `hop` samples and do not
     * go past the end of the clip. Returns frames x (nFft/2 + 1) values.
     */
    fun powerFrames(x: FloatArray, nFft: Int, hop: Int): Array<DoubleArray> {
        if (x.size < nFft) return emptyArray()
        val window = hann(nFft)
        val frames = 1 + (x.size - nFft) / hop
        val re = DoubleArray(nFft)
        val im = DoubleArray(nFft)
        return Array(frames) { t ->
            val start = t * hop
            for (i in 0 until nFft) {
                re[i] = x[start + i] * window[i]
                im[i] = 0.0
            }
            fft(re, im)
            DoubleArray(nFft / 2 + 1) { k -> re[k] * re[k] + im[k] * im[k] }
        }
    }

    /** First and last FFT bin inside a frequency band (both ends included). */
    private fun bandBins(nFft: Int, loHz: Double, hiHz: Double): IntRange {
        var first = -1
        var last = -1
        for (k in 0..nFft / 2) {
            val f = k * SR.toDouble() / nFft
            if (f >= loHz && f <= hiHz) {
                if (first < 0) first = k
                last = k
            }
        }
        return first..last
    }

    /**
     * Clean-sound rule. Speech-band energy (900-4000 Hz) as a share of
     * bee-band (100-600 Hz) plus speech-band energy, in half-second steps.
     * Returns the middle (median) share for the clip. Low = clean bee sound.
     */
    fun speechShare(x: FloatArray): Double {
        val frames = powerFrames(x, 1024, 512)
        val bee = bandBins(1024, 100.0, 600.0)
        val speech = bandBins(1024, 900.0, 4000.0)
        val perGroup = (0.5 * SR / 512).toInt().coerceAtLeast(1)   // frames in half a second
        val groups = frames.size / perGroup
        if (groups == 0) return Double.NaN
        val shares = DoubleArray(groups)
        for (gIdx in 0 until groups) {
            var lo = 0.0
            var hi = 0.0
            for (t in gIdx * perGroup until (gIdx + 1) * perGroup) {
                val p = frames[t]
                for (k in bee) lo += p[k]
                for (k in speech) hi += p[k]
            }
            shares[gIdx] = hi / (lo + hi + 1e-12)
        }
        shares.sort()
        return if (groups % 2 == 1) shares[groups / 2]
        else 0.5 * (shares[groups / 2 - 1] + shares[groups / 2])
    }

    /** Bee-band spectrum of one clip in dB: 121 values from 60 to 1000 Hz. */
    fun beeSpectrumDb(x: FloatArray): DoubleArray {
        val frames = powerFrames(x, 2048, 512)
        val band = bandBins(2048, 60.0, 1000.0)
        val out = DoubleArray(band.last - band.first + 1)
        if (frames.isEmpty()) return out
        for (i in out.indices) {
            var sum = 0.0
            for (p in frames) sum += p[band.first + i]
            out[i] = 10.0 * log10(sum / frames.size + 1e-12)
        }
        return out
    }

    /** Removes loudness: the spectrum minus its own average. Only the shape is left. */
    fun shapeOf(spectrumDb: DoubleArray): DoubleArray {
        val mean = spectrumDb.average()
        return DoubleArray(spectrumDb.size) { spectrumDb[it] - mean }
    }

    /** Loudness of the samples in dB (0 dB = loudest possible). */
    fun rmsDb(x: ShortArray, from: Int, to: Int): Double {
        if (to <= from) return -120.0
        var sumSq = 0.0
        for (i in from until to) {
            val v = x[i] / 32768.0
            sumSq += v * v
        }
        val rms = sqrt(sumSq / (to - from))
        return if (rms <= 1e-6) -120.0 else 20.0 * log10(rms)
    }
}

/** What happened to one 3-second clip. */
class ClipInfo(
    val startSec: Double,
    val speechShare: Double,
    val clean: Boolean,
    val vote: String?,
    val shape: DoubleArray? = null     // bee-band shape of a clean clip (kept for the on-phone learning)
)

/** The result of one hive check. */
data class CheckResult(
    val outcome: String,            // READING, UNCLEAR, QUIET or NOISY
    val strength: String?,          // strong / medium / weak, only for READING and UNCLEAR
    val agree: Int,                 // clean clips that voted for the top class
    val cleanClips: Int,
    val totalClips: Int,
    val votes: Map<String, Int>,
    val shape: DoubleArray?,        // average bee-band shape of the clean clips
    val rmsDb: Double,
    val clips: List<ClipInfo>
)

object Analyzer {
    const val CLIP_SECONDS = 3
    const val SKIP_SECONDS = 9            // the colony is disturbed just after the lid is lifted
    const val CLEAN_LIMIT = 0.30          // clean-sound rule, same value as in training
    const val MIN_CLEAN_CLIPS = 8
    const val QUIET_DB = -65.0            // below this the recording is treated as "very quiet"
    const val MIN_SHARE_FOR_READING = 0.5 // the top class needs at least half of the clean clips
    const val CHANGE_LIMIT_DB = 3.5       // "own normal" check, see ModelData.BASELINE_NOTE
    const val TARGET_CLEAN_CLIPS = 20     // the phone listens until it has this many clean clips (one minute)
    const val MAX_LISTEN_SECONDS = 180    // ... but never longer than this
    const val SILENT_CLIP_DB = -75.0      // a clip below this is silence, not bee sound

    const val READING = "READING"
    const val UNCLEAR = "UNCLEAR"
    const val QUIET = "QUIET"
    const val NOISY = "NOISY"

    /** Strength class for one clip from its bee-band shape. */
    fun classify(shape: DoubleArray): String {
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (c in ModelData.CLASSES.indices) {
            var score = ModelData.INTERCEPT[c]
            val coef = ModelData.COEF[c]
            for (i in shape.indices) {
                score += coef[i] * (shape[i] - ModelData.MEAN[i]) / ModelData.SCALE[i]
            }
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return ModelData.CLASSES[best]
    }

    /**
     * The result of a check that was collected clip by clip while the phone listened.
     * Only the clean clips are analysed; "total" is the number of clips the phone heard.
     */
    fun analyzeCollected(c: CleanCollector): CheckResult {
        if (c.cleanClips == 0) {
            val votes = LinkedHashMap<String, Int>()
            for (name in ModelData.CLASSES) votes[name] = 0
            val outcome = if (c.silentClips > c.otherClips) QUIET else NOISY
            return CheckResult(outcome, null, 0, 0, c.heardClips, votes, null, c.heardDb(), emptyList())
        }
        return analyze(c.keptSamples(), 0).copy(totalClips = c.heardClips)
    }

    /** Runs the whole check on a recording (16 kHz, mono). */
    fun analyze(samples: ShortArray, skipSeconds: Int = SKIP_SECONDS): CheckResult {
        val sr = Dsp.SR
        val clipLen = CLIP_SECONDS * sr
        var skip = skipSeconds * sr
        if (samples.size < skip + MIN_CLEAN_CLIPS * clipLen) skip = 0   // short recording: use all of it
        val total = (samples.size - skip) / clipLen
        val rms = Dsp.rmsDb(samples, skip.coerceAtMost(samples.size), samples.size)

        val clips = ArrayList<ClipInfo>()
        val votes = LinkedHashMap<String, Int>()
        for (c in ModelData.CLASSES) votes[c] = 0
        val shapeSum = DoubleArray(ModelData.MEAN.size)
        var clean = 0
        val buf = FloatArray(clipLen)
        for (n in 0 until total) {
            val start = skip + n * clipLen
            for (i in 0 until clipLen) buf[i] = samples[start + i] / 32768f
            val share = Dsp.speechShare(buf)
            val isClean = !share.isNaN() && share <= CLEAN_LIMIT
            var vote: String? = null
            var lastShape: DoubleArray? = null
            if (isClean) {
                val shape = Dsp.shapeOf(Dsp.beeSpectrumDb(buf))
                lastShape = shape
                vote = classify(shape)
                votes[vote] = (votes[vote] ?: 0) + 1
                for (i in shape.indices) shapeSum[i] += shape[i]
                clean++
            }
            clips.add(ClipInfo(start.toDouble() / sr, share, isClean, vote, if (isClean) lastShape else null))
        }

        if (rms < QUIET_DB) {
            return CheckResult(QUIET, null, 0, clean, total, votes, null, rms, clips)
        }
        if (clean < MIN_CLEAN_CLIPS) {
            return CheckResult(NOISY, null, 0, clean, total, votes, null, rms, clips)
        }
        val meanShape = DoubleArray(shapeSum.size) { shapeSum[it] / clean }
        var top = ModelData.CLASSES[0]
        for (c in ModelData.CLASSES) if ((votes[c] ?: 0) > (votes[top] ?: 0)) top = c
        val agree = votes[top] ?: 0
        val outcome = if (agree >= MIN_SHARE_FOR_READING * clean) READING else UNCLEAR
        return CheckResult(outcome, top, agree, clean, total, votes, meanShape, rms, clips)
    }

    /** How different two bee-band shapes are: the average gap in dB. */
    fun shapeDistance(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        for (i in 0 until n) sum += kotlin.math.abs(a[i] - b[i])
        return sum / n
    }

    /** Average of several shapes (used for the hive's "own normal"). */
    fun averageShape(shapes: List<DoubleArray>): DoubleArray {
        val sum = DoubleArray(shapes[0].size)
        for (s in shapes) {
            for (i in sum.indices) sum[i] = sum[i] + s[i]
        }
        val count = shapes.size.toDouble()
        return DoubleArray(sum.size) { sum[it] / count }
    }
}

/**
 * Collects bee sound from a live stream of sound, 3 seconds at a time.
 *
 * Every 3-second clip is judged:
 *   - silence                                  -> not kept
 *   - too much sound above 900 Hz (birds,
 *     horns, rain, hiss)                       -> not kept (the clean-sound rule used in training)
 *   - other sounds found by the bee-sound gate
 *     (talking, vehicles, wind, animals...)    -> not kept (only when `beeGate` is given, see BeeGate)
 *   - otherwise                                -> kept
 * The first 9 seconds are not used. When 20 clips (one minute) are kept, the job is done.
 *
 * Limits: a clip is kept or thrown away as a whole; nothing can take a voice out of a clip.
 * Without the bee-sound gate, talking, engines and wind often pass as clean (tested 8 Oct 2026).
 * No Android code here, so this class is tested on a computer with real hive recordings.
 */
class CleanCollector(
    private val targetClips: Int = Analyzer.TARGET_CLEAN_CLIPS,
    private val beeGate: ((FloatArray) -> Boolean)? = null
) {

    companion object {
        const val WAIT = "WAIT"         // the first seconds, which are not used
        const val BEE = "BEE"           // the last clip was clean and was kept
        const val OTHER = "OTHER"       // the last clip had other sounds and was thrown away
        const val SILENT = "SILENT"     // the last clip was silence and was thrown away
    }

    private val clipLen = Analyzer.CLIP_SECONDS * Dsp.SR
    private val skipSamples = Analyzer.SKIP_SECONDS.toLong() * Dsp.SR
    private val clip = ShortArray(clipLen)
    private val floatClip = FloatArray(clipLen)
    private val kept = ShortArray(targetClips * clipLen)
    private var filled = 0
    private var seen = 0L
    private var heardSumSq = 0.0

    @Volatile var cleanClips = 0
        private set
    @Volatile var heardClips = 0        // clips that were judged (the skipped start is not counted)
        private set
    @Volatile var otherClips = 0
        private set
    @Volatile var silentClips = 0
        private set
    @Volatile var gateClips = 0         // clips thrown away by the bee-sound gate (part of otherClips)
        private set
    @Volatile var lastState = WAIT
        private set

    fun done(): Boolean = cleanClips >= targetClips

    /** Seconds of clean sound collected so far. */
    fun cleanSeconds(): Int = cleanClips * Analyzer.CLIP_SECONDS

    /** Takes the next piece of sound. Returns true when enough clean sound has been collected. */
    fun add(chunk: ShortArray, count: Int): Boolean {
        var i = 0
        while (i < count && !done()) {
            if (seen < skipSamples) {
                val take = minOf(skipSamples - seen, (count - i).toLong()).toInt()
                seen += take
                i += take
                continue
            }
            val take = minOf(clipLen - filled, count - i)
            System.arraycopy(chunk, i, clip, filled, take)
            filled += take
            seen += take
            i += take
            if (filled == clipLen) {
                judge()
                filled = 0
            }
        }
        return done()
    }

    private fun judge() {
        var sumSq = 0.0
        for (k in 0 until clipLen) {
            val v = clip[k] / 32768f
            floatClip[k] = v
            sumSq += v * v
        }
        heardSumSq += sumSq
        heardClips++
        val rms = sqrt(sumSq / clipLen)
        val db = if (rms <= 1e-6) -120.0 else 20.0 * log10(rms)
        if (db < Analyzer.SILENT_CLIP_DB) {
            silentClips++
            lastState = SILENT
            return
        }
        val share = Dsp.speechShare(floatClip)
        if (share.isNaN() || share > Analyzer.CLEAN_LIMIT) {
            otherClips++
            lastState = OTHER
            return
        }
        if (beeGate != null && !beeGate.invoke(floatClip)) {
            otherClips++
            gateClips++
            lastState = OTHER
            return
        }
        System.arraycopy(clip, 0, kept, cleanClips * clipLen, clipLen)
        cleanClips++
        lastState = BEE
    }

    /** The clean clips, one after the other. */
    fun keptSamples(): ShortArray = kept.copyOf(cleanClips * clipLen)

    /** Loudness of everything that was judged, in dB. */
    fun heardDb(): Double {
        if (heardClips == 0) return -120.0
        val rms = sqrt(heardSumSq / (heardClips.toLong() * clipLen))
        return if (rms <= 1e-6) -120.0 else 20.0 * log10(rms)
    }
}

/** The five questions asked after a check, and the simple rules that read the answers. English and Sinhala. */
object Advice {
    class Question(val id: String, val english: String, val sinhala: String) {
        fun text(inSinhala: Boolean): String = if (inSinhala) sinhala else english
    }

    const val YES = "yes"
    const val NO = "no"
    const val UNSURE = "not sure"

    val QUESTIONS = listOf(
        Question(
            "brood", "Did you see eggs or young larvae in the comb?",
            "මී වදේ බිත්තර හරි පොඩි පැටව් (කීටයන්) හරි දැක්කාද?"
        ),
        Question(
            "wasps", "Are wasps or hornets attacking at the entrance?",
            "මැස්සෝ යන එන සිදුර ළඟ දෙබරු මී මැස්සන්ට පහර දෙනවාද?"
        ),
        Question(
            "moth", "Is there webbing, tunnels or small worms in the comb?",
            "මී වදේ මකුළු දැල් වගේ නූල්, පණු පාරවල්, නැත්නම් පොඩි පණුවෝ දැක්කාද?"
        ),
        Question(
            "leaving", "Are there far fewer bees than before, or are bees leaving the hive?",
            "කලින්ට වඩා මී මැස්සෝ ගොඩක් අඩුද, නැත්නම් මී මැස්සෝ පෙට්ටිය දාලා යනවාද?"
        ),
        Question(
            "food", "Is there stored honey and pollen in the comb?",
            "මී වදේ මී පැණි සහ මල් පිටි (පරාග) ගබඩා කරලා තියෙනවාද?"
        )
    )

    /** Turns the answers into short suggestions. These come from the answers, not from the sound. */
    fun suggestions(answers: Map<String, String>, inSinhala: Boolean): List<String> {
        fun pick(english: String, sinhala: String) = if (inSinhala) sinhala else english
        val out = ArrayList<String>()
        if (answers["brood"] == NO) {
            out.add(
                pick(
                    "Possible queen problem: no eggs or young larvae were seen. Look again in 3 to 4 days. If there are still none, the colony may be queenless. Give a comb with eggs from a strong hive, or ask an experienced beekeeper.",
                    "රැජින ගැන ප්‍රශ්නයක් වෙන්න පුළුවන්: බිත්තර හරි පොඩි පැටව් හරි දැක්කේ නැහැ. දවස් 3-4කින් ආයෙත් බලන්න. එතකොටත් නැත්නම් ජනපදයේ රැජින නැති වෙලා වෙන්න පුළුවන්. ශක්තිමත් පෙට්ටියකින් බිත්තර තියෙන වදයක් දෙන්න, නැත්නම් පළපුරුදු මී පාලකයෙක්ගෙන් අහන්න."
                )
            )
        }
        if (answers["wasps"] == YES) {
            out.add(
                pick(
                    "Wasp or hornet attack: make the entrance smaller, kill the attacking wasps, and look for their nest nearby. Check this hive every day until the attacks stop.",
                    "දෙබරු පහර දෙනවා: මැස්සෝ යන එන සිදුර පොඩි කරන්න, පහර දෙන දෙබරු මරන්න, ළඟපාත තියෙන දෙබර වදය හොයන්න. පහර දීම නවතිනකල් මේ පෙට්ටිය හැමදාම බලන්න."
                )
            )
        }
        if (answers["moth"] == YES) {
            out.add(
                pick(
                    "Possible wax moth: remove the damaged comb, clean the bottom of the hive, and keep the colony strong. Wax moth usually takes over weak colonies.",
                    "ඉටි සලබයා (ඉටි පණුවා) වෙන්න පුළුවන්: හානි වුණ වදය අයින් කරන්න, පෙට්ටියේ පතුල සුද්ද කරන්න, ජනපදය ශක්තිමත්ව තියාගන්න. ඉටි සලබයා ගොඩක් වෙලාවට එන්නේ දුර්වල ජනපදවලටයි."
                )
            )
        }
        if (answers["leaving"] == YES) {
            out.add(
                pick(
                    "Possible absconding: the colony may be getting ready to leave. Check for food, pests and too much heat or disturbance. Feed sugar syrup if there is no honey, and avoid opening the hive often.",
                    "ජනපදය පෙට්ටිය දාලා යන්න හදනවා වෙන්න පුළුවන්: කෑම තියෙනවාද, පළිබෝධ ඉන්නවාද, රස්නය වැඩිද, කරදර වැඩිද බලන්න. මී පැණි නැත්නම් සීනි පැණි දෙන්න. පෙට්ටිය නිතර අරින්න එපා."
                )
            )
        }
        if (answers["food"] == NO) {
            out.add(
                pick(
                    "Low food stores: feed sugar syrup in the evening and check again in a few days. A hungry Apis cerana colony often absconds.",
                    "පෙට්ටියේ කෑම අඩුයි: හවස් වරුවේ සීනි පැණි දීලා දවස් කීපයකින් ආයෙත් බලන්න. බඩගින්නේ ඉන්න මී ජනපද ගොඩක් වෙලාවට පෙට්ටිය දාලා යනවා."
                )
            )
        }
        if (out.isEmpty()) {
            val asked = QUESTIONS.map { answers[it.id] }
            if (asked.any { it == null || it == UNSURE }) {
                out.add(
                    pick(
                        "No clear problem from your answers, but some answers were 'not sure' or missing. Open the hive and look again when the weather is good.",
                        "ඔබේ පිළිතුරුවලින් පැහැදිලි ප්‍රශ්නයක් පේන්නේ නැහැ. ඒත් සමහර ප්‍රශ්නවලට ඔබ 'හරියටම දන්නේ නැහැ' කියලා තියෙනවා, නැත්නම් පිළිතුරක් දීලා නැහැ. කාලගුණය හොඳ වෙලාවක පෙට්ටිය ඇරලා ආයෙත් බලන්න."
                    )
                )
            } else {
                out.add(
                    pick(
                        "No problem found from your answers. Keep checking this hive regularly.",
                        "ඔබේ පිළිතුරුවලින් ප්‍රශ්නයක් පේන්නේ නැහැ. මේ පෙට්ටිය දිගටම පරීක්ෂා කරන්න."
                    )
                )
            }
        }
        return out
    }
}
