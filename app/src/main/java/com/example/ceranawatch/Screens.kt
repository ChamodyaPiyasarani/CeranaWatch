package com.example.ceranawatch

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- do a check: listen until there is one minute of bee sound, then analyse ----------

/** "1:05" */
fun minutesText(seconds: Int): String = "" + (seconds / 60) + ":" + (if (seconds % 60 < 10) "0" else "") + (seconds % 60)

@Composable
fun CheckScreen(store: Store, cloud: Cloud, hive: String, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { HiveRecorder(context.applicationContext) }

    var phase by remember { mutableStateOf("ready") }      // ready, listening, analysing
    var secondsDone by remember { mutableStateOf(0) }       // how long the phone has listened
    var level by remember { mutableStateOf(0f) }
    var cleanSeconds by remember { mutableStateOf(0) }      // bee sound collected so far
    var thrownAway by remember { mutableStateOf(0) }        // clips that were not kept
    var state by remember { mutableStateOf(CleanCollector.WAIT) }
    var note by remember { mutableStateOf("") }             // "", short, permission, or an error text

    // stop the microphone if the screen is closed
    DisposableEffect(Unit) {
        onDispose { recorder.requestStop() }
    }

    val wantedSeconds = Analyzer.TARGET_CLEAN_CLIPS * Analyzer.CLIP_SECONDS
    val neededSeconds = Analyzer.MIN_CLEAN_CLIPS * Analyzer.CLIP_SECONDS
    val maxSeconds = Analyzer.MAX_LISTEN_SECONDS

    fun start() {
        phase = "listening"
        secondsDone = 0
        level = 0f
        cleanSeconds = 0
        thrownAway = 0
        state = CleanCollector.WAIT
        note = ""
        scope.launch {
            try {
                // the bee-sound filter (YAMNet + our small model) is compulsory: without it nothing is recorded,
                // because the basic rule alone keeps talking, engines and room noise as if they were bees
                val yam = withContext(Dispatchers.IO) { YamNet.get(context.applicationContext) }
                if (yam == null) {
                    note = "gate:" + YamNet.loadError
                    phase = "ready"
                    return@launch
                }
                var gateErrors = 0
                val gate: (FloatArray) -> Boolean = { clip ->
                    try {
                        BeeGate.isBee(clip) { window -> yam.scores(window) }
                    } catch (e: Exception) {
                        gateErrors++
                        false      // a clip that could not be checked is never kept as bee sound
                    }
                }
                val collector = CleanCollector(beeGate = gate)
                withContext(Dispatchers.IO) {
                    recorder.listen(maxSeconds) { chunk, count, sec, lvl ->
                        val enough = collector.add(chunk, count)
                        secondsDone = sec
                        level = lvl
                        cleanSeconds = collector.cleanSeconds()
                        thrownAway = collector.otherClips + collector.silentClips
                        state = collector.lastState
                        enough
                    }
                }
                if (collector.heardClips == 0) {
                    // stopped before the first clip was judged: nothing to save
                    note = "short"
                    phase = "ready"
                } else {
                    phase = "analysing"
                    val filter = if (gateErrors > 0) "gate_error" else "gate"
                    val id = withContext(Dispatchers.Default) {
                        saveAndAnalyse(store, cloud, recorder, hive, collector, filter)
                    }
                    go(Screen.ResultView(id))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                note = e.message ?: "unknown error"
                phase = "ready"
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            start()
        } else {
            note = "permission"
        }
    }

    Page(
        L("Check $hive", "$hive පරීක්ෂාව"),
        L("One minute of bee sound from inside the hive", "පෙට්ටිය ඇතුළේ මී මැස්සන්ගේ ශබ්දය විනාඩියක්"),
        onBack = {
            recorder.requestStop()
            go(Screen.HiveView(hive))
        },
        art = ART_BEE
    ) {
        if (phase == "ready") {
            Panel {
                SectionTitle(L("Before you start", "පටන් ගන්න කලින්"))
                StepLine(
                    "1",
                    L(
                        "Lift the cover, lay the microphone inside the hive above the combs, and close the cover.",
                        "පියන උස්සලා, මයික් එක පෙට්ටිය ඇතුළේ මී වදවලට උඩින් තියලා, පියන වහන්න."
                    )
                )
                StepLine("2", L("Stand still and stay quiet.", "හෙල්ලෙන්නේ නැතුව, කතා කරන්නේ නැතුව ඉන්න."))
                StepLine("3", L("Keep away from roads, radios and people talking.", "පාරවල්, රේඩියෝ සහ කතා කරන අයගෙන් ඈත් වෙන්න."))
            }
            Panel(soft = true) {
                Text(
                    L(
                        "The app keeps only the parts with bee sound. Parts with talking, birds, horns or other sounds are thrown away, " +
                            "and the app waits for bee sound again. It stops by itself when it has 1 minute of bee sound, or after " +
                            (maxSeconds / 60) + " minutes.",
                        "ඇප් එක තියාගන්නේ මී මැස්සන්ගේ ශබ්දය තියෙන කොටස් විතරයි. කතා, කුරුල්ලන්ගේ සද්ද, හෝන් සද්ද වගේ වෙනත් ශබ්ද තියෙන කොටස් අයින් කරලා, " +
                            "ආයෙත් මී මැස්සන්ගේ ශබ්දය එනකල් බලන් ඉන්නවා. මී මැස්සන්ගේ ශබ්දය විනාඩියක් එකතු වුණාම, නැත්නම් විනාඩි " +
                            (maxSeconds / 60) + "කට පස්සේ ඇප් එක ඉබේම නවතිනවා."
                    ),
                    color = Muted, style = MaterialTheme.typography.bodyMedium
                )
            }
            BigButton(L("Start listening", "අහන්න පටන් ගන්න")) {
                val allowed = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (allowed) start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            if (note.isNotEmpty()) {
                val text = when (note) {
                    "short" -> L(
                        "It was stopped too early, so nothing was saved. Please start again.",
                        "ඉක්මනට නැවැත්තුව නිසා කිසි දෙයක් සේව් වුණේ නැහැ. ආයෙත් පටන් ගන්න."
                    )
                    "permission" -> L(
                        "The app needs the microphone permission to record.",
                        "රෙකෝඩ් කරන්න ඇප් එකට මයික් එක පාවිච්චි කරන්න අවසර ඕනෑ."
                    )
                    else -> if (note.startsWith("gate:")) L(
                        "The bee-sound recogniser could not start on this phone, so the check cannot run. The app only records when it can recognise bee sound. Close the app fully, open it again and try again. If it still does not work, install the app again.",
                        "මී මැසි ශබ්දය හඳුනාගන්න කොටස මේ ෆෝන් එකේ පටන් ගන්න බැරි වුණා, ඒ නිසා පරීක්ෂාව කරන්න බැහැ. ඇප් එක රෙකෝඩ් කරන්නේ මී මැසි ශබ්දය හඳුනාගන්න පුළුවන් වුණොත් විතරයි. ඇප් එක සම්පූර්ණයෙන් වහලා ආයෙත් ඇරලා උත්සාහ කරන්න. තවමත් වැඩ නැත්නම් ඇප් එක ආයෙත් ඉන්ස්ටෝල් කරන්න."
                    ) + (if (note.length > 5) " (" + note.substring(5) + ")" else "")
                    else L("Problem: ", "ප්‍රශ්නයක්: ") + note
                }
                Panel(accent = WarnRed) { Text(text, color = Ink) }
            }
            Text(
                L("This works without internet.", "මේකට ඉන්ටර්නෙට් ඕනෑ නැහැ."),
                style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(start = 4.dp)
            )
        }

        if (phase == "listening") {
            val db = WavIO.toDb(level.toDouble())
            val fraction = ((db + 70.0) / 70.0).coerceIn(0.0, 1.0).toFloat()
            Panel {
                SectionTitle(L("Listening. Please stay quiet.", "අහගෙන ඉන්නවා. කතා කරන්නේ නැතුව ඉන්න."))
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Ring(cleanSeconds.toFloat() / wantedSeconds)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(cleanSeconds.toString(), fontSize = 52.sp, lineHeight = 56.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                        Text(
                            L("of $wantedSeconds seconds", "තත්පර ${wantedSeconds}න්"),
                            color = Muted, style = MaterialTheme.typography.bodySmall
                        )
                        Text(L("of bee sound", "මී මැස්සන්ගේ ශබ්දය"), color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                // what the last 3 seconds were
                val stateColor = when (state) {
                    CleanCollector.BEE -> GoodGreen
                    CleanCollector.OTHER -> Amber
                    CleanCollector.SILENT -> WarnRed
                    else -> InfoBlue
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.size(12.dp).background(stateColor, CircleShape))
                    Text(
                        when (state) {
                            CleanCollector.BEE -> L("Bee sound. Keeping it.", "මී මැස්සන්ගේ ශබ්දය. තියාගන්නවා.")
                            CleanCollector.OTHER -> L(
                                "Other sound (talking, bird, horn...). Not kept. Waiting for bee sound.",
                                "වෙනත් ශබ්දයක් (කතා, කුරුල්ලෝ, හෝන්...). ගත්තේ නැහැ. මී මැස්සන්ගේ ශබ්දය එනකල් බලන් ඉන්නවා."
                            )
                            CleanCollector.SILENT -> L(
                                "No sound. Is the microphone inside the hive and plugged in?",
                                "ශබ්දයක් නැහැ. මයික් එක පෙට්ටිය ඇතුළේද? ෆෝන් එකට හරියට සම්බන්ධ කරලාද?"
                            )
                            else -> L(
                                "Starting. The first " + Analyzer.SKIP_SECONDS + " seconds are not used, so the bees can settle.",
                                "පටන් ගන්නවා. මී මැස්සෝ සන්සුන් වෙනකල් මුල් තත්පර " + Analyzer.SKIP_SECONDS + " ගන්නේ නැහැ."
                            )
                        },
                        color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    L("Listened: ", "අහපු වෙලාව: ") + minutesText(secondsDone) + L(" of at most ", " (උපරිම ") + minutesText(maxSeconds) + L("", ")") +
                        "   ·   " + L("Parts not kept: ", "ගත්තේ නැති කොටස්: ") + thrownAway,
                    color = Muted, style = MaterialTheme.typography.bodySmall
                )
                Text(L("Sound level now: ", "දැන් ශබ්ද මට්ටම: ") + oneDecimal(db) + " dB", color = Muted, style = MaterialTheme.typography.bodySmall)
                FillBar(fraction, if (db > -3.0) WarnRed else Ink)
            }
            SecondButton(
                if (cleanSeconds >= neededSeconds) L("Stop and use what I have", "නවත්තලා දැනට තියෙන එකෙන් බලන්න")
                else L("Stop", "නවත්තන්න")
            ) { recorder.requestStop() }
            if (cleanSeconds < neededSeconds) {
                Text(
                    L(
                        "At least $neededSeconds seconds of bee sound are needed for a result.",
                        "ප්‍රතිඵලයක් දෙන්න මී මැස්සන්ගේ ශබ්දය අඩුම තත්පර ${neededSeconds}ක් ඕනෑ."
                    ),
                    style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(start = 4.dp)
                )
            }
        }

        if (phase == "analysing") {
            Panel {
                BusyLine(L("Looking at the bee sound...", "මී මැස්සන්ගේ ශබ්දය බලනවා..."))
            }
        }
    }
}

/** One numbered line of instructions. */
@Composable
fun StepLine(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.size(30.dp).background(Ink, CircleShape), contentAlignment = Alignment.Center) {
            Text(number, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Text(text, color = Ink, modifier = Modifier.weight(1f))
    }
}

/**
 * Saves the collected bee sound as a file, runs the sound model, compares with the hive's own normal,
 * and stores the check. The file holds only the clean clips, one after the other.
 */
private fun saveAndAnalyse(store: Store, cloud: Cloud, recorder: HiveRecorder, hive: String, collector: CleanCollector, filter: String): Long {
    val now = System.currentTimeMillis()
    val result = Analyzer.analyzeCollected(collector)
    var fileName = ""
    if (collector.cleanClips > 0) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val file = File(recorder.recordingsDir(), hive + "_" + stamp + ".wav")
        WavIO.write(file, collector.keptSamples(), HiveRecorder.SAMPLE_RATE)
        recorder.exportCopy(file)
        fileName = file.name
    }
    val normal = store.judgeNormal(hive, result.shape)
    val record = CheckRecord(
        id = now,
        hive = hive,
        outcome = result.outcome,
        strength = result.strength,
        agree = result.agree,
        cleanClips = result.cleanClips,
        totalClips = result.totalClips,
        rmsDb = result.rmsDb,
        micMode = recorder.sourceUsed,
        wavName = fileName,
        shape = result.shape,
        normalStatus = normal.first,
        normalDistance = normal.second,
        normalCount = normal.third,
        answers = emptyMap(),
        suggestions = emptyList(),
        votes = result.votes
    )
    // the hive details at this moment, so the on-phone learning can use them later
    store.saveClipShapes(now, result.clips.mapNotNull { it.shape })   // every clean clip, for the on-phone learning
    store.saveLearnInfo(
        now,
        JSONObject().put("details", store.detailsForLearning(hive)).put("filter", filter).put("filterClips", collector.gateClips)
    )
    store.saveCheck(record)
    uploadLater(store, cloud, now)
    return now
}

/** Sends a copy of a check to the cloud in the background. Nothing happens when there is no cloud or no internet. */
fun uploadLater(store: Store, cloud: Cloud, id: Long) {
    if (!cloud.ready()) return
    Thread {
        try {
            val record = store.check(id)
            if (record != null) cloud.uploadCheck(record, store.details(record.hive))
        } catch (_: Exception) {
        }
    }.start()
}

/**
 * After a check was deleted: marks its cloud copy as deleted and sends the other checks of the hive again,
 * because their "own normal" result may have changed. Nothing happens without cloud or internet.
 */
fun deletedLater(store: Store, cloud: Cloud, hive: String, id: Long) {
    if (!cloud.ready()) return
    Thread {
        try {
            cloud.markCheckDeleted(hive, id)
            val details = store.details(hive)
            for (r in store.checks(hive)) cloud.uploadCheck(r, details)
        } catch (_: Exception) {
        }
    }.start()
}

// ---------- result ----------

/** The big title of a result card. */
@Composable
fun ResultTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Ink)
}

@Composable
fun ResultScreen(store: Store, cloud: Cloud, agent: Agent, id: Long, go: (Screen) -> Unit) {
    val r = store.check(id)
    if (r == null) {
        Page(L("Result", "ප්‍රතිඵලය"), "", onBack = { go(Screen.Home) }) {
            EmptyState(ART_BEE, L("This check was not found.", "මේ පරීක්ෂාව හොයාගන්න බැහැ."), "")
        }
        return
    }
    val limit = Analyzer.CHANGE_LIMIT_DB
    val distance = oneDecimal(r.normalDistance ?: 0.0)

    Page(r.hive, clock(r.id), onBack = { go(Screen.HiveView(r.hive)) }, art = ART_BEE) {
        // 1. what the sound model says
        when (r.outcome) {
            Analyzer.READING -> Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconSpot(58.dp) { Doodle(ART_HIVE, 34.dp) }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(L("Strength estimate", "ජනපදයේ ශක්තිය (දළ අනුමානයක්)"), color = Muted, style = MaterialTheme.typography.bodySmall)
                        Text(strengthName(r.strength), fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                    }
                }
                ChipRow {
                    Chip(L("Experimental", "තවම අත්හදා බලනවා"), Amber)
                    Chip(
                        L("${r.agree} of ${r.cleanClips} clean clips agreed", "පැහැදිලි කොටස් ${r.cleanClips}න් ${r.agree}ක් කිව්වේ එකම දේ"),
                        outcomeColor(r)
                    )
                }
                VotesBar(r.votes)
                Text(
                    L(
                        "On hives the model has not heard before, this estimate is right about half the time.",
                        "ඇප් එක කලින් අහලා නැති පෙට්ටිවලදී මේ අනුමානය හරි යන්නේ දෙපාරකින් එක පාරක් විතරයි."
                    ),
                    style = MaterialTheme.typography.bodySmall, color = Muted
                )
            }
            Analyzer.UNCLEAR -> Panel(accent = Amber) {
                ResultTitle(L("Cannot tell from this recording", "මේ රෙකෝඩින් එකෙන් කියන්න බැහැ"))
                Text(
                    L(
                        "The clean clips did not agree (the largest group was ${r.agree} of ${r.cleanClips}). Try again early in the morning.",
                        "පැහැදිලි කොටස් එකිනෙකට ගැළපුණේ නැහැ (එකම දේ කිව්වේ ${r.cleanClips}න් ${r.agree}ක් විතරයි). උදේ පාන්දර ආයෙත් කරලා බලන්න."
                    ),
                    color = Muted
                )
                VotesBar(r.votes)
            }
            Analyzer.QUIET -> Panel(accent = WarnRed) {
                ResultTitle(L("Very little sound", "ශබ්දය ගොඩක් අඩුයි"))
                Text(
                    L(
                        "Check that the microphone is working and inside the hive. If it is, open the hive: the colony may have left.",
                        "මයික් එක වැඩ කරනවාද, ඒක පෙට්ටිය ඇතුළේ තියෙනවාද බලන්න. ඒ දෙකම හරි නම් පෙට්ටිය ඇරලා බලන්න: මී ජනපදය පෙට්ටිය දාලා ගිහින් වෙන්න පුළුවන්."
                    ),
                    color = Muted
                )
            }
            else -> Panel(accent = WarnRed) {
                val listened = Analyzer.SKIP_SECONDS + r.totalClips * Analyzer.CLIP_SECONDS
                val beeSeconds = r.cleanClips * Analyzer.CLIP_SECONDS
                val needed = Analyzer.MIN_CLEAN_CLIPS * Analyzer.CLIP_SECONDS
                ResultTitle(L("Not enough bee sound", "මී මැස්සන්ගේ ශබ්දය මදි"))
                Text(
                    L(
                        "The app listened for $listened seconds, but only $beeSeconds seconds were bee sound without other sounds. $needed seconds are needed.",
                        "ඇප් එක තත්පර ${listened}ක් ඇහුවා. ඒත් වෙනත් ශබ්ද නැතුව මී මැස්සන්ගේ ශබ්දය තිබුණේ තත්පර ${beeSeconds}ක් විතරයි. තත්පර ${needed}ක් ඕනෑ."
                    ),
                    color = Ink
                )
                Text(
                    L(
                        "Check that the microphone is inside the hive and the cover is closed. Move away from people and roads, stay quiet, and try again.",
                        "මයික් එක පෙට්ටිය ඇතුළේද, පියන වහලාද බලන්න. මිනිස්සුන්ගෙන් සහ පාරවල්වලින් ඈත් වෙලා, කතා කරන්නේ නැතුව, ආයෙත් කරලා බලන්න."
                    ),
                    color = Muted
                )
            }
        }

        // 2. this hive against its own normal
        when (r.normalStatus) {
            Store.CHANGED -> Panel(accent = WarnRed) {
                SectionTitle(L("Changed from its own normal", "සුපුරුදු ශබ්දයට වඩා වෙනස්"))
                FillBar(((r.normalDistance ?: 0.0) / (2.0 * limit)).toFloat(), WarnRed)
                Text(
                    L(
                        "The sound differs from this hive's earlier checks by $distance dB (limit $limit dB). This is a reason to inspect the hive, not proof of a problem. Please answer the five questions.",
                        "මේ පෙට්ටියේ කලින් පරීක්ෂාවලට වඩා ශබ්දය $distance dB කින් වෙනස් (සීමාව $limit dB). මේක පෙට්ටිය ඇරලා බලන්න හේතුවක් විතරයි, ප්‍රශ්නයක් තියෙනවා කියලා සාක්ෂියක් නෙවෙයි. ප්‍රශ්න පහට පිළිතුරු දෙන්න."
                    ),
                    color = Muted
                )
            }
            Store.NORMAL -> Panel(accent = GoodGreen) {
                SectionTitle(L("Sounds like its own normal", "සුපුරුදු ශබ්දය වගේමයි"))
                FillBar(((r.normalDistance ?: 0.0) / (2.0 * limit)).toFloat())
                Text(
                    L(
                        "The difference from this hive's earlier checks is $distance dB (limit $limit dB).",
                        "මේ පෙට්ටියේ කලින් පරීක්ෂා එක්ක වෙනස $distance dB යි (සීමාව $limit dB)."
                    ),
                    color = Muted
                )
            }
            Store.LEARNING -> Panel(accent = InfoBlue) {
                SectionTitle(L("Learning this hive's normal sound", "මේ පෙට්ටියේ සුපුරුදු ශබ්දය ඉගෙන ගන්නවා"))
                FillBar(r.normalCount.toFloat() / Store.BASELINE_CHECKS)
                Text(
                    L(
                        "Good check ${r.normalCount} of ${Store.BASELINE_CHECKS}. After ${Store.BASELINE_CHECKS} good checks the app compares each new check with them.",
                        "හොඳ පරීක්ෂා ${Store.BASELINE_CHECKS}න් ${r.normalCount} වැනි එක. හොඳ පරීක්ෂා ${Store.BASELINE_CHECKS}කට පස්සේ, ඇප් එක හැම අලුත් පරීක්ෂාවක්ම ඒවා එක්ක ගළපලා බලනවා."
                    ),
                    color = Muted
                )
            }
            else -> Panel(soft = true) {
                SectionTitle(L("Not compared with its own normal", "සුපුරුදු ශබ්දය එක්ක ගළපලා බලලා නැහැ"))
                Text(
                    L(
                        "This check did not have enough clean sound to compare.",
                        "ගළපලා බලන්න තරම් පැහැදිලි ශබ්දයක් මේ පරීක්ෂාවේ තිබුණේ නැහැ."
                    ),
                    color = Muted
                )
            }
        }

        // 3. what the models trained on this phone say (each learned only from answers given before this check)
        if (r.shape != null) LearnedCard(store, r, go)

        // 4. the beekeeper's answers to the five questions
        if (Advice.QUESTIONS.any { it.id in r.answers }) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Doodle(ART_FLOWER, 26.dp)
                    SectionTitle(L("From your answers", "ඔබේ පිළිතුරු අනුව"))
                }
                for (s in Advice.suggestions(r.answers, uiSinhala)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(modifier = Modifier.padding(top = 8.dp).size(6.dp).background(Ink, CircleShape))
                        Text(s, color = Ink, modifier = Modifier.weight(1f))
                    }
                }
                Text(
                    L(
                        "These suggestions come from your answers, not from the sound.",
                        "මේ යෝජනා එන්නේ ඔබේ පිළිතුරුවලින්, ශබ්දයෙන් නෙවෙයි."
                    ),
                    style = MaterialTheme.typography.bodySmall, color = Muted
                )
            }
        }

        BigButton(
            if (r.answers.isEmpty()) L("Answer the questions about this hive", "මේ පෙට්ටිය ගැන ප්‍රශ්නවලට පිළිතුරු දෙන්න")
            else L("Change my answers", "මගේ පිළිතුරු වෙනස් කරන්න")
        ) {
            go(Screen.QuestionsView(id))
        }
        SecondButton(L("Ask the assistant to explain this result", "මේ ප්‍රතිඵලය පැහැදිලි කරන්න කියලා සහායකයාට කියන්න")) { go(Screen.ChatView(r.hive)) }

        // 5. advice on the recording itself, written by the assistant (for the newest check of the hive)
        val newest = store.checks(r.hive).firstOrNull()?.id == id
        if (newest || store.savedText("coach_$id").isNotEmpty()) AssistantCard(
            store, agent, "coach_$id", Agent.COACH, r.hive,
            L("Recording coach", "රෙකෝඩ් කිරීම ගැන උපදෙස්"),
            L(
                "Ask the assistant how to make the next recording of this hive better.",
                "මේ පෙට්ටියේ ඊළඟ රෙකෝඩින් එක තව හොඳට කරන්නේ කොහොමද කියලා සහායකයාගෙන් අහන්න."
            ),
            L("Get advice on this recording", "මේ රෙකෝඩින් එක ගැන උපදෙස් ගන්න"),
            "Give recording advice for the latest check of hive " + r.hive + ". Read get_latest_check and get_weather. " +
                "Look at the outcome, the number of clean clips against the number needed, the loudness, and how the clips voted. " +
                "Give at most 3 short tips that would make the next recording better (where to put the microphone, time of day, noise, wind, rain). " +
                "If the recording was good, say so in one line and give one tip to keep it good. Do not judge the health of the hive."
        )

        Panel(soft = true) {
            SectionTitle(L("Details", "විස්තර"))
            Text(
                L("Clean clips: ${r.cleanClips} of ${r.totalClips}", "පැහැදිලි කොටස්: ${r.totalClips}න් ${r.cleanClips}"),
                color = Muted
            )
            Text(
                L(
                    "Listened for about " + (Analyzer.SKIP_SECONDS + r.totalClips * Analyzer.CLIP_SECONDS) + " seconds; kept " + (r.cleanClips * Analyzer.CLIP_SECONDS) + " seconds of bee sound.",
                    "තත්පර " + (Analyzer.SKIP_SECONDS + r.totalClips * Analyzer.CLIP_SECONDS) + "ක් විතර ඇහුවා. මී මැස්සන්ගේ ශබ්දය තත්පර " + (r.cleanClips * Analyzer.CLIP_SECONDS) + "ක් තියාගත්තා."
                ),
                color = Muted
            )
            Text(L("Loudness: ", "ශබ්දයේ සැර: ") + oneDecimal(r.rmsDb) + " dB", color = Muted)
            Text(L("Microphone mode: ", "මයික් එකේ ක්‍රමය: ") + r.micMode, color = Muted)
            val info = store.learnInfo(r.id)
            val thrown = info?.optInt("filterClips") ?: 0
            when (info?.optString("filter") ?: "") {
                "gate" -> Text(
                    L(
                        "Sound filter: bee-sound filter (YAMNet). Parts thrown away for talking, vehicles, wind or animals: $thrown.",
                        "ශබ්ද පෙරහන: මී මැස්සන්ගේ ශබ්දය වෙන් කරන පෙරහන (YAMNet). කතා, වාහන, හුළඟ, සතුන්ගේ සද්ද නිසා අයින් කරපු කොටස්: $thrown."
                    ),
                    color = Muted
                )
                "basic" -> Text(
                    L(
                        "Sound filter: basic only (the bee-sound filter could not start). Talking or engine sound may have been kept.",
                        "ශබ්ද පෙරහන: සරල පෙරහන විතරයි (මී මැස්සන්ගේ ශබ්දය වෙන් කරන පෙරහන පටන් ගත්තේ නැහැ). කතා හරි එන්ජින් සද්ද හරි ඉතුරු වෙලා තියෙන්න පුළුවන්."
                    ),
                    color = WarnRed
                )
                "gate_error" -> Text(
                    L(
                        "Sound filter: bee-sound filter (YAMNet). It had an error on some parts; those parts were thrown away, not kept. Parts thrown away: $thrown.",
                        "ශබ්ද පෙරහන: මී මැස්සන්ගේ ශබ්දය වෙන් කරන පෙරහන (YAMNet). සමහර කොටස්වලදී ඒකේ දෝෂයක් ආවා; ඒ කොටස් අයින් කළා, තියාගත්තේ නැහැ. අයින් කරපු කොටස්: $thrown."
                    ),
                    color = Amber
                )
            }
            Text(L("File: ", "ෆයිල් එක: ") + r.wavName, style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(
                L(
                    "The app listens in 3-second parts. A part with talking, birds, horns or other sounds is thrown away. Only the clean parts are kept and used.",
                    "ඇප් එක අහන්නේ තත්පර 3ක කොටස් විදිහට. කතා, කුරුල්ලෝ, හෝන් වගේ වෙනත් ශබ්ද තියෙන කොටසක් අයින් කරනවා. තියාගෙන පාවිච්චි කරන්නේ පැහැදිලි කොටස් විතරයි."
                ),
                style = MaterialTheme.typography.bodySmall, color = Muted
            )
        }

        // remove this check (for example a test, or a recording that went wrong)
        var askDelete by remember(id) { mutableStateOf(false) }
        if (!askDelete) {
            SecondButton(L("Delete this check", "මේ පරීක්ෂාව මකන්න")) { askDelete = true }
        } else {
            Panel(accent = WarnRed) {
                Text(
                    L(
                        "Delete this check and its recording in the app? You cannot get it back. " +
                            "If it was one of the first ${Store.BASELINE_CHECKS} good checks, the hive's own normal sound is worked out again.",
                        "මේ පරීක්ෂාවත්, ඇප් එකේ තියෙන ඒකේ රෙකෝඩින් එකත් මකන්නද? මැකුවාම ආපහු ගන්න බැහැ. " +
                            "මේක මුල්ම හොඳ පරීක්ෂා ${Store.BASELINE_CHECKS}න් එකක් නම්, පෙට්ටියේ සුපුරුදු ශබ්දය ආයෙත් හදනවා."
                    ),
                    color = Ink
                )
                ButtonPair(
                    L("Yes, delete", "ඔව්, මකන්න"), L("No", "එපා"), leftFilled = true,
                    onLeft = {
                        store.deleteCheck(id)
                        deletedLater(store, cloud, r.hive, id)
                        go(Screen.HiveView(r.hive))
                    },
                    onRight = { askDelete = false }
                )
            }
        }
    }
}

// ---------- the five questions ----------

@Composable
fun QuestionsScreen(store: Store, cloud: Cloud, id: Long, go: (Screen) -> Unit) {
    val r = store.check(id)
    if (r == null) {
        Page(L("Questions", "ප්‍රශ්න"), "", onBack = { go(Screen.Home) }) {
            EmptyState(ART_BEE, L("This check was not found.", "මේ පරීක්ෂාව හොයාගන්න බැහැ."), "")
        }
        return
    }
    var answers by remember { mutableStateOf(r.answers) }

    Page(
        L("Questions", "ප්‍රශ්න"),
        L("Open the hive and look, then answer. ", "පෙට්ටිය ඇරලා බලලා පිළිතුරු දෙන්න. ") + r.hive,
        onBack = { go(Screen.ResultView(id)) },
        art = ART_FLOWER
    ) {
        // the beekeeper's own judgement: this is what the app's own strength model learns from
        Panel {
            Text(
                L("Your own judgement", "ඔබේම අදහස"),
                style = MaterialTheme.typography.labelMedium, color = Muted
            )
            Text(
                L("After looking inside, how strong is this colony?", "ඇතුළ බැලුවාට පස්සේ, මේ ජනපදය කොච්චර ශක්තිමත්ද?"),
                fontWeight = FontWeight.Bold, color = Ink
            )
            Choice(
                listOf(
                    "strong" to strengthName("strong"), "medium" to strengthName("medium"),
                    "weak" to strengthName("weak"), Advice.UNSURE to L("Not sure", "කියන්න බැහැ")
                ),
                answers[Learner.JUDGE] ?: ""
            ) { choice -> answers = answers + (Learner.JUDGE to choice) }
            Text(
                L(
                    "The app learns from this answer on this phone. Answer from what you see, not from the app's estimate.",
                    "ඇප් එක මේ ෆෝන් එකේම මේ පිළිතුරෙන් ඉගෙන ගන්නවා. ඇප් එක කිව්ව දේ නෙවෙයි, ඔබ ඇස් දෙකෙන් දැක්ක දේ කියන්න."
                ),
                style = MaterialTheme.typography.bodySmall, color = Muted
            )
        }
        for ((index, q) in Advice.QUESTIONS.withIndex()) {
            val number = index + 1
            Panel {
                Text(
                    L("Question $number of ${Advice.QUESTIONS.size}", "ප්‍රශ්න ${Advice.QUESTIONS.size}න් $number වැනි එක"),
                    style = MaterialTheme.typography.labelMedium, color = Muted
                )
                Text(q.text(uiSinhala), fontWeight = FontWeight.Bold, color = Ink)
                Choice(
                    listOf(Advice.YES to answerName(Advice.YES), Advice.NO to answerName(Advice.NO), Advice.UNSURE to answerName(Advice.UNSURE)),
                    answers[q.id] ?: ""
                ) { choice -> answers = answers + (q.id to choice) }
            }
        }
        BigButton(L("Save answers", "පිළිතුරු සේව් කරන්න"), enabled = answers.isNotEmpty()) {
            store.saveCheck(r.withAnswers(answers, Advice.suggestions(answers, false)))
            uploadLater(store, cloud, id)
            // the first time answers are saved, the app keeps its own guesses for this check (from earlier answers only)
            Thread {
                try {
                    Learner.freezeGuess(store, id)
                } catch (_: Exception) {
                }
            }.start()
            go(Screen.ResultView(id))
        }
    }
}

// ---------- the online assistant ----------

class ChatLine(val kind: String, val text: String)

/** The round black button that sends a question. */
@Composable
fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(ButtonHeight)
            .clip(CircleShape)
            .background(if (enabled) Ink else Faint)
            .clickable(enabled = enabled, onClickLabel = L("Send", "යවන්න"), role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.12f
            drawLine(Color.White, Offset(w * 0.5f, h * 0.88f), Offset(w * 0.5f, h * 0.14f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(w * 0.5f, h * 0.14f), Offset(w * 0.18f, h * 0.46f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(w * 0.5f, h * 0.14f), Offset(w * 0.82f, h * 0.46f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun ChatScreen(store: Store, agent: Agent, hive: String?, go: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var lines by remember { mutableStateOf(listOf<ChatLine>()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val pad = screenPad()

    // every visit to this screen starts a new conversation
    remember {
        agent.reset()
        0
    }
    LaunchedEffect(lines.size, busy, scroll.maxValue) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    fun send(question: String) {
        val text = question.trim()
        if (text.isEmpty() || busy) return
        lines = lines + ChatLine("me", text)
        input = ""
        busy = true
        scope.launch {
            val steps = withContext(Dispatchers.IO) { agent.ask(text, hive) }
            lines = lines + steps.map { ChatLine(it.kind, it.text) }
            busy = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Paper)) {
        Header(
            L("Assistant", "AI සහායකයා"),
            if (hive != null) L("About hive $hive", "$hive පෙට්ටිය ගැන") else L("About all your hives", "ඔබේ හැම මී පෙට්ටියක් ගැනම"),
            onBack = { go(if (hive != null) Screen.HiveView(hive) else Screen.Home) }
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(start = pad, end = pad, top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!isOnline) {
                    Panel(accent = Amber) {
                        Text(
                            L(
                                "No internet. The assistant needs internet. Hive checks still work.",
                                "ඉන්ටර්නෙට් නැහැ. AI සහායකයාට ඉන්ටර්නෙට් ඕනෑ. මී පෙට්ටි පරීක්ෂාව ඉන්ටර්නෙට් නැතුවත් කරන්න පුළුවන්."
                            ),
                            color = Ink
                        )
                    }
                }
                if (!store.aiReady()) {
                    Panel(accent = Amber) {
                        Text(
                            L(
                                "The assistant is not set up in this copy of the app. Hive checks and the hive device still work.",
                                "මේ ඇප් එකේ AI සහායකයා සකස් කරලා නැහැ. මී පෙට්ටි පරීක්ෂාව සහ පෙට්ටියේ උපකරණය ඒ විදිහටම වැඩ කරනවා."
                            ),
                            color = Ink
                        )
                    }
                }
                if (lines.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
                        Doodle(ART_BEE, 64.dp)
                    }
                    Text(
                        L("Ask about your hives. Try one of these:", "ඔබේ මී පෙට්ටි ගැන අහන්න. මේවායින් එකක් අහලා බලන්න:"),
                        fontWeight = FontWeight.SemiBold, color = Muted, modifier = Modifier.padding(start = 4.dp)
                    )
                    val starters = if (hive != null) {
                        listOf(
                            L("Explain the latest result of this hive", "මේ පෙට්ටියේ අන්තිම ප්‍රතිඵලය පැහැදිලි කරන්න"),
                            L("What should I do next for this hive?", "මේ පෙට්ටියට මම ඊළඟට මොනවාද කරන්න ඕනෑ?"),
                            L("What is the hive device saying now?", "පෙට්ටියේ උපකරණය දැන් කියන්නේ මොකක්ද?"),
                            L("Has this hive changed over time?", "කාලයත් එක්ක මේ පෙට්ටිය වෙනස් වෙලාද?")
                        )
                    } else {
                        listOf(
                            L("Which hive should I inspect first, and why?", "මම මුලින්ම බලන්න ඕනෑ මොන පෙට්ටියද? ඇයි?"),
                            L("Are there any new alerts? Explain them.", "අලුත් අනතුරු ඇඟවීම් තියෙනවාද? ඒවා පැහැදිලි කරන්න."),
                            L("Is today's weather good for opening hives?", "අද කාලගුණය පෙට්ටි අරින්න හොඳද?"),
                            L("How accurate is the strength estimate?", "ජනපදයේ ශක්තිය ගැන කියන එක කොච්චර හරිද?")
                        )
                    }
                    for (s in starters) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(ControlRadius))
                                .background(Color.White)
                                .border(1.dp, Line, RoundedCornerShape(ControlRadius))
                                .clickable(role = Role.Button) { send(s) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(s, color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Chevron()
                        }
                    }
                }
                for (line in lines) {
                    when (line.kind) {
                        "me" -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 480.dp)
                                    .fillMaxWidth(0.86f)
                                    .background(Ink, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp))
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(line.text, color = Color.White)
                            }
                        }
                        AgentStep.TOOL -> Row(
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(start = 6.dp)
                        ) {
                            Box(modifier = Modifier.size(6.dp).background(Faint, CircleShape))
                            Text(line.text, style = MaterialTheme.typography.labelMedium, color = Muted)
                        }
                        AgentStep.ERROR -> Panel(accent = Amber) { Text(line.text, color = Ink) }
                        else -> Box(
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .background(Color.White, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp))
                                .border(1.dp, Line, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(line.text, color = Ink)
                        }
                    }
                }
                if (busy) BusyLine(L("Thinking...", "හිතනවා..."))
            }

            Row(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(start = pad, end = pad, top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Field(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(L("Ask a question", "ප්‍රශ්නයක් අහන්න")) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                )
                SendButton(enabled = !busy && input.trim().isNotEmpty()) { send(input) }
            }
        }
    }
}
