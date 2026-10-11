package com.vamahan.dailydraw

import com.vamahan.dailydraw.draw.RoundHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failures of 2026-10-11, as tests: a phone whose wall clock jumped showed a
 * drawn round as "Draw in 197:03", and tickets whose round closed while the app
 * was not looking stayed "Draw delayed" for hours.
 */
class ChainTimeTest {
    private val chain = 1_791_745_500L // 2026-10-11 09:45:00 UTC, round #117's draw

    @Test
    fun anAnchoredClockIgnoresTheWallClock() {
        val monoAtRead = 1_000_000L
        // One minute after the reading, whatever the wall clock says.
        for (wall in listOf(chain + 60, chain + 60 + 11_820, chain + 60 - 11_820, 0L)) {
            assertEquals(chain + 60, ChainTime.now(chain, monoAtRead, wallOffset = 0, wallSecs = wall, monoMs = monoAtRead + 60_000))
        }
    }

    @Test
    fun theOldWallClockOffsetIsWhatBrokeAndIsOnlyUsedUntilTheFirstReading() {
        // The offset was saved while the wall clock ran 3 h 17 min fast; then the
        // phone corrected its wall clock. Before the first reading, that estimate is
        // 197 minutes behind...
        val savedOffset = -11_820L
        val unanchored = ChainTime.now(0, 0, savedOffset, wallSecs = chain, monoMs = 5_000)
        assertEquals(chain - 11_820, unanchored)
        // ...and the first reading of this process is always taken, however far it moves.
        assertTrue(ChainTime.accepts(chain, anchored = false, estimate = unanchored))
    }

    @Test
    fun aReadingFromANodeThatFellBehindIsRefused() {
        assertFalse(ChainTime.accepts(chain - 11_820, anchored = true, estimate = chain))
        assertFalse(ChainTime.accepts(chain - ChainTime.BACKWARDS_SECS - 1, anchored = true, estimate = chain))
    }

    @Test
    fun ordinaryReadingsAreTaken() {
        assertTrue(ChainTime.accepts(chain, anchored = true, estimate = chain))
        assertTrue(ChainTime.accepts(chain - 5, anchored = true, estimate = chain)) // a node a few slots behind
        assertTrue(ChainTime.accepts(chain + 3_600, anchored = true, estimate = chain)) // forward is never a stale node
    }

    // Real log lines from devnet: round 3904 (#117 of 2026-10-11, one winner) and
    // round 3910 (#123, nobody matched).
    private val round3904 = listOf(
        "Program data: 2cB7SGyW+CFADwAAAAAAAIpmt4ecbh2BggtFL4UAKyr0FJIhhJGxGEqiKBmctYqdAAk9AAAAAAA=",
        "Program data: +eFCNp3I6t5ADwAAAAAAAAEBAAAAAAk9AAAAAAA=",
        "Program data: /xtDoAn3W7lADwAAAAAAAAYiJ0ZMAQAAAAAJPQAAAAAA",
        "Program data: 96QV3tuy7aBADwAAAAAAAIpmt4ecbh2BggtFL4UAKyr0FJIhhJGxGEqiKBmctYqdima3h5xuHYGCC0UvhQArKvQUkiGEkbEYSqIoGZy1ip0ABgk2NzkACT0AAAAAAA==",
    )
    private val round3910 = listOf(
        "Program data: +eFCNp3I6t5GDwAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "Program data: /xtDoAn3W7lGDwAAAAAAAAcIFSYoAQAAAEBCDwAAAAAA",
        "Program log: Instruction: ScoreTickets",
    )

    @Test
    fun aMissedResultIsReadBackFromTheRoundsHistory() {
        val won = RoundHistory.outcome(3904, listOf(round3904))!!
        assertEquals(listOf(6, 34, 39, 70, 76), won.winning)
        assertEquals(1, won.best)
        assertEquals(1L, won.winners)
        assertEquals(4_000_000L, won.share)

        val none = RoundHistory.outcome(3910, listOf(round3910))!!
        assertEquals(listOf(7, 8, 21, 38, 40), none.winning)
        assertEquals(0, none.best)
    }

    @Test
    fun anotherRoundsEventsAreNotTakenAndHalfAResultIsNone() {
        assertNull(RoundHistory.outcome(3910, listOf(round3904)))
        assertNull(RoundHistory.outcome(3910, listOf(listOf(round3910[1])))) // revealed, not yet settled
    }
}
