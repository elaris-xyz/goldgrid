// Is Switchboard's devnet queue able to serve randomness right now? Reads the
// queue's oracles from chain (no gateway needed) and asks the gateway too:
//   node sb-health.mjs
import * as sb from "@switchboard-xyz/on-demand";
import { connect, loadKeypair } from "./lib.mjs";
import { fileURLToPath } from "node:url";

const crank = loadKeypair(fileURLToPath(new URL("../keys/crank.json", import.meta.url)));
const { connection, program } = connect(crank);
const [cfg] = await program.account.config.all();
const sbProgram = await sb.AnchorUtils.loadProgramFromConnection(connection);
const queue = new sb.Queue(sbProgram, cfg.account.sbQueue);
const q = await queue.loadData();
const now = Math.floor(Date.now() / 1000);
const keys = q.oracleKeys.slice(0, q.oracleKeysLen ?? q.oracleKeys.length).filter((k) => !k.equals(sb.web3?.PublicKey?.default ?? k));
console.log("queue", cfg.account.sbQueue.toBase58(), "oracles", keys.length);
for (const k of keys) {
  try {
    const o = await new sb.Oracle(sbProgram, k).loadData();
    const hb = Number(o.lastHeartbeat ?? 0);
    console.log(" ", k.toBase58().slice(0, 8), "heartbeat", hb ? `${Math.round((now - hb) / 60)} min ago` : "never",
      "gateway", Buffer.from(o.gatewayUri ?? []).toString().replace(/\0+$/, "").slice(0, 60));
  } catch (e) {
    console.log(" ", k.toBase58().slice(0, 8), "unreadable", String(e.message).slice(0, 80));
  }
}
try {
  const oracles = await queue.fetchHealthyOracles?.();
  console.log("gateway healthy oracles:", oracles?.length ?? "n/a");
} catch (e) {
  console.log("gateway check failed:", String(e.message).slice(0, 120));
}
