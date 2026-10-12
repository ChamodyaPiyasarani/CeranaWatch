package com.example.ceranawatch

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val SINHALA_MONTHS = arrayOf(
    "ජනවාරි", "පෙබරවාරි", "මාර්තු", "අප්‍රේල්", "මැයි", "ජූනි",
    "ජූලි", "අගෝස්තු", "සැප්තැම්බර්", "ඔක්තෝබර්", "නොවැම්බර්", "දෙසැම්බර්"
)

/** How many calendar days ago a time was: 0 = today, 1 = yesterday. */
fun daysAgo(millis: Long): Int {
    val today = Calendar.getInstance()
    val then = Calendar.getInstance()
    then.timeInMillis = millis
    for (c in listOf(today, then)) {
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
    }
    return Math.round((today.timeInMillis - then.timeInMillis) / 86_400_000.0).toInt()
}

/** "Today, 14:05", "Yesterday, 14:05" or "6 Oct, 14:05" (in Sinhala when asked). */
fun dayAndTime(millis: Long, sinhala: Boolean): String {
    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))
    return when (daysAgo(millis)) {
        0 -> (if (sinhala) "අද " else "Today, ") + time
        1 -> (if (sinhala) "ඊයේ " else "Yesterday, ") + time
        else -> if (sinhala) {
            val c = Calendar.getInstance()
            c.timeInMillis = millis
            SINHALA_MONTHS[c.get(Calendar.MONTH)] + " " + c.get(Calendar.DAY_OF_MONTH) + ", " + time
        } else SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date(millis))
    }
}

/** A network problem in simple words, in English or in Sinhala. */
fun netErrorText(e: Exception, sinhala: Boolean): String = when (e) {
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
        if (sinhala) "ඉන්ටර්නෙට් නැහැ." else "No internet connection."
    is java.net.SocketTimeoutException ->
        if (sinhala) "ඉන්ටර්නෙට් ගොඩක් හෙමින්. ආයෙත් උත්සාහ කරන්න." else "The connection is too slow. Please try again."
    is java.net.MalformedURLException ->
        if (sinhala) "ලිපිනය වැරදියි. සැකසුම්වල Firebase ලිපිනය බලන්න." else "The address is not right. Check the Firebase address in Settings."
    else -> e.message ?: (if (sinhala) "මොකක් හරි වැරදීමක් වුණා." else "Something went wrong.")
}

/** Reads a text value; gives "" when the value is missing or null. */
fun textOf(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key, "")

/** One reading that the hive device sent. */
class NodeReading(
    val key: String,
    val t: Long,                 // time in seconds (0 when the device had no clock)
    val lowDb: Double,           // loudness of the bee band (100 to 600 Hz)
    val highDb: Double,          // loudness of the higher band
    val rmsDb: Double,
    val z: Double,               // how far lowDb is from this hive's normal, in standard deviations
    val ready: Boolean,          // true when the device has finished learning the normal
    val normalCount: Int,
    val normalNeeded: Int,
    val normalDb: Double,
    val normalSd: Double,
    val everySeconds: Int,
    val sound: String = "",      // "bee", "other" or "silent" from the device's bee detector ("" = old device program)
    val beeProb: Double = -1.0,  // the detector's chance that the sound was bees, 0 to 1 (-1 = not known)
    val tempC: Double? = null,   // temperature inside the hive from the DHT22 (null = no sensor answer)
    val humidity: Double? = null,// humidity inside the hive in % (null = no sensor answer)
    val dhtOk: Boolean = false,  // true when the DHT22 answered this time
    val noBeeRun: Int = 0,       // readings in a row without bee sound
    val fw: String = "",         // the device program version ("2" or newer has the bee detector and DHT22)
    val beeNow: Boolean? = null, // version 2.1: did this one reading pass as bees? (null = older program)
    val passes: Int = 0,         // version 2.1: readings of the last 4 that passed (3 of 4 = bees)
    val teach: String = "",      // version 2.1: "room 4/10" or "hive 4/10" while the device is being taught
    val taughtRoom: Int = 0,     // version 2.1: windows taught as "no bees here"
    val taughtHive: Int = 0,     // version 2.1: windows taught as "bees here"
    val model: String = "",      // the bee detector the device uses
    val range: String = "",      // version 2.2: listening range "auto", "near" (hive only) or "all" ("" = older program)
    val nearDb: Double? = null   // version 2.2: sound quieter than this counts as far away (not inside a hive)
) {
    companion object {
        const val LEARNING = "LEARNING"
        const val NORMAL = "NORMAL"
        const val UNUSUAL = "UNUSUAL"
        const val NO_BEES = "NO_BEES"       // the bee detector did not hear bees in this reading
        const val NO_CHECK = "NO_CHECK"     // an old device program that cannot tell bee sound from other sound
        const val Z_LIMIT = 3.0
        const val HOT_C = 40.0              // the same limit as the device's "hot" alert

        /** A number, or null when it is missing, null or not a real number. */
        private fun numberOrNull(o: JSONObject, key: String): Double? {
            if (!o.has(key) || o.isNull(key)) return null
            val v = o.optDouble(key, Double.NaN)
            return if (v.isNaN() || v.isInfinite()) null else v
        }

        fun from(key: String, o: JSONObject): NodeReading {
            val temp = numberOrNull(o, "tempC")
            val hum = numberOrNull(o, "humidity")
            return NodeReading(
                key = key,
                t = o.optLong("t", 0L),
                lowDb = o.optDouble("lowDb", 0.0),
                highDb = o.optDouble("highDb", 0.0),
                rmsDb = o.optDouble("rmsDb", 0.0),
                z = o.optDouble("z", 0.0),
                ready = o.optBoolean("ready", false),
                normalCount = o.optInt("normalCount", 0),
                normalNeeded = o.optInt("normalNeeded", 0),
                normalDb = o.optDouble("normalDb", 0.0),
                normalSd = o.optDouble("normalSd", 0.0),
                everySeconds = o.optInt("everySeconds", 300),
                sound = textOf(o, "sound").lowercase(Locale.US),
                beeProb = numberOrNull(o, "beeProb") ?: -1.0,
                tempC = temp,
                humidity = hum,
                dhtOk = o.optBoolean("dhtOk", temp != null),
                noBeeRun = o.optInt("noBeeRun", 0),
                fw = textOf(o, "fw"),
                beeNow = if (o.has("beeNow") && !o.isNull("beeNow")) o.optBoolean("beeNow") else null,
                passes = o.optInt("passes", 0),
                teach = textOf(o, "teach"),
                taughtRoom = o.optInt("taughtRoom", 0),
                taughtHive = o.optInt("taughtHive", 0),
                model = textOf(o, "model"),
                range = textOf(o, "range"),
                nearDb = numberOrNull(o, "nearDb")
            )
        }
    }

    fun hasClock(): Boolean = t > 1_600_000_000L

    fun ageSeconds(): Long = if (hasClock()) (System.currentTimeMillis() / 1000L - t).coerceAtLeast(0L) else -1L

    /** The device counts as online when its last reading is not older than three reading periods. */
    fun online(): Boolean = hasClock() && ageSeconds() <= maxOf(3L * everySeconds, 180L)

    /** True when the device program has the bee detector (version 2 or newer). */
    fun checksBees(): Boolean = sound.isNotEmpty()

    /** True only when the device's bee detector heard bees. Old device programs never count as bee sound. */
    fun isBee(): Boolean = sound == "bee"

    fun isSilent(): Boolean = sound == "silent"

    /** Version 2.2: the sound was quieter than the listening range, so it is treated as far away. */
    fun isFar(): Boolean = sound == "far"

    /** True when the device program has a listening range (version 2.2 or newer). */
    fun hasRange(): Boolean = range.isNotEmpty()

    fun hasTemp(): Boolean = tempC != null

    /** True when the device program can be taught its place (version 2.1 or newer). */
    fun canTeach(): Boolean = beeNow != null

    fun teaching(): Boolean = teach.isNotEmpty()

    fun isHot(): Boolean = (tempC ?: 0.0) >= HOT_C

    fun status(): String = when {
        !checksBees() -> NO_CHECK
        !isBee() -> NO_BEES
        !ready -> LEARNING
        Math.abs(z) > Z_LIMIT -> UNUSUAL
        else -> NORMAL
    }

    fun timeText(): String = if (hasClock()) Cloud.timeText(t) else "?"

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("time", timeText())
            .put("minutes_ago", if (hasClock()) ageSeconds() / 60 else JSONObject.NULL)
            .put(
                "sound_heard", when {
                    !checksBees() -> "unknown (old device program without the bee detector)"
                    isBee() -> "bee sound"
                    isSilent() -> "silent"
                    isFar() -> "only far or quiet sound (quieter than the device's listening range, so not from inside a hive)"
                    else -> "other sound, not bees"
                }
            )
            .put("bee_chance", if (beeProb >= 0.0) Math.round(beeProb * 100.0) / 100.0 else JSONObject.NULL)
            .put("readings_in_a_row_without_bees", noBeeRun)
            .put("this_reading_passed_as_bees", beeNow ?: JSONObject.NULL)
            .put("readings_of_last_4_that_passed", if (beeNow != null) passes else JSONObject.NULL)
            .put("teaching_now", if (teach.isNotEmpty()) teach else JSONObject.NULL)
            .put("taught_windows_no_bees", taughtRoom)
            .put("taught_windows_bees", taughtHive)
            .put("listening_range", if (range.isNotEmpty()) range + " (" + (nearDb?.let { Math.round(it).toString() + " dB" } ?: "?") + ")" else JSONObject.NULL)
            .put("bee_band_loudness_db", if (isBee()) lowDb else JSONObject.NULL)
            .put("overall_loudness_db", rmsDb)
            .put("z_score", if (isBee() && ready) z else JSONObject.NULL)
            .put("temperature_c", tempC ?: JSONObject.NULL)
            .put("humidity_percent", humidity ?: JSONObject.NULL)
            .put("status", status())
        return o
    }
}

/** One alert that the hive device raised. */
class AlertItem(
    val id: String,
    val hive: String,
    val t: Long,
    val type: String,            // "quiet", "loud", "nobees" or "hot"
    val message: String,
    val lowDb: Double,
    val z: Double,
    val normalDb: Double,
    val normalSd: Double,
    val ack: Boolean,            // the beekeeper has seen it
    val userLabel: String,       // "real", "false" or ""  (what the beekeeper found)
    val reviewLabel: String,     // "likely_real", "likely_false", "unsure" or ""  (the assistant's review)
    val reviewText: String,
    val escalated: Boolean,      // the second contact has been told
    val escalateReason: String,
    val tempC: Double? = null    // temperature inside the hive when the alert was raised (device version 2)
) {
    companion object {
        const val QUIET = "quiet"
        const val LOUD = "loud"
        const val NO_BEES = "nobees"
        const val HOT = "hot"

        fun from(id: String, o: JSONObject): AlertItem {
            val review = o.optJSONObject("review") ?: JSONObject()
            val temp = if (!o.has("tempC") || o.isNull("tempC")) null else o.optDouble("tempC", Double.NaN).takeIf { !it.isNaN() && !it.isInfinite() }
            return AlertItem(
                id = id,
                hive = textOf(o, "hive"),
                t = o.optLong("t", 0L),
                type = textOf(o, "type"),
                message = textOf(o, "message"),
                lowDb = o.optDouble("lowDb", 0.0),
                z = o.optDouble("z", 0.0),
                normalDb = o.optDouble("normalDb", 0.0),
                normalSd = o.optDouble("normalSd", 0.0),
                ack = o.optBoolean("ack", false),
                userLabel = textOf(o, "userLabel"),
                reviewLabel = textOf(review, "label"),
                reviewText = textOf(review, "explanation"),
                escalated = o.optBoolean("escalated", false),
                escalateReason = textOf(o, "escalateReason"),
                tempC = temp
            )
        }

        /** Reads the list that was kept on the phone for offline use. */
        fun listFromSaved(text: String): List<AlertItem> {
            val out = ArrayList<AlertItem>()
            try {
                if (text.isBlank()) return out
                val a = JSONArray(text)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    out.add(from(o.optString("id"), o))
                }
            } catch (_: Exception) {
            }
            return out
        }

        fun listToSaved(list: List<AlertItem>): String {
            val a = JSONArray()
            for (x in list) a.put(x.toStored())
            return a.toString()
        }
    }

    fun hasClock(): Boolean = t > 1_600_000_000L

    fun ageMinutes(): Long = if (hasClock()) ((System.currentTimeMillis() / 1000L - t) / 60L).coerceAtLeast(0L) else -1L

    fun timeText(): String = if (hasClock()) Cloud.timeText(t) else "?"

    private fun toStored(): JSONObject = JSONObject()
        .put("id", id).put("hive", hive).put("t", t).put("type", type).put("message", message)
        .put("lowDb", lowDb).put("z", z).put("normalDb", normalDb).put("normalSd", normalSd)
        .put("ack", ack).put("userLabel", userLabel)
        .put("review", JSONObject().put("label", reviewLabel).put("explanation", reviewText))
        .put("escalated", escalated).put("escalateReason", escalateReason)
        .put("tempC", tempC ?: JSONObject.NULL)

    /** What this kind of alert means, in plain English (for the assistant). */
    fun meaning(): String = when (type) {
        QUIET -> "The bee sound was much quieter than this hive's normal, two readings in a row."
        LOUD -> "The bee sound was much louder than this hive's normal."
        NO_BEES -> "The device's bee detector heard no bee sound several readings in a row, although it heard bees at this hive before. The colony may have left, or the device may have been moved or blocked."
        HOT -> "The temperature inside the hive was 40 °C or more, two readings in a row."
        else -> "An alert from the hive device."
    }

    /** What the assistant is given about this alert. */
    fun toJson(): JSONObject = JSONObject()
        .put("alert_id", id)
        .put("hive", hive)
        .put("time", timeText())
        .put("minutes_ago", if (hasClock()) ageMinutes() else JSONObject.NULL)
        .put("type", type)
        .put("meaning", meaning())
        .put("temperature_c", tempC ?: JSONObject.NULL)
        .put("bee_band_loudness_db", lowDb)
        .put("hive_normal_db", normalDb)
        .put("hive_normal_spread_db", normalSd)
        .put("z_score", z)
        .put("seen_by_beekeeper", ack)
        .put("beekeeper_label", if (userLabel.isEmpty()) "none yet" else userLabel)
        .put("assistant_review", if (reviewLabel.isEmpty()) "none yet" else reviewLabel)
        .put("second_contact_told", escalated)
}

/**
 * The link to the cloud (Firebase Realtime Database) and to the weather service.
 * Every function here needs internet and blocks, so call it from a background thread.
 */
class Cloud(private val store: Store) {

    companion object {
        fun timeText(seconds: Long): String =
            SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date(seconds * 1000L))

        private const val NEWEST = "orderBy=%22%24key%22&limitToLast="
    }

    /** Only for tests: another address for the weather service. */
    var weatherUrl = "https://api.open-meteo.com/v1/forecast"

    fun ready(): Boolean = store.firebaseUrl().startsWith("http")

    // ---------- the hive device ----------

    /** The latest reading of every device, by hive name. */
    fun nodes(): Map<String, NodeReading> {
        val out = LinkedHashMap<String, NodeReading>()
        val root = getObject("/nodes.json") ?: return out
        for (hive in sortedKeys(root)) {
            val latest = root.optJSONObject(hive)?.optJSONObject("latest") ?: continue
            out[hive] = NodeReading.from(hive, latest)
        }
        return out
    }

    fun latest(hive: String): NodeReading? {
        val o = getObject("/nodes/" + safe(hive) + "/latest.json") ?: return null
        return NodeReading.from(hive, o)
    }

    /** The last readings of one device, oldest first. */
    fun readings(hive: String, count: Int): List<NodeReading> {
        val root = getObject("/readings/" + safe(hive) + ".json?" + NEWEST + count.coerceIn(1, 500)) ?: return emptyList()
        val out = ArrayList<NodeReading>()
        for (k in sortedKeys(root)) {
            val o = root.optJSONObject(k) ?: continue
            out.add(NodeReading.from(k, o))
        }
        return out
    }

    /** Tells the device to forget its normal and learn it again. The device reads this on its next cycle. */
    fun resetNormal(hive: String) = sendCommand(hive, "reset")

    /**
     * A command for the device, read on its next cycle: "reset", "teach_room" (no bees here),
     * "teach_hive" (bees here) or "teach_forget".
     */
    fun sendCommand(hive: String, command: String) {
        send("PUT", "/nodes/" + safe(hive) + "/command.json", JSONObject.quote(command))
    }

    // ---------- alerts ----------

    /** The last alerts, newest first. */
    fun alerts(count: Int): List<AlertItem> {
        val root = getObject("/alerts.json?" + NEWEST + count.coerceIn(1, 200)) ?: return emptyList()
        val out = ArrayList<AlertItem>()
        for (k in sortedKeys(root).reversed()) {
            val o = root.optJSONObject(k) ?: continue
            out.add(AlertItem.from(k, o))
        }
        return out
    }

    fun acknowledge(id: String) {
        send("PUT", "/alerts/" + safe(id) + "/ack.json", "true")
        send("PUT", "/alerts/" + safe(id) + "/ackTime.json", (System.currentTimeMillis() / 1000L).toString())
    }

    /** Marks several alerts as seen. */
    fun acknowledgeAll(ids: List<String>) {
        for (id in ids) acknowledge(id)
    }

    /** The beekeeper's own finding after looking at the hive: "real" or "false". */
    fun setUserLabel(id: String, label: String) {
        send("PUT", "/alerts/" + safe(id) + "/userLabel.json", JSONObject.quote(if (label == "real") "real" else "false"))
    }

    /** The assistant's review of an alert. */
    fun saveReview(id: String, label: String, explanation: String, model: String) {
        val body = JSONObject()
            .put("label", label)
            .put("explanation", explanation)
            .put("model", model)
            .put("t", System.currentTimeMillis() / 1000L)
        send("PUT", "/alerts/" + safe(id) + "/review.json", body.toString())
    }

    /** Marks an alert so that the second contact's phone shows it. */
    fun escalate(id: String, reason: String) {
        send("PUT", "/alerts/" + safe(id) + "/escalated.json", "true")
        send("PUT", "/alerts/" + safe(id) + "/escalateReason.json", JSONObject.quote(reason.take(300)))
        send("PUT", "/alerts/" + safe(id) + "/escalateTime.json", (System.currentTimeMillis() / 1000L).toString())
    }

    // ---------- messages for the second contact ----------

    fun sendMessage(text: String, from: String) {
        val body = JSONObject()
            .put("t", System.currentTimeMillis() / 1000L)
            .put("text", text.take(500))
            .put("from", from)
        send("POST", "/messages.json", body.toString())
    }

    /** The last messages, newest first: id, time in seconds and text. */
    fun messages(count: Int): List<Triple<String, Long, String>> {
        val root = getObject("/messages.json?" + NEWEST + count.coerceIn(1, 50)) ?: return emptyList()
        val out = ArrayList<Triple<String, Long, String>>()
        for (k in sortedKeys(root).reversed()) {
            val o = root.optJSONObject(k) ?: continue
            out.add(Triple(k, o.optLong("t", 0L), textOf(o, "text")))
        }
        return out
    }

    // ---------- copies of app data ----------

    /** Keeps a copy of one hive check in the cloud. The sound itself is not sent. */
    fun uploadCheck(r: CheckRecord, details: JSONObject, info: JSONObject? = null) {
        val o = JSONObject()
            .put("t", r.id / 1000L)
            .put("hive", r.hive)
            .put("outcome", r.outcome)
            .put("strength", r.strength ?: JSONObject.NULL)
            .put("agree", r.agree)
            .put("cleanClips", r.cleanClips)
            .put("totalClips", r.totalClips)
            .put("rmsDb", Math.round(r.rmsDb * 10.0) / 10.0)
            .put("micMode", r.micMode)
            .put("normalStatus", r.normalStatus)
            .put("normalDistance", r.normalDistance ?: JSONObject.NULL)
        val votes = JSONObject()
        for ((k, v) in r.votes) votes.put(k, v)
        o.put("votes", votes)
        val answers = JSONObject()
        for ((k, v) in r.answers) answers.put(k, v)
        o.put("answers", answers)
        val d = JSONObject(details.toString())
        d.remove("notes")
        o.put("details", d)
        // why parts of the sound were thrown away (silence, the basic rule, the bee-sound filter)
        if (info != null && info.has("filter")) {
            val sorted = JSONObject()
                .put("filter", info.optString("filter"))
                .put("filterClips", info.optInt("filterClips", 0))
            if (info.has("ruleClips")) sorted.put("ruleClips", info.optInt("ruleClips", 0))
            if (info.has("silentClips")) sorted.put("silentClips", info.optInt("silentClips", 0))
            o.put("thrownAway", sorted)
        }
        send("PUT", "/checks/" + safe(r.hive) + "/" + r.id + ".json", o.toString())
    }

    /** Marks the cloud copy of a check as deleted in the app. The copy itself stays, so no research data is lost. */
    fun markCheckDeleted(hive: String, id: Long) {
        send("PUT", "/checks/" + safe(hive) + "/" + id + "/deletedInApp.json", "true")
    }

    /** Keeps a copy of one assistant step in the cloud. */
    fun uploadAgentLog(entry: JSONObject) {
        send("POST", "/agentlog.json", entry.toString())
    }

    /** Tries to read the database. Returns "" when it works, or a text that says what is wrong. */
    fun test(): String = try {
        send("GET", "/nodes.json?shallow=true", null)
        ""
    } catch (e: Exception) {
        netErrorText(e, store.sinhala())
    }

    // ---------- weather ----------

    /** The weather now at the place saved in Settings. */
    fun weather(): JSONObject {
        val parts = store.location().split(",").map { it.trim() }
        val lat = parts.getOrNull(0)?.toDoubleOrNull()
        val lon = parts.getOrNull(1)?.toDoubleOrNull()
        if (lat == null || lon == null || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw IOException(
                if (store.sinhala()) "මී පෙට්ටි තියෙන තැන දාලා නැහැ. සැකසුම්වලට ගිහින් දිස්ත්‍රික්කය තෝරන්න."
                else "The place of the apiary is not set. Open Settings and choose your district."
            )
        }
        val address = weatherUrl + "?latitude=" + lat + "&longitude=" + lon +
            "&current=temperature_2m,relative_humidity_2m,precipitation,wind_speed_10m,weather_code" +
            "&daily=precipitation_sum,precipitation_probability_max&forecast_days=1&timezone=auto"
        val root = JSONObject(http("GET", address, null, false))
        val now = root.optJSONObject("current") ?: throw IOException(
            if (store.sinhala()) "කාලගුණ සේවාවෙන් තොරතුරු ආවේ නැහැ." else "The weather service gave no data."
        )
        val code = now.optInt("weather_code", -1)
        val out = JSONObject()
            .put("temperature_c", now.optDouble("temperature_2m"))
            .put("humidity_percent", now.optDouble("relative_humidity_2m"))
            .put("rain_now_mm", now.optDouble("precipitation"))
            .put("wind_kmh", now.optDouble("wind_speed_10m"))
            .put("sky", skyWord(code))
        val day = root.optJSONObject("daily")
        if (day != null) {
            val sum = day.optJSONArray("precipitation_sum")
            val chance = day.optJSONArray("precipitation_probability_max")
            if (sum != null && sum.length() > 0 && !sum.isNull(0)) out.put("rain_today_mm", sum.optDouble(0))
            if (chance != null && chance.length() > 0 && !chance.isNull(0)) out.put("rain_chance_today_percent", chance.optDouble(0))
        }
        return out
    }

    private fun skyWord(code: Int): String = when (code) {
        0 -> "clear"
        1, 2 -> "partly cloudy"
        3 -> "cloudy"
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67 -> "rain"
        80, 81, 82 -> "rain showers"
        95, 96, 99 -> "thunderstorm"
        else -> "unknown"
    }

    // ---------- network ----------

    private fun safe(name: String): String =
        name.filter { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun sortedKeys(o: JSONObject): List<String> {
        val keys = ArrayList<String>()
        val it = o.keys()
        while (it.hasNext()) keys.add(it.next())
        keys.sort()
        return keys
    }

    private fun getObject(path: String): JSONObject? {
        val text = send("GET", path, null).trim()
        if (text.isEmpty() || text == "null" || !text.startsWith("{")) return null
        return JSONObject(text)
    }

    private fun send(method: String, path: String, body: String?): String {
        if (!ready()) throw IOException(
            if (store.sinhala()) "Firebase ලිපිනය දාලා නැහැ. සැකසුම්වලට ගිහින් ඒක දාන්න."
            else "The Firebase address is not set. Open Settings and add it."
        )
        return http(method, store.firebaseUrl() + path, body, true)
    }

    private fun http(method: String, address: String, body: String?, firebase: Boolean): String {
        val conn = URL(address).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val si = store.sinhala()
                if (!firebase) throw IOException(
                    if (si) "කාලගුණ සේවාවේ ප්‍රශ්නයක් (code $code). ටිකකින් ආයෙත් උත්සාහ කරන්න."
                    else "The weather service had a problem (code $code). Try again in a moment."
                )
                throw IOException(
                    when (code) {
                        401, 403 -> if (si) "Firebase මේකට ඉඩ දුන්නේ නැහැ (code $code). Firebase එකේ database rules බලන්න."
                        else "Firebase did not allow this (code $code). Check the database rules in Firebase."
                        404 -> if (si) "ඒ ලිපිනය හොයාගන්න බැහැ (code 404). සැකසුම්වල Firebase ලිපිනය බලන්න."
                        else "The address was not found (code 404). Check the Firebase address in Settings."
                        else -> if (si) "Firebase සේවාවේ ප්‍රශ්නයක් (code $code). ටිකකින් ආයෙත් උත්සාහ කරන්න."
                        else "The online service had a problem (code $code). Try again in a moment."
                    }
                )
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
