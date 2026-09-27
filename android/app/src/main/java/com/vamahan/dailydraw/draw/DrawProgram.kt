package com.vamahan.dailydraw.draw

import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The on-chain daily_draw program: its addresses, account layouts and the
 * instructions the app sends. Layouts mirror programs/daily_draw/src/lib.rs;
 * DrawProgramTest checks the discriminators against the IDL.
 */
object DrawProgram {
    val PROGRAM_ID = SolanaPublicKey.from("gvd3fv3QgWvTMzLfxN2HBKkspAeVwzGBCZkW9ixaucM")
    val SYSTEM_PROGRAM = SolanaPublicKey.from("11111111111111111111111111111111")
    val TOKEN_PROGRAM = SolanaPublicKey.from("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val TOKEN_2022_PROGRAM = SolanaPublicKey.from("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
    val ASSOCIATED_TOKEN_PROGRAM = SolanaPublicKey.from("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
    val SGT_GROUP = SolanaPublicKey.from("GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te")

    const val PICKS = 5
    const val MAX_NUMBER = 85
    const val MAX_TICKETS_PER_ROUND = 5
    const val UNSCORED = 255

    /** Anchor's discriminator: the first 8 bytes of sha256("<namespace>:<name>"). */
    fun discriminator(namespace: String, name: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("$namespace:$name".toByteArray()).copyOf(8)

    private fun u64(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private suspend fun pda(vararg seeds: ByteArray): SolanaPublicKey =
        ProgramDerivedAddress.find(seeds.toList(), PROGRAM_ID).getOrThrow()

    suspend fun config() = pda("config".toByteArray())
    suspend fun vault() = pda("vault".toByteArray())
    suspend fun round(id: Long) = pda("round".toByteArray(), u64(id))
    suspend fun seeker(identity: SolanaPublicKey) = pda("seeker".toByteArray(), identity.bytes)
    suspend fun ticket(round: Long, identity: SolanaPublicKey, index: Int) =
        pda("ticket".toByteArray(), u64(round), identity.bytes, byteArrayOf(index.toByte()))

    /** The owner's account for `mint` under `tokenProgram`, which is whichever program owns the mint. */
    suspend fun associatedTokenAccount(owner: SolanaPublicKey, mint: SolanaPublicKey, tokenProgram: SolanaPublicKey): SolanaPublicKey =
        ProgramDerivedAddress.find(listOf(owner.bytes, tokenProgram.bytes, mint.bytes), ASSOCIATED_TOKEN_PROGRAM).getOrThrow()

    /**
     * Enters a ticket. `identity` is the SGT mint when the draw requires a
     * Seeker (then `sgtTokens` is the player's token account holding it), or the
     * wallet itself in demo mode, where the optional sgt_tokens account is passed
     * as the program id, Anchor's "none".
     */
    suspend fun enter(
        player: SolanaPublicKey,
        identity: SolanaPublicKey,
        sgtTokens: SolanaPublicKey?,
        roundId: Long,
        index: Int,
        picks: List<Int>,
    ): TransactionInstruction {
        require(picks.size == PICKS)
        val data = discriminator("global", "enter") + u64(roundId) + byteArrayOf(index.toByte()) +
            picks.map { it.toByte() }.toByteArray()
        return TransactionInstruction(
            PROGRAM_ID,
            listOf(
                AccountMeta(player, true, true),
                AccountMeta(config(), false, true),
                AccountMeta(identity, false, false),
                AccountMeta(sgtTokens ?: PROGRAM_ID, false, false),
                AccountMeta(round(roundId), false, true),
                AccountMeta(seeker(identity), false, true),
                AccountMeta(ticket(roundId, identity, index), false, true),
                AccountMeta(SYSTEM_PROGRAM, false, false),
            ),
            data,
        )
    }

    /** Creates the owner's token account if it does not exist yet (idempotent). */
    fun createTokenAccountIdempotent(
        payer: SolanaPublicKey,
        ata: SolanaPublicKey,
        owner: SolanaPublicKey,
        mint: SolanaPublicKey,
        tokenProgram: SolanaPublicKey,
    ) = TransactionInstruction(
        ASSOCIATED_TOKEN_PROGRAM,
        listOf(
            AccountMeta(payer, true, true),
            AccountMeta(ata, false, true),
            AccountMeta(owner, false, false),
            AccountMeta(mint, false, false),
            AccountMeta(SYSTEM_PROGRAM, false, false),
            AccountMeta(tokenProgram, false, false),
        ),
        byteArrayOf(1),
    )

    /** Pays a winning ticket and closes it; the owner gets the ticket's rent back too. */
    suspend fun claim(
        owner: SolanaPublicKey,
        roundId: Long,
        ticket: SolanaPublicKey,
        mint: SolanaPublicKey,
        ownerTokens: SolanaPublicKey,
        tokenProgram: SolanaPublicKey,
    ) = TransactionInstruction(
        PROGRAM_ID,
        listOf(
            AccountMeta(owner, true, true),
            AccountMeta(config(), false, false),
            AccountMeta(round(roundId), false, true),
            AccountMeta(ticket, false, true),
            AccountMeta(mint, false, false),
            AccountMeta(vault(), false, true),
            AccountMeta(ownerTokens, false, true),
            AccountMeta(tokenProgram, false, false),
        ),
        discriminator("global", "claim") + u64(roundId),
    )

    /** Closes a finished, non-winning (or already paid) ticket for its rent. */
    suspend fun closeTicket(owner: SolanaPublicKey, roundId: Long, ticket: SolanaPublicKey) =
        TransactionInstruction(
            PROGRAM_ID,
            listOf(
                AccountMeta(owner, true, true),
                AccountMeta(round(roundId), false, true),
                AccountMeta(ticket, false, true),
            ),
            discriminator("global", "close_ticket") + u64(roundId),
        )
}

/**
 * The program's DrawError, in declaration order: Anchor numbers custom errors
 * from 6000 in that order, and that number is all a failed transaction reports.
 */
enum class DrawError(val userMessage: String) {
    BadConfig("The draw is misconfigured."),
    ZeroAmount("Amount must be greater than zero."),
    Overflow("Arithmetic overflow."),
    NotCurrentRound("That round is over. Try again for the new one."),
    EntriesClosed("Entries for this round are closed."),
    InvalidPicks("Pick 5 different numbers from 1 to 85."),
    NotASeeker("This draw needs the Seeker Genesis Token in the connected wallet."),
    WrongTicketIndex("Your ticket count changed. Try again."),
    TicketLimit("No tickets left this round. A 7-day streak earns another."),
    WrongStatus("The round is not ready for that yet."),
    TooEarly("The draw time has not come yet."),
    BadRandomness("The draw's randomness did not check out."),
    StaleCommit("The draw was not committed in time. Try again."),
    NotRevealed("The draw is not revealed yet."),
    BadTicket("That ticket is not from this round."),
    NotAWinner("This ticket did not win."),
    AlreadyClaimed("Already claimed."),
    RandomnessExpired("The draw's randomness changed after commit and was refused."),
    RevealPending("The committed draw can still be revealed."),
    UnclaimedPrize("Claim your prize first; claiming also returns the ticket's SOL."),
    TicketsOpen("Some tickets of this round are still open."),
    NotCreator("Only the round's creator gets its rent back."),
    NotUpgradeAuthority("Only the program's upgrade authority can initialize.");

    companion object {
        private const val FIRST_CODE = 6000
        private val custom = Regex("\"Custom\"\\s*:\\s*(\\d+)")
        private val hex = Regex("custom program error: 0x([0-9a-fA-F]+)")

        /**
         * Finds the program error in a failure message: `{"Custom":6008}` from a
         * landed transaction, `0x1778` from a simulation, or the error's name.
         */
        fun from(message: String): DrawError? {
            val code = custom.find(message)?.groupValues?.get(1)?.toIntOrNull()
                ?: hex.find(message)?.groupValues?.get(1)?.toIntOrNull(16)
            if (code != null) return entries.getOrNull(code - FIRST_CODE)
            return entries.firstOrNull { message.contains(it.name) }
        }
    }
}

/** Little-endian reader over an Anchor account, after checking its discriminator. */
private class Reader(data: ByteArray, account: String) {
    private val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    init {
        val expected = DrawProgram.discriminator("account", account)
        val actual = ByteArray(8).also { buf.get(it) }
        require(actual.contentEquals(expected)) { "not a $account account" }
    }

    fun u8() = buf.get().toInt() and 0xff
    fun bool() = u8() != 0
    fun u32() = buf.int.toLong() and 0xffffffffL
    fun u64() = buf.long
    fun key() = SolanaPublicKey(ByteArray(32).also { buf.get(it) })
    fun picks() = List(DrawProgram.PICKS) { u8() }
}

data class DrawConfig(
    val mint: SolanaPublicKey,
    val genesisTs: Long,
    val roundSecs: Long,
    val entrySecs: Long,
    val perTicketBonus: Long,
    val sponsorBudget: Long,
    val carry: Long,
    val requireSgt: Boolean,
) {
    fun roundAt(chainNow: Long): Long = (chainNow - genesisTs).floorDiv(roundSecs)
    fun closeTs(round: Long) = genesisTs + round * roundSecs + entrySecs
    fun drawTs(round: Long) = genesisTs + (round + 1) * roundSecs

    companion object {
        fun decode(data: ByteArray) = Reader(data, "Config").run {
            key() // admin
            DrawConfig(
                mint = key(), genesisTs = u64(), roundSecs = u64(), entrySecs = u64(),
                perTicketBonus = u64(), sponsorBudget = u64(), carry = u64(), requireSgt = bool(),
            )
        }
    }
}

enum class RoundStatus { Open, Committed, Revealed, Settled }

data class DrawRound(
    val id: Long,
    val closeTs: Long,
    val drawTs: Long,
    val pot: Long,
    val tickets: Long,
    val scored: Long,
    val best: Int,
    val winners: Long,
    val share: Long,
    val winning: List<Int>,
    val status: RoundStatus,
    val openTickets: Long,
) {
    companion object {
        fun decode(data: ByteArray) = Reader(data, "Round").run {
            val id = u64(); val close = u64(); val draw = u64(); val pot = u64()
            val tickets = u32(); val scored = u32(); val best = u8(); val winners = u32(); val share = u64()
            key(); u64() // randomness, commit_slot
            val winning = picks()
            val status = RoundStatus.entries[u8()]
            key() // creator
            val open = u32()
            DrawRound(id, close, draw, pot, tickets, scored, best, winners, share, winning, status, open)
        }
    }
}

data class DrawTicket(
    val address: String,
    val round: Long,
    val owner: SolanaPublicKey,
    val picks: List<Int>,
    val matches: Int,
    val claimed: Boolean,
) {
    val scored get() = matches != DrawProgram.UNSCORED

    companion object {
        fun decode(address: String, data: ByteArray) = Reader(data, "Ticket").run {
            val round = u64(); val owner = key(); key() // identity
            DrawTicket(address, round, owner, picks(), u8(), bool())
        }
    }
}

data class SeekerState(val lastRound: Long, val entered: Boolean, val streak: Long, val ticketsInRound: Int) {
    /** Mirrors logic::next_streak and tickets_allowed in the program. */
    fun ticketsAllowedIn(round: Long): Pair<Int, Int> {
        val streak = when {
            !entered -> 1L
            lastRound == round -> streak
            lastRound + 1 == round -> streak + 1
            else -> 1L
        }
        val used = if (entered && lastRound == round) ticketsInRound else 0
        val allowed = minOf(5L, 1 + (streak - 1) / 7).toInt()
        return used to allowed
    }

    companion object {
        fun decode(data: ByteArray) = Reader(data, "Seeker").run {
            key(); key() // identity, owner
            val entered = bool(); val last = u64(); val streak = u32(); val used = u8()
            SeekerState(last, entered, streak, used)
        }
    }
}
