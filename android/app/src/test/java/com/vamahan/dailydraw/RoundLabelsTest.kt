package com.vamahan.dailydraw

import com.vamahan.dailydraw.draw.DrawConfig
import com.vamahan.dailydraw.draw.DrawProgram
import org.junit.Assert.assertEquals
import org.junit.Test

/** What players read about a round: its date-and-number name, and whether they can still enter. */
class RoundLabelsTest {
    // 2026-09-30 00:00:00 UTC, five-minute rounds, entries close 20 s before the draw.
    private val midnight = java.time.LocalDate.of(2026, 9, 30).toEpochDay() * 86_400L
    private val config = DrawConfig(
        DrawProgram.PROGRAM_ID, genesisTs = midnight, roundSecs = 300, entrySecs = 280,
        perTicketBonus = 0, sponsorBudget = 0, carry = 0, requireSgt = false,
    )

    @Test
    fun roundsAreNamedByDateAndNumberThatDay() {
        assertEquals("2026-09-30 · #1", config.labelOf(0))
        assertEquals("2026-09-30 · #50", config.labelOf(49))
        assertEquals("2026-09-30 · #288", config.labelOf(287))
        assertEquals("2026-10-01 · #1", config.labelOf(288))
        assertEquals(50L, config.dayNumberOf(49))
    }

    @Test
    fun aRoundFromAnOlderScheduleIsNamedByItsOwnDrawTime() {
        // Round 49 under today's schedule draws at 04:10; a draw time that does not
        // fit today's schedule belongs to an older one.
        assertEquals("2026-09-30 · #50", config.labelFor(49, config.drawTs(49)))
        assertEquals("2026-09-29 · 21:40 draw", config.labelFor(49, midnight - 8_400))
        assertEquals("Round 7", null.labelFor(7, 0))
    }

    @Test
    fun lastCallComesBeforeEntriesCloseAndClosedLastsUntilTheDraw() {
        val close = config.closeTs(10)
        assertEquals(Phase.Open, config.phaseOf(10, close - LAST_CALL_SECS - 1))
        assertEquals(Phase.LastCall, config.phaseOf(10, close - LAST_CALL_SECS))
        assertEquals(Phase.LastCall, config.phaseOf(10, close - 1))
        assertEquals(Phase.Closed, config.phaseOf(10, close))
        assertEquals(Phase.Closed, config.phaseOf(10, config.drawTs(10) - 1))
    }
}
