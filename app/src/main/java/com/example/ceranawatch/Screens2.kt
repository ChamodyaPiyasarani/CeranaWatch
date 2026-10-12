package com.example.ceranawatch

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Shown on the cloud screens when the Firebase address has not been typed in yet. */
@Composable
fun CloudNotSet(go: (Screen) -> Unit) {
    Panel(accent = Amber) {
        SectionTitle(L("The cloud is not set up yet", "Firebase ලිපිනය තවම දාලා නැහැ"))
        Text(
            L(
                "This copy of the app has no cloud address built in. Hive checks with the phone work without it.",
                "මේ ඇප් එකේ Firebase ලිපිනය සකස් කරලා නැහැ. ෆෝන් එකෙන් කරන මී පෙට්ටි පරීක්ෂාවට ඒක ඕනෑ නැහැ."
            ),
            color = Muted
        )
        SecondButton(L("Open Settings", "සැකසුම්වලට යන්න")) { go(Screen.Settings) }
    }
}

/** A line shown when the phone has no internet. */
@Composable
fun OfflineNote(text: String) {
    Panel(accent = Amber) { Text(text, color = Ink) }
}

/** A problem text in a card with a red bar. */
@Composable
fun ProblemNote(text: String) {
    Panel(accent = WarnRed) { Text(text, color = Ink) }
}

// ---------- live data from the hive device ----------

@Composable
fun LiveScreen(store: Store, cloud: Cloud, startHive: String?, go: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()
    val names = (liveNodes.keys.toList() + store.hives()).distinct()
    var selected by remember { mutableStateOf(startHive ?: liveNodes.keys.firstOrNull() ?: "") }
    var latest by remember { mutableStateOf<NodeReading?>(null) }
    var readings by remember { mutableStateOf<List<NodeReading>>(emptyList()) }
    var problem by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var resetStep by remember { mutableStateOf(0) }          // 0 = nothing, 1 = asked "are you sure", 2 = sent
    var teachSent by remember { mutableStateOf("") }         // the teaching command that was just sent

    // when the list of devices arrives after the screen opened, show the first one
    LaunchedEffect(liveNodes.size) {
        if (selected.isEmpty() && liveNodes.isNotEmpty()) selected = liveNodes.keys.first()
    }

    // read the device data now, and again every 15 seconds
    LaunchedEffect(selected) {
        latest = null
        readings = emptyList()
        problem = ""
        loaded = false
        resetStep = 0
        teachSent = ""
        while (true) {
            val hive = selected
            if (hive.isNotEmpty() && cloud.ready() && isOnline) {
                try {
                    val got = withContext(Dispatchers.IO) { Pair(cloud.latest(hive), cloud.readings(hive, 60)) }
                    latest = got.first
                    readings = got.second
                    problem = ""
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    problem = friendly(e)
                }
                loaded = true
            }
            delay(15000)
        }
    }

    Page(
        L("Live", "උපකරණය"),
        L("What the hive device hears now", "පෙට්ටියේ උපකරණයට දැන් ඇහෙන දේ"),
        onBack = if (startHive != null) ({ go(Screen.HiveView(startHive)) }) else null,
        art = ART_BEE
    ) {
        if (!cloud.ready()) {
            CloudNotSet(go)
        } else {
            if (!isOnline) OfflineNote(L("No internet. Live data needs internet.", "ඉන්ටර්නෙට් නැහැ. උපකරණයේ තොරතුරු බලන්න ඉන්ටර්නෙට් ඕනෑ."))
            if (names.isNotEmpty()) {
                // choose the hive: all pills have the same height
                ChipRow {
                    for (name in names) {
                        val on = name == selected
                        val node = liveNodes[name]
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50.dp))
                                .background(if (on) Ink else Color.White)
                                .border(1.dp, if (on) Ink else Line, RoundedCornerShape(50.dp))
                                .clickable(role = Role.Tab) { selected = name }
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Box(modifier = Modifier.size(8.dp).background(if (node != null) nodeColor(node) else Faint, CircleShape))
                            Text(name, color = if (on) Color.White else Ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
            if (problem.isNotEmpty()) ProblemNote(problem)

            val now = latest
            if (selected.isEmpty()) {
                EmptyState(
                    ART_HIVE,
                    L("No device yet", "තවම උපකරණයක් නැහැ"),
                    L(
                        "No hive device has sent data yet. Switch the device on and wait for its first reading.",
                        "තවම කිසිම උපකරණයකින් තොරතුරු ඇවිල්ලා නැහැ. උපකරණය ඔන් කරලා ඒකේ පළමු මිනුම එනකල් ඉන්න."
                    )
                )
            } else if (now == null && !loaded && !isOnline) {
                // nothing to show: the note above already says that there is no internet
            } else if (now == null) {
                if (!loaded) {
                    Panel { BusyLine(L("Reading the device data...", "උපකරණයේ තොරතුරු බලනවා...")) }
                } else {
                    EmptyState(
                        ART_HIVE,
                        L("No device data for $selected", "$selected පෙට්ටියට උපකරණ තොරතුරු නැහැ"),
                        L(
                            "Check that the device is on and that the hive name in the device is $selected.",
                            "උපකරණය ඔන් කරලාද, උපකරණයේ දාලා තියෙන පෙට්ටි නම $selected ද කියලා බලන්න."
                        )
                    )
                }
            } else {
                // the reading now: first what kind of sound it was (only bee sound counts)
                Panel(accent = nodeColor(now)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            selected, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        Chip(nodeWord(now), nodeColor(now))
                    }
                    Text(
                        soundWord(now), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold,
                        color = when {
                            now.isBee() -> GoodGreen
                            now.checksBees() && !now.isSilent() -> Amber
                            else -> Muted
                        }
                    )
                    if (now.checksBees() && now.beeProb >= 0.0 && !now.isSilent()) {
                        val pct = Math.round(now.beeProb * 100.0).toInt()
                        Text(
                            L("Bee detector on the device: $pct% sure it is bees (bees from 50%).", "උපකරණයේ මී මැසි හඳුනාගන්නා කොටස: මී මැස්සෝ කියලා $pct%ක් විශ්වාසයි (50%න් උඩ නම් මී මැස්සෝ)."),
                            style = MaterialTheme.typography.bodySmall, color = Muted
                        )
                    }
                    if (now.canTeach() && !now.isSilent()) {
                        Text(
                            L(
                                "This reading " + (if (now.beeNow == true) "passed" else "did not pass") + ". The device says \"bees\" only when 3 of the last 4 readings pass (now " + now.passes + " of 4).",
                                "මේ මිනුම " + (if (now.beeNow == true) "සමත් වුණා" else "සමත් වුණේ නැහැ") + ". අන්තිම මිනුම් 4න් 3ක් සමත් වුණොත් විතරයි උපකරණය \"මී මැස්සෝ\" කියන්නේ (දැන් 4න් " + now.passes + ")."
                            ),
                            style = MaterialTheme.typography.bodySmall, color = Muted
                        )
                    }
                    if (now.teaching()) {
                        Text(
                            L("Teaching now (" + now.teach + "). Keep the device where it is.", "දැන් උගන්වනවා (" + now.teach + "). උපකරණය තියෙන තැනින් ගන්න එපා."),
                            color = InfoBlue, fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (now.isBee()) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(oneDecimal(now.lowDb), fontSize = 46.sp, lineHeight = 50.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                            Text("dB", style = MaterialTheme.typography.titleMedium, color = Muted, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Text(L("Loudness of the bee sound (100 to 600 Hz)", "මී මැස්සන්ගේ ශබ්දයේ සැර (100–600 Hz)"), color = Muted, style = MaterialTheme.typography.bodySmall)
                    } else if (now.checksBees()) {
                        Text(
                            if (now.isSilent()) L(
                                "The device hears almost nothing. This reading is not used as bee sound.",
                                "උපකරණයට කිසිම දෙයක් වගේ ඇහෙන්නේ නැහැ. මේ මිනුම මී මැසි ශබ්දය විදිහට ගන්නේ නැහැ."
                            ) else if (now.isFar()) L(
                                "The sound is quieter than the listening range (" + Math.round(now.rmsDb) + " dB, range " + Math.round(now.nearDb ?: -75.0) + " dB), so it comes from further away, not from inside a hive. It is not used.",
                                "ශබ්දය ඇහුම් පරාසයට වඩා හෙමින් (" + Math.round(now.rmsDb) + " dB, පරාසය " + Math.round(now.nearDb ?: -75.0) + " dB). ඒ නිසා ඒක එන්නේ ඈතින්, පෙට්ටිය ඇතුළෙන් නොවේ. මේ මිනුම ගන්නේ නැහැ."
                            ) else L(
                                "The device hears a sound, but it is not bee sound (for example talking, a fan, traffic or rain). This reading is not used.",
                                "උපකරණයට ශබ්දයක් ඇහෙනවා, හැබැයි ඒක මී මැසි ශබ්දය නොවේ (උදාහරණ: කතා කිරීම, විදුලි පංකාව, වාහන, වැස්ස). මේ මිනුම ගන්නේ නැහැ."
                            ),
                            color = Ink
                        )
                        if (now.noBeeRun >= 2) {
                            Text(
                                L("No bee sound for " + now.noBeeRun + " readings in a row.", "මිනුම් " + now.noBeeRun + "ක් එක දිගට මී මැසි ශබ්දය නැහැ."),
                                color = Amber, fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(oneDecimal(now.lowDb), fontSize = 46.sp, lineHeight = 50.sp, fontWeight = FontWeight.ExtraBold, color = Muted)
                            Text("dB", style = MaterialTheme.typography.titleMedium, color = Muted, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Text(
                            L(
                                "Sound level from 100 to 600 Hz. This device program cannot tell bee sound from other sounds, so this may not be bees. Upload device program version 2.",
                                "100–600 Hz ශබ්ද මට්ටම. මේ උපකරණ ප්‍රෝග්‍රෑම් එකට මී මැසි ශබ්දය වෙනත් ශබ්ද වලින් වෙන් කරන්න බැහැ, ඒ නිසා මේක මී මැස්සෝ නොවෙන්න පුළුවන්. උපකරණයට 2 වැනි ප්‍රෝග්‍රෑම් එක දාන්න."
                            ),
                            color = Amber, style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        L("Last reading: ", "අන්තිම මිනුම: ") + agoText(now.ageSeconds()) + " (" + shownTime(now.t) + ")",
                        style = MaterialTheme.typography.bodySmall, color = Muted
                    )
                }

                // temperature and humidity inside the hive (DHT22)
                if (now.checksBees() || now.hasTemp()) {
                    Panel(accent = if (now.isHot()) WarnRed else null) {
                        SectionTitle(L("Inside the hive", "පෙට්ටිය ඇතුළේ"))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(L("Temperature", "උෂ්ණත්වය"), style = MaterialTheme.typography.labelMedium, color = Muted)
                                Text(tempText(now.tempC), fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, color = if (now.isHot()) WarnRed else Ink)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(L("Humidity", "තෙතමනය"), style = MaterialTheme.typography.labelMedium, color = Muted)
                                Text(humidityText(now.humidity), fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                            }
                        }
                        if (!now.hasTemp()) {
                            Text(
                                L(
                                    "The temperature sensor (DHT22) did not answer. Check its three wires: + to 3V3, OUT to D4, − to GND.",
                                    "උෂ්ණත්ව සංවේදකය (DHT22) උත්තර දුන්නේ නැහැ. වයර් තුන බලන්න: + 3V3 එකට, OUT D4 එකට, − GND එකට."
                                ),
                                color = Amber, style = MaterialTheme.typography.bodySmall
                            )
                        } else if (now.isHot()) {
                            Text(
                                L(
                                    "Very hot inside the hive (40 °C or more). Give the hive shade and check it.",
                                    "පෙට්ටිය ඇතුළ ගොඩක් රස්නෙයි (40 °C හෝ වැඩි). පෙට්ටියට සෙවණ දීලා බලන්න."
                                ),
                                color = WarnRed, fontWeight = FontWeight.SemiBold
                            )
                        }
                        val temps = readings.mapNotNull { it.tempC }
                        if (temps.size >= 2) {
                            Text(L("Temperature, last " + temps.size + " readings", "උෂ්ණත්වය, අන්තිම මිනුම් " + temps.size), style = MaterialTheme.typography.labelMedium, color = Muted)
                            LineChart(temps, null, null, limit = NodeReading.HOT_C, fromZero = false)
                            Text(
                                L("The red dashed line is 40 °C, where the device gives a \"hot\" alert.", "රතු ඉරි ඉර 40 °C. එතනදී උපකරණය \"රස්නෙයි\" කියලා අනතුරු අඟවනවා."),
                                style = MaterialTheme.typography.bodySmall, color = Muted
                            )
                        }
                    }
                }

                // listening range (device program 2.2): how loud a sound must be to come from inside the hive
                if (now.hasRange()) {
                    Panel(soft = true) {
                        SectionTitle(L("Listening range", "ඇහුම් පරාසය"))
                        Text(
                            L(
                                "A microphone hears everything around it. Bees inside a hive are very close to it, so they are loud; talking or a TV further away is quieter. The device ignores sound quieter than its range.",
                                "මයික් එකකට වටේ තියෙන හැම දෙයක්ම ඇහෙනවා. පෙට්ටිය ඇතුළේ මී මැස්සෝ ඒකට ගොඩක් ළඟ නිසා ඒ ශබ්දය සැරයි; ඈතින් එන කතා, TV ශබ්ද අඩුයි. උපකරණය පරාසයට වඩා හෙමින් ශබ්ද ගණන් ගන්නේ නැහැ."
                            ),
                            style = MaterialTheme.typography.bodySmall, color = Muted
                        )
                        Text(
                            L("Now: sounds quieter than " + Math.round(now.nearDb ?: -75.0) + " dB are ignored. This reading: " + Math.round(now.rmsDb) + " dB.",
                              "දැන්: " + Math.round(now.nearDb ?: -75.0) + " dB ට වඩා හෙමින් ශබ්ද ගණන් ගන්නේ නැහැ. මේ මිනුම: " + Math.round(now.rmsDb) + " dB."),
                            color = Ink, style = MaterialTheme.typography.bodySmall
                        )
                        Choice(
                            listOf("auto" to L("Auto", "ඉබේම"), "near" to L("Hive only", "පෙට්ටිය විතරයි"), "all" to L("Everything", "හැම ශබ්දයම")),
                            now.range
                        ) { choice ->
                            val hive = selected
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { cloud.sendCommand(hive, "range_" + choice) }
                                    teachSent = "range"
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    problem = friendly(e)
                                }
                            }
                        }
                        Text(
                            L(
                                "Auto: set by teaching (\"No bees here\" and \"Bees here\"). Hive only: only very close, loud sound (−50 dB). Everything: all sound, like before.",
                                "ඉබේම: ඉගැන්වීමෙන් සකස් වෙනවා (\"මෙතන මී මැස්සෝ නැහැ\" සහ \"මෙතන මී මැස්සෝ ඉන්නවා\"). පෙට්ටිය විතරයි: ගොඩක් ළඟ, සැර ශබ්ද විතරයි (−50 dB). හැම ශබ්දයම: කලින් වගේ හැම ශබ්දයක්ම."
                            ),
                            style = MaterialTheme.typography.labelSmall, color = Muted
                        )
                        if (teachSent == "range") {
                            Text(
                                L("Sent. The device uses it from its next reading.", "යැව්වා. උපකරණය ඊළඟ මිනුමේ ඉඳන් ඒක පාවිච්චි කරනවා."),
                                color = GoodGreen, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // teach the device its own place (device program 2.1)
                if (now.checksBees()) {
                    Panel(soft = true) {
                        SectionTitle(L("Teach the device this place", "උපකරණයට මේ තැන උගන්වන්න"))
                        if (!now.canTeach()) {
                            Text(
                                L("Upload device program 2.1 to use this.", "මේක පාවිච්චි කරන්න උපකරණයට 2.1 ප්‍රෝග්‍රෑම් එක දාන්න."),
                                style = MaterialTheme.typography.bodySmall, color = Muted
                            )
                        } else {
                            Text(
                                L(
                                    "If the device says \"bees\" where there are no bees, put it there and press \"No bees here\". " +
                                        "It listens 10 times (about 3 minutes) and learns how its microphone hears that place. " +
                                        "At a hive, \"Bees here\" teaches it your colony's sound.",
                                    "මී මැස්සෝ නැති තැනක උපකරණය \"මී මැස්සෝ\" කියනවා නම්, උපකරණය එතන තියලා \"මෙතන මී මැස්සෝ නැහැ\" ඔබන්න. " +
                                        "ඒක 10 පාරක් (විනාඩි 3ක් විතර) අහලා, ඒ තැන ඒකේ මයික් එකට ඇහෙන හැටි ඉගෙන ගන්නවා. " +
                                        "පෙට්ටියක් ළඟදී \"මෙතන මී මැස්සෝ ඉන්නවා\" ඔබලා ඔබේ ජනපදයේ ශබ්දය උගන්වන්න පුළුවන්."
                                ),
                                style = MaterialTheme.typography.bodySmall, color = Muted
                            )
                            Text(
                                L(
                                    "Taught so far: " + now.taughtRoom + " parts \"no bees\", " + now.taughtHive + " parts \"bees\".",
                                    "මෙතෙක් ඉගැන්නුවා: \"මී මැස්සෝ නැහැ\" කොටස් " + now.taughtRoom + ", \"මී මැස්සෝ ඉන්නවා\" කොටස් " + now.taughtHive + "."
                                ),
                                color = Ink, style = MaterialTheme.typography.bodySmall
                            )
                            fun teach(command: String) {
                                val hive = selected
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { cloud.sendCommand(hive, command) }
                                        teachSent = command
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        problem = friendly(e)
                                    }
                                }
                            }
                            ButtonPair(
                                L("No bees here", "මෙතන මී මැස්සෝ නැහැ"), L("Bees here", "මෙතන මී මැස්සෝ ඉන්නවා"),
                                enabled = isOnline && !now.teaching(),
                                onLeft = { teach("teach_room") }, onRight = { teach("teach_hive") }
                            )
                            if (now.taughtRoom + now.taughtHive > 0) {
                                SmallAction(L("Forget what was taught", "ඉගැන්නුව දේ අමතක කරන්න"), Muted) { teach("teach_forget") }
                            }
                            if (teachSent.isNotEmpty() && teachSent != "range") {
                                Text(
                                    when (teachSent) {
                                        "teach_forget" -> L("Sent. The device forgets the teaching at its next reading.", "යැව්වා. ඊළඟ මිනුමේදී උපකරණය ඉගැන්නුව දේ අමතක කරනවා.")
                                        else -> L("Sent. Teaching starts at the device's next reading. Do not move the device until it beeps twice.", "යැව්වා. උපකරණයේ ඊළඟ මිනුමේදී ඉගැන්වීම පටන් ගන්නවා. දෙපාරක් බීප් කරනකල් උපකරණය ගන්න එපා.")
                                    },
                                    color = GoodGreen, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                // against the hive's own normal (learned only from bee sound)
                Panel {
                    SectionTitle(L("Compared with this hive's normal", "මේ පෙට්ටියේ සුපුරුදු මට්ටම එක්ක ගළපලා බැලීම"))
                    if (!now.ready) {
                        Text(
                            if (now.checksBees()) L(
                                "The device is still learning what is normal for this hive, only from bee sound: " + now.normalCount + " of " + now.normalNeeded + " bee readings. Readings without bees are not used. It gives no sound alerts until it has finished.",
                                "උපකරණය මේ පෙට්ටියේ සුපුරුදු මට්ටම තවම ඉගෙන ගන්නවා, මී මැසි ශබ්දයෙන් විතරයි: මී මැසි මිනුම් " + now.normalNeeded + "න් " + now.normalCount + "ක් ඉවරයි. මී මැස්සෝ නැති මිනුම් ගන්නේ නැහැ. ඒක ඉවර වෙනකල් ශබ්ද ගැන අනතුරු ඇඟවීම් එවන්නේ නැහැ."
                            ) else L(
                                "The device is still learning what is normal for this hive: " + now.normalCount + " of " + now.normalNeeded + " readings. It gives no alerts until it has finished.",
                                "උපකරණය මේ පෙට්ටියේ සුපුරුදු මට්ටම තවම ඉගෙන ගන්නවා: මිනුම් " + now.normalNeeded + "න් " + now.normalCount + "ක් ඉවරයි. ඒක ඉවර වෙනකල් අනතුරු ඇඟවීම් එවන්නේ නැහැ."
                            ),
                            color = Muted
                        )
                        FillBar(if (now.normalNeeded > 0) now.normalCount.toFloat() / now.normalNeeded else 0f)
                    } else if (now.checksBees() && !now.isBee()) {
                        Text(
                            L(
                                "Normal bee sound for this hive: " + oneDecimal(now.normalDb) + " dB. This reading is not compared, because the device did not hear bees.",
                                "මේ පෙට්ටියේ සුපුරුදු මී මැසි ශබ්දය: " + oneDecimal(now.normalDb) + " dB. උපකරණයට මී මැස්සෝ ඇහුණේ නැති නිසා මේ මිනුම ගළපලා බලන්නේ නැහැ."
                            ),
                            color = Muted
                        )
                    } else {
                        Text(
                            L(
                                "Normal for this hive: " + oneDecimal(now.normalDb) + " dB (usual spread " + oneDecimal(now.normalSd) + " dB).",
                                "මේ පෙට්ටියේ සුපුරුදු මට්ටම: " + oneDecimal(now.normalDb) + " dB (සාමාන්‍යයෙන් වෙනස් වෙන්නේ " + oneDecimal(now.normalSd) + " dB විතර)."
                            ),
                            color = Muted
                        )
                        ZBar(now.z)
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(L("quieter", "අඩුයි"), style = MaterialTheme.typography.labelSmall, color = Muted)
                            Text(L("normal zone", "සුපුරුදු මට්ටම"), style = MaterialTheme.typography.labelSmall, color = Ink, fontWeight = FontWeight.SemiBold)
                            Text(L("louder", "වැඩියි"), style = MaterialTheme.typography.labelSmall, color = Muted)
                        }
                        Text(
                            L(
                                "Now it is " + oneDecimal(Math.abs(now.z)) + " steps " + (if (now.z < 0) "below" else "above") + " normal. More than 3 steps counts as unusual.",
                                "දැන් ඒක සුපුරුදු මට්ටමට වඩා පියවර " + oneDecimal(Math.abs(now.z)) + "ක් " + (if (now.z < 0) "අඩුයි" else "වැඩියි") + ". පියවර 3කට වඩා වෙනස් නම් අමුතුයි කියලා ගන්නවා."
                            ),
                            style = MaterialTheme.typography.bodySmall, color = Muted
                        )
                    }
                }

                // the last readings: what was heard, then the bee sound level of the bee readings only
                if (readings.isNotEmpty()) {
                    val checked = readings.any { it.checksBees() }
                    val beeCount = readings.count { it.isBee() }
                    Panel {
                        SectionTitle(L("Last " + readings.size + " readings", "අන්තිම මිනුම් " + readings.size))
                        if (checked) {
                            Text(
                                L("Bee sound in $beeCount of the last ${readings.size} readings.", "අන්තිම මිනුම් ${readings.size}න් $beeCount ක මී මැසි ශබ්දය තිබුණා."),
                                color = Ink, fontWeight = FontWeight.SemiBold
                            )
                            BeeStrip(readings)
                        }
                        val levels = if (checked) readings.filter { it.isBee() } else readings
                        if (levels.isNotEmpty()) {
                            Text(
                                if (checked) L("Bee sound level (only the readings with bee sound)", "මී මැසි ශබ්ද මට්ටම (මී මැසි ශබ්දය තිබුණු මිනුම් විතරයි)")
                                else L("Sound level 100–600 Hz (not checked for bees)", "100–600 Hz ශබ්ද මට්ටම (මී මැස්සෝද කියලා බැලුවේ නැහැ)"),
                                style = MaterialTheme.typography.labelMedium, color = Muted
                            )
                            val band = now.ready && now.normalSd > 0.0
                            LineChart(
                                levels.map { it.lowDb },
                                if (band) now.normalDb - NodeReading.Z_LIMIT * now.normalSd else null,
                                if (band) now.normalDb + NodeReading.Z_LIMIT * now.normalSd else null
                            )
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(shownTime(levels.first().t), style = MaterialTheme.typography.labelSmall, color = Muted)
                                Text(shownTime(levels.last().t), style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                            Text(
                                L(
                                    "The grey band is this hive's normal zone. Red points are outside it.",
                                    "අළු පාට තීරුව මේ පෙට්ටියේ සුපුරුදු මට්ටම. රතු තිත් ඒකෙන් පිට තියෙන ඒවා."
                                ),
                                style = MaterialTheme.typography.bodySmall, color = Muted
                            )
                        } else {
                            Text(
                                L("No bee sound in these readings, so there is no bee sound level to draw.", "මේ මිනුම්වල මී මැසි ශබ්දය නැති නිසා අඳින්න මී මැසි ශබ්ද මට්ටමක් නැහැ."),
                                color = Muted
                            )
                        }
                    }
                }

                SecondButton(L("Ask the assistant about this device", "මේ උපකරණය ගැන සහායකයාගෙන් අහන්න")) { go(Screen.ChatView(selected)) }

                Panel(soft = true) {
                    SectionTitle(L("Learn the normal again", "සුපුරුදු මට්ටම ආයෙත් ඉගෙන ගන්න"))
                    Text(
                        L(
                            "Use this after you moved the device or the hive. The device forgets its old normal and learns a new one from bee sound.",
                            "උපකරණය හරි පෙට්ටිය හරි වෙන තැනකට ගෙනිච්චාම මේක පාවිච්චි කරන්න. උපකරණය පරණ සුපුරුදු මට්ටම අමතක කරලා මී මැසි ශබ්දයෙන් අලුතින් ඉගෙන ගන්නවා."
                        ),
                        style = MaterialTheme.typography.bodySmall, color = Muted
                    )
                    when (resetStep) {
                        0 -> SecondButton(L("Learn again", "ආයෙත් ඉගෙන ගන්න"), enabled = isOnline) { resetStep = 1 }
                        1 -> {
                            Text(L("Are you sure? The old normal will be forgotten.", "විශ්වාසද? පරණ සුපුරුදු මට්ටම අමතක වෙනවා."), color = Ink, fontWeight = FontWeight.SemiBold)
                            ButtonPair(
                                L("Yes, learn again", "ඔව්, ආයෙත් ඉගෙන ගන්න"), L("No", "එපා"), leftFilled = true,
                                onLeft = {
                                    val hive = selected
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { cloud.resetNormal(hive) }
                                            resetStep = 2
                                        } catch (e: Exception) {
                                            if (e is CancellationException) throw e
                                            problem = friendly(e)
                                            resetStep = 0
                                        }
                                    }
                                },
                                onRight = { resetStep = 0 }
                            )
                        }
                        else -> Text(
                            L("Sent. The device starts again at its next reading.", "යැව්වා. උපකරණය ඊළඟ මිනුමේදී අලුතින් පටන් ගන්නවා."),
                            color = GoodGreen, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

// ---------- alerts ----------

fun reviewWord(label: String): String = when (label) {
    "likely_real" -> L("Assistant: likely a real change", "සහායකයා: ඇත්ත වෙනසක් වෙන්න පුළුවන්")
    "likely_false" -> L("Assistant: likely a false alarm", "සහායකයා: බොරු ඇඟවීමක් වෙන්න පුළුවන්")
    else -> L("Assistant: not sure", "සහායකයා: හරියටම කියන්න බැහැ")
}

fun alertText(a: AlertItem): String {
    val now = oneDecimal(a.lowDb)
    val normal = oneDecimal(a.normalDb)
    val temp = tempText(a.tempC)
    return when (a.type) {
        AlertItem.QUIET -> L(
            "The bee sound was much lower than this hive's normal, two readings in a row: $now dB against a normal of $normal dB.",
            "මී මැස්සන්ගේ ශබ්දය මේ පෙට්ටියේ සුපුරුදු මට්ටමට වඩා ගොඩක් අඩු වුණා, මිනුම් දෙකක් එක දිගට: එවෙලේ $now dB, සුපුරුදු මට්ටම $normal dB."
        )
        AlertItem.NO_BEES -> L(
            "The device heard no bee sound for several readings in a row, although it heard bees at this hive before. Look at the hive: the colony may have left, or the device may have moved or be covered.",
            "මේ පෙට්ටියෙන් කලින් මී මැසි ශබ්දය ඇහුණත්, මිනුම් කිහිපයක් එක දිගට උපකරණයට මී මැසි ශබ්දය ඇහුණේ නැහැ. පෙට්ටිය බලන්න: ජනපදය පැනලා යන්න පුළුවන්, නැත්නම් උපකරණය තැන වෙනස් වෙලා හරි වැහිලා හරි තියෙන්න පුළුවන්."
        )
        AlertItem.HOT -> L(
            "It was very hot inside the hive, two readings in a row: $temp. Give the hive shade and check it.",
            "පෙට්ටිය ඇතුළ ගොඩක් රස්නෙයි, මිනුම් දෙකක් එක දිගට: $temp. පෙට්ටියට සෙවණ දීලා බලන්න."
        )
        else -> L(
            "The bee sound was much higher than this hive's normal: $now dB against a normal of $normal dB.",
            "මී මැස්සන්ගේ ශබ්දය මේ පෙට්ටියේ සුපුරුදු මට්ටමට වඩා ගොඩක් වැඩි වුණා: එවෙලේ $now dB, සුපුරුදු මට්ටම $normal dB."
        )
    }
}

@Composable
fun AlertsScreen(store: Store, cloud: Cloud, agent: Agent, go: (Screen) -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var alerts by remember { mutableStateOf(AlertItem.listFromSaved(store.savedText("alerts"))) }
    var loadProblem by remember { mutableStateOf("") }       // the list could not be read
    var actionProblem by remember { mutableStateOf("") }     // a button did not work
    var working by remember { mutableStateOf(false) }        // a change is on its way to the cloud
    var busyId by remember { mutableStateOf("") }            // the alert the assistant is looking at now
    var steps by remember { mutableStateOf(listOf<String>()) }
    var showSeen by remember { mutableStateOf(false) }       // also show the alerts that were already seen
    var relabelId by remember { mutableStateOf("") }         // the alert whose "real / false alarm" answer is being changed
    var checker by remember { mutableStateOf<Learner.Model?>(null) }   // the alert checker trained on this phone

    // train the alert checker again whenever the list changes (it is small and quick)
    LaunchedEffect(alerts) {
        checker = withContext(Dispatchers.Default) { Learner.alertModel(store) }
    }

    fun refresh() {
        if (!cloud.ready() || !isOnline) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { AlertChecker.check(app, store) }
            alerts = result.alerts
            openAlerts = result.open
            loadProblem = result.error
        }
    }

    /** Does one small change in the cloud, then reads the alerts again. */
    fun change(work: () -> Unit) {
        if (working) return
        working = true
        actionProblem = ""
        scope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                actionProblem = friendly(e)
            }
            working = false
            refresh()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(20000)
        }
    }

    // one alert as a card; all its buttons have the same size
    val card: @Composable (AlertItem) -> Unit = { a ->
        val serious = a.type != AlertItem.LOUD
        Panel(accent = if (a.ack) Faint else (if (serious) WarnRed else Amber)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconSpot(44.dp) { TabIcon(TAB_ALERTS, Ink, 22.dp) }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(AlertChecker.title(a, uiSinhala), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                    Text(
                        shownTime(a.t) + (if (a.hasClock()) "  ·  " + agoText(a.ageMinutes() * 60L) else ""),
                        style = MaterialTheme.typography.bodySmall, color = Muted
                    )
                }
            }
            ChipRow {
                if (a.ack) Chip(L("Seen", "දැක්කා"), GoodGreen) else Chip(L("New", "අලුත්"), WarnRed)
                if (a.userLabel == "real") Chip(L("You: real", "ඔබ: ඇත්ත ප්‍රශ්නයක්"))
                if (a.userLabel == "false") Chip(L("You: false alarm", "ඔබ: බොරු ඇඟවීමක්"))
                if (a.escalated) Chip(L("Second contact told", "දෙවැනි කෙනාට දැනුම් දුන්නා"), InfoBlue)
            }
            Text(alertText(a), color = Ink)

            // what the alert checker trained on this phone thinks, for alerts not yet answered
            val guess = if (a.userLabel.isEmpty()) Learner.ownAlert(checker, a) else null
            if (guess != null) {
                val percent = Math.round(guess.chance * 100.0)
                val usual = Math.round(guess.usual * 100.0)
                Text(
                    L(
                        "App's own checker: about $percent% chance this is a real problem. Usually $usual% of the alerts you marked were real. (Learned from ${guess.examples} of your answers.)",
                        "ඇප් එකේම මොඩලය කියන්නේ: මේක ඇත්ත ප්‍රශ්නයක් වෙන්න සියයට $percent ක ඉඩක් තියෙනවා. ඔබ බලපු ඇඟවීම්වලින් සාමාන්‍යයෙන් ඇත්ත වුණේ සියයට $usual යි. (ඔබේ පිළිතුරු ${guess.examples}කින් ඉගෙන ගත්තා.)"
                    ),
                    style = MaterialTheme.typography.bodySmall, color = Muted
                )
            }

            // the assistant's explanation and its view on whether the alert is real
            if (a.reviewText.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Soft, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TabIcon(TAB_CHAT, Ink, 18.dp)
                        Text(reviewWord(a.reviewLabel), color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    }
                    Text(a.reviewText, color = Ink)
                    Text(
                        L("This is the assistant's reading of the data, not a diagnosis.", "මේක තොරතුරු ගැන සහායකයාගේ අදහස විතරයි, ලෙඩක් හඳුනාගැනීමක් නෙවෙයි."),
                        style = MaterialTheme.typography.labelSmall, color = Muted
                    )
                }
            }
            if (busyId == a.id) {
                BusyLine(L("The assistant is checking this alert...", "සහායකයා මේ අනතුරු ඇඟවීම බලනවා..."))
                for (s in steps) Text("• $s", style = MaterialTheme.typography.labelSmall, color = Muted)
            }

            if (!a.ack) {
                BigButton(L("I have seen it", "මම දැක්කා"), enabled = isOnline && !working) { change { cloud.acknowledge(a.id) } }
            }
            if (busyId != a.id) {
                SecondButton(
                    if (a.reviewText.isEmpty()) L("Ask the assistant to explain and check", "මේක බලලා පැහැදිලි කරන්න කියලා සහායකයාට කියන්න")
                    else L("Ask the assistant again", "සහායකයාගෙන් ආයෙත් අහන්න"),
                    enabled = isOnline && busyId.isEmpty()
                ) {
                    busyId = a.id
                    steps = emptyList()
                    actionProblem = ""
                    scope.launch {
                        val done = withContext(Dispatchers.IO) {
                            agent.runTask(
                                Agent.REVIEW,
                                "Review the alert with alert_id " + a.id + " of hive " + a.hive + ". " +
                                    "Read get_alerts, get_node_history and get_node_latest for this hive, get_weather, get_latest_check and get_hive_details. " +
                                    "Decide if the alert is likely_real, likely_false or unsure, using only what the data shows. " +
                                    "Rain or strong wind at that time, one single unusual reading that went back to normal, a device that is still learning, " +
                                    "or a hive that was opened a short time ago point to a false alarm. Several unusual readings in a row, or a phone check " +
                                    "that changed from the hive's own normal, point to a real change. " +
                                    "Then call save_alert_review with the label and an explanation of two or three short sentences: what happened, " +
                                    "the likely reason, and what to do next. After that, reply with the same explanation.",
                                a.hive
                            )
                        }
                        steps = done.filter { it.kind == AgentStep.TOOL }.map { it.text }
                        val failed = agent.errorOf(done)
                        if (failed != null) actionProblem = failed
                        busyId = ""
                        refresh()
                    }
                }
            }

            // what the beekeeper found at the hive: asked once, and can be changed later
            if (a.userLabel.isEmpty() || relabelId == a.id) {
                Text(L("After you looked at the hive, what was it?", "පෙට්ටිය බැලුවාට පස්සේ කියන්න: මේ ඇඟවීම ඇත්තද, බොරුද?"), style = MaterialTheme.typography.bodySmall, color = Muted)
                ButtonPair(
                    L("A real problem", "ඇත්ත ප්‍රශ්නයක්"), L("False alarm", "බොරු ඇඟවීමක්"), enabled = isOnline && !working,
                    onLeft = {
                        relabelId = ""
                        change {
                            cloud.setUserLabel(a.id, "real")
                            store.rememberAlertLabel(a, "real")
                            if (!a.ack) cloud.acknowledge(a.id)
                        }
                    },
                    onRight = {
                        relabelId = ""
                        change {
                            cloud.setUserLabel(a.id, "false")
                            store.rememberAlertLabel(a, "false")
                            if (!a.ack) cloud.acknowledge(a.id)
                        }
                    }
                )
                if (relabelId == a.id) SmallAction(L("Cancel", "එපා"), Muted) { relabelId = "" }
            } else {
                SmallAction(L("Change my answer", "මගේ පිළිතුර වෙනස් කරන්න"), Muted) { relabelId = a.id }
            }
            if (!a.ack && !a.escalated && !store.secondContact()) {
                SecondButton(L("Tell the second contact now", "දෙවැනි කෙනාට දැන්ම දැනුම් දෙන්න"), enabled = isOnline && !working) {
                    change { cloud.escalate(a.id, "Sent on by the owner") }
                }
            }
        }
    }

    val fresh = alerts.filter { !it.ack }
    val seen = alerts.filter { it.ack }

    Page(L("Alerts", "අනතුරු ඇඟවීම්"), L("From the hive devices", "පෙට්ටිවල උපකරණවලින්"), art = ART_BLOOM) {
        if (!cloud.ready()) {
            CloudNotSet(go)
        } else {
            Panel(soft = true) {
                Text(
                    if (store.secondContact()) L(
                        "This phone is the second contact. It is told about alerts that the owner sent on, or did not answer within " + store.escalateMinutes() + " minutes.",
                        "මේ ෆෝන් එක දෙවැනි කෙනාගේ. අයිතිකාරයා එවපු, නැත්නම් විනාඩි " + store.escalateMinutes() + "ක් ඇතුළත බලලා නැති අනතුරු ඇඟවීම් මේකට එනවා."
                    ) else L(
                        "When you have looked at an alert, press \"I have seen it\". Alerts that stay unanswered are passed to the second contact's phone.",
                        "අනතුරු ඇඟවීමක් බැලුවාම \"මම දැක්කා\" ඔබන්න. බලලා නැති අනතුරු ඇඟවීම් දෙවැනි කෙනාගේ ෆෝන් එකට යනවා."
                    ),
                    color = Muted, style = MaterialTheme.typography.bodyMedium
                )
            }
            if (!isOnline) OfflineNote(L("No internet. This list was saved earlier.", "ඉන්ටර්නෙට් නැහැ. මේ ලැයිස්තුව කලින් සේව් වුණ එකක්."))
            if (loadProblem.isNotEmpty()) ProblemNote(loadProblem)
            if (actionProblem.isNotEmpty()) ProblemNote(actionProblem)
            if (alerts.isEmpty()) {
                EmptyState(
                    ART_FLOWER,
                    L("No alerts", "අනතුරු ඇඟවීම් නැහැ"),
                    L(
                        "All calm. The hive device sends an alert when the bee sound is far from that hive's normal.",
                        "ඔක්කොම හොඳින්. මී මැස්සන්ගේ ශබ්දය පෙට්ටියේ සුපුරුදු මට්ටමෙන් ගොඩක් වෙනස් වුණාම උපකරණය අනතුරු ඇඟවීමක් එවනවා."
                    )
                )
            } else {
                // new alerts first; the ones already seen are folded away so the list stays short
                ListLabel(L("New (" + fresh.size + ")", "අලුත් ඒවා (" + fresh.size + ")"))
                if (fresh.isEmpty()) {
                    Panel(soft = true) { Text(L("No new alerts. All calm.", "අලුත් අනතුරු ඇඟවීම් නැහැ. ඔක්කොම හොඳින්."), color = Muted) }
                }
                if (fresh.size >= 2) {
                    SecondButton(
                        L("I have seen all " + fresh.size, "මේ " + fresh.size + "ම මම දැක්කා"),
                        enabled = isOnline && !working
                    ) {
                        val ids = fresh.map { it.id }
                        change { cloud.acknowledgeAll(ids) }
                    }
                }
                for (a in fresh) card(a)
                if (seen.isNotEmpty()) {
                    val unanswered = seen.count { it.userLabel.isEmpty() }
                    SecondButton(
                        if (showSeen) L("Hide the alerts I have seen (" + seen.size + ")", "මම දැක්ක ඒවා හංගන්න (" + seen.size + ")")
                        else L("Show the alerts I have seen (" + seen.size + ")", "මම දැක්ක ඒවා පෙන්වන්න (" + seen.size + ")")
                    ) { showSeen = !showSeen }
                    if (unanswered > 0 && !showSeen) {
                        Text(
                            L(
                                "For $unanswered of them you have not yet said if it was a real problem or a false alarm.",
                                "ඒවායින් ${unanswered}ක් ඇත්තද බොරුද කියලා ඔබ තවම කියලා නැහැ."
                            ),
                            style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    if (showSeen) for (a in seen) card(a)
                }
            }
        }
    }
}

// ---------- inspection plan ----------

@Composable
fun PlanScreen(store: Store, agent: Agent, go: (Screen) -> Unit) {
    Page(
        L("Inspection plan", "අද බලන්න ඕනෑ පෙට්ටි"), L("Which hive to open first", "මුලින්ම අරින්න ඕනෑ පෙට්ටිය"),
        onBack = { go(Screen.More) }, art = ART_COMB
    ) {
        AssistantCard(
            store, agent, "plan", Agent.PLAN, null,
            L("Today's plan", "අද සැලැස්ම"),
            L(
                "The assistant reads your checks, the device data, the alerts and the weather, and puts your hives in order.",
                "සහායකයා ඔබේ පරීක්ෂා, උපකරණ තොරතුරු, අනතුරු ඇඟවීම් සහ කාලගුණය බලලා ඔබේ පෙට්ටි පිළිවෙළකට දානවා."
            ),
            L("Make today's plan", "අද සැලැස්ම හදන්න"),
            "Make an inspection plan for today. Read list_hives, get_alerts with only_open true and get_weather. For the hives that look " +
                "important, also read get_latest_check, get_hive_details and get_node_latest. Put the hives in order from most to least urgent. " +
                "For each hive give the reason from the data and what to look at when the hive is opened. If it is raining or rain is likely, " +
                "say to wait and say what can be done without opening the hives. Use numbered lines, at most 8 lines. Do not invent anything.",
            daily = true, oldTitle = L("Old plan", "පරණ සැලැස්ම")
        )
        Panel(soft = true) {
            Text(
                L(
                    "The plan is kept on this phone, so you can read it in the apiary without internet.",
                    "සැලැස්ම මේ ෆෝන් එකේ සේව් වෙන නිසා, මී පෙට්ටි ළඟදී ඉන්ටර්නෙට් නැතුවත් කියවන්න පුළුවන්."
                ),
                style = MaterialTheme.typography.bodySmall, color = Muted
            )
        }
    }
}

// ---------- settings ----------

/** A district with the place of its main town, used only to read the weather. */
class District(val english: String, val sinhala: String, val latitude: String, val longitude: String)

val DISTRICTS = listOf(
    District("Ampara", "අම්පාර", "7.30", "81.67"),
    District("Anuradhapura", "අනුරාධපුරය", "8.31", "80.40"),
    District("Badulla", "බදුල්ල", "6.99", "81.05"),
    District("Batticaloa", "මඩකලපුව", "7.72", "81.70"),
    District("Colombo", "කොළඹ", "6.93", "79.85"),
    District("Galle", "ගාල්ල", "6.05", "80.22"),
    District("Gampaha", "ගම්පහ", "7.09", "80.00"),
    District("Hambantota", "හම්බන්තොට", "6.12", "81.12"),
    District("Jaffna", "යාපනය", "9.66", "80.02"),
    District("Kalutara", "කළුතර", "6.58", "79.96"),
    District("Kandy", "මහනුවර", "7.29", "80.63"),
    District("Kegalle", "කෑගල්ල", "7.25", "80.35"),
    District("Kilinochchi", "කිලිනොච්චිය", "9.38", "80.40"),
    District("Kurunegala", "කුරුණෑගල", "7.49", "80.36"),
    District("Mannar", "මන්නාරම", "8.98", "79.90"),
    District("Matale", "මාතලේ", "7.47", "80.62"),
    District("Matara", "මාතර", "5.95", "80.54"),
    District("Monaragala", "මොනරාගල", "6.87", "81.35"),
    District("Mullaitivu", "මුලතිව්", "9.27", "80.81"),
    District("Nuwara Eliya", "නුවරඑළිය", "6.97", "80.77"),
    District("Polonnaruwa", "පොළොන්නරුව", "7.94", "81.00"),
    District("Puttalam", "පුත්තලම", "8.03", "79.83"),
    District("Ratnapura", "රත්නපුරය", "6.68", "80.40"),
    District("Trincomalee", "ත්‍රිකුණාමලය", "8.57", "81.23"),
    District("Vavuniya", "වවුනියාව", "8.75", "80.50")
)

/** A small grey line of help under a setting. */
@Composable
fun Help(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Muted)
}

@Composable
fun SettingsScreen(store: Store, cloud: Cloud, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(store.provider()) }
    var key by remember { mutableStateOf(store.ownApiKey()) }
    var model by remember { mutableStateOf(store.modelName()) }
    var saved by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var showDeveloper by remember { mutableStateOf(false) }
    var autoSummary by remember { mutableStateOf(store.autoSummary()) }

    var address by remember { mutableStateOf(store.ownFirebaseUrl()) }
    var cloudNote by remember { mutableStateOf("") }
    var cloudGood by remember { mutableStateOf(false) }

    var background by remember { mutableStateOf(store.backgroundAlerts()) }
    var notifyAllowed by remember { mutableStateOf(Notifier.allowed(context)) }
    var second by remember { mutableStateOf(store.secondContact()) }
    var minutes by remember { mutableStateOf(store.escalateMinutes().toString()) }

    val place = remember { store.location().split(",") }
    var latitude by remember { mutableStateOf(place.getOrNull(0)?.trim() ?: "") }
    var longitude by remember { mutableStateOf(place.getOrNull(1)?.trim() ?: "") }
    var weatherNote by remember { mutableStateOf("") }
    var showDistricts by remember { mutableStateOf(false) }

    /** Saves the place and reads the weather there, to show that it works. */
    fun readWeather() {
        store.setLocation(latitude.trim() + "," + longitude.trim())
        weatherNote = L("Reading the weather...", "කාලගුණය බලනවා...")
        scope.launch {
            weatherNote = withContext(Dispatchers.IO) {
                try {
                    val w = cloud.weather()
                    val sky = skyName(w.optString("sky"))
                    L("Now: ", "දැන්: ") + oneDecimal(w.optDouble("temperature_c")) + " °C, " +
                        L("humidity ", "තෙතමනය ") + w.optInt("humidity_percent") + " %, " +
                        L("rain ", "වැස්ස ") + oneDecimal(w.optDouble("rain_now_mm")) + " mm" +
                        (if (sky.isNotEmpty()) ", $sky" else "")
                } catch (e: Exception) {
                    friendly(e)
                }
            }
        }
    }

    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifyAllowed = granted
    }

    Page(L("Settings", "සැකසුම්"), "", onBack = { go(Screen.More) }, art = ART_COMB) {
        Panel {
            SectionTitle(L("Language", "භාෂාව"))
            Help(L("The whole app and the assistant use this language.", "මුළු ඇප් එකම සහ AI සහායකයා මේ භාෂාව පාවිච්චි කරනවා."))
            Choice(listOf("en" to "English", "si" to "සිංහල"), if (uiSinhala) "si" else "en") { setAppLanguage(store, it == "si") }
        }

        Panel {
            SectionTitle(L("Online assistant", "AI සහායකයා"))
            if (store.aiBuiltIn()) {
                Text(
                    L("Ready. The assistant comes with the app; you do not need a key.", "සූදානම්. AI සහායකයා ඇප් එකත් එක්කම එනවා; key එකක් ඕනෑ නැහැ."),
                    color = GoodGreen, fontWeight = FontWeight.SemiBold
                )
            } else if (!store.aiReady()) {
                Text(
                    L("The assistant is not set up in this copy of the app. Hive checks work without it.", "මේ ඇප් එකේ AI සහායකයා සකස් කරලා නැහැ. මී පෙට්ටි පරීක්ෂාවට ඒක ඕනෑ නැහැ."),
                    color = Amber, fontWeight = FontWeight.SemiBold
                )
            } else {
                Text(L("Ready (developer key on this phone).", "සූදානම් (මේ ෆෝන් එකේ developer key එකක්)."), color = GoodGreen, fontWeight = FontWeight.SemiBold)
            }
            Help(L("The assistant needs internet. Hive checks work without it.", "AI සහායකයාට ඉන්ටර්නෙට් ඕනෑ. මී පෙට්ටි පරීක්ෂාවට ඒක ඕනෑ නැහැ."))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    L(
                        "Write today's summary by itself, once a day, when the app is opened",
                        "ඇප් එක අරිනකොට, දවසකට සැරයක් අද සාරාංශය ඉබේම ලියන්න"
                    ),
                    color = Ink, modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = autoSummary,
                    onCheckedChange = { on ->
                        autoSummary = on
                        store.setAutoSummary(on)
                    }
                )
            }
            Help(
                L(
                    "When you use the assistant, your question, this app's hive results, your hive details and the device readings are sent to the AI service. Recordings are not sent.",
                    "AI සහායකයා පාවිච්චි කරනකොට, ඔබේ ප්‍රශ්නය, මේ ඇප් එකේ පෙට්ටි ප්‍රතිඵල, පෙට්ටි විස්තර සහ උපකරණ මිනුම් AI සේවාවට යනවා. ශබ්ද රෙකෝඩින් යවන්නේ නැහැ."
                )
            )
        }

        Panel {
            SectionTitle(L("Cloud (hive device and alerts)", "Firebase (පෙට්ටියේ උපකරණය සහ අනතුරු ඇඟවීම්)"))
            Text(
                when {
                    store.firebaseBuiltIn() -> L("Comes with the app; nothing to type.", "ඇප් එකත් එක්කම එනවා; කිසිම දෙයක් ටයිප් කරන්න ඕනෑ නැහැ.")
                    cloud.ready() -> L("Set on this phone (developer setting).", "මේ ෆෝන් එකේ දාලා තියෙනවා (developer setting).")
                    else -> L("Not set up in this copy of the app.", "මේ ඇප් එකේ සකස් කරලා නැහැ.")
                },
                color = if (cloud.ready()) Ink else Amber, fontWeight = FontWeight.SemiBold
            )
            if (cloud.ready()) {
                SecondButton(L("Test the connection", "සම්බන්ධය බලන්න")) {
                    cloudNote = L("Testing...", "බලනවා...")
                    cloudGood = false
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { cloud.test() }
                        cloudGood = result.isEmpty()
                        cloudNote = if (result.isEmpty()) L("Connected. The cloud works.", "සම්බන්ධ වුණා. Firebase වැඩ කරනවා.") else result
                    }
                }
            }
            if (cloudNote.isNotEmpty()) Text(cloudNote, color = if (cloudGood) GoodGreen else Amber, fontWeight = FontWeight.SemiBold)
            Help(
                L(
                    "A copy of each hive check and of each assistant step is kept in the cloud. Recordings are not sent.",
                    "හැම පෙට්ටි පරීක්ෂාවකම සහ සහායකයා කරන හැම දෙයකම පිටපතක් Firebase එකේ තියාගන්නවා. ශබ්ද රෙකෝඩින් යවන්නේ නැහැ."
                )
            )
        }

        // ---------- only for the person who builds and tests the app ----------
        Panel(soft = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L("Developer settings", "Developer settings"), color = Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                SmallAction(if (showDeveloper) L("Hide", "හංගන්න") else L("Show", "පෙන්වන්න"), Muted) { showDeveloper = !showDeveloper }
            }
            if (showDeveloper) {
                Help(
                    L(
                        "Beekeepers do not need these. They are for testing with your own key or database. Leave them empty to use what is built into the app.",
                        "මී පාලකයන්ට මේවා ඕනෑ නැහැ. තමන්ගේම key එකක් හරි database එකක් හරි එක්ක test කරන්න විතරයි. ඇප් එකේ තියෙන ඒවා පාවිච්චි කරන්න හිස්ව තියන්න."
                    )
                )
                Choice(listOf(Store.GROQ to "Groq", Store.GEMINI to "Google Gemini"), provider) { choice ->
                    store.setProvider(choice)
                    provider = choice
                    key = store.ownApiKey()
                    model = store.modelName()
                    saved = false
                }
                Field(
                    value = key,
                    onValueChange = { key = it; saved = false },
                    label = {
                        Text(
                            if (provider == Store.GROQ) L("Own Groq API key (optional)", "තමන්ගේ Groq API key එක (ඕනෑ නම්)")
                            else L("Own Gemini API key", "තමන්ගේ Gemini API key එක")
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    hidden = !showKey,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        L("Key length: " + key.trim().length + " characters", "Key එකේ දිග: අකුරු " + key.trim().length),
                        style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.weight(1f)
                    )
                    SmallAction(if (showKey) L("Hide key", "Key එක හංගන්න") else L("Show key", "Key එක පෙන්වන්න"), Muted) { showKey = !showKey }
                }
                Field(
                    value = model,
                    onValueChange = { model = it; saved = false },
                    label = { Text(L("Model name", "Model නම")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Field(
                    value = address,
                    onValueChange = { address = it; cloudNote = ""; saved = false },
                    label = { Text(L("Own Firebase address (optional)", "තමන්ගේ Firebase ලිපිනය (ඕනෑ නම්)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SecondButton(L("Save developer settings", "Developer settings සේව් කරන්න")) {
                    store.setApiKey(key)
                    store.setModelName(model)
                    store.setFirebaseUrl(address)
                    key = store.ownApiKey()
                    model = store.modelName()
                    address = store.ownFirebaseUrl()
                    saved = true
                }
                if (saved) Text(L("Saved.", "සේව් වුණා."), color = GoodGreen, fontWeight = FontWeight.SemiBold)
            }
        }

        Panel {
            SectionTitle(L("Alerts on this phone", "මේ ෆෝන් එකට එන අනතුරු ඇඟවීම්"))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    L("Check for alerts when the app is closed (about every 15 minutes)", "ඇප් එක වහලා තියෙනකොටත් අනතුරු ඇඟවීම් බලන්න (විනාඩි 15කට විතර සැරයක්)"),
                    color = Ink, modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = background,
                    onCheckedChange = { on ->
                        background = on
                        store.setBackgroundAlerts(on)
                        AlertJob.schedule(app, on)
                        if (on && Build.VERSION.SDK_INT >= 33 && !Notifier.allowed(context)) {
                            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }
            if (!notifyAllowed) {
                Text(L("Notifications are not allowed yet.", "දැනුම්දීම් (notifications) වලට තවම අවසර දීලා නැහැ."), color = WarnRed, fontWeight = FontWeight.SemiBold)
                if (Build.VERSION.SDK_INT >= 33) {
                    SecondButton(L("Allow notifications", "දැනුම්දීම්වලට අවසර දෙන්න")) {
                        notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            } else {
                SecondButton(L("Show a test notification", "දැනුම්දීමක් එන හැටි බලන්න")) {
                    Notifier.show(
                        app, 7,
                        L("CeranaWatch test", "CeranaWatch පරීක්ෂණයක්"),
                        L("This is how a hive alert looks.", "මී පෙට්ටියේ අනතුරු ඇඟවීමක් පේන්නේ මෙහෙමයි.")
                    )
                }
            }
            Text(L("Who uses this phone?", "මේ ෆෝන් එක පාවිච්චි කරන්නේ කවුද?"), fontWeight = FontWeight.SemiBold, color = Ink)
            Choice(
                listOf("owner" to L("The beekeeper", "පෙට්ටි අයිතිකාරයා"), "second" to L("Second contact", "දෙවැනි කෙනා")),
                if (second) "second" else "owner"
            ) { choice ->
                second = choice == "second"
                store.setSecondContact(second)
            }
            Help(
                L(
                    "The second contact is a family member or another beekeeper. Install the app on their phone and choose \"Second contact\" there.",
                    "දෙවැනි කෙනා කියන්නේ පවුලේ කෙනෙක්, නැත්නම් වෙන මී පාලකයෙක්. එයාගේ ෆෝන් එකෙත් මේ ඇප් එක දාලා, එතන \"දෙවැනි කෙනා\" තෝරන්න."
                )
            )
            Field(
                value = minutes,
                onValueChange = { text ->
                    minutes = text.filter { it.isDigit() }.take(4)
                    val n = minutes.toIntOrNull()
                    if (n != null) store.setEscalateMinutes(n)
                },
                label = { Text(L("Minutes before the second contact is told", "දෙවැනි කෙනාට දැනුම් දෙන්න කලින් ඉන්න විනාඩි ගාණ")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Help(L("This number is used on the second contact's phone.", "මේ ගාණ පාවිච්චි වෙන්නේ දෙවැනි කෙනාගේ ෆෝන් එකේ."))
        }

        Panel {
            SectionTitle(L("Place of the apiary (for the weather)", "මී පෙට්ටි තියෙන තැන (කාලගුණය බලන්න)"))
            Help(
                L(
                    "Choose your district. The app uses it only to read the weather.",
                    "ඔබේ දිස්ත්‍රික්කය තෝරන්න. ඇප් එක ඒක පාවිච්චි කරන්නේ කාලගුණය බලන්න විතරයි."
                )
            )
            val chosen = DISTRICTS.firstOrNull { it.latitude == latitude.trim() && it.longitude == longitude.trim() }
            SecondButton(
                if (showDistricts) L("Close the list", "ලැයිස්තුව වහන්න")
                else if (chosen != null) L("District: " + chosen.english + " (change)", "දිස්ත්‍රික්කය: " + chosen.sinhala + " (වෙනස් කරන්න)")
                else L("Choose district", "දිස්ත්‍රික්කය තෝරන්න")
            ) { showDistricts = !showDistricts }
            if (showDistricts) {
                for (row in DISTRICTS.chunked(3)) {
                    Choice(
                        row.map { (it.latitude + "," + it.longitude) to L(it.english, it.sinhala) },
                        latitude.trim() + "," + longitude.trim()
                    ) { place ->
                        latitude = place.substringBefore(",")
                        longitude = place.substringAfter(",")
                        showDistricts = false
                        readWeather()
                    }
                }
            }
            Help(
                L(
                    "For the exact place: open a map app, press and hold on your apiary, and copy the two numbers here.",
                    "හරියටම තැන දාන්න ඕනෑ නම්: Map ඇප් එකක් ඇරලා, මී පෙට්ටි තියෙන තැන ඔබාගෙන ඉඳලා, එන ඉලක්කම් දෙක මෙතනට දාන්න."
                )
            )
            Field(
                value = latitude, onValueChange = { latitude = it; weatherNote = "" },
                label = { Text(L("Latitude (first number)", "Latitude (පළමු ඉලක්කම)")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Field(
                value = longitude, onValueChange = { longitude = it; weatherNote = "" },
                label = { Text(L("Longitude (second number)", "Longitude (දෙවැනි ඉලක්කම)")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            BigButton(L("Save and read the weather", "සේව් කරලා කාලගුණය බලන්න")) { readWeather() }
            if (weatherNote.isNotEmpty()) Text(weatherNote, color = Ink)
        }
    }
}

// ---------- learning on the phone ----------

/** What the models trained on this phone say about one check (worked out away from the screen thread). */
class LearnedView(
    val guess: JSONObject?,          // the guesses kept when answers were first saved (null before any answer)
    val ready: Boolean,              // the app's own strength model has enough earlier answers
    val answers: Int,                // strength answers for earlier checks with enough bee sound
    val problem: Learner.Chance?     // chance of a problem: the kept one, or worked out now
)

/** The card on the result screen. */
@Composable
fun LearnedCard(store: Store, r: CheckRecord, go: (Screen) -> Unit) {
    var view by remember(r.id) { mutableStateOf<LearnedView?>(null) }
    LaunchedEffect(r.id) {
        view = withContext(Dispatchers.Default) {
            val g = Learner.freezeGuess(store, r.id)   // keeps the guesses if answers were saved but not kept yet
            val answers = Learner.strengthAnswers(store, r.id)
            val problem = if (g != null && g.has("problem")) Learner.Chance(g.optDouble("problem"), g.optDouble("usual"), g.optInt("problemN"))
            else if (g == null) Learner.ownProblem(store, r) else null
            // once guesses are kept, "ready" means a strength guess was really kept for this check
            LearnedView(g, if (g != null) g.has("own") else answers >= Learner.MIN_STRENGTH, answers, problem)
        }
    }
    Panel(accent = InfoBlue) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Doodle(ART_FLOWER, 26.dp)
            SectionTitle(L("Learned on this phone", "මේ ෆෝන් එකේම ඉගෙන ගත්ත දේ"))
        }
        val v = view
        if (v == null) {
            BusyLine(L("Learning from your answers...", "ඔබේ පිළිතුරුවලින් ඉගෙන ගන්නවා..."))
        } else {
            val judged = Learner.judgement(r)
            val own = if (v.guess != null) textOf(v.guess, "own") else ""
            if (judged != null && own.isNotEmpty()) {
                // the beekeeper has answered: show what the app had guessed before that, and if it was right
                Text(L("Before your answer, the app's own model guessed", "ඔබ පිළිතුරු දෙන්න කලින් ඇප් එකේම මොඩලය අනුමාන කළේ"), color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(strengthName(own), fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                ChipRow {
                    Chip(L("You said: ", "ඔබ කිව්වේ: ") + strengthName(judged))
                    if (own == judged) Chip(L("The app was right", "ඇප් එක හරි"), GoodGreen) else Chip(L("The app was wrong", "ඇප් එක වැරදියි"), WarnRed)
                }
                Text(
                    L(
                        "It had learned from ${v.guess?.optInt("ownN") ?: 0} of your strength answers for earlier checks.",
                        "ඒක කලින් පරීක්ෂාවලට ඔබ දීපු ශක්තිය ගැන පිළිතුරු ${v.guess?.optInt("ownN") ?: 0}කින් ඉගෙන ගෙන තිබුණා."
                    ),
                    color = Muted
                )
            } else if (judged != null) {
                Text(
                    L(
                        "The app's own model was not ready when you answered. It starts after ${Learner.MIN_STRENGTH} answers for earlier checks.",
                        "ඔබ පිළිතුරු දුන්න වෙලාවේ ඇප් එකේම මොඩලය ලෑස්ති නැහැ. ඒක පටන් ගන්නේ කලින් පරීක්ෂාවලට පිළිතුරු ${Learner.MIN_STRENGTH}ක් දුන්නාට පස්සේ."
                    ),
                    color = Ink
                )
            } else if (v.ready) {
                Text(
                    L(
                        "The app's own model has a guess for this colony. Open the hive and answer \"How strong is this colony?\" first. Then you will see its guess and whether it was right.",
                        "ඇප් එකේම මොඩලයට මේ ජනපදය ගැන අනුමානයක් තියෙනවා. මුලින් පෙට්ටිය ඇරලා බලලා \"මේ ජනපදය කොච්චර ශක්තිමත්ද?\" කියන එකට පිළිතුරු දෙන්න. ඊට පස්සේ ඒ අනුමානයත්, ඒක හරිද කියලත් පේනවා."
                    ),
                    color = Ink
                )
                Text(
                    L(
                        "It is hidden until then, so it cannot change your answer and its score stays fair.",
                        "ඔබේ පිළිතුරට බලපාන්නේ නැති වෙන්නයි, ඒකේ ලකුණු සාධාරණව තියාගන්නයි, ඒ වෙනකල් ඒක හංගලා තියෙන්නේ."
                    ),
                    style = MaterialTheme.typography.bodySmall, color = Muted
                )
            } else {
                Text(
                    L(
                        "The app's own strength model starts after ${Learner.MIN_STRENGTH} of your answers for earlier checks with enough bee sound. So far: ${v.answers}.",
                        "ඇප් එකේම ශක්තිය මොඩලය පටන් ගන්නේ, මී මැස්සන්ගේ ශබ්දය හොඳට තිබුණු කලින් පරීක්ෂාවලට ඔබ පිළිතුරු ${Learner.MIN_STRENGTH}ක් දුන්නාට පස්සේ. මේ වෙනකල්: ${v.answers}."
                    ),
                    color = Ink
                )
                FillBar(v.answers.toFloat() / Learner.MIN_STRENGTH, InfoBlue)
                Text(
                    L(
                        "Open the hive, then answer \"How strong is this colony?\" in the questions.",
                        "පෙට්ටිය ඇරලා බලලා, ප්‍රශ්නවල \"මේ ජනපදය කොච්චර ශක්තිමත්ද?\" කියන එකට පිළිතුරු දෙන්න."
                    ),
                    style = MaterialTheme.typography.bodySmall, color = Muted
                )
            }
            val p = v.problem
            if (p != null) {
                val percent = Math.round(p.chance * 100.0)
                val usual = Math.round(p.usual * 100.0)
                Text(
                    L(
                        "Chance of a problem (no eggs, wasps, wax moth, bees leaving or no food): about $percent%. In your answers so far, $usual% of checks had a problem. (Learned from ${p.examples} of your answers.)",
                        "කරදරයක් තියෙන්න තියෙන ඉඩ (බිත්තර නැතිවීම, දෙබරු, ඉටි පණුවා, මැස්සෝ යනවා, කෑම නැතිවීම): සියයට $percent ක් විතර. ඔබ මේ වෙනකල් දීපු පිළිතුරුවලින්, කරදරයක් තිබුණේ පරීක්ෂාවලින් සියයට $usual ක. (ඔබේ පිළිතුරු ${p.examples}කින් ඉගෙන ගත්තා.)"
                    ),
                    color = Ink
                )
                if (p.higherThanUsual()) {
                    Chip(L("Higher than usual: look carefully", "සාමාන්‍යයට වඩා වැඩියි: හොඳට බලන්න"), Amber)
                }
            }
            Text(
                L(
                    "The app learns only from answers for checks made before this one. It is still experimental.",
                    "ඇප් එක ඉගෙන ගන්නේ මේකට කලින් කරපු පරීක්ෂාවලට දීපු පිළිතුරුවලින් විතරයි. මේක තවම අත්හදා බලන දෙයක්."
                ),
                style = MaterialTheme.typography.labelSmall, color = Muted
            )
        }
        SecondButton(L("What the app has learned", "ඇප් එක ඉගෙන ගත්ත දේ")) { go(Screen.LearnView) }
    }
}

/** One score with a bar: "7 of 10 right". */
@Composable
fun ScoreLine(name: String, s: Learner.Score, color: Color = Ink) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = Ink, modifier = Modifier.weight(1f))
            Text(
                L("${s.right} of ${s.total} right", "${s.total}න් ${s.right}ක් හරි"),
                color = Ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium
            )
        }
        FillBar(if (s.total == 0) 0f else s.right.toFloat() / s.total, color)
    }
}

/** A plain sentence that says if the app's own model beat the simple guesses (on the same checks). */
fun verdictText(own: Learner.Score, others: List<Learner.Score>): String {
    if (own.total < 10) return L(
        "Too few checks to judge yet. At least 10 are needed.",
        "තවම විනිශ්චය කරන්න පරීක්ෂා මදි. අඩුම තරමේ 10ක් ඕනෑ."
    )
    val best = others.maxOfOrNull { it.right } ?: 0
    return if (own.right > best) L(
        "So far the app's own model is right more often than the simple guesses.",
        "මේ වෙනකල් ඇප් එකේම මොඩලය සරල අනුමානවලට වඩා වැඩි පාරක් හරි ගිහින් තියෙනවා."
    ) else L(
        "So far the app's own model is not better than a simple guess. Keep answering, and do not rely on it yet.",
        "මේ වෙනකල් ඇප් එකේම මොඩලය සරල අනුමානයකට වඩා හොඳ නැහැ. දිගටම පිළිතුරු දෙන්න, ඒත් තවම ඒක විශ්වාස කරන්න එපා."
    )
}

@Composable
fun LearningScreen(store: Store, go: (Screen) -> Unit) {
    var board by remember { mutableStateOf<Learner.Scoreboard?>(null) }
    LaunchedEffect(Unit) {
        board = withContext(Dispatchers.Default) { Learner.scoreboard(store) }
    }
    Page(
        L("What the app has learned", "ඇප් එක ඉගෙන ගත්ත දේ"),
        L("Trained on this phone from your answers", "ඔබේ පිළිතුරුවලින් මේ ෆෝන් එකේම පුහුණු කළා"),
        onBack = { go(Screen.More) }, art = ART_FLOWER
    ) {
        Panel(soft = true) {
            Text(
                L(
                    "After each check, open the hive and answer the questions. The app trains its own small models on this phone from your answers. No internet is needed.",
                    "හැම පරීක්ෂාවකටම පස්සේ පෙට්ටිය ඇරලා බලලා ප්‍රශ්නවලට පිළිතුරු දෙන්න. ඇප් එක ඔබේ පිළිතුරුවලින් මේ ෆෝන් එකේම තමන්ගේ පොඩි මොඩල හදාගන්නවා. ඉන්ටර්නෙට් ඕනෑ නැහැ."
                ),
                color = Ink
            )
            Text(
                L(
                    "To test itself fairly, the app makes its guess for a check from earlier answers only, and keeps it the moment you first answer. Changing answers later does not change a kept guess. Then it compares itself with simple guesses on the same checks.",
                    "සාධාරණව තමන්වම පරීක්ෂා කරන්න, ඇප් එක පරීක්ෂාවක් ගැන අනුමානය හදන්නේ කලින් පිළිතුරුවලින් විතරයි. ඔබ මුලින්ම පිළිතුරු දෙන මොහොතේම ඒ අනුමානය සේව් කරනවා. පස්සේ පිළිතුරු වෙනස් කළත් ඒක වෙනස් වෙන්නේ නැහැ. ඊට පස්සේ ඒ පරීක්ෂාවලම සරල අනුමාන එක්ක තමන්ව සසඳනවා."
                ),
                style = MaterialTheme.typography.bodySmall, color = Muted
            )
        }
        val b = board
        if (b == null) {
            BusyLine(L("Working it out...", "ගණන් හදනවා..."))
        } else {
            // colony strength
            Panel {
                SectionTitle(L("Colony strength", "ජනපදයේ ශක්තිය"))
                Text(
                    L("Your answers: ", "ඔබේ පිළිතුරු: ") + b.strengthAnswers + "  (" +
                        Learner.STRENGTHS.joinToString(", ") { strengthName(it) + " " + (b.strengthCounts[it] ?: 0) } + ")",
                    color = Muted
                )
                if (b.own.total == 0) {
                    Text(
                        L(
                            "Nothing to compare yet. The app's own model starts after ${Learner.MIN_STRENGTH} answers. After that, every new answer tests it.",
                            "තවම සසඳන්න දෙයක් නැහැ. ඇප් එකේම මොඩලය පටන් ගන්නේ පිළිතුරු ${Learner.MIN_STRENGTH}කට පස්සේ. ඊට පස්සේ හැම අලුත් පිළිතුරකින්ම ඒක පරීක්ෂා වෙනවා."
                        ),
                        color = Ink
                    )
                } else {
                    ScoreLine(L("App's own model", "ඇප් එකේම මොඩලය"), b.own, Ink)
                    ScoreLine(L("The 70-hive model", "පෙට්ටි 70කින් හදපු මොඩලය"), b.fixed, VoteMedium)
                    ScoreLine(L("Always your most common answer", "හැමවෙලේම ඔබ වැඩිපුරම දීපු පිළිතුර"), b.mostCommon, VoteMedium)
                    Text(verdictText(b.own, listOf(b.fixed, b.mostCommon)), color = Ink, fontWeight = FontWeight.SemiBold)
                    if (b.repeatOwn.total > 0) {
                        Text(L("Hives you had answered for before", "කලින් පිළිතුරු දීපු පෙට්ටි"), color = Muted, style = MaterialTheme.typography.labelLarge)
                        ScoreLine(L("App's own model", "ඇප් එකේම මොඩලය"), b.repeatOwn, Ink)
                        ScoreLine(L("Same as your last answer for that hive", "ඒ පෙට්ටියට ඔබ කලින් දීපු පිළිතුරම"), b.repeatLast, VoteMedium)
                    }
                    if (b.newOwn.total > 0) {
                        Text(L("Hives you had not answered for before", "කලින් පිළිතුරු දීලා නැති පෙට්ටි"), color = Muted, style = MaterialTheme.typography.labelLarge)
                        ScoreLine(L("App's own model", "ඇප් එකේම මොඩලය"), b.newOwn, Ink)
                        ScoreLine(L("The 70-hive model", "පෙට්ටි 70කින් හදපු මොඩලය"), b.newFixed, VoteMedium)
                    }
                }
            }

            // problems from the five questions
            Panel {
                SectionTitle(L("Colony problems", "ජනපදයේ කරදර"))
                Text(
                    L(
                        "Checks where your answers show if there was a problem: ${b.problemAnswers}. With a problem: ${b.problemsSeen}.",
                        "කරදරයක් තිබුණද නැද්ද කියලා ඔබේ පිළිතුරුවලින් පේන පරීක්ෂා: ${b.problemAnswers}. කරදරයක් තිබුණු ඒවා: ${b.problemsSeen}."
                    ),
                    color = Muted
                )
                if (b.ownProblem.total == 0) {
                    Text(
                        L(
                            "Starts after at least ${Learner.MIN_EACH} checks with a problem and ${Learner.MIN_EACH} without one.",
                            "පටන් ගන්නේ කරදරයක් තිබුණු පරීක්ෂා ${Learner.MIN_EACH}කුයි, කරදරයක් නැති පරීක්ෂා ${Learner.MIN_EACH}කුයි තිබුණාට පස්සේ."
                        ),
                        color = Ink
                    )
                } else {
                    ScoreLine(L("App's own model (says \"problem\" when the chance is higher than usual)", "ඇප් එකේම මොඩලය (ඉඩ සාමාන්‍යයට වඩා වැඩි නම් \"කරදරයක්\" කියනවා)"), b.ownProblem, Ink)
                    ScoreLine(L("Always \"no problem\"", "හැමවෙලේම \"කරදරයක් නැහැ\" කියන එක"), b.neverProblem, VoteMedium)
                    Text(verdictText(b.ownProblem, listOf(b.neverProblem)), color = Ink, fontWeight = FontWeight.SemiBold)
                }
            }

            // hive device alerts
            Panel {
                SectionTitle(L("Hive device alerts", "පෙට්ටියේ උපකරණයේ අනතුරු ඇඟවීම්"))
                Text(
                    L(
                        "Alerts you marked: ${b.alertAnswers}. Real: ${b.realAlerts}.",
                        "ඔබ ඇත්තද බොරුද කියපු ඇඟවීම්: ${b.alertAnswers}. ඇත්ත ඒවා: ${b.realAlerts}."
                    ),
                    color = Muted
                )
                if (b.ownAlert.total == 0) {
                    Text(
                        L(
                            "Starts after you mark at least ${Learner.MIN_EACH} real alerts and ${Learner.MIN_EACH} false alarms.",
                            "පටන් ගන්නේ ඔබ ඇත්ත ඇඟවීම් ${Learner.MIN_EACH}කුයි, බොරු ඇඟවීම් ${Learner.MIN_EACH}කුයි කිව්වාට පස්සේ."
                        ),
                        color = Ink
                    )
                } else {
                    ScoreLine(L("App's own alert checker (says \"real\" when the chance is higher than usual)", "ඇප් එකේම ඇඟවීම් බලන මොඩලය (ඉඩ සාමාන්‍යයට වඩා වැඩි නම් \"ඇත්ත\" කියනවා)"), b.ownAlert, Ink)
                    ScoreLine(L("Every alert is real", "හැම ඇඟවීමක්ම ඇත්ත කියලා හිතන එක"), b.alwaysReal, VoteMedium)
                    Text(verdictText(b.ownAlert, listOf(b.alwaysReal)), color = Ink, fontWeight = FontWeight.SemiBold)
                }
            }

            Text(
                L(
                    "Note: the strength guess is hidden until you answer, so it cannot change your answer. The problem and alert chances are shown before you answer, because they help you decide where to look, so they may affect your answers a little.",
                    "සටහන: ශක්තිය ගැන අනුමානය ඔබ පිළිතුරු දෙනකල් හංගලා තියෙන්නේ, ඒක ඔබේ පිළිතුරට බලපාන්නේ නැති වෙන්න. කරදර සහ ඇඟවීම් ගැන ඉඩ ඔබ පිළිතුරු දෙන්න කලින්ම පෙන්වනවා, මොකද ඒවා කොහෙද බලන්න ඕනෑ කියලා තීරණය කරන්න උදව් වෙනවා. ඒ නිසා ඒවා ඔබේ පිළිතුරුවලට ටිකක් බලපාන්න පුළුවන්."
                ),
                style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(start = 4.dp)
            )
            Panel(soft = true) {
                SectionTitle(L("What the field test showed", "ක්ෂේත්‍ර පරීක්ෂණයෙන් පෙනුණු දේ"))
                Text(
                    L(
                        "On the 70 field recordings, at the largest apiary (29 hives), the 70-hive model made without that apiary was right about 1 time in 4. " +
                            "After learning from the beekeeper's answers it was right about 2 times in 3. That is still a little below always giving the most common answer (about 7 in 10). " +
                            "For a hive checked before, repeating the last answer was as good. So the scores above, from your own hives, are the real test.",
                        "ක්ෂේත්‍රයේ පෙට්ටි 70ක රෙකෝඩින්වලින් බැලුවාම, ලොකුම මී පාලන ස්ථානයේ (පෙට්ටි 29), ඒ ස්ථානය නැතුව හදපු මොඩලය හරි ගියේ 4න් 1ක් විතරයි. " +
                            "මී පාලකයාගේ පිළිතුරුවලින් ඉගෙන ගත්තාම 3න් 2ක් විතර හරි ගියා. ඒත් ඒක හැමවෙලේම වැඩිපුරම දීපු පිළිතුර කියනවාට (10න් 7ක් විතර) වඩා ටිකක් අඩුයි. " +
                            "කලින් බලපු පෙට්ටියකට, කලින් දීපු පිළිතුරම කිව්වත් ඒ තරමටම හරි. ඒ නිසා ඇත්ත පරීක්ෂණය ඔබේම පෙට්ටිවලින් එන උඩ තියෙන ලකුණු."
                    ),
                    style = MaterialTheme.typography.bodySmall, color = Muted
                )
            }
        }
    }
}
