package com.vamahan.dailydraw

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.vamahan.dailydraw.draw.DrawProgram
import kotlinx.coroutines.delay
import kotlin.math.sqrt

private val Gold = Color(0xFFF5C451)
private val Ink = Color(0xFF0E0F13)
private val Card = Color(0xFF1A1C23)
private val CardHigh = Color(0xFF242732)
private val Muted = Color(0xFF8A8F9C)
private val Win = Color(0xFF4ADE80)
private val Alert = Color(0xFFF87171)
private const val CLOSING_SECS = 30

class MainActivity : ComponentActivity() {
    private val vm: DrawViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must be created before the activity is started: it registers an activity-result launcher.
        val sender = ActivityResultSender(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Ink, surface = Card)) {
                DrawScreen(vm, sender)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.start()
    }

    override fun onStop() {
        vm.stop()
        super.onStop()
    }
}

/**
 * One screen, top to bottom in the order a player asks: what is happening now,
 * what do I do, and how did my tickets do. Every action shows its own progress on
 * its own button; notices go to a snackbar instead of a line of raw text.
 */
@Composable
fun DrawScreen(vm: DrawViewModel, sender: ActivityResultSender) {
    val s by vm.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(s.chainNow()) }
    LaunchedEffect(s.clockOffset) {
        while (true) {
            now = s.chainNow()
            delay(250)
        }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.message) {
        s.message?.let {
            vm.messageShown()
            snackbar.showSnackbar(it)
        }
    }
    // Ask for notifications once the player has a ticket to hear about, not before.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val context = LocalContext.current
    LaunchedEffect(s.results.isNotEmpty()) {
        if (s.results.isNotEmpty() && Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val config = s.config
    val open = config != null && now < config.closeTs(s.roundId)
    val left = if (s.wallet == null) 1 else s.ticketsLeft()
    val picking = open && left > 0
    if (picking) ShakeToPick { vm.quickPick() }

    Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(s, onConnect = { vm.connect(sender) })
            if (s.networkTrouble) {
                Text("Connection to Solana devnet is slow — retrying…", color = Gold, fontSize = 13.sp)
            }
            NowCard(s, now, open, left)
            s.error?.let { ErrorCard(it, onDismiss = vm::dismissError) }
            if (s.lowSol) LowSolCard(busy = "airdrop" in s.pending, onAirdrop = vm::airdrop)
            if (picking) {
                PickArea(s, now, onToggle = vm::toggle, onQuickPick = vm::quickPick, onClear = vm::clearSelection) {
                    if (s.wallet == null) vm.connect(sender) else vm.enter(sender)
                }
            }
            Results(s, now, onClaim = { vm.claim(sender, it) }, onCollect = { vm.collect(sender) }, onRevealed = vm::revealed)
            HowItWorks()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Header(s: UiState, onConnect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Daily Draw", color = Gold, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text("Free draw for Seeker owners · devnet demo", color = Muted, fontSize = 12.sp)
        }
        val w = s.wallet
        Box(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, Gold.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .clickable(enabled = w == null && "connect" !in s.pending) { onConnect() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                when {
                    w != null -> "${w.take(4)}…${w.takeLast(4)} · ${formatSkr(s.skr ?: 0)} SKR"
                    "connect" in s.pending -> "Connecting…"
                    else -> "Connect wallet"
                },
                color = Color.White, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun NowCard(s: UiState, now: Long, open: Boolean, left: Int) {
    val config = s.config
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (config == null) {
            Text(
                if (s.programMissing) "The draw isn't live on devnet right now. Checking again every few seconds…"
                else "Connecting to Solana…",
                color = Muted,
            )
            return@Column
        }
        val secs = ((if (open) config.closeTs(s.roundId) else config.drawTs(s.roundId)) - now).coerceAtLeast(0)
        Text("Round #${s.roundId} · a new round every ${config.roundSecs / 60} min in this demo", color = Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (open) "Entries close in" else "Numbers drawn in", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(clock(secs), color = Gold, fontSize = 34.sp, fontWeight = FontWeight.Black)
        }
        val pot = s.round?.pot ?: config.carry
        Text("Prize pot ${formatSkr(pot)} SKR · ${s.round?.tickets ?: 0} ticket(s)", color = Color.White, fontSize = 15.sp)
        val mine = s.resultsIn(s.roundId)
        Text(
            when {
                !open -> "Entries are closed. The winning numbers are drawn on-chain when the timer ends."
                s.wallet == null -> "Pick 5 numbers, then connect a wallet to enter. Entry is free."
                left > 0 && mine.isEmpty() -> "Pick 5 numbers below. Entry is free — the sponsor adds ${formatSkr(config.perTicketBonus)} SKR to the pot per ticket."
                left > 0 -> "You're in. Your streak earned you another ticket — pick again below."
                else -> "You're in! Your result appears below when the draw ends."
            },
            color = Muted, fontSize = 13.sp,
        )
        mine.forEach { Balls(it.picks) }
    }
}

@Composable
private fun PickArea(
    s: UiState,
    now: Long,
    onToggle: (Int) -> Unit,
    onQuickPick: () -> Unit,
    onClear: () -> Unit,
    onEnter: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Your numbers  ${s.selection.size}/${DrawProgram.PICKS} · or shake the phone", color = Color.White, fontWeight = FontWeight.Bold)
        NumberGrid(s.selection, onToggle)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onQuickPick, modifier = Modifier.weight(1f)) { Text("Quick pick") }
            OutlinedButton(onClick = onClear, enabled = s.selection.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Clear") }
        }
        val entering = "enter" in s.pending
        val needsSgt = s.wallet != null && s.identity == null && s.config?.requireSgt == true
        val ready = s.selection.size == DrawProgram.PICKS
        // A wallet approval takes 10-60 s; entering in the last seconds only ends
        // in "entries closed" after the player has already approved.
        val closing = s.config?.let { it.closeTs(s.roundId) - now < CLOSING_SECS } ?: false
        Button(
            onClick = onEnter,
            enabled = !entering && !closing && "connect" !in s.pending && !needsSgt && (s.wallet == null || ready),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            if (entering) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Ink, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                when {
                    entering -> "Confirm in your wallet, then wait a moment…"
                    closing -> "Entries closing — next round in ${clock(((s.config?.drawTs(s.roundId) ?: now) - now).coerceAtLeast(0))}"
                    s.wallet == null -> "Connect wallet to enter"
                    needsSgt -> "Needs a Seeker Genesis Token"
                    !ready -> "Pick ${DrawProgram.PICKS - s.selection.size} more"
                    else -> "Enter round #${s.roundId} — free"
                },
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun NumberGrid(selection: Set<Int>, onToggle: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..DrawProgram.MAX_NUMBER).chunked(9).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { n ->
                    val on = n in selection
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(if (on) Gold else Card)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onToggle(n)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$n", color = if (on) Ink else Color.White, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                repeat(9 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Results(
    s: UiState,
    now: Long,
    onClaim: (MyResult) -> Unit,
    onCollect: () -> Unit,
    onRevealed: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Your tickets", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        when {
            s.wallet == null -> Text("Connect a wallet to see your tickets and results.", color = Muted, fontSize = 13.sp)
            s.results.isEmpty() -> Text("No tickets yet. After each draw your result shows up here.", color = Muted, fontSize = 13.sp)
        }
        val returnable = s.results.count { it.canReturnDeposit }
        if (returnable > 0) {
            val busy = "collect" in s.pending
            OutlinedButton(onClick = onCollect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "Returning deposits…" else "Get back the SOL deposit of $returnable finished ticket(s)")
            }
        }
        s.results.take(12).forEach { r ->
            ResultCard(r, now, s, pending = r.key in s.pending, fresh = r.key in s.freshlyDrawn, onClaim = { onClaim(r) }, onRevealed = { onRevealed(r.key) })
        }
    }
}

@Composable
private fun ResultCard(r: MyResult, now: Long, s: UiState, pending: Boolean, fresh: Boolean, onClaim: () -> Unit, onRevealed: () -> Unit) {
    val won = r.outcome == Outcome.Won || r.outcome == Outcome.Claimed
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (won) Win.copy(alpha = 0.10f) else Card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Round #${r.round}", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val drawIn = (if (r.drawTs > 0) r.drawTs else s.config?.drawTs(r.round) ?: now) - now
            val (label, color) = when (r.outcome) {
                Outcome.Waiting -> "Draw in ${clock(drawIn.coerceAtLeast(0))}" to Muted
                Outcome.Drawing -> "Drawing the numbers…" to Gold
                Outcome.NoMatch -> "No match this time" to Muted
                Outcome.Matched -> "${r.matches} matched · the winner had ${r.best}" to Muted
                Outcome.Won -> "You won ${formatSkr(r.prize)} SKR!" to Win
                Outcome.Claimed -> "Won ${formatSkr(r.prize)} SKR · claimed ✓" to Win
            }
            if (r.outcome == Outcome.Drawing) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Gold, strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, color = color, fontSize = 13.sp, fontWeight = if (won) FontWeight.Bold else FontWeight.Normal)
        }
        if (r.winning.isNotEmpty()) {
            Text("Winning numbers", color = Muted, fontSize = 12.sp)
            RevealBalls(r.winning, animate = fresh, onDone = onRevealed)
        }
        Text("Your numbers", color = Muted, fontSize = 12.sp)
        Balls(r.picks, highlight = r.hits)
        if (r.winning.isNotEmpty()) VerifyLink(r.round)
        if (r.canClaim) {
            Button(
                onClick = onClaim,
                enabled = !pending,
                colors = ButtonDefaults.buttonColors(containerColor = Win, contentColor = Ink),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (pending) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Ink, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (pending) "Claiming — confirm in your wallet…" else "Claim ${formatSkr(r.prize)} SKR", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * The draw's receipts: the round account's history on Solana Explorer holds the
 * Switchboard commit made after entries closed, the reveal, and the scoring.
 * Anyone can check that the numbers came from that commit and nowhere else.
 */
@Composable
private fun VerifyLink(round: Long) {
    val uri = LocalUriHandler.current
    val address by produceState<String?>(null, round) { value = DrawProgram.round(round).base58() }
    val a = address ?: return
    Text(
        "Verify this draw on Solana Explorer ↗",
        color = Gold, fontSize = 13.sp,
        modifier = Modifier.clickable { uri.openUri("https://explorer.solana.com/address/$a?cluster=devnet") }.padding(vertical = 4.dp),
    )
}

/** A failure stays next to the action it belongs to until the player dismisses or retries. */
@Composable
private fun ErrorCard(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Alert.copy(alpha = 0.15f))
            .border(1.dp, Alert.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text("✕", color = Muted, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(start = 12.dp))
    }
}

@Composable
private fun LowSolCard(busy: Boolean, onAirdrop: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Your wallet needs a little devnet SOL. Entry is free, but each ticket holds a small deposit you get back after the draw.",
            color = Color.White, fontSize = 14.sp,
        )
        OutlinedButton(onClick = onAirdrop, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (busy) "Asking the devnet faucet…" else "Get free devnet SOL")
        }
    }
}

/** The draw's moment: a fresh result turns its balls over one by one, each with a tap. */
@Composable
private fun RevealBalls(numbers: List<Int>, animate: Boolean, onDone: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var shown by remember(numbers) { mutableIntStateOf(if (animate) 0 else numbers.size) }
    LaunchedEffect(numbers, animate) {
        if (!animate) return@LaunchedEffect
        for (i in 1..numbers.size) {
            delay(550)
            shown = i
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        onDone()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        numbers.forEachIndexed { i, n ->
            Ball(if (i < shown) "$n" else "?", fill = if (i < shown) Gold else CardHigh, text = if (i < shown) Ink else Muted)
        }
    }
}

@Composable
private fun Balls(numbers: List<Int>, highlight: Set<Int> = emptySet()) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        numbers.forEach { n ->
            val hit = n in highlight
            Ball("$n", fill = if (hit) Win else CardHigh, text = if (hit) Ink else Color.White)
        }
    }
}

@Composable
private fun Ball(label: String, fill: Color, text: Color) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(fill).border(1.dp, Gold.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
}

@Composable
private fun HowItWorks() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("How it works", color = Color.White, fontWeight = FontWeight.Bold)
        listOf(
            "Pick 5 numbers from 1 to 85. One free ticket per round — per Seeker Genesis Token on mainnet.",
            "Play every day: each 7-day streak adds a ticket, up to 5.",
            "The numbers come from Switchboard randomness on-chain. Nobody can pick them, including us — every draw links to its proof on Solana Explorer.",
            "The best match wins the pot and ties split it. No match? The pot rolls over.",
            "This demo runs on devnet with a round every few minutes; prizes are test SKR.",
        ).forEach { Text("• $it", color = Muted, fontSize = 13.sp) }
    }
}

private fun clock(secs: Long) = "%02d:%02d".format(secs / 60, secs % 60)

/** A firm shake picks five random numbers, the phone-native quick pick. */
@Composable
private fun ShakeToPick(onShake: () -> Unit) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onShake)
    DisposableEffect(Unit) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var last = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (x, y, z) = e.values
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                val t = System.currentTimeMillis()
                if (g > 2.6f && t - last > 1200) {
                    last = t
                    latest()
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        accel?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sensors.unregisterListener(listener) }
    }
}
