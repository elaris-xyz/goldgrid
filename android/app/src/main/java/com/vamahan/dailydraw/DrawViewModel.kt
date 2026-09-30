package com.vamahan.dailydraw

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.funkatronics.encoders.Base58
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import com.vamahan.dailydraw.draw.DrawConfig
import com.vamahan.dailydraw.draw.DrawError
import com.vamahan.dailydraw.draw.DrawProgram
import com.vamahan.dailydraw.draw.DrawRound
import com.vamahan.dailydraw.draw.DrawTicket
import com.vamahan.dailydraw.draw.MessageCompiler
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Below this a ticket's refundable deposit and the fee may not fit: the app offers the faucet. */
const val LOW_SOL_LAMPORTS = 5_000_000L

private class AccountSwitched(val account: String) : Exception("wallet switched to $account")

/** Who the program counts as "one Seeker": the SGT mint, or the wallet in demo mode. */
data class Identity(val key: String, val sgtTokens: String?)

data class UiState(
    val wallet: String? = null,
    val identity: Identity? = null,
    val skr: Long? = null,
    /** chain time minus device time, so countdowns follow the program's clock. */
    val clockOffset: Long = 0,
    val config: DrawConfig? = null,
    /** The RPC answered but the program's config account is not there. */
    val programMissing: Boolean = false,
    /** Several polls in a row failed; the screen says so instead of showing raw errors. */
    val networkTrouble: Boolean = false,
    /** The program that owns the prize mint (classic SPL Token or Token-2022). */
    val tokenProgram: String? = null,
    val roundId: Long = 0,
    val round: DrawRound? = null,
    val seeker: SeekerState? = null,
    /** The player's tickets and results, newest first, chain merged with the device's memory. */
    val results: List<MyResult> = emptyList(),
    /** Results that became known while the app was open: they get the reveal. */
    val freshlyDrawn: Set<String> = emptySet(),
    val selection: Set<Int> = emptySet(),
    /** Actions waiting for the wallet or the chain: "enter", "collect", or a result key. */
    val pending: Set<String> = emptySet(),
    /** One-shot notice for the snackbar: something worked. */
    val message: String? = null,
    /** Something failed: stays on screen, next to the action, until dismissed or retried. */
    val error: String? = null,
    /** The wallet's SOL, which pays each ticket's refundable deposit and the fee. */
    val lamports: Long? = null,
) {
    fun chainNow() = System.currentTimeMillis() / 1000 + clockOffset
    fun ticketsLeft(): Int {
        val s = seeker ?: return 1
        val (used, allowed) = s.ticketsAllowedIn(roundId)
        return (allowed - used).coerceAtLeast(0)
    }
    fun resultsIn(round: Long) = results.filter { it.round == round }
    val lowSol get() = wallet != null && lamports != null && lamports < LOW_SOL_LAMPORTS
}

class DrawViewModel(app: Application) : AndroidViewModel(app) {
    private val rpc = SolanaRpc(BuildConfig.RPC_URL)
    private val store = ResultStore(app)
    private val session = Session(app)
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
    /** PDAs by (round, index) for the current identity, and the player's token account. */
    private val ticketAddresses = HashMap<Pair<Long, Int>, String>()
    private var tokenAccount: String? = null
    private var configData: ByteArray? = null

    init {
        // Open ready: the last config and chain-clock offset draw the round and the
        // grid at once, and a wallet stays connected until the player disconnects.
        val saved = session.configData?.let { runCatching { DrawConfig.decode(it) }.getOrNull() }
        configData = session.configData
        val wallet = session.wallet
        _state.update {
            it.copy(
                config = saved, tokenProgram = session.tokenProgram, clockOffset = session.clockOffset,
                roundId = saved?.roundAt(System.currentTimeMillis() / 1000 + session.clockOffset) ?: 0,
                wallet = wallet, results = wallet?.let(store::load).orEmpty(),
            )
        }
    }

    fun start() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            var tick = 0
            var failures = 0
            while (isActive) {
                val wait = try {
                    refresh(fullClock = tick % 12 == 0)
                    failures = 0
                    _state.update { it.copy(networkTrouble = false) }
                    POLL_MS
                } catch (e: Exception) {
                    // The public devnet RPC rate-limits by IP (429), and a phone's link
                    // drops: back off quietly and only say so when it persists.
                    failures++
                    Log.w(TAG, "poll failed ($failures)", e)
                    _state.update { it.copy(networkTrouble = failures >= 3) }
                    minOf(POLL_MS shl minOf(failures, 3), 30_000L)
                }
                tick++
                delay(wait)
            }
        }
    }

    fun stop() {
        poller?.cancel()
    }

    /**
     * One poll in (usually) one RPC call: the config, this round and the last,
     * the seeker, the prize token account and the player's recent ticket PDAs all
     * go into a single getMultipleAccounts. Seven calls every four seconds ran
     * straight into devnet's per-IP limit.
     */
    private suspend fun refresh(fullClock: Boolean) {
        if (fullClock) {
            val chain = rpc.chainTime()
            val offset = chain - System.currentTimeMillis() / 1000
            session.clockOffset = offset
            _state.update { it.copy(clockOffset = offset) }
        }
        val known = _state.value.config ?: run {
            val raw = rpc.accountData(DrawProgram.config().base58())
            if (raw == null) {
                _state.update { it.copy(programMissing = true) }
                return
            }
            DrawConfig.decode(raw)
        }
        val tokenProgram = _state.value.tokenProgram ?: rpc.accountOwner(known.mint.base58())?.also { session.tokenProgram = it }
        val now = _state.value.chainNow()
        val id = known.roundAt(now)

        val owner = _state.value.wallet
        val identity = owner?.let { _state.value.identity ?: resolveIdentity(it, known) }
        val identityKey = identity?.let { SolanaPublicKey.from(it.key) }
        if (owner != null && tokenAccount == null && tokenProgram != null) {
            tokenAccount = DrawProgram.associatedTokenAccount(
                SolanaPublicKey.from(owner), known.mint, SolanaPublicKey.from(tokenProgram),
            ).base58()
        }
        val slots = if (identityKey == null) emptyList() else
            (id - WATCHED_ROUNDS + 1..id).flatMap { r -> (0 until DrawProgram.MAX_TICKETS_PER_ROUND).map { r to it } }
        val ticketKeys = slots.map { key ->
            ticketAddresses.getOrPut(key) { DrawProgram.ticket(key.first, identityKey!!, key.second).base58() }
        }
        val configKey = DrawProgram.config().base58()
        val fixed = listOf(
            configKey, DrawProgram.round(id).base58(), DrawProgram.round(id - 1).base58(),
            identityKey?.let { DrawProgram.seeker(it).base58() } ?: configKey,
            tokenAccount ?: configKey,
        )
        val data = rpc.multipleAccounts(fixed + ticketKeys)
        val config = data[0]?.let(DrawConfig::decode) ?: known
        if (data[0] != null && !data[0].contentEquals(configData)) {
            configData = data[0]
            session.configData = data[0]
        }
        val current = data[1]?.let(DrawRound::decode)
        val previous = data[2]?.let(DrawRound::decode)
        _state.update { it.copy(config = config, programMissing = false, tokenProgram = tokenProgram, roundId = id, round = current) }
        if (owner == null || identityKey == null) return

        val seeker = data[3]?.let(SeekerState::decode)
        val skr = if (tokenAccount != null) data[4]?.let { amountOf(it) } ?: 0L else null
        val live = slots.zip(data.drop(fixed.size)).zip(ticketKeys).mapNotNull { (pair, address) ->
            val (slot, raw) = pair
            raw?.let { slot to DrawTicket.decode(address, it) }
        }.filter { (_, t) -> t.owner.base58() == owner }

        // Rounds of live tickets: this and the last are already here, older ones cost one call.
        val rounds = HashMap<Long, DrawRound>()
        current?.let { rounds[id] = it }
        previous?.let { rounds[id - 1] = it }
        // Remembered tickets that are gone were paid or returned by the crank; their
        // round (kept an hour after the draw) says which.
        val watchedFrom = id - WATCHED_ROUNDS + 1
        val liveKeys = live.map { (slot, t) -> "${t.round}:${slot.second}" }.toSet()
        val vanished = _state.value.results.filter { it.ticket != null && it.round >= watchedFrom && it.key !in liveKeys }
        val missing = (live.map { it.second.round } + vanished.map { it.round }).distinct().filter { it !in rounds }
        rpc.multipleAccounts(missing.map { DrawProgram.round(it).base58() }).zip(missing)
            .forEach { (raw, rid) -> raw?.let { rounds[rid] = DrawRound.decode(it) } }

        val onChain = live.map { (slot, t) -> MyResult.of(t, slot.second, rounds[t.round], now, config.drawTs(t.round)) }
        merge(owner, onChain, watchedFrom, rounds, seeker = seeker, skr = skr)
        if (fullClock || _state.value.lamports == null || (_state.value.lamports ?: 0) < LOW_SOL_LAMPORTS) {
            val lamports = rpc.lamports(owner)
            _state.update { it.copy(lamports = lamports) }
        }
    }

    /**
     * Folds what the chain shows into what the device remembers. A remembered
     * ticket that is gone from a watched round was closed: a win that disappears
     * was claimed, anything else had its deposit returned. Results outside the
     * watched window stay as remembered.
     */
    private fun merge(
        owner: String,
        onChain: List<MyResult>,
        watchedFrom: Long,
        rounds: Map<Long, DrawRound>,
        seeker: SeekerState?,
        skr: Long?,
    ) {
        val before = _state.value.results.associateBy { it.key }
        val chainKeys = onChain.map { it.key }.toSet()
        val closed = before.values.filter { it.key !in chainKeys && it.round >= watchedFrom && it.ticket != null }
            .map { r ->
                val round = rounds[r.round]
                when {
                    round?.status == RoundStatus.Settled -> MyResult.closed(r.round, r.index, r.picks, round)
                    else -> r.copy(ticket = null, outcome = if (r.outcome == Outcome.Won) Outcome.Claimed else r.outcome)
                }
            }
        val kept = before.values.filter { it.key !in chainKeys && (it.round < watchedFrom || it.ticket == null) }
        val results = (onChain + closed + kept)
            .sortedWith(compareByDescending<MyResult> { it.round }.thenBy { it.index })
        val fresh = (onChain + closed).filter { r ->
            val was = before[r.key]?.outcome
            (was == Outcome.Waiting || was == Outcome.Drawing) && r.outcome != Outcome.Waiting && r.outcome != Outcome.Drawing
        }.map { it.key }
        if (results != _state.value.results) store.save(owner, results)
        _state.update { it.copy(results = results, seeker = seeker, skr = skr, freshlyDrawn = it.freshlyDrawn + fresh) }
    }

    /** SPL token accounts (classic and Token-2022) keep the amount at byte 64. */
    private fun amountOf(account: ByteArray): Long =
        ByteBuffer.wrap(account, 64, 8).order(ByteOrder.LITTLE_ENDIAN).long

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
            _state.update { it.copy(error = DrawError.NotASeeker.userMessage) }
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
    fun messageShown() = _state.update { it.copy(message = null) }
    fun dismissError() = _state.update { it.copy(error = null) }

    /** Devnet SOL from the public faucet, for a judge whose wallet is empty. */
    fun airdrop() = viewModelScope.launch {
        val owner = _state.value.wallet ?: return@launch
        _state.update { it.copy(pending = it.pending + "airdrop", error = null) }
        try {
            val sig = rpc.requestAirdrop(owner, AIRDROP_LAMPORTS)
            repeat(30) {
                if (rpc.isConfirmed(sig)) {
                    _state.update { it.copy(lamports = rpc.lamports(owner), message = "0.5 devnet SOL added to your wallet.") }
                    return@repeat
                }
                delay(1000)
            }
        } catch (e: Exception) {
            Log.w(TAG, "airdrop failed", e)
            _state.update { it.copy(error = "The devnet faucet is busy. Get free devnet SOL at faucet.solana.com, then come back.") }
        }
        _state.update { it.copy(pending = it.pending - "airdrop") }
    }
    fun revealed(key: String) = _state.update { it.copy(freshlyDrawn = it.freshlyDrawn - key) }

    fun connect(sender: ActivityResultSender) = viewModelScope.launch {
        _state.update { it.copy(pending = it.pending + "connect", error = null) }
        when (val result = wallet.connect(sender)) {
            is TransactionResult.Success -> {
                val key = Base58.encodeToString(result.authResult.accounts.first().publicKey)
                tokenAccount = null
                ticketAddresses.clear()
                session.wallet = key
                _state.update { it.copy(wallet = key, identity = null, results = store.load(key)) }
                runCatching { refresh(fullClock = true) }
            }
            is TransactionResult.NoWalletFound -> _state.update { it.copy(error = "No Solana wallet found. Install Phantom or Solflare.") }
            is TransactionResult.Failure -> _state.update {
                it.copy(error = if (authorizationRefused(result.e)) WALLET_REFUSED else "The wallet did not connect: ${result.e.message}")
            }
        }
        _state.update { it.copy(pending = it.pending - "connect") }
    }

    /** Forgets the wallet on this device; results stay stored per wallet for its return. */
    fun disconnect() {
        session.wallet = null
        tokenAccount = null
        ticketAddresses.clear()
        _state.update { it.copy(wallet = null, identity = null, results = emptyList(), skr = null, lamports = null, seeker = null) }
    }

    private fun authorizationRefused(e: Exception) = "authorization request failed" in (e.message ?: "").lowercase()

    fun enter(sender: ActivityResultSender) = viewModelScope.launch {
        val s = _state.value
        val owner = s.wallet?.let(SolanaPublicKey::from) ?: return@launch
        val identity = s.identity ?: return@launch
        val picks = s.selection.sorted()
        if (picks.size != DrawProgram.PICKS) return@launch
        val index = s.seeker?.ticketsAllowedIn(s.roundId)?.first ?: 0
        val ok = send(sender, "enter", "You're in! Good luck in round #${s.roundId}.") {
            listOf(
                DrawProgram.enter(
                    owner, SolanaPublicKey.from(identity.key), identity.sgtTokens?.let(SolanaPublicKey::from),
                    s.roundId, index, picks,
                ),
            )
        }
        // Keep the picks when entering failed, so the player can simply retry.
        if (ok) {
            _state.update { it.copy(selection = emptySet()) }
            val config = s.config ?: return@launch
            val ticket = DrawProgram.ticket(s.roundId, SolanaPublicKey.from(identity.key), index).base58()
            // Chain time to device time, then a little slack for the crank.
            val at = (config.drawTs(s.roundId) - s.clockOffset) * 1000 + RESULT_SLACK_MS
            ResultAlarm.schedule(getApplication(), s.roundId, index, ticket, picks, at)
        }
    }

    fun claim(sender: ActivityResultSender, item: MyResult) = viewModelScope.launch {
        val s = _state.value
        val owner = s.wallet?.let(SolanaPublicKey::from) ?: return@launch
        val mint = s.config?.mint ?: return@launch
        val tokenProgram = s.tokenProgram?.let(SolanaPublicKey::from) ?: return@launch
        val ticket = item.ticket?.let(SolanaPublicKey::from) ?: return@launch
        val ata = DrawProgram.associatedTokenAccount(owner, mint, tokenProgram)
        val ok = send(sender, item.key, "${formatSkr(item.prize)} SKR is in your wallet.") {
            listOf(
                DrawProgram.createTokenAccountIdempotent(owner, ata, owner, mint, tokenProgram),
                DrawProgram.claim(owner, item.round, ticket, mint, ata, tokenProgram),
            )
        }
        // Show the win as claimed at once; the next poll confirms it from the chain.
        if (ok) settleLocally(item.key, Outcome.Claimed, skrDelta = item.prize)
    }

    /** Closes finished tickets with nothing to claim, returning their deposits in one signature. */
    fun collect(sender: ActivityResultSender) = viewModelScope.launch {
        val s = _state.value
        val owner = s.wallet?.let(SolanaPublicKey::from) ?: return@launch
        val batch = s.results.filter { it.canReturnDeposit }.take(MAX_CLOSES_PER_TX)
        if (batch.isEmpty()) return@launch
        val ok = send(sender, "collect", "Deposit of ${batch.size} ticket(s) returned to your wallet.") {
            batch.map { DrawProgram.closeTicket(owner, it.round, SolanaPublicKey.from(it.ticket!!)) }
        }
        if (ok) batch.forEach { settleLocally(it.key, it.outcome) }
    }

    private fun settleLocally(key: String, outcome: Outcome, skrDelta: Long = 0) {
        val owner = _state.value.wallet ?: return
        _state.update { s ->
            val results = s.results.map { if (it.key == key) it.copy(outcome = outcome, ticket = null) else it }
            store.save(owner, results)
            s.copy(results = results, skr = (s.skr ?: 0) + skrDelta)
        }
    }

    /** Signs and sends through the wallet; true once the transaction is confirmed. */
    private suspend fun send(
        sender: ActivityResultSender,
        pendingKey: String,
        done: String,
        build: suspend () -> List<TransactionInstruction>,
    ): Boolean {
        _state.update { it.copy(pending = it.pending + pendingKey, message = null, error = null) }
        try {
            val instructions = build()
            val payer = SolanaPublicKey.from(_state.value.wallet ?: return false)
            // Phantom speaks the legacy protocol, where a session holding a saved
            // token opens with `reauthorize`, and it refuses that even for a token it
            // issued a minute earlier. Retrying in a second session raced the wallet's
            // closing screen and hung on a black page. Without a token every session
            // opens with a plain `authorize`, which the wallet accepts, in ONE trip.
            wallet.authToken = null
            var blockhash = ""
            // The wallet only signs; the app broadcasts. Phantom's own send went
            // through its servers, which failed from this network, and it reported a
            // signature for a transaction that never reached the chain.
            val result = wallet.transact(sender) { auth ->
                val account = Base58.encodeToString(auth.accounts.first().publicKey)
                if (account != payer.base58()) throw AccountSwitched(account)
                // Fetched once the wallet is open and authorized: a blockhash taken
                // before the approval screen can expire while the person reads it.
                blockhash = rpc.latestBlockhash().first
                val message = MessageCompiler.compile(payer, DrawProgram.computeBudget() + instructions, blockhash)
                signTransactions(arrayOf(Transaction(message).serialize()))
            }
            val signed = when (result) {
                is TransactionResult.Success -> result.payload.signedPayloads.first()
                is TransactionResult.NoWalletFound -> throw IllegalStateException("No Solana wallet found")
                is TransactionResult.Failure -> throw result.e
            }
            // A legacy transaction starts with the signature count, then the signatures.
            val sig = Base58.encodeToString(signed.copyOfRange(1, 65))
            Log.i(TAG, "signed by the wallet: ${android.util.Base64.encodeToString(signed, android.util.Base64.NO_WRAP)}")
            signedBlockhash(signed)?.let { returned ->
                if (returned != blockhash) Log.w(TAG, "wallet replaced the blockhash: sent $blockhash, signed $returned")
            }
            rpc.sendTransaction(signed)
            // Resend until it confirms or its blockhash dies; a dropped packet is
            // routine on a mobile link, a resend of the same bytes cannot land twice.
            // One status check every 2 s: the public endpoint allows 10 calls per method
            // per 10 s, and a 1 s loop alone used the whole budget.
            while (true) {
                repeat(4) {
                    if (rpc.isConfirmed(sig)) {
                        _state.update { it.copy(pending = it.pending - pendingKey, message = done) }
                        return true
                    }
                    delay(2000)
                }
                if (!rpc.isBlockhashValid(blockhash)) break
                runCatching { rpc.sendTransaction(signed) }
            }
            if (rpc.isConfirmed(sig)) {
                _state.update { it.copy(pending = it.pending - pendingKey, message = done) }
                return true
            }
            _state.update { it.copy(error = "The approval took too long and the transaction expired. Please try again.") }
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
            _state.update { it.copy(error = friendly(e)) }
        }
        _state.update { it.copy(pending = it.pending - pendingKey) }
        return false
    }

    /** The recent blockhash inside a signed single-signer legacy transaction. */
    private fun signedBlockhash(signed: ByteArray): String? = runCatching {
        var i = 1 + 64 * signed[0].toInt() + 3 // signatures, then the 3-byte message header
        var keys = 0
        var shift = 0
        while (true) { // compact-u16 account count
            val b = signed[i++].toInt() and 0xff
            keys = keys or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        i += 32 * keys
        Base58.encodeToString(signed.copyOfRange(i, i + 32))
    }.getOrNull()

    private fun friendly(e: Exception): String {
        val text = e.message ?: e.toString()
        DrawError.from(text)?.let { return it.userMessage }
        return when {
            "insufficient" in text.lowercase() -> "Not enough devnet SOL for the fee."
            // Match the RPC's own words: a simulation error carries "replacementBlockhash"
            // in its payload, and a bare "blockhash" match called a program error expired.
            "blockhash not found" in text.lowercase() -> "The transaction expired before it was sent. Try again."
            authorizationRefused(e) -> WALLET_REFUSED
            e is AccountSwitched -> {
                session.wallet = e.account
                tokenAccount = null
                ticketAddresses.clear()
                _state.update { it.copy(wallet = e.account, identity = null, results = store.load(e.account)) }
                "Your wallet is now on account ${e.account.take(4)}…${e.account.takeLast(4)}. The app switched to it — try again."
            }
            // The wallet ended the session without an answer (closed, backgrounded,
            // or it gave up): nothing was signed and nothing was sent.
            e is java.util.concurrent.CancellationException ->
                "The wallet closed before signing, so nothing was sent. Try again."
            "429" in text || "too many requests" in text.lowercase() ->
                "Solana devnet is busy right now. Wait a few seconds and try again."
            else -> text.take(120)
        }
    }

    private companion object {
        const val TAG = "DailyDraw"
        const val POLL_MS = 5_000L
        /** The crank needs a minute or two after draw time: commit, oracle reveal, scoring. */
        const val RESULT_SLACK_MS = 120_000L
        const val AIRDROP_LAMPORTS = 500_000_000L
        /** Rounds whose tickets each poll reads: 16 minutes of demo rounds, 8 nights of real
         * ones. Older results come from the device's memory. */
        const val WATCHED_ROUNDS = 8L
        const val MAX_CLOSES_PER_TX = 8
        /** The wallet said no without saying why: most often a wallet still on mainnet. */
        const val WALLET_REFUSED = "The wallet refused. In Phantom turn on Settings → Developer Settings → Testnet Mode (Solana Devnet), then try again."
    }
}

fun formatSkr(amount: Long): String {
    val whole = amount / 1_000_000
    val frac = amount % 1_000_000
    return if (frac == 0L) "$whole" else "%d.%s".format(whole, frac.toString().padStart(6, '0').trimEnd('0'))
}
