package com.vamahan.dailydraw.draw

import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The enter transaction as the wallet will see it. */
class EnterMessageTest {
    private val player = SolanaPublicKey.from("CBCxy5PknwBZK8awoc3y54r5TTqAZEB7RYrGEtWHh3iL")

    /** The compiler the app sends with; web3-solana's Builder got the flags wrong. */
    private fun build(ix: com.solana.transaction.TransactionInstruction) =
        MessageCompiler.compile(player, listOf(ix), "11111111111111111111111111111111")

    /** Demo mode: the wallet is both `player` (signer) and `identity`, so it
     * must appear once, as the fee-paying signer. */
    @Test
    fun demoEntryHasOneSignerAndNoDuplicateKeys() = runBlocking {
        val message = build(DrawProgram.enter(player, player, null, 3, 0, listOf(1, 2, 3, 4, 5)))
        val keys = message.accounts.map { it.base58() }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(player.base58(), keys.first())
        assertEquals(1, message.signatureCount.toInt())
    }

    /** The player pays for the ticket and seeker accounts through a CPI, so it
     * must stay a WRITABLE signer even though the same key is also passed as the
     * read-only identity. The wallet rejected this with PrivilegeEscalation. */
    @Test
    fun demoEntryKeepsThePlayerWritable() = runBlocking {
        val bytes = build(DrawProgram.enter(player, player, null, 3, 0, listOf(1, 2, 3, 4, 5))).serialize()
        val (required, readonlySigned) = bytes[0].toInt() to bytes[1].toInt()
        assertEquals(1, required)
        assertEquals("the fee payer must be writable", 0, readonlySigned)
    }

    /** Every account the program writes must be writable in the compiled message:
     * the wallet's copy had the ticket read-only and the CPI creating it failed. */
    @Test
    fun demoEntryKeepsEveryWrittenAccountWritable() = runBlocking {
        val message = build(DrawProgram.enter(player, player, null, 3, 0, listOf(1, 2, 3, 4, 5)))
        val bytes = message.serialize()
        val keys = message.accounts.map { it.base58() }
        val firstReadonly = keys.size - bytes[2].toInt()
        for (written in listOf(DrawProgram.config(), DrawProgram.round(3), DrawProgram.seeker(player), DrawProgram.ticket(3, player, 0))) {
            val i = keys.indexOf(written.base58())
            assertTrue("${written.base58()} at $i of $keys must be writable (readonly from $firstReadonly)", i in 1 until firstReadonly)
        }
    }

    /** SGT mode: the SGT mint is the identity and its token account is passed;
     * seeker and ticket PDAs come from the mint, not the wallet. */
    @Test
    fun seekerEntryUsesTheSgtMintAsIdentity() = runBlocking {
        val sgtMint = SolanaPublicKey.from("3SSQo28JgsmT3EHhSSBHTNGCRvEApRHRT27eCdzjxk9q")
        val sgtTokens = SolanaPublicKey.from("E5e7uixCyyXAxTxUzLxm59KWKwqF1qKQFkey8pPyiYoh")
        val ix = DrawProgram.enter(player, sgtMint, sgtTokens, 3, 0, listOf(1, 2, 3, 4, 5))
        val metas = ix.accounts.map { it.publicKey.base58() }
        assertEquals(sgtMint.base58(), metas[2])
        assertEquals(sgtTokens.base58(), metas[3])
        assertEquals(DrawProgram.seeker(sgtMint).base58(), metas[5])
        assertEquals(DrawProgram.ticket(3, sgtMint, 0).base58(), metas[6])
        val message = build(ix)
        assertEquals(1, message.signatureCount.toInt())
        assertTrue(message.accounts.first().base58() == player.base58())
    }
}
