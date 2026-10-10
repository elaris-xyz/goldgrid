package com.vamahan.dailydraw

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A card that is waiting on something outside the player's hands (their ticket
 * before the draw) breathes: its gold edge brightens and fades every two seconds,
 * so the screen reads as alive rather than stuck.
 */
@Composable
fun Modifier.breathing(active: Boolean, shape: RoundedCornerShape = RoundedCornerShape(16.dp)): Modifier {
    if (!active) return this
    val t = rememberInfiniteTransition(label = "breath")
    val glow by t.animateFloat(
        0.15f, 0.85f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    return this
        .border(2.dp, Gold.copy(alpha = glow), shape)
        .drawBehind { drawRect(Gold.copy(alpha = glow * 0.07f)) }
}

/** The time left in the current phase as a gold ring that empties toward zero. */
@Composable
fun CountdownRing(fraction: Float, size: Dp = 44.dp) {
    Canvas(Modifier.size(size)) {
        val stroke = 4.dp.toPx()
        val inset = stroke / 2
        val arc = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(CardHigh, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        drawArc(Gold, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** A ball that arrives with a small bounce the moment it is revealed. */
@Composable
fun PoppingBall(label: String, fill: Color, text: Color, revealed: Boolean) {
    val scale = remember { Animatable(if (revealed) 1f else 0.85f) }
    LaunchedEffect(revealed) {
        if (revealed) {
            scale.snapTo(0.3f)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
        }
    }
    Box(
        Modifier.size(36.dp).scale(scale.value).clip(CircleShape).background(fill)
            .border(1.dp, Gold.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
}

/** A burst of gold and green confetti over a winning result. Plays once. */
@Composable
fun Confetti(modifier: Modifier = Modifier, pieces: Int = 70, millis: Int = 2600) {
    val bits = remember {
        List(pieces) {
            val angle = Random.nextDouble(-PI * 0.95, -PI * 0.05)
            val speed = Random.nextDouble(0.55, 1.25).toFloat()
            Bit(
                vx = (cos(angle) * speed).toFloat(), vy = (sin(angle) * speed).toFloat(),
                spin = Random.nextFloat() * 720f - 360f,
                color = listOf(Gold, Win, Color.White, Color(0xFFFFB020))[Random.nextInt(4)],
                w = Random.nextFloat() * 6f + 5f, h = Random.nextFloat() * 4f + 3f,
            )
        }
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(millis, easing = LinearEasing)) }
    if (progress.value >= 1f) return
    Canvas(modifier.fillMaxSize()) {
        val p = progress.value
        val origin = Offset(size.width / 2, size.height * 0.55f)
        bits.forEach { b ->
            val t = p * 1.6f
            val x = origin.x + b.vx * t * size.width * 0.55f
            val y = origin.y + b.vy * t * size.height * 0.9f + 0.9f * t * t * size.height * 0.7f
            rotate(b.spin * p, Offset(x, y)) {
                drawRect(b.color.copy(alpha = 1f - p), Offset(x, y), Size(b.w.dp.toPx(), b.h.dp.toPx()))
            }
        }
    }
}

private class Bit(val vx: Float, val vy: Float, val spin: Float, val color: Color, val w: Float, val h: Float)

/**
 * A dim field of dots behind the sign-in screen, the game board stretched to
 * the edges; now and then one of them glints gold and fades.
 */
@Composable
private fun TwinkleField() {
    val t = rememberInfiniteTransition(label = "field")
    val clock by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "clock")
    val seeds = remember { List(60) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    Canvas(Modifier.fillMaxSize()) {
        val step = 34.dp.toPx()
        val r = 3.dp.toPx()
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) {
                drawCircle(Color.White.copy(alpha = 0.045f), r, Offset(x, y))
                x += step
            }
            y += step
        }
        seeds.forEach { (sx, sy, phase) ->
            // Each glint rises and falls once per cycle, at its own moment.
            val local = ((clock + phase) % 1f)
            val a = (1f - kotlin.math.abs(local * 2f - 1f)).let { it * it * it }
            val cx = (sx * size.width / step).toInt() * step + step / 2
            val cy = (sy * size.height / step).toInt() * step + step / 2
            drawCircle(Gold.copy(alpha = 0.55f * a), r * (1f + a), Offset(cx, cy))
        }
    }
}

// The brand icon's pattern: which of the 5x5 balls are gold, row by row.
private val LOGO_GOLD = setOf(1 * 5 + 1, 2 * 5 + 1, 3 * 5 + 2, 3 * 5 + 4, 4 * 5 + 0)

/**
 * The logo builds itself: the 25 balls arrive on a diagonal wave, then five turn
 * gold one after another, as a pick does. Afterwards the gold ones keep a slow
 * pulse, so the mark reads as the game rather than a picture of it.
 */
@Composable
private fun AssemblingLogo(size: Dp) {
    val build = remember { Animatable(0f) }
    LaunchedEffect(Unit) { build.animateTo(1f, tween(2_200, easing = LinearEasing)) }
    val t = rememberInfiniteTransition(label = "logo")
    val pulse by t.animateFloat(0.92f, 1.04f, infiniteRepeatable(tween(1_300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val order = LOGO_GOLD.toList()
    Canvas(Modifier.size(size)) {
        val cell = this.size.width / 5f
        val radius = cell * 0.43f
        for (i in 0 until 25) {
            val row = i / 5
            val col = i % 5
            // arrival: a diagonal wave over the first 55 % of the build
            val arrive = ((build.value / 0.55f) - (row + col) / 8f * 0.6f).coerceIn(0f, 1f)
            if (arrive <= 0f) continue
            val bounce = 1f + 0.18f * sin(arrive * PI.toFloat()) * (1f - arrive)
            val gold = order.indexOf(i).let { k -> k >= 0 && build.value >= 0.6f + k * 0.08f }
            val scale = arrive * bounce * (if (gold && build.value >= 1f) pulse else 1f)
            val center = Offset(col * cell + cell / 2, row * cell + cell / 2)
            if (gold) drawCircle(Gold.copy(alpha = 0.22f), radius * scale * 1.35f, center)
            drawCircle(if (gold) Gold else Color(0xFF454545), radius * scale, center)
        }
    }
}

/** A word lit by a band of light that sweeps across it. */
@Composable
private fun ShimmerTitle(text: String) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-400f, 1200f, infiniteRepeatable(tween(2_600, easing = LinearEasing)), label = "x")
    Text(
        text,
        fontSize = 46.sp, fontWeight = FontWeight.Black,
        style = androidx.compose.ui.text.TextStyle(
            brush = Brush.linearGradient(
                listOf(Color.White, Color.White, Gold, Color.White, Color.White),
                start = Offset(x, 0f), end = Offset(x + 400f, 120f),
            ),
        ),
    )
}

/** Fades and lifts its content into place after a delay, for a staggered entrance. */
@Composable
private fun Arrive(delayMillis: Int, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(700, delayMillis, FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 36.dp.toPx() }) { content() }
}

/**
 * The sign-in screen. The board comes to life, the logo assembles and picks its
 * five, the name lights up, and the reasons to play and the two ways in follow.
 */
@Composable
fun WelcomeScreen(connectedAs: String?, onConnect: () -> Unit, onContinue: () -> Unit) {
    // A returning player sees the intro play out and goes on by itself; a tap skips.
    if (connectedAs != null) LaunchedEffect(Unit) { kotlinx.coroutines.delay(4_200); onContinue() }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF17181F), Ink, Color(0xFF08080B))))
            .then(if (connectedAs != null) Modifier.clickable(onClick = onContinue) else Modifier),
    ) {
        TwinkleField()
        Box(Modifier.fillMaxSize().drawBehind {
            drawCircle(
                Brush.radialGradient(listOf(Gold.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width / 2, size.height * 0.3f), radius = size.width * 0.7f),
                radius = size.width * 0.7f, center = Offset(size.width / 2, size.height * 0.3f),
            )
        })
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AssemblingLogo(172.dp)
            Spacer(Modifier.height(26.dp))
            Arrive(1_700) { ShimmerTitle("Goldgrid") }
            Arrive(1_950) { Text("Pick five. Strike gold.", color = Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(30.dp))
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Arrive(2_250) { Feature("◎", "Free to play", "A new round every five minutes. Sponsors fill the pot — you never pay to enter.") }
                Arrive(2_450) { Feature("◈", "Provably fair", "ORAO VRF randomness on Solana picks the numbers. Nobody can choose them, not even us.") }
                Arrive(2_650) { Feature("◆", "Paid automatically", "Win and the prize lands in your wallet by itself. Nothing to claim.") }
            }
            Spacer(Modifier.height(34.dp))
            Arrive(2_950) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = if (connectedAs != null) onContinue else onConnect,
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                        modifier = Modifier.widthIn(min = 280.dp, max = 420.dp).height(56.dp),
                    ) { Text(if (connectedAs != null) "Play" else "Connect wallet", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                    if (connectedAs != null) {
                        Text("Signed in as ${connectedAs.take(4)}…${connectedAs.takeLast(4)} · tap anywhere to skip", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    } else {
                        TextButton(onClick = onContinue) { Text("Look around first", color = Muted) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Feature(mark: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Gold.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text(mark, color = Gold, fontSize = 18.sp)
        }
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(body, color = Muted, fontSize = 13.sp)
        }
    }
}
