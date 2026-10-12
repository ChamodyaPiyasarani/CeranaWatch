package com.example.ceranawatch

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One thing the assistant did: used a tool, gave an answer, or hit a problem. */
class AgentStep(val kind: String, val text: String) {
    companion object {
        const val TOOL = "tool"
        const val ANSWER = "answer"
        const val ERROR = "error"
    }
}

/**
 * The online assistant (the "agentic AI" layer).
 *
 * It is a language model that can call tools. With the tools it reads what the app
 * and the hive device have recorded, reads the weather, and can do four small
 * actions: save hive details, save its review of an alert, send an alert on to the
 * second contact, and send a short message.
 *
 * The sound model on the phone and the rule on the hive device decide the readings
 * and the alerts. The assistant explains them and plans the next steps.
 * It needs internet; everything else in the app works without it.
 */
/** The AI service said "too many requests". waitSeconds = how long it asked us to wait (-1 when it did not say). */
class RateLimited(message: String, val waitSeconds: Double, val daily: Boolean) : IOException(message)

class Agent(private val store: Store, private val cloud: Cloud) {

    companion object {
        private const val MAX_ROUNDS = 8
        private const val MAX_WAITS = 4                 // how often the app waits and tries again after "too many requests"
        private const val MAX_WAIT_SECONDS = 65.0       // a longer wait (for example the daily limit) is not waited for
        private const val MAX_TOTAL_WAIT_SECONDS = 150.0
        private const val MAX_TOOL_TEXT = 3500          // a tool result longer than this is cut, so each request stays small
        private const val MAX_ONE_SHOT_DATA = 9000      // characters of app data put into a one-request job
        const val FALLBACK_GROQ_MODEL = "openai/gpt-oss-20b"   // has its own free limit at Groq

        private val NET = Any()                          // only one request to the AI service at a time, for the whole app
        @Volatile private var tokensLeft = -1            // tokens left this minute, from the service's last answer (-1 = not known)
        @Volatile private var tokensResetAt = 0L         // when that count starts again (ms)
        const val CHAT = "chat"
        const val REVIEW = "alert_review"
        const val SUMMARY = "daily_summary"
        const val PLAN = "inspection_plan"
        const val NOTES = "notes_to_data"
        const val COACH = "recording_coach"
    }

    /** Only for tests: another address for the Groq service. */
    var groqUrl = Store.GROQ_URL
    var geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/"

    @Volatile private var usedFallback = false   // the last job used the smaller model because the main one was busy
    private var contents = JSONArray()   // the conversation so far (Gemini format)
    private var messages = JSONArray()   // the conversation so far (Groq / OpenAI format)

    /** Starts a new, empty conversation. A question that is still running keeps the old one. */
    fun reset() {
        contents = JSONArray()
        messages = JSONArray()
    }

    /** Asks one question in the chat. This call blocks, so run it on a background thread. */
    @Synchronized
    fun ask(question: String, focusHive: String?): List<AgentStep> {
        val first = if (store.provider() == Store.GROQ) messages.length() == 0 else contents.length() == 0
        val text = if (focusHive != null && first) {
            "(The beekeeper is looking at hive $focusHive in the app.) $question"
        } else question
        return run(CHAT, question, text, focusHive, contents, messages)
    }

    /**
     * Does one job without a chat: an alert review, the daily summary, the inspection plan,
     * notes to data, or recording advice. Every job starts a new, empty conversation.
     */
    fun runTask(task: String, instruction: String, hive: String?): List<AgentStep> =
        run(task, instruction, instruction, hive, JSONArray(), JSONArray())

    /** The answer text of a finished job, or null when it failed. */
    fun answerOf(steps: List<AgentStep>): String? = steps.lastOrNull { it.kind == AgentStep.ANSWER }?.text

    /** The problem text of a failed job, or null. */
    fun errorOf(steps: List<AgentStep>): String? = steps.lastOrNull { it.kind == AgentStep.ERROR }?.text

    private fun run(
        task: String, shown: String, text: String, focusHive: String?,
        contents: JSONArray, messages: JSONArray
    ): List<AgentStep> {
        val steps = ArrayList<AgentStep>()
        val key = store.apiKey()
        if (!store.aiReady()) {
            steps.add(
                AgentStep(
                    AgentStep.ERROR,
                    if (store.sinhala()) "මේ ඇප් එකේ AI සහායකයා සකස් කරලා නැහැ. (ඇප් එක හදන කෙනාට: secrets.properties එකේ key එක හරි server ලිපිනය හරි දාන්න, නැත්නම් සැකසුම් > Developer settings එකේ API key එකක් දාන්න.)"
                    else "The assistant is not set up in this copy of the app. (For the app maker: add the key or the server address in secrets.properties, or an API key in Settings > Developer settings.)"
                )
            )
            return steps
        }
        val started = System.currentTimeMillis()
        usedFallback = false
        val useGroq = store.provider() == Store.GROQ
        val markGemini = contents.length()
        val markGroq = messages.length()
        val toolsUsed = JSONArray()
        var answer: String? = null
        var problem: String? = null
        try {
            val ready = prefetch(task, text, focusHive)
            answer = when {
                ready != null -> answerOnce(key, task, text, ready, steps, toolsUsed, useGroq)
                useGroq -> chatGroq(key, text, steps, toolsUsed, messages)
                else -> chatGemini(key, text, steps, toolsUsed, contents)
            }
            if (answer.isNullOrEmpty()) {
                answer = null
                throw IOException(
                    if (store.sinhala()) "පිළිතුර සම්පූර්ණ කරන්න බැරි වුණා. ආයෙත් උත්සාහ කරන්න."
                    else "The assistant could not finish the answer. Please try again."
                )
            }
            steps.add(AgentStep(AgentStep.ANSWER, answer))
        } catch (e: Exception) {
            while (contents.length() > markGemini) contents.remove(contents.length() - 1)
            while (messages.length() > markGroq) messages.remove(messages.length() - 1)
            problem = friendlyError(e)
            steps.add(AgentStep(AgentStep.ERROR, problem))
        }
        val entry = JSONObject()
            .put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .put("task", task)
            .put("service", store.provider())
            .put("model", if (usedFallback) FALLBACK_GROQ_MODEL else store.modelName())
            .put("server", store.useProxy())
            .put("language", store.language())
            .put("hive", focusHive ?: JSONObject.NULL)
            .put("question", shown)
            .put("tools", toolsUsed)
            .put("answer", answer ?: JSONObject.NULL)
            .put("error", problem ?: JSONObject.NULL)
            .put("milliseconds", System.currentTimeMillis() - started)
        store.appendAssistantLog(entry)
        // a copy of every assistant step is also kept in the cloud, when the cloud is set up
        if (cloud.ready() && problem == null) {
            try {
                cloud.uploadAgentLog(entry)
            } catch (_: Exception) {
            }
        }
        return steps
    }

    /** One question with Google Gemini. The model may ask for tools several times before it answers. */
    private fun chatGemini(key: String, text: String, steps: MutableList<AgentStep>, toolsUsed: JSONArray, contents: JSONArray): String {
        contents.put(userTurn(JSONArray().put(JSONObject().put("text", text))))
        val url = geminiUrl + store.modelName() + ":generateContent"
        var round = 0
        while (round < MAX_ROUNDS) {
            round++
            val reply = postWaiting(url, mapOf("x-goog-api-key" to key), requestBody(contents))
            val content = reply.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?: throw IOException("The assistant gave no answer. Please ask again in other words.")
            contents.put(content)   // kept exactly as received; the service needs it back unchanged
            val parts = content.optJSONArray("parts") ?: JSONArray()
            val toolResults = JSONArray()
            val said = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val call = part.optJSONObject("functionCall")
                if (call != null) {
                    val name = call.optString("name")
                    val args = call.optJSONObject("args") ?: JSONObject()
                    val result = runTool(name, args)
                    steps.add(AgentStep(AgentStep.TOOL, describeTool(name, args)))
                    toolsUsed.put(JSONObject().put("name", name).put("args", args))
                    val response = JSONObject().put("name", name)
                        .put("response", JSONObject().put("result", result))
                    if (call.has("id")) response.put("id", call.get("id"))
                    toolResults.put(JSONObject().put("functionResponse", response))
                } else if (part.has("text") && !part.optBoolean("thought", false)) {
                    said.append(part.getString("text"))
                }
            }
            if (toolResults.length() == 0) return said.toString().trim()
            contents.put(userTurn(toolResults))
        }
        return ""
    }

    /** One question with Groq (the OpenAI-style chat format). Same tools, same rules. */
    private fun chatGroq(key: String, text: String, steps: MutableList<AgentStep>, toolsUsed: JSONArray, messages: JSONArray): String {
        messages.put(JSONObject().put("role", "user").put("content", text))
        var round = 0
        while (round < MAX_ROUNDS) {
            round++
            val all = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
            for (i in 0 until messages.length()) all.put(messages.get(i))
            val body = JSONObject()
                .put("model", if (usedFallback) FALLBACK_GROQ_MODEL else store.modelName())
                .put("messages", all)
                .put("tools", groqTools())
                .put("tool_choice", "auto")
            val reply = postGroq(key, body)
            val message = reply.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IOException("The assistant gave no answer. Please ask again in other words.")
            val said = if (message.isNull("content")) "" else message.optString("content")
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                messages.put(JSONObject().put("role", "assistant").put("content", said))
                return said.trim()
            }
            // keep only the standard fields of the assistant turn, then answer every tool call
            val kept = JSONArray()
            val results = ArrayList<JSONObject>()
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val id = call.optString("id")
                val function = call.optJSONObject("function") ?: JSONObject()
                val name = function.optString("name")
                val rawArgs = function.optString("arguments", "{}")
                val args = try {
                    JSONObject(rawArgs)
                } catch (_: Exception) {
                    JSONObject()
                }
                kept.put(
                    JSONObject().put("id", id).put("type", "function")
                        .put("function", JSONObject().put("name", name).put("arguments", rawArgs))
                )
                val result = runTool(name, args)
                steps.add(AgentStep(AgentStep.TOOL, describeTool(name, args)))
                toolsUsed.put(JSONObject().put("name", name).put("args", args))
                results.add(
                    JSONObject().put("role", "tool").put("tool_call_id", id).put("name", name)
                        .put("content", shortText(result.toString()))
                )
            }
            messages.put(
                JSONObject().put("role", "assistant")
                    .put("content", if (said.isEmpty()) JSONObject.NULL else said)
                    .put("tool_calls", kept)
            )
            for (r in results) messages.put(r)
        }
        return ""
    }

    /** The same tools, wrapped in the format Groq expects. */
    private fun groqTools(): JSONArray {
        val list = toolList()
        val out = JSONArray()
        for (i in 0 until list.length()) {
            val tool = list.getJSONObject(i)
            if (!tool.has("parameters")) {
                tool.put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))
            }
            out.put(JSONObject().put("type", "function").put("function", tool))
        }
        return out
    }

    private fun userTurn(parts: JSONArray): JSONObject = JSONObject().put("role", "user").put("parts", parts)

    private fun systemPrompt(): String =
        "You are the assistant inside CeranaWatch, a phone app for Sri Lankan beekeepers who keep the " +
            "native honeybee Apis cerana. The app has two sources of data: (a) hive checks, where the beekeeper " +
            "lets the phone listen inside the hive until it has one minute of clean bee sound (3-second parts with talking, " +
            "birds, horns or other sounds are thrown away), and (b) an optional hive device that listens all day, " +
            "first checks with a small bee detector whether the sound is bee sound, other sound or silence, " +
            "learns the hive's normal loudness only from bee sound, raises an alert when the bee sound is far from that normal " +
            "or when no bee sound is heard several readings in a row, and measures temperature and humidity inside the hive. " +
            "Never call a reading bee sound unless the device's bee detector said so. " +
            "Rules: " +
            "1. Before you say anything about a hive, use the tools to read what was recorded. " +
            "Never invent a reading, a date or a number. If a tool gives an error, say what is missing. " +
            "2. The phone's sound model, the device's rule and the beekeeper's own answers decide the readings " +
            "and the alerts. You explain them and plan the next steps. You do not make your own diagnosis from sound, " +
            "and you never name a disease or a pest as the cause. You may say what the beekeeper should look for. " +
            "3. The strength estimate is experimental. When you mention it, say that it is right only " +
            "about half the time on hives the model has not heard before. Call get_model_info if you need the facts. " +
            "4. 'Changed from its own normal' and a device alert both mean the sound differs from what was " +
            "heard before. They are reasons to inspect, not proof of a problem. Rain, wind, a passing vehicle, " +
            "opening the hive or moving the device can also cause them. " +
            "5. Give short, practical steps a small-scale beekeeper can do. If something may be serious, " +
            "tell the beekeeper to open and inspect the hive or to ask an experienced beekeeper or the local " +
            "agriculture extension officer. Do not advise opening a hive in rain. " +
            "6. If the app has no data for a hive, say so and suggest doing a check. " +
            "7. Use save_hive_details only for facts the beekeeper clearly stated. Use escalate or send_message " +
            "only when the beekeeper asks, or when an alert is unanswered and your review says it is likely real. " +
            "8. " + languageRule() + " Keep the answer under 150 words unless asked for more. " +
            "Do not use tables or special formatting marks such as * or #."

    /** How the assistant must write. For Sinhala it gets the same everyday words the app itself uses. */
    private fun languageRule(): String =
        if (!store.sinhala()) "Reply in English. Use simple words and short sentences."
        else "Reply in Sinhala, written in Sinhala letters. Use simple everyday spoken Sinhala that a village beekeeper " +
            "understands: short sentences, common words and spoken verb forms (කරන්න, තියෙනවා, නැහැ, පුළුවන්). " +
            "Do not use rare, literary or technical Sinhala words, and do not translate English word by word. " +
            "Use these words: hive = මී පෙට්ටිය, colony = මී ජනපදය, bees = මී මැස්සෝ, queen = රැජින, comb = මී වදය, " +
            "eggs and larvae = බිත්තර සහ පැටව්, hive device = උපකරණය, alert = අනතුරු ඇඟවීම, hive check = පරීක්ෂාව, " +
            "recording = රෙකෝඩින් එක, the hive's own normal sound = සුපුරුදු ශබ්දය, strong / medium / weak = ශක්තිමත් / මධ්‍යම / දුර්වල, " +
            "false alarm = බොරු ඇඟවීමක්, wasps or hornets = දෙබරු, wax moth = ඉටි සලබයා, sugar syrup = සීනි පැණි, " +
            "open the hive = පෙට්ටිය ඇරලා බලන්න, second contact = දෙවැනි කෙනා, internet = ඉන්ටර්නෙට්, phone = ෆෝන් එක. " +
            "Keep hive names, dB and all numbers exactly as they are."

    private fun requestBody(contents: JSONArray, withTools: Boolean = true): JSONObject {
        val o = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
        if (withTools) o.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", toolList())))
        return o
    }

    // ---------- jobs done with one request ----------
    // The daily summary, the inspection plan, the recording coach and the alert review always need the same data.
    // The app reads that data itself and sends it in ONE request without the tool list. This uses about a third of
    // the tokens of the tool loop, so the free per-minute limit is not reached.

    private fun prefetch(task: String, instruction: String, hive: String?): List<Pair<String, JSONObject>>? {
        val h = hive ?: ""
        fun call(name: String, vararg kv: Pair<String, Any>): Pair<String, JSONObject> {
            val o = JSONObject()
            for ((k, v) in kv) o.put(k, v)
            return name to o
        }
        val hives = store.hives()
        return when (task) {
            SUMMARY -> listOf(call("list_hives"), call("get_alerts", "only_open" to true), call("get_weather")) +
                hives.take(8).map { call("get_node_latest", "hive" to it) }
            PLAN -> listOf(call("list_hives"), call("get_alerts", "only_open" to true), call("get_weather")) +
                hives.take(6).flatMap { listOf(call("get_hive_details", "hive" to it), call("get_node_latest", "hive" to it)) }
            COACH -> if (h.isEmpty()) null else listOf(call("get_latest_check", "hive" to h), call("get_weather"))
            REVIEW -> if (h.isEmpty() || alertIdIn(instruction).isEmpty()) null else listOf(
                call("get_alerts", "hive" to h), call("get_node_history", "hive" to h, "limit" to 40), call("get_node_latest", "hive" to h),
                call("get_weather"), call("get_latest_check", "hive" to h), call("get_hive_details", "hive" to h)
            )
            else -> null
        }
    }

    private fun alertIdIn(instruction: String): String =
        Regex("alert_id\\s+([A-Za-z0-9_-]+)").find(instruction)?.groupValues?.get(1) ?: ""

    private fun answerOnce(
        key: String, task: String, instruction: String, calls: List<Pair<String, JSONObject>>,
        steps: MutableList<AgentStep>, toolsUsed: JSONArray, useGroq: Boolean
    ): String {
        val share = maxOf(700, MAX_ONE_SHOT_DATA / maxOf(1, calls.size))
        val data = StringBuilder()
        for ((name, args) in calls) {
            val result = runTool(name, args)
            steps.add(AgentStep(AgentStep.TOOL, describeTool(name, args)))
            toolsUsed.put(JSONObject().put("name", name).put("args", args))
            val text = result.toString()
            data.append("### ").append(name).append(if (args.length() > 0) " $args" else "").append('\n')
                .append(if (text.length <= share) text else text.substring(0, share) + " ...(cut)").append("\n\n")
        }
        val review = task == REVIEW
        val prompt = "The app has already read this data for you. You cannot call tools now; use only this data.\n\n" + data +
            "Task: " + instruction +
            (if (review) "\nDo not call save_alert_review; the app saves your review. Answer in this form: the first line is exactly " +
                "LABEL: likely_real, LABEL: likely_false or LABEL: unsure. Then write the explanation." else "")
        val said = if (useGroq) {
            val body = JSONObject().put("model", store.modelName()).put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt()))
                    .put(JSONObject().put("role", "user").put("content", prompt))
            )
            val reply = postGroq(key, body)
            val message = reply.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IOException(noAnswerText())
            if (message.isNull("content")) "" else message.optString("content").trim()
        } else {
            val url = geminiUrl + store.modelName() + ":generateContent"
            val reply = postWaiting(url, mapOf("x-goog-api-key" to key), requestBody(JSONArray().put(userTurn(JSONArray().put(JSONObject().put("text", prompt)))), false))
            val parts = reply.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IOException(noAnswerText())
            val out = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text") && !part.optBoolean("thought", false)) out.append(part.getString("text"))
            }
            out.toString().trim()
        }
        if (!review || said.isEmpty()) return said
        // the app saves the review itself
        val first = said.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        val label = Regex("likely_real|likely_false|unsure").find(first.lowercase(Locale.US))?.value ?: "unsure"
        val explanation = (if (first.uppercase(Locale.US).contains("LABEL")) said.substringAfter(first) else said).trim().ifEmpty { said }
        val args = JSONObject().put("alert_id", alertIdIn(instruction)).put("label", label).put("explanation", explanation)
        runTool("save_alert_review", args)
        steps.add(AgentStep(AgentStep.TOOL, describeTool("save_alert_review", args)))
        toolsUsed.put(JSONObject().put("name", "save_alert_review").put("args", JSONObject().put("label", label)))
        return explanation
    }

    private fun noAnswerText(): String =
        if (store.sinhala()) "සහායකයා උත්තරයක් දුන්නේ නැහැ. ආයෙත් උත්සාහ කරන්න." else "The assistant gave no answer. Please try again."

    // ---------- tools ----------

    private fun hiveParam(extra: JSONObject? = null): JSONObject {
        val props = JSONObject().put(
            "hive", JSONObject().put("type", "string").put("description", "Hive name as shown in the app, for example N01H01")
        )
        if (extra != null) {
            val keys = extra.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                props.put(k, extra.get(k))
            }
        }
        return JSONObject().put("type", "object").put("properties", props).put("required", JSONArray().put("hive"))
    }

    private fun toolList(): JSONArray = JSONArray()
        .put(
            JSONObject().put("name", "list_hives")
                .put("description", "Lists every hive in the app with the time and result of its latest check.")
        )
        .put(
            JSONObject().put("name", "get_latest_check")
                .put("description", "Returns the latest check of one hive: outcome, strength estimate, how many clips agreed, the own-normal status, and the beekeeper's answers and suggestions.")
                .put("parameters", hiveParam())
        )
        .put(
            JSONObject().put("name", "get_history")
                .put("description", "Returns the past checks of one hive, newest first.")
                .put(
                    "parameters",
                    hiveParam(JSONObject().put("limit", JSONObject().put("type", "integer").put("description", "How many checks to return, 1 to 20")))
                )
        )
        .put(
            JSONObject().put("name", "get_model_info")
                .put("description", "Returns facts about the sound model: how it was tested, how accurate it is, and its limits.")
        )
        .put(
            JSONObject().put("name", "get_hive_details")
                .put("description", "Returns what the beekeeper has written about one hive: district, nectar flow, hive type, colony age, days since it was opened, weather, and free notes.")
                .put("parameters", hiveParam())
        )
        .put(
            JSONObject().put("name", "save_hive_details")
                .put("description", "Saves facts about one hive that the beekeeper clearly stated. Give only the fields that were stated; leave the others out.")
                .put(
                    "parameters",
                    hiveParam(
                        JSONObject()
                            .put("district", text("District where the hive is, for example Badulla"))
                            .put("flow", text("Is there a nectar flow now: yes, no or unsure"))
                            .put("hive_type", text("Type of hive: box, log, clay pot or other"))
                            .put("colony_age_months", text("Age of the colony in months, as a number"))
                            .put("days_since_opened", text("Days since the hive was last opened, as a number"))
                            .put("weather", text("Weather the beekeeper described: sunny, cloudy, rain or windy"))
                            .put("note", text("Any other fact worth keeping, as one short sentence"))
                    )
                )
        )
        .put(
            JSONObject().put("name", "get_node_latest")
                .put("description", "Returns the latest reading of the hive device of one hive: whether the bee detector heard bee sound, other sound or silence, the loudness of the bee band (only for bee sound), this hive's learned normal, the z-score, temperature and humidity inside the hive, the status (NO_BEES, LEARNING, NORMAL, UNUSUAL, or NO_CHECK for an old device program) and whether the device is online.")
                .put("parameters", hiveParam())
        )
        .put(
            JSONObject().put("name", "get_node_history")
                .put("description", "Returns a summary of the last readings of the hive device of one hive (how many had bee sound, bee-band loudness of the bee readings, temperature and humidity), and the newest readings one by one.")
                .put(
                    "parameters",
                    hiveParam(JSONObject().put("limit", JSONObject().put("type", "integer").put("description", "How many readings to look at, 10 to 300")))
                )
        )
        .put(
            JSONObject().put("name", "get_alerts")
                .put("description", "Returns the latest alerts raised by the hive devices, newest first, with the alert_id of each.")
                .put(
                    "parameters",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("hive", text("Only alerts of this hive. Leave out for all hives."))
                            .put("only_open", JSONObject().put("type", "boolean").put("description", "True = only alerts the beekeeper has not seen yet"))
                    )
                )
        )
        .put(
            JSONObject().put("name", "get_weather")
                .put("description", "Returns the weather now at the apiary and today's rain: temperature, humidity, rain, wind and sky.")
        )
        .put(
            JSONObject().put("name", "save_alert_review")
                .put("description", "Saves your review of one alert so the beekeeper sees it on the Alerts screen. Read the data with the other tools first.")
                .put(
                    "parameters",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("alert_id", text("The alert_id from get_alerts"))
                            .put("label", text("One of: likely_real, likely_false, unsure"))
                            .put("explanation", text("Two or three short sentences for the beekeeper: what happened, the likely reason, what to do next"))
                    ).put("required", JSONArray().put("alert_id").put("label").put("explanation"))
                )
        )
        .put(
            JSONObject().put("name", "escalate")
                .put("description", "Sends one alert on to the second contact (a family member or another beekeeper), whose phone then shows it.")
                .put(
                    "parameters",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("alert_id", text("The alert_id from get_alerts"))
                            .put("reason", text("One short sentence that says why"))
                    ).put("required", JSONArray().put("alert_id").put("reason"))
                )
        )
        .put(
            JSONObject().put("name", "send_message")
                .put("description", "Sends a short message to the phone of the second contact.")
                .put(
                    "parameters",
                    JSONObject().put("type", "object").put(
                        "properties", JSONObject().put("text", text("The message, at most two sentences"))
                    ).put("required", JSONArray().put("text"))
                )
        )

    private fun text(description: String): JSONObject = JSONObject().put("type", "string").put("description", description)

    private fun runTool(name: String, args: JSONObject): Any {
        return try {
            when (name) {
                "list_hives" -> {
                    val out = JSONArray()
                    for (h in store.hives()) {
                        val all = store.checks(h)
                        val o = JSONObject().put("hive", h).put("number_of_checks", all.size)
                        if (all.isNotEmpty()) o.put("latest_check", summary(all[0]))
                        out.put(o)
                    }
                    out
                }
                "get_latest_check" -> {
                    val hive = store.findHive(args.optString("hive")) ?: return unknownHive(args.optString("hive"))
                    val all = store.checks(hive)
                    if (all.isEmpty()) JSONObject().put("hive", hive).put("message", "No check has been done for this hive yet.")
                    else summary(all[0], learning = true)
                }
                "get_history" -> {
                    val hive = store.findHive(args.optString("hive")) ?: return unknownHive(args.optString("hive"))
                    val limit = args.optInt("limit", 10).coerceIn(1, 20)
                    val out = JSONArray()
                    for (r in store.checks(hive).take(limit)) out.put(summary(r))
                    JSONObject().put("hive", hive).put("checks", out)
                }
                "get_model_info" -> JSONObject()
                    .put("what_it_estimates", "colony strength: strong, medium or weak")
                    .put("trained_on", "${ModelData.TRAIN_HIVES} Apis cerana hives at 4 apiaries in Sri Lanka, ${ModelData.TRAIN_CLIPS} clean 3-second clips")
                    .put("balanced_accuracy_on_unseen_hives", ModelData.EXPECTED_BA)
                    .put("balanced_accuracy_if_guessing", 0.33)
                    .put("meaning", "On a hive it has not heard before, the estimate is right about half the time. Treat it as an experimental estimate, not a measurement.")
                    .put("own_normal_check", "The first ${Store.BASELINE_CHECKS} good checks of a hive form its normal sound. A later check more than ${Analyzer.CHANGE_LIMIT_DB} dB away is reported as changed. " + ModelData.BASELINE_NOTE)
                    .put("hive_device", "The optional hive device (program version 2.1) listens every few minutes. A small bee detector on the device decides for each 10-second reading if it passes as bee sound; the device says bees are present only when 3 of the last 4 readings pass, because a colony hums all the time while house and road sounds come and go. Trained on recordings from 70 hives against 57 kinds of other sounds; on apiaries and sounds it had not heard (Colab notebook Part 8, 11 Oct), with this rule about 80% of hive readings were reported as bees and about 1% of other-sound readings. With the device's own INMP441 microphone in a house it gave more false bee readings, so the beekeeper can teach it the place from the app ('No bees here' / 'Bees here'): it fits its last layer to that place; in a simulation of hard rooms this cut false bee windows from about 35% to about 2% while keeping about 82% of hive windows. Program 2.2 also has a listening range: sound quieter than the range counts as far away (not from inside the hive) and is not used; the range is set from the loudness heard while teaching, or 'hive only' (-50 dB), or 'everything'. On the house readings of 11 Oct, a range learned from 'No bees here' would have removed every false bee reading (0 of 53, against 16 of 53 without it). It has not yet been tested inside a real hive with its own microphone. Only bee-sound readings are used: the device learns the hive's normal bee-band loudness from them and raises an alert when a reading is more than ${NodeReading.Z_LIMIT} standard deviations away. It also raises a 'no bee sound' alert after several readings in a row without bees (only where it heard bees before). A DHT22 sensor measures temperature and humidity inside the hive, with a 'hot' alert at ${NodeReading.HOT_C} °C or more, two readings in a row. The device tells that something changed, not why.")
                    .put("problems", "Neither the app nor the device detects problems such as queen loss or pests from sound. Suggestions about problems come from the beekeeper's answers to five questions.")
                    .put(
                        "on_phone_learning",
                        "The app also trains small models on the phone from the beekeeper's own answers: a strength model that starts from the ${ModelData.TRAIN_HIVES}-hive model " +
                            "and is adjusted with the beekeeper's strength answers, a problem-chance model from the five questions, and an alert checker from the real / false alarm answers. " +
                            "Each check is predicted only from earlier answers. On the field recordings, at a new apiary the adjusted model was about as good as guessing the answer " +
                            "given most often so far, and for a hive checked before it was not better than repeating the last answer. Treat it as experimental."
                    )
                "get_hive_details" -> {
                    val hive = store.findHive(args.optString("hive")) ?: return unknownHive(args.optString("hive"))
                    val d = store.details(hive)
                    if (d.length() == 0) JSONObject().put("hive", hive).put("message", "The beekeeper has not written any details for this hive yet.")
                    else d.put("hive", hive)
                }
                "save_hive_details" -> {
                    val hive = store.findHive(args.optString("hive")) ?: return unknownHive(args.optString("hive"))
                    val fields = LinkedHashMap<String, String>()
                    for ((from, to) in DETAIL_FIELDS) {
                        val v = argText(args, from)
                        if (v.isNotEmpty()) fields[to] = v
                    }
                    val changed = store.saveDetails(hive, fields)
                    val note = argText(args, "note")
                    if (note.isNotEmpty()) store.addNote(hive, note)
                    JSONObject().put("hive", hive).put("saved_fields", JSONArray(changed)).put("note_saved", note.isNotEmpty())
                }
                "get_node_latest" -> {
                    val hive = args.optString("hive").trim().uppercase(Locale.US)
                    val r = cloud.latest(hive)
                        ?: return JSONObject().put("hive", hive).put("message", "No hive device has sent data under this hive name.")
                    r.toJson().put("hive", hive)
                        .put("device_online", r.online())
                        .put("device_program", if (r.checksBees()) "version ${r.fw.ifEmpty { "2" }}, with the bee detector" else "old version without the bee detector: its loudness may be any sound, not only bees")
                        .put("temperature_sensor_answered", r.dhtOk)
                        .put("hive_normal_db", r.normalDb)
                        .put("hive_normal_spread_db", r.normalSd)
                        .put("learning_progress", "${r.normalCount} of ${r.normalNeeded} bee-sound readings")
                        .put("reads_every_seconds", r.everySeconds)
                        .put("z_score_meaning", "Above ${NodeReading.Z_LIMIT} or below -${NodeReading.Z_LIMIT} counts as unusual.")
                }
                "get_node_history" -> {
                    val hive = args.optString("hive").trim().uppercase(Locale.US)
                    val all = cloud.readings(hive, args.optInt("limit", 60).coerceIn(10, 300))
                    if (all.isEmpty()) return JSONObject().put("hive", hive).put("message", "No readings from a hive device for this hive.")
                    val checked = all.filter { it.checksBees() }
                    // only bee-sound readings count as bee sound; very old readings without the detector are kept apart
                    val low = (if (checked.isEmpty()) all else all.filter { it.isBee() }).map { it.lowDb }
                    val temps = all.mapNotNull { it.tempC }
                    val hums = all.mapNotNull { it.humidity }
                    val newest = JSONArray()
                    for (r in all.takeLast(6).reversed()) newest.put(r.toJson())
                    val out = JSONObject().put("hive", hive)
                        .put("readings_looked_at", all.size)
                        .put("from", all.first().timeText())
                        .put("to", all.last().timeText())
                    if (checked.isEmpty()) {
                        out.put("bee_detector", "not on this device program: the loudness below may include sounds that are not bees")
                    } else {
                        out.put("readings_with_bee_sound", checked.count { it.isBee() })
                            .put("readings_with_other_sound", checked.count { !it.isBee() && !it.isSilent() })
                            .put("readings_silent", checked.count { it.isSilent() })
                    }
                    if (low.isNotEmpty()) {
                        out.put("bee_band_db_lowest", low.minOrNull() ?: 0.0)
                            .put("bee_band_db_highest", low.maxOrNull() ?: 0.0)
                            .put("bee_band_db_average", Math.round(low.average() * 10.0) / 10.0)
                    } else {
                        out.put("bee_band_db", "no bee-sound readings in this period")
                    }
                    if (temps.isNotEmpty()) {
                        out.put("temperature_c_lowest", temps.minOrNull() ?: 0.0)
                            .put("temperature_c_highest", temps.maxOrNull() ?: 0.0)
                            .put("temperature_c_average", Math.round(temps.average() * 10.0) / 10.0)
                    }
                    if (hums.isNotEmpty()) out.put("humidity_percent_average", Math.round(hums.average()))
                    out.put("unusual_readings", all.count { it.status() == NodeReading.UNUSUAL })
                        .put("newest_first", newest)
                }
                "get_alerts" -> {
                    val wanted = argText(args, "hive").uppercase(Locale.US)
                    val onlyOpen = args.optBoolean("only_open", false)
                    val out = JSONArray()
                    for (a in cloud.alerts(20)) {
                        if (wanted.isNotEmpty() && !a.hive.equals(wanted, ignoreCase = true)) continue
                        if (onlyOpen && a.ack) continue
                        out.put(a.toJson())
                    }
                    if (out.length() == 0) JSONObject().put("message", "There are no such alerts.") else out
                }
                "get_weather" -> cloud.weather()
                "save_alert_review" -> {
                    val id = argText(args, "alert_id")
                    val label = when (argText(args, "label").lowercase(Locale.US)) {
                        "likely_real" -> "likely_real"
                        "likely_false" -> "likely_false"
                        else -> "unsure"
                    }
                    val explanation = argText(args, "explanation")
                    if (id.isEmpty() || explanation.isEmpty()) return JSONObject().put("error", "alert_id and explanation are needed.")
                    if (cloud.alerts(50).none { it.id == id }) return JSONObject().put("error", "There is no alert with this alert_id. Call get_alerts first.")
                    cloud.saveReview(id, label, explanation, store.modelName())
                    JSONObject().put("saved", true).put("label", label)
                }
                "escalate" -> {
                    val id = argText(args, "alert_id")
                    val alert = cloud.alerts(50).firstOrNull { it.id == id }
                        ?: return JSONObject().put("error", "There is no alert with this alert_id. Call get_alerts first.")
                    if (alert.escalated) return JSONObject().put("already_sent", true)
                    cloud.escalate(id, argText(args, "reason").ifEmpty { "Sent on by the assistant" })
                    JSONObject().put("sent_to_second_contact", true).put("hive", alert.hive)
                }
                "send_message" -> {
                    val message = argText(args, "text")
                    if (message.isEmpty()) return JSONObject().put("error", "The message text is empty.")
                    cloud.sendMessage(message, "assistant")
                    JSONObject().put("sent", true)
                }
                else -> JSONObject().put("error", "Unknown tool: $name")
            }
        } catch (e: UnknownHostException) {
            JSONObject().put("error", "No internet connection, so this could not be read.")
        } catch (e: Exception) {
            JSONObject().put("error", "Tool failed: " + (e.message ?: "unknown error"))
        }
    }

    private val DETAIL_FIELDS = listOf(
        "district" to "district", "flow" to "flow", "hive_type" to "hiveType",
        "colony_age_months" to "colonyAgeMonths", "days_since_opened" to "daysSinceOpened", "weather" to "weather"
    )

    /** A tool value as text, also when the model sent a number. */
    private fun argText(args: JSONObject, key: String): String =
        if (!args.has(key) || args.isNull(key)) "" else args.get(key).toString().trim()

    private fun unknownHive(name: String): JSONObject =
        JSONObject().put("error", "There is no hive named '$name' in the app.").put("hives_in_app", JSONArray(store.hives()))

    private fun summary(r: CheckRecord, learning: Boolean = false): JSONObject {
        val o = JSONObject()
        o.put("hive", r.hive)
        o.put("time", r.timeText())
        o.put("outcome", r.outcome)
        o.put(
            "outcome_meaning", when (r.outcome) {
                Analyzer.READING -> "A strength estimate was made."
                Analyzer.UNCLEAR -> "The clips did not agree, so the estimate is not reliable."
                Analyzer.QUIET -> "Very little sound was recorded. The colony may be gone, or the microphone was not working."
                else -> "Too much background noise. Fewer than ${Analyzer.MIN_CLEAN_CLIPS} clean clips."
            }
        )
        if (r.strength != null) {
            o.put("strength_estimate", r.strength)
            o.put("clips_that_agreed", "${r.agree} of ${r.cleanClips}")
        }
        o.put("clean_clips", r.cleanClips)
        o.put("total_clips", r.totalClips)
        o.put("clean_clips_needed", Analyzer.MIN_CLEAN_CLIPS)
        o.put("seconds_listened", Analyzer.SKIP_SECONDS + r.totalClips * Analyzer.CLIP_SECONDS)
        o.put("seconds_of_clean_bee_sound_kept", r.cleanClips * Analyzer.CLIP_SECONDS)
        o.put("loudness_db", Math.round(r.rmsDb * 10.0) / 10.0)
        o.put("microphone_mode", r.micMode)
        when (store.learnInfo(r.id)?.optString("filter") ?: "") {
            "gate" -> o.put("sound_filter", "bee-sound filter (YAMNet + small model) and basic rule; parts thrown away for other sounds: " + (store.learnInfo(r.id)?.optInt("filterClips") ?: 0))
            "gate_error" -> o.put("sound_filter", "bee-sound filter (YAMNet + small model) and basic rule; the filter had an error on some parts and those parts were thrown away; parts thrown away: " + (store.learnInfo(r.id)?.optInt("filterClips") ?: 0))
            "basic" -> o.put("sound_filter", "basic rule only (an older check, made before the bee-sound filter was compulsory): talking or engine sound may have been kept")
        }
        if (r.votes.isNotEmpty()) {
            val votes = JSONObject()
            for ((k, v) in r.votes) votes.put(k, v)
            o.put("clip_votes", votes)
        }
        val normal = JSONObject().put("status", r.normalStatus)
        when (r.normalStatus) {
            Store.LEARNING -> normal.put("meaning", "The app is still learning this hive's normal sound (${r.normalCount} of ${Store.BASELINE_CHECKS} checks).")
            Store.NORMAL -> normal.put("meaning", "The sound is close to this hive's own earlier checks.")
            Store.CHANGED -> normal.put("meaning", "The sound differs from this hive's own earlier checks. This is a reason to inspect, not proof of a problem.")
            else -> normal.put("meaning", "Not compared, because this check had too few clean clips.")
        }
        if (r.normalDistance != null) {
            normal.put("distance_db", Math.round(r.normalDistance * 100.0) / 100.0)
            normal.put("limit_db", Analyzer.CHANGE_LIMIT_DB)
        }
        o.put("own_normal", normal)
        if (Advice.QUESTIONS.any { it.id in r.answers }) {
            val ans = JSONObject()
            for (q in Advice.QUESTIONS) {
                val a = r.answers[q.id]
                if (a != null) ans.put(q.english, a)
            }
            o.put("beekeeper_answers", ans)
            o.put("suggestions_from_answers", JSONArray(Advice.suggestions(r.answers, false)))
        } else {
            o.put("beekeeper_answers", "The beekeeper has not answered the five questions for this check.")
        }
        val judged = Learner.judgement(r)
        o.put("beekeeper_strength_judgement", judged ?: "not given")
        if (learning && r.shape != null) o.put("app_own_model", ownModelJson(r))
        return o
    }

    /**
     * What the models trained on this phone say about one check (each learned only from answers for earlier checks).
     * The app's own strength guess is only given after the beekeeper has given their own judgement, so it cannot sway it.
     */
    private fun ownModelJson(r: CheckRecord): JSONObject {
        val o = JSONObject()
        val frozen = Learner.frozenGuess(store, r.id)
        if (Learner.judgement(r) != null) {
            val own = if (frozen != null) textOf(frozen, "own") else ""
            if (own.isNotEmpty()) {
                o.put("strength_guessed_before_the_beekeeper_answered", own)
                o.put("learned_from_beekeeper_answers", frozen?.optInt("ownN") ?: 0)
                o.put("was_right", own == Learner.judgement(r))
            } else {
                o.put("strength", "the app's own model was not ready when the beekeeper answered")
            }
        } else {
            val ready = if (frozen != null) frozen.has("own") else Learner.strengthAnswers(store, r.id) >= Learner.MIN_STRENGTH
            o.put(
                "strength",
                if (ready) "hidden until the beekeeper gives their own strength judgement in the questions (this keeps the score fair); do not guess it"
                else "not ready: needs ${Learner.MIN_STRENGTH} strength answers for earlier checks, has ${Learner.strengthAnswers(store, r.id)}"
            )
        }
        val problem = if (frozen != null && frozen.has("problem")) Learner.Chance(frozen.optDouble("problem"), frozen.optDouble("usual"), frozen.optInt("problemN"))
        else Learner.ownProblem(store, r)
        if (problem != null) {
            o.put("problem_chance", Math.round(problem.chance * 100.0).toString() + "%")
            o.put("usual_problem_rate_in_beekeeper_answers", Math.round(problem.usual * 100.0).toString() + "%")
            o.put("problem_learned_from_answers", problem.examples)
        }
        o.put("meaning", "These models are trained on this phone from the beekeeper's own answers. They are experimental. Compare them with simple guesses on the What the app has learned screen before trusting them.")
        return o
    }

    private fun describeTool(name: String, args: JSONObject): String {
        val hive = args.optString("hive")
        val si = store.sinhala()
        return when (name) {
            "list_hives" -> if (si) "මී පෙට්ටි ලැයිස්තුව බැලුවා" else "Looked at the list of hives"
            "get_latest_check" -> if (si) "$hive පෙට්ටියේ අන්තිම පරීක්ෂාව බැලුවා" else "Read the latest check of $hive"
            "get_history" -> if (si) "$hive පෙට්ටියේ කලින් පරීක්ෂා බැලුවා" else "Read the history of $hive"
            "get_model_info" -> if (si) "ශබ්දය හඳුනන හැටි ගැන තොරතුරු බැලුවා" else "Read the facts about the sound model"
            "get_hive_details" -> if (si) "$hive පෙට්ටියේ විස්තර බැලුවා" else "Read the details of $hive"
            "save_hive_details" -> if (si) "$hive පෙට්ටියේ විස්තර සේව් කළා" else "Saved details of $hive"
            "get_node_latest" -> if (si) "$hive උපකරණයේ අන්තිම මිනුම බැලුවා" else "Read the latest device reading of $hive"
            "get_node_history" -> if (si) "$hive උපකරණයේ කලින් මිනුම් බැලුවා" else "Read the device history of $hive"
            "get_alerts" -> if (si) "අනතුරු ඇඟවීම් බැලුවා" else "Read the alerts"
            "get_weather" -> if (si) "කාලගුණය බැලුවා" else "Checked the weather"
            "save_alert_review" -> if (si) "අනතුරු ඇඟවීම ගැන අදහස සේව් කළා" else "Saved its review of the alert"
            "escalate" -> if (si) "අනතුරු ඇඟවීම දෙවැනි කෙනාට යැව්වා" else "Sent the alert on to the second contact"
            "send_message" -> if (si) "දෙවැනි කෙනාට පණිවිඩයක් යැව්වා" else "Sent a message to the second contact"
            else -> if (si) "$name පාවිච්චි කළා" else "Used $name"
        }
    }

    // ---------- network ----------

    /** Sends to Groq, or to our own small server when the app is built with one (the server adds the key). */
    private fun postGroq(key: String, body: JSONObject): JSONObject {
        val proxy = store.useProxy()
        val address = if (proxy) store.aiProxyUrl() else groqUrl
        val headers = LinkedHashMap<String, String>()
        if (proxy) {
            if (store.aiProxyToken().isNotEmpty()) headers["X-App-Token"] = store.aiProxyToken()
        } else {
            headers["Authorization"] = "Bearer $key"
        }
        // GPT-OSS models think before they answer; "low" keeps that short, so fewer tokens count against the free limit
        if (body.optString("model").startsWith("openai/gpt-oss")) body.put("reasoning_effort", "low")
        return try {
            postWaiting(address, headers, body)
        } catch (e: RateLimited) {
            // the main model's free limit is used up for now: the smaller model has its own limit, so try that once
            if (body.optString("model") == FALLBACK_GROQ_MODEL) throw e
            body.put("model", FALLBACK_GROQ_MODEL)
            usedFallback = true
            postWaiting(address, headers, body)
        }
    }

    private fun post(address: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val conn = URL(address).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            for ((name, value) in headers) conn.setRequestProperty(name, value)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code == 429) {
                val daily = isDailyLimit(text)
                throw RateLimited(serviceError(code, text), waitSecondsOf(conn.getHeaderField("retry-after"), text), daily)
            }
            if (code !in 200..299) throw IOException(serviceError(code, text))
            rememberTokens(conn)
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Like post, but when the free service says "too many requests" (code 429) it waits as long as the service asks
     * (at most about a minute each time) and tries again, up to MAX_WAITS times. A daily limit is not waited for.
     */
    private fun postWaiting(address: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        synchronized(NET) {
        var waited = 0.0
        var tries = 0
        while (true) {
            waitForTokens(body)
            try {
                return post(address, headers, body)
            } catch (e: RateLimited) {
                tries++
                val wait = (if (e.waitSeconds > 0) e.waitSeconds else 20.0) + 1.0
                if (e.daily || tries > MAX_WAITS || wait > MAX_WAIT_SECONDS || waited + wait > MAX_TOTAL_WAIT_SECONDS) throw e
                Thread.sleep((wait * 1000.0).toLong())
                waited += wait
            }
        }
        }
    }

    /** Keeps what the service says about its per-minute token limit (Groq sends it with every answer). */
    private fun rememberTokens(conn: HttpURLConnection) {
        val left = conn.getHeaderField("x-ratelimit-remaining-tokens")?.trim()?.toIntOrNull() ?: return
        val reset = durationSeconds(conn.getHeaderField("x-ratelimit-reset-tokens") ?: "")
        tokensLeft = left
        tokensResetAt = System.currentTimeMillis() + (if (reset > 0) (reset * 1000.0).toLong() else 60_000L)
    }

    /** Waits before sending when the last answer said this request would go over the per-minute limit. */
    private fun waitForTokens(body: JSONObject) {
        val left = tokensLeft
        if (left < 0) return
        val need = body.toString().length / 3 + 1100     // about 3 characters per token, plus the answer
        val wait = tokensResetAt - System.currentTimeMillis()
        if (left < need && wait in 1..60_000) Thread.sleep(wait + 500)
        tokensLeft = -1
    }

    /** "7.66s", "1m26.4s", "450ms", "2h3m" -> seconds (0 when it cannot be read). */
    private fun durationSeconds(text: String): Double {
        val m = Regex("^\\s*(?:(\\d+)h)?(?:(\\d+)m(?!s))?(?:([\\d.]+)(ms|s))?\\s*$", RegexOption.IGNORE_CASE).find(text) ?: return 0.0
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val min = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val num = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val sec = if (m.groupValues[4].lowercase(Locale.US) == "ms") num / 1000.0 else num
        return h * 3600.0 + min * 60.0 + sec
    }

    /** A tool result, cut when it is very long, so one request does not use up the free per-minute limit. */
    private fun shortText(text: String): String =
        if (text.length <= MAX_TOOL_TEXT) text else text.substring(0, MAX_TOOL_TEXT) + " ...(cut: too long)"

    /** True when the service says the limit for the whole day is used up. */
    private fun isDailyLimit(text: String): Boolean {
        val t = text.lowercase(Locale.US)
        return t.contains("per day") || t.contains("per_day") || t.contains("(tpd)") || t.contains("(rpd)")
    }

    /** Seconds to wait: from the retry-after header, or from "try again in 1m2.5s" / "7.6s" / "450ms" in the message. */
    private fun waitSecondsOf(header: String?, text: String): Double {
        header?.trim()?.toDoubleOrNull()?.let { if (it > 0) return it }
        val m = Regex("(?:try again|retry) in (?:(\\d+)h)?(?:(\\d+)m(?!s))?(?:([\\d.]+)(ms|s))?", RegexOption.IGNORE_CASE).find(text) ?: return -1.0
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val min = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val num = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val sec = if (m.groupValues[4].lowercase(Locale.US) == "ms") num / 1000.0 else num
        val total = h * 3600.0 + min * 60.0 + sec
        return if (total > 0) total else -1.0
    }

    private fun serviceError(code: Int, text: String): String {
        val detail = try {
            JSONObject(text).optJSONObject("error")?.optString("message") ?: ""
        } catch (_: Exception) {
            ""
        }
        val si = store.sinhala()
        val hint = when (code) {
            401, 403 -> if (si) "API key එක වැරදියි වගේ. සැකසුම්වලට ගිහින් key එක ආයෙත් දාන්න, නැත්නම් අනෙක් සේවාව තෝරන්න."
            else "The service did not accept the API key. In Settings, paste the key again, or try the other service."
            400 -> if (si) "ඉල්ලීම පිළිගත්තේ නැහැ. සැකසුම්වල තියෙන model නම හරිද බලන්න."
            else "The service did not accept the request. Check the model name in Settings."
            404 -> if (si) "සැකසුම්වල තියෙන model නම හොයාගන්න බැහැ." else "The model name in Settings was not found."
            429 -> if (isDailyLimit(text)) {
                if (si) "අද දවසට AI සහායකයාගේ නොමිලේ සීමාව ඉවරයි. පස්සේ ආයෙත් උත්සාහ කරන්න. ඔබේ තොරතුරු සේරම ආරක්ෂිතයි."
                else "The assistant has used today's free AI limit. Please try again later. All your data is safe."
            } else {
                if (si) "AI සහායකයා දැන් කාර්යබහුලයි. විනාඩියකින් ආයෙත් උත්සාහ කරන්න."
                else "The assistant is busy right now. Please try again in a minute."
            }
            else -> if (si) "AI සේවාවේ ප්‍රශ්නයක් තියෙනවා. ටිකකින් ආයෙත් උත්සාහ කරන්න." else "The service had a problem. Try again later."
        }
        if (code == 429) return hint
        return "$hint (code $code" + (if (detail.isNotEmpty()) ": " + detail.take(200) else "") + ")"
    }

    private fun friendlyError(e: Exception): String {
        val si = store.sinhala()
        return when (e) {
            is UnknownHostException ->
                if (si) "ඉන්ටර්නෙට් නැහැ. AI සහායකයාට ඉන්ටර්නෙට් ඕනෑ. මී පෙට්ටි පරීක්ෂාව ඉන්ටර්නෙට් නැතුවත් කරන්න පුළුවන්."
                else "No internet connection. The assistant needs internet. Hive checks still work offline."
            is java.net.SocketTimeoutException ->
                if (si) "ඉන්ටර්නෙට් ගොඩක් හෙමින්. ආයෙත් උත්සාහ කරන්න." else "The connection is too slow. Please try again."
            else -> e.message ?: (if (si) "මොකක් හරි වැරදීමක් වුණා." else "Something went wrong.")
        }
    }
}
