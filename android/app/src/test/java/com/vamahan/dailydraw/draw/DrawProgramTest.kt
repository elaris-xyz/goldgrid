package com.vamahan.dailydraw.draw

import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The app hand-encodes Anchor instructions; these pin them to the program's IDL. */
class DrawProgramTest {
    private fun ints(vararg b: Int) = b.map { it.toByte() }.toByteArray()

    @Test
    fun instructionDiscriminatorsMatchTheIdl() {
        assertArrayEquals(ints(139, 49, 209, 114, 88, 91, 77, 134), DrawProgram.discriminator("global", "enter"))
        assertArrayEquals(ints(62, 198, 214, 193, 213, 159, 108, 210), DrawProgram.discriminator("global", "claim"))
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
        assertEquals("Hg5MZ2u7YyAX3tQG7whxJtZJnHVUjz7dvmgpRHPKUGBe", DrawProgram.config().base58())
        assertEquals("GsQR2XDbsyxCB5zxL9PwaoQjNsLBA1QqkZ2iN4vdQjFF", DrawProgram.vault().base58())
        assertEquals("AbTh231JeT1sqR3c8kH7HWaMVUBZJG2R56o1rX3oy4NB", DrawProgram.round(3).base58())
        assertEquals("5wd6jhkbPAwyYySjSSssAM2df6fMBDW24VxgHZM2Vvmq", DrawProgram.seeker(who).base58())
        assertEquals("982btUfupYdJCXvDesNXrMbVUpZCNfRvqHyye9SC6R5N", DrawProgram.ticket(3, who, 1).base58())
    }

    @Test
    fun roundTimesFollowTheConfig() {
        val c = DrawConfig(DrawProgram.PROGRAM_ID, genesisTs = 1000, roundSecs = 120, entrySecs = 90, perTicketBonus = 0, sponsorBudget = 0, carry = 0, requireSgt = false)
        assertEquals(0L, c.roundAt(1119))
        assertEquals(1L, c.roundAt(1120))
        assertEquals(1090L, c.closeTs(0))
        assertEquals(1120L, c.drawTs(0))
    }
}
