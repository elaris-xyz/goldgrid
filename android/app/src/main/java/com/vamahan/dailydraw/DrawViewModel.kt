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
import com.vamahan.dailydraw.draw.DrawError
import com.vamahan.dailydraw.draw.DrawProgram
import com.vamahan.dailydraw.draw.DrawRound
import com.vamahan.dailydraw.draw.DrawTicket
import com.vamahan.dailydraw.draw.RoundStatus
import com.vamahan.dailydraw.draw.SeekerState
import com.vamahan.dailydraw.draw.Sgt
import com.vamahan.dailydraw.solana.SolanaRpc
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class Claimable(val ticket: DrawTicket, val round: DrawRound)

/** Who the program counts as "one Seeker": the SGT mint, or the wallet in demo mode. */
data class Identity(val key: String, val sgtTokens: String?)

data class UiState(
    val wallet: String? = null,
    val identity: Identity? = null,
    val lamports: Long? = null,
    val skr: Long? = null,
    /** chain time minus device time, so countdowns follow the program's clock. */
    val clockOffset: Long = 0,
    val config: DrawConfig? = null,
    /** The RPC answered but the program's config account is not there. */
    val programMissing: Boolean = false,
    /** The program that owns the prize mint (classic SPL Token or Token-2022). */
    val tokenProgram: String? = null,
    val roundId: Long = 0,
    val round: DrawRound? = null,
    val lastRound: DrawRound? = null,
    val myTickets: List<DrawTicket> = emptyList(),
    val claimable: List<Claimable> = emptyList(),
    /** Finished tickets with nothing to claim, whose rent can come back. */
    val closable: List<Claimable> = emptyList(),
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
    /** Ticket PDAs by (round, index) for the current identity; derivation is not free. */
    private val ticketAddresses = HashMap<Pair<Long, Int>, String>()

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
        val raw = rpc.accountData(DrawProgram.config().base58())
        if (raw == null) {
            _state.update { it.copy(programMissing = true) }
            return
        }
        val config = DrawConfig.decode(raw)
        val tokenProgram = _state.value.tokenProgram ?: rpc.accountOwner(config.mint.base58())
        val now = _state.value.chainNow()
        val id = config.roundAt(now)
        val (current, previous) = rpc.multipleAccounts(
            listOf(DrawProgram.round(id).base58(), DrawProgram.round(id - 1).base58())
        ).map { data -> data?.let(DrawRound::decode) }
        _state.update {
            it.copy(
                config = config, programMissing = false, tokenProgram = tokenProgram, roundId = id, round = current,
                lastRound = previous ?: it.lastRound.takeIf { r -> r?.id == id - 1 },
            )
        }

        val owner = _state.value.wallet ?: return
        val identity = _state.value.identity ?: resolveIdentity(owner, config) ?: return
        val identityKey = SolanaPublicKey.from(identity.key)

        // Tickets are PDAs of (round, identity, index): read the recent ones
        // directly instead of scanning every account of the program.
        val slots = (id - RECENT_ROUNDS + 1..id).flatMap { r -> (0 until DrawProgram.MAX_TICKETS_PER_ROUND).map { r to it } }
        val addresses = slots.map { key -> ticketAddresses.getOrPut(key) { DrawProgram.ticket(key.first, identityKey, key.second).base58() } }
        val tickets = rpc.multipleAccounts(addresses).zip(addresses)
            .mapNotNull { (data, address) -> data?.let { DrawTicket.decode(address, it) } }
            .filter { it.owner.base58() == owner }
            .sortedByDescending { it.round }
        val roundIds = tickets.map { it.round }.distinct()
        val rounds = rpc.multipleAccounts(roundIds.map { DrawProgram.round(it).base58() })
            .zip(roundIds).mapNotNull { (data, rid) -> data?.let { rid to DrawRound.decode(it) } }.toMap()
        val finished = tickets.mapNotNull { t -> rounds[t.round]?.takeIf { it.status == RoundStatus.Settled }?.let { Claimable(t, it) } }
        val (claimable, closable) = finished.partition { (t, r) -> r.best > 0 && t.matches == r.best && !t.claimed }

        val ownerKey = SolanaPublicKey.from(owner)
        val seeker = rpc.accountData(DrawProgram.seeker(identityKey).base58())?.let(SeekerState::decode)
        val skr = tokenProgram?.let { tp ->
            rpc.tokenBalance(DrawProgram.associatedTokenAccount(ownerKey, config.mint, SolanaPublicKey.from(tp)).base58())
        }
        val lamports = rpc.lamports(owner)
        _state.update {
            it.copy(myTickets = tickets, claimable = claimable, closable = closable, seeker = seeker, skr = skr, lamports = lamports)
        }
    }

    /**
     * Demo mode: the wallet is its own identity. SGT mode: the wallet must hold a
     * Seeker Genesis Token; its mint is the identity and its token account goes
     * into `enter`. Without one the app says so rather than failing at entry.
     */
    private suspend fun resolveIdentity(owner: String, config: DrawConfig): Identity? {
        val identity = if (!config.requireSgt) {
            Identity(owner, null)
        } else {
            val holdings = rpc.tokenHoldings(owner, DrawProgram.TOKEN_2022_PROGRAM.base58())
            val mints = rpc.multipleAccounts(holdings.map { it.mint })
            holdings.zip(mints).firstOrNull { (h, data) ->
                data != null && Sgt.isSeekerGenesisToken(SolanaPublicKey.from(h.mint), data)
            }?.let { (h, _) -> Identity(h.mint, h.account) }
        }
        if (identity == null) {
            _state.update { it.copy(message = DrawError.NotASeeker.userMessage) }
        } else {
            ticketAddresses.clear()
            _state.update { it.copy(identity = identity) }
        }
        return identity
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
                _state.update { it.copy(wallet = Base58.encodeToString(key), identity = null, busy = null) }
                try {
                    refresh(fullClock = true)
                } catch (e: Exception) {
                    _state.update { it.copy(message = "Network: ${e.message?.take(80)}") }
                }
            }
            is TransactionResult.NoWalletFound -> _state.update { it.copy(busy = null, message = "No Solana wallet found. Install Phantom or Solflare.") }
            is TransactionResult.Failure -> _state.update { it.copy(busy = null, message = "Wallet: ${result.e.message}") }
        }
    }

    fun enter(sender: ActivityResultSender) = viewModelScope.launch {
        val s = _state.value
        val owner = s.wallet?.let(SolanaPublicKey::from) ?: return@launch
        val identity = s.identity ?: return@launch
        val picks = s.selection.sorted()
        if (picks.size != DrawProgram.PICKS) return@launch
        val index = s.seeker?.ticketsAllowedIn(s.roundId)?.first ?: 0
        val ok = send(sender, "Entering the draw…", "Ticket entered: ${picks.joinToString(" ")}") {
            listOf(
                DrawProgram.enter(
                    owner, SolanaPublicKey.from(identity.key), identity.sgtTokens?.let(SolanaPublicKey::from),
                    s.roundId, index, picks,
                ),
            )
        }
        // Keep the picks when entering failed, so the player can simply retry.
        if (ok) _state.update { it.copy(selection = emptySet()) }
    }

    fun claim(sender: ActivityResultSender, item: Claimable) = viewModelScope.launch {
        val s = _state.value
        val owner = s.wallet?.let(SolanaPublicKey::from) ?: return@launch
        val mint = s.config?.mint ?: return@launch
        val tokenProgram = s.tokenProgram?.let(SolanaPublicKey::from) ?: return@launch
        val ata = DrawProgram.associatedTokenAccount(owner, mint, tokenProgram)
        send(sender, "Claiming your prize…", "Claimed ${formatSkr(item.round.share)} SKR") {
            listOf(
                DrawProgram.createTokenAccountIdempotent(owner, ata, owner, mint, tokenProgram),
                DrawProgram.claim(owner, item.round.id, SolanaPublicKey.from(item.ticket.address), mint, ata, tokenProgram),
            )
        }
    }

    /** Closes finished tickets with nothing to claim, returning their rent in one signature. */
    fun collect(sender: ActivityResultSender) = viewModelScope.launch {
        val s = _state.value
        val owner = s.wallet?.let(SolanaPublicKey::from) ?: return@launch
        val batch = s.closable.take(MAX_CLOSES_PER_TX)
        if (batch.isEmpty()) return@launch
        send(sender, "Returning ticket SOL…", "Returned the SOL of ${batch.size} ticket(s)") {
            batch.map { (t, r) -> DrawProgram.closeTicket(owner, r.id, SolanaPublicKey.from(t.address)) }
        }
    }

    /** Signs and sends through the wallet; true once the transaction is confirmed. */
    private suspend fun send(
        sender: ActivityResultSender,
        busy: String,
        done: String,
        build: suspend () -> List<TransactionInstruction>,
    ): Boolean {
        _state.update { it.copy(busy = busy, message = null) }
        try {
            val instructions = build()
            val result = wallet.transact(sender) {
                // Fetched once the wallet is open and authorized: a blockhash taken
                // before the approval screen can expire while the person reads it.
                val message = Message.Builder().apply { instructions.forEach { addInstruction(it) } }
                    .setRecentBlockhash(rpc.latestBlockhash()).build()
                signAndSendTransactions(arrayOf(Transaction(message).serialize()))
            }
            val signature = when (result) {
                is TransactionResult.Success -> result.payload.signatures.first()
                is TransactionResult.NoWalletFound -> throw IllegalStateException("No Solana wallet found")
                is TransactionResult.Failure -> throw result.e
            }
            val sig = Base58.encodeToString(signature)
            repeat(30) {
                if (rpc.isConfirmed(sig)) {
                    _state.update { it.copy(busy = null, message = done) }
                    runCatching { refresh(fullClock = false) }
                    return true
                }
                delay(1000)
            }
            _state.update { it.copy(busy = null, message = "Sent, not confirmed yet: ${sig.take(8)}…") }
        } catch (e: Exception) {
            _state.update { it.copy(busy = null, message = friendly(e)) }
        }
        return false
    }

    private fun friendly(e: Exception): String {
        val text = e.message ?: e.toString()
        DrawError.from(text)?.let { return it.userMessage }
        return when {
            "insufficient" in text.lowercase() -> "Not enough devnet SOL for the fee."
            "blockhash" in text.lowercase() -> "The transaction expired before it was sent. Try again."
            else -> text.take(120)
        }
    }

    private companion object {
        /** Rounds of tickets the app watches: a day of demo rounds, three weeks of nightly ones. */
        const val RECENT_ROUNDS = 20L
        const val MAX_CLOSES_PER_TX = 8
    }
}

fun formatSkr(amount: Long): String {
    val whole = amount / 1_000_000
    val frac = amount % 1_000_000
    return if (frac == 0L) "$whole" else "%d.%s".format(whole, frac.toString().padStart(6, '0').trimEnd('0'))
}
