// Compares the app's RPC (rpc.url in android/local.properties, as the APK uses)
// with public devnet every 10 s: the chain clock and slot each one answers with,
// and errors. A node that has fallen behind shows up as a large clock gap.
//   node rpc-monitor.mjs <minutes>
import { Connection, SYSVAR_CLOCK_PUBKEY } from "@solana/web3.js";
import { RPC } from "./lib.mjs";

const minutes = Number(process.argv[2] ?? 15);
const ends = { app: RPC, public: "https://api.devnet.solana.com" };
const stats = { samples: 0, maxGap: 0, errors: { app: 0, public: 0 } };
const until = Date.now() + minutes * 60_000;
while (Date.now() < until) {
  const read = {};
  for (const [name, url] of Object.entries(ends)) {
    try {
      const info = await new Connection(url, "confirmed").getAccountInfo(SYSVAR_CLOCK_PUBKEY);
      read[name] = { slot: Number(info.data.readBigUInt64LE(0)), clock: Number(info.data.readBigInt64LE(32)) };
    } catch (e) {
      stats.errors[name]++;
      console.log(new Date().toISOString().slice(11, 19), name, "error", String(e.message).slice(0, 80));
    }
  }
  if (read.app && read.public) {
    stats.samples++;
    const gap = read.public.clock - read.app.clock;
    if (Math.abs(gap) > Math.abs(stats.maxGap)) stats.maxGap = gap;
    if (Math.abs(gap) > 30) console.log(new Date().toISOString().slice(11, 19), `APP RPC ${gap}s behind public (slots ${read.app.slot} vs ${read.public.slot})`);
  }
  await new Promise((r) => setTimeout(r, 10_000));
}
console.log(`${stats.samples} samples; largest clock gap app vs public ${stats.maxGap}s; errors app ${stats.errors.app}, public ${stats.errors.public}`);
