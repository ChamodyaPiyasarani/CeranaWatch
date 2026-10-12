package com.example.ceranawatch

import org.json.JSONObject
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Learning on the phone, from the beekeeper's own hives. No internet and no Colab needed.
 *
 * Three small models are trained on the phone, every time they are needed, from all answers so far:
 *  1. Strength model. It starts as the model trained on the 70 field hives and is adjusted with the
 *     beekeeper's own answer to "How strong is this colony?" after a check, using the sound of the check
 *     and a few hive details (nectar flow, weather, days since opened, colony age).
 *  2. Problem model. It learns which sounds came with a problem in the five questions
 *     (no eggs or young larvae, wasps, wax moth, bees leaving, no stored food).
 *  3. Alert checker. It learns from the beekeeper's "real / false alarm" answers which hive-device
 *     alerts were real, using the alert type, how far the sound was from normal, the hour and the hive's spread.
 *
 * Honest testing ("predict first, then learn"): the first time the beekeeper saves answers for a check,
 * the app works out its guesses for that check from EARLIER answers only and keeps them ("frozen").
 * Later answers or changed answers never change a frozen guess. The scoreboard counts only frozen
 * guesses and compares them with simple guesses on the same checks (the 70-hive model, the answer given
 * most often so far, "same as last time", "never a problem", "every alert is real").
 * The app's own strength guess is shown only after the beekeeper has given their own judgement,
 * so it cannot sway that judgement.
 *
 * No Android code here, so this file is tested on a computer.
 */
object Learner {

    /** Key of the beekeeper's own strength answer in a check's answers. */
    const val JUDGE = "judge"

    const val MIN_STRENGTH = 3      // strength answers needed before the app's own strength model is shown
    const val MIN_EACH = 2          // examples of each side needed before a yes/no model is used
    const val MAX_TRAIN = 150       // a model learns from the newest answers only, so training stays under a second
    const val MAX_TRAIN_STRENGTH = 100   // the strength model learns from every clean clip, so it uses fewer checks

    private const val LAMBDA = 0.1  // pull towards the starting weights: a few answers adjust, they do not replace
    private const val STEPS = 300
    private const val RATE = 0.1
    private const val DETAILS = 8

    val STRENGTHS: List<String> = ModelData.CLASSES.toList()
    private val PROBLEM_CLASSES = listOf("no", "yes")

    // ---------- features ----------

    /** The 121 bee-band numbers, scaled the same way as for the 70-hive model. */
    fun soundFeatures(shape: DoubleArray): DoubleArray =
        DoubleArray(ModelData.MEAN.size) { i -> if (i < shape.size) (shape[i] - ModelData.MEAN[i]) / ModelData.SCALE[i] else 0.0 }

    /** Hive details that change over time. Hive type is left out on purpose: it would only tell the hives apart. */
    fun detailFeatures(d: JSONObject?): DoubleArray {
        val x = DoubleArray(DETAILS)
        if (d == null) return x
        when (textOf(d, "flow")) {
            "yes" -> x[0] = 1.0
            "no" -> x[1] = 1.0
        }
        when (textOf(d, "weather")) {
            "sunny" -> x[2] = 1.0
            "cloudy" -> x[3] = 1.0
            "rain" -> x[4] = 1.0
            "windy" -> x[5] = 1.0
        }
        textOf(d, "daysSinceOpened").toDoubleOrNull()?.let { x[6] = minOf(it, 60.0) / 30.0 }
        textOf(d, "colonyAgeMonths").toDoubleOrNull()?.let { x[7] = minOf(it, 36.0) / 12.0 }
        return x
    }

    fun features(shape: DoubleArray, details: JSONObject?): DoubleArray = soundFeatures(shape) + detailFeatures(details)

    private fun featuresOf(store: Store, r: CheckRecord): DoubleArray? {
        val shape = r.shape ?: return null
        return features(shape, store.learnInfo(r.id)?.optJSONObject("details"))
    }

    // ---------- labels ----------

    /** The beekeeper's own strength answer for a check, or null. */
    fun judgement(r: CheckRecord): String? = r.answers[JUDGE]?.takeIf { it in STRENGTHS }

    /** 1 = a problem was seen, 0 = all five answers say no problem, null = not known (unanswered or "not sure"). */
    fun problemLabel(answers: Map<String, String>): Int? {
        val problem = answers["brood"] == Advice.NO || answers["wasps"] == Advice.YES || answers["moth"] == Advice.YES ||
            answers["leaving"] == Advice.YES || answers["food"] == Advice.NO
        if (problem) return 1
        val clear = answers["brood"] == Advice.YES && answers["wasps"] == Advice.NO && answers["moth"] == Advice.NO &&
            answers["leaving"] == Advice.NO && answers["food"] == Advice.YES
        return if (clear) 0 else null
    }

    // ---------- the model ----------

    /**
     * Logistic regression with one weight row and one bias for each class.
     * counts = how many training answers each class had (empty for the 70-hive model).
     */
    class Model(
        val classes: List<String>, val w: Array<DoubleArray>, val b: DoubleArray, val examples: Int,
        val counts: IntArray = IntArray(0)
    ) {
        private fun scores(x: DoubleArray): DoubleArray {
            val z = DoubleArray(classes.size)
            for (k in classes.indices) {
                var s = b[k]
                val row = w[k]
                for (i in row.indices) s += row[i] * x[i]
                z[k] = s
            }
            return z
        }

        private fun softmax(z: DoubleArray): DoubleArray {
            val top = z.maxOrNull() ?: 0.0
            val e = DoubleArray(z.size) { exp(z[it] - top) }
            val sum = e.sum()
            return DoubleArray(e.size) { e[it] / sum }
        }

        /** Chances as if every class were equally common (this is what the training balances for). */
        fun probabilities(x: DoubleArray): DoubleArray = softmax(scores(x))

        /**
         * Real chances: the balanced chances turned back to how common each class really was in the answers.
         * Used for any percentage shown to the beekeeper.
         */
        fun chances(x: DoubleArray): DoubleArray {
            val z = scores(x)
            if (counts.size != z.size) return softmax(z)
            for (k in z.indices) z[k] += if (counts[k] > 0) ln(counts[k].toDouble()) else Double.NEGATIVE_INFINITY
            return softmax(z)
        }

        /** How often the second class ("yes" / "real") was seen in the training answers, from 0 to 1. */
        fun usual(): Double = if (counts.size == 2 && examples > 0) counts[1].toDouble() / examples else 0.0

        fun predict(x: DoubleArray): String {
            val p = probabilities(x)
            var best = 0
            for (k in p.indices) if (p[k] > p[best]) best = k
            return classes[best]
        }
    }

    /**
     * Fits the weights by gradient descent. Each answer gets a weight so that every class counts the same
     * (balanced classes), and the weights are pulled towards the starting weights.
     */
    private fun fit(classes: List<String>, xs: List<DoubleArray>, ys: List<Int>, w0: Array<DoubleArray>, b0: DoubleArray): Model {
        val k = classes.size
        val d = w0[0].size
        val n = xs.size
        val w = Array(k) { w0[it].copyOf() }
        val b = b0.copyOf()
        val counts = IntArray(k)
        for (y in ys) counts[y]++
        val present = counts.count { it > 0 }
        val weight = DoubleArray(n) { n.toDouble() / (present * counts[ys[it]]) }
        val gw = Array(k) { DoubleArray(d) }
        val gb = DoubleArray(k)
        val model = Model(classes, w, b, n)
        repeat(STEPS) {
            for (c in 0 until k) {
                java.util.Arrays.fill(gw[c], 0.0)
                gb[c] = 0.0
            }
            for (i in 0 until n) {
                val p = model.probabilities(xs[i])
                val x = xs[i]
                for (c in 0 until k) {
                    val g = weight[i] * (p[c] - (if (ys[i] == c) 1.0 else 0.0)) / n
                    if (g == 0.0) continue
                    val row = gw[c]
                    for (j in 0 until d) row[j] += g * x[j]
                    gb[c] += g
                }
            }
            for (c in 0 until k) {
                val row = w[c]
                val start = w0[c]
                val grad = gw[c]
                for (j in 0 until d) row[j] -= RATE * (grad[j] + LAMBDA * (row[j] - start[j]))
                b[c] -= RATE * (gb[c] + 0.1 * LAMBDA * (b[c] - b0[c]))
            }
        }
        return Model(classes, w, b, n, counts)
    }

    /** The 70-hive model, written in the same form as the app's own model (hive details start with no effect). */
    fun baseStrengthModel(): Model {
        val w = Array(STRENGTHS.size) { k -> ModelData.COEF[k] + DoubleArray(DETAILS) }
        return Model(STRENGTHS, w, ModelData.INTERCEPT.copyOf(), 0)
    }

    // ---------- strength ----------

    /** Strength answers given for checks made before the given time (all, when no time is given). */
    fun strengthAnswers(store: Store, before: Long = Long.MAX_VALUE): Int =
        store.allChecks().count { it.id < before && it.shape != null && judgement(it) != null }

    /**
     * The app's own strength model, trained on the phone from every strength answer for checks made
     * before the given time. Null while there are fewer than MIN_STRENGTH answers.
     */
    fun strengthModel(store: Store, before: Long = Long.MAX_VALUE): Model? {
        val checks = ArrayList<Pair<List<DoubleArray>, Int>>()
        for (r in store.allChecks()) {
            if (r.id >= before) continue
            val j = judgement(r) ?: continue
            checks.add(Pair(clipRowsOf(store, r) ?: continue, STRENGTHS.indexOf(j)))
        }
        if (checks.size < MIN_STRENGTH) return null
        // every clean clip of a check is one example (tested: better than one averaged example per check)
        val xs = ArrayList<DoubleArray>()
        val ys = ArrayList<Int>()
        for ((rows, y) in checks.takeLast(MAX_TRAIN_STRENGTH)) {
            for (x in rows) {
                xs.add(x)
                ys.add(y)
            }
        }
        val base = baseStrengthModel()
        val m = fit(STRENGTHS, xs, ys, base.w, base.b)
        return Model(m.classes, m.w, m.b, minOf(checks.size, MAX_TRAIN_STRENGTH), m.counts)
    }

    /** One row of numbers for each clean clip of a check (or one row from the average shape, for older checks). */
    private fun clipRowsOf(store: Store, r: CheckRecord): List<DoubleArray>? {
        val details = store.learnInfo(r.id)?.optJSONObject("details")
        val clips = store.clipShapes(r.id)
        if (clips != null) return clips.map { features(it, details) }
        val shape = r.shape ?: return null
        return listOf(features(shape, details))
    }

    /** What the app's own model says about one check (the average of its clips), using only answers for earlier checks. */
    fun ownStrength(store: Store, r: CheckRecord): Pair<String, Int>? {
        val rows = clipRowsOf(store, r) ?: return null
        val m = strengthModel(store, r.id) ?: return null
        val sum = DoubleArray(STRENGTHS.size)
        for (x in rows) {
            val p = m.probabilities(x)
            for (k in sum.indices) sum[k] += p[k]
        }
        var best = 0
        for (k in sum.indices) if (sum[k] > sum[best]) best = k
        return Pair(STRENGTHS[best], m.examples)
    }

    // ---------- problems ----------

    fun problemModel(store: Store, before: Long = Long.MAX_VALUE): Model? {
        val xs = ArrayList<DoubleArray>()
        val ys = ArrayList<Int>()
        for (r in store.allChecks()) {
            if (r.id >= before) continue
            val y = problemLabel(r.answers) ?: continue
            xs.add(featuresOf(store, r) ?: continue)
            ys.add(y)
        }
        val x = xs.takeLast(MAX_TRAIN)
        val y = ys.takeLast(MAX_TRAIN)
        if (y.count { it == 1 } < MIN_EACH || y.count { it == 0 } < MIN_EACH) return null
        val d = ModelData.MEAN.size + DETAILS
        return fit(PROBLEM_CLASSES, x, y, Array(2) { DoubleArray(d) }, DoubleArray(2))
    }

    /**
     * A chance from 0 to 1, how common that was in the answers so far ("usual"), and how many answers it learned from.
     * A chance above the usual one means "more likely than usual". (This is the same as the balanced model saying yes.)
     */
    class Chance(val chance: Double, val usual: Double, val examples: Int) {
        fun higherThanUsual(): Boolean = chance > usual
    }

    /** The chance of a problem for one check, learned only from answers for earlier checks. */
    fun ownProblem(store: Store, r: CheckRecord): Chance? {
        val x = featuresOf(store, r) ?: return null
        val m = problemModel(store, r.id) ?: return null
        return Chance(m.chances(x)[1], m.usual(), m.examples)
    }

    // ---------- device alerts ----------

    /** True for the alerts about a change in bee sound ("quiet" or "loud"); the "nobees" and "hot" alerts have no distance from normal. */
    fun isSoundChange(type: String): Boolean = type == AlertItem.QUIET || type == AlertItem.LOUD

    /** Numbers that describe one alert: type, distance from normal, hour of the day, the hive's usual spread. */
    fun alertFeatures(type: String, z: Double, seconds: Long, spread: Double): DoubleArray {
        val c = Calendar.getInstance()
        c.timeInMillis = seconds * 1000L
        val hour = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0
        return doubleArrayOf(
            if (type == "quiet") 1.0 else 0.0,
            minOf(abs(z), 10.0) / 5.0,
            sin(2.0 * PI * hour / 24.0),
            cos(2.0 * PI * hour / 24.0),
            minOf(spread, 10.0) / 5.0
        )
    }

    private fun alertModelFrom(rows: List<JSONObject>): Model? {
        val xs = ArrayList<DoubleArray>()
        val ys = ArrayList<Int>()
        for (o in rows) {
            val label = textOf(o, "label")
            if (label != "real" && label != "false") continue
            if (!isSoundChange(textOf(o, "type"))) continue      // the checker learns only the "quiet" / "loud" alerts
            xs.add(alertFeatures(textOf(o, "type"), o.optDouble("z", 0.0), o.optLong("t", 0L), o.optDouble("sd", 0.0)))
            ys.add(if (label == "real") 1 else 0)
        }
        val x = xs.takeLast(MAX_TRAIN)
        val y = ys.takeLast(MAX_TRAIN)
        if (y.count { it == 1 } < MIN_EACH || y.count { it == 0 } < MIN_EACH) return null
        return fit(PROBLEM_CLASSES, x, y, Array(2) { DoubleArray(5) }, DoubleArray(2))
    }

    /** The app's own alert checker, trained on every alert the beekeeper marked as real or false. */
    fun alertModel(store: Store): Model? = alertModelFrom(store.alertLabels())

    /** The chance that an alert is real, from the given checker. */
    fun ownAlert(model: Model?, a: AlertItem): Chance? {
        if (model == null || !a.hasClock() || !isSoundChange(a.type)) return null
        return Chance(model.chances(alertFeatures(a.type, a.z, a.t, a.normalSd))[1], model.usual(), model.examples)
    }

    /** The chance that an alert is real, from a checker trained on the given (earlier) answered alerts. */
    fun alertChance(earlier: List<JSONObject>, a: AlertItem): Chance? = ownAlert(alertModelFrom(earlier), a)

    // ---------- frozen guesses ----------

    /**
     * The app's guesses for one check, kept the first time answers for it are saved and never changed after:
     * own (strength), ownN, fixed (70-hive model), common (answer given most often so far), last (last answer for
     * this hive), problem (chance), usual, problemN. Returns null while the check has no answers.
     * Safe to call many times and from any thread.
     */
    @Synchronized
    fun freezeGuess(store: Store, id: Long): JSONObject? {
        val r = store.check(id) ?: return null
        val info = store.learnInfo(id)
        val kept = info?.optJSONObject("guess")
        if (kept != null) return kept
        if (r.answers.isEmpty()) return null
        val g = guessNow(store, r)
        // a check made before this version was answered before any guess could be kept: shown, but never scored
        if (info == null || !info.has("details")) g.put("late", true)
        return store.keepGuessIfAbsent(id, g)
    }

    /** The guess that was kept for a check, or null. */
    fun frozenGuess(store: Store, id: Long): JSONObject? = store.learnInfo(id)?.optJSONObject("guess")

    /** Works out the guesses for one check from answers for earlier checks only. */
    fun guessNow(store: Store, r: CheckRecord): JSONObject {
        val g = JSONObject().put("t", System.currentTimeMillis())
        if (r.strength != null) g.put("fixed", r.strength)
        val earlier = store.allChecks().filter { it.id < r.id && judgement(it) != null }
        mostCommon(earlier)?.let { g.put("common", it) }
        earlier.lastOrNull { it.hive == r.hive }?.let { g.put("last", judgement(it)) }
        val own = ownStrength(store, r)
        if (own != null) g.put("own", own.first).put("ownN", own.second)
        val p = ownProblem(store, r)
        if (p != null) g.put("problem", p.chance).put("usual", p.usual).put("problemN", p.examples)
        return g
    }

    /** The strength answer given most often in these checks (null when there is none). */
    fun mostCommon(judged: List<CheckRecord>): String? {
        if (judged.isEmpty()) return null
        return STRENGTHS.maxByOrNull { s -> judged.count { judgement(it) == s } }
    }

    // ---------- honest scoreboard: predict first, then learn ----------

    /** How many predictions were right out of how many were made. */
    class Score(val right: Int, val total: Int)

    /**
     * Only frozen guesses are counted, and every model is compared with a simple guess on exactly the same checks:
     *  - all checks: own model, the 70-hive model, and "the answer you gave most often so far";
     *  - hives you answered for before: own model and "the same as you said last time for this hive";
     *  - hives you had not answered for before: own model and the 70-hive model.
     * Reading frozen guesses needs no training, so this is quick even with many checks.
     */
    class Scoreboard(
        val strengthAnswers: Int,
        val strengthCounts: Map<String, Int>,
        val own: Score,
        val fixed: Score,
        val mostCommon: Score,
        val repeatOwn: Score,
        val repeatLast: Score,
        val newOwn: Score,
        val newFixed: Score,
        val problemAnswers: Int,
        val problemsSeen: Int,
        val ownProblem: Score,
        val neverProblem: Score,       // always guessing "no problem", on the same checks
        val alertAnswers: Int,
        val realAlerts: Int,
        val ownAlert: Score,
        val alwaysReal: Score          // treating every device alert as real, on the same alerts
    )

    private class Tally {
        var right = 0
        var total = 0
        fun add(ok: Boolean) {
            total++
            if (ok) right++
        }
        fun score() = Score(right, total)
    }

    fun scoreboard(store: Store): Scoreboard {
        val checks = store.allChecks()

        // strength
        val judged = checks.filter { judgement(it) != null }
        val counts = LinkedHashMap<String, Int>()
        for (s in STRENGTHS) counts[s] = judged.count { judgement(it) == s }
        val own = Tally()
        val fixed = Tally()
        val common = Tally()
        val repeatOwn = Tally()
        val repeatLast = Tally()
        val newOwn = Tally()
        val newFixed = Tally()
        for (r in judged) {
            val truth = judgement(r) ?: continue
            val g = frozenGuess(store, r.id) ?: continue
            if (g.optBoolean("late")) continue
            val guess = textOf(g, "own")
            if (guess.isEmpty()) continue
            own.add(guess == truth)
            fixed.add(textOf(g, "fixed") == truth)
            common.add(textOf(g, "common") == truth)
            val last = textOf(g, "last")
            if (last.isNotEmpty()) {
                repeatOwn.add(guess == truth)
                repeatLast.add(last == truth)
            } else {
                newOwn.add(guess == truth)
                newFixed.add(textOf(g, "fixed") == truth)
            }
        }

        // problems: the app "says problem" when the chance was higher than usual
        val withProblemAnswer = checks.filter { problemLabel(it.answers) != null }
        val pOwn = Tally()
        val pNever = Tally()
        for (r in withProblemAnswer) {
            val truth = problemLabel(r.answers) ?: continue
            val g = frozenGuess(store, r.id) ?: continue
            if (g.optBoolean("late") || !g.has("problem")) continue
            val says = g.optDouble("problem", 0.0) > g.optDouble("usual", 1.0)
            pOwn.add((if (says) 1 else 0) == truth)
            pNever.add(truth == 0)
        }

        // device alerts: the guess kept when the answer was first saved, from earlier alerts only
        val rows = store.alertLabels().filter { textOf(it, "label") == "real" || textOf(it, "label") == "false" }
        val aOwn = Tally()
        val aReal = Tally()
        for (o in rows) {
            if (!o.has("guess")) continue
            val truth = textOf(o, "label") == "real"
            aOwn.add((o.optDouble("guess", 0.0) > o.optDouble("usual", 1.0)) == truth)
            aReal.add(truth)
        }

        return Scoreboard(
            judged.size, counts, own.score(), fixed.score(), common.score(),
            repeatOwn.score(), repeatLast.score(), newOwn.score(), newFixed.score(),
            withProblemAnswer.size, withProblemAnswer.count { problemLabel(it.answers) == 1 }, pOwn.score(), pNever.score(),
            rows.size, rows.count { textOf(it, "label") == "real" }, aOwn.score(), aReal.score()
        )
    }
}
