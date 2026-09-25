// Runs the draw for every round that has passed its draw time and is not
// settled yet. Permissionless: anyone may run this, and the app will do the same
// work in-app. Loops until stopped; `--once` does a single pass.
import {
  chainNow,
  connect,
  configPda,
  crankRound,
  currentRound,
  loadKeypair,
  log,
  roundPda,
  roundTimes,
} from "./lib.mjs";

const payer = loadKeypair(process.env.CRANK_KEYPAIR ?? new URL("../spikes/switchboard-devnet/payer.json", import.meta.url));
const { connection, program } = connect(payer);
const once = process.argv.includes("--once");
const LOOKBACK = 6; // rounds behind the current one to re-check

async function pass() {
  const config = await program.account.config.fetch(configPda());
  const now = await chainNow(connection);
  const current = currentRound(config, now);
  for (let id = Math.max(0, current - LOOKBACK); id <= current; id++) {
    if (now < roundTimes(config, id).draw) continue;
    const round = await program.account.round.fetchNullable(roundPda(id));
    if (!round || "settled" in round.status) continue;
    log(`round ${id}: ${round.tickets} tickets, status ${Object.keys(round.status)[0]}`);
    const done = await crankRound(program, payer, id);
    log(`round ${id}: winning ${done.winning.join(" ")}, best ${done.best}, winners ${done.winners}`);
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
