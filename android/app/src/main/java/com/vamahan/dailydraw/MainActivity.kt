package com.vamahan.dailydraw

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.vamahan.dailydraw.draw.DrawProgram
import com.vamahan.dailydraw.draw.DrawRound
import com.vamahan.dailydraw.draw.RoundStatus
import kotlinx.coroutines.delay
import kotlin.math.sqrt

private val Gold = Color(0xFFF5C451)
private val Ink = Color(0xFF0E0F13)
private val Card = Color(0xFF1A1C23)
private val Muted = Color(0xFF8A8F9C)
private val Win = Color(0xFF4ADE80)

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
    ShakeToPick { vm.quickPick() }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(s, onConnect = { vm.connect(sender) })
            TonightCard(s, now)
            s.message?.let {
                Text(it, color = Gold, fontSize = 13.sp, modifier = Modifier.clickable { vm.dismissMessage() })
            }
            s.busy?.let { Text(it, color = Muted, fontSize = 13.sp) }
            val open = s.config != null && now < (s.config?.closeTs(s.roundId) ?: 0)
            NumberGrid(s.selection, enabled = open) { vm.toggle(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { vm.quickPick() }, enabled = open, modifier = Modifier.weight(1f)) { Text("Quick pick") }
                OutlinedButton(onClick = { vm.clearSelection() }, enabled = s.selection.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Clear") }
            }
            val left = s.ticketsLeft()
            Button(
                onClick = { if (s.wallet == null) vm.connect(sender) else vm.enter(sender) },
                enabled = s.busy == null && (s.wallet == null || (s.identity != null && open && s.selection.size == DrawProgram.PICKS && left > 0)),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    when {
                        s.wallet == null -> "Connect wallet"
                        s.identity == null && s.config?.requireSgt == true -> "Needs a Seeker Genesis Token"
                        !open -> "Entries closed — next round soon"
                        left == 0 -> "No tickets left this round"
                        s.selection.size < DrawProgram.PICKS -> "Pick ${DrawProgram.PICKS - s.selection.size} more"
                        else -> "Enter tonight's draw — free"
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
            if (s.wallet != null) {
                Text("Tickets left this round: $left · streak ${s.seeker?.streak ?: 0}", color = Muted, fontSize = 12.sp)
            }
            MyTickets(s)
            s.claimable.forEach { c ->
                Button(
                    onClick = { vm.claim(sender, c) },
                    enabled = s.busy == null,
                    colors = ButtonDefaults.buttonColors(containerColor = Win, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Claim ${formatSkr(c.round.share)} SKR from round #${c.round.id}", fontWeight = FontWeight.Bold) }
            }
            if (s.closable.isNotEmpty()) {
                OutlinedButton(
                    onClick = { vm.collect(sender) },
                    enabled = s.busy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Get back the SOL of ${s.closable.size} finished ticket(s)") }
            }
            LastDraw(s)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Header(s: UiState, onConnect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Daily Draw", color = Gold, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text("Free for Seeker owners · devnet", color = Muted, fontSize = 12.sp)
        }
        val w = s.wallet
        Box(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, Gold.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .clickable(enabled = w == null) { onConnect() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                if (w == null) "Connect" else "${w.take(4)}…${w.takeLast(4)} · ${s.skr?.let(::formatSkr) ?: "0"} SKR",
                color = Color.White, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun TonightCard(s: UiState, now: Long) {
    val config = s.config
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (config == null) {
            Text("Connecting to Solana…", color = Muted)
            return@Column
        }
        val close = config.closeTs(s.roundId)
        val draw = config.drawTs(s.roundId)
        val (label, secs) = when {
            now < close -> "Entries close in" to close - now
            now < draw -> "Draw in" to draw - now
            else -> "Drawing" to 0L
        }
        Text("Round #${s.roundId}", color = Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("%02d:%02d".format(secs / 60, secs % 60), color = Gold, fontSize = 34.sp, fontWeight = FontWeight.Black)
        }
        val pot = s.round?.pot ?: config.carry
        Text("Pot ${formatSkr(pot)} SKR · ${s.round?.tickets ?: 0} tickets", color = Color.White, fontSize = 15.sp)
        Text("Every ticket adds ${formatSkr(config.perTicketBonus)} SKR from the sponsor. Entry is free.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun NumberGrid(selection: Set<Int>, enabled: Boolean, onToggle: (Int) -> Unit) {
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
                            .clickable(enabled = enabled) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onToggle(n)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$n", color = if (on) Ink else if (enabled) Color.White else Muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                repeat(9 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Balls(numbers: List<Int>, highlight: Set<Int> = emptySet()) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        numbers.forEach { n ->
            val hit = n in highlight
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (hit) Win else Card).border(1.dp, Gold.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("$n", color = if (hit) Ink else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun MyTickets(s: UiState) {
    val mine = s.ticketsInRound(s.roundId)
    if (mine.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your tickets in round #${s.roundId}", color = Color.White, fontWeight = FontWeight.Bold)
        mine.forEach { Balls(it.picks) }
    }
}

@Composable
private fun LastDraw(s: UiState) {
    val r: DrawRound = s.lastRound ?: return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Last draw · round #${r.id}", color = Color.White, fontWeight = FontWeight.Bold)
        when (r.status) {
            RoundStatus.Open, RoundStatus.Committed -> Text("Waiting for the draw to run…", color = Muted)
            else -> {
                Balls(r.winning)
                Text(
                    if (r.status != RoundStatus.Settled) "Counting tickets…"
                    else if (r.best == 0) "Nobody matched — ${formatSkr(r.pot)} SKR carries to the next round"
                    else "Best match ${r.best} · ${r.winners} winner(s) · ${formatSkr(r.share)} SKR each",
                    color = Muted, fontSize = 13.sp,
                )
                s.ticketsInRound(r.id).forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Balls(t.picks, highlight = r.winning.toSet())
                        Spacer(Modifier.width(8.dp))
                        if (t.scored) Text("${t.matches} ✓", color = if (t.matches > 0 && t.matches == r.best) Win else Muted)
                    }
                }
            }
        }
    }
}

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
