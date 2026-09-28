// Retimes future rounds: `node set-schedule.mjs 600 540` gives ten-minute rounds with
// entries open for nine. The first new round starts now and is numbered after every
// round the old schedule opened; the program refuses anything that reuses an id.
import anchor from "@coral-xyz/anchor";
import { chainNow, configPda, connect, currentRound, loadKeypair, log } from "./lib.mjs";

const { BN } = anchor;

const [roundSecs, entrySecs] = process.argv.slice(2).map(Number);
if (!(roundSecs > 0 && entrySecs > 0 && entrySecs < roundSecs)) {
  console.error("usage: node set-schedule.mjs <round_secs> <entry_secs>");
  process.exit(1);
}
const admin = loadKeypair(new URL("../spikes/switchboard-devnet/payer.json", import.meta.url));
const { connection, program } = connect(admin);
const before = await program.account.config.fetch(configPda());
const now = await chainNow(connection);
const next = currentRound(before, now) + 1;
const genesis = now - next * roundSecs;
await program.methods
  .setSchedule(new BN(genesis), new BN(roundSecs), new BN(entrySecs))
  .accountsPartial({ admin: admin.publicKey, config: configPda() })
  .rpc();
const after = await program.account.config.fetch(configPda());
log(`round ${next} starts now: ${after.roundSecs} s rounds, entries open ${after.entrySecs} s`);
