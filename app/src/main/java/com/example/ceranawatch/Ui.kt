package com.example.ceranawatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// ---------- language ----------

/** True when the app is shown in Sinhala. Every screen redraws when this changes. */
var uiSinhala by mutableStateOf(false)

/** Picks the English or the Sinhala text. */
fun L(english: String, sinhala: String): String = if (uiSinhala) sinhala else english

fun setAppLanguage(store: Store, sinhala: Boolean) {
    store.setLanguage(if (sinhala) "Sinhala" else "English")
    uiSinhala = sinhala
}

// ---------- things every screen can see ----------

/** True when the phone has internet. */
var isOnline by mutableStateOf(true)

/** How many alerts the beekeeper has not seen yet. */
var openAlerts by mutableStateOf(0)

/** The latest reading of every hive device, by hive name. */
var liveNodes by mutableStateOf<Map<String, NodeReading>>(emptyMap())

fun readOnline(context: Context): Boolean = try {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork
    val caps = if (network != null) manager.getNetworkCapabilities(network) else null
    caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
} catch (_: Exception) {
    true
}

/** A network problem in simple words. */
fun friendly(e: Exception): String = netErrorText(e, uiSinhala)

/** "Today, 14:05", "Yesterday, 14:05" or "6 Oct, 14:05", in the chosen language. */
fun clock(millis: Long): String = dayAndTime(millis, uiSinhala)

/** True when the time (in milliseconds) is on today's date. */
fun isToday(millis: Long): Boolean = daysAgo(millis) == 0

/** The time of a device reading or an alert, for the screen. */
fun shownTime(seconds: Long): String = if (seconds > 1_600_000_000L) clock(seconds * 1000L) else "?"

/** The sky word of the weather service in the chosen language. */
fun skyName(english: String): String = when (english) {
    "clear" -> L("clear sky", "අහස පැහැදිලියි")
    "partly cloudy" -> L("partly cloudy", "ටිකක් වලාකුළු")
    "cloudy" -> L("cloudy", "වලාකුළු")
    "fog" -> L("fog", "මීදුම")
    "drizzle" -> L("light rain", "පොද වැස්ස")
    "rain" -> L("rain", "වැස්ස")
    "rain showers" -> L("rain showers", "ඉඳහිට වැස්ස")
    "thunderstorm" -> L("thunderstorm", "අකුණු සහිත වැස්ස")
    else -> ""
}

// ---------- colours: black, white and greys; colour is kept only for status ----------

val Ink = Color(0xFF111111)          // text, buttons, icons
val Paper = Color(0xFFFAFAF7)        // the background of every screen
val Soft = Color(0xFFF1F1EC)         // quiet fills: chips, icon circles, soft cards
val Line = Color(0xFFE4E4DE)         // thin borders
val Muted = Color(0xFF6B6B66)        // second-level text
val Faint = Color(0xFFB5B5AE)        // decorations, disabled things

val GoodGreen = Color(0xFF2E7D32)    // status: normal
val WarnRed = Color(0xFFB3261E)      // status: problem
val Amber = Color(0xFFB26A00)        // status: take care
val InfoBlue = Color(0xFF1565C0)     // status: learning / information

// ---------- sizes that are the same everywhere ----------

val ButtonHeight = 54.dp
val CardRadius = 24.dp
val ControlRadius = 18.dp

/** The space at the left and right edge of a screen; a little less on narrow phones. */
@Composable
fun screenPad(): Dp = if (LocalConfiguration.current.screenWidthDp < 360) 14.dp else 20.dp

/** True on phones that are too narrow for extras such as the small drawing in a page title. */
@Composable
fun narrowScreen(): Boolean = LocalConfiguration.current.screenWidthDp < 360

// ---------- cards ----------

/**
 * A flat rounded card.
 * accent = a thin coloured bar at the left edge (used for status: red, green, amber, blue).
 * soft = grey card without a border, for quiet information.
 */
@Composable
fun Panel(
    accent: Color? = null,
    soft: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(CardRadius)
    var modifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(if (soft) Soft else Color.White)
    if (!soft) modifier = modifier.border(1.dp, Line, shape)
    if (onClick != null) modifier = modifier.clickable(role = Role.Button) { onClick() }
    if (accent != null) {
        modifier = modifier.drawBehind {
            val inset = 18.dp.toPx()
            if (size.height > inset * 2f) {
                drawRoundRect(
                    accent,
                    topLeft = Offset(10.dp.toPx(), inset),
                    size = Size(4.dp.toPx(), size.height - inset * 2f),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
            }
        }
    }
    Column(
        modifier = modifier.padding(start = if (accent != null) 26.dp else 18.dp, top = 18.dp, end = 18.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        content()
    }
}

/** A small rounded label. A status colour is shown as a dot; the text stays black. */
@Composable
fun Chip(text: String, color: Color = Ink, background: Color = Soft) {
    Row(
        modifier = Modifier
            .background(background, RoundedCornerShape(50.dp))
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (color != Ink && color != Muted) Box(modifier = Modifier.size(7.dp).background(color, CircleShape))
        Text(
            text, color = if (color == Muted) Muted else Ink, style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold, maxLines = 1
        )
    }
}

/** A row of chips. On a narrow phone it can be moved sideways with a finger, so nothing is cut off. */
@Composable
fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

/** A round grey spot with a small picture in it, used at the start of list rows. */
@Composable
fun IconSpot(size: Dp = 48.dp, content: @Composable () -> Unit) {
    Box(modifier = Modifier.size(size).background(Soft, CircleShape), contentAlignment = Alignment.Center) {
        content()
    }
}

/**
 * Shows whether the phone has internet.
 * small = only a green dot while online (to leave room for long titles); the word "Offline" is always shown.
 */
@Composable
fun OnlineBadge(small: Boolean = false) {
    Row(
        modifier = Modifier
            .background(Color.White, RoundedCornerShape(50.dp))
            .border(1.dp, Line, RoundedCornerShape(50.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).background(if (isOnline) GoodGreen else WarnRed, CircleShape))
        if (!small || !isOnline) {
            Text(
                if (isOnline) L("Online", "ඔන්ලයින්") else L("Offline", "ඕෆ්ලයින්"),
                color = Ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1
            )
        }
    }
}

/** A round back button. */
@Composable
fun BackArrow(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(1.dp, Line, CircleShape)
            .clickable(role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.12f
            drawLine(Ink, Offset(w * 0.6f, h * 0.18f), Offset(w * 0.28f, h * 0.5f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(Ink, Offset(w * 0.28f, h * 0.5f), Offset(w * 0.6f, h * 0.82f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

/** A small arrow at the end of a row that can be tapped. */
@Composable
fun Chevron() {
    Canvas(modifier = Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        drawLine(Faint, Offset(w * 0.35f, h * 0.15f), Offset(w * 0.7f, h * 0.5f), strokeWidth = w * 0.14f, cap = StrokeCap.Round)
        drawLine(Faint, Offset(w * 0.7f, h * 0.5f), Offset(w * 0.35f, h * 0.85f), strokeWidth = w * 0.14f, cap = StrokeCap.Round)
    }
}

// ---------- the app logo ----------

/**
 * The CeranaWatch mark: one honeycomb cell with sound bars inside
 * ("listening to the hive"). It is the same drawing as the app icon.
 */
@Composable
fun LogoMark(size: Dp, cell: Color = Ink, bars: Color = Color.White) {
    Canvas(modifier = Modifier.size(size)) {
        val u = this.size.width / 58f            // the drawing is 58 units wide
        val c = this.size.width / 2f
        val r = 25f * u
        val p = Path()
        for (k in 0..5) {
            val angle = Math.toRadians(-90.0 + 60.0 * k)
            val x = c + r * Math.cos(angle).toFloat()
            val y = c + r * Math.sin(angle).toFloat()
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        drawPath(p, cell)
        drawPath(p, cell, style = Stroke(width = 6f * u, join = StrokeJoin.Round))
        val heights = floatArrayOf(10f, 20f, 30f, 20f, 10f)
        for (i in 0..4) {
            val x = c + (i - 2) * 7f * u
            val half = (heights[i] / 2f - 2.2f) * u
            drawLine(bars, Offset(x, c - half), Offset(x, c + half), strokeWidth = 4.4f * u, cap = StrokeCap.Round)
        }
    }
}

// ---------- the frame of a screen ----------

/** The top of a screen: back button, title, a small drawing and the internet badge. */
@Composable
fun Header(title: String, subtitle: String, onBack: (() -> Unit)? = null, art: Int = ART_NONE) {
    val pad = screenPad()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = pad, end = pad, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (onBack != null) BackArrow(onBack)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title, color = Ink, fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (art != ART_NONE && !narrowScreen()) Doodle(art, 34.dp)
        OnlineBadge(small = true)
    }
}

/** The part of a screen under the top: it scrolls, keeps clear of the keyboard, and is not too wide on tablets. */
@Composable
fun PageBody(content: @Composable ColumnScope.() -> Unit) {
    val pad = screenPad()
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = pad, end = pad, top = 10.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            content()
            Garland()
        }
    }
}

/** The frame used by most screens: the top, then the content. */
@Composable
fun Page(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    art: Int = ART_NONE,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(Paper)) {
        Header(title, subtitle, onBack, art)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PageBody(content)
        }
    }
}

/** The title inside a card. */
@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
}

/** The small grey title above a list. */
@Composable
fun ListLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Muted,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    )
}

// ---------- buttons: one size and one shape everywhere ----------

/** The one button of the app. filled = black with white text; not filled = white with a black line. */
@Composable
fun AppButton(text: String, filled: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(ControlRadius)
    val back = if (filled) (if (enabled) Ink else Faint) else Color.White
    val front = if (filled) Color.White else (if (enabled) Ink else Faint)
    Box(
        modifier = modifier
            .heightIn(min = ButtonHeight)
            .clip(shape)
            .background(back)
            .border(1.5.dp, if (filled) back else (if (enabled) Ink else Line), shape)
            .clickable(enabled = enabled, role = Role.Button) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, color = front, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

/** The main action of a screen. */
@Composable
fun BigButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    AppButton(text, true, enabled, modifier, onClick)
}

/** Any other action. Same size as the main button. */
@Composable
fun SecondButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    AppButton(text, false, enabled, modifier, onClick)
}

/** Two buttons side by side, always the same width and the same height. */
@Composable
fun ButtonPair(
    left: String, right: String,
    enabled: Boolean = true, leftFilled: Boolean = false,
    onLeft: () -> Unit, onRight: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AppButton(left, leftFilled, enabled, Modifier.weight(1f).fillMaxHeight(), onLeft)
        AppButton(right, false, enabled, Modifier.weight(1f).fillMaxHeight(), onRight)
    }
}

/** A row of choices of equal size; the chosen one is black. */
@Composable
fun Choice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for ((value, label) in options) {
            val on = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) Ink else Color.White)
                    .border(1.dp, if (on) Ink else Line, RoundedCornerShape(16.dp))
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, color = if (on) Color.White else Ink, fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp, lineHeight = 18.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The one text box of the app. */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    minLines: Int = 1,
    hidden: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = label,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        minLines = minLines,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(ControlRadius)
    )
}

/** A small text that can be tapped, for less important actions such as "Delete" or "Change". */
@Composable
fun SmallAction(text: String, color: Color = Ink, onClick: () -> Unit) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 6.dp, vertical = 8.dp)
    )
}

// ---------- numbers, bars and charts ----------

/** A number with a small label under it, on the black card of the home screen. */
@Composable
fun StatBox(value: String, label: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.11f), RoundedCornerShape(18.dp))
            .padding(horizontal = 6.dp, vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(value, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(
            label, color = Color(0xFFCFCFC8), fontSize = 11.sp, lineHeight = 14.sp,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

/** A flat bar that is filled up to a part of its width. */
@Composable
fun FillBar(fraction: Float, color: Color = Ink) {
    val safe = if (fraction.isNaN()) 0f else fraction.coerceIn(0.02f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(Soft, RoundedCornerShape(5.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(safe)
                .height(10.dp)
                .background(color, RoundedCornerShape(5.dp))
        )
    }
}

val VoteStrong = Ink
val VoteMedium = Color(0xFF8C8C86)
val VoteWeak = Color(0xFFD2D2CB)

/** Shows how the clean clips voted: strong (black), medium (grey), weak (light grey). */
@Composable
fun VotesBar(votes: Map<String, Int>) {
    val strong = votes["strong"] ?: 0
    val medium = votes["medium"] ?: 0
    val weak = votes["weak"] ?: 0
    val total = strong + medium + weak
    if (total == 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
    ) {
        if (strong > 0) Box(modifier = Modifier.weight(strong.toFloat()).height(14.dp).background(VoteStrong))
        if (medium > 0) Box(modifier = Modifier.weight(medium.toFloat()).height(14.dp).background(VoteMedium))
        if (weak > 0) Box(modifier = Modifier.weight(weak.toFloat()).height(14.dp).background(VoteWeak))
    }
    ChipRow {
        Legend(VoteStrong, strengthName("strong") + " " + strong)
        Legend(VoteMedium, strengthName("medium") + " " + medium)
        Legend(VoteWeak, strengthName("weak") + " " + weak)
    }
}

@Composable
fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Muted, maxLines = 1)
    }
}

/**
 * A line chart. The band between bandLow and bandHigh is shaded (the "normal" zone).
 * Points outside the band, or above the limit, are drawn in red.
 */
@Composable
fun LineChart(values: List<Double>, bandLow: Double?, bandHigh: Double?, limit: Double? = null, fromZero: Boolean = true) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
    ) {
        if (values.isEmpty()) return@Canvas
        var lo = values.minOrNull() ?: 0.0
        var hi = values.maxOrNull() ?: 1.0
        if (bandLow != null) lo = minOf(lo, bandLow)
        if (bandHigh != null) hi = maxOf(hi, bandHigh)
        if (limit != null) {
            hi = maxOf(hi, limit)
            if (fromZero) lo = minOf(lo, 0.0)
        }
        if (hi - lo < 1.0) {
            hi += 0.5
            lo -= 0.5
        }
        val pad = (hi - lo) * 0.12
        hi += pad
        lo -= pad
        val edge = 6.dp.toPx()
        val w = size.width - edge * 2f
        val h = size.height
        fun y(v: Double): Float = (h - ((v - lo) / (hi - lo)) * h).toFloat()
        fun x(i: Int): Float = edge + (if (values.size == 1) w / 2f else w * i / (values.size - 1).toFloat())

        // faint grid
        for (k in 1..3) {
            val gy = h * k / 4f
            drawLine(Line, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx())
        }
        if (bandLow != null && bandHigh != null) {
            val top = y(bandHigh)
            val bottom = y(bandLow)
            drawRect(Ink.copy(alpha = 0.07f), topLeft = Offset(0f, top), size = Size(size.width, bottom - top))
        }
        if (limit != null) {
            val ly = y(limit)
            drawLine(
                WarnRed, Offset(0f, ly), Offset(size.width, ly), strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()), 0f)
            )
        }
        if (values.size > 1) {
            val path = Path()
            path.moveTo(x(0), y(values[0]))
            for (i in 1 until values.size) path.lineTo(x(i), y(values[i]))
            drawPath(path, Ink, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        for (i in values.indices) {
            val v = values[i]
            val outside = (bandLow != null && v < bandLow) || (bandHigh != null && v > bandHigh) || (limit != null && v > limit)
            val last = i == values.size - 1
            if (outside || last || values.size <= 12) {
                val center = Offset(x(i), y(v))
                drawCircle(Color.White, radius = (if (last) 6.5.dp else 5.dp).toPx(), center = center)
                drawCircle(if (outside) WarnRed else Ink, radius = (if (last) 4.5.dp else 3.2.dp).toPx(), center = center)
            }
        }
    }
}

val BeeYes = Color(0xFF2E7D32)     // a reading with bee sound
val BeeOther = Color(0xFFE0A030)   // a reading with other sound
val BeeSilent = Color(0xFFD2D2CB)  // a silent reading or an old reading that was not checked

/** One small block per reading, oldest on the left: green = bee sound, amber = other sound, light grey = silent. */
@Composable
fun BeeStrip(readings: List<NodeReading>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(6.dp))
    ) {
        if (readings.isEmpty()) return@Canvas
        val gap = if (readings.size > 40) 1.dp.toPx() else 2.dp.toPx()
        val w = (size.width - gap * (readings.size - 1)) / readings.size
        for (i in readings.indices) {
            val r = readings[i]
            val c = when {
                r.isBee() -> BeeYes
                r.checksBees() && !r.isSilent() -> BeeOther
                else -> BeeSilent
            }
            drawRect(c, topLeft = Offset(i * (w + gap), 0f), size = Size(w.coerceAtLeast(1f), size.height))
        }
    }
    ChipRow {
        Legend(BeeYes, L("Bee sound", "මී මැසි ශබ්දය"))
        Legend(BeeOther, L("Other sound", "වෙනත් ශබ්දය"))
        Legend(BeeSilent, L("Silent", "නිහඬයි"))
    }
}

/** Shows where a z-score lies between -6 and +6; the darker middle is the normal zone. */
@Composable
fun ZBar(z: Double) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
    ) {
        val w = size.width
        val h = size.height
        val barTop = h * 0.32f
        val barHeight = h * 0.36f
        val radius = CornerRadius(barHeight / 2f, barHeight / 2f)
        drawRoundRect(Soft, topLeft = Offset(0f, barTop), size = Size(w, barHeight), cornerRadius = radius)
        drawRect(Ink.copy(alpha = 0.16f), topLeft = Offset(w * 0.25f, barTop), size = Size(w * 0.5f, barHeight))
        val safeZ = if (z.isNaN()) 0.0 else z.coerceIn(-6.0, 6.0)
        val pos = (((safeZ + 6.0) / 12.0) * w).toFloat().coerceIn(h * 0.5f, w - h * 0.5f)
        val unusual = Math.abs(safeZ) > NodeReading.Z_LIMIT
        drawCircle(Color.White, radius = h * 0.5f, center = Offset(pos, h / 2f))
        drawCircle(if (unusual) WarnRed else Ink, radius = h * 0.36f, center = Offset(pos, h / 2f))
    }
}

/** A ring that fills as the recording goes on. */
@Composable
fun Ring(fraction: Float, color: Color = Ink) {
    Canvas(modifier = Modifier.size(184.dp)) {
        val stroke = 12.dp.toPx()
        val inset = stroke / 2f
        val area = Size(size.width - stroke, size.height - stroke)
        drawArc(Soft, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = Offset(inset, inset), size = area, style = Stroke(width = stroke))
        drawArc(
            color, startAngle = -90f, sweepAngle = 360f * fraction.coerceIn(0f, 1f), useCenter = false,
            topLeft = Offset(inset, inset), size = area, style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

// ---------- bottom bar ----------

const val TAB_HIVES = 0
const val TAB_LIVE = 1
const val TAB_ALERTS = 2
const val TAB_CHAT = 3
const val TAB_MORE = 4

/** The small pictures of the bottom bar, drawn by the app. */
@Composable
fun TabIcon(tab: Int, color: Color, size: Dp = 24.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (tab) {
            TAB_HIVES -> {
                // a honeycomb cell
                val p = Path()
                p.moveTo(w * 0.5f, h * 0.08f)
                p.lineTo(w * 0.87f, h * 0.29f)
                p.lineTo(w * 0.87f, h * 0.71f)
                p.lineTo(w * 0.5f, h * 0.92f)
                p.lineTo(w * 0.13f, h * 0.71f)
                p.lineTo(w * 0.13f, h * 0.29f)
                p.close()
                drawPath(p, color, style = s)
                drawCircle(color, radius = w * 0.1f, center = Offset(w * 0.5f, h * 0.5f))
            }
            TAB_LIVE -> {
                // a pulse line
                val p = Path()
                p.moveTo(w * 0.05f, h * 0.55f)
                p.lineTo(w * 0.3f, h * 0.55f)
                p.lineTo(w * 0.42f, h * 0.2f)
                p.lineTo(w * 0.58f, h * 0.85f)
                p.lineTo(w * 0.7f, h * 0.55f)
                p.lineTo(w * 0.95f, h * 0.55f)
                drawPath(p, color, style = s)
            }
            TAB_ALERTS -> {
                // a bell
                val p = Path()
                p.moveTo(w * 0.2f, h * 0.72f)
                p.lineTo(w * 0.28f, h * 0.6f)
                p.lineTo(w * 0.28f, h * 0.42f)
                p.cubicTo(w * 0.28f, h * 0.08f, w * 0.72f, h * 0.08f, w * 0.72f, h * 0.42f)
                p.lineTo(w * 0.72f, h * 0.6f)
                p.lineTo(w * 0.8f, h * 0.72f)
                p.close()
                drawPath(p, color, style = s)
                drawLine(color, Offset(w * 0.43f, h * 0.88f), Offset(w * 0.57f, h * 0.88f), strokeWidth = w * 0.085f, cap = StrokeCap.Round)
            }
            TAB_CHAT -> {
                // a speech bubble
                val p = Path()
                p.moveTo(w * 0.2f, h * 0.14f)
                p.lineTo(w * 0.8f, h * 0.14f)
                p.cubicTo(w * 0.9f, h * 0.14f, w * 0.92f, h * 0.2f, w * 0.92f, h * 0.28f)
                p.lineTo(w * 0.92f, h * 0.56f)
                p.cubicTo(w * 0.92f, h * 0.66f, w * 0.88f, h * 0.7f, w * 0.8f, h * 0.7f)
                p.lineTo(w * 0.45f, h * 0.7f)
                p.lineTo(w * 0.25f, h * 0.9f)
                p.lineTo(w * 0.27f, h * 0.7f)
                p.lineTo(w * 0.2f, h * 0.7f)
                p.cubicTo(w * 0.1f, h * 0.7f, w * 0.08f, h * 0.64f, w * 0.08f, h * 0.56f)
                p.lineTo(w * 0.08f, h * 0.28f)
                p.cubicTo(w * 0.08f, h * 0.18f, w * 0.12f, h * 0.14f, w * 0.2f, h * 0.14f)
                p.close()
                drawPath(p, color, style = s)
            }
            else -> {
                drawCircle(color, radius = w * 0.09f, center = Offset(w * 0.2f, h * 0.5f))
                drawCircle(color, radius = w * 0.09f, center = Offset(w * 0.5f, h * 0.5f))
                drawCircle(color, radius = w * 0.09f, center = Offset(w * 0.8f, h * 0.5f))
            }
        }
    }
}

/** The floating black bar at the bottom with the five main places of the app. */
@Composable
fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(
        L("Hives", "පෙට්ටි"), L("Live", "උපකරණය"), L("Alerts", "ඇඟවීම්"), L("Assistant", "සහායක"), L("More", "තවත්")
    )
    val pad = if (narrowScreen()) 8.dp else 14.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            .navigationBarsPadding()
            .padding(start = pad, end = pad, top = 6.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .background(Ink, RoundedCornerShape(30.dp))
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (tab in 0..4) {
                val on = tab == selected
                val color = if (on) Ink else Color(0xFFB9B9B2)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (on) Color.White else Color.Transparent)
                        .clickable(role = Role.Tab) { onSelect(tab) }
                        .padding(vertical = 8.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Box(modifier = Modifier.padding(horizontal = 8.dp)) { TabIcon(tab, color, 22.dp) }
                        if (tab == TAB_ALERTS && openAlerts > 0) {
                            Box(
                                modifier = Modifier
                                    .size(17.dp)
                                    .background(WarnRed, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (openAlerts > 9) "9+" else openAlerts.toString(),
                                    color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Text(
                        labels[tab], color = color, fontSize = 11.sp, lineHeight = 14.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ---------- words ----------

fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

/** "strong" becomes "Strong". */
fun capital(text: String): String = text.replaceFirstChar { it.uppercase(Locale.US) }

/** The strength word in the chosen language. */
fun strengthName(strength: String?): String = when (strength) {
    "strong" -> L("Strong", "ශක්තිමත්")
    "medium" -> L("Medium", "මධ්‍යම")
    "weak" -> L("Weak", "දුර්වල")
    else -> ""
}

/** The answer word in the chosen language. */
fun answerName(answer: String): String = when (answer) {
    Advice.YES -> L("Yes", "ඔව්")
    Advice.NO -> L("No", "නැහැ")
    else -> L("Not sure", "හරියටම දන්නේ නැහැ")
}

/** One short line that describes a check, for lists. */
fun shortLine(r: CheckRecord): String = when (r.outcome) {
    Analyzer.READING -> L(
        strengthName(r.strength) + ": " + r.agree + " of " + r.cleanClips + " clips agreed",
        strengthName(r.strength) + ": කොටස් " + r.cleanClips + "න් " + r.agree + "ක් කිව්වේ එකම දේ"
    )
    Analyzer.UNCLEAR -> L("Unclear: the clips did not agree", "පැහැදිලි නැහැ: කොටස් එකිනෙකට ගැළපුණේ නැහැ")
    Analyzer.QUIET -> L("Very quiet recording", "ශබ්දය ගොඩක් අඩු රෙකෝඩින් එකක්")
    else -> L(
        "Too noisy: " + r.cleanClips + " of " + r.totalClips + " clips were clean",
        "වෙනත් ශබ්ද වැඩියි: කොටස් " + r.totalClips + "න් පැහැදිලි " + r.cleanClips + "යි"
    )
}

/** One or two words for the outcome of a check, for a chip. */
fun outcomeWord(r: CheckRecord): String = when (r.outcome) {
    Analyzer.READING -> strengthName(r.strength)
    Analyzer.UNCLEAR -> L("Unclear", "පැහැදිලි නැහැ")
    Analyzer.QUIET -> L("Very quiet", "ශබ්දය ගොඩක් අඩුයි")
    else -> L("Not enough bee sound", "මී මැසි ශබ්දය මදි")
}

/** The status colour of a check (shown as a small dot). */
fun outcomeColor(r: CheckRecord): Color = when (r.outcome) {
    Analyzer.READING -> when (r.strength) {
        "strong" -> GoodGreen
        "weak" -> WarnRed
        else -> Amber
    }
    Analyzer.UNCLEAR -> Faint
    else -> WarnRed
}

fun normalLine(r: CheckRecord): String = when (r.normalStatus) {
    Store.LEARNING -> L(
        "Learning its normal sound (" + r.normalCount + " of " + Store.BASELINE_CHECKS + ")",
        "සුපුරුදු ශබ්දය ඉගෙන ගන්නවා (" + Store.BASELINE_CHECKS + "න් " + r.normalCount + ")"
    )
    Store.NORMAL -> L("Sounds like its own normal", "සුපුරුදු ශබ්දය වගේමයි")
    Store.CHANGED -> L("Changed from its own normal", "සුපුරුදු ශබ්දයට වඩා වෙනස්")
    else -> L("Not compared with its own normal", "සුපුරුදු ශබ්දය එක්ක ගළපලා බලලා නැහැ")
}

fun normalColor(r: CheckRecord): Color = when (r.normalStatus) {
    Store.CHANGED -> WarnRed
    Store.NORMAL -> GoodGreen
    else -> Muted
}

/** The status of a hive device in the chosen language. */
fun nodeWord(r: NodeReading): String = when {
    !r.online() -> L("Device offline", "උපකරණය ඕෆ්ලයින්")
    r.status() == NodeReading.NO_CHECK -> L("Update device program", "උපකරණයේ ප්‍රෝග්‍රෑම් එක අලුත් කරන්න")
    r.status() == NodeReading.NO_BEES && r.isSilent() -> L("Silent: no bees", "නිහඬයි: මී මැස්සෝ නැහැ")
    r.status() == NodeReading.NO_BEES -> L("No bee sound", "මී මැසි ශබ්දය නැහැ")
    r.status() == NodeReading.LEARNING -> L("Device learning", "උපකරණය ඉගෙන ගන්නවා")
    r.status() == NodeReading.UNUSUAL -> L("Unusual bee sound", "අමුතු මී මැසි ශබ්දයක්")
    else -> L("Bees: normal", "මී මැස්සෝ: සුපුරුදුයි")
}

/** The status colour of a hive device (shown as a small dot or a thin bar). */
fun nodeColor(r: NodeReading): Color = when {
    !r.online() -> Faint
    r.status() == NodeReading.NO_CHECK -> Muted
    r.status() == NodeReading.NO_BEES -> Amber
    r.status() == NodeReading.LEARNING -> InfoBlue
    r.status() == NodeReading.UNUSUAL -> WarnRed
    else -> GoodGreen
}

/** What the device heard in one reading, in the chosen language. */
fun soundWord(r: NodeReading): String = when {
    !r.checksBees() -> L("Not checked (old device program)", "පරීක්ෂා කළේ නැහැ (පරණ ප්‍රෝග්‍රෑම් එක)")
    r.isBee() -> L("Bee sound heard", "මී මැසි ශබ්දය ඇහුණා")
    r.isSilent() -> L("Silent", "නිහඬයි")
    r.isFar() -> L("Only far or quiet sound", "ඈත හරි හෙමින් හරි ශබ්දයක් විතරයි")
    else -> L("Other sound, not bees", "වෙනත් ශබ්දයක්, මී මැස්සෝ නොවේ")
}

/** "21.5 °C" or "–" when the sensor did not answer. */
fun tempText(c: Double?): String = if (c == null) "–" else oneDecimal(c) + " °C"

/** "68 %" or "–" when the sensor did not answer. */
fun humidityText(h: Double?): String = if (h == null) "–" else Math.round(h).toString() + " %"

/** "5 minutes ago" in the chosen language. */
fun agoText(seconds: Long): String = when {
    seconds < 0 -> L("time unknown", "වෙලාව දන්නේ නැහැ")
    seconds < 90 -> L("just now", "මේ දැන්")
    seconds < 120 -> L("1 minute ago", "විනාඩියකට කලින්")
    seconds < 5400 -> L("" + (seconds / 60) + " minutes ago", "විනාඩි " + (seconds / 60) + "කට කලින්")
    seconds < 7200 -> L("1 hour ago", "පැයකට කලින්")
    seconds < 172800 -> L("" + (seconds / 3600) + " hours ago", "පැය " + (seconds / 3600) + "කට කලින්")
    else -> L("" + (seconds / 86400) + " days ago", "දවස් " + (seconds / 86400) + "කට කලින්")
}

// ---------- hive health status (red, amber, green, grey) ----------

/** The colour of a health status. */
fun healthColor(level: String): Color = when (level) {
    HiveHealth.PROBLEM -> WarnRed
    HiveHealth.LOOK -> Amber
    HiveHealth.HEALTHY -> GoodGreen
    else -> Faint
}

/** The title of a health status in the chosen language. */
fun healthTitle(level: String): String = when (level) {
    HiveHealth.PROBLEM -> L("Problem found", "ප්‍රශ්නයක් හමු වුණා")
    HiveHealth.LOOK -> L("Needs a look", "පෙට්ටිය බලන්න ඕනෑ")
    HiveHealth.HEALTHY -> L("Healthy at last inspection", "අන්තිම පරීක්ෂාවේදී නිරෝගීයි")
    else -> L("Not enough information yet", "තවම ඇති තරම් තොරතුරු නැහැ")
}

/** "Inspected today", "Inspected yesterday" or "Inspected 5 days ago"; empty when there was no inspection. */
fun inspectedText(at: Long?): String {
    if (at == null) return ""
    val days = daysAgo(at)
    return when {
        days <= 0 -> L("Inspected today", "අද පරීක්ෂා කළා")
        days == 1 -> L("Inspected yesterday", "ඊයේ පරීක්ෂා කළා")
        else -> L("Inspected $days days ago", "දවස් ${days}කට කලින් පරීක්ෂා කළා")
    }
}

/** The health status of one hive, worked out from what is kept on the phone (works offline). */
fun hiveHealth(store: Store, hive: String): HiveHealth.Status =
    HiveHealth.of(store.checks(hive), AlertItem.listFromSaved(store.savedText("alerts")), hive, uiSinhala)

/** The large health card, shown at the top of the hive screen and of the newest result. */
@Composable
fun HealthCard(s: HiveHealth.Status, onClick: (() -> Unit)? = null) {
    Panel(accent = healthColor(s.level), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(18.dp).background(healthColor(s.level), CircleShape))
            Column(modifier = Modifier.weight(1f)) {
                Text(L("Hive health", "පෙට්ටියේ සෞඛ්‍යය"), color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(healthTitle(s.level), fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            }
        }
        for (reason in s.reasons) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.padding(top = 8.dp).size(6.dp).background(Ink, CircleShape))
                Text(reason, color = Ink, modifier = Modifier.weight(1f))
            }
        }
        val inspected = inspectedText(s.inspectedAt)
        if (inspected.isNotEmpty()) Text(inspected, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(
            when (s.level) {
                HiveHealth.HEALTHY -> L(
                    "Based on your own inspection. Between inspections the sound checks and the hive device keep watch.",
                    "මේක ඔබේම පරීක්ෂාව අනුව. පරීක්ෂා අතර කාලයේදී ශබ්ද පරීක්ෂා සහ පෙට්ටියේ උපකරණය බලාගෙන ඉන්නවා."
                )
                HiveHealth.PROBLEM -> L(
                    "From your answers to the five questions. The advice for each problem is on the result of that check.",
                    "මේක ප්‍රශ්න පහට ඔබ දුන්න පිළිතුරු අනුව. හැම ප්‍රශ්නයකටම උපදෙස් ඒ පරීක්ෂාවේ ප්‍රතිඵලයේ තියෙනවා."
                )
                HiveHealth.LOOK -> L(
                    "A change is a reason to inspect the hive, not proof of a problem. After you look, answer the five questions.",
                    "වෙනසක් කියන්නේ පෙට්ටිය බලන්න හේතුවක් විතරයි, ප්‍රශ්නයක් තියෙනවා කියලා සාක්ෂියක් නෙවෙයි. බැලුවට පස්සේ ප්‍රශ්න පහට පිළිතුරු දෙන්න."
                )
                else -> L(
                    "\"Healthy\" is shown only after an inspection where all five answers are good and the sound is like its own normal.",
                    "\"නිරෝගීයි\" කියලා පෙන්නන්නේ ප්‍රශ්න පහටම හොඳ පිළිතුරු තියෙන, ශබ්දය සුපුරුදු ශබ්දය වගේ තියෙන පරීක්ෂාවකට පස්සේ විතරයි."
                )
            },
            style = MaterialTheme.typography.bodySmall, color = Muted
        )
    }
}

/** One short health line for the list of hives on the home screen. */
@Composable
fun HealthLine(s: HiveHealth.Status) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(10.dp).background(healthColor(s.level), CircleShape))
        Text(
            healthTitle(s.level) + (if (s.level == HiveHealth.PROBLEM || s.level == HiveHealth.LOOK) ": " + (s.reasons.firstOrNull() ?: "") else ""),
            color = Ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
    }
}
