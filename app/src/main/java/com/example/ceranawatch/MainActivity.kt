package com.example.ceranawatch

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Black, white and greys for the standard parts (text boxes, switch, loading circle). */
private val AppColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Soft,
    onPrimaryContainer = Ink,
    secondary = Ink,
    onSecondary = Color.White,
    secondaryContainer = Soft,
    onSecondaryContainer = Ink,
    tertiary = Ink,
    onTertiary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Soft,
    onSurfaceVariant = Muted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Paper,
    surfaceContainer = Soft,
    surfaceContainerHigh = Soft,
    surfaceContainerHighest = Line,
    outline = Faint,
    outlineVariant = Line,
    error = WarnRed,
    onError = Color.White
)

/** The screens of the app. */
sealed class Screen {
    object Home : Screen()
    data class Live(val hive: String?) : Screen()
    object Alerts : Screen()
    object More : Screen()
    object Settings : Screen()
    object Plan : Screen()
    object About : Screen()
    object LearnView : Screen()
    data class HiveView(val name: String) : Screen()
    data class DetailsView(val hive: String) : Screen()
    data class CheckView(val hive: String) : Screen()
    data class ResultView(val id: Long) : Screen()
    data class QuestionsView(val id: Long) : Screen()
    data class ChatView(val hive: String?) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // draw behind the status bar, with dark icons on the light screens
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        // keep the screen on, so the phone does not sleep in the middle of a recording
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
                    CeranaApp()
                }
            }
        }
    }
}

@Composable
fun CeranaApp() {
    val context = LocalContext.current
    val app = context.applicationContext
    val store = remember { Store.get(app) }
    val cloud = remember { Cloud(store) }
    val agent = remember { Agent(store, cloud) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val go: (Screen) -> Unit = { screen = it }

    // use the language that was chosen last time, and keep the background alert check as it was set
    remember {
        uiSinhala = store.sinhala()
        openAlerts = AlertItem.listFromSaved(store.savedText("alerts")).count { !it.ack }
        AlertJob.schedule(app, store.backgroundAlerts())
        0
    }

    // while the app is open: look at the internet, the alerts and the hive devices every 30 seconds
    LaunchedEffect(Unit) {
        while (true) {
            isOnline = readOnline(app)
            if (isOnline && cloud.ready()) {
                val result = withContext(Dispatchers.IO) { AlertChecker.check(app, store) }
                openAlerts = result.open
                val nodes = withContext(Dispatchers.IO) {
                    try {
                        cloud.nodes()
                    } catch (_: Exception) {
                        null
                    }
                }
                if (nodes != null) liveNodes = nodes
            }
            delay(30000)
        }
    }

    val current = screen
    BackHandler(enabled = current != Screen.Home) {
        screen = when (current) {
            is Screen.DetailsView -> Screen.HiveView(current.hive)
            is Screen.CheckView -> Screen.HiveView(current.hive)
            is Screen.ResultView -> {
                val record = store.check(current.id)
                if (record != null) Screen.HiveView(record.hive) else Screen.Home
            }
            is Screen.QuestionsView -> Screen.ResultView(current.id)
            is Screen.ChatView -> if (current.hive != null) Screen.HiveView(current.hive) else Screen.Home
            is Screen.Settings, is Screen.Plan, is Screen.About, is Screen.LearnView -> Screen.More
            is Screen.Live -> if (current.hive != null) Screen.HiveView(current.hive) else Screen.Home
            else -> Screen.Home
        }
    }

    val tab = when (current) {
        is Screen.Home -> TAB_HIVES
        is Screen.Live -> TAB_LIVE
        is Screen.Alerts -> TAB_ALERTS
        is Screen.More -> TAB_MORE
        else -> -1
    }

    // while the keyboard is open the bottom bar is hidden, so the typing area is not squeezed
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showBar = tab >= 0 && !keyboardOpen

    Column(modifier = Modifier.fillMaxSize().background(Paper)) {
        // screens without the bottom bar keep clear of the phone's own navigation buttons
        val area = if (showBar) Modifier.weight(1f).fillMaxWidth() else Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()
        Box(modifier = area) {
            when (current) {
                is Screen.Home -> HomeScreen(store, agent, go)
                is Screen.Live -> LiveScreen(store, cloud, current.hive, go)
                is Screen.Alerts -> AlertsScreen(store, cloud, agent, go)
                is Screen.More -> MoreScreen(store, go)
                is Screen.Settings -> SettingsScreen(store, cloud, go)
                is Screen.Plan -> PlanScreen(store, agent, go)
                is Screen.About -> AboutScreen(go)
                is Screen.LearnView -> LearningScreen(store, go)
                is Screen.HiveView -> HiveScreen(store, current.name, go)
                is Screen.DetailsView -> DetailsScreen(store, agent, current.hive, go)
                is Screen.CheckView -> CheckScreen(store, cloud, current.hive, go)
                is Screen.ResultView -> ResultScreen(store, cloud, agent, current.id, go)
                is Screen.QuestionsView -> QuestionsScreen(store, cloud, current.id, go)
                is Screen.ChatView -> ChatScreen(store, agent, current.hive, go)
            }
        }
        if (showBar) {
            BottomBar(tab) { chosen ->
                screen = when (chosen) {
                    TAB_HIVES -> Screen.Home
                    TAB_LIVE -> Screen.Live(null)
                    TAB_ALERTS -> Screen.Alerts
                    TAB_CHAT -> Screen.ChatView(null)
                    else -> Screen.More
                }
            }
        }
    }
}

/** A line that says the assistant is working. */
@Composable
fun BusyLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
        Text(text, color = Muted, modifier = Modifier.weight(1f))
    }
}

// ---------- a card whose text is written by the assistant and kept on the phone ----------

/** The day on which the app last tried to write the daily summary by itself, so it tries only once a day. */
private var autoSummaryTriedDay = ""

private fun todayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

/**
 * Shows a text the assistant wrote earlier (so it can be read without internet),
 * with a button that asks the assistant to write it again.
 *
 * daily = the text is about "today". One from an earlier day is clearly marked as old.
 * auto = the app asks for a new text by itself, once a day, when it has internet.
 */
@Composable
fun AssistantCard(
    store: Store, agent: Agent,
    name: String, task: String, hive: String?,
    title: String, hint: String, buttonText: String,
    instruction: String,
    daily: Boolean = false,
    oldTitle: String = title,
    auto: Boolean = false
) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var text by remember(name) { mutableStateOf(store.savedText(name)) }
    var time by remember(name) { mutableStateOf(store.savedTextTime(name)) }
    var busy by remember(name) { mutableStateOf(false) }
    var problem by remember(name) { mutableStateOf("") }
    var used by remember(name) { mutableStateOf(listOf<String>()) }
    val old = daily && text.isNotEmpty() && !isToday(time)

    fun write(byItself: Boolean) {
        if (busy) return
        busy = true
        problem = ""
        used = emptyList()
        scope.launch {
            val steps = withContext(Dispatchers.IO) {
                val done = agent.runTask(task, instruction, hive)
                // saved here, so the text is kept even when the user has already left this screen
                val written = agent.answerOf(done)
                if (written != null) {
                    store.saveText(name, written)
                    if (byItself) store.setAutoSummaryDay(todayKey())
                }
                done
            }
            val answer = agent.answerOf(steps)
            if (answer != null) {
                text = answer
                time = store.savedTextTime(name)
                used = steps.filter { it.kind == AgentStep.TOOL }.map { it.text }
            } else {
                // when it was written by itself, a failure is not shown: the old text stays and it tries again next time
                if (!byItself) problem = agent.errorOf(steps) ?: L("Something went wrong.", "මොකක් හරි වැරදීමක් වුණා.")
            }
            busy = false
        }
    }

    // once a day, when the app is opened with internet, the summary is written without being asked
    if (auto) {
        LaunchedEffect(name, isOnline) {
            val today = todayKey()
            // another screen may have finished writing it in the meantime
            val savedTime = store.savedTextTime(name)
            if (savedTime != time) {
                text = store.savedText(name)
                time = savedTime
            }
            val needed = text.isEmpty() || !isToday(time)
            if (autoSummaryTriedDay != today && needed && store.autoSummary() && store.autoSummaryDay() != today &&
                store.aiReady() && store.hives().isNotEmpty() && readOnline(app)
            ) {
                autoSummaryTriedDay = today
                write(true)
            }
        }
    }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconSpot(44.dp) { TabIcon(TAB_CHAT, Ink, 22.dp) }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                SectionTitle(if (old) oldTitle else title)
                if (text.isNotEmpty() && time > 0L) {
                    Text(L("Written: ", "ලිව්වේ: ") + clock(time), style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
        }
        if (old && !busy) {
            Text(
                L(
                    "This was written on an earlier day. Press the button below for a new one.",
                    "මේක කලින් දවසක ලියපු එකක්. අලුත් එකක් ගන්න පහළ බොත්තම ඔබන්න."
                ),
                color = Amber, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall
            )
        }
        if (text.isEmpty() && !busy) Text(hint, color = Muted)
        if (text.isNotEmpty()) Text(text, color = if (old) Muted else Ink)
        for (step in used) Text("• $step", style = MaterialTheme.typography.labelSmall, color = Muted)
        if (problem.isNotEmpty()) Text(problem, color = Amber, style = MaterialTheme.typography.bodySmall)
        if (busy) {
            BusyLine(L("The assistant is reading the data...", "සහායකයා තොරතුරු බලනවා..."))
        } else {
            SecondButton(buttonText, enabled = isOnline) { write(false) }
            if (!isOnline) {
                Text(
                    if (text.isEmpty()) L("The assistant needs internet.", "සහායකයාට ඉන්ටර්නෙට් ඕනෑ.")
                    else L("The assistant needs internet. The text above was saved earlier.", "සහායකයාට ඉන්ටර්නෙට් ඕනෑ. උඩ තියෙන්නේ කලින් සේව් වුණ එකක්."),
                    style = MaterialTheme.typography.labelSmall, color = Muted
                )
            }
        }
    }
}

// ---------- home ----------

/** A small white pill that can be tapped (used for the language switch). */
@Composable
fun PillButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(50.dp))
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun HomeScreen(store: Store, agent: Agent, go: (Screen) -> Unit) {
    var hives by remember { mutableStateOf(store.hives()) }
    var newName by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val nodes = liveNodes
    val devicesOnline = nodes.values.count { it.online() }
    val checkedToday = hives.count { name -> store.checks(name).firstOrNull()?.let { isToday(it.id) } == true }
    val pad = screenPad()
    val narrow = narrowScreen()

    Column(modifier = Modifier.fillMaxSize().background(Paper)) {
        // the top line: logo, name, internet badge and language switch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = pad, end = pad, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LogoMark(34.dp)
            Text(
                "CeranaWatch", color = Ink, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            OnlineBadge(small = narrow)
            PillButton(if (uiSinhala) "EN" else "සිං") { setAppLanguage(store, !uiSinhala) }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PageBody {
                // the black card: the apiary at a glance
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Ink)
                ) {
                    HeroArt(
                        width = if (narrow) 124.dp else 158.dp,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 12.dp)
                    )
                    Column(modifier = Modifier.padding(20.dp)) {
                        // the words keep to the left half, so they never run into the drawing
                        Text(
                            L("Your apiary", "ඔබ ළඟ තියෙන්නේ"), color = Color(0xFFB9B9B2), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.5f)
                        )
                        Text(
                            L(
                                if (hives.size == 1) "1 hive" else "" + hives.size + " hives",
                                "මී පෙට්ටි " + hives.size + "ක්"
                            ),
                            color = Color.White, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.5f)
                        )
                        Spacer(modifier = Modifier.height(if (narrow) 40.dp else 52.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatBox("" + checkedToday + "/" + hives.size, L("Checked today", "අද පරීක්ෂා කළා"), Modifier.weight(1f).fillMaxHeight())
                            StatBox(openAlerts.toString(), L("New alerts", "අලුත් ඇඟවීම්"), Modifier.weight(1f).fillMaxHeight())
                            StatBox("" + devicesOnline + "/" + nodes.size, L("Devices online", "ඔන්ලයින් උපකරණ"), Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }

                if (openAlerts > 0) {
                    Panel(accent = WarnRed, onClick = { go(Screen.Alerts) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                L(
                                    if (openAlerts == 1) "1 new alert from the hive devices" else "$openAlerts new alerts from the hive devices",
                                    "උපකරණවලින් අලුත් අනතුරු ඇඟවීම් ${openAlerts}ක් තියෙනවා"
                                ),
                                color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                            )
                            Chevron()
                        }
                    }
                }

                AssistantCard(
                    store, agent, "summary", Agent.SUMMARY, null,
                    L("Today's summary", "අද සාරාංශය"),
                    L(
                        "Ask the assistant for a short summary of all your hives, alerts and today's weather.",
                        "ඔබේ හැම මී පෙට්ටියක්ම, අනතුරු ඇඟවීම් සහ අද කාලගුණය ගැන කෙටි සාරාංශයක් AI සහායකයාගෙන් ඉල්ලන්න."
                    ),
                    L("Write today's summary", "අද සාරාංශය ලියන්න"),
                    "Write today's short summary for the beekeeper. First call list_hives. Then call get_alerts with only_open true, " +
                        "get_weather, and get_node_latest for each hive. If a tool says something is not set up, skip it and do not complain. " +
                        "Use each hive's health_status from list_hives as it is; never call a hive healthy unless its status says so. " +
                        "Write at most 6 short lines: one line for each hive that needs attention (say why, from the data), one line for the " +
                        "hives that are fine, one line on new alerts, and one line on today's weather for beekeeping. Do not invent anything.",
                    daily = true, oldTitle = L("Old summary", "පරණ සාරාංශය"), auto = true
                )

                ListLabel(L("Your hives", "ඔබේ මී පෙට්ටි"))
                if (hives.isEmpty()) {
                    EmptyState(
                        ART_HIVE,
                        L("No hives yet", "තවම මී පෙට්ටි නැහැ"),
                        L("Add your first hive below, then tap it to do a check.", "පහළින් ඔබේ පළමු මී පෙට්ටිය එකතු කරන්න. ඊට පස්සේ ඒක ඔබලා පරීක්ෂාවක් කරන්න.")
                    )
                }
                for (name in hives) {
                    val last = store.checks(name).firstOrNull()
                    val node = nodes[name]
                    Panel(onClick = { go(Screen.HiveView(name)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconSpot { Doodle(ART_HIVE, 28.dp) }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (last == null) L("No checks yet", "තවම පරීක්ෂා කරලා නැහැ")
                                    else L("Last check: ", "අන්තිම පරීක්ෂාව: ") + clock(last.id),
                                    color = Muted, style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Chevron()
                        }
                        HealthLine(hiveHealth(store, name))
                        if (last != null || node != null) {
                            ChipRow {
                                if (last != null) {
                                    Chip(outcomeWord(last), outcomeColor(last))
                                    if (last.normalStatus == Store.CHANGED) Chip(L("Changed", "වෙනස් වෙලා"), WarnRed)
                                    if (last.normalStatus == Store.NORMAL) Chip(L("Own normal", "සුපුරුදුයි"), GoodGreen)
                                }
                                if (node != null) Chip(nodeWord(node), nodeColor(node))
                                if (node != null && node.online() && node.hasTemp()) Chip(tempText(node.tempC), if (node.isHot()) WarnRed else Ink)
                            }
                        }
                    }
                }
                for (name in nodes.keys.filter { !hives.contains(it) }) {
                    Panel(soft = true) {
                        Text(
                            L(
                                "A hive device named $name is sending data, but this hive is not in your list.",
                                "$name කියන උපකරණයකින් තොරතුරු එනවා. ඒත් ඒ නමින් මී පෙට්ටියක් ඔබේ ලැයිස්තුවේ නැහැ."
                            ),
                            color = Ink
                        )
                        SecondButton(L("Add $name to my hives", "$name මගේ පෙට්ටිවලට එකතු කරන්න")) {
                            store.addHive(name)
                            hives = store.hives()
                        }
                    }
                }

                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Doodle(ART_BLOOM, 26.dp)
                        SectionTitle(L("Add a hive", "මී පෙට්ටියක් එකතු කරන්න"))
                    }
                    Field(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(L("Hive name, for example H01", "පෙට්ටියේ නම, උදා: H01")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    BigButton(L("Add hive", "පෙට්ටිය එකතු කරන්න"), enabled = newName.trim().isNotEmpty()) {
                        if (store.addHive(newName)) {
                            newName = ""
                            note = ""
                            hives = store.hives()
                        } else {
                            note = "error"
                        }
                    }
                    if (note.isNotEmpty()) {
                        Text(
                            L(
                                "Type a new name. Use English letters and numbers only.",
                                "අලුත් නමක් ටයිප් කරන්න. ඉංග්‍රීසි අකුරු සහ ඉලක්කම් විතරක් පාවිච්චි කරන්න."
                            ),
                            color = WarnRed
                        )
                    }
                    Text(
                        L(
                            "If the hive has a device, use the same name here as in the device.",
                            "පෙට්ටියට උපකරණයක් තියෙනවා නම්, උපකරණයේ තියෙන නමම මෙතන දාන්න."
                        ),
                        style = MaterialTheme.typography.bodySmall, color = Muted
                    )
                }
            }
        }
    }
}

// ---------- one hive ----------

/** A square button with a small picture, used in a row of three. All three get the same size. */
@Composable
fun Tile(text: String, modifier: Modifier, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(CardRadius))
            .background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(CardRadius))
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconSpot(44.dp) { icon() }
        Text(
            text, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
    }
}

@Composable
fun HiveScreen(store: Store, name: String, go: (Screen) -> Unit) {
    val checks = store.checks(name)
    val goodChecks = checks.count { it.shape != null }
    val node = liveNodes[name]
    val distances = checks.filter { it.normalDistance != null }.sortedBy { it.id }.map { it.normalDistance ?: 0.0 }

    Page(name, L("Hive", "මී පෙට්ටිය"), onBack = { go(Screen.Home) }, art = ART_HIVE) {
        // the hive's health status: tapping it opens the newest inspection (or the newest check)
        val health = hiveHealth(store, name)
        val openId = health.inspectionId ?: checks.firstOrNull()?.id
        HealthCard(health, onClick = if (openId != null) ({ go(Screen.ResultView(openId)) }) else null)
        BigButton(L("Check this hive now", "මේ පෙට්ටිය දැන් පරීක්ෂා කරන්න")) { go(Screen.CheckView(name)) }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Tile(L("Details and notes", "විස්තර සහ සටහන්"), Modifier.weight(1f).fillMaxHeight(), { go(Screen.DetailsView(name)) }) { Doodle(ART_FLOWER, 26.dp) }
            Tile(L("Live device", "උපකරණයේ තොරතුරු"), Modifier.weight(1f).fillMaxHeight(), { go(Screen.Live(name)) }) { TabIcon(TAB_LIVE, Ink, 22.dp) }
            Tile(L("Ask assistant", "AI සහායකයා"), Modifier.weight(1f).fillMaxHeight(), { go(Screen.ChatView(name)) }) { TabIcon(TAB_CHAT, Ink, 22.dp) }
        }

        if (node != null) {
            Panel(accent = nodeColor(node), onClick = { go(Screen.Live(name)) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) { SectionTitle(L("Hive device", "පෙට්ටියේ උපකරණය")) }
                    Chip(nodeWord(node), nodeColor(node))
                }
                val heard = when {
                    node.isBee() -> L("Bee sound now: " + oneDecimal(node.lowDb) + " dB.", "දැන් මී මැසි ශබ්දය: " + oneDecimal(node.lowDb) + " dB.")
                    node.checksBees() -> soundWord(node) + "."
                    else -> L("Sound level " + oneDecimal(node.lowDb) + " dB (not checked for bees).", "ශබ්ද මට්ටම " + oneDecimal(node.lowDb) + " dB (මී මැස්සෝද කියලා බැලුවේ නැහැ).")
                }
                val inside = if (node.hasTemp()) L(" Inside: ", " ඇතුළේ: ") + tempText(node.tempC) + ", " + humidityText(node.humidity) + "." else ""
                Text(
                    heard + inside + L(" Last reading " + agoText(node.ageSeconds()) + ".", " අන්තිම මිනුම " + agoText(node.ageSeconds()) + "."),
                    color = Muted
                )
            }
        }

        Panel {
            SectionTitle(L("This hive's own normal", "මේ පෙට්ටියේ සුපුරුදු ශබ්දය"))
            if (goodChecks < Store.BASELINE_CHECKS) {
                Text(
                    L(
                        "The app learns the normal sound of this hive from its first " + Store.BASELINE_CHECKS + " good checks. So far: " + goodChecks + ".",
                        "ඇප් එක මේ පෙට්ටියේ සුපුරුදු ශබ්දය ඉගෙන ගන්නේ මුල්ම හොඳ පරීක්ෂා " + Store.BASELINE_CHECKS + "න්. දැනට කරලා තියෙන්නේ: " + goodChecks + "."
                    ),
                    color = Muted
                )
                FillBar(goodChecks.toFloat() / Store.BASELINE_CHECKS)
            } else {
                Text(
                    L(
                        "Ready. Each new check is compared with the first " + Store.BASELINE_CHECKS + " good checks of this hive.",
                        "ලෑස්තියි. හැම අලුත් පරීක්ෂාවක්ම මේ පෙට්ටියේ මුල්ම හොඳ පරීක්ෂා " + Store.BASELINE_CHECKS + " එක්ක ගළපලා බලනවා."
                    ),
                    color = Muted
                )
            }
            if (distances.isNotEmpty()) {
                LineChart(distances, null, null, limit = Analyzer.CHANGE_LIMIT_DB)
                Text(
                    L(
                        "Difference from its normal in each check (dB). Above the red line (" + Analyzer.CHANGE_LIMIT_DB + " dB) counts as changed.",
                        "හැම පරීක්ෂාවකදීම සුපුරුදු ශබ්දයෙන් තිබුණ වෙනස (dB). රතු ඉරට (" + Analyzer.CHANGE_LIMIT_DB + " dB) වඩා උඩ නම් වෙනස් වෙලා කියලා ගන්නවා."
                    ),
                    style = MaterialTheme.typography.bodySmall, color = Muted
                )
            }
        }

        ListLabel(L("Past checks (" + checks.size + ")", "කලින් කළ පරීක්ෂා (" + checks.size + ")"))
        if (checks.isEmpty()) {
            EmptyState(
                ART_BEE,
                L("No checks yet", "තවම පරීක්ෂා නැහැ"),
                L("Press the black button above to record one minute of hive sound.", "පෙට්ටියේ ශබ්දය විනාඩියක් රෙකෝඩ් කරන්න උඩ තියෙන කළු බොත්තම ඔබන්න.")
            )
        }
        for (r in checks) {
            Panel(onClick = { go(Screen.ResultView(r.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(outcomeWord(r), outcomeColor(r))
                    Text(clock(r.id), style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.weight(1f))
                    Chevron()
                }
                Text(shortLine(r), fontWeight = FontWeight.Bold, color = Ink)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.size(7.dp).background(normalColor(r), RoundedCornerShape(50.dp)))
                    Text(normalLine(r), color = Muted, modifier = Modifier.weight(1f))
                }
                if (r.answers.isNotEmpty()) {
                    Text(L("Questions answered", "ප්‍රශ්නවලට පිළිතුරු දීලා තියෙනවා"), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }

        // change the name of this hive, or delete it
        var mode by remember(name) { mutableStateOf("") }            // "", rename or delete
        var newName by remember(name) { mutableStateOf(name) }
        var renameNote by remember(name) { mutableStateOf("") }
        Panel(soft = true) {
            SectionTitle(L("Rename or delete this hive", "මේ පෙට්ටියේ නම වෙනස් කරන්න, නැත්නම් පෙට්ටිය මකන්න"))
            when (mode) {
                "rename" -> {
                    Field(
                        value = newName,
                        onValueChange = { newName = it; renameNote = "" },
                        label = { Text(L("New hive name", "පෙට්ටියේ අලුත් නම")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (node != null) {
                        Text(
                            L(
                                "This hive has a device named $name. If you change the name here, change the hive name in the device too. If not, the device data will no longer show under this hive.",
                                "මේ පෙට්ටියට $name කියන උපකරණයක් තියෙනවා. මෙතන නම වෙනස් කළොත් උපකරණයේ තියෙන පෙට්ටි නමත් වෙනස් කරන්න. නැත්නම් උපකරණයේ තොරතුරු මේ පෙට්ටිය යටතේ පේන්නේ නැහැ."
                            ),
                            style = MaterialTheme.typography.bodySmall, color = Muted
                        )
                    }
                    if (renameNote.isNotEmpty()) Text(renameNote, color = WarnRed, fontWeight = FontWeight.SemiBold)
                    ButtonPair(
                        L("Save name", "නම සේව් කරන්න"), L("Cancel", "එපා"), leftFilled = true,
                        onLeft = {
                            when (store.renameHive(name, newName)) {
                                "" -> go(Screen.HiveView(store.cleanName(newName)))
                                "same" -> mode = ""
                                "used" -> renameNote = L("Another hive already has this name.", "වෙන පෙට්ටියකට මේ නම දාලා තියෙනවා.")
                                else -> renameNote = L(
                                    "Type a name. Use English letters and numbers only.",
                                    "නමක් ටයිප් කරන්න. ඉංග්‍රීසි අකුරු සහ ඉලක්කම් විතරක් පාවිච්චි කරන්න."
                                )
                            }
                        },
                        onRight = {
                            mode = ""
                            newName = name
                            renameNote = ""
                        }
                    )
                }
                "delete" -> {
                    Text(
                        L(
                            "Delete hive $name with its ${checks.size} checks, details and notes? You cannot get them back.",
                            "$name පෙට්ටියත්, ඒකේ පරීක්ෂා ${checks.size}ත්, විස්තර සහ සටහනුත් මකන්නද? මැකුවාම ආපහු ගන්න බැහැ."
                        ),
                        color = Ink, fontWeight = FontWeight.SemiBold
                    )
                    ButtonPair(
                        L("Yes, delete", "ඔව්, මකන්න"), L("No", "එපා"), leftFilled = true,
                        onLeft = {
                            store.deleteHive(name)
                            go(Screen.Home)
                        },
                        onRight = { mode = "" }
                    )
                }
                else -> ButtonPair(
                    L("Rename", "නම වෙනස් කරන්න"), L("Delete hive", "පෙට්ටිය මකන්න"),
                    onLeft = { mode = "rename" }, onRight = { mode = "delete" }
                )
            }
        }
    }
}

// ---------- details form and notes ----------

@Composable
fun DetailsScreen(store: Store, agent: Agent, hive: String, go: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()
    val first = remember { store.details(hive) }
    var district by remember { mutableStateOf(textOf(first, "district")) }
    var flow by remember { mutableStateOf(textOf(first, "flow")) }
    var hiveType by remember { mutableStateOf(textOf(first, "hiveType")) }
    var age by remember { mutableStateOf(textOf(first, "colonyAgeMonths")) }
    var opened by remember { mutableStateOf(textOf(first, "daysSinceOpened")) }
    var weather by remember { mutableStateOf(textOf(first, "weather")) }
    var saved by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var agentSaid by remember { mutableStateOf("") }
    var agentProblem by remember { mutableStateOf("") }
    var version by remember { mutableStateOf(0) }
    var askDelete by remember { mutableStateOf(0L) }                 // the time of the note the user wants to delete

    fun reload() {
        val d = store.details(hive)
        district = textOf(d, "district")
        flow = textOf(d, "flow")
        hiveType = textOf(d, "hiveType")
        age = textOf(d, "colonyAgeMonths")
        opened = textOf(d, "daysSinceOpened")
        weather = textOf(d, "weather")
        version++
    }

    Page(
        L("Details of $hive", "$hive පෙට්ටියේ විස්තර"), L("These facts help the assistant", "මේ විස්තර AI සහායකයාට උදව් වෙනවා"),
        onBack = { go(Screen.HiveView(hive)) }, art = ART_FLOWER
    ) {
        Panel {
            SectionTitle(L("About this hive", "මේ පෙට්ටිය ගැන"))
            Field(
                value = district, onValueChange = { district = it; saved = false },
                label = { Text(L("District", "දිස්ත්‍රික්කය")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Text(L("Type of hive", "පෙට්ටියේ වර්ගය"), fontWeight = FontWeight.SemiBold, color = Ink)
            Choice(
                listOf(
                    "box" to L("Box", "ලී පෙට්ටිය"), "log" to L("Log", "ගස් කොටය"),
                    "clay pot" to L("Clay pot", "මැටි මුට්ටිය"), "other" to L("Other", "වෙනත්")
                ),
                hiveType
            ) { hiveType = it; saved = false }
            Field(
                value = age, onValueChange = { age = it.filter { c -> c.isDigit() }.take(3); saved = false },
                label = { Text(L("Colony age (months)", "ජනපදයේ වයස (මාස)")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Field(
                value = opened, onValueChange = { opened = it.filter { c -> c.isDigit() }.take(3); saved = false },
                label = { Text(L("Days since the hive was opened", "පෙට්ටිය අන්තිමට ඇරලා බැලුවේ දවස් කීයකට කලින්ද?")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Text(L("Are flowers giving nectar now?", "මේ දවස්වල මල්වල පැණි තියෙනවාද?"), fontWeight = FontWeight.SemiBold, color = Ink)
            Choice(
                listOf("yes" to L("Yes", "ඔව්"), "no" to L("No", "නැහැ"), "unsure" to L("Not sure", "හරියටම දන්නේ නැහැ")),
                flow
            ) { flow = it; saved = false }
            Text(L("Weather these days", "මේ දවස්වල කාලගුණය"), fontWeight = FontWeight.SemiBold, color = Ink)
            Choice(
                listOf(
                    "sunny" to L("Sunny", "අව්වයි"), "cloudy" to L("Cloudy", "වලාකුළු"),
                    "rain" to L("Rain", "වැස්ස"), "windy" to L("Windy", "හුළඟ")
                ),
                weather
            ) { weather = it; saved = false }
            BigButton(L("Save details", "විස්තර සේව් කරන්න")) {
                store.saveDetails(
                    hive,
                    mapOf(
                        "district" to district, "flow" to flow, "hiveType" to hiveType,
                        "colonyAgeMonths" to age, "daysSinceOpened" to opened, "weather" to weather
                    ),
                    clearEmpty = true
                )
                saved = true
            }
            if (saved) Text(L("Saved on this phone.", "මේ ෆෝන් එකේ සේව් වුණා."), color = GoodGreen, fontWeight = FontWeight.SemiBold)
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Doodle(ART_LEAF, 26.dp)
                SectionTitle(L("Notes", "සටහන්"))
            }
            Text(
                L(
                    "Write what you saw, in your own words. The assistant can read your note and fill the form above for you.",
                    "ඔබ දැක්ක දේ ඔබේම වචනවලින් ලියන්න. AI සහායකයා ඒ සටහන කියවලා උඩ තියෙන විස්තර පුරවලා දෙනවා."
                ),
                color = Muted
            )
            Field(
                value = noteText, onValueChange = { noteText = it },
                label = { Text(L("Your note", "ඔබේ සටහන")) },
                enabled = !busy, minLines = 3, modifier = Modifier.fillMaxWidth()
            )
            if (busy) {
                BusyLine(L("The assistant is reading your note...", "සහායකයා ඔබේ සටහන කියවනවා..."))
            } else {
                SecondButton(
                    if (isOnline) L("Save note and fill the form", "සටහන සේව් කරලා විස්තර පුරවන්න")
                    else L("Save note", "සටහන සේව් කරන්න"),
                    enabled = noteText.trim().isNotEmpty()
                ) {
                    val note = noteText.trim()
                    store.addNote(hive, note)
                    noteText = ""
                    agentSaid = ""
                    agentProblem = ""
                    version++
                    if (isOnline) {
                        busy = true
                        scope.launch {
                            val steps = withContext(Dispatchers.IO) {
                                agent.runTask(
                                    Agent.NOTES,
                                    "The beekeeper wrote this note about hive " + hive + ": \"" + note.replace("\"", "'") + "\". " +
                                        "The note itself is already saved, so do not pass the note field. " +
                                        "Call save_hive_details for hive " + hive + " with only the facts that are clearly stated in the note. " +
                                        "Then reply in one or two short sentences that say which details you saved. " +
                                        "If the note has no such facts, do not call the tool and say that nothing was added to the form.",
                                    hive
                                )
                            }
                            agentSaid = agent.answerOf(steps) ?: ""
                            agentProblem = agent.errorOf(steps) ?: ""
                            reload()
                            busy = false
                        }
                    }
                }
            }
            if (agentSaid.isNotEmpty()) Text(L("Assistant: ", "සහායකයා: ") + agentSaid, color = Ink)
            if (agentProblem.isNotEmpty()) Text(agentProblem, color = Amber, style = MaterialTheme.typography.bodySmall)
        }

        val notes = remember(version) { store.notes(hive) }
        if (notes.isNotEmpty()) {
            ListLabel(L("Saved notes", "සේව් කළ සටහන්"))
            for (n in notes) {
                Panel(soft = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(clock(n.first), style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.weight(1f))
                        if (askDelete != n.first) SmallAction(L("Delete", "මකන්න"), Muted) { askDelete = n.first }
                    }
                    Text(n.second, color = Ink)
                    if (askDelete == n.first) {
                        ButtonPair(
                            L("Yes, delete this note", "ඔව්, මේ සටහන මකන්න"), L("No", "එපා"),
                            onLeft = {
                                store.deleteNote(hive, n.first)
                                askDelete = 0L
                                version++
                            },
                            onRight = { askDelete = 0L }
                        )
                    }
                }
            }
        }
    }
}

// ---------- more ----------

@Composable
fun MoreRow(title: String, text: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconSpot { icon() }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                Text(text, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Chevron()
        }
    }
}

@Composable
fun MoreScreen(store: Store, go: (Screen) -> Unit) {
    var exportNote by remember { mutableStateOf("") }
    val logCount = remember(exportNote) { store.assistantLogCount() }
    Page(L("More", "තවත්"), L("Plan, settings and data", "සැලැස්ම, සැකසුම් සහ තොරතුරු"), art = ART_LEAF) {
        MoreRow(
            L("Inspection plan", "අද බලන්න ඕනෑ පෙට්ටි"),
            L("Which hive to open first today, and why", "අද මුලින්ම අරින්න ඕනෑ පෙට්ටිය සහ හේතුව"),
            { go(Screen.Plan) }
        ) { Doodle(ART_COMB, 28.dp) }
        MoreRow(
            L("Settings", "සැකසුම්"),
            L("Language, assistant, cloud, alerts, place", "භාෂාව, AI සහායකයා, Firebase, අනතුරු ඇඟවීම්, පෙට්ටි තියෙන තැන"),
            { go(Screen.Settings) }
        ) { TabIcon(TAB_MORE, Ink, 22.dp) }
        MoreRow(
            L("What the app has learned", "ඇප් එක ඉගෙන ගත්ත දේ"),
            L("Learned on this phone from your answers, and how often it was right", "ඔබේ පිළිතුරුවලින් මේ ෆෝන් එකේම ඉගෙන ගත්ත දේ, සහ ඒක කොච්චර හරි ගියාද"),
            { go(Screen.LearnView) }
        ) { Doodle(ART_FLOWER, 28.dp) }
        MoreRow(
            L("About the sound model", "ශබ්දය හඳුනන හැටි ගැන"),
            L("How it was tested and what it cannot do", "ඒක පරීක්ෂා කළ හැටි සහ ඒකට කරන්න බැරි දේ"),
            { go(Screen.About) }
        ) { Doodle(ART_BEE, 28.dp) }
        MoreRow(
            L("Copy data to the Download folder", "තොරතුරු Download ෆෝල්ඩර් එකට කොපි කරන්න"),
            L("Hive checks and the assistant log ($logCount entries)", "පෙට්ටි පරීක්ෂා සහ සහායකයාගේ වාර්තා ($logCount)"),
            { exportNote = store.exportForLaptop() }
        ) { Doodle(ART_LEAF, 28.dp) }
        if (exportNote.isNotEmpty()) Text(exportNote, style = MaterialTheme.typography.bodySmall, color = Muted)
        MoreRow(
            if (uiSinhala) "English" else "සිංහල",
            L("Change the language of the app", "ඇප් එකේ භාෂාව මාරු කරන්න"),
            { setAppLanguage(store, !uiSinhala) }
        ) { TabIcon(TAB_CHAT, Ink, 22.dp) }
    }
}

@Composable
fun AboutScreen(go: (Screen) -> Unit) {
    Page(L("About the sound model", "ශබ්දය හඳුනන හැටි ගැන"), "", onBack = { go(Screen.More) }, art = ART_BEE) {
        Panel(soft = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LogoMark(60.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("CeranaWatch", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Ink)
                    Text(
                        L(
                            "The logo: one honeycomb cell with sound bars. The app listens to the hive.",
                            "ලෝගෝ එක: ශබ්ද ඉරි තියෙන මී වද කොටුවක්. ඇප් එක මී පෙට්ටියට ඇහුම්කන් දෙනවා."
                        ),
                        style = MaterialTheme.typography.bodySmall, color = Muted
                    )
                }
            }
        }
        Panel {
            SectionTitle(L("Strength estimate (phone)", "ජනපදයේ ශක්තිය ගැන දළ අනුමානය (ෆෝන් එකෙන්)"))
            Text(
                L(
                    "Trained on " + ModelData.TRAIN_HIVES + " Apis cerana hives (" + ModelData.TRAIN_CLIPS + " clean 3-second clips).",
                    "Apis cerana මී පෙට්ටි " + ModelData.TRAIN_HIVES + "ක (තත්පර 3ක පැහැදිලි කොටස් " + ModelData.TRAIN_CLIPS + ") ශබ්දවලින් ඇප් එකට ඉගැන්නුවා."
                ),
                color = Muted
            )
            Text(
                L(
                    "Tested on hives it never heard: balanced accuracy " + ModelData.EXPECTED_BA + " (guessing = 0.33). The strength estimate is experimental.",
                    "ඇප් එක කලින් අහලා නැති පෙට්ටිවල බැලුවාම: හරි යන ප්‍රමාණය " + ModelData.EXPECTED_BA + " (නිකම් අනුමාන කළොත් 0.33). ඒ නිසා ශක්තිය ගැන කියන එක තවම අත්හදා බලන දෙයක්."
                ),
                color = Muted
            )
        }
        Panel {
            SectionTitle(L("Bee sound and other sounds (phone)", "මී මැස්සන්ගේ ශබ්දය සහ වෙනත් ශබ්ද (ෆෝන් එකෙන්)"))
            Text(
                L(
                    "The phone listens in 3-second parts. Each part goes through two filters. The basic filter throws away parts with a lot of high sound (birds, horns, rain). " +
                        "The bee-sound filter uses YAMNet, Google's sound recogniser (521 sound types), with a small model trained on our 70 hive recordings, " +
                        "and throws away parts with talking, vehicles, wind, animals or machines. " +
                        "The phone keeps listening until it has 1 minute of bee sound, at most " + (Analyzer.MAX_LISTEN_SECONDS / 60) + " minutes. Everything runs on the phone, without internet.",
                    "ෆෝන් එක අහන්නේ තත්පර 3ක කොටස් විදිහට. හැම කොටසක්ම පෙරහන් දෙකකින් බලනවා. සරල පෙරහන උස් සද්ද ගොඩක් තියෙන කොටස් (කුරුල්ලෝ, හෝන්, වැස්ස) අයින් කරනවා. " +
                        "මී මැස්සන්ගේ ශබ්දය වෙන් කරන පෙරහන, Google එකේ YAMNet (ශබ්ද වර්ග 521ක් හඳුනනවා) සහ අපේ පෙට්ටි 70ක රෙකෝඩින්වලින් ඉගැන්නුව පොඩි මොඩලයක් පාවිච්චි කරලා, " +
                        "කතා, වාහන, හුළං, සතුන්ගේ හරි යන්ත්‍රවල හරි සද්ද තියෙන කොටස් අයින් කරනවා. " +
                        "මී මැස්සන්ගේ ශබ්දය විනාඩියක් එකතු වෙනකල් ෆෝන් එක අහගෙන ඉන්නවා, උපරිම විනාඩි " + (Analyzer.MAX_LISTEN_SECONDS / 60) + "ක්. මේ ඔක්කොම ෆෝන් එකේම වෙන්නේ, ඉන්ටර්නෙට් නැතුව."
                ),
                color = Muted
            )
            Text(
                L(
                    "Tested on apiaries and sounds it had never heard: it threw away 97% of other sounds heard alone, and 84% when they were as loud as the bees " +
                        "(talking 94%, engines only 56%). It keeps about half of the clean bee sound, so the phone may listen longer. " +
                        "A part is kept or thrown away as a whole; the app cannot take a voice out of the bee sound. Keep the microphone inside the closed hive. " +
                        "YAMNet: Google, Apache License 2.0.",
                    "කලින් අහලා නැති මී පාලන ස්ථාන සහ ශබ්ද එක්ක පරීක්ෂා කළාම: වෙනත් ශබ්ද තනියම ආවාම 97%ක් අයින් කළා. මී මැස්සන් තරම්ම සැරට ආවාම 84%ක් අයින් කළා " +
                        "(කතා 94%, එන්ජින් 56%ක් විතරයි). පැහැදිලි මී මැස්සන්ගේ ශබ්දයෙන් භාගයක් විතර තියාගන්නවා. ඒ නිසා ෆෝන් එක ටිකක් වැඩි වෙලාවක් අහගෙන ඉන්න පුළුවන්. " +
                        "කොටසක් සම්පූර්ණයෙන්ම තියාගන්නවා, නැත්නම් සම්පූර්ණයෙන්ම අයින් කරනවා. මී මැස්සන්ගේ ශබ්දයෙන් කටහඬක් වෙන් කරලා අයින් කරන්න බැහැ. මයික් එක වහපු පෙට්ටිය ඇතුළේම තියන්න. " +
                        "YAMNet: Google, Apache License 2.0."
                ),
                color = Muted
            )
        }
        Panel {
            SectionTitle(L("Own-normal check (phone)", "සුපුරුදු ශබ්දය එක්ක ගළපලා බැලීම (ෆෝන් එකෙන්)"))
            Text(
                L(
                    ModelData.BASELINE_NOTE,
                    "සීමාව 3.5 dB. ඇප් එකට උගන්වන්න ගත්ත රෙකෝඩින්වල, එකම රෙකෝඩින් එකේ භාග දෙක මීට වඩා වෙනස් වුණේ පෙට්ටිවලින් 5%ක විතරයි. එක පෙට්ටියක දවසින් දවස වෙන වෙනස තවම මැනලා නැහැ."
                ),
                color = Muted
            )
        }
        Panel {
            SectionTitle(L("Hive device", "පෙට්ටියේ උපකරණය"))
            Text(
                L(
                    "The device measures the loudness of the bee sound every few minutes and learns what is normal for that hive. When a reading is more than 3 standard deviations from that normal, it sounds its buzzer and sends an alert. It tells you that the sound changed, not why.",
                    "උපකරණය විනාඩි කීපයකට සැරයක් මී මැස්සන්ගේ ශබ්දයේ සැර මනිනවා. ඒ පෙට්ටියේ සුපුරුදු මට්ටම ඉගෙන ගන්නවා. මිනුමක් සුපුරුදු මට්ටමෙන් ගොඩක් වෙනස් වුණාම (පියවර 3කට වඩා) බසර් එකෙන් සද්දයක් දීලා අනතුරු ඇඟවීමක් එවනවා. ඒක කියන්නේ ශබ්දය වෙනස් වුණා කියලා විතරයි, හේතුව නෙවෙයි."
                ),
                color = Muted
            )
        }
        Panel(accent = Amber) {
            SectionTitle(L("What it cannot do", "ඒකට කරන්න බැරි දේ"))
            Text(
                L(
                    "Neither the app nor the device finds problems such as queen loss or pests from sound. Problem suggestions come from your answers to five questions. The assistant explains and plans; it does not diagnose.",
                    "ඇප් එකට හරි උපකරණයට හරි ශබ්දයෙන් රැජින නැති වීම, පළිබෝධ වගේ අවුල් හොයන්න බැහැ. එහෙම අවුල් ගැන යෝජනා එන්නේ ප්‍රශ්න පහට ඔබ දෙන පිළිතුරුවලින්. AI සහායකයා පැහැදිලි කරනවා, සැලසුම් කරනවා. ඒත් ලෙඩ හඳුනන්නේ නැහැ."
                ),
                color = Muted
            )
        }
    }
}
