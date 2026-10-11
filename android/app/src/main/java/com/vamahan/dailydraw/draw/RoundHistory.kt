package com.vamahan.dailydraw.draw

import java.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * A round's result read back from its transaction history, for a ticket whose
 * accounts are already gone: the crank pays, returns the deposit and closes the
 * round an hour after the draw, and an app that missed that hour (offline, or a
 * wrong clock) used to show "Drawing" forever. The program's events stay in the
 * round address's history for good.
 */
data class RoundOutcome(val winning: List<Int>, val best: Int, val winners: Long, val share: Long)

object RoundHistory {
    private fun discriminator(event: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("event:$event".toByteArray()).copyOf(8)

    private val REVEALED = discriminator("DrawRevealed")
    private val SETTLED = discriminator("RoundSettled")

    /** Reads DrawRevealed and RoundSettled for `round` out of transaction logs. */
    fun outcome(round: Long, logs: List<List<String>>): RoundOutcome? {
        var winning: List<Int>? = null
        var settled: Triple<Int, Long, Long>? = null
        for (line in logs.flatten()) {
            if (!line.startsWith("Program data: ")) continue
            val data = runCatching { Base64.getDecoder().decode(line.removePrefix("Program data: ")) }.getOrNull() ?: continue
            if (data.size < 16) continue
            val head = data.copyOf(8)
            val buf = ByteBuffer.wrap(data, 8, data.size - 8).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.long != round) continue
            when {
                // DrawRevealed { round: u64, winning: [u8; 5], tickets: u32, pot: u64 }
                head.contentEquals(REVEALED) && data.size >= 16 + 5 ->
                    winning = (0 until 5).map { buf.get().toInt() and 0xff }
                // RoundSettled { round: u64, best: u8, winners: u32, share: u64 }
                head.contentEquals(SETTLED) && data.size >= 16 + 1 + 4 + 8 ->
                    settled = Triple(buf.get().toInt() and 0xff, buf.int.toLong() and 0xffffffffL, buf.long)
            }
        }
        val w = winning ?: return null
        val (best, winners, share) = settled ?: return null
        return RoundOutcome(w, best, winners, share)
    }
}
