// Attacks on the ORAO draw, against the live devnet program. Each attack is a
// simulation that must be refused with the named error; then the round is drawn
// for real. Pause the hosted crank first (gh workflow disable crank.yml).
//   node orao-security-test.mjs
import anchor from "@coral-xyz/anchor";
import { ComputeBudgetProgram, TransactionMessage, VersionedTransaction } from "@solana/web3.js";
import { randomBytes } from "node:crypto";
import { fileURLToPath } from "node:url";
import {
  chainNow, commitRound, connect, currentRound, loadKeypair, log, oraoRequestIx, oraoRequestPda, oraoState,
  revealRound, roundPda, roundTimes, send, sleepUntilChain, ticketPda, REVEAL_TIMEOUT_SLOTS,
} from "./lib.mjs";

const { BN } = anchor;
const admin = loadKeypair(fileURLToPath(new URL("../spikes/switchboard-devnet/payer.json", import.meta.url)));
const { connection, program } = connect(admin);
const [{ account: config }] = await program.account.config.all();
let failures = 0;

async function expectRefused(label, ixs, code) {
  const { blockhash } = await connection.getLatestBlockhash();
  const msg = new TransactionMessage({
    payerKey: admin.publicKey, recentBlockhash: blockhash,
    instructions: [ComputeBudgetProgram.setComputeUnitLimit({ units: 400_000 }), ...ixs],
  }).compileToV0Message();
  const sim = await connection.simulateTransaction(new VersionedTransaction(msg), { sigVerify: false, replaceRecentBlockhash: true });
  const logs = (sim.value.logs ?? []).join("\n");
  const ok = sim.value.err && logs.includes(code);
  if (!ok) failures++;
  log(`${ok ? "PASS" : "FAIL"} ${label} -> ${ok ? code : JSON.stringify(sim.value.err) + "\n" + logs.slice(-600)}`);
}

// A request that ORAO has already answered.
const oldSeed = randomBytes(32);
await send(connection, [await oraoRequestIx(connection, admin.publicKey, oldSeed)], [admin], "an ORAO request made early");
for (let i = 0; i < 20 && !(await oraoState(connection, oraoRequestPda(oldSeed))).fulfilled; i++) await new Promise((r) => setTimeout(r, 3000));
log("early request fulfilled:", (await oraoState(connection, oraoRequestPda(oldSeed))).fulfilled);

// One ticket in a round, so the round exists; `--round=N` reuses an open round we
// already entered (a run cut short by the network).
const given = process.argv.find((a) => a.startsWith("--round="));
let id;
if (given) {
  id = Number(given.slice(8));
  log(`reusing round ${id}`);
} else {
  id = currentRound(config, await chainNow(connection));
  if (roundTimes(config, id).close - (await chainNow(connection)) < 30) {
    await sleepUntilChain(connection, roundTimes(config, id).draw);
    id = currentRound(config, await chainNow(connection));
  }
  await program.methods.enter(new BN(id), 0, [1, 2, 3, 4, 5])
    .accountsPartial({ player: admin.publicKey, identity: admin.publicKey, sgtTokens: null, round: roundPda(id), ticket: ticketPda(id, admin.publicKey, 0) })
    .rpc();
  log(`entered round ${id}; waiting for its draw time`);
}
await sleepUntilChain(connection, roundTimes(config, id).draw);

const commitIx = (request, previous) => {
  let b = program.methods.commitDraw(new BN(id)).accountsPartial({ randomness: request });
  if (previous) b = b.remainingAccounts([{ pubkey: previous, isSigner: false, isWritable: false }]);
  return b.instruction();
};

// 1. Committing a request whose answer is already known.
await expectRefused("commit an already-answered request", [await commitIx(oraoRequestPda(oldSeed))], "StaleCommit");

// The real commit.
await commitRound(program, admin, id);
const committed = (await program.account.round.fetch(roundPda(id))).randomness;
for (let i = 0; i < 20 && !(await oraoState(connection, committed)).fulfilled; i++) await new Promise((r) => setTimeout(r, 3000));

// 2. Revealing with a different (answered) request.
await expectRefused("reveal from another request", [
  await program.methods.revealDraw(new BN(id)).accountsPartial({ randomness: oraoRequestPda(oldSeed) }).instruction(),
], "BadRandomness");

// 3. Replacing the committed request before the timeout.
const seedA = randomBytes(32);
await expectRefused("re-commit before the timeout", [
  await oraoRequestIx(connection, admin.publicKey, seedA), await commitIx(oraoRequestPda(seedA), committed),
], "RevealPending");

// 4. Replacing an answered request after the timeout: the re-roll.
const round = await program.account.round.fetch(roundPda(id));
const readyAt = round.commitSlot.toNumber() + REVEAL_TIMEOUT_SLOTS + 2;
log(`waiting for slot ${readyAt} (the re-commit timeout)`);
while ((await connection.getSlot("confirmed")) < readyAt) await new Promise((r) => setTimeout(r, 5000));
const seedB = randomBytes(32);
await expectRefused("re-roll an answered draw after the timeout", [
  await oraoRequestIx(connection, admin.publicKey, seedB), await commitIx(oraoRequestPda(seedB), committed),
], "RandomnessReady");

// The honest reveal.
await revealRound(program, admin, id, committed);
const done = await program.account.round.fetch(roundPda(id));
log(`round ${id} revealed: ${done.winning.join(" ")}; status ${Object.keys(done.status)[0]}`);
log(failures ? `${failures} FAILED` : "all attacks refused");
process.exit(failures ? 1 : 0);
