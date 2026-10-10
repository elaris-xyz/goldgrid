// Simulates (never sends) the crank's commit for one round with the crank's
// current randomness account, and prints why it fails:
//   node simulate-commit.mjs <round_id>
import * as sb from "@switchboard-xyz/on-demand";
import anchor from "@coral-xyz/anchor";
const { BN } = anchor;
import { PublicKey, TransactionMessage, VersionedTransaction, ComputeBudgetProgram } from "@solana/web3.js";
import { connect, loadKeypair } from "./lib.mjs";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const id = Number(process.argv[2]);
const crank = loadKeypair(fileURLToPath(new URL("../keys/crank.json", import.meta.url)));
const rkp = loadKeypair(fileURLToPath(new URL("../keys/crank-randomness.json", import.meta.url)));
const { connection, program } = connect(crank);
const [cfg] = await program.account.config.all();
const config = cfg.account;
const sbProgram = await sb.AnchorUtils.loadProgramFromConnection(connection);
const randomness = new sb.Randomness(sbProgram, rkp.publicKey);
const info = await connection.getAccountInfo(rkp.publicKey);
console.log("randomness", rkp.publicKey.toBase58(), info ? `exists, owner ${info.owner.toBase58().slice(0, 8)}` : "MISSING");
if (info) {
  const d = await randomness.loadData();
  console.log("seedSlot", String(d.seedSlot), "revealSlot", String(d.revealSlot), "queue", d.queue.toBase58().slice(0, 8), "config queue", config.sbQueue.toBase58().slice(0, 8));
}
const commitIx = await randomness.commitIx(config.sbQueue, crank.publicKey);
const ours = await program.methods.commitDraw(new BN(id)).accountsPartial({ randomness: rkp.publicKey }).instruction();
const { blockhash } = await connection.getLatestBlockhash();
const msg = new TransactionMessage({
  payerKey: crank.publicKey,
  recentBlockhash: blockhash,
  instructions: [ComputeBudgetProgram.setComputeUnitLimit({ units: 400_000 }), commitIx, ours],
}).compileToV0Message();
const sim = await connection.simulateTransaction(new VersionedTransaction(msg), { sigVerify: false, replaceRecentBlockhash: true });
console.log("err", JSON.stringify(sim.value.err));
console.log((sim.value.logs ?? []).slice(-14).join("\n"));
