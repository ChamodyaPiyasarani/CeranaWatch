package com.example.ceranawatch

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved hive check. */
class CheckRecord(
    val id: Long,                    // time of the check in milliseconds
    val hive: String,
    val outcome: String,             // READING, UNCLEAR, QUIET or NOISY
    val strength: String?,
    val agree: Int,
    val cleanClips: Int,
    val totalClips: Int,
    val rmsDb: Double,
    val micMode: String,
    val wavName: String,
    val shape: DoubleArray?,         // bee-band shape of this check (null when too noisy or quiet)
    val normalStatus: String,        // LEARNING, NORMAL, CHANGED or NONE
    val normalDistance: Double?,
    val normalCount: Int,            // how many checks the "own normal" is built from so far
    val answers: Map<String, String>,
    val suggestions: List<String>,
    val votes: Map<String, Int> = emptyMap()   // how many clean clips voted for each strength
) {
    fun withAnswers(newAnswers: Map<String, String>, newSuggestions: List<String>) = CheckRecord(
        id, hive, outcome, strength, agree, cleanClips, totalClips, rmsDb, micMode, wavName,
        shape, normalStatus, normalDistance, normalCount, newAnswers, newSuggestions, votes
    )

    /** The same check under another hive name (used when a hive is renamed). */
    fun withHive(newHive: String) = CheckRecord(
        id, newHive, outcome, strength, agree, cleanClips, totalClips, rmsDb, micMode, wavName,
        shape, normalStatus, normalDistance, normalCount, answers, suggestions, votes
    )

    /** The same check with a new "own normal" result (used after another check was deleted). */
    fun withNormal(status: String, distance: Double?, count: Int) = CheckRecord(
        id, hive, outcome, strength, agree, cleanClips, totalClips, rmsDb, micMode, wavName,
        shape, status, distance, count, answers, suggestions, votes
    )

    fun timeText(): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(id))
}

/** Keeps hives, checks and settings on the phone. Everything here works offline. */
class Store(private val context: Context) {

    companion object {
        const val LEARNING = "LEARNING"
        const val NORMAL = "NORMAL"
        const val CHANGED = "CHANGED"
        const val NONE = "NONE"
        const val BASELINE_CHECKS = 3
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val GEMINI = "gemini"
        const val GROQ = "groq"
        const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
        const val DEFAULT_GROQ_MODEL = "openai/gpt-oss-120b"
        private const val DAY_MS = 86_400_000L
        private const val MONTH_MS = 2_629_800_000L      // 30.44 days

        @Volatile
        private var shared: Store? = null

        /**
         * The one Store of the app. The screens and the background alert job must use the same one,
         * so they never write the data file over each other.
         */
        fun get(context: Context): Store = shared ?: synchronized(this) {
            shared ?: Store(context.applicationContext).also { shared = it }
        }
    }

    private val file = File(context.filesDir, "ceranawatch_data.json")
    private val logFile = File(context.filesDir, "assistant_log.jsonl")
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val hiveNames = ArrayList<String>()
    private val records = ArrayList<CheckRecord>()
    private val hiveDetails = HashMap<String, JSONObject>()   // the details form and notes of each hive
    private val learn = HashMap<Long, JSONObject>()            // for on-phone learning: hive details at the time of each check
    private val alertRows = LinkedHashMap<String, JSONObject>() // for on-phone learning: device alerts the beekeeper marked real or false

    init {
        load()
    }

    // ---------- hives ----------

    @Synchronized
    fun hives(): List<String> = ArrayList(hiveNames)

    /** A hive name as the app keeps it: capital English letters, numbers, _ and - only. */
    fun cleanName(name: String): String =
        name.trim().uppercase(Locale.US).filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }.take(24)

    /** Adds a hive. Returns false when the name is empty or already used. */
    @Synchronized
    fun addHive(name: String): Boolean {
        val clean = cleanName(name)
        if (clean.isEmpty() || hiveNames.contains(clean)) return false
        hiveNames.add(clean)
        save()
        return true
    }

    /**
     * Gives a hive a new name and keeps its checks, details and notes.
     * Returns "" when it is done, or why not: "missing", "empty", "same" or "used".
     */
    @Synchronized
    fun renameHive(old: String, wanted: String): String {
        val clean = cleanName(wanted)
        val index = hiveNames.indexOf(old)
        if (index < 0) return "missing"
        if (clean.isEmpty()) return "empty"
        if (clean == old) return "same"
        if (hiveNames.contains(clean)) return "used"
        hiveNames[index] = clean
        for (i in records.indices) {
            if (records[i].hive == old) records[i] = records[i].withHive(clean)
        }
        val d = hiveDetails.remove(old)
        if (d != null) hiveDetails[clean] = d
        save()
        return ""
    }

    /**
     * Removes a hive with its checks, details, notes and the recordings the app keeps for itself.
     * Copies in the phone's Music folder and in the cloud are not touched.
     */
    @Synchronized
    fun deleteHive(name: String): Boolean {
        if (!hiveNames.remove(name)) return false
        for (r in records.filter { it.hive == name }) {
            forget(r)
            learn.remove(r.id)
            forgetClipShapes(r.id)
        }
        records.removeAll { it.hive == name }
        hiveDetails.remove(name)
        save()
        return true
    }

    /** Removes the private sound file and the saved advice text of one check. */
    private fun forget(r: CheckRecord) {
        try {
            if (r.wavName.isNotEmpty()) File(File(context.filesDir, "recordings"), r.wavName).delete()
        } catch (_: Exception) {
        }
        removeText("coach_" + r.id)
    }

    @Synchronized
    fun findHive(name: String): String? {
        val wanted = name.trim()
        return hiveNames.firstOrNull { it.equals(wanted, ignoreCase = true) }
    }

    // ---------- checks ----------

    /** Checks of one hive, newest first. */
    @Synchronized
    fun checks(hive: String): List<CheckRecord> =
        records.filter { it.hive == hive }.sortedByDescending { it.id }

    @Synchronized
    fun check(id: Long): CheckRecord? = records.firstOrNull { it.id == id }

    /** Every check of every hive, oldest first (used by the on-phone learning). */
    @Synchronized
    fun allChecks(): List<CheckRecord> = records.sortedBy { it.id }

    @Synchronized
    fun saveCheck(record: CheckRecord) {
        val index = records.indexOfFirst { it.id == record.id }
        if (index >= 0) records[index] = record else records.add(record)
        save()
    }

    /**
     * Removes one check. The "own normal" of the hive is then worked out again from the checks
     * that are left, because the removed check may have been one of the first three good ones.
     */
    @Synchronized
    fun deleteCheck(id: Long): Boolean {
        val r = records.firstOrNull { it.id == id } ?: return false
        records.remove(r)
        forget(r)
        learn.remove(r.id)
        forgetClipShapes(r.id)
        rejudge(r.hive)
        save()
        return true
    }

    /** Works out the "own normal" result of every check of one hive again, in time order. */
    private fun rejudge(hive: String) {
        val mine = records.filter { it.hive == hive }.sortedBy { it.id }
        val baseShapes = mine.mapNotNull { it.shape }.take(BASELINE_CHECKS)
        var good = 0
        for (r in mine) {
            val shape = r.shape
            val fresh = if (shape == null) {
                r.withNormal(NONE, null, minOf(good, BASELINE_CHECKS))
            } else if (good < BASELINE_CHECKS) {
                good++
                r.withNormal(LEARNING, null, good)
            } else {
                val distance = Analyzer.shapeDistance(shape, Analyzer.averageShape(baseShapes))
                r.withNormal(if (distance > Analyzer.CHANGE_LIMIT_DB) CHANGED else NORMAL, distance, BASELINE_CHECKS)
            }
            val index = records.indexOfFirst { it.id == r.id }
            if (index >= 0) records[index] = fresh
        }
    }

    /**
     * "Own normal" check. The first three good checks of a hive become its normal sound.
     * After that, a new check is compared with that normal.
     * Returns the status, the distance in dB (when compared) and how many checks the normal has.
     */
    @Synchronized
    fun judgeNormal(hive: String, shape: DoubleArray?): Triple<String, Double?, Int> {
        val base = records.filter { it.hive == hive && it.shape != null }.sortedBy { it.id }
            .take(BASELINE_CHECKS).mapNotNull { it.shape }
        if (shape == null) return Triple(NONE, null, base.size)
        if (base.size < BASELINE_CHECKS) return Triple(LEARNING, null, base.size + 1)
        val distance = Analyzer.shapeDistance(shape, Analyzer.averageShape(base))
        val status = if (distance > Analyzer.CHANGE_LIMIT_DB) CHANGED else NORMAL
        return Triple(status, distance, base.size)
    }

    // ---------- on-phone learning ----------

    /** What was kept for learning about one check (the hive details at that time), or null. */
    @Synchronized
    fun learnInfo(id: Long): JSONObject? = learn[id]?.let { JSONObject(it.toString()) }

    @Synchronized
    fun saveLearnInfo(id: Long, info: JSONObject) {
        learn[id] = JSONObject(info.toString())
        save()
    }

    /**
     * Keeps the app's guesses for a check, only if the check still exists and has no kept guess yet.
     * Returns the guess that is kept now (the earlier one when there was one), or null when the check is gone.
     */
    @Synchronized
    fun keepGuessIfAbsent(id: Long, guess: JSONObject): JSONObject? {
        if (records.none { it.id == id }) return null
        val info = learn[id] ?: JSONObject()
        info.optJSONObject("guess")?.let { return JSONObject(it.toString()) }
        info.put("guess", JSONObject(guess.toString()))
        learn[id] = info
        save()
        return guess
    }

    // The bee-band shape of every clean clip of a check, one file per check (kept apart so the main file stays small).
    private fun clipFile(id: Long): File = File(File(context.filesDir, "learn_clips"), "$id.txt")

    /** Keeps the clean clips' shapes of one check (2 decimals are plenty: the shapes are in dB). */
    fun saveClipShapes(id: Long, shapes: List<DoubleArray>) {
        if (shapes.isEmpty()) return
        try {
            val f = clipFile(id)
            f.parentFile?.mkdirs()
            f.writeText(shapes.joinToString("\n") { s -> s.joinToString(",") { v -> String.format(Locale.US, "%.2f", v) } })
        } catch (_: Exception) {
        }
    }

    /** The clean clips' shapes of one check, or null (checks made before this version have none). */
    fun clipShapes(id: Long): List<DoubleArray>? = try {
        val f = clipFile(id)
        if (!f.exists()) null
        else f.readLines().filter { it.isNotBlank() }.map { line -> line.split(",").map { it.toDouble() }.toDoubleArray() }
            .takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun forgetClipShapes(id: Long) {
        try {
            clipFile(id).delete()
        } catch (_: Exception) {
        }
    }

    /** The hive details as they are now, without the notes, for keeping with a new check. */
    @Synchronized
    fun detailsForLearning(hive: String): JSONObject {
        val d = details(hive)
        d.remove("notes")
        d.remove("updated")
        return d
    }

    /** Device alerts the beekeeper marked as real or false: t, type, z, sd and label. */
    @Synchronized
    fun alertLabels(): List<JSONObject> = alertRows.values.map { JSONObject(it.toString()) }

    /** Keeps the real / false answers of these alerts on the phone, so the alert checker can learn from them. */
    @Synchronized
    fun rememberAlertLabels(alerts: List<AlertItem>) {
        var changed = false
        for (a in alerts) {
            if (rememberRow(a, a.userLabel)) changed = true
        }
        if (changed) save()
    }

    /** Keeps one answer straight away, when the beekeeper taps "real" or "false alarm". */
    @Synchronized
    fun rememberAlertLabel(a: AlertItem, label: String) {
        if (rememberRow(a, label)) save()
    }

    /**
     * The first time an answer for an alert is kept, the alert checker's guess for it is worked out from
     * earlier alerts only, and kept with it. A changed answer keeps that first guess, so the score stays fair.
     */
    private fun rememberRow(a: AlertItem, label: String): Boolean {
        if (label != "real" && label != "false") return false
        if (!a.hasClock() || a.id.isEmpty()) return false
        val old = alertRows[a.id]
        if (old != null && textOf(old, "label") == label) return false
        val row = JSONObject().put("t", a.t).put("hive", a.hive).put("type", a.type)
            .put("z", a.z).put("sd", a.normalSd).put("label", label)
        if (old != null) {
            if (old.has("guess")) row.put("guess", old.optDouble("guess")).put("usual", old.optDouble("usual"))
        } else {
            val guess = Learner.alertChance(alertRows.values.filter { it.optLong("t", 0L) < a.t }, a)
            if (guess != null) row.put("guess", guess.chance).put("usual", guess.usual)
        }
        alertRows[a.id] = row
        return true
    }

    // ---------- settings ----------

    // ---------- built-in settings ----------
    // The Firebase address and the assistant access are put into the app when it is built
    // (from secrets.properties, see build.gradle.kts), so a beekeeper never types a key or an address.
    // A developer can still type their own in Settings > Developer settings; that wins over the built-in one.

    fun builtInFirebaseUrl(): String = BuildConfig.FIREBASE_URL.trim().trimEnd('/')
    fun aiProxyUrl(): String = BuildConfig.AI_PROXY_URL.trim()
    fun aiProxyToken(): String = BuildConfig.AI_PROXY_TOKEN.trim()
    private fun builtInGroqKey(): String = BuildConfig.GROQ_KEY.trim()

    /** The key typed in Developer settings for the chosen service ("" = none typed). */
    fun ownApiKey(): String = prefs.getString(keyName(), "") ?: ""

    /** True when the assistant talks to our own small server, which keeps the key (the safest way). */
    fun useProxy(): Boolean = provider() == GROQ && ownApiKey().isBlank() && aiProxyUrl().startsWith("http")

    /** True when the assistant can be used. */
    fun aiReady(): Boolean = apiKey().isNotBlank() || useProxy()

    /** True when the assistant works without anything typed on this phone. */
    fun aiBuiltIn(): Boolean = ownApiKey().isBlank() && aiReady()

    /** True when the Firebase address comes with the app (nothing typed on this phone). */
    fun firebaseBuiltIn(): Boolean = ownFirebaseUrl().isEmpty() && builtInFirebaseUrl().startsWith("http")

    /** Which AI service the assistant uses: GEMINI or GROQ. Each has its own key and model name. Groq is the built-in one. */
    fun provider(): String = if ((prefs.getString("provider", GROQ) ?: GROQ) == GEMINI) GEMINI else GROQ
    fun setProvider(value: String) = prefs.edit().putString("provider", if (value == GROQ) GROQ else GEMINI).apply()

    private fun keyName(): String = if (provider() == GROQ) "api_key_groq" else "api_key"
    private fun modelKeyName(): String = if (provider() == GROQ) "model_groq" else "model"
    private fun defaultModel(): String = if (provider() == GROQ) DEFAULT_GROQ_MODEL else DEFAULT_MODEL

    /** The key used: the one typed in Developer settings, or the one built into the app (not used with the server). */
    fun apiKey(): String {
        val own = ownApiKey()
        if (own.isNotBlank()) return own
        return if (provider() == GROQ && !aiProxyUrl().startsWith("http")) builtInGroqKey() else ""
    }

    /** Saves the key without spaces, line breaks or quote marks, which often come with copy and paste. */
    fun setApiKey(value: String) =
        prefs.edit().putString(keyName(), value.filter { !it.isWhitespace() && it != '"' && it != '\'' }).apply()

    fun modelName(): String {
        val saved = (prefs.getString(modelKeyName(), defaultModel()) ?: defaultModel())
            .filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == '/' || it == ':' }
        return if (saved.isEmpty()) defaultModel() else saved
    }

    fun setModelName(value: String) = prefs.edit().putString(modelKeyName(), value.trim()).apply()

    fun language(): String = if (prefs.getString("language", "English") == "Sinhala") "Sinhala" else "English"
    fun sinhala(): Boolean = language() == "Sinhala"
    fun setLanguage(value: String) = prefs.edit().putString("language", value).apply()

    // ---------- hive details form and notes ----------

    /**
     * The details of one hive: district, flow, hiveType, colonyAgeMonths, daysSinceOpened, weather, notes.
     * The two numbers are kept as dates, so they stay right as the days go by.
     */
    @Synchronized
    fun details(hive: String): JSONObject {
        val d = JSONObject((hiveDetails[hive] ?: JSONObject()).toString())
        val now = System.currentTimeMillis()
        if (d.has("openedAt")) d.put("daysSinceOpened", ((now - d.optLong("openedAt")) / DAY_MS).coerceAtLeast(0L).toString())
        if (d.has("bornAt")) d.put("colonyAgeMonths", ((now - d.optLong("bornAt")) / MONTH_MS).coerceAtLeast(0L).toString())
        d.remove("openedAt")
        d.remove("bornAt")
        return d
    }

    /**
     * Saves the given fields. Returns the names that changed.
     * Fields that are not given stay as they were. An empty field also stays as it was,
     * unless clearEmpty is true (the details form): then an empty field is removed.
     */
    @Synchronized
    fun saveDetails(hive: String, fields: Map<String, String>, clearEmpty: Boolean = false): List<String> {
        val current = hiveDetails[hive] ?: JSONObject()
        val before = details(hive)
        val now = System.currentTimeMillis()
        val changed = ArrayList<String>()
        for ((k, v) in fields) {
            val value = v.trim()
            val dateKey = when (k) {
                "daysSinceOpened" -> "openedAt"
                "colonyAgeMonths" -> "bornAt"
                else -> null
            }
            if (value.isEmpty()) {
                if (!clearEmpty) continue
                if (before.has(k)) changed.add(k)
                current.remove(dateKey ?: k)
                continue
            }
            if (dateKey != null) {
                val number = firstNumber(value) ?: continue
                if (before.optString(k) == number.toString()) continue
                current.put(dateKey, now - number * (if (k == "daysSinceOpened") DAY_MS else MONTH_MS))
                changed.add(k)
            } else {
                if (before.optString(k) != value) changed.add(k)
                current.put(k, value)
            }
        }
        current.put("updated", now)
        hiveDetails[hive] = current
        save()
        return changed
    }

    @Synchronized
    fun addNote(hive: String, text: String) {
        val current = hiveDetails[hive] ?: JSONObject()
        val notes = current.optJSONArray("notes") ?: JSONArray()
        notes.put(JSONObject().put("t", System.currentTimeMillis()).put("text", text.trim()))
        current.put("notes", notes)
        hiveDetails[hive] = current
        save()
    }

    /** The first whole number in a text: "3" from "3", "3.0" or "3 to 4 days". Null when there is none. */
    private fun firstNumber(text: String): Long? = Regex("\\d+").find(text)?.value?.take(4)?.toLongOrNull()

    /** Removes the note that was written at the given time. */
    @Synchronized
    fun deleteNote(hive: String, time: Long) {
        val current = hiveDetails[hive] ?: return
        val notes = current.optJSONArray("notes") ?: return
        val kept = JSONArray()
        for (i in 0 until notes.length()) {
            val o = notes.optJSONObject(i) ?: continue
            if (o.optLong("t") != time) kept.put(o)
        }
        current.put("notes", kept)
        save()
    }

    /** The notes of one hive, newest first: time in milliseconds and text. */
    @Synchronized
    fun notes(hive: String): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        val a = hiveDetails[hive]?.optJSONArray("notes") ?: return out
        for (i in a.length() - 1 downTo 0) {
            val o = a.optJSONObject(i) ?: continue
            out.add(Pair(o.optLong("t"), if (o.isNull("text")) "" else o.optString("text")))
        }
        return out
    }

    // ---------- cloud, alerts and saved assistant texts ----------

    /** The address of the Firebase Realtime Database, without a slash at the end. Empty = not set. */
    fun firebaseUrl(): String = ownFirebaseUrl().ifEmpty { builtInFirebaseUrl() }

    /** The address typed in Developer settings ("" = use the built-in one). */
    fun ownFirebaseUrl(): String = (prefs.getString("firebase_url", "") ?: "").trim().trimEnd('/')
    fun setFirebaseUrl(value: String) = prefs.edit().putString("firebase_url", value.trim().trimEnd('/')).apply()

    /** True on the phone of the second contact (the person who is told when the owner does not reply). */
    fun secondContact(): Boolean = prefs.getBoolean("second_contact", false)
    fun setSecondContact(value: Boolean) = prefs.edit().putBoolean("second_contact", value).apply()

    /** Minutes an alert may stay unanswered before the second contact is told. */
    fun escalateMinutes(): Int = prefs.getInt("escalate_minutes", 30).coerceIn(1, 1440)
    fun setEscalateMinutes(value: Int) = prefs.edit().putInt("escalate_minutes", value.coerceIn(1, 1440)).apply()

    fun backgroundAlerts(): Boolean = prefs.getBoolean("background_alerts", false)
    fun setBackgroundAlerts(value: Boolean) = prefs.edit().putBoolean("background_alerts", value).apply()

    /** Place of the apiary for the weather tool, as "latitude,longitude". Empty = not set. */
    fun location(): String = prefs.getString("location", "") ?: ""
    fun setLocation(value: String) = prefs.edit().putString("location", value.trim()).apply()

    /** A text kept on the phone so it can be read offline (daily summary, inspection plan, alert list). */
    fun savedText(name: String): String = prefs.getString("text_$name", "") ?: ""
    fun savedTextTime(name: String): Long = prefs.getLong("time_$name", 0L)
    fun saveText(name: String, text: String) =
        prefs.edit().putString("text_$name", text).putLong("time_$name", System.currentTimeMillis()).apply()
    fun removeText(name: String) = prefs.edit().remove("text_$name").remove("time_$name").apply()

    /** True when the app may write the daily summary by itself, once a day, when it is opened with internet. */
    fun autoSummary(): Boolean = prefs.getBoolean("summary_auto", true)
    fun setAutoSummary(value: Boolean) = prefs.edit().putBoolean("summary_auto", value).apply()

    /** The day (as "20261007") on which the app last wrote the daily summary by itself. */
    fun autoSummaryDay(): String = prefs.getString("summary_auto_day", "") ?: ""
    fun setAutoSummaryDay(value: String) = prefs.edit().putString("summary_auto_day", value).apply()

    /** Alerts that this phone has already shown as a notification. */
    @Synchronized
    fun alertWasShown(id: String): Boolean = (prefs.getStringSet("shown_alerts", emptySet()) ?: emptySet()).contains(id)

    @Synchronized
    fun markAlertShown(id: String) {
        val all = HashSet(prefs.getStringSet("shown_alerts", emptySet()) ?: emptySet())
        all.add(id)
        prefs.edit().putStringSet("shown_alerts", all).apply()
    }

    // ---------- assistant log (used to evaluate the assistant) ----------

    @Synchronized
    fun appendAssistantLog(entry: JSONObject) {
        try {
            logFile.appendText(entry.toString() + "\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun assistantLogCount(): Int = try {
        if (logFile.exists()) logFile.readLines().count { it.isNotBlank() } else 0
    } catch (_: Exception) {
        0
    }

    /**
     * Copies the app data and the assistant log to the phone's Download folder,
     * so they can be moved to a laptop. Returns a short text for the screen.
     */
    @Synchronized
    fun exportForLaptop(): String {
        val done = ArrayList<String>()
        val sources = listOf(Pair(file, "ceranawatch_data.json"), Pair(logFile, "ceranawatch_assistant_log.txt"))
        try {
            for ((source, name) in sources) {
                if (!source.exists()) continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues()
                    values.put(MediaStore.Downloads.DISPLAY_NAME, name)
                    values.put(MediaStore.Downloads.MIME_TYPE, if (name.endsWith(".json")) "application/json" else "text/plain")
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CeranaWatch")
                    val uri = context.contentResolver.insert(
                        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
                    ) ?: continue
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    }
                    done.add("Download/CeranaWatch/$name")
                } else {
                    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: continue
                    val copy = File(dir, name)
                    source.copyTo(copy, overwrite = true)
                    done.add(copy.absolutePath)
                }
            }
        } catch (e: Exception) {
            return (if (sinhala()) "කොපි කරන්න බැරි වුණා: " else "Copy failed: ") + (e.message ?: "unknown error")
        }
        return if (done.isEmpty()) (if (sinhala()) "කොපි කරන්න තවම දෙයක් නැහැ." else "Nothing to copy yet.")
        else (if (sinhala()) "කොපි වුණා: " else "Copied: ") + done.joinToString(", ")
    }

    // ---------- file ----------

    private fun load() {
        val root = try {
            if (!file.exists()) return
            JSONObject(file.readText())
        } catch (_: Exception) {
            return   // a damaged file must not stop the app from opening
        }
        // each part is read on its own, and one damaged entry is skipped, so it cannot hide the rest
        val hs = root.optJSONArray("hives") ?: JSONArray()
        for (i in 0 until hs.length()) {
            try {
                hiveNames.add(hs.getString(i))
            } catch (_: Exception) {
            }
        }
        val cs = root.optJSONArray("checks") ?: JSONArray()
        for (i in 0 until cs.length()) {
            try {
                records.add(fromJson(cs.getJSONObject(i)))
            } catch (_: Exception) {
            }
        }
        for ((k, o) in objects(root.optJSONObject("details"))) {
            try {
                hiveDetails[k] = datesFromNumbers(o)
            } catch (_: Exception) {
            }
        }
        for ((k, o) in objects(root.optJSONObject("learn"))) {
            val id = k.toLongOrNull() ?: continue
            learn[id] = o
        }
        for ((k, o) in objects(root.optJSONObject("alertLabels"))) alertRows[k] = o
    }

    /** The objects inside a JSON object, skipping anything that is not an object. */
    private fun objects(o: JSONObject?): List<Pair<String, JSONObject>> {
        val out = ArrayList<Pair<String, JSONObject>>()
        if (o == null) return out
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.optJSONObject(k) ?: continue
            out.add(Pair(k, v))
        }
        return out
    }

    /** Older versions kept "days since opened" and "age in months" as plain numbers. They become dates here. */
    private fun datesFromNumbers(d: JSONObject): JSONObject {
        val then = d.optLong("updated", System.currentTimeMillis())
        val days = firstNumber(d.optString("daysSinceOpened"))
        if (days != null && !d.has("openedAt")) d.put("openedAt", then - days * DAY_MS)
        val months = firstNumber(d.optString("colonyAgeMonths"))
        if (months != null && !d.has("bornAt")) d.put("bornAt", then - months * MONTH_MS)
        d.remove("daysSinceOpened")
        d.remove("colonyAgeMonths")
        return d
    }

    private fun save() {
        try {
            val root = JSONObject()
            root.put("hives", JSONArray(hiveNames))
            val cs = JSONArray()
            for (r in records) cs.put(toJson(r))
            root.put("checks", cs)
            val ds = JSONObject()
            for ((k, v) in hiveDetails) ds.put(k, v)
            root.put("details", ds)
            val ls = JSONObject()
            for ((k, v) in learn) ls.put(k.toString(), v)
            root.put("learn", ls)
            val al = JSONObject()
            for ((k, v) in alertRows) al.put(k, v)
            root.put("alertLabels", al)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun toJson(r: CheckRecord): JSONObject {
        val o = JSONObject()
        o.put("id", r.id)
        o.put("hive", r.hive)
        o.put("outcome", r.outcome)
        o.put("strength", r.strength ?: JSONObject.NULL)
        o.put("agree", r.agree)
        o.put("cleanClips", r.cleanClips)
        o.put("totalClips", r.totalClips)
        o.put("rmsDb", r.rmsDb)
        o.put("micMode", r.micMode)
        o.put("wavName", r.wavName)
        if (r.shape != null) {
            val a = JSONArray()
            for (v in r.shape) a.put(Math.round(v * 1000.0) / 1000.0)
            o.put("shape", a)
        }
        o.put("normalStatus", r.normalStatus)
        o.put("normalDistance", r.normalDistance ?: JSONObject.NULL)
        o.put("normalCount", r.normalCount)
        val ans = JSONObject()
        for ((k, v) in r.answers) ans.put(k, v)
        o.put("answers", ans)
        o.put("suggestions", JSONArray(r.suggestions))
        val vt = JSONObject()
        for ((k, v) in r.votes) vt.put(k, v)
        o.put("votes", vt)
        return o
    }

    private fun fromJson(o: JSONObject): CheckRecord {
        var shape: DoubleArray? = null
        val a = o.optJSONArray("shape")
        if (a != null) shape = DoubleArray(a.length()) { a.getDouble(it) }
        val answers = LinkedHashMap<String, String>()
        val ans = o.optJSONObject("answers")
        if (ans != null) {
            val keys = ans.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                answers[k] = ans.getString(k)
            }
        }
        val votes = LinkedHashMap<String, Int>()
        val vt = o.optJSONObject("votes")
        if (vt != null) {
            val keys = vt.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                votes[k] = vt.optInt(k)
            }
        }
        val suggestions = ArrayList<String>()
        val sg = o.optJSONArray("suggestions")
        if (sg != null) for (i in 0 until sg.length()) suggestions.add(sg.getString(i))
        return CheckRecord(
            id = o.getLong("id"),
            hive = o.getString("hive"),
            outcome = o.getString("outcome"),
            strength = if (o.isNull("strength")) null else o.getString("strength"),
            agree = o.optInt("agree"),
            cleanClips = o.optInt("cleanClips"),
            totalClips = o.optInt("totalClips"),
            rmsDb = o.optDouble("rmsDb", -120.0),
            micMode = o.optString("micMode", "unknown"),
            wavName = o.optString("wavName", ""),
            shape = shape,
            normalStatus = o.optString("normalStatus", NONE),
            normalDistance = if (o.isNull("normalDistance")) null else o.getDouble("normalDistance"),
            normalCount = o.optInt("normalCount"),
            answers = answers,
            suggestions = suggestions,
            votes = votes
        )
    }
}

