package com.vamahan.dailydraw.draw

import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** The app hand-encodes Anchor instructions; these pin them to the program's IDL. */
class DrawProgramTest {
    private fun ints(vararg b: Int) = b.map { it.toByte() }.toByteArray()

    @Test
    fun instructionDiscriminatorsMatchTheIdl() {
        assertArrayEquals(ints(139, 49, 209, 114, 88, 91, 77, 134), DrawProgram.discriminator("global", "enter"))
        assertArrayEquals(ints(62, 198, 214, 193, 213, 159, 108, 210), DrawProgram.discriminator("global", "claim"))
        assertArrayEquals(ints(66, 209, 114, 197, 75, 27, 182, 117), DrawProgram.discriminator("global", "close_ticket"))
    }

    @Test
    fun accountDiscriminatorsMatchTheIdl() {
        assertArrayEquals(ints(155, 12, 170, 224, 30, 250, 204, 130), DrawProgram.discriminator("account", "Config"))
        assertArrayEquals(ints(87, 127, 165, 51, 73, 78, 116, 174), DrawProgram.discriminator("account", "Round"))
        assertArrayEquals(ints(41, 228, 24, 165, 78, 90, 235, 200), DrawProgram.discriminator("account", "Ticket"))
        assertArrayEquals(ints(106, 201, 97, 118, 1, 110, 224, 133), DrawProgram.discriminator("account", "Seeker"))
    }

    @Test
    fun ticketAllowanceMirrorsTheProgram() {
        fun s(last: Long, streak: Long, used: Int) = SeekerState(last, true, streak, used)
        assertEquals(0 to 1, SeekerState(0, false, 0, 0).ticketsAllowedIn(10))
        assertEquals(1 to 1, s(10, 1, 1).ticketsAllowedIn(10)) // used its one ticket today
        assertEquals(0 to 2, s(9, 7, 1).ticketsAllowedIn(10))  // day 8 of a streak: two tickets
        assertEquals(0 to 1, s(5, 30, 3).ticketsAllowedIn(10)) // streak broken
        assertEquals(0 to 5, s(9, 100, 5).ticketsAllowedIn(10))
    }

    /** Reference addresses computed by the Node client (cli/lib.mjs), which the devnet e2e test uses. */
    @Test
    fun pdasMatchTheNodeClient() = runBlocking {
        val who = SolanaPublicKey.from("CBCxy5PknwBZK8awoc3y54r5TTqAZEB7RYrGEtWHh3iL")
        assertEquals("uXuUWGvgKzYrgmTrCoXEZ9W1sczs9kDSvtXSkGqL8yZ", DrawProgram.config().base58())
        assertEquals("63Kb4wzsdRFEAsXq4teSdVr5uzUzvAuh9LBvfQhbWs6v", DrawProgram.vault().base58())
        assertEquals("7CB3ARfrHWxBcAJmDHjbPB9T72yvo45TTHKpyvWZpU8j", DrawProgram.round(3).base58())
        assertEquals("E5e7uixCyyXAxTxUzLxm59KWKwqF1qKQFkey8pPyiYoh", DrawProgram.seeker(who).base58())
        assertEquals("341P856TkVi7Q7HtUsu5ANyNM8327J4VCWMzJ5yoqLhh", DrawProgram.ticket(3, who, 1).base58())
    }

    @Test
    fun roundTimesFollowTheConfig() {
        val c = DrawConfig(DrawProgram.PROGRAM_ID, genesisTs = 1000, roundSecs = 120, entrySecs = 90, perTicketBonus = 0, sponsorBudget = 0, carry = 0, requireSgt = false)
        assertEquals(0L, c.roundAt(1119))
        assertEquals(1L, c.roundAt(1120))
        assertEquals(1090L, c.closeTs(0))
        assertEquals(1120L, c.drawTs(0))
    }

    /** Round in the program's field order, including the fields the audit added. */
    @Test
    fun roundDecodesTheCurrentLayout() {
        val buf = ByteBuffer.allocate(8 + 8 * 4 + 4 + 4 + 1 + 4 + 8 + 32 + 8 + 5 + 1 + 32 + 4 + 1).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(DrawProgram.discriminator("account", "Round"))
        buf.putLong(42).putLong(1090).putLong(1120).putLong(9_000_000) // id, close, draw, pot
        buf.putInt(3).putInt(3).put(1).putInt(2).putLong(4_500_000)    // tickets, scored, best, winners, share
        buf.put(ByteArray(32)).putLong(777)                               // randomness, commit_slot
        buf.put(ints(4, 10, 35, 78, 83)).put(3)                           // winning, status = Settled
        buf.put(ByteArray(32)).putInt(1).put(255.toByte())                // creator, open_tickets, bump
        val r = DrawRound.decode(buf.array())
        assertEquals(42L, r.id)
        assertEquals(4_500_000L, r.share)
        assertEquals(listOf(4, 10, 35, 78, 83), r.winning)
        assertEquals(RoundStatus.Settled, r.status)
        assertEquals(1L, r.openTickets)
    }

    @Test
    fun programErrorsAreReadFromEveryFailureShape() {
        assertEquals(DrawError.TicketLimit, DrawError.from("transaction failed: {\"InstructionError\":[0,{\"Custom\":6008}]}"))
        assertEquals(DrawError.EntriesClosed, DrawError.from("Transaction simulation failed: custom program error: 0x1774"))
        assertEquals(DrawError.UnclaimedPrize, DrawError.from("AnchorError ... Error Code: UnclaimedPrize"))
        assertEquals(DrawError.NotUpgradeAuthority, DrawError.from("{\"Custom\":6022}"))
        assertNull(DrawError.from("{\"Custom\":1}"))
        assertNull(DrawError.from("Blockhash not found"))
    }

    /** A real Seeker Genesis Token mint from mainnet (same fixture as the program's test). */
    @Test
    fun recognisesARealSgtFromMainnet() {
        val fixture = javaClass.classLoader!!.getResource("sgt-member-mint.json")!!.readText()
        fun field(name: String) = Regex("\"$name\":\\s*\"([^\"]+)\"").find(fixture)!!.groupValues[1]
        val mint = SolanaPublicKey.from(field("mint"))
        val data = Base64.getDecoder().decode(field("data"))
        assertTrue(Sgt.isSeekerGenesisToken(mint, data))
        assertFalse(Sgt.isSeekerGenesisToken(DrawProgram.PROGRAM_ID, data))
        assertNull(Sgt.memberGroup(mint, ByteArray(82)))
    }
}
