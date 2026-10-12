package com.example.ceranawatch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The small line drawings of the app: bee, flower, bee hive, honeycomb and leaf.
 * They are drawn by the app itself (no picture files), so they stay sharp on every phone.
 * The drawing functions at the bottom of this file were generated from one design file;
 * each drawing lives in a box of 100 x 100 units.
 */

const val ART_NONE = 0
const val ART_BEE = 1
const val ART_FLOWER = 2
const val ART_BLOOM = 3
const val ART_HIVE = 4
const val ART_COMB = 5
const val ART_LEAF = 6

private fun DrawScope.lineStroke(width: Float) = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.dashStroke(width: Float) = Stroke(
    width = width, cap = StrokeCap.Round,
    pathEffect = PathEffect.dashPathEffect(floatArrayOf(width * 0.6f, width * 2.8f), 0f)
)

/** One small drawing. */
@Composable
fun Doodle(kind: Int, size: Dp, color: Color = Ink) {
    Canvas(modifier = Modifier.size(size)) {
        val u = this.size.width / 100f
        val w = maxOf(1.3.dp.toPx(), this.size.width * 0.036f)
        val line = lineStroke(w)
        val dashed = dashStroke(w)
        when (kind) {
            ART_BEE -> drawBee(u, color, line, dashed)
            ART_FLOWER -> drawFlower(u, color, line, dashed)
            ART_BLOOM -> drawBloom(u, color, line, dashed)
            ART_HIVE -> drawHive(u, color, line, dashed)
            ART_COMB -> drawComb(u, color, line, dashed)
            ART_LEAF -> drawLeaf(u, color, line, dashed)
        }
    }
}

/** The picture on the black card of the home screen: a hive, flowers and two bees. */
@Composable
fun HeroArt(width: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.width(width).height(width * (100f / 170f))) {
        val u = this.size.width / 170f
        val w = 1.5.dp.toPx()
        drawHero(u, color, lineStroke(w), dashStroke(w))
    }
}

/** A thin strip with flowers and a bee, shown at the bottom of every page. */
@Composable
fun Garland() {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.width(198.dp).height(36.dp)) {
            val u = this.size.width / 220f
            val w = 1.2.dp.toPx()
            drawGarland(u, Faint, lineStroke(w), dashStroke(w))
        }
    }
}

/** Shown where a list is still empty: a drawing, a title and one line of help. */
@Composable
fun EmptyState(kind: Int, title: String, text: String) {
    Panel(soft = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Doodle(kind, 56.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        }
    }
}

// ---------- generated drawing code (do not edit by hand) ----------

private fun DrawScope.drawBee(u: Float, color: Color, line: Stroke, dashed: Stroke) {
    rotate(-8f, Offset(50f * u, 60f * u)) { drawOval(color, topLeft = Offset(27f * u, 44.5f * u), size = Size(46f * u, 31f * u), style = line) }
    val p1 = Path()
    p1.moveTo(43f * u, 45.8f * u)
    p1.cubicTo(46.5f * u, 53f * u, 46.5f * u, 66f * u, 42f * u, 75.2f * u)
    drawPath(p1, color, style = line)
    val p2 = Path()
    p2.moveTo(56f * u, 44.3f * u)
    p2.cubicTo(59.5f * u, 52f * u, 59.5f * u, 65f * u, 55f * u, 74.6f * u)
    drawPath(p2, color, style = line)
    drawCircle(color, radius = 9f * u, center = Offset(78f * u, 54f * u), style = line)
    drawCircle(color, radius = 1.6f * u, center = Offset(81f * u, 52.5f * u))
    val p3 = Path()
    p3.moveTo(82f * u, 46f * u)
    p3.cubicTo(84f * u, 40f * u, 88f * u, 37f * u, 93f * u, 37f * u)
    drawPath(p3, color, style = line)
    val p4 = Path()
    p4.moveTo(76.5f * u, 45.2f * u)
    p4.cubicTo(76f * u, 39f * u, 78f * u, 33.5f * u, 83f * u, 31f * u)
    drawPath(p4, color, style = line)
    rotate(-24f, Offset(44f * u, 33f * u)) { drawOval(color, topLeft = Offset(35f * u, 16.5f * u), size = Size(18f * u, 33f * u), style = line) }
    rotate(14f, Offset(59f * u, 31f * u)) { drawOval(color, topLeft = Offset(51f * u, 15.5f * u), size = Size(16f * u, 31f * u), style = line) }
    val p5 = Path()
    p5.moveTo(27.5f * u, 62.5f * u)
    p5.lineTo(20f * u, 65.5f * u)
    drawPath(p5, color, style = line)
}

private fun DrawScope.drawFlower(u: Float, color: Color, line: Stroke, dashed: Stroke) {
    rotate(0f, Offset(50f * u, 17f * u)) { drawOval(color, topLeft = Offset(44f * u, 7.6f * u), size = Size(12f * u, 18.8f * u), style = line) }
    rotate(60f, Offset(64.72f * u, 25.5f * u)) { drawOval(color, topLeft = Offset(58.72f * u, 16.1f * u), size = Size(12f * u, 18.8f * u), style = line) }
    rotate(120f, Offset(64.72f * u, 42.5f * u)) { drawOval(color, topLeft = Offset(58.72f * u, 33.1f * u), size = Size(12f * u, 18.8f * u), style = line) }
    rotate(180f, Offset(50f * u, 51f * u)) { drawOval(color, topLeft = Offset(44f * u, 41.6f * u), size = Size(12f * u, 18.8f * u), style = line) }
    rotate(240f, Offset(35.28f * u, 42.5f * u)) { drawOval(color, topLeft = Offset(29.28f * u, 33.1f * u), size = Size(12f * u, 18.8f * u), style = line) }
    rotate(300f, Offset(35.28f * u, 25.5f * u)) { drawOval(color, topLeft = Offset(29.28f * u, 16.1f * u), size = Size(12f * u, 18.8f * u), style = line) }
    drawCircle(color, radius = 6.2f * u, center = Offset(50f * u, 34f * u), style = line)
    val p1 = Path()
    p1.moveTo(50f * u, 53f * u)
    p1.cubicTo(50.5f * u, 67f * u, 47.5f * u, 80f * u, 49.5f * u, 97f * u)
    drawPath(p1, color, style = line)
    val p2 = Path()
    p2.moveTo(49.3f * u, 82f * u)
    p2.cubicTo(56f * u, 70f * u, 68f * u, 67f * u, 75f * u, 69f * u)
    p2.cubicTo(71f * u, 80f * u, 60f * u, 86f * u, 49.3f * u, 82f * u)
    p2.close()
    drawPath(p2, color, style = line)
    val p3 = Path()
    p3.moveTo(49.2f * u, 71f * u)
    p3.cubicTo(43f * u, 62.5f * u, 34f * u, 62f * u, 29f * u, 64.5f * u)
    p3.cubicTo(33f * u, 73f * u, 42f * u, 75.5f * u, 49.2f * u, 71f * u)
    p3.close()
    drawPath(p3, color, style = line)
}

private fun DrawScope.drawBloom(u: Float, color: Color, line: Stroke, dashed: Stroke) {
    rotate(0f, Offset(50f * u, 25.5f * u)) { drawOval(color, topLeft = Offset(41f * u, 12f * u), size = Size(18f * u, 27f * u), style = line) }
    rotate(60f, Offset(71.22f * u, 37.75f * u)) { drawOval(color, topLeft = Offset(62.22f * u, 24.25f * u), size = Size(18f * u, 27f * u), style = line) }
    rotate(120f, Offset(71.22f * u, 62.25f * u)) { drawOval(color, topLeft = Offset(62.22f * u, 48.75f * u), size = Size(18f * u, 27f * u), style = line) }
    rotate(180f, Offset(50f * u, 74.5f * u)) { drawOval(color, topLeft = Offset(41f * u, 61f * u), size = Size(18f * u, 27f * u), style = line) }
    rotate(240f, Offset(28.78f * u, 62.25f * u)) { drawOval(color, topLeft = Offset(19.78f * u, 48.75f * u), size = Size(18f * u, 27f * u), style = line) }
    rotate(300f, Offset(28.78f * u, 37.75f * u)) { drawOval(color, topLeft = Offset(19.78f * u, 24.25f * u), size = Size(18f * u, 27f * u), style = line) }
    drawCircle(color, radius = 8.5f * u, center = Offset(50f * u, 50f * u), style = line)
    drawCircle(color, radius = 2.2f * u, center = Offset(50f * u, 50f * u))
}

private fun DrawScope.drawHive(u: Float, color: Color, line: Stroke, dashed: Stroke) {
    val p1 = Path()
    p1.moveTo(17f * u, 86f * u)
    p1.cubicTo(17f * u, 62f * u, 23f * u, 27f * u, 50f * u, 19f * u)
    p1.cubicTo(77f * u, 27f * u, 83f * u, 62f * u, 83f * u, 86f * u)
    drawPath(p1, color, style = line)
    val p2 = Path()
    p2.moveTo(9f * u, 86f * u)
    p2.lineTo(91f * u, 86f * u)
    drawPath(p2, color, style = line)
    val p3 = Path()
    p3.moveTo(19.5f * u, 70f * u)
    p3.cubicTo(34f * u, 74.5f * u, 66f * u, 74.5f * u, 80.5f * u, 70f * u)
    drawPath(p3, color, style = line)
    val p4 = Path()
    p4.moveTo(23.5f * u, 54f * u)
    p4.cubicTo(37f * u, 58.5f * u, 63f * u, 58.5f * u, 76.5f * u, 54f * u)
    drawPath(p4, color, style = line)
    val p5 = Path()
    p5.moveTo(30.5f * u, 38f * u)
    p5.cubicTo(41f * u, 42f * u, 59f * u, 42f * u, 69.5f * u, 38f * u)
    drawPath(p5, color, style = line)
    val p6 = Path()
    p6.moveTo(42.5f * u, 86f * u)
    p6.lineTo(42.5f * u, 79f * u)
    p6.cubicTo(42.5f * u, 71.5f * u, 57.5f * u, 71.5f * u, 57.5f * u, 79f * u)
    p6.lineTo(57.5f * u, 86f * u)
    drawPath(p6, color, style = line)
    drawCircle(color, radius = 3.4f * u, center = Offset(50f * u, 14.5f * u), style = line)
}

private fun DrawScope.drawComb(u: Float, color: Color, line: Stroke, dashed: Stroke) {
    val p1 = Path()
    p1.moveTo(36.5f * u, 22f * u)
    p1.lineTo(49.49f * u, 29.5f * u)
    p1.lineTo(49.49f * u, 44.5f * u)
    p1.lineTo(36.5f * u, 52f * u)
    p1.lineTo(23.51f * u, 44.5f * u)
    p1.lineTo(23.51f * u, 29.5f * u)
    p1.close()
    drawPath(p1, color, style = line)
    val p2 = Path()
    p2.moveTo(62.5f * u, 22f * u)
    p2.lineTo(75.49f * u, 29.5f * u)
    p2.lineTo(75.49f * u, 44.5f * u)
    p2.lineTo(62.5f * u, 52f * u)
    p2.lineTo(49.51f * u, 44.5f * u)
    p2.lineTo(49.51f * u, 29.5f * u)
    p2.close()
    drawPath(p2, color, style = line)
    val p3 = Path()
    p3.moveTo(49.5f * u, 44.5f * u)
    p3.lineTo(62.49f * u, 52f * u)
    p3.lineTo(62.49f * u, 67f * u)
    p3.lineTo(49.5f * u, 74.5f * u)
    p3.lineTo(36.51f * u, 67f * u)
    p3.lineTo(36.51f * u, 52f * u)
    p3.close()
    drawPath(p3, color, style = line)
    val p4 = Path()
    p4.moveTo(23.5f * u, 44.5f * u)
    p4.lineTo(36.49f * u, 52f * u)
    p4.lineTo(36.49f * u, 67f * u)
    p4.lineTo(23.5f * u, 74.5f * u)
    p4.lineTo(10.51f * u, 67f * u)
    p4.lineTo(10.51f * u, 52f * u)
    p4.close()
    drawPath(p4, color, style = line)
    val p5 = Path()
    p5.moveTo(75.5f * u, 44.5f * u)
    p5.lineTo(88.49f * u, 52f * u)
    p5.lineTo(88.49f * u, 67f * u)
    p5.lineTo(75.5f * u, 74.5f * u)
    p5.lineTo(62.51f * u, 67f * u)
    p5.lineTo(62.51f * u, 52f * u)
    p5.close()
    drawPath(p5, color, style = line)
    drawCircle(color, radius = 3.2f * u, center = Offset(49.5f * u, 59.5f * u))
}

private fun DrawScope.drawLeaf(u: Float, color: Color, line: Stroke, dashed: Stroke) {
    val p1 = Path()
    p1.moveTo(14f * u, 86f * u)
    p1.cubicTo(30f * u, 74f * u, 52f * u, 52f * u, 86f * u, 16f * u)
    drawPath(p1, color, style = line)
    val p2 = Path()
    p2.moveTo(40f * u, 62.5f * u)
    p2.cubicTo(37f * u, 47f * u, 43f * u, 36f * u, 52f * u, 30f * u)
    p2.cubicTo(56f * u, 42f * u, 52f * u, 54f * u, 40f * u, 62.5f * u)
    p2.close()
    drawPath(p2, color, style = line)
    val p3 = Path()
    p3.moveTo(46f * u, 56.5f * u)
    p3.cubicTo(60f * u, 58f * u, 71f * u, 53f * u, 77f * u, 45f * u)
    p3.cubicTo(65f * u, 41f * u, 53f * u, 46f * u, 46f * u, 56.5f * u)
    p3.close()
    drawPath(p3, color, style = line)
    val p4 = Path()
    p4.moveTo(24f * u, 78f * u)
    p4.cubicTo(21f * u, 66f * u, 25f * u, 57f * u, 32f * u, 52f * u)
    p4.cubicTo(36f * u, 61f * u, 33f * u, 71f * u, 24f * u, 78f * u)
    p4.close()
    drawPath(p4, color, style = line)
    val p5 = Path()
    p5.moveTo(68f * u, 34f * u)
    p5.cubicTo(66f * u, 25f * u, 69f * u, 17f * u, 76f * u, 13f * u)
    p5.cubicTo(80f * u, 21f * u, 77f * u, 29f * u, 68f * u, 34f * u)
    p5.close()
    drawPath(p5, color, style = line)
}

private fun DrawScope.drawHero(u: Float, color: Color, line: Stroke, dashed: Stroke) {
    val p1 = Path()
    p1.moveTo(23.52f * u, 92.16f * u)
    p1.cubicTo(23.52f * u, 78.72f * u, 26.88f * u, 59.12f * u, 42f * u, 54.64f * u)
    p1.cubicTo(57.12f * u, 59.12f * u, 60.48f * u, 78.72f * u, 60.48f * u, 92.16f * u)
    drawPath(p1, color, style = line)
    val p2 = Path()
    p2.moveTo(19.04f * u, 92.16f * u)
    p2.lineTo(64.96f * u, 92.16f * u)
    drawPath(p2, color, style = line)
    val p3 = Path()
    p3.moveTo(24.92f * u, 83.2f * u)
    p3.cubicTo(33.04f * u, 85.72f * u, 50.96f * u, 85.72f * u, 59.08f * u, 83.2f * u)
    drawPath(p3, color, style = line)
    val p4 = Path()
    p4.moveTo(27.16f * u, 74.24f * u)
    p4.cubicTo(34.72f * u, 76.76f * u, 49.28f * u, 76.76f * u, 56.84f * u, 74.24f * u)
    drawPath(p4, color, style = line)
    val p5 = Path()
    p5.moveTo(31.08f * u, 65.28f * u)
    p5.cubicTo(36.96f * u, 67.52f * u, 47.04f * u, 67.52f * u, 52.92f * u, 65.28f * u)
    drawPath(p5, color, style = line)
    val p6 = Path()
    p6.moveTo(37.8f * u, 92.16f * u)
    p6.lineTo(37.8f * u, 88.24f * u)
    p6.cubicTo(37.8f * u, 84.04f * u, 46.2f * u, 84.04f * u, 46.2f * u, 88.24f * u)
    p6.lineTo(46.2f * u, 92.16f * u)
    drawPath(p6, color, style = line)
    drawCircle(color, radius = 1.9f * u, center = Offset(42f * u, 52.12f * u), style = line)
    rotate(0f, Offset(121f * u, 58.5f * u)) { drawOval(color, topLeft = Offset(118f * u, 53.8f * u), size = Size(6f * u, 9.4f * u), style = line) }
    rotate(60f, Offset(128.36f * u, 62.75f * u)) { drawOval(color, topLeft = Offset(125.36f * u, 58.05f * u), size = Size(6f * u, 9.4f * u), style = line) }
    rotate(120f, Offset(128.36f * u, 71.25f * u)) { drawOval(color, topLeft = Offset(125.36f * u, 66.55f * u), size = Size(6f * u, 9.4f * u), style = line) }
    rotate(180f, Offset(121f * u, 75.5f * u)) { drawOval(color, topLeft = Offset(118f * u, 70.8f * u), size = Size(6f * u, 9.4f * u), style = line) }
    rotate(240f, Offset(113.64f * u, 71.25f * u)) { drawOval(color, topLeft = Offset(110.64f * u, 66.55f * u), size = Size(6f * u, 9.4f * u), style = line) }
    rotate(300f, Offset(113.64f * u, 62.75f * u)) { drawOval(color, topLeft = Offset(110.64f * u, 58.05f * u), size = Size(6f * u, 9.4f * u), style = line) }
    drawCircle(color, radius = 3.1f * u, center = Offset(121f * u, 67f * u), style = line)
    val p7 = Path()
    p7.moveTo(121f * u, 76.5f * u)
    p7.cubicTo(121.25f * u, 83.5f * u, 119.75f * u, 90f * u, 120.75f * u, 98.5f * u)
    drawPath(p7, color, style = line)
    val p8 = Path()
    p8.moveTo(120.65f * u, 91f * u)
    p8.cubicTo(124f * u, 85f * u, 130f * u, 83.5f * u, 133.5f * u, 84.5f * u)
    p8.cubicTo(131.5f * u, 90f * u, 126f * u, 93f * u, 120.65f * u, 91f * u)
    p8.close()
    drawPath(p8, color, style = line)
    val p9 = Path()
    p9.moveTo(120.6f * u, 85.5f * u)
    p9.cubicTo(117.5f * u, 81.25f * u, 113f * u, 81f * u, 110.5f * u, 82.25f * u)
    p9.cubicTo(112.5f * u, 86.5f * u, 117f * u, 87.75f * u, 120.6f * u, 85.5f * u)
    p9.close()
    drawPath(p9, color, style = line)
    rotate(-0f, Offset(146f * u, 70.12f * u)) { drawOval(color, topLeft = Offset(143.84f * u, 66.74f * u), size = Size(4.32f * u, 6.77f * u), style = line) }
    rotate(-60f, Offset(140.7f * u, 73.18f * u)) { drawOval(color, topLeft = Offset(138.54f * u, 69.8f * u), size = Size(4.32f * u, 6.77f * u), style = line) }
    rotate(-120f, Offset(140.7f * u, 79.3f * u)) { drawOval(color, topLeft = Offset(138.54f * u, 75.92f * u), size = Size(4.32f * u, 6.77f * u), style = line) }
    rotate(-180f, Offset(146f * u, 82.36f * u)) { drawOval(color, topLeft = Offset(143.84f * u, 78.98f * u), size = Size(4.32f * u, 6.77f * u), style = line) }
    rotate(-240f, Offset(151.3f * u, 79.3f * u)) { drawOval(color, topLeft = Offset(149.14f * u, 75.92f * u), size = Size(4.32f * u, 6.77f * u), style = line) }
    rotate(-300f, Offset(151.3f * u, 73.18f * u)) { drawOval(color, topLeft = Offset(149.14f * u, 69.8f * u), size = Size(4.32f * u, 6.77f * u), style = line) }
    drawCircle(color, radius = 2.23f * u, center = Offset(146f * u, 76.24f * u), style = line)
    val p10 = Path()
    p10.moveTo(146f * u, 83.08f * u)
    p10.cubicTo(145.82f * u, 88.12f * u, 146.9f * u, 92.8f * u, 146.18f * u, 98.92f * u)
    drawPath(p10, color, style = line)
    val p11 = Path()
    p11.moveTo(146.25f * u, 93.52f * u)
    p11.cubicTo(143.84f * u, 89.2f * u, 139.52f * u, 88.12f * u, 137f * u, 88.84f * u)
    p11.cubicTo(138.44f * u, 92.8f * u, 142.4f * u, 94.96f * u, 146.25f * u, 93.52f * u)
    p11.close()
    drawPath(p11, color, style = line)
    val p12 = Path()
    p12.moveTo(146.29f * u, 89.56f * u)
    p12.cubicTo(148.52f * u, 86.5f * u, 151.76f * u, 86.32f * u, 153.56f * u, 87.22f * u)
    p12.cubicTo(152.12f * u, 90.28f * u, 148.88f * u, 91.18f * u, 146.29f * u, 89.56f * u)
    p12.close()
    drawPath(p12, color, style = line)
    rotate(0f, Offset(85f * u, 75.1f * u)) { drawOval(color, topLeft = Offset(83.2f * u, 72.28f * u), size = Size(3.6f * u, 5.64f * u), style = line) }
    rotate(60f, Offset(89.42f * u, 77.65f * u)) { drawOval(color, topLeft = Offset(87.62f * u, 74.83f * u), size = Size(3.6f * u, 5.64f * u), style = line) }
    rotate(120f, Offset(89.42f * u, 82.75f * u)) { drawOval(color, topLeft = Offset(87.62f * u, 79.93f * u), size = Size(3.6f * u, 5.64f * u), style = line) }
    rotate(180f, Offset(85f * u, 85.3f * u)) { drawOval(color, topLeft = Offset(83.2f * u, 82.48f * u), size = Size(3.6f * u, 5.64f * u), style = line) }
    rotate(240f, Offset(80.58f * u, 82.75f * u)) { drawOval(color, topLeft = Offset(78.78f * u, 79.93f * u), size = Size(3.6f * u, 5.64f * u), style = line) }
    rotate(300f, Offset(80.58f * u, 77.65f * u)) { drawOval(color, topLeft = Offset(78.78f * u, 74.83f * u), size = Size(3.6f * u, 5.64f * u), style = line) }
    drawCircle(color, radius = 1.86f * u, center = Offset(85f * u, 80.2f * u), style = line)
    val p13 = Path()
    p13.moveTo(85f * u, 85.9f * u)
    p13.cubicTo(85.15f * u, 90.1f * u, 84.25f * u, 94f * u, 84.85f * u, 99.1f * u)
    drawPath(p13, color, style = line)
    val p14 = Path()
    p14.moveTo(84.79f * u, 94.6f * u)
    p14.cubicTo(86.8f * u, 91f * u, 90.4f * u, 90.1f * u, 92.5f * u, 90.7f * u)
    p14.cubicTo(91.3f * u, 94f * u, 88f * u, 95.8f * u, 84.79f * u, 94.6f * u)
    p14.close()
    drawPath(p14, color, style = line)
    val p15 = Path()
    p15.moveTo(84.76f * u, 91.3f * u)
    p15.cubicTo(82.9f * u, 88.75f * u, 80.2f * u, 88.6f * u, 78.7f * u, 89.35f * u)
    p15.cubicTo(79.9f * u, 91.9f * u, 82.6f * u, 92.65f * u, 84.76f * u, 91.3f * u)
    p15.close()
    drawPath(p15, color, style = line)
    rotate(-8f, Offset(91.5f * u, 24.2f * u)) { drawOval(color, topLeft = Offset(85.29f * u, 20.02f * u), size = Size(12.42f * u, 8.37f * u), style = line) }
    val p16 = Path()
    p16.moveTo(89.61f * u, 20.37f * u)
    p16.cubicTo(90.56f * u, 22.31f * u, 90.56f * u, 25.82f * u, 89.34f * u, 28.3f * u)
    drawPath(p16, color, style = line)
    val p17 = Path()
    p17.moveTo(93.12f * u, 19.96f * u)
    p17.cubicTo(94.06f * u, 22.04f * u, 94.06f * u, 25.55f * u, 92.85f * u, 28.14f * u)
    drawPath(p17, color, style = line)
    drawCircle(color, radius = 2.43f * u, center = Offset(99.06f * u, 22.58f * u), style = line)
    drawCircle(color, radius = 0.43f * u, center = Offset(99.87f * u, 22.18f * u))
    val p18 = Path()
    p18.moveTo(100.14f * u, 20.42f * u)
    p18.cubicTo(100.68f * u, 18.8f * u, 101.76f * u, 17.99f * u, 103.11f * u, 17.99f * u)
    drawPath(p18, color, style = line)
    val p19 = Path()
    p19.moveTo(98.66f * u, 20.2f * u)
    p19.cubicTo(98.52f * u, 18.53f * u, 99.06f * u, 17.05f * u, 100.41f * u, 16.37f * u)
    drawPath(p19, color, style = line)
    rotate(-24f, Offset(89.88f * u, 16.91f * u)) { drawOval(color, topLeft = Offset(87.45f * u, 12.46f * u), size = Size(4.86f * u, 8.91f * u), style = line) }
    rotate(14f, Offset(93.93f * u, 16.37f * u)) { drawOval(color, topLeft = Offset(91.77f * u, 12.19f * u), size = Size(4.32f * u, 8.37f * u), style = line) }
    val p20 = Path()
    p20.moveTo(85.42f * u, 24.88f * u)
    p20.lineTo(83.4f * u, 25.69f * u)
    drawPath(p20, color, style = line)
    rotate(8f, Offset(138.5f * u, 38.6f * u)) { drawOval(color, topLeft = Offset(133.67f * u, 35.34f * u), size = Size(9.66f * u, 6.51f * u), style = line) }
    val p21 = Path()
    p21.moveTo(139.97f * u, 35.62f * u)
    p21.cubicTo(139.24f * u, 37.13f * u, 139.24f * u, 39.86f * u, 140.18f * u, 41.79f * u)
    drawPath(p21, color, style = line)
    val p22 = Path()
    p22.moveTo(137.24f * u, 35.3f * u)
    p22.cubicTo(136.5f * u, 36.92f * u, 136.5f * u, 39.65f * u, 137.45f * u, 41.67f * u)
    drawPath(p22, color, style = line)
    drawCircle(color, radius = 1.89f * u, center = Offset(132.62f * u, 37.34f * u), style = line)
    drawCircle(color, radius = 0.34f * u, center = Offset(131.99f * u, 37.02f * u))
    val p23 = Path()
    p23.moveTo(131.78f * u, 35.66f * u)
    p23.cubicTo(131.36f * u, 34.4f * u, 130.52f * u, 33.77f * u, 129.47f * u, 33.77f * u)
    drawPath(p23, color, style = line)
    val p24 = Path()
    p24.moveTo(132.94f * u, 35.49f * u)
    p24.cubicTo(133.04f * u, 34.19f * u, 132.62f * u, 33.03f * u, 131.57f * u, 32.51f * u)
    drawPath(p24, color, style = line)
    rotate(24f, Offset(139.76f * u, 32.93f * u)) { drawOval(color, topLeft = Offset(137.87f * u, 29.46f * u), size = Size(3.78f * u, 6.93f * u), style = line) }
    rotate(-14f, Offset(136.61f * u, 32.51f * u)) { drawOval(color, topLeft = Offset(134.93f * u, 29.25f * u), size = Size(3.36f * u, 6.51f * u), style = line) }
    val p25 = Path()
    p25.moveTo(143.22f * u, 39.12f * u)
    p25.lineTo(144.8f * u, 39.75f * u)
    drawPath(p25, color, style = line)
    val p26 = Path()
    p26.moveTo(76f * u, 30f * u)
    p26.cubicTo(66f * u, 34f * u, 62f * u, 42f * u, 58f * u, 54f * u)
    drawPath(p26, color, style = dashed)
    val p27 = Path()
    p27.moveTo(150f * u, 40f * u)
    p27.cubicTo(158f * u, 46f * u, 160f * u, 56f * u, 150f * u, 62f * u)
    drawPath(p27, color, style = dashed)
}

private fun DrawScope.drawGarland(u: Float, color: Color, line: Stroke, dashed: Stroke) {
    rotate(0f, Offset(18f * u, 15.12f * u)) { drawOval(color, topLeft = Offset(15.84f * u, 11.88f * u), size = Size(4.32f * u, 6.48f * u), style = line) }
    rotate(60f, Offset(23.09f * u, 18.06f * u)) { drawOval(color, topLeft = Offset(20.93f * u, 14.82f * u), size = Size(4.32f * u, 6.48f * u), style = line) }
    rotate(120f, Offset(23.09f * u, 23.94f * u)) { drawOval(color, topLeft = Offset(20.93f * u, 20.7f * u), size = Size(4.32f * u, 6.48f * u), style = line) }
    rotate(180f, Offset(18f * u, 26.88f * u)) { drawOval(color, topLeft = Offset(15.84f * u, 23.64f * u), size = Size(4.32f * u, 6.48f * u), style = line) }
    rotate(240f, Offset(12.91f * u, 23.94f * u)) { drawOval(color, topLeft = Offset(10.75f * u, 20.7f * u), size = Size(4.32f * u, 6.48f * u), style = line) }
    rotate(300f, Offset(12.91f * u, 18.06f * u)) { drawOval(color, topLeft = Offset(10.75f * u, 14.82f * u), size = Size(4.32f * u, 6.48f * u), style = line) }
    drawCircle(color, radius = 2.04f * u, center = Offset(18f * u, 21f * u), style = line)
    drawCircle(color, radius = 0.53f * u, center = Offset(18f * u, 21f * u))
    rotate(0f, Offset(204.5f * u, 17.34f * u)) { drawOval(color, topLeft = Offset(202.97f * u, 15.04f * u), size = Size(3.06f * u, 4.59f * u), style = line) }
    rotate(60f, Offset(208.11f * u, 19.42f * u)) { drawOval(color, topLeft = Offset(206.58f * u, 17.12f * u), size = Size(3.06f * u, 4.59f * u), style = line) }
    rotate(120f, Offset(208.11f * u, 23.58f * u)) { drawOval(color, topLeft = Offset(206.58f * u, 21.29f * u), size = Size(3.06f * u, 4.59f * u), style = line) }
    rotate(180f, Offset(204.5f * u, 25.66f * u)) { drawOval(color, topLeft = Offset(202.97f * u, 23.37f * u), size = Size(3.06f * u, 4.59f * u), style = line) }
    rotate(240f, Offset(200.89f * u, 23.58f * u)) { drawOval(color, topLeft = Offset(199.36f * u, 21.29f * u), size = Size(3.06f * u, 4.59f * u), style = line) }
    rotate(300f, Offset(200.89f * u, 19.42f * u)) { drawOval(color, topLeft = Offset(199.36f * u, 17.12f * u), size = Size(3.06f * u, 4.59f * u), style = line) }
    drawCircle(color, radius = 1.45f * u, center = Offset(204.5f * u, 21.5f * u), style = line)
    drawCircle(color, radius = 0.37f * u, center = Offset(204.5f * u, 21.5f * u))
    rotate(-8f, Offset(109f * u, 21f * u)) { drawOval(color, topLeft = Offset(102.1f * u, 16.35f * u), size = Size(13.8f * u, 9.3f * u), style = line) }
    val p1 = Path()
    p1.moveTo(106.9f * u, 16.74f * u)
    p1.cubicTo(107.95f * u, 18.9f * u, 107.95f * u, 22.8f * u, 106.6f * u, 25.56f * u)
    drawPath(p1, color, style = line)
    val p2 = Path()
    p2.moveTo(110.8f * u, 16.29f * u)
    p2.cubicTo(111.85f * u, 18.6f * u, 111.85f * u, 22.5f * u, 110.5f * u, 25.38f * u)
    drawPath(p2, color, style = line)
    drawCircle(color, radius = 2.7f * u, center = Offset(117.4f * u, 19.2f * u), style = line)
    drawCircle(color, radius = 0.48f * u, center = Offset(118.3f * u, 18.75f * u))
    val p3 = Path()
    p3.moveTo(118.6f * u, 16.8f * u)
    p3.cubicTo(119.2f * u, 15f * u, 120.4f * u, 14.1f * u, 121.9f * u, 14.1f * u)
    drawPath(p3, color, style = line)
    val p4 = Path()
    p4.moveTo(116.95f * u, 16.56f * u)
    p4.cubicTo(116.8f * u, 14.7f * u, 117.4f * u, 13.05f * u, 118.9f * u, 12.3f * u)
    drawPath(p4, color, style = line)
    rotate(-24f, Offset(107.2f * u, 12.9f * u)) { drawOval(color, topLeft = Offset(104.5f * u, 7.95f * u), size = Size(5.4f * u, 9.9f * u), style = line) }
    rotate(14f, Offset(111.7f * u, 12.3f * u)) { drawOval(color, topLeft = Offset(109.3f * u, 7.65f * u), size = Size(4.8f * u, 9.3f * u), style = line) }
    val p5 = Path()
    p5.moveTo(102.25f * u, 21.75f * u)
    p5.lineTo(100f * u, 22.65f * u)
    drawPath(p5, color, style = line)
    val p6 = Path()
    p6.moveTo(34f * u, 22f * u)
    p6.cubicTo(52f * u, 6f * u, 72f * u, 34f * u, 92f * u, 20f * u)
    drawPath(p6, color, style = dashed)
    val p7 = Path()
    p7.moveTo(128f * u, 20f * u)
    p7.cubicTo(148f * u, 6f * u, 168f * u, 34f * u, 190f * u, 22f * u)
    drawPath(p7, color, style = dashed)
}
