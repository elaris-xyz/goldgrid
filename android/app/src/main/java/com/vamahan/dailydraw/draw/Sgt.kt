package com.vamahan.dailydraw.draw

import com.solana.publickey.SolanaPublicKey

/**
 * The Seeker Genesis Token check, mirroring programs/daily_draw/src/sgt.rs: a
 * genuine SGT is a Token-2022 mint whose TokenGroupMember extension names this
 * mint and the SGT group. Only the group's update authority can add a member,
 * so unlike a metadata pointer, the group field cannot be forged.
 */
object Sgt {
    private const val ACCOUNT_TYPE_OFFSET = 165
    private const val ACCOUNT_TYPE_MINT = 1
    private const val EXTENSION_UNINITIALIZED = 0
    private const val EXTENSION_TOKEN_GROUP_MEMBER = 23
    private const val GROUP_MEMBER_LEN = 72

    private fun u16(data: ByteArray, at: Int) = (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8)

    /** The group this Token-2022 mint belongs to, or null if it is not a group member. */
    fun memberGroup(mint: SolanaPublicKey, data: ByteArray): SolanaPublicKey? {
        if (data.size <= ACCOUNT_TYPE_OFFSET || (data[ACCOUNT_TYPE_OFFSET].toInt() and 0xff) != ACCOUNT_TYPE_MINT) return null
        var at = ACCOUNT_TYPE_OFFSET + 1
        while (at + 4 <= data.size) {
            val kind = u16(data, at)
            val len = u16(data, at + 2)
            val start = at + 4
            if (start + len > data.size || kind == EXTENSION_UNINITIALIZED) return null
            if (kind == EXTENSION_TOKEN_GROUP_MEMBER && len == GROUP_MEMBER_LEN) {
                val memberMint = SolanaPublicKey(data.copyOfRange(start, start + 32))
                val group = SolanaPublicKey(data.copyOfRange(start + 32, start + 64))
                return if (memberMint.bytes.contentEquals(mint.bytes)) group else null
            }
            at = start + len
        }
        return null
    }

    fun isSeekerGenesisToken(mint: SolanaPublicKey, data: ByteArray): Boolean =
        memberGroup(mint, data)?.bytes?.contentEquals(DrawProgram.SGT_GROUP.bytes) == true
}
