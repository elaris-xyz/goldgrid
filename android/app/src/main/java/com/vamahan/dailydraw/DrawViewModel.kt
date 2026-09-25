package com.vamahan.dailydraw

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funkatronics.encoders.Base58
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import com.vamahan.dailydraw.draw.DrawConfig
import com.vamahan.dailydraw.draw.DrawProgram
import com.vamahan.dailydraw.draw.DrawRound
import com.vamahan.dailydraw.draw.DrawTicket
import com.vamahan.dailydraw.draw.RoundStatus
import com.vamahan.dailydraw.draw.SeekerState
import com.vamahan.dailydraw.solana.SolanaRpc
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class Claimable(val ticket: DrawTicket, val round: DrawRound)

data class UiState(
    val wallet: String? = null,
    val lamports: Long? = null,
    val skr: Long? = null,
    /** chain time minus device time, so countdowns follow the program's clock. */
    val clockOffset: Long = 0,
    val config: DrawConfig? = null,
    val roundId: Long = 0,
    val round: DrawRound? = null,
    val lastRound: DrawRound? = null,
    val myTickets: List<DrawTicket> = emptyList(),
    val claimable: List<Claimable> = emptyList(),
    val seeker: SeekerState? = null,
    val selection: Set<Int> = emptySet(),
    val busy: String? = null,
    val message: String? = null,
) {
    fun chainNow() = System.currentTimeMillis() / 1000 + clockOffset
    fun ticketsInRound(round: Long) = myTickets.filter { it.round == round }
    fun ticketsLeft(): Int {
        val s = seeker ?: return 1
        val (used, allowed) = s.ticketsAllowedIn(roundId)
        return (allowed - used).coerceAtLeast(0)
    }
}

class DrawViewModel : ViewModel() {
    private val rpc = SolanaRpc(BuildConfig.RPC_URL)
    private val wallet = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = Uri.parse("https://github.com/vamahan"),
            iconUri = Uri.parse("favicon.ico"),
            identityName = "Daily Draw",
        ),
    ).apply { blockchain = Solana.Devnet }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var poller: Job? = null

    fun start() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                try {
                    refresh(fullClock = tick % 10 == 0)
                } catch (e: Exception) {
                    _state.update { it.copy(message = "Network: ${e.message?.take(80)}") }
                }
                tick++
                delay(4000)
            }
        }
    }

    fun stop() {
        poller?.cancel()
    }

    private suspend fun refresh(fullClock: Boolean) {
        if (fullClock) {
            val chain = rpc.chainTime()
            _state.update { it.copy(clockOffset = chain - System.currentTimeMillis() / 1000) }
        }
        val config = DrawConfig.decode(rpc.accountData(DrawProgram.config().base58()) ?: return)
        val now = _state.value.chainNow()
        val id = config.roundAt(now)
        val (current, previous) = rpc.multipleAccounts(
            listOf(DrawProgram.round(id).base58(), DrawProgram.round(id - 1).base58())
        ).map { data -> data?.let(DrawRound::decode) }
        _state.update { it.copy(config = config, roundId = id, round = current, lastRound = previous ?: it.lastRound.takeIf { r -> r?.id == id - 1 }) }

        val owner = _state.value.wallet ?: return
        val ownerKey = SolanaPublicKey.from(owner)
        // Ticket layout: 8-byte discriminator, round (u64), then the owner.
        val tickets = rpc.programAccounts(DrawProgram.PROGRAM_ID.base58(), 16, owner)
            .map { (address, data) -> DrawTicket.decode(address, data) }
            .sortedByDescending { it.round }
        val roundIds = tickets.map { it.round }.distinct().take(20)
        val rounds = rpc.multipleAccounts(roundIds.map { DrawProgram.round(it).base58() })
            .zip(roundIds).mapNotNull { (data, rid) -> data?.let { rid to DrawRound.decode(it) } }.toMap()
        val claimable = tickets.mapNotNull { t ->
            val r = rounds[t.round] ?: return@mapNotNull null
            if (r.status == RoundStatus.Settled && r.best > 0 && t.matches == r.best && !t.claimed) Claimable(t, r) else null
        }
        val seeker = rpc.accountData(DrawProgram.seeker(ownerKey).base58())?.let(SeekerState::decode)
        val ata = DrawProgram.associatedTokenAccount(ownerKey, config.mint).base58()
        val skr = rpc.tokenBalance(ata)
        val lamports = rpc.lamports(owner)
        _state.update { it.copy(myTickets = tickets, claimable = claimable, seeker = seeker, skr = skr, lamports = lamports) }
    }

    fun toggle(number: Int) = _state.update {
        val sel = it.selection
        it.copy(selection = when {
            number in sel -> sel - number
            sel.size < DrawProgram.PICKS -> sel + number
            else -> sel
        })
    }

    fun quickPick() = _state.update {
        it.copy(selection = (1..DrawProgram.MAX_NUMBER).shuffled().take(DrawProgram.PICKS).toSet())
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun connect(sender: ActivityResultSender) = viewModelScope.launch {
        _state.update { it.copy(busy = "Opening wallet…", message = null) }
        when (val result = wallet.connect(sender)) {
            is TransactionResult.Success -> {
                val key = result.authResult.accounts.first().publicKey
                _state.update { it.copy(wallet = Base58.encodeToString(key), busy = null) }
                runCatching { refresh(fullClock = true) }
            }
            is TransactionResult.NoWalletFound -> _state.update { it.copy(busy = null, message = "No Solana wallet found. Install Phantom or Solflare.") }
            is TransactionResult.Failure -> _state.update { it.copy(busy = null, message = "Wallet: ${result.e.message}") }
        }
    }

    fun enter(sender: ActivityResultSender) = viewModelScope.launch {
        val s = _state.value
        val owner = s.wallet?.let(SolanaPublicKey::from) ?: return@launch
        val picks = s.selection.sorted()
        if (picks.size != DrawProgram.PICKS) return@launch
        val index = s.seeker?.ticketsAllowedIn(s.roundId)?.first ?: 0
        send(sender, "Entering the draw…", "Ticket entered: ${picks.joinToString(" ")}") {
            listOf(DrawProgram.enter(owner, s.roundId, index, picks))
        }
        _state.update { it.copy(selection = emptySet()) }
    }

    fun claim(sender: ActivityResultSender, item: Claimable) = viewModelScope.launch {
        val s = _state.value
        val owner = s.wallet?.let(SolanaPublicKey::from) ?: return@launch
        val mint = s.config?.mint ?: return@launch
        val ata = DrawProgram.associatedTokenAccount(owner, mint)
        send(sender, "Claiming your prize…", "Claimed ${formatSkr(item.round.share)} SKR") {
            listOf(
                DrawProgram.createTokenAccountIdempotent(owner, ata, owner, mint),
                DrawProgram.claim(owner, item.round.id, SolanaPublicKey.from(item.ticket.address), mint, ata),
            )
        }
    }

    private suspend fun send(sender: ActivityResultSender, busy: String, done: String, build: suspend () -> List<TransactionInstruction>) {
        _state.update { it.copy(busy = busy, message = null) }
        try {
            val instructions = build()
            val blockhash = rpc.latestBlockhash()
            val message = Message.Builder().apply { instructions.forEach { addInstruction(it) } }
                .setRecentBlockhash(blockhash).build()
            val tx = Transaction(message)
            val result = wallet.transact(sender) { signAndSendTransactions(arrayOf(tx.serialize())) }
            val signature = when (result) {
                is TransactionResult.Success -> result.payload.signatures.first()
                is TransactionResult.NoWalletFound -> throw IllegalStateException("No Solana wallet found")
                is TransactionResult.Failure -> throw result.e
            }
            val sig = Base58.encodeToString(signature)
            repeat(30) {
                if (rpc.isConfirmed(sig)) {
                    _state.update { it.copy(busy = null, message = done) }
                    refresh(fullClock = false)
                    return
                }
                delay(1000)
            }
            _state.update { it.copy(busy = null, message = "Sent, not confirmed yet: ${sig.take(8)}…") }
        } catch (e: Exception) {
            _state.update { it.copy(busy = null, message = friendly(e)) }
        }
    }

    private fun friendly(e: Exception): String {
        val text = e.message ?: e.toString()
        return when {
            // Anchor custom errors start at 6000 (0x1770), in DrawError's declaration order.
            "0x1778" in text || "TicketLimit" in text -> "No tickets left this round. A 7-day streak earns another."
            "0x1774" in text || "EntriesClosed" in text -> "Entries for this round are closed."
            "0x1773" in text || "NotCurrentRound" in text -> "That round is over. Try again for the new one."
            "0x1777" in text -> "Ticket count changed. Try again."
            "insufficient" in text.lowercase() -> "Not enough devnet SOL for the fee."
            else -> text.take(120)
        }
    }
}

fun formatSkr(amount: Long): String {
    val whole = amount / 1_000_000
    val frac = amount % 1_000_000
    return if (frac == 0L) "$whole" else "%d.%s".format(whole, frac.toString().padStart(6, '0').trimEnd('0'))
}
