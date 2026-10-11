package com.vamahan.dailydraw

/**
 * The program's clock on the phone. Rounds open, close and draw by the chain's
 * Clock sysvar, so the app reads it and then counts on from the device's MONOTONIC
 * clock (elapsedRealtime), which nothing moves: not network time, not a time zone,
 * not a VPN toggling. Version 1.0.2 counted from the wall clock instead, and one
 * wall-clock jump on a presenter's phone turned a drawn round into "Draw in 197:03".
 * Pure functions, so the rules are unit-tested without a phone.
 */
object ChainTime {
    /** A reading this far behind the running estimate is a node that fell behind, not time. */
    const val BACKWARDS_SECS = 20L

    /**
     * The chain's time now. Anchored (a reading has been taken in this process):
     * the reading plus monotonic time since. Not yet: the wall clock plus the last
     * saved offset, until the first reading replaces it.
     */
    fun now(chainAnchor: Long, monoAnchor: Long, wallOffset: Long, wallSecs: Long, monoMs: Long): Long =
        if (monoAnchor > 0) chainAnchor + (monoMs - monoAnchor) / 1000 else wallSecs + wallOffset

    /** Whether a fresh clock reading may replace the estimate: the chain clock never runs backwards. */
    fun accepts(reading: Long, anchored: Boolean, estimate: Long): Boolean =
        !anchored || reading >= estimate - BACKWARDS_SECS
}
