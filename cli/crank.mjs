// Runs the draw for every round that has passed its draw time and is not
// finished yet, and closes finished rounds. Permissionless: anyone may run this,
// the app does not crank yet, so this must be running for rounds to finish.
// Loops until stopped; `--once` does a
// single pass.
import { chainNow, connect, configPda, crankRound, loadKeypair, log, roundTimes } from "./lib.mjs";

const payer = loadKeypair(process.env.CRANK_KEYPAIR ?? new URL("../spikes/switchboard-devnet/payer.json", import.meta.url));
const { connection, program } = connect(payer);
const once = process.argv.includes("--once");

async function pass() {
  const config = await program.account.config.fetch(configPda());
  const now = await chainNow(connection);
  // Every Round account, not a window of recent ids: a round the crank missed
  // while it was down still holds a pot and tickets waiting for a result.
  const rounds = await program.account.round.all();
  const pending = rounds
    .map((r) => r.account)
    .filter((r) => now >= roundTimes(config, r.id.toNumber()).draw)
    .filter((r) => !("settled" in r.status) || r.openTickets === 0)
    .sort((a, b) => a.id.toNumber() - b.id.toNumber());
  for (const r of pending) {
    const id = r.id.toNumber();
    try {
      log(`round ${id}: ${r.tickets} tickets, status ${Object.keys(r.status)[0]}`);
      const done = await crankRound(program, payer, id);
      if ("settled" in done.status) log(`round ${id}: winning ${done.winning.join(" ")}, best ${done.best}, winners ${done.winners}`);
    } catch (e) {
      log(`round ${id} failed: ${(e.message ?? String(e)).slice(0, 200)}`);
    }
  }
}

log("crank for", program.programId.toBase58(), "as", payer.publicKey.toBase58());
for (;;) {
  try {
    await pass();
  } catch (e) {
    log("pass failed:", (e.message ?? String(e)).slice(0, 200));
  }
  if (once) break;
  await new Promise((r) => setTimeout(r, 10_000));
}
