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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.vamahan.dailydraw.draw.DrawProgram
import kotlinx.coroutines.delay
import kotlin.math.sqrt

// The brand's gold, from the Goldgrid icon.
internal val Gold = Color(0xFFFFD803)
internal val Ink = Color(0xFF0E0F13)
internal val Card = Color(0xFF1A1C23)
internal val CardHigh = Color(0xFF242732)
internal val Muted = Color(0xFF8A8F9C)
internal val Win = Color(0xFF4ADE80)
private val Danger = Color(0xFFF87171)
private val Amber = Color(0xFFFFA940)
/** Past this the screen stops stretching: a tablet shows a phone-width column. */
private val MAX_CONTENT_WIDTH = 640.dp
private const val RECENT_ON_MAIN = 3

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

    override fun onResume() {
        super.onResume()
        vm.onForeground()
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
    val phase = config?.phaseOf(s.roundId, now) ?: Phase.Open
    val open = config != null && phase != Phase.Closed
    val left = if (s.wallet == null) 1 else s.ticketsLeft()
    // The grid stays while there is anything to pick for: this round, or the next
    // one once this closes. It goes only when the player is in and has no ticket left.
    val showGrid = config != null && (s.wallet == null || left > 0 || phase != Phase.Open)
    if (showGrid) ShakeToPick { vm.quickPick() }

    // A new round opening with five numbers already picked: tell the player's hand.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(s.roundId) {
        if (s.wallet != null && s.selection.size == DrawProgram.PICKS) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    if (s.showWelcome) {
        WelcomeScreen(
            connectedAs = s.wallet,
            onConnect = { vm.welcomed(); vm.connect(sender) },
            onContinue = vm::welcomed,
        )
        return
    }
    var showActivity by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showActivity || showSettings) { showActivity = false; showSettings = false }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        // The action stays on screen: on a tablet the grid alone is taller than the view.
        bottomBar = {
            if (!showActivity && !showSettings && config != null) ActionBar(s, now, phase, left) { if (s.wallet == null) vm.connect(sender) else vm.enter(sender) }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = MAX_CONTENT_WIDTH)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (showSettings) {
                    SettingsPage(s, vm, onBack = { showSettings = false }, onIntro = { showSettings = false; vm.showIntro() })
                    return@Column
                }
                if (showActivity) {
                    ActivityPage(s, now, onBack = { showActivity = false }, onClaim = { vm.claim(sender, it) },
                        onCollect = { vm.collect(sender) }, onRevealed = vm::revealed)
                    return@Column
                }
                Header(s, onConnect = { vm.connect(sender) }, onBalance = { showActivity = true }, onDisconnect = vm::disconnect, onSettings = { showSettings = true })
                if (s.networkTrouble) {
                    Text("Connection to Solana devnet is slow — retrying…", color = Gold, fontSize = 13.sp)
                }
                NowCard(s, now, phase, left)
                s.error?.let { ErrorCard(it, onDismiss = vm::dismissError) }
                if (s.lowSol) LowSolCard(busy = "airdrop" in s.pending, onAirdrop = vm::airdrop)
                if (showGrid) PickArea(s, phase, onToggle = vm::toggle, onQuickPick = vm::quickPick, onClear = vm::clearSelection)
                Results(s, now, onClaim = { vm.claim(sender, it) }, onCollect = { vm.collect(sender) },
                    onRevealed = vm::revealed, onSeeAll = { showActivity = true })
                HowItWorks()
                WhySkr()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Header(s: UiState, onConnect: () -> Unit, onBalance: () -> Unit, onDisconnect: () -> Unit, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(
            painterResource(R.drawable.goldgrid_logo), contentDescription = null,
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(4.dp),
        )
        Column(Modifier.weight(1f)) {
            Text("Goldgrid", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Pick five. Strike gold. · devnet", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val w = s.wallet
        if (w != null) {
            // The prize balance is its own chip: tapping it shows where every SKR came from.
            Box(
                Modifier.clip(RoundedCornerShape(20.dp)).background(Gold).clickable(onClick = onBalance)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text("${formatSkr(s.skr ?: 0)} SKR", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
        var menu by remember { mutableStateOf(false) }
        Box(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, Gold.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .clickable(enabled = "connect" !in s.pending) { if (w == null) onConnect() else menu = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Disconnect wallet") }, onClick = { menu = false; onDisconnect() })
            }
            Text(
                when {
                    w != null -> "${w.take(4)}…${w.takeLast(4)}"
                    "connect" in s.pending -> "Connecting…"
                    else -> "Connect wallet"
                },
                color = Color.White, fontSize = 12.sp,
            )
        }
        Box(
            Modifier.size(38.dp).clip(CircleShape).border(1.dp, Gold.copy(alpha = 0.4f), CircleShape).clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) { Text("⚙", color = Color.White, fontSize = 18.sp) }
    }
}

@Composable
private fun NowCard(s: UiState, now: Long, phase: Phase, left: Int) {
    val config = s.config
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (config == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!s.programMissing) CircularProgressIndicator(Modifier.size(22.dp), color = Gold, strokeWidth = 2.dp)
                Text(
                    if (s.programMissing) "The draw isn't live on devnet right now. Checking again every few seconds…"
                    else "Loading the current round from Solana…",
                    color = Muted,
                )
            }
            return@Column
        }
        val close = config.closeTs(s.roundId)
        val draw = config.drawTs(s.roundId)
        val (chip, chipColor) = when (phase) {
            Phase.Open -> "OPEN" to Win
            Phase.LastCall -> "LAST CALL" to Amber
            Phase.Closed -> "CLOSED" to Muted
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(chipColor.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(chip, color = chipColor, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
            Text("Round ${config.labelOf(s.roundId)}", color = Muted, fontSize = 13.sp)
        }
        val (headline, target, color) = when (phase) {
            Phase.Open -> Triple("Entries close in", close, Gold)
            Phase.LastCall -> Triple("Last call — closes in", close, Amber)
            Phase.Closed -> Triple("Winning numbers in", draw, Color.White)
        }
        val secs = (target - now).coerceAtLeast(0)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(headline, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(clock(secs), color = color, fontSize = 34.sp, fontWeight = FontWeight.Black)
        }
        val pot = s.round?.pot ?: config.carry
        val tickets = s.round?.tickets ?: 0
        val rolled = (pot - config.perTicketBonus * tickets).coerceAtLeast(0)
        // The pot counts up when a ticket lands, so growth is something you see.
        val shownPot by animateFloatAsState(pot / 1_000_000f, tween(900), label = "pot")
        Text("Prize pot ${"%.2f".format(shownPot).trimEnd('0').trimEnd('.')} SKR", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(
            "${formatSkr(config.perTicketBonus)} SKR per ticket × $tickets" +
                (if (rolled > 0) " + ${formatSkr(rolled)} rolled over from rounds nobody won" else "") +
                ". The best match takes the whole pot; ties split it.",
            color = Muted, fontSize = 13.sp,
        )
        val entered = s.resultsIn(s.roundId).isNotEmpty()
        val nextIn = clock((draw - now).coerceAtLeast(0))
        Text(
            when {
                phase == Phase.Closed && entered -> "You're in this round. The numbers are drawn on-chain when the timer ends; the next round opens right after."
                phase == Phase.Closed -> "This round is closed. The next one opens in $nextIn — pick now and your numbers are kept."
                phase == Phase.LastCall && !entered -> "Too close to the deadline for a wallet approval to land in time. Pick now — the next round opens in $nextIn and your numbers are kept."
                s.wallet == null -> "Pick 5 numbers, then connect a wallet to enter. Entry is free."
                entered && left > 0 -> "You're in. Your streak earned you another ticket — pick again below."
                entered -> "You're in! Your ticket is waiting below — the result lands there when the draw ends."
                else -> "Pick 5 numbers below. Entry is free — the sponsor adds ${formatSkr(config.perTicketBonus)} SKR to the pot per ticket."
            },
            color = Muted, fontSize = 13.sp,
        )
    }
    if (config != null) PhaseStrip(config.roundSecs, config.drawTs(s.roundId) - config.roundSecs, config.closeTs(s.roundId), now)
    }
}

/**
 * The round as a strip along the card's bottom edge: dim gold for the time to
 * enter, amber for last call, grey until the draw, and the part already gone
 * filled bright. How long is left reads at a glance without a second timer.
 */
@Composable
private fun PhaseStrip(roundSecs: Long, start: Long, close: Long, now: Long) {
    val total = roundSecs.toFloat().coerceAtLeast(1f)
    val openEnd = ((close - LAST_CALL_SECS - start) / total).coerceIn(0f, 1f)
    val lastEnd = ((close - start) / total).coerceIn(0f, 1f)
    val at = ((now - start) / total).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxWidth().height(5.dp)) {
        val w = size.width
        val h = size.height
        fun band(from: Float, to: Float, color: Color) {
            if (to > from) drawRect(color, Offset(w * from, 0f), Size(w * (to - from), h))
        }
        band(0f, openEnd, Gold.copy(alpha = 0.22f))
        band(openEnd, lastEnd, Amber.copy(alpha = 0.28f))
        band(lastEnd, 1f, CardHigh)
        band(0f, minOf(at, openEnd), Gold)
        band(openEnd, minOf(at, lastEnd), Amber)
        band(lastEnd, at, Color.White.copy(alpha = 0.55f))
    }
}

@Composable
private fun PickArea(
    s: UiState,
    phase: Phase,
    onToggle: (Int) -> Unit,
    onQuickPick: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            (if (phase == Phase.Open) "Your numbers" else "Your numbers for the next round") +
                "  ${s.selection.size}/${DrawProgram.PICKS} · or shake the phone",
            color = Color.White, fontWeight = FontWeight.Bold,
        )
        NumberGrid(s.selection, onToggle)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onQuickPick, modifier = Modifier.weight(1f)) { Text("Quick pick") }
            OutlinedButton(onClick = onClear, enabled = s.selection.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Clear") }
        }
    }
}

/**
 * The one thing to do next, pinned to the bottom of the screen. When it cannot be
 * pressed it says why and until when; when it can, it glows.
 */
@Composable
private fun ActionBar(s: UiState, now: Long, phase: Phase, left: Int, onEnter: () -> Unit) {
    val config = s.config ?: return
    val entering = "enter" in s.pending
    val needsSgt = s.wallet != null && s.identity == null && config.requireSgt
    val ready = s.selection.size == DrawProgram.PICKS
    val nextIn = clock((config.drawTs(s.roundId) - now).coerceAtLeast(0))
    val entered = s.resultsIn(s.roundId).isNotEmpty()
    val (label, enabled) = when {
        s.wallet == null -> "Connect wallet to play" to ("connect" !in s.pending)
        entering -> "Confirm in your wallet, then wait a moment…" to false
        needsSgt -> "Needs a Seeker Genesis Token" to false
        phase != Phase.Open && ready -> "Next round opens in $nextIn · your numbers are kept" to false
        phase != Phase.Open -> "Next round opens in $nextIn" to false
        left == 0 -> "You're in · draw in ${clock((config.drawTs(s.roundId) - now).coerceAtLeast(0))}" to false
        !ready -> "Pick ${DrawProgram.PICKS - s.selection.size} more number${if (DrawProgram.PICKS - s.selection.size == 1) "" else "s"}" to false
        entered -> "Enter another ticket for #${config.dayNumberOf(s.roundId)} — free" to true
        else -> "Enter round #${config.dayNumberOf(s.roundId)} — free" to true
    }
    val glow = rememberInfiniteTransition(label = "cta")
    val pulse by glow.animateFloat(1f, 1.03f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.fillMaxWidth().background(Ink).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Button(
            onClick = onEnter,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = Gold, contentColor = Ink,
                disabledContainerColor = CardHigh, disabledContentColor = Color.White.copy(alpha = 0.75f),
            ),
            modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth().height(56.dp)
                .scale(if (enabled && s.wallet != null) pulse else 1f),
        ) {
            if (entering) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Ink, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(label, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
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
                    val pop by animateFloatAsState(
                        if (on) 1.08f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "pop",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .scale(pop)
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
    onSeeAll: (() -> Unit)? = null,
    limit: Int = RECENT_ON_MAIN,
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
                Text(if (busy) "Returning deposits…" else "Get back the SOL deposit of $returnable finished ${plural(returnable, "ticket")} now")
            }
        }
        // Anything waiting for the player (a prize to claim) is never hidden behind "see all".
        val shown = (s.results.take(limit) + s.results.filter { it.canClaim }).distinct()
        shown.forEach { r ->
            ResultCard(r, now, s, pending = r.key in s.pending, fresh = r.key in s.freshlyDrawn, onClaim = { onClaim(r) }, onRevealed = { onRevealed(r.key) })
        }
        if (onSeeAll != null && s.results.size > shown.size) {
            OutlinedButton(onClick = onSeeAll, modifier = Modifier.fillMaxWidth()) { Text("See all activity (${s.results.size}) →") }
        }
    }
}

@Composable
private fun ResultCard(r: MyResult, now: Long, s: UiState, pending: Boolean, fresh: Boolean, onClaim: () -> Unit, onRevealed: () -> Unit) {
    val won = r.outcome == Outcome.Won || r.outcome == Outcome.Claimed
    val waiting = r.outcome == Outcome.Waiting || r.outcome == Outcome.Drawing
    Box {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (won) Win.copy(alpha = 0.10f) else Card)
            .breathing(waiting)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.config.labelFor(r.round, r.drawTs), color = Color.White, fontWeight = FontWeight.Bold)
                if (r.drawTs > 0) Text(when_(r.drawTs), color = Muted, fontSize = 12.sp)
            }
            val drawIn = (if (r.drawTs > 0) r.drawTs else s.config?.drawTs(r.round) ?: now) - now
            val (label, color) = when (r.outcome) {
                Outcome.Waiting -> "Draw in ${clock(drawIn.coerceAtLeast(0))}" to Muted
                Outcome.Drawing -> "Drawing the numbers…" to Gold
                Outcome.NoMatch -> "No match this time" to Muted
                Outcome.Matched -> "${r.matches} matched · the winner had ${r.best}" to Muted
                Outcome.Won -> "You won ${formatSkr(r.prize)} SKR!" to Win
                Outcome.Claimed -> "Won ${formatSkr(r.prize)} SKR · paid to your wallet ✓" to Win
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
                Text(if (pending) "Collecting — confirm in your wallet…" else "Collect ${formatSkr(r.prize)} SKR now", fontWeight = FontWeight.Bold)
            }
        }
    }
    if (fresh && won) Confetti(Modifier.matchParentSize())
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Danger.copy(alpha = 0.15f))
            .border(1.dp, Danger.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp),
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
            PoppingBall(if (i < shown) "$n" else "?", fill = if (i < shown) Gold else CardHigh, text = if (i < shown) Ink else Muted, revealed = animate && i < shown)
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

/** Notifications, the wallet, and where Goldgrid lives. */
@Composable
private fun SettingsPage(s: UiState, vm: DrawViewModel, onBack: () -> Unit, onIntro: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("←", color = Gold, fontSize = 26.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
        Text("Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
    SettingsCard("Notifications") {
        if (!Notifier.canPost(context)) {
            Text("Notifications are off for Goldgrid on this phone.", color = Danger, fontSize = 14.sp)
            OutlinedButton(onClick = {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }) { Text("Allow notifications") }
        }
        Toggle("Wins", "When your numbers take a pot, with the winning balls.", s.settings.wins, vm::setNotifyWins)
        Toggle("Results", "Every draw you played, even when it was not your round.", s.settings.results, vm::setNotifyResults)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New rounds", color = Color.White, fontWeight = FontWeight.Bold)
            Text("A nudge when a fresh round opens. Rounds come every five minutes, so hourly is gentler.", color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(RoundReminders.Off to "Off", RoundReminders.Hourly to "Hourly", RoundReminders.EveryRound to "Every round").forEach { (mode, label) ->
                    val on = s.settings.rounds == mode
                    Box(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) Gold else CardHigh)
                            .clickable { vm.setRoundReminders(mode) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(label, color = if (on) Ink else Color.White, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) }
                }
            }
        }
        Toggle("Sound & vibration", "The Goldgrid chime. Off keeps notifications silent.", s.settings.sound, vm::setNotifySound)
    }
    SettingsCard("Wallet") {
        val w = s.wallet
        Text(if (w == null) "Not connected" else "${w.take(6)}…${w.takeLast(6)}", color = Color.White, fontSize = 15.sp)
        if (w != null) OutlinedButton(onClick = { vm.disconnect(); onBack() }) { Text("Disconnect wallet") }
    }
    SettingsCard("About") {
        Text("Goldgrid ${BuildConfig.VERSION_NAME} · Solana devnet", color = Muted, fontSize = 13.sp)
        Text("© 2026 The Goldgrid authors · source-available under BUSL 1.1", color = Muted, fontSize = 12.sp)
        listOf(
            "Goldgrid website ↗" to "https://elaris-xyz.github.io/goldgrid/",
            "The program on Solana Explorer ↗" to "https://explorer.solana.com/address/${DrawProgram.PROGRAM_ID.base58()}?cluster=devnet",
            "Source code on GitHub ↗" to "https://github.com/elaris-xyz/goldgrid",
        ).forEach { (label, link) ->
            Text(label, color = Gold, fontSize = 14.sp, modifier = Modifier.clickable { uri.openUri(link) }.padding(vertical = 4.dp))
        }
        Text("Show the intro again", color = Gold, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onIntro).padding(vertical = 4.dp))
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title.uppercase(), color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        content()
    }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(detail, color = Muted, fontSize = 13.sp)
        }
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Gold),
        )
    }
}

/** Every ticket the player has had, newest first, and what it earned. */
@Composable
private fun ActivityPage(
    s: UiState,
    now: Long,
    onBack: () -> Unit,
    onClaim: (MyResult) -> Unit,
    onCollect: () -> Unit,
    onRevealed: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("←", color = Gold, fontSize = 26.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
        Text("Activity", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
    val won = s.results.filter { it.outcome == Outcome.Won || it.outcome == Outcome.Claimed }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("${formatSkr(s.skr ?: 0)} SKR in your wallet", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(
            "Won ${formatSkr(won.sumOf { it.prize })} SKR with ${won.size} of ${s.results.size} ${plural(s.results.size, "ticket")} · " +
                "${won.count { it.outcome == Outcome.Claimed }} paid to your wallet",
            color = Muted, fontSize = 13.sp,
        )
    }
    Results(s, now, onClaim, onCollect, onRevealed, onSeeAll = null, limit = Int.MAX_VALUE)
    WhySkr()
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
            "Play round after round: every 7 rounds in a row adds a ticket, up to 5.",
            "The numbers come from Switchboard randomness on-chain. Nobody can pick them, including us — every draw links to its proof on Solana Explorer.",
            "The best match wins the pot and ties split it; the prize goes straight to the winner's wallet. No match? The pot rolls over.",
            "Entry is free. Each ticket holds a small SOL deposit that comes back to you right after the draw.",
            "This demo runs on Solana devnet with a round every five minutes; prizes are test SKR.",
        ).forEach { Text("• $it", color = Muted, fontSize = 13.sp) }
    }
}

/** Why the prize is SKR and what that makes possible; folded, so it costs one line until asked. */
@Composable
private fun WhySkr() {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
            .clickable { open = !open }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Why prizes are in SKR", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (open) "▴" else "▾", color = Gold, fontSize = 16.sp)
        }
        if (!open) {
            Text("SKR is the Seeker community's own token. Tap to see why we chose it and what it makes possible.", color = Muted, fontSize = 13.sp)
            return@Column
        }
        listOf(
            "It's the Seeker's own token, so prizes stay with the community that plays instead of arriving in a coin nobody here uses.",
            "SKR only ever flows out to winners. You never pay SKR, or anything else, to enter: Goldgrid stays a free draw, not a bet.",
            "Sponsors put the SKR into the program's vault before anyone plays, so the money behind every prize is on-chain and visible.",
            "A prize is a token transfer straight to your wallet, provable on Solana Explorer.",
        ).forEach { Text("• $it", color = Muted, fontSize = 13.sp) }
        Text("What it makes possible next", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        listOf(
            "Sponsored rounds: a dApp or brand funds a pot and its name sits on that round.",
            "Featured draws: a bigger pot for a launch day or a weekend.",
            "Partner prizes: a sponsor's own token paid alongside SKR.",
            "Seeker-only rounds where every ticket is tied to a Seeker Genesis Token.",
            "Streak rewards: playing round after round earns extra SKR, not just extra tickets.",
        ).forEach { Text("• $it", color = Muted, fontSize = 13.sp) }
        Text(
            "On devnet the prize is Test SKR (devnet), a stand-in with no value. On mainnet the same program is set up with the real SKR mint.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun clock(secs: Long) = "%02d:%02d".format(secs / 60, secs % 60)

private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"

/** A draw time (chain seconds) in the device's own date format. */
private fun when_(unixSecs: Long): String =
    java.text.SimpleDateFormat("EEE d MMM · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(unixSecs * 1000))

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
