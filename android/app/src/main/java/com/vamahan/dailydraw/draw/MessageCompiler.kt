package com.vamahan.dailydraw.draw

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Instruction
import com.solana.transaction.LegacyMessage
import com.solana.transaction.Message
import com.solana.transaction.TransactionInstruction

/**
 * Compiles instructions into a legacy message. web3-solana's Message.Builder
 * miscounts read-only accounts when one key appears twice with different flags
 * (demo mode passes the wallet as both `player` and `identity`): it marked the
 * ticket read-only, and the program's CPI creating it failed with
 * PrivilegeEscalation. Here each key appears once with its flags OR-ed together.
 */
object MessageCompiler {
    private class Flags(var signer: Boolean, var writable: Boolean)

    fun compile(payer: SolanaPublicKey, instructions: List<TransactionInstruction>, blockhash: String): Message {
        val flags = LinkedHashMap<String, Flags>()
        fun add(key: SolanaPublicKey, signer: Boolean, writable: Boolean) {
            val f = flags.getOrPut(key.base58()) { Flags(false, false) }
            f.signer = f.signer || signer
            f.writable = f.writable || writable
        }
        add(payer, signer = true, writable = true)
        for (ix in instructions) {
            ix.accounts.forEach { add(it.publicKey, it.isSigner, it.isWritable) }
            add(ix.programId, signer = false, writable = false)
        }
        val payerKey = payer.base58()
        // Runtime order: writable signers (payer first), read-only signers,
        // writable non-signers, read-only non-signers.
        val ordered = flags.entries.sortedWith(
            compareBy({ it.key != payerKey }, { !it.value.signer }, { !it.value.writable }),
        )
        val index = ordered.withIndex().associate { (i, e) -> e.key to i }
        val compiled = instructions.map { ix ->
            Instruction(
                index.getValue(ix.programId.base58()).toUByte(),
                ix.accounts.map { index.getValue(it.publicKey.base58()).toByte() }.toByteArray(),
                ix.data,
            )
        }
        return LegacyMessage(
            ordered.count { it.value.signer }.toUByte(),
            ordered.count { it.value.signer && !it.value.writable }.toUByte(),
            ordered.count { !it.value.signer && !it.value.writable }.toUByte(),
            ordered.map { SolanaPublicKey.from(it.key) },
            SolanaPublicKey.from(blockhash),
            compiled,
        )
    }
}
