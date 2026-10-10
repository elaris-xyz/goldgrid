// Runs the draw for every round that has passed its draw time and is not
// finished yet, and closes finished rounds. Permissionless: anyone may run this.
// It runs on GitHub Actions (.github/workflows/crank.yml) and may run anywhere else too.
// Loops until stopped; `--once` does a
// single pass.
import { chainNow, connect, crankRound, keypairFromJson, loadKeypair, log, oracleHealth, switchboardProgram } from "./lib.mjs";

// CI passes the crank's own key as a secret; locally it is a file. Never the
// program's upgrade authority: a crank key only pays fees and can do nothing else.
const payer = process.env.CRANK_SECRET
  ? keypairFromJson(process.env.CRANK_SECRET)
  : loadKeypair(process.env.CRANK_KEYPAIR ?? new URL("../keys/crank.json", import.meta.url));
const { connection, program } = connect(payer);
const once = process.argv.includes("--once");
// `--for=SECONDS` stops after that long, so a CI job ends inside its time limit
// and the next queued run takes over.
const stopAt = Date.now() + 1000 * Number(process.argv.find((a) => a.startsWith("--for="))?.slice(6) ?? Infinity);

// Below this the crank stops committing (a commit and its reveal cost about
// 0.006 SOL) and the run ends red, so GitHub tells the owner to top it up.
const LOW_SOL = 0.05e9;
let lowSol = false;
let lastOracleNote = 0;

async function pass() {
  const now = await chainNow(connection);
  const balance = await connection.getBalance(payer.publicKey);
  if (balance < LOW_SOL) {
    if (!lowSol) log(`CRANK IS OUT OF SOL: ${balance / 1e9} SOL on ${payer.publicKey.toBase58()}; draws are paused until it is funded`);
    lowSol = true;
  } else lowSol = false;
  const health = await oracleHealth(program, await switchboardProgram());
  if (health.live === 0 && Date.now() - lastOracleNote > 600_000) {
    lastOracleNote = Date.now();
    log(`Switchboard has no live oracle (${health.oracles} on the queue, freshest heartbeat ${Math.round(health.freshestHeartbeatSecs / 60)} min ago); draws wait without spending`);
  }
  // Out of SOL: only the free work (scoring and payouts need fees too, so nothing).
  if (lowSol) return;
  // Every Round account, not a window of recent ids: a round the crank missed
  // while it was down still holds a pot and tickets waiting for a result.
  const rounds = await program.account.round.all();
  const pending = rounds
    .map((r) => r.account)
    // Each round carries its own draw time: a schedule change must not move it.
    .filter((r) => now >= r.drawTs.toNumber())
    // Settled rounds stay on the list until every ticket is paid or returned and
    // the round itself is closed.
    .sort((a, b) => a.id.toNumber() - b.id.toNumber());
  for (const r of pending) {
    const id = r.id.toNumber();
    try {
      log(`round ${id}: ${r.tickets} tickets, status ${Object.keys(r.status)[0]}`);
      const done = await crankRound(program, payer, id, { health });
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
  if (once || Date.now() >= stopAt) break;
  await new Promise((r) => setTimeout(r, 10_000));
}
// A red run is the alarm: GitHub emails the repository owner about a failed workflow.
if (lowSol) {
  log("ending red: the crank key needs SOL");
  process.exitCode = 1;
}
