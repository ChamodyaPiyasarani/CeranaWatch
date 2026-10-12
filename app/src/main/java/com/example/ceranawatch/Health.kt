package com.example.ceranawatch

/**
 * One clear health status for a hive, from simple rules (no new model):
 *
 *  PROBLEM  - the answers of the latest inspection show a problem (no eggs or young larvae, wasps,
 *             wax moth, bees leaving, no food). The problem is named.
 *  LOOK     - no problem was answered, but the sound changed from the hive's own normal, the last check
 *             was very quiet, or a device alert is waiting.
 *  HEALTHY  - the latest inspection had all five answers good, the latest sound comparison was "own normal",
 *             the last check was not very quiet and no device alert is waiting.
 *  UNKNOWN  - not enough information yet.
 *
 * "Healthy" is shown only when the beekeeper's own inspection supports it. Sound and the hive device can only
 * ask for a look; they never say a hive is healthy or sick on their own.
 * An inspection is a check with at least one of the five questions answered.
 */
object HiveHealth {
    const val PROBLEM = "PROBLEM"
    const val LOOK = "LOOK"
    const val HEALTHY = "HEALTHY"
    const val UNKNOWN = "UNKNOWN"

    /** An inspection older than this is still shown, with a reminder to inspect again. */
    const val OLD_INSPECTION_DAYS = 14

    class Status(
        val level: String,
        val reasons: List<String>,      // short reasons in the chosen language, most important first
        val inspectedAt: Long?,         // time of the latest inspection (milliseconds), or null
        val inspectionId: Long?         // id of the check that holds that inspection
    )

    /** The answer that means "all good" for each of the five questions. */
    private val GOOD = linkedMapOf(
        "brood" to Advice.YES,
        "wasps" to Advice.NO,
        "moth" to Advice.NO,
        "leaving" to Advice.NO,
        "food" to Advice.YES
    )

    /** True when at least one of the five questions was answered for this check. */
    fun isInspection(r: CheckRecord): Boolean = Advice.QUESTIONS.any { r.answers.containsKey(it.id) }

    /** True when all five answers are the good ones. */
    fun allGood(answers: Map<String, String>): Boolean = GOOD.all { (question, good) -> answers[question] == good }

    /** Short names of the problems the answers show, in the order of the questions. */
    fun problems(answers: Map<String, String>, sinhala: Boolean): List<String> {
        fun pick(english: String, sinhalaText: String) = if (sinhala) sinhalaText else english
        val out = ArrayList<String>()
        if (answers["brood"] == Advice.NO) out.add(
            pick("No eggs or young larvae: possible queen problem.", "බිත්තර හරි පොඩි පැටව් හරි නැහැ: රැජින ගැන ප්‍රශ්නයක් වෙන්න පුළුවන්.")
        )
        if (answers["wasps"] == Advice.YES) out.add(pick("Wasps or hornets attacking.", "දෙබරු පහර දෙනවා."))
        if (answers["moth"] == Advice.YES) out.add(pick("Possible wax moth.", "ඉටි සලබයා වෙන්න පුළුවන්."))
        if (answers["leaving"] == Advice.YES) out.add(
            pick("Far fewer bees, or bees leaving the hive.", "මී මැස්සෝ ගොඩක් අඩුයි, නැත්නම් පෙට්ටිය දාලා යනවා.")
        )
        if (answers["food"] == Advice.NO) out.add(pick("Low food stores.", "පෙට්ටියේ කෑම අඩුයි."))
        return out
    }

    /**
     * Device alerts of this hive that still ask for a look: not marked as a false alarm, not yet seen
     * (or seen and marked as real), and newer than the latest inspection.
     */
    fun waitingAlerts(alerts: List<AlertItem>, hive: String, inspectedAt: Long?): List<AlertItem> = alerts.filter { a ->
        a.hive.equals(hive, ignoreCase = true) &&
            a.userLabel != "false" &&
            (!a.ack || a.userLabel == "real") &&
            (inspectedAt == null || !a.hasClock() || a.t * 1000L > inspectedAt)
    }

    /**
     * The status of one hive.
     * checks = this hive's checks, newest first. alerts = the device alerts kept on the phone (any hive).
     */
    fun of(checks: List<CheckRecord>, alerts: List<AlertItem>, hive: String, sinhala: Boolean): Status {
        fun pick(english: String, sinhalaText: String) = if (sinhala) sinhalaText else english

        val inspection = checks.firstOrNull { isInspection(it) }
        val inspectedAt = inspection?.id
        val newest = checks.firstOrNull()
        // the latest check that was compared with the hive's own normal
        val compared = checks.firstOrNull { it.normalStatus == Store.NORMAL || it.normalStatus == Store.CHANGED }
        val waiting = waitingAlerts(alerts, hive, inspectedAt)

        // 1. a problem answered at the latest inspection
        if (inspection != null) {
            val found = problems(inspection.answers, sinhala)
            if (found.isNotEmpty()) return Status(PROBLEM, found, inspectedAt, inspection.id)
        }

        // 2. signs that ask the beekeeper to look
        val look = ArrayList<String>()
        if (compared != null && compared.normalStatus == Store.CHANGED) look.add(
            pick(
                "The sound changed from this hive's own normal. Open the hive and look.",
                "ශබ්දය මේ පෙට්ටියේ සුපුරුදු ශබ්දයට වඩා වෙනස් වෙලා. පෙට්ටිය ඇරලා බලන්න."
            )
        )
        if (newest != null && newest.outcome == Analyzer.QUIET) look.add(
            pick(
                "Very little sound at the last check. The colony may have left.",
                "අන්තිම පරීක්ෂාවේදී ශබ්දය ගොඩක් අඩුයි. මී ජනපදය පෙට්ටිය දාලා ගිහින් වෙන්න පුළුවන්."
            )
        )
        if (waiting.isNotEmpty()) look.add(
            pick(
                if (waiting.size == 1) "1 device alert is waiting for you." else "${waiting.size} device alerts are waiting for you.",
                "උපකරණයෙන් ආපු අනතුරු ඇඟවීම් ${waiting.size}ක් බලන්න තියෙනවා."
            )
        )
        if (look.isNotEmpty()) return Status(LOOK, look, inspectedAt, inspection?.id)

        // 3. healthy only when the beekeeper's inspection supports it and the sound is like its own normal
        if (inspection != null && allGood(inspection.answers) && compared != null && compared.normalStatus == Store.NORMAL) {
            val why = arrayListOf(
                pick("All five answers were good.", "ප්‍රශ්න පහටම හොඳ පිළිතුරු."),
                pick("The sound is like this hive's own normal.", "ශබ්දය මේ පෙට්ටියේ සුපුරුදු ශබ්දය වගේමයි."),
                pick("No device alerts are waiting.", "බලන්න තියෙන අනතුරු ඇඟවීම් නැහැ.")
            )
            if (daysAgo(inspection.id) > OLD_INSPECTION_DAYS) why.add(
                pick(
                    "The last inspection was more than 2 weeks ago. Inspect again soon.",
                    "අන්තිම පරීක්ෂාව සති 2කට වඩා කලින්. ඉක්මනින් ආයෙත් පරීක්ෂා කරන්න."
                )
            )
            return Status(HEALTHY, why, inspectedAt, inspection.id)
        }

        // 4. not enough information yet: say what is missing
        val missing = ArrayList<String>()
        if (checks.isEmpty()) {
            missing.add(pick("No checks yet. Check this hive first.", "තවම පරීක්ෂා කරලා නැහැ. මුලින්ම මේ පෙට්ටිය පරීක්ෂා කරන්න."))
        } else {
            if (inspection == null) missing.add(
                pick("Answer the five questions after a check.", "පරීක්ෂාවකට පස්සේ ප්‍රශ්න පහට පිළිතුරු දෙන්න.")
            )
            else if (!allGood(inspection.answers)) missing.add(
                pick("Some answers were 'not sure' or missing.", "සමහර ප්‍රශ්නවලට 'හරියටම දන්නේ නැහැ' කියලා, නැත්නම් පිළිතුරක් නැහැ.")
            )
            if (compared == null) {
                val good = checks.count { it.shape != null }
                missing.add(
                    if (good < Store.BASELINE_CHECKS) pick(
                        "Still learning this hive's normal sound (good checks $good of ${Store.BASELINE_CHECKS}).",
                        "මේ පෙට්ටියේ සුපුරුදු ශබ්දය තවම ඉගෙන ගන්නවා (හොඳ පරීක්ෂා ${Store.BASELINE_CHECKS}න් $good)."
                    ) else pick(
                        "Do one more check to compare the sound with its own normal.",
                        "සුපුරුදු ශබ්දය එක්ක ගළපන්න තව එක පරීක්ෂාවක් කරන්න."
                    )
                )
            }
        }
        return Status(UNKNOWN, missing, inspectedAt, inspection?.id)
    }

    /** The status word for the assistant (always English). */
    fun word(level: String): String = when (level) {
        PROBLEM -> "problem found"
        LOOK -> "needs a look"
        HEALTHY -> "healthy at last inspection"
        else -> "not enough information yet"
    }
}
