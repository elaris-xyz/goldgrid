// Lists every round account still on chain with its status, so a stuck draw is visible:
//   node rounds-status.mjs
import { connect, loadKeypair } from "./lib.mjs";
import { fileURLToPath } from "node:url";

const admin = loadKeypair(fileURLToPath(new URL("../spikes/switchboard-devnet/payer.json", import.meta.url)));
const { connection, program } = connect(admin);
const [config] = await program.account.config.all();
const c = config.account;
const now = await connection.getBlockTime(await connection.getSlot("confirmed"));
const current = Math.floor((now - Number(c.genesisTs)) / Number(c.roundSecs));
console.log("chain time", new Date(now * 1000).toISOString(), "current round", current);
const rounds = await program.account.round.all();
rounds.sort((a, b) => Number(a.account.id) - Number(b.account.id));
for (const { publicKey, account: r } of rounds) {
  const status = Object.keys(r.status)[0];
  console.log(
    String(r.id).padStart(6), status.padEnd(10),
    "tickets", r.tickets, "scored", r.scored, "open", r.openTickets,
    "drawTs", new Date(Number(r.drawTs) * 1000).toISOString().slice(5, 16),
    "commitSlot", String(r.commitSlot), publicKey.toBase58().slice(0, 8),
  );
}
